package app.myspoti.spotify

import app.myspoti.api.ItemKind
import app.myspoti.api.NowPlaying
import app.myspoti.api.Player
import app.myspoti.api.PlayerState
import app.myspoti.api.Queue
import app.myspoti.api.Repeat
import app.myspoti.api.Uri
import app.myspoti.spotify.bridge.service
import app.myspoti.spotify.bridge.Bridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * The Web Player's own player, driven through the bridge.
 *
 * Ads: Spotify's own, played to their end on this page as Spotify sends them (nothing is
 * muted, skipped or left out here). [adPlaying] says an ad is on; ads are never exposed in
 * [state] (the last song stays there, buffering), and what the user asks for meanwhile (the page
 * refuses every play and skip then, "ad_disallow") is held and played once the ad is over.
 * The ad blocker only acts on a Spotify Connect device this player sends the music to
 * ([app.myspoti.spotify.bridge.AdBlock]).
 *
 * Stalls: on some phones the page stops between two songs (stuck past the end, paused at
 * the end with a next song, buffering for ever). After [STUCK_MS] the next song is played;
 * if that does not help either, [reloadHost] reloads the page and playback resumes there.
 */
class SpotifyPlayer(private val bridge: Bridge, private val scope: CoroutineScope) : Player {
    /** The bridge's clock (epoch ms): a virtual one in tests. */
    private val clock = bridge.clock

    /** True while this account is a member of a Jam: playback stays on the host's device (no transfer here). */
    var inJam: () -> Boolean = { false }
    /** Whether this account is in a Jam at all (host or member): a Jam's list is only played then. */
    var inJamSession: () -> Boolean = { false }

    /** The page's state, as it reports it: what the repairs below judge. */
    private val _state = MutableStateFlow<PlayerState?>(null)
    private val _shown = MutableStateFlow<PlayerState?>(null)
    /**
     * The state the app shows: the page's, with the native engine's own playback laid over it while
     * the engine plays the account's songs ([EngineStates.overlay]): the page only relays that one,
     * seconds late, and with no page up the engine's alone.
     */
    override val state: StateFlow<PlayerState?> = _shown

    /** The page's state, as [state] would be without the engine. */
    val pageState: StateFlow<PlayerState?> = _state

    /** [state] made again from the page's last state and the engine's playback now. */
    private fun publish() {
        _shown.value = EngineStates.overlay(_state.value, audio?.takeIf { engineHolds() && !it.mirrors }?.now?.value)
    }

    /** The page reported (or the player made) [s]: the page's state, and what is shown. */
    private fun setPageState(s: PlayerState?) {
        _state.value = s
        publish()
    }
    private val _queue = MutableStateFlow<Queue?>(null)
    override val queue: StateFlow<Queue?> = _queue

    private var lastTrackState: PlayerState? = null
    private val _adPlaying = MutableStateFlow(false)
    /** An ad is playing (the page plays it to its end). */
    val adPlaying: StateFlow<Boolean> = _adPlaying
    private val _stalled = MutableStateFlow(false)
    /**
     * The state says this phone plays, but its sound stands still: the media element waits for
     * data (a slow network) or is paused. The page's own state often goes on as playing then,
     * its clock running over the silence; the screens stop theirs on this instead.
     */
    val stalled: StateFlow<Boolean> = _stalled
    @Volatile private var adSince = 0L
    /** When the page last reported the ad break (its end can be lost on the way, see [watchStalls]). */
    @Volatile private var adStateAt = 0L
    /** A pause asked through [pause]; it holds until playback is asked for again, never expires. */
    @Volatile private var pausedByUser = false
    /** The order given to the last [play] (context uri to songs), kept for a stall restart. */
    @Volatile private var lastOrder: Pair<Uri, List<Uri>>? = null

    /** The volume the user wants (0..1), put back when the page is left silent by mistake. */
    @Volatile private var wantedVolume: Double? = null
    /** The last [play] sent: (context, track, when), for a refused command's retry. */
    @Volatile private var lastPlay: Triple<Uri, Uri?, Long>? = null
    /** The place in its list [lastPlay]'s song was sent with (see SongPlayer.rowIndex), or null. */
    @Volatile private var lastIndex: Int? = null
    /**
     * A song the user picked: the list, the song (null: the list from its start), when, and the
     * order given with it (a sorted list or a smart shuffle plays as that order, not as the list).
     */
    private data class Pick(val context: Uri, val track: Uri?, val at: Long, val order: List<Uri>?, val positionMs: Long? = null, val index: Int? = null)
    /**
     * The pick an ad came in place of, or one made during the ad (the page refuses every play
     * then): asked again when the ad is over (see [onState]).
     */
    @Volatile private var pickBeforeAd: Pick? = null
    /**
     * The user's last pick around an ad they paused during ([pickBeforeAd] then): nothing plays it
     * before their [resume], which plays it instead of the song before the ad.
     */
    @Volatile private var pickAfterPause: Pick? = null
    /**
     * A pick before an ad whose break ended in a reload of ours, or one made during a reload (see
     * [playAsked]): played on the new page, however long the reload took (it is the user's),
     * unless they played or skipped something since.
     */
    @Volatile private var pickAfterReload: Pick? = null
    /** When the user last played, skipped or picked: what a repair started earlier must not undo. */
    @Volatile private var userMoveAt = 0L
    /** The last play sent was the user's: only its refusal walks [onRefused]'s steps. */
    @Volatile private var lastPlayRefusable = false
    /** How far down [onRefused]'s steps the current play went (0: not refused). */
    @Volatile private var refusalStep = 0
    @Volatile private var refusalTransferring = false
    private val _refused = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /**
     * Spotify refused a play even after the retry (free accounts in some countries can't
     * pick a song): the UI says so.
     */
    val refused: kotlinx.coroutines.flow.SharedFlow<Unit> = _refused
    /** When each kind of command (play, seek_to, transfer...) was last refused. */
    private val refusedKinds = java.util.concurrent.ConcurrentHashMap<String, Long>()
    /** The page was reloaded once this session for an account refused everything. */
    @Volatile private var refusedReloaded = false
    @Volatile private var refusedAllToldAt = 0L
    private val _refusedAll = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /**
     * Spotify refuses this player every kind of command (a play, a seek, a transfer), whichever
     * way the host sent them: the fallbacks are pointless, the UI says so.
     */
    val refusedAll: kotlinx.coroutines.flow.SharedFlow<Unit> = _refusedAll

    private val _drmMissing = kotlinx.coroutines.flow.MutableStateFlow(false)
    /**
     * The WebView has no working Widevine: nothing Spotify streams can play on this device, whatever
     * is tried, and the user has to update or change the WebView (see player-bridge.js).
     */
    val drmMissing: kotlinx.coroutines.flow.StateFlow<Boolean> = _drmMissing

    /** Reloads the Web Player page (set by the host); used when a stalled player does not recover. */
    @Volatile var reloadHost: (suspend () -> Unit)? = null
    /**
     * True while Spotify is limiting this client (a storm of `429` on the audio licences): every
     * repair — a reload, a replay, a seek — asks for licences again and makes the limit last.
     * The watchers stand still until it is over.
     */
    @Volatile var recoveryHeld: () -> Boolean = { false }
    /**
     * True while Spotify refuses this client its audio licences (set by the host): playback is
     * held, and a song the page moves on to by itself meanwhile is paused (see [onState]).
     */
    @Volatile var playbackHeld: () -> Boolean = { false }
    /** The song last paused by the hold: paused once, so a play the user asks for on it stands. */
    @Volatile private var heldPausedUri: Uri? = null
    /** The page was seen paused during the hold: its pause took, no other song is paused by it. */
    @Volatile private var heldPauseTook = false

    /** Audio licences Spotify refused (403) in a row, see [onLicence]. */
    private val licenceRefusals = LicenceRefusals(LICENCE_REFUSALS, LICENCE_REFUSALS_MS)
    /**
     * Spotify refuses this account its audio licences (see [LicenceRefusals]): playback was paused
     * once and the user told, and nothing is repaired (no reload, no replay, no seek: each asks for
     * a licence again) until the user plays something.
     */
    @Volatile private var licenceRefusedHold = false
    /** The song whose own licence was refused (a 403 before it played [PRELOAD_AFTER_MS]), and when. */
    @Volatile private var licenceRefusedUri: Uri? = null
    @Volatile private var licenceRefusedAt = 0L
    /** When the current page said "ready", and how many licence answers it has had, for the log. */
    @Volatile private var pageReadyAt = 0L
    @Volatile private var pageLicences = 0
    private val _licencesRefused = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** Spotify refuses this account its audio licences: playback was paused, the UI says so. */
    val licencesRefused: kotlinx.coroutines.flow.SharedFlow<Unit> = _licencesRefused

    /** True from the pause the refusal made until the user plays: an ad the page is stuck on meanwhile is the refusal's, not an ad. */
    val licencesHeld: Boolean get() = licenceRefusedHold

    /** Repairs wait: held by the host ([recoveryHeld]), or licences refused ([licenceRefusedHold]). */
    private fun repairsHeld(): Boolean = recoveryHeld() || licenceRefusedHold

    /**
     * An audio licence answer the page saw. The first ones of each page are logged with what they
     * were for (one licence answered 200 per page, all others 403: a song's or the certificate?).
     * Refusals in a row ([LicenceRefusals]) pause playback once ([licencesRefused]). Observation
     * only: nothing of the request or its answer is touched.
     */
    private fun onLicence(msg: JsonObject) {
        val url = (msg["url"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull ?: return
        val status = (msg["status"] as? kotlinx.serialization.json.JsonPrimitive)?.intOrNull ?: 0
        val now = clock()
        val s = _state.value
        if (pageLicences++ < PAGE_LICENCES_LOGGED)
            bridge.logger?.invoke("page licence #$pageLicences: $status ${url.substringAfter("widevine-license", url).take(60)} on ${s?.item?.uri ?: "nothing"}, ${if (pageReadyAt > 0) "${now - pageReadyAt} ms after ready" else "page not ready"}")
        // The page's token is stale: not a refusal of the account's licences (see PageAuth).
        if (status == 401) app.myspoti.spotify.bridge.PageAuth.unauthorized("page licence", licence = true, now = clock())
        if (status != 403 || !LicenceRefusals.isAudio(url)) return
        // The page only mirrors a song of an extension, heard from the phone's own player: its
        // licences are not what the user hears. Nor are another device's.
        if (mirroring || remoteActive) return
        licenceRefusedAt = now
        // Before the song played the moment after which the next one is preloaded: its own licence.
        licenceRefusedUri = s?.takeIf { it.positionAt(now) < PRELOAD_AFTER_MS }?.item?.uri
        if (licenceRefusals.record(now)) refusedLicences("403 x$LICENCE_REFUSALS in ${LICENCE_REFUSALS_MS / 1000} s")
    }

    /** Pauses once, tells the user and holds every repair until they play ([licenceRefusedHold]). */
    private fun refusedLicences(why: String) {
        if (licenceRefusedHold) return
        licenceRefusedHold = true
        bridge.logger?.invoke("Spotify refuses this account's audio licences ($why): paused, no reload nor replay until the next play")
        _licencesRefused.tryEmit(Unit)
        scope.launch { runCatching { pause() } }
    }

    /** Reloads for a stale token in a row (see [onAuthStale]), and when the last one began. */
    @Volatile private var authReloads = 0
    @Volatile private var authReloadAt = 0L
    /** The token went stale while nothing played here: the next resume loads the page again first. */
    @Volatile private var authStalePending = false

    /**
     * The page's token is stale ([app.myspoti.spotify.bridge.PageAuth]): every request it makes
     * is answered 401, and the next song's licence too. The page is loaded again (a new page gets
     * a new token) and the song goes on where it was. Nothing plays here: the next resume does it.
     * While the 401s go on (a network that stays broken), the reloads are spaced by [authReloadGap].
     */
    private suspend fun onAuthStale(why: String) {
        if (ownReload || reloadUnderWay || reloadHost == null) return
        val now = clock()
        if (authReloads > 0 && now - authReloadAt < authReloadGap(authReloads)) {
            bridge.logger?.invoke("page token stale ($why): reloaded ${(now - authReloadAt) / 1000} s ago, the next reload waits")
            return
        }
        val playing = lastTrackState?.takeIf { !pausedByUser && !it.isPaused && it.item != null }
        // Another device plays (its own device joined, this one idle), an ad or a mirror is on: their own repairs run.
        if (playing == null || (remoteActive && pageDeviceJoined()) || mirroring || _adPlaying.value) {
            if (!authStalePending) bridge.logger?.invoke("page token stale ($why): the page is loaded again at the next play here")
            authStalePending = true
            return
        }
        authReloads++
        authReloadAt = now
        bridge.logger?.invoke("page token stale ($why): reloading the player (reload $authReloads)")
        reloadAndPlayOn(playing.copy(positionMs = playing.positionAt(now), timestampMs = now), next = false)
    }

    /** The user plays (a pick, a skip, play): repairs may run again. */
    private fun userPlays() {
        if (!licenceRefusedHold) return
        licenceRefusedHold = false
        bridge.logger?.invoke("played after the licences were refused: repairs back on")
    }

    /** Streaming quality ("auto", "low", "normal", "high", "very_high"), kept for every page load. */
    @Volatile var audioQuality: String = "auto"
        private set

    /** A state came from the page: the watcher, when it waits long ([watchStalls]), looks now.
     * Declared before [init], which starts the watcher (a later initializer left it null there). */
    private val watchWake = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)

    init {
        scope.launch {
            bridge.events.collect { (type, payload) ->
                when (type) {
                    "state" -> onState(playerState(payload))
                    "queue" -> queue(payload)?.let { q -> _queue.value = q.copy(nextUp = q.nextUp.filter { it.kind != ItemKind.AD }) }
                    "refused" -> onRefused(
                        late = ((payload as? JsonObject)?.get("late") as? kotlinx.serialization.json.JsonPrimitive)?.booleanOrNull == true,
                        kind = ((payload as? JsonObject)?.get("kind") as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull,
                    )
                    "drm" -> (payload as? JsonObject)?.let { _drmMissing.value = (it["ok"] as? kotlinx.serialization.json.JsonPrimitive)?.booleanOrNull == false }
                    "licence" -> (payload as? JsonObject)?.let { onLicence(it) }
                }
            }
        }
        scope.launch { watchStalls() }
        scope.launch { app.myspoti.spotify.bridge.PageAuth.stale.collect { onAuthStale(it) } }
        // A reloaded page starts on "auto": the chosen quality is set again.
        scope.launch {
            bridge.ready.collect {
                if (!it) {
                    // The page's queue went with it, and the ad due in it.
                    app.myspoti.spotify.bridge.AdSchedule.clear()
                    // The page went away (reloaded by Android, the WebView or the host): what
                    // played then is picked up again once the new page is there.
                    // A page that unloads reports "nothing plays" first: the song before that counts.
                    val now = clock()
                    val last = lastTrackState ?: lastSong?.takeIf { now - lastSongUntil < UNLOAD_NULL_MS }
                    // Another device of the account plays (the PC): that state is its, and the phone never resumes it (#82).
                    if (playingAtLoss == null && !remoteActive) playingAtLoss = last?.takeIf { s -> !s.isPaused && !pausedByUser && s.item != null }
                        ?.let { s -> s.copy(positionMs = s.positionAt(now), timestampMs = now) }
                    return@collect
                }
                pageReadyAt = clock()
                pageLicences = 0
                // A new page has a new token.
                authStalePending = false
                licenceRefusedUri = null
                val lost = playingAtLoss
                playingAtLoss = null
                // Read now: the quiet below waits for the page (0.5 s seen), by when our own reload
                // can be over, and this page was then taken for one that was not ours (log seen: the
                // song before an ad resumed next to the mirror's).
                val own = ownReload
                val mirrored = mirroring
                // A new page forgot it was unheard (a mirror): told again before it plays on.
                if (localMuted) { quietUri = null; applyQuiet() }
                // A pick held over a reload that was not ours, or whose own play did not run: played now.
                when (PageRecoveryRules.pageBack(own, mirrored, pickAfterReload != null, lost != null, liveIntent() != null)) {
                    PageRecoveryRules.PageBack.MIRROR -> {
                        pickAfterReload = null
                        if (lost != null) bridge.logger?.invoke("page back: it mirrors a song played here, ${lost.item?.name} not resumed (the mirror plays its song again)")
                    }
                    PageRecoveryRules.PageBack.PICK -> { val seq = stateSeq; scope.launch { awaitPageSettled(seq); playPickAfterReload() } }
                    PageRecoveryRules.PageBack.RESUME -> scope.launch { runCatching { awaitDeviceJoined(); resumeAfterReload(lost ?: return@runCatching) } }
                    PageRecoveryRules.PageBack.INTENT -> { val seq = stateSeq; scope.launch { runCatching { awaitPageSettled(seq); playIntent() } } }
                    PageRecoveryRules.PageBack.NOTHING -> {}
                }
                repeatRestoreUntil = clock() + REPEAT_RESTORE_MS
                if (streamingLevel != "auto") applyAudioQuality()
                // The page keeps its volume: an app closed during a fade must not stay silent.
                // Only when this page is Connect's active device: the volume is that device's
                // otherwise, and a speaker at 0 (muted for its ad, not reported yet) went to 100 %
                // (#159). A page not active yet is looked after by the guard once it plays here.
                runCatching { if (volume() == 0.0 && ownsConnectVolume(readMedia())) setVolume(wantedVolume?.takeIf { v -> v > 0.0 } ?: 1.0) }
            }
        }
    }

    /** Set while [reloadAndPlayOn] runs: that reload says itself what plays next. */
    @Volatile private var ownReload = false
    /** What was playing when the page went away; null when nothing was. */
    @Volatile private var playingAtLoss: PlayerState? = null
    /** The last state with a song, and when the page stopped reporting it (a null state). */
    @Volatile private var lastSong: PlayerState? = null
    @Volatile private var lastSongUntil = 0L
    /** Bumped at every state the page reports. */
    @Volatile private var stateSeq = 0

    /**
     * Reloads the page, waits for it, then plays from [s]: the song after it ([next], a song
     * stuck at its end or an ad) or [s] itself where it was (a frozen page).
     */
    private suspend fun reloadAndPlayOn(s: PlayerState?, next: Boolean = true) {
        val reload = reloadHost ?: return
        if (repairsHeld()) { bridge.logger?.invoke("reload held: a sign-in page is open or Spotify is limiting or refusing playback"); return }
        val startedAt = clock()
        // A station without an order of ours: what came next on this page, taken before it goes
        // (the new page's is a new station, see RepeatRules.stationOrder).
        val nextUp = s?.takeIf { RepeatRules.isStation(it.contextUri) && !it.shuffle && lastOrder?.first != it.contextUri }
            ?.let { _queue.value?.nextUp }
        // The songs the user queued: the new page has no queue, they are added to it again (#144).
        val queued = _queue.value?.queued.orEmpty().filter { it.kind == ItemKind.TRACK || it.kind == ItemKind.EPISODE }
        ownReload = true
        try {
            runCatching {
                if (!reloadAndWait(reload)) return@runCatching
                val seq = stateSeq
                // The page only mirrors: the mirror plays its song again there, nothing from here.
                if (!PageRecoveryRules.playsAfterOwnReload(mirroring)) {
                    pickAfterReload = null
                    bridge.logger?.invoke("page back after a reload: it mirrors a song played here, the mirror plays it again")
                    return@runCatching
                }
                if (playPickAfterReload()) return@runCatching
                if (s == null) return@runCatching
                if (next) awaitPageSettled(seq)
                // Reported (2.2.0, log seen): a song picked during the reload, then the song after
                // the one before it, in the old list. What the user played or skipped since the
                // reload began is theirs; a skip ([intent]) is played by what follows.
                if (userMoveAt > startedAt && liveIntent() == null) {
                    bridge.logger?.invoke("page back after a reload: played or picked meanwhile, ${s.item?.name} not played on")
                    return@runCatching
                }
                if (next) playOnNewPage { playOn(s, nextUp) } else {
                    val sent = clock()
                    val movedAt = userMoveAt
                    val joined = awaitDeviceJoined()
                    resumeAfterReload(s, nextUp)
                    // Its restore went to a page whose device had not joined: followed as a play on a new page is.
                    if (!joined) lastPlay?.takeIf { it.third >= sent }?.let { p -> scope.launch { playWhenJoined(p, movedAt) } }
                }
            }
            if (queued.isNotEmpty() && PageRecoveryRules.playsAfterOwnReload(mirroring)) runCatching { requeue(queued) }
                .onFailure { bridge.logger?.invoke("page back after a reload: the queue was not added again (${it.message})") }
        } finally {
            ownReload = false
            playingAtLoss = null
        }
    }

    /** The pick held over our reload ([pickAfterReload]), played on the new page. True when there was one. */
    private suspend fun playPickAfterReload(): Boolean {
        val pick = pickAfterReload ?: return false
        pickAfterReload = null
        if (liveIntent() != null) return false
        bridge.logger?.invoke("playing the song picked before the ad or the reload, on the new page: ${pick.track ?: pick.context}")
        playOnNewPage { runCatching { playNow(pick.context, trackUri = pick.track, index = pick.index, positionMs = pick.positionMs, order = pick.order, refusable = true, origin = "pick after reload") } }
        return true
    }

    /**
     * A new page says "ready" before its device has joined Spotify Connect. Reported (2.2.0, log
     * seen): a play sent then was answered "ok" by the page, its Connect command 404
     * DEVICE_NOT_FOUND, the device joining 0.9 s later, and nothing played. Waited for, at most
     * [JOIN_WAIT_MS], when the host reports the joins.
     */
    private suspend fun awaitDeviceJoined(): Boolean {
        val device = app.myspoti.spotify.bridge.PageDevice
        if (!device.reported || device.joined.value) return true
        val started = clock()
        val joined = withTimeoutOrNull(JOIN_WAIT_MS) { device.joined.first { it } } != null
        bridge.logger?.invoke(if (joined) "page device joined after ${clock() - started} ms: playing" else "page device not joined after ${JOIN_WAIT_MS / 1000} s: playing anyway")
        return joined
    }

    /** The page's own device has joined Spotify Connect, or the host does not report joins. */
    private fun pageDeviceJoined(): Boolean =
        app.myspoti.spotify.bridge.PageDevice.let { !it.reported || it.joined.value }

    /**
     * A page just back settles before we play on it: its device has joined Spotify Connect and it
     * has told its first state ([seq]: [stateSeq] when it said ready). That was a fixed
     * [RESTORE_GAP_MS] after every reload (a stuck ad, a stall, a pick made during it), which the
     * join and the page's first state now end sooner; [RESTORE_GAP_MS] stays the longest wait,
     * and the whole wait when the host does not report joins.
     */
    private suspend fun awaitPageSettled(seq: Int) {
        val device = app.myspoti.spotify.bridge.PageDevice
        if (!device.reported) { delay(RESTORE_GAP_MS); return }
        val started = clock()
        withTimeoutOrNull(RESTORE_GAP_MS) {
            device.joined.first { it }
            while (stateSeq == seq) delay(50)
        }
        bridge.logger?.invoke("page back settled after ${clock() - started} ms")
    }

    /**
     * Our play on a page just loaded: sent once its device has joined, and sent again once when
     * Spotify answered DEVICE_NOT_FOUND to it (the page's call itself answers "ok" before that),
     * unless something else was played or skipped meanwhile.
     */
    private suspend fun playOnNewPage(play: suspend () -> Unit) {
        val joined = awaitDeviceJoined()
        val sent = clock()
        val movedAt = userMoveAt
        play()
        val p = lastPlay?.takeIf { it.third >= sent } ?: return
        if (!joined) scope.launch { playWhenJoined(p, movedAt) }
        else unjoinedReloads = 0
        scope.launch {
            val device = app.myspoti.spotify.bridge.PageDevice
            withTimeoutOrNull(DEVICE_LOST_WAIT_MS) { while (device.commandLostAt < sent) delay(100) } ?: return@launch
            if (userMoveAt != movedAt || lastPlay?.third != p.third) return@launch
            bridge.logger?.invoke("play on the new page answered DEVICE_NOT_FOUND: sent again once the device has joined")
            awaitDeviceJoined()
            if (userMoveAt != movedAt || lastPlay?.third != p.third) return@launch
            runCatching {
                playNow(p.first, trackUri = p.second, index = lastIndex, order = lastOrder?.takeIf { it.first == p.first }?.second,
                    refusable = lastPlayRefusable, origin = "sent again after DEVICE_NOT_FOUND")
            }
        }
    }

    /** Pages loaded again in a row because their device never joined (see [playWhenJoined]). */
    @Volatile private var unjoinedReloads = 0

    /**
     * Our play [p] went to a new page whose device had not joined Spotify Connect: the page took it
     * ("ok") and nothing went out. Reported (2.3.0, log seen): a page reloaded to repair a stall
     * never registered its device, and the phone, which was the one playing, stayed silent for
     * 1 h 40 until a network change. The play is sent again once the device joins; if it has not
     * within [UNJOINED_WAIT_MS], the page is loaded again ([UNJOINED_RELOADS] times at most).
     * Never once the user played, picked or paused something else.
     */
    private suspend fun playWhenJoined(p: Triple<Uri, Uri?, Long>, movedAt: Long) {
        val device = app.myspoti.spotify.bridge.PageDevice
        fun ours() = userMoveAt == movedAt && lastPlay?.third == p.third && !pausedByUser
        fun heard() = _state.value?.let { it.item != null && !it.isPaused } == true && lastMedia?.paused == false
        var joined = withTimeoutOrNull(UNJOINED_WAIT_MS) { device.joined.first { it } } != null
        if (!ours() || heard()) return
        if (!joined && unjoinedReloads < UNJOINED_RELOADS) {
            unjoinedReloads++
            bridge.logger?.invoke("page device still not joined ${UNJOINED_WAIT_MS / 1000} s after our play: loading the page again (attempt $unjoinedReloads)")
            if (!reloadPage() || !ours()) return
            playOnNewPage { replay(p, "sent again on a page loaded again") }
            return
        }
        if (!joined) {
            bridge.logger?.invoke("page device never joined after $unjoinedReloads loads: the play is sent again if it joins")
            joined = withTimeoutOrNull(UNJOINED_REPLAY_MS) { device.joined.first { it } } != null
            if (!joined || !ours() || heard()) return
        }
        unjoinedReloads = 0
        bridge.logger?.invoke("page device joined after our play: sent again")
        replay(p, "sent again once the device joined")
    }

    /** [p] (a play of ours) sent again as it was. */
    private suspend fun replay(p: Triple<Uri, Uri?, Long>, origin: String) {
        runCatching {
            playNow(p.first, trackUri = p.second, index = lastIndex, order = lastOrder?.takeIf { it.first == p.first }?.second,
                refusable = lastPlayRefusable, origin = origin)
        }
    }

    /**
     * Reloads the page and waits until it is ready again; nothing is played. For a page whose
     * Spotify Connect layer hangs (a transfer from a device that went away never ends).
     */
    suspend fun reloadPage(): Boolean {
        val reload = reloadHost ?: return false
        if (repairsHeld()) { bridge.logger?.invoke("reload held: a sign-in page is open or Spotify is limiting or refusing playback"); return false }
        ownReload = true
        try {
            val back = reloadAndWait(reload)
            // A song picked during the reload was held for this page ([playAsked]).
            if (back && PageRecoveryRules.playsAfterOwnReload(mirroring)) playPickAfterReload()
            return back
        } finally {
            ownReload = false
            playingAtLoss = null
        }
    }

    /**
     * Reloads and waits for the *new* page's "ready". Reported (2.0.1, log seen): an ad stuck,
     * the page reloaded, and the music stayed off until Play was pressed. The host only asks for
     * the load; that page started 10 s later, and "ready" — still the old page's — was taken for
     * the new one after 1.5 s: the song after the ad was sent to the page that was going away.
     */
    private suspend fun reloadAndWait(reload: suspend () -> Unit): Boolean {
        val gone = bridge.pagesGone
        // The old page still says "ready" for a moment: its device is not one to command from now.
        app.myspoti.spotify.bridge.PageDevice.pageGone()
        reloadUnderWay = true
        try {
            reload()
            return withTimeoutOrNull(45_000) { delay(1_500); bridge.ready.first { it && bridge.pagesGone != gone } } != null
        } finally {
            reloadUnderWay = false
        }
    }

    /** Between our reload's start and its new page's "ready": a pick then is held ([playAsked]). */
    @Volatile private var reloadUnderWay = false

    /**
     * [queued] (the user's queue before a reload of ours) added again to the new page's queue, but
     * for the song now playing (played on from the first of them) and those the page has already.
     */
    private suspend fun requeue(queued: List<NowPlaying>) {
        // The queue the page reports after the song it plays on.
        delay(REQUEUE_WAIT_MS)
        val playing = _state.value?.item?.uri
        val has = _queue.value?.queued.orEmpty().map { it.uri }
        val missing = PageRecoveryRules.requeued(queued.map { it.uri }, playing, has)
        if (missing.isEmpty()) return
        bridge.logger?.invoke("page back after a reload: ${missing.size} queued songs added to the queue again")
        addToQueue(missing)
    }

    /**
     * A new page after [lost] was playing: the page restores the session paused, or not at
     * all, and takes commands only once its device is registered again. The song goes on from
     * where it was, checked and tried again, unless the user paused or played something since.
     */
    private suspend fun resumeAfterReload(lost: PlayerState, nextUp: List<NowPlaying>? = null) {
        if (licenceRefusedHold) { bridge.logger?.invoke("page back after a reload: nothing resumed, Spotify refuses this account's licences"); return }
        // Skipped during the reload: that song, not the one that played before it.
        if (liveIntent() != null) {
            val seq = stateSeq
            withTimeoutOrNull(RESTORE_WAIT_MS) { while (stateSeq == seq) delay(100) }
            playIntent()
            return
        }
        val item = lost.item ?: return
        val uri = item.uri
        val at = lost.positionMs
        // A play newer than [since] is the user's: the restore stops there.
        var since = lastPlay?.third ?: 0L
        val seq = stateSeq
        // The restored session (or nothing, when the page has none): a real state, or a while.
        withTimeoutOrNull(RESTORE_WAIT_MS) { while (stateSeq == seq || _state.value?.item == null) delay(100) }
        bridge.logger?.invoke("page back after a reload: ${item.name} goes on at ${at / 1000} s")
        for (attempt in 1..RESTORE_ATTEMPTS) {
            if (pausedByUser || (lastPlay?.third ?: 0L) != since) return
            val s = _state.value
            if (s != null && s.item?.uri == uri && !s.isPaused) return
            runCatching {
                if (s?.item?.uri == uri) {
                    resumePage()
                    if (kotlin.math.abs(s.positionMs - at) > 5_000) seek(at)
                } else {
                    // A state without a list would play the song alone, and Spotify then goes on
                    // with its own autoplay: the list of the song before it is the next best.
                    // A song autoplay brought is not in that list: played in it, the list would start over (#135).
                    val ctx = if (item.provider == "autoplay") uri else lost.contextUri ?: lastSong?.contextUri ?: uri
                    val station = nextUp?.takeIf { ctx == lost.contextUri }?.let { RepeatRules.stationOrder(uri, it) }
                    playNow(ctx, trackUri = uri.takeIf { ctx != it }, positionMs = at, order = station ?: lastOrder?.takeIf { it.first == ctx }?.second,
                        keepsOrder = station == null, origin = "restore")
                }
            }.onFailure { bridge.logger?.invoke("restore attempt $attempt failed: ${it.message}") }
            // The play just sent is ours, not the user's.
            since = lastPlay?.third ?: 0L
            val ok = withTimeoutOrNull(RESTORE_CHECK_MS) { _state.first { it?.item?.uri == uri && !it.isPaused } }
            if (ok != null) return
            delay(RESTORE_GAP_MS * attempt)
        }
        bridge.logger?.invoke("page back after a reload: ${item.name} did not start again")
    }

    /** The media element as the watcher last read it. */
    @Volatile private var lastMedia: Media? = null
    /** When the watcher read [lastMedia] (its tick's start). */
    @Volatile private var lastMediaAt = 0L

    /**
     * Another device of the account is Spotify Connect's active one: this page's element stands
     * still while the state is that device's. Judged on the element too, as the active flag is read
     * every few seconds and lags a transfer to this phone (whose element then moves).
     */
    internal val remoteActive: Boolean get() = lastMedia?.let { it.active == false && it.paused } == true &&
        // The native engine is the device that plays: the page sees it as another one, it is this phone.
        audio?.takeIf { it.on && !mirroring }?.holdsPlayback != true

    /**
     * Whether a volume set on the page now is this page's own: the page player's volume is the
     * Connect volume of the active device, so it may be set by us (not by the user) only when
     * [media] says this page is that device. Unknown (no element, the device list not read) is no.
     */
    private fun ownsConnectVolume(media: Media?): Boolean = media?.active == true

    private val _playsElsewhere = MutableStateFlow(false)
    /**
     * [remoteActive] as the watcher last read it (no call of its own): true while another device of
     * the account plays and this page stands still; back to false once this page plays again.
     */
    val playsElsewhere: StateFlow<Boolean> = _playsElsewhere

    /** Loads the song after the current one in advance (see "player.preloadNext"); on by default. */
    @Volatile var preloadNext = true
    /** "current|next" already preloaded. */
    private var preloadedFor: String? = null

    /**
     * Once a song has played a couple of seconds here, the next one is loaded: a skip then
     * starts ~0.3 s sooner. The page does it itself only 10 s before the end, so the bytes are
     * the same when the song plays out; again when the next song changes (queue edits).
     */
    private suspend fun preloadNextSong(s: PlayerState?, media: Media?) {
        if (!preloadNext || mirroring || s == null || s.isPaused || s.isBuffering || _adPlaying.value || media == null || media.active == false) return
        val next = s.next?.takeIf { it.kind == ItemKind.TRACK || it.kind == ItemKind.EPISODE } ?: return
        if (s.positionAt(clock()) < PRELOAD_AFTER_MS) return
        val key = "${s.item?.uri}|${next.uri}"
        if (key == preloadedFor) return
        preloadedFor = key
        runCatching { bridge.call("player.preloadNext", timeoutMs = 5_000, quiet = true) }
    }

    /** The page's media element as `media.state` reads it. [active]: null when unknown. */
    private data class Media(
        val paused: Boolean,
        val muted: Boolean,
        val level: Double,
        val own: Double,
        val time: Double,
        /** What the page player itself is set to; null when the page did not say. */
        val playerVolume: Double?,
        val active: Boolean?,
        /** HTMLMediaElement readyState / networkState and its error, for the log only (a stall's "why"). */
        val readyState: Int = -1,
        val networkState: Int = -1,
        val error: String? = null,
        /** The element has media keys (1) or not (0), and how often it waited for a key ("waitingforkey"); -1: not said. */
        val keys: Int = -1,
        val keyWaits: Int = -1,
    ) {
        fun describe() = "paused=$paused muted=$muted volume=$level own=$own t=${"%.1f".format(time)} ready=$readyState net=$networkState error=${error ?: "none"} keys=$keys keyWaits=$keyWaits active=$active"
    }

    private suspend fun readMedia(): Media? {
        val o = bridge.call("media.state", timeoutMs = MEDIA_TIMEOUT_MS, quiet = true) as? JsonObject ?: return null
        fun p(k: String) = o[k] as? kotlinx.serialization.json.JsonPrimitive
        return Media(
            paused = p("paused")?.booleanOrNull ?: true,
            muted = p("muted")?.booleanOrNull ?: false,
            level = p("level")?.doubleOrNull ?: 1.0,
            own = p("own")?.doubleOrNull ?: 1.0,
            time = p("time")?.doubleOrNull ?: 0.0,
            playerVolume = p("playerVolume")?.doubleOrNull,
            // The device list is read at most every few seconds in the page: what it did not
            // answer this time is what it answered last time.
            active = p("active")?.booleanOrNull ?: lastMedia?.active,
            readyState = p("readyState")?.intOrNull ?: -1,
            networkState = p("networkState")?.intOrNull ?: -1,
            error = p("error")?.contentOrNull?.takeIf { it != "null" },
            keys = p("keys")?.intOrNull ?: -1,
            keyWaits = p("keyWaits")?.intOrNull ?: -1,
        )
    }

    /**
     * Every [WATCH_TICK_MS]: the page answers (else it is frozen and reloaded), the song it
     * says it plays is heard (its media element moves), the sound is on, an ad break's end is
     * not missed, and a song stuck between two others moves on.
     */
    private suspend fun watchStalls() {
        var key: String? = null
        var since = 0L
        var attempts = 0
        /** This break was said once in the log: on another device, left to it. */
        var remoteAdLogged = false
        var frozenReloadAt = 0L
        /** Consecutive ticks where the page answered nothing (see FROZEN_MISSES). */
        var frozenMisses = 0
        var tick = 0
        var lastMediaTime = -1.0
        var silentSince = 0L
        /** Since when the sound stands still, for [stalled]. */
        var stillSince = 0L
        /** When a stall already seen is due its repair: the next look is then, not a whole tick later. */
        var dueAt = 0L
        fun notStalled() { stillSince = 0L; _stalled.value = false }
        while (true) {
            // A song plays here (or an ad is on): the watcher looks every [WATCH_TICK_MS] to hear a
            // stall in seconds. Nothing plays, or another device of the account does (the phone a
            // remote): nothing here to repair, so it looks rarely, and at once when the page
            // pushes a state ([watchWake]: a play, a transfer to this phone). It looked every 2 to
            // 4 s, a call into the page each time, for an element that did not play.
            val quiet = !_adPlaying.value && _state.value.let { it == null || it.isPaused }
            // The native engine plays the account's songs: the page's element is not what is heard,
            // so it is looked at as rarely as for another device (it was read every 2 s).
            val elsewhere = !quiet && !_adPlaying.value && ((remoteActive && pageDeviceJoined()) || engineHolds())
            // A stall seen and due sooner is looked at when due: on the plain ticks a song stuck past
            // its end stayed silent 3.5 to 5.5 s for a wait of one, and one paused at its end 8 to 12 s.
            // The listen mirror: the page plays unheard, the extension's song is what is heard, and
            // the sync follows the page itself; a stall here only delays a count, so a look every
            // [WATCH_MIRROR_TICK_MS] (an ad still every tick, its end must not be missed).
            val mirrorOnly = !quiet && !elsewhere && mirroring && !_adPlaying.value
            val tickMs = when { quiet -> WATCH_IDLE_TICK_MS; elsewhere -> WATCH_ELSEWHERE_TICK_MS; mirrorOnly -> WATCH_MIRROR_TICK_MS; else -> WATCH_TICK_MS }
            val lookIn = if (dueAt > 0) (dueAt - clock()).coerceIn(DUE_TICK_MIN_MS, tickMs) else tickMs
            dueAt = 0L
            if (quiet || elsewhere) {
                // A wake from before this wait was already seen by the state read above.
                watchWake.tryReceive()
                // Woken: the element is given a moment to start, so the look sees it move.
                if (withTimeoutOrNull(lookIn) { watchWake.receive() } != null) delay(WAKE_SETTLE_MS)
            } else delay(lookIn)
            tick++
            // Repairs held (a sign-in page open, playback limited): nothing is judged, not even a
            // silent page, which is what a sign-in page looks like from here.
            if (!bridge.ready.value || ownReload || recoveryHeld()) { key = null; attempts = 0; silentSince = 0L; frozenMisses = 0; notStalled(); continue }
            val now = clock()
            // The page answers nothing, not even a ping: frozen (a stuck renderer). Reloaded
            // at once, the song going on where it was.
            val read = runCatching { readMedia() }
            if (read.isFailure && bridge.ready.value && !bridge.ping() && bridge.ready.value) {
                notStalled()
                // Paused: a page that does not answer is most often one Android froze with the
                // app in the background (seen: "freezing <renderer pid>" in the system log, then
                // a reload every minute that could not help). Nothing is lost while paused: the
                // page is only marked, and the next resume loads it again at once ([resume]).
                if (quiet) {
                    // One missed read proves little (a busy main thread misses one too), and a
                    // suspect page makes the next resume ping first: it is marked only after
                    // [FROZEN_MISSES] in a row, as a playing page is. Any answer clears it below.
                    if (++frozenMisses >= FROZEN_MISSES) pageSuspect = true
                    key = null; silentSince = 0L; continue
                }
                // One silent page is not a dead one: a busy main thread (a long render, a reload
                // still settling, an ad break starting) also holds the bridge for seconds, and
                // reloading on the first miss turned a short hiccup into a full page reload — the
                // one the user actually sees. The reload needs two misses in a row
                // ([FROZEN_MISSES] ticks of WATCH_TICK_MS, so about four seconds of real silence).
                frozenMisses++
                if (frozenMisses < FROZEN_MISSES) { key = null; silentSince = 0L; continue }
                if (now - frozenReloadAt > FROZEN_RELOAD_GAP_MS) {
                    frozenReloadAt = now
                    frozenMisses = 0
                    bridge.logger?.invoke("page not answering ($FROZEN_MISSES misses in a row): reloading the player")
                    val playing = lastTrackState?.takeIf { !pausedByUser && !it.isPaused && !remoteActive }
                    reloadAndPlayOn(playing?.copy(positionMs = playing.positionAt(now), timestampMs = now), next = false)
                    key = null; attempts = 0; silentSince = 0L
                }
                continue
            }
            // The page answered: it is alive, whatever happened before.
            frozenMisses = 0
            pageSuspect = false
            val media = read.getOrNull()
            lastMedia = media
            lastMediaAt = now
            _playsElsewhere.value = remoteActive
            if (media != null) repeatOnRead()
            val s = _state.value
            preloadNextSong(s, media)
            tickCrossfade(s, media, now)
            if (localMuted && s?.item?.uri != quietUri) { quietUri = s?.item?.uri; applyQuiet() }
            if (tick % VOLUME_CHECK_TICKS == 0) guardVolume(s, media)
            if (!_adPlaying.value) remoteAdLogged = false
            if (_adPlaying.value) {
                // The screens show the break (or the song before it, waiting), not a stall. The ad
                // plays to its end; nothing is muted or skipped from here.
                notStalled()
                if (remoteActive) {
                    // On another device of the account (Connect, Cast): the ad blocker for Connect
                    // devices looks after it (AdBlock); a reload or a play of the next song from here
                    // was refused by that device, or pulled playback back to the phone.
                    if (!remoteAdLogged) bridge.logger?.invoke("ad on another device: nothing done from here")
                    remoteAdLogged = true
                } else if (now - adStateAt > AD_STATE_RECHECK_MS) {
                    // The page's state push that ends the break can be lost on the way (the events
                    // buffer drops the oldest under a stalled main thread), leaving the screen on
                    // "Advertisement" through the next song: the page is asked again when it has said
                    // nothing for a while. Only a state with an item counts: one read between the ad
                    // and the next song ("nothing plays") would end the break too soon.
                    val asked = runCatching { bridge.call("player.state", timeoutMs = MEDIA_TIMEOUT_MS, quiet = true) as? JsonObject }.getOrNull()
                    val st = asked?.let { playerState(it) }
                    if (st?.item != null) onState(st)
                    if (!_adPlaying.value) bridge.logger?.invoke("ad flag cleared: the page plays ${_state.value?.item?.name}")
                    else if (now - adStateAt > AD_FLAG_MAX_MS) {
                        // Nothing said about the break for longer than any break: the flag is not
                        // left standing for good (it holds every command), the state is read again.
                        bridge.logger?.invoke("ad break not reported for ${AD_FLAG_MAX_MS / 1000}s: flag cleared")
                        adStateAt = 0L
                        _adPlaying.value = false
                        runCatching { refresh() }
                    }
                }
                key = null; silentSince = 0L
                continue
            }
            // Another device of the account plays (Spotify Connect, the desktop app): the state is
            // its, followed and never repaired. A stall judged on its clock — "past the end" on a
            // state that came a moment late, "buffering" on its report — was answered with a seek or
            // a play of the next song, i.e. a command that skipped a track on that other device.
            // Not a page whose own device has not joined Connect yet (reported, 2.3.0: a page reloaded
            // to repair a stall, its device never registered, its element idle, taken for another
            // device's and left silent for 1 h 40 while the phone was the one playing).
            // The native engine too: its own player repairs its songs, never the page.
            if ((remoteActive && pageDeviceJoined()) || engineHolds()) {
                if (key != null || attempts != 0) bridge.logger?.invoke("another device plays: nothing repaired from here")
                key = null; attempts = 0; silentSince = 0L
                notStalled()
                continue
            }
            // Silent: the state says this phone plays, yet its media element stands still.
            val fading = fadeBackTo != null || fadeBack?.isActive == true
            val heardHere = s != null && !s.isPaused && !s.isBuffering && media != null && media.active != false
            val playingHere = heardHere && !fading
            val moved = media != null && !media.paused && kotlin.math.abs(media.time - lastMediaTime) > 0.25
            lastMediaTime = media?.time ?: -1.0
            silentSince = if (playingHere && !moved) (if (silentSince == 0L) now else silentSince) else 0L
            // Reported: the clock counted on while nothing was heard. Still over one tick and
            // waiting for data (or paused) is a stall at once; still over two, whatever the element
            // says. Not one still tick alone: a song resumed just before it has not moved yet.
            // Judged through a resume's rise too (a slow network holds the sound right there),
            // and not reset by a repair as [silentSince] is: the sound is not back yet.
            stillSince = if (heardHere && !moved) (if (stillSince == 0L) now else stillSince) else 0L
            // An unknown readyState (-1, an older page) is not taken for waiting.
            val waiting = media != null && (media.paused || media.readyState in 0 until HAVE_FUTURE_DATA)
            _stalled.value = stillSince != 0L && (waiting || now - stillSince >= WATCH_TICK_MS)
            val silent = silentSince != 0L && now - silentSince >= SILENT_MS
            val reason = s?.let { stallOf(it, now, pausedByUser) } ?: (if (silent) SILENT else null)
            if (repairsHeld()) { key = null; attempts = 0; continue }
            // A song heard again: a reload for a stale token may be tried again from the first gap.
            if (playingHere && moved) authReloads = 0
            // A silent song repaired once is not silent for [SILENT_MS] after the repair, which
            // reset its count: its attempt is kept while its element still stands, so the next
            // look reloads (reported, 2.3.0: "attempt 1" every 8 s for 4.5 minutes, a seek refused
            // each time, never a reload).
            if (s == null || reason == null) { if (!silentKept(key, s?.item?.uri, heardHere && !moved)) { key = null; attempts = 0 }; continue }
            val k = "${s.item?.uri}|$reason"
            val wait = when (reason) { "past the end" -> END_STUCK_MS; SILENT -> 0L; else -> STUCK_MS }
            if (k != key) { key = k; since = now; attempts = 0; if (reason != SILENT) { dueAt = now + wait; continue } }
            if (now - since < wait) { dueAt = since + wait; continue }
            since = now
            attempts++
            silentSince = 0L
            val refusedAgo = licenceRefusedAt.takeIf { it > 0 }?.let { "; last licence 403 ${(now - it) / 1000} s ago" }.orEmpty()
            bridge.logger?.invoke("player stalled ($reason) on ${s.item?.name}, attempt $attempts; media element: ${media?.describe() ?: "none"}$refusedAgo")
            // B4 (log seen, 2.0.2-beta.5): the song's licence refused, its first seconds play and
            // then the sound stops while the clock goes on. A seek or a reload only asks for a
            // licence again (a reload gets one, then the page skips on): paused and said instead.
            if (licenceRefusedUri != null && s.item?.uri == licenceRefusedUri && !mirroring) {
                key = null; attempts = 0
                refusedLicences("refused for ${s.item?.name}, which then stalled")
                continue
            }
            // A song stuck at its end moves on; one stuck in its middle goes on where it was.
            val onward = reason == "past the end" || reason == "paused at the end"
            // A frozen page answers nothing: straight to the reload.
            if (attempts == 1 && !bridge.ping()) attempts = 2
            // A licence answered 401 a moment ago: the page's token is stale, a seek only asks the
            // same token again (and is refused while the element waits for its key): the reload.
            if (attempts == 1 && staleTokenStall(now - app.myspoti.spotify.bridge.PageAuth.licenceUnauthorizedAt, media?.keyWaits ?: 0)) {
                bridge.logger?.invoke("a licence was answered 401: the page's token is stale, reloading")
                attempts = 2
            }
            if (attempts == 1) runCatching { if (onward) playOn(s) else seek(s.positionAt(now)) }
            else {
                attempts = 0
                reloadAndPlayOn(if (onward) s else s.copy(positionMs = s.positionAt(now), timestampMs = now), next = onward)
            }
        }
    }

    /**
     * The page left silent while a song plays: a fade or an ad mute whose call was lost (page
     * busy, reloaded). The sound comes back to what the user wants.
     */
    private suspend fun guardVolume(s: PlayerState?, media: Media?) {
        if (s == null || s.isPaused || s.isBuffering || _adPlaying.value || fadeBackTo != null || fadeBack?.isActive == true) return
        // A ramp of ours under way (a pause's fade, a rise just started): the level is low on purpose.
        if (levelRamps.get() > 0 || clock() < levelHeldUntil) return
        // Its volume is set through Connect: with another device playing it would be that device's.
        if (remoteActive) return
        // A crossfade owns the element level until it ends: the guard must not undo the ramp.
        // Past its deadline, though, it is exactly the lost unmute it exists to catch.
        if (crossfadeArmed) {
            if (clock() >= crossfadeUntil) {
                bridge.logger?.invoke("crossfade overdue: volume restored")
                endCrossfade(restore = true)
            }
            return
        }
        if (media?.muted == true) {
            bridge.logger?.invoke("media element muted during a song: unmuted")
            applyQuiet()
        }
        val wanted = wantedVolume ?: 1.0
        if (wanted <= 0.0) return
        // The page's own volume is read with the media element (one call, no second round trip).
        val v = media?.playerVolume ?: runCatching { volume() }.getOrNull() ?: return
        // Set through Connect: only when this page is known to be the active device. Right after a
        // transfer the element has not stopped yet (so [remoteActive] is still false) while the
        // volume read is the speaker's, and a speaker reporting 0 was set to 100 % (#159).
        if (v <= 0.0 && ownsConnectVolume(media)) {
            bridge.logger?.invoke("page silent during a song: volume back to $wanted")
            runCatching { setVolume(wanted) }
        }
        // A fade that never came back: the element back to the page player's own volume.
        // (Not while the page is unheard on purpose.)
        if (!localMuted && media != null && media.active != false && media.own > 0.001 && media.level < media.own * 0.5) {
            bridge.logger?.invoke("page player silent during a song: level ${media.level} back to ${media.own}")
            runCatching { restoreLevel() }
        }
    }

    /** Spotify refused the last play: once, the list is played from its start instead. */
    /**
     * Spotify refused the last play (`402 COMMAND_FAILED`). Each refusal within the window moves
     * one step down, so a play that works never pays for any of it:
     * 1. the list without picking the song (an account that may not choose a track); a song sent
     *    with its place in the list is asked by that place alone instead, so the song picked
     *    still plays (reported: a song picked far down a big list started another one);
     * 2. playback taken to this player first, then the song again (the command may have gone to
     *    another device of the account — the official app — which a free account cannot drive);
     * 3. the list on shuffle (an account that may only shuffle);
     * then the user is told.
     */
    private fun onRefused(late: Boolean = false, kind: String? = null) {
        probeDrm()
        val now = clock()
        // A command acknowledged and never delivered: the page's media element stood still for 10 s
        // while its state may well say "playing". Not a refusal if the user paused, an ad is on or
        // another device plays.
        if (late && (pausedByUser || _adPlaying.value || remoteActive)) return
        // Reported (402 on free accounts in some countries): play, seek, shuffle and transfer all
        // refused from the first command of a session, after the host already sent each one again
        // another way (see RelayRoutes). Two kinds refused: playing the list, transferring and
        // shuffling would only be refused in turn. The page is loaded again once, the song asked
        // again there, and the user is told.
        if (kind != null) {
            refusedKinds[kind] = now
            refusedKinds.entries.removeIf { now - it.value > REFUSED_KINDS_MS }
            if (refusedKinds.size >= 2) { refusedEverything(); return }
        }
        val last = lastPlay ?: return
        if (now - last.third > if (late) LATE_REFUSED_WINDOW_MS else REFUSED_WINDOW_MS) return
        // A play of ours (an ad recovery, a reload restore): refused during an ad, and the steps
        // below would replay the list from its start and turn shuffle on, which nobody asked for.
        if (!lastPlayRefusable) { bridge.logger?.invoke("play refused by Spotify: not the user's, left alone"); return }
        // The step's own transfer refused: the step is judged by the play that follows it.
        if (refusalTransferring) return
        // A Jam member: taking playback here (step 2) would take this account out of the Jam.
        if (inJam()) { bridge.logger?.invoke("play refused by Spotify in a Jam: left to the host"); return }
        val ctx = last.first
        var step = ++refusalStep
        if (step == 1 && last.second == null) step = ++refusalStep
        // Each step's own play may be refused in its turn: it opens a new window.
        lastPlay = last.copy(third = now)
        // The list as it was picked: a sorted list or a smart shuffle keeps its order.
        val order = lastOrder?.takeIf { it.first == ctx }?.second
        val index = lastIndex?.takeIf { last.second != null }
        suspend fun playCtx(track: Uri?, at: Int? = null) = bridge.call("player.play", buildJsonObject {
            put("contextUri", ctx)
            order?.let { uris -> putJsonArray("order") { uris.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } } }
            track?.let { put("trackUri", it) }
            at?.let { put("index", it) }
        }, timeoutMs = PLAY_TIMEOUT_MS, label = "refusal step")
        when (step) {
            1 -> if (index != null) {
                bridge.logger?.invoke("play refused by Spotify: the song asked by its place in the list ($index)")
                scope.launch { runCatching { playCtx(null, index) } }
            } else {
                bridge.logger?.invoke("play refused by Spotify: playing the list without picking the song")
                scope.launch { runCatching { playCtx(null) } }
            }
            2 -> {
                bridge.logger?.invoke("play refused by Spotify: taking playback to this player first")
                scope.launch {
                    refusalTransferring = true
                    try {
                        runCatching { bridge.service("ConnectTransferAPI", "pullToLocalDevice", JsonObject(emptyMap())) }
                            .onFailure { bridge.logger?.invoke("transfer to this player failed: ${it.message}") }
                        delay(REFUSAL_SETTLE_MS)
                    } finally {
                        refusalTransferring = false
                    }
                    lastPlay = last.copy(third = clock())
                    runCatching { playCtx(last.second, index) }
                }
            }
            3 -> {
                bridge.logger?.invoke("play refused by Spotify: playing the list on shuffle")
                scope.launch {
                    runCatching { setShuffle(true) }
                    lastPlay = last.copy(third = clock())
                    runCatching { playCtx(null) }
                }
            }
            else -> _refused.tryEmit(Unit)
        }
    }

    private fun refusedEverything() {
        refusalStep = 0
        val now = clock()
        if (now - refusedAllToldAt > REFUSED_TOLD_GAP_MS) {
            refusedAllToldAt = now
            _refusedAll.tryEmit(Unit)
        }
        if (refusedReloaded) { bridge.logger?.invoke("refused: ${refusedKinds.keys.sorted()}: fallbacks stopped"); return }
        refusedReloaded = true
        bridge.logger?.invoke("refused: ${refusedKinds.keys.sorted()}: fallbacks stopped, the page loaded again once")
        val pick = lastPlay
        val order = lastOrder?.takeIf { pick != null && it.first == pick.first }?.second
        scope.launch {
            val movedAt = userMoveAt
            if (reloadPage() && pick != null && userMoveAt == movedAt) runCatching {
                awaitDeviceJoined()
                playAsked(pick.first, trackUri = pick.second, index = lastIndex?.takeIf { pick.second != null }, positionMs = null, order = order, origin = "after the reload for refusals")
            }
        }
    }

    /** Plays the song after [s] (or [s] again when it is the last one) in its context. */
    /**
     * The song the user's skips point at, kept through what the page cannot take a skip in:
     * an ad break (skips refused, "The operation is not allowed"), a reload ("local player is
     * disabled", "not ready"). Skips then move this intent instead of reaching the page, and
     * whatever recovers playback (the ad recovery, a reload, the end of the ad) plays it.
     * [from]: the song shown when it was set (the page has not moved while it reports it).
     */
    private data class Intent(val context: Uri?, val item: NowPlaying, val from: Uri?, val at: Long, val adSeen: Boolean = false)
    @Volatile private var intent: Intent? = null
    /** The song and time the intent was last played, so that a slow page is not asked twice. */
    @Volatile private var intentPlayedAt = 0L
    @Volatile private var intentPlayedUri: Uri? = null
    /** The intent is being played after an ad or a reload: skips meanwhile only move it. */
    @Volatile private var playingIntent = false

    /** The page cannot take a skip now. */
    private val skipsHeld: Boolean get() = _adPlaying.value || ownReload || playingIntent || !bridge.ready.value

    private fun liveIntent(): Intent? = intent?.takeIf { clock() - it.at < INTENT_MAX_MS }

    /** The songs after the last real one, as the page listed them (queued first). */
    private fun upcoming(): List<NowPlaying> =
        _queue.value?.let { it.queued + it.nextUp }.orEmpty().filter { it.kind == ItemKind.TRACK || it.kind == ItemKind.EPISODE }

    /**
     * A skip the page cannot take: the intent moves [by] songs from where it is (or from the
     * song shown: the first "next" is the song after it, the first "previous" that song again)
     * and is shown at once.
     */
    private fun stepIntent(by: Int) {
        val base = lastWithSong() ?: return
        val current = base.item ?: return
        val seq = listOfNotNull(base.previous) + current + upcoming().ifEmpty { listOfNotNull(base.next) }
        val baseIdx = if (base.previous != null) 1 else 0
        val at = liveIntent()?.let { i -> seq.indexOfFirst { it.uri == i.item.uri }.takeIf { it >= 0 } }
        // No intent yet: "next" lands on the song after the one shown, "previous" on that one.
        val idx = ((at ?: (baseIdx + if (by > 0) 0 else 1)) + by).coerceIn(0, seq.lastIndex)
        setIntent(base.contextUri, seq[idx], current.uri)
    }

    private fun setIntent(context: Uri?, item: NowPlaying, from: Uri?) {
        val now = clock()
        intent = Intent(context, item, from, now, adSeen = _adPlaying.value || liveIntent()?.adSeen == true)
        bridge.logger?.invoke("skip held (${if (_adPlaying.value) "ad" else "page reloading"}): ${item.name} goes on next")
        showIntent()
    }

    /** The screen shows the song the user is on, waiting, while the page is elsewhere. */
    private fun showIntent() {
        val i = liveIntent() ?: return
        val base = lastWithSong() ?: return
        val now = clock()
        setPageState(base.copy(item = i.item, positionMs = 0, timestampMs = now, isPaused = false, isBuffering = true))
    }

    /**
     * Plays the intent until the page is on it; skips made meanwhile move the intent and the
     * newest one is played. False when there is none.
     */
    private suspend fun playIntent(): Boolean {
        if (liveIntent() == null || playingIntent) return liveIntent() != null
        playingIntent = true
        try {
            repeat(INTENT_PLAYS) {
                val i = liveIntent() ?: return true
                val uri = i.item.uri
                if (lastTrackState?.item?.uri == uri) { intent = null; return true }
                val now = clock()
                // Asked for a moment ago (a slow page): not again, wait for it below.
                if (uri != intentPlayedUri || now - intentPlayedAt >= INTENT_REPLAY_MS) {
                    intentPlayedUri = uri
                    intentPlayedAt = now
                    bridge.logger?.invoke("playing the song the skips pointed at: ${i.item.name}")
                    val ctx = i.context
                    runCatching {
                        if (ctx != null) playNow(ctx, trackUri = uri, order = lastOrder?.takeIf { it.first == ctx }?.second, origin = "intent")
                        else playNow(uri, origin = "intent")
                    }.onFailure { bridge.logger?.invoke("intent not played: ${it.message}") }
                }
                // The page on it, or the user skipped again (then that song is played).
                withTimeoutOrNull(INTENT_REPLAY_MS) {
                    while (lastTrackState?.item?.uri != uri && liveIntent()?.item?.uri == uri) delay(100)
                }
                if (lastTrackState?.item?.uri == uri && liveIntent()?.item?.uri == uri) { intent = null; return true }
            }
            return true
        } finally {
            playingIntent = false
        }
    }

    /**
     * Plays on from [s], a song stuck at its end: what the user asked for
     * meanwhile, the song again under repeat one, or the song after it. [nextUp]: what came next
     * on a page our reload took away (a station's, see [reloadAndPlayOn]).
     */
    private suspend fun playOn(s: PlayerState, nextUp: List<NowPlaying>? = null) {
        // The user skipped meanwhile: their song, not the one after the last state seen.
        if (playIntent()) return
        // Or picked one during an ad: that one, in the order it was picked.
        pickBeforeAd?.takeIf { clock() - it.at < PICK_REPLAY_MS }?.let { pick ->
            pickBeforeAd = null
            bridge.logger?.invoke("playing the song picked during the ad: ${pick.track ?: pick.context}")
            playNow(pick.context, trackUri = pick.track, index = pick.index, positionMs = pick.positionMs, order = pick.order, refusable = true, origin = "pick during the ad")
            return
        }
        // Repeat one: the way on from the song is the song again (that is its end): played again
        // rather than the next song.
        if (wantedRepeat == Repeat.TRACK && s.item?.let { it.kind != ItemKind.AD } == true) {
            bridge.logger?.invoke("repeat one: ${s.item?.name} played again, not the next song")
            playAgain(s, origin = "repeat one")
            return
        }
        val ctx = s.contextUri
        val onward = onwardItem(s, nextUp ?: _queue.value?.nextUp.orEmpty())
        val target = onward?.uri
        // A song autoplay brought is not in the list the page names: a new play of the list would start it over (#135).
        val fromAutoplay = onward?.provider == "autoplay"
        // A new play reshuffles a shuffled list (measured 21/10) and does not find a song the user
        // queued in the list: both go on within the queue the page has, as a skip would.
        val inQueue = target?.let { t -> upcoming().firstOrNull { it.uri == t } }
        if (inQueue != null && (s.shuffle || inQueue.provider == "queue" || inQueue.provider == "autoplay") &&
            runCatching { command("queue.skipTo", ref(inQueue), timeoutMs = PLAY_TIMEOUT_MS) }.isSuccess) return
        val station = nextUp?.let { target?.let { t -> RepeatRules.stationOrder(t, it) } }
        when {
            // Not in the queue the page has (a page our reload took away): the song plays alone, Spotify goes on with its autoplay.
            target != null && fromAutoplay -> playNow(target, origin = "play on")
            ctx != null && target != null -> playNow(ctx, trackUri = target, order = station ?: lastOrder?.takeIf { it.first == ctx }?.second,
                keepsOrder = station == null, origin = "play on")
            target != null -> next()
            // Nothing after it: a song is started again (the page's own end-of-context behaviour);
            // an episode is left finished rather than replayed from the start. Not a song autoplay
            // brought or a station's: its list would start over (#135), the page goes on itself.
            ctx != null && s.item?.kind == ItemKind.TRACK && RepeatRules.replayList(s) == null -> {
                bridge.logger?.invoke("play on: nothing after ${s.item?.name} (autoplay or a station), the page's next asked for")
                command("player.next")
            }
            ctx != null && s.item?.kind == ItemKind.TRACK -> playNow(ctx, trackUri = s.item?.uri, origin = "play on")
        }
    }

    /**
     * [s]'s song from its start, in its list (with the order it was played in) or alone where
     * the list would not find it ([RepeatRules.replayList]).
     */
    private suspend fun playAgain(s: PlayerState, origin: String) {
        val song = s.item ?: return
        val list = RepeatRules.replayList(s)
        playNow(list ?: song.uri, trackUri = song.uri.takeIf { list != null }, order = lastOrder?.takeIf { list != null && it.first == list }?.second, origin = origin)
    }

    private val skipChain = TrackChanges.Chain()
    private val commandsExplained = TrackChanges.Commands()
    private val _skipChained = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /**
     * The page moved on by itself through several songs after a few seconds each (it cannot play
     * them: licences refused, a stream failing): it was paused, the UI says so.
     */
    val skipChained: kotlinx.coroutines.flow.SharedFlow<Unit> = _skipChained

    /**
     * One Logbook line for each change of song with the reason (a command, an ad, the song's end,
     * or the page on its own), so a report of songs skipping says which. The page moving on by
     * itself again and again is stopped instead of chained through the list.
     */
    private fun noteTrackChange(old: PlayerState?, s: PlayerState?, adJustEnded: Boolean) {
        val from = old?.item ?: return
        val to = s?.item ?: return
        if (from.uri == to.uri || from.kind == ItemKind.AD || remoteActive) return
        val now = clock()
        val played = old.positionAt(now)
        val play = lastPlay
        val commandAt = maxOf(userMoveAt, play?.third ?: 0L)
        // The song a play asked for; a skip (newer than the last play) names none.
        val target = play?.takeIf { it.third >= userMoveAt }?.second
        val used = commandsExplained.used(commandAt, to.uri, target)
        val cause = TrackChanges.cause(played, from.durationMs, now - commandAt, adJustEnded, crossfadeArmed, commandUsed = used)
        val list = if (old.contextUri == s.contextUri) "in ${s.contextUri}" else "${old.contextUri} -> ${s.contextUri}"
        bridge.logger?.invoke("track change: ${from.name} -> ${to.name} (${cause.text}, left at ${TrackChanges.clock(played)} of ${TrackChanges.clock(from.durationMs)}, $list${if (playbackHeld()) ", playback held" else ""}${if (mirrored) ", mirroring" else ""})")
        // The page only mirrors a song of an extension (heard from the phone's own player): pausing
        // it would not stop what the user hears, and the mirror sends its song again.
        if (playbackHeld() || mirrored) { skipChain.reset(); return }
        if (skipChain.record(cause, played, now)) {
            bridge.logger?.invoke("skip chain: the page moved on by itself three times in a row after a few seconds each (now on ${to.name}): paused")
            _skipChained.tryEmit(Unit)
            scope.launch { runCatching { pause() } }
        }
    }

    private fun onState(s: PlayerState?) {
        stateSeq++
        // The watcher, waiting long while nothing plays here, looks at once (see [watchStalls]).
        watchWake.trySend(Unit)
        // Another song than the one a crossfade is fading out: the ramp up, at once (the
        // watcher's next tick would leave the new song's first second at the faded level).
        if (crossfadeArmed) {
            val item = s?.item
            when {
                // An ad takes the sound over (and is muted on its own): the fade is over.
                item?.kind == ItemKind.AD -> disarmCrossfade()
                item != null && item.uri != crossfadeUri -> {
                    val from = crossfadeUri
                    disarmCrossfade()
                    riseWhenSongPlays(from, lastMedia?.own?.takeIf { it > 0.01 } ?: 1.0, CROSSFADE_IN_MS)
                }
            }
        }
        if (s?.item?.kind == ItemKind.AD) {
            // Keep showing the last real track, buffering, while the ad plays.
            if (!_adPlaying.value) {
                adSince = clock()
                bridge.logger?.invoke("ad break started")
                app.myspoti.spotify.report.Refusal402.adBreak()
                // A song picked a moment ago and not playing yet: the ad came in its place.
                val p = lastPlay
                val came = p?.takeIf {
                    adSince - it.third < PICK_AD_MS && it.second != null && lastTrackState?.item?.uri != it.second
                }?.let { Pick(it.first, it.second, it.third, lastOrder?.takeIf { o -> o.first == it.first }?.second, index = lastIndex) }
                // A pick held from an earlier break stays until it was played or went stale. Not
                // while the page only mirrors: that play was the mirror's, which sends its song again.
                pickBeforeAd = if (PageRecoveryRules.keepsPickBeforeAd(mirroring)) came ?: pickBeforeAd else null
            }
            adStateAt = clock()
            _adPlaying.value = true
            intent = intent?.copy(adSeen = true)
            setPageState(lastTrackState?.copy(isBuffering = true) ?: s.copy(item = null))
            showIntent()
            return
        }
        val adJustEnded = _adPlaying.value
        _adPlaying.value = false
        if (adJustEnded) bridge.logger?.invoke("ad break over after ${(clock() - adSince) / 1000} s: ${s?.item?.name ?: "nothing"}${if (s?.isPaused == true) " (paused)" else ""}")
        // The page plays on from an ad by itself, on its own options: its repeat then is no change of the user's.
        if (adJustEnded) repeatRestoreUntil = clock() + REPEAT_RESTORE_MS
        // Repeat one: the song's end is the song again. Should the page go on from an ad to another
        // song, the song before the ad plays again, unless the user picked or skipped something meanwhile.
        val beforeAd = lastTrackState
        val songBeforeAd = beforeAd?.item
        if (adJustEnded && wantedRepeat == Repeat.TRACK && pickBeforeAd == null && liveIntent() == null && userMoveAt < adSince &&
            !pausedByUser && !remoteActive && !mirrored && !ownReload && bridge.ready.value &&
            beforeAd != null && songBeforeAd != null && songBeforeAd.kind == ItemKind.TRACK &&
            s != null && s.item?.kind == ItemKind.TRACK && s.item?.uri != songBeforeAd.uri) {
            bridge.logger?.invoke("ad break over: repeat one, ${songBeforeAd.name} played again instead of ${s.item?.name}")
            scope.launch {
                runCatching { playAgain(beforeAd, origin = "repeat one after an ad") }
                    .onFailure { bridge.logger?.invoke("repeat one after the ad refused: ${it.message}") }
            }
        }
        val pick = pickBeforeAd
        if (adJustEnded && pick != null && !PageRecoveryRules.keepsPickBeforeAd(mirroring)) {
            pickBeforeAd = null
            bridge.logger?.invoke("song picked before the ad: left to the mirror (the page only mirrors)")
        } else if (adJustEnded && pick != null && pausedByUser) {
            // Paused by the user during the ad: nothing plays before their resume, which plays the pick.
            pickBeforeAd = null
            val landed = if (pick.track != null) s?.item?.uri == pick.track else s?.contextUri == pick.context
            if (!landed) {
                pickAfterPause = pick
                bridge.logger?.invoke("song picked before the ad: paused by the user, played on their resume")
            }
        } else if (adJustEnded && pick != null) {
            pickBeforeAd = null
            // Reported (beta.9): a song picked in another playlist, an ad, then the old queue went
            // on and the song had to be picked again. Not on it after the ad: asked for again.
            // A pick of a whole list (no song) has landed when the page plays that list.
            val landed = if (pick.track != null) s?.item?.uri == pick.track else s?.contextUri == pick.context
            if (!landed && clock() - pick.at < PICK_REPLAY_MS && liveIntent() == null) {
                // The break "ended" because our own reload took the page away (an ad stuck): the
                // page going away takes no command ("local player is disabled"). The pick waits
                // for the new page and is played there ([playPickAfterReload]).
                if (ownReload || !bridge.ready.value) {
                    pickAfterReload = pick
                    bridge.logger?.invoke("song picked before the ad: played once the page is back")
                } else {
                    bridge.logger?.invoke("song picked before the ad not played: asked again")
                    // With the order it was picked in: a sorted list or a smart shuffle is not the list's own.
                    scope.launch { runCatching { playAsked(pick.context, trackUri = pick.track, index = pick.index, positionMs = pick.positionMs, order = pick.order, origin = "pick asked again after the ad") } }
                }
            }
        }
        shufflePending?.takeIf { clock() - it.second < PICK_REPLAY_MS }?.let { (on, _) ->
            if (adJustEnded && s?.shuffle != on) scope.launch { runCatching { setShuffle(on) } }
        }
        // Reported (2.0, stable): the ad is got past, then the music stays paused until Play is
        // pressed. The page can leave the song it moves on to paused when the ad's audio was
        // refused. Nobody paused (a pause is [pausedByUser]), so the song is resumed once the
        // state has settled, unless something else was played or picked meanwhile.
        if (adJustEnded && s?.item != null && s.isPaused && !pausedByUser && s.item?.kind != ItemKind.AD) {
            val playedAt = lastPlay?.third
            val uri = s.item?.uri
            scope.launch {
                delay(AD_END_SETTLE_MS)
                val now = _state.value
                if (now != null && now.item?.uri == uri && now.isPaused && !pausedByUser && !_adPlaying.value && liveIntent() == null &&
                    lastPlay?.third == playedAt && !remoteActive && !mirrored && !licenceRefusedHold) {
                    bridge.logger?.invoke("paused after the ad break, nobody paused: ${now.item?.name} resumed")
                    runCatching { resumePage() }
                }
            }
        }
        // The song asked is playing (or the ask is long gone): the fallback after a refusal (play the
        // list without picking the song) is armed again for the next command. Another song (the one
        // left playing, the Jam host's) does not count: seen with a play refused in a Jam, steps 1
        // and 2 went on in turns for minutes, each state of the old song resetting them.
        val asked = lastPlay
        if (asked == null || clock() - asked.third > LATE_REFUSED_WINDOW_MS ||
            (if (asked.second != null) s?.item?.uri == asked.second else s?.contextUri == asked.first)) refusalStep = 0
        // A transient empty report at the loop must not erase the song that just ended.
        val repeatBefore = lastTrackState ?: lastSong?.takeIf {
            clock() - lastSongUntil in 0..UNLOAD_NULL_MS
        }
        if (s?.item != null) lastSong = s
        else if (lastTrackState?.item != null) lastSongUntil = clock()
        // Another song: the stall was the last one's (the watcher judges this one on its next tick).
        if (s?.item?.uri != lastTrackState?.item?.uri) _stalled.value = false
        noteTrackChange(lastTrackState, s, adJustEnded)
        val shown = s?.let { followRepeat(it, repeatBefore) }
        lastTrackState = shown
        setPageState(shown)
        // A song plays, the user's pause over: nothing left to play from around a paused ad.
        if (s?.item != null && !s.isPaused && !pausedByUser) pickAfterPause = null
        // Reported (2.0.2-beta.3, log seen): Spotify refusing the audio licences (429), the page
        // skips on by itself through every song it gets no licence for, to the end of the list and
        // round again. The hold's one pause was sent while the page was loading the next song and
        // never took (the page kept playing for minutes): a song it settles on while held is
        // paused, once. Only until the hold's pause took (reported: "a pause before each song"
        // through the hold, every song the user played on paused again).
        if (!playbackHeld()) { heldPausedUri = null; heldPauseTook = false }
        else if (s?.item != null && s.isPaused) heldPauseTook = true
        else if (!heldPauseTook && s?.item != null && !s.isPaused && !s.isBuffering && s.item?.uri != heldPausedUri) {
            heldPausedUri = s.item?.uri
            bridge.logger?.invoke("playback held (Spotify limiting): ${s.item?.name} paused")
            scope.launch { runCatching { pause() } }
        }
        val i = liveIntent() ?: run { intent = null; return }
        val uri = s?.item?.uri
        when {
            uri == i.item.uri -> intent = null
            // The page has not moved yet (or reports nothing): during an ad or a reload the
            // screen stays on the intent. On a plain skip it shows what the page plays: a
            // skip the page lost must not leave the screen on a song that is not heard.
            uri == null || uri == i.from -> if (i.adSeen || skipsHeld) showIntent()
            // Somewhere else after an ad or a reload: the user's song is asked for again.
            i.adSeen || skipsHeld -> { showIntent(); scope.launch { runCatching { if (!skipsHeld) playIntent() } } }
            // Somewhere else on a plain skip: the page knows its queue best.
            else -> intent = null
        }
    }


    // ── Fades: a song cut off at full volume pops in the phone's speaker. Each skip or play
    // lowers the sound over ~0.2 s first and brings it back once the next song's sound starts. ──

    /** The volume a fade comes back to, while one is under way. */
    @Volatile private var fadeBackTo: Double? = null
    /** Level ramps of our own going down (a pause's), which the volume guard must not take for a lost fade. */
    private val levelRamps = java.util.concurrent.atomic.AtomicInteger(0)
    /** Until then the level is still moving (a rise just started, a ramp just ended): the guard leaves it be. */
    @Volatile private var levelHeldUntil = 0L
    private var fadeBack: kotlinx.coroutines.Job? = null

    /** The element back to the page player's own volume. */
    private suspend fun restoreLevel() {
        bridge.call("media.volume", buildJsonObject { put("restore", true) }, timeoutMs = LEVEL_TIMEOUT_MS, quiet = true)
    }

    /**
     * One call for a whole fade: the page applies [levels] one after the other while the host
     * does not wait for them (a call per step delayed the first sound by one round trip each).
     * [restore] ends on the page player's own volume, as [restoreLevel] does.
     */
    /**
     * [riseMs]: the page then holds the sound through the change of song and brings it up over that
     * long once the next song's own sound starts ('media.fade' rise, see riseOnSwitch), or after
     * [riseWaitMs] whatever happened.
     */
    private suspend fun fade(levels: List<Double>, restore: Boolean = false, ms: Long = FADE_STEP_MS, riseMs: Long? = null, riseWaitMs: Long = 0L) {
        bridge.call("media.fade", buildJsonObject {
            putJsonArray("steps") { levels.forEach { add(kotlinx.serialization.json.JsonPrimitive(it.coerceIn(0.0, 1.0))) } }
            put("ms", ms)
            put("restore", restore)
            riseMs?.let { r -> put("rise", buildJsonObject { put("ms", r); put("wait", riseWaitMs) }) }
        }, timeoutMs = LEVEL_TIMEOUT_MS, quiet = true)
    }

    /**
     * After a skip or the end of a crossfade: the page holds the sound until the next song's own
     * sound starts (see [fade]). This is the fall-back for a change it cannot see (the element
     * going on with the next song on the same clock): once the next song is reported playing, or
     * none was in [FADE_BACK_MAX_MS] (the skip was lost, the song before plays on), the level comes
     * up from wherever the page holds it. [from]: the song the change starts from.
     */
    private fun riseWhenSongPlays(from: String?, target: Double, riseMs: Long) {
        fadeBackTo = target
        fadeBack?.cancel()
        fadeBack = scope.launch {
            val moved = withTimeoutOrNull(FADE_BACK_MAX_MS) {
                _state.first { it != null && !it.isPaused && (it.item?.uri != from || it.positionMs < 1_500) }
            }
            if (moved != null) {
                // Buffering is nothing heard: waited for, bounded (a page that keeps saying
                // "buffering" while it plays must not leave the song's start down for long).
                withTimeoutOrNull(SWITCH_BUFFER_MAX_MS) { _state.first { it == null || !it.isBuffering } }
                delay(SWITCH_GRACE_MS)
            }
            // Ends on the page player's own volume: a volume change during the fade is kept.
            runCatching {
                bridge.call("media.rise", buildJsonObject { put("ms", riseMs); put("wait", RISE_WAIT_MS); put("keep", true) },
                    timeoutMs = LEVEL_TIMEOUT_MS, quiet = true)
            }.onFailure { runCatching { restoreLevel() } }
            // The call answers as the rise starts: the guard waits for the ramp to be over.
            levelHeldUntil = clock() + riseMs + LEVEL_HELD_GRACE_MS
            fadeBackTo = null
        }
    }

    private suspend fun withFade(block: suspend () -> Unit) {
        // A crossfade under way owns the level. Its ramp is ended without putting the level back:
        // a skip it is fading into (the song before an ad, played a second before the end) went up
        // to full volume for that second, which undid the crossfade.
        val crossfading = crossfadeArmed
        if (crossfading) disarmCrossfade()
        val s = _state.value
        // Nothing heard (paused, an ad muted): nothing to fade.
        if (s == null || s.isPaused || _adPlaying.value) { if (crossfading) runCatching { restoreLevel() }; block(); return }
        // The level as the watcher last read it: no extra round trip before the skip.
        val seen = lastMedia
        if (seen?.active == false) { if (crossfading) runCatching { restoreLevel() }; block(); return }
        val target = fadeBackTo ?: seen?.own?.takeIf { crossfading && it > 0.01 } ?: seen?.level?.takeIf { it > 0.01 } ?: 1.0
        val from = s.item?.uri
        val startedAt = clock()
        fadeBackTo = target
        fadeBack?.cancel()
        // The ramp runs in the page, so it goes down while the command is already on its way; the
        // page then holds the sound until the next song's own sound starts. From a crossfade, the
        // sound goes on down from where it is (a ramp along the curve), and the next song rises as
        // after a crossfade.
        val riseMs = if (crossfading) CROSSFADE_IN_MS else SKIP_RISE_MS
        runCatching {
            if (crossfading) {
                bridge.call("media.ramp", buildJsonObject { put("to", 0); put("ms", FADE_OUT_STEP_MS * FADE_OUT_STEPS.size) }, timeoutMs = LEVEL_TIMEOUT_MS, quiet = true)
                bridge.call("media.rise", buildJsonObject { put("ms", riseMs); put("wait", SWITCH_HOLD_MAX_MS); put("switch", true) }, timeoutMs = LEVEL_TIMEOUT_MS, quiet = true)
            } else fade(FADE_OUT_STEPS.map { it * target }, ms = FADE_OUT_STEP_MS, riseMs = riseMs, riseWaitMs = SWITCH_HOLD_MAX_MS)
        }
        var sent = false
        try {
            // The page changes song a few ms after the command: sent at once, the old song was cut
            // while still at -6 to -9 dB (heard as a click on every skip). The fade has to be down
            // before it goes; what the fade call and the caller took already counts. A sound
            // already down (a crossfade nearly over) waits for nothing. Cancelled or failed here,
            // the level comes back below like for any command that is not sent.
            val low = crossfading && (seen?.level ?: target) < target * SKIP_HOLD_LEVEL
            if (!low) delay(SKIP_HOLD_MS - (clock() - startedAt))
            block()
            sent = true
        } finally {
            if (sent) riseWhenSongPlays(from, target, riseMs)
            else {
                // Not sent (refused, the page reloading): the song before plays on, heard again now.
                fadeBackTo = null
                withContext(NonCancellable) { runCatching { restoreLevel() } }
            }
        }
    }

    /**
     * An extension plays the song itself and only mirrors it here (to count the listen): the page
     * keeps playing, unheard. Sent again on a new song, whose element can change.
     */
    override suspend fun setLocalMute(on: Boolean) {
        localMuted = on
        quietUri = if (on) _state.value?.item?.uri else null
        applyQuiet()
    }

    override val localMute: Boolean get() = localMuted

    @Volatile private var localMuted = false
    /** The song [localMuted] was last sent for: a new one is told again (its element can change). */
    @Volatile private var quietUri: String? = null

    private suspend fun applyQuiet() {
        runCatching { bridge.call("media.quiet", buildJsonObject { put("on", localMuted) }) }
    }

    /**
     * Crossfade length in seconds (0 = off). Only a fade is possible: the page plays one media
     * element, so two songs never overlap. Set by the app from the user's setting, at start and
     * whenever it changes.
     */
    @Volatile var crossfadeSeconds: Int = 0
        set(value) {
            val v = value.coerceIn(0, CROSSFADE_MAX_SECONDS)
            if (field == v) return
            field = v
            // Turned off while a ramp runs: the sound comes back at once rather than at the end
            // of a fade nobody wants any more.
            if (v == 0) {
                val was = crossfadeArmed
                disarmCrossfade()
                if (was) scope.launch { runCatching { restoreLevel() } }
            }
        }

    /**
     * The page's element level, ramped down over the last seconds of a song: one media element
     * means the next song cannot start before this one ends, so this is a fade, not an overlap.
     */
    @Volatile private var crossfadeArmed = false
    /** The song the ramp belongs to: the song after it (another uri) ends the fade-out. */
    @Volatile private var crossfadeUri: String? = null
    /** When the ramp will have reached its floor; past that the next song must have come. */
    @Volatile private var crossfadeUntil = 0L
    /** The fail-safe that puts the level back if the page never moved on. */
    private var crossfadeJob: kotlinx.coroutines.Job? = null
    /** Arms the fade-out on the dot when its window opens between two watcher ticks. */
    private var crossfadeTimer: kotlinx.coroutines.Job? = null

    private fun disarmCrossfade() {
        crossfadeArmed = false
        crossfadeUri = null
        crossfadeUntil = 0L
        crossfadeJob?.cancel()
        crossfadeJob = null
    }

    /** Ends a crossfade; [restore] puts the element back on the page player's own volume. */
    private suspend fun endCrossfade(restore: Boolean) {
        if (!crossfadeArmed) return
        disarmCrossfade()
        if (restore) runCatching { restoreLevel() }
    }

    /**
     * Called each watch tick: arms the fade-out when the song is inside its last
     * [crossfadeSeconds]. The ramp ends with the song (the window is what is left of it), so a
     * late tick only shortens the fade, it never runs past the next song.
     */
    private suspend fun tickCrossfade(s: PlayerState?, media: Media?, now: Long) {
        val secs = crossfadeSeconds
        // Off, or nothing to fade out of: an armed ramp is lifted (never left down).
        if (secs <= 0 || s == null || s.isPaused || _adPlaying.value) { endCrossfade(restore = true); return }
        val item = s.item ?: return
        if (item.kind == ItemKind.AD || item.durationMs <= 0) return
        // Another device is playing: this phone's element is not where the sound is, and a ramp
        // set on it would greet the user at 2% after a transfer back.
        if (media?.active == false) { endCrossfade(restore = true); return }
        val window = secs * 1000L
        val remaining = item.durationMs - s.positionAt(now)
        if (crossfadeArmed) {
            // The next song is handled where it is reported (see [onState]). Here: the song was
            // seeked back out of the window, or the setting was shortened under the ramp.
            if (item.uri != crossfadeUri || remaining > window) endCrossfade(restore = true)
            return
        }
        // The watcher ticks every WATCH_TICK_MS: a window that opens before the next tick is armed
        // on time by a timer, not up to a tick late (a 6 s crossfade lasted 4.3-4.5 s, measured).
        if (remaining > window && remaining <= window + WATCH_TICK_MS) {
            if (crossfadeTimer?.isActive != true) crossfadeTimer = scope.launch {
                delay(remaining - window)
                val st = _state.value ?: return@launch
                if (crossfadeArmed || st.isPaused || st.item?.uri != item.uri) return@launch
                tickCrossfade(st, lastMedia, clock())
            }
            return
        }
        if (remaining <= 0 || remaining > window) return
        // Nothing after it: a fade-out into silence is not a crossfade.
        if (s.next == null && upcoming().isEmpty()) return
        val own = (media?.own ?: 1.0).coerceIn(0.0, 1.0)
        // Silent already (volume at 0): there is nothing to fade.
        if (own <= 0.001) return
        armCrossfade(item.uri, remaining, own)
    }

    private suspend fun armCrossfade(uri: String, remainingMs: Long, own: Double) {
        val steps = (remainingMs / CROSSFADE_STEP_MS).toInt().coerceIn(2, CROSSFADE_MAX_STEPS)
        val per = (remainingMs / steps).coerceAtLeast(1L)
        // Down the ear's curve (the cubic taper of the resume and pause ramps: even steps to the
        // ear; linear in amplitude it stayed loud, then fell away in the last second), ending on
        // CROSSFADE_FLOOR of the page's own level: the last step lands on the song's end.
        val floor = Math.cbrt(CROSSFADE_FLOOR)
        val levels = (1..steps).map { i ->
            val p = 1.0 - (1.0 - floor) * i / steps
            (own * p * p * p).coerceAtLeast(CROSSFADE_SILENT)
        }
        crossfadeArmed = true
        crossfadeUri = uri
        crossfadeUntil = clock() + remainingMs + CROSSFADE_FAILSAFE_MS
        crossfadeJob?.cancel()
        // The hard floor: whatever the page does (a stall, a lost state, a reload, a song that
        // repeats), the sound is back a few seconds after the ramp was due. A volume left down
        // is the one failure the user cannot work around.
        crossfadeJob = scope.launch {
            delay(remainingMs + CROSSFADE_FAILSAFE_MS)
            if (crossfadeArmed) {
                bridge.logger?.invoke("crossfade left the sound down ${(remainingMs + CROSSFADE_FAILSAFE_MS) / 1000}s: volume restored")
                endCrossfade(restore = true)
            }
        }
        bridge.logger?.invoke("crossfade: ${remainingMs / 1000.0}s out on ${_state.value?.item?.name}")
        // One call: the page runs the whole ramp itself (see [fade]), then holds the sound through
        // the change of song and brings the next one up once its sound starts. It was brought up
        // on the state's word before: the page sets its own level on a new song, so the next one
        // mostly started at full volume, no fade-in heard (reported: "crossfade does nothing").
        runCatching { fade(levels, ms = per, riseMs = CROSSFADE_IN_MS, riseWaitMs = remainingMs + CROSSFADE_FAILSAFE_MS) }
            .onFailure { endCrossfade(restore = true) }
    }

    /**
     * The user's play (a pick, a list started). The page refuses every play while an ad is on
     * ("ad_disallow", measured 20/10), and each refusal walks [onRefused]'s steps, which play the
     * list from its start and turn shuffle on: the pick is held instead and played, in the order it
     * was given, when the ad is over (see [onState]).
     */
    /** The account's audio outside the page (the native engine), when the host has one; see [AccountAudio]. */
    @Volatile var audio: AccountAudio? = null
        set(value) {
            field = value
            audioWatch?.cancel()
            // Each change of the engine's playback is shown at once, page or no page.
            audioWatch = value?.now?.let { now -> scope.launch { now.collect { publish() } } }
            publish()
        }
    private var audioWatch: Job? = null

    /** The native engine plays the account's songs now (not the page's element). */
    private fun engineHolds(): Boolean = outside()?.holdsPlayback == true

    /** [audio] when it plays the account's songs now: turned on, and the page not the unheard mirror. */
    private fun outside(): AccountAudio? = audio?.takeIf { it.on && !mirroring }

    override suspend fun play(contextUri: Uri, trackUri: Uri?, index: Int?, positionMs: Long?, order: List<Uri>?) {
        userPlays()
        outside()?.let { a ->
            pausedByUser = false
            userMoveAt = clock()
            // It could not play (it did not start, the licence was refused): the page plays, as before it existed.
            // The shuffle goes with the play: a set_shuffling sent just before was undone by it.
            val shuffle = shuffleChosen ?: _state.value?.shuffle
            val played = runCatching { a.play(contextUri, trackUri, index, positionMs, order, shuffle) }
                .onFailure { bridge.logger?.invoke("play outside the page failed (${it.message}): the page plays") }
            if (played.isSuccess) return
        }
        playAsked(contextUri, trackUri, index, positionMs, order, origin = if (mirroring) "mirror" else "user")
    }

    /** [play], from the user or from a pick of theirs played again here; [origin] for the log. */
    private suspend fun playAsked(contextUri: Uri, trackUri: Uri?, index: Int?, positionMs: Long?, order: List<Uri>?, origin: String) {
        userMoveAt = clock()
        pickAfterPause = null
        // Held whether or not the song comes with its place in the list (SongPlayer.rowIndex).
        if (_adPlaying.value) {
            if (trackUri == null || trackUri != liveIntent()?.item?.uri) intent = null
            lastOrder = order?.let { contextUri to it }
            pausedByUser = false
            pickBeforeAd = Pick(contextUri, trackUri, clock(), order, positionMs, index)
            bridge.logger?.invoke("pick during an ad: ${trackUri ?: contextUri} held until the ad is over")
            return
        }
        pickBeforeAd = null
        // Reported (2.2.0, log seen): a song picked while our reload was under way went to the page
        // going away, and the old list went on instead. Held for the new page (ours, or any page
        // that went away and is not back yet), as during an ad, and played by [playPickAfterReload].
        // Not a mirror's play: the mirror sends its song again.
        if (!mirroring && (reloadUnderWay || (!bridge.ready.value && pageReadyAt > 0))) {
            if (trackUri == null || trackUri != liveIntent()?.item?.uri) intent = null
            lastOrder = order?.let { contextUri to it }
            pausedByUser = false
            pickAfterReload = Pick(contextUri, trackUri, clock(), order, positionMs, index)
            bridge.logger?.invoke("pick during a reload: ${trackUri ?: contextUri} held until the page is back")
            return
        }
        pickAfterReload = null
        playNow(contextUri, trackUri, index, positionMs, order, refusable = true, origin = origin)
    }

    /**
     * A play sent to the page; [refusable]: the user's own, whose refusal walks [onRefused]'s steps.
     * [keepsOrder] false: [order] is for this play only, later plays of the list go without it.
     */
    private suspend fun playNow(contextUri: Uri, trackUri: Uri? = null, index: Int? = null, positionMs: Long? = null, order: List<Uri>? = null, refusable: Boolean = false, keepsOrder: Boolean = true, origin: String) {
        // A Jam's own list (spotify:list:jam-list:<session>) kept from a Jam this account has left:
        // Spotify refuses every play of it (403 command_not_allowed), and a fallback, a mirror or a
        // resume then stood still on it (reported). The song plays alone instead.
        if (contextUri.startsWith(JAM_LIST_PREFIX) && trackUri != null && !inJamSession()) {
            bridge.logger?.invoke("play: the Jam's list $contextUri is over (no Jam now): the song plays alone")
            return playNow(trackUri, positionMs = positionMs, refusable = refusable, origin = origin)
        }
        // A new song picked (or the intent itself played): any earlier skip is over.
        if (trackUri == null || trackUri != liveIntent()?.item?.uri) intent = null
        lastOrder = order?.takeIf { keepsOrder }?.let { contextUri to it }
        lastPlay = Triple(contextUri, trackUri, clock())
        lastIndex = index
        lastPlayRefusable = refusable
        refusalStep = 0
        pausedByUser = false
        // The play starts the page on its own options: what it reports next is no answer to a set of ours.
        repeatRestoreUntil = clock() + REPEAT_RESTORE_MS
        repeatHeldUntil = 0L
        withFade {
            try {
                command("player.play", timeoutMs = PLAY_TIMEOUT_MS, label = origin, args = buildJsonObject {
                    put("contextUri", contextUri)
                    order?.let { uris -> putJsonArray("order") { uris.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } } }
                    trackUri?.let { put("trackUri", it) }
                    index?.let { put("index", it) }
                    positionMs?.let { put("positionMs", it) }
                })
            } catch (e: app.myspoti.spotify.bridge.BridgeException) {
                probeDrm()
                throw e
            }
        }
    }

    /**
     * Asks the page whether its WebView has a working Widevine ([drmMissing]), after a play failed:
     * never at boot, where the probe ran alongside the player's own EME set-up and the page of
     * 2.0.2-beta.1 never registered its device. The page asks once; later calls only repeat the answer.
     */
    private fun probeDrm() {
        scope.launch { runCatching { bridge.call("drm.probe", timeoutMs = 5_000, quiet = true) } }
    }

    /**
     * A playback command, sent again when the page was not able to take it yet: a page just
     * (re)loaded says "ready" a moment before Spotify Connect knows its device ("No such
     * device"), and a reload under way refuses everything ("not ready").
     */
    private suspend fun command(method: String, args: JsonObject = JsonObject(emptyMap()), timeoutMs: Long = 20_000, label: String? = null): kotlinx.serialization.json.JsonElement {
        var attempt = 0
        while (true) {
            try {
                return bridge.call(method, args, timeoutMs, label = label)
            } catch (e: app.myspoti.spotify.bridge.BridgeException) {
                val transient = e.message.orEmpty().let { m -> TRANSIENT.any { it in m } }
                if (!transient || ++attempt > COMMAND_RETRIES) throw e
                bridge.logger?.invoke("$method not taken (${e.message}): sent again")
                delay(COMMAND_RETRY_MS * attempt)
                if (!bridge.ready.value) withTimeoutOrNull(READY_WAIT_MS) { bridge.ready.first { it } }
            }
        }
    }

    override suspend fun pause() = pauseOver(PAUSE_MS)

    /** The sleep timer's end: the same pause over [fadeMs], and the level back for the next play. A cancel keeps the song playing. */
    override suspend fun pauseSlowly(fadeMs: Long) = pauseOver(fadeMs)

    private suspend fun pauseOver(fadeMs: Long) {
        outside()?.let { a -> pausedByUser = true; a.pause(); return }
        // A long fade is still playing: the song is only marked paused once it is.
        if (fadeMs <= PAUSE_MS) pausedByUser = true
        endCrossfade(restore = true)
        // Nothing playing (no state yet, or already paused): nothing to send. A pause on the
        // orphan session a fresh page finds makes the page claim it with a transfer first —
        // refused with a 402 on some free accounts, and the page then hung ~13 s on it (tester
        // report, beta.9). An ad keeps the old song's state paused-looking: sent then.
        val s = _state.value
        if (!_adPlaying.value && (s == null || s.isPaused)) {
            bridge.logger?.invoke("pause: nothing plays, not sent")
            return
        }
        val level = lastMedia?.level?.takeIf { it > 0.01 }
        val fadeOut = level != null && !_adPlaying.value && lastMedia?.active != false
        if (fadeOut) {
            fadeBack?.cancel()
            // A rise cancelled here leaves no one to end its mark; the pause owns the level now.
            fadeBackTo = null
            // The ramp takes the level below the guard's threshold on purpose: marked for it
            // (reported: the guard put the level back to full in the middle of the fade, a blast).
            levelRamps.incrementAndGet()
        }
        try {
            if (fadeOut) {
                runCatching {
                    bridge.call("media.ramp", buildJsonObject { put("to", 0); put("ms", fadeMs) }, timeoutMs = LEVEL_TIMEOUT_MS, quiet = true)
                }
                delay(fadeMs)
            } else if (fadeMs > PAUSE_MS) delay(fadeMs)   // nothing to fade (another device plays): the pause still comes at the end
            if (fadeMs > PAUSE_MS) {
                // Paused by the user meanwhile: nothing left to send.
                if (_state.value?.isPaused != false) return
                pausedByUser = true
            }
            command("player.pause")
        } finally {
            if (fadeOut) {
                // The element back at its level while paused (not heard): a new song started next
                // must not begin at the bottom of the fade.
                withContext(NonCancellable) { runCatching { fade(listOf(level!!), restore = true, ms = 1) } }
                levelHeldUntil = clock() + LEVEL_HELD_GRACE_MS
                levelRamps.decrementAndGet()
            }
        }
    }
    /*
     * The owner's call: a song coming back after a pause (or after another app had the sound)
     * rises from silence in half a second, and a pause fades out in a sixth of one, both along
     * the ear's curve, as the official app does. The ramps run in the page on its media element
     * ('media.rise', 'media.ramp'); the rise starts when the element's clock moves, and
     * [fadeBackTo]/[fadeBack] mark it as a fade meanwhile, so the silence watchdog and the volume
     * guard leave it be.
     */
    /** The page stopped answering while paused (see the watcher): checked before the next resume. */
    @Volatile private var pageSuspect = false

    override suspend fun resume() {
        userPlays()
        outside()?.let { a -> pausedByUser = false; a.resume(); return }
        // The user's pick around an ad they paused during: played now, in one play (held by
        // [playAsked] if an ad or a reload is still on), not the song before the ad.
        val pick = pickAfterPause?.takeIf { pausedByUser }
        pickAfterPause = null
        if (pick != null) {
            val now = _state.value
            val landed = if (pick.track != null) now?.item?.uri == pick.track else now?.contextUri == pick.context
            if (!landed) {
                pausedByUser = false
                bridge.logger?.invoke("resume after a paused ad: playing the song picked around it, ${pick.track ?: pick.context}")
                if (_adPlaying.value) { pickBeforeAd = pick; resumePage(); return }
                playOnNewPage { playAsked(pick.context, trackUri = pick.track, index = pick.index, positionMs = pick.positionMs, order = pick.order, origin = "pick held over a paused ad") }
                return
            }
        }
        resumePage()
    }

    /** [resume] without counting as the user's play (a restore after a reload, the end of an ad). */
    private suspend fun resumePage() {
        pausedByUser = false
        // A page that went silent while paused (frozen in the background): a quick ping, and if it
        // still says nothing, the page is loaded again and plays on where it was, instead of the
        // resume waiting out its 20 s timeout.
        // The watcher looks rarely while paused: a page it has not heard from for a while is pinged
        // too (twice: not suspect, one slow answer is not a frozen page).
        val unheard = !pageSuspect && clock() - lastMediaAt > RESUME_UNHEARD_MS
        if ((pageSuspect && !bridge.ping(RESUME_PING_MS)) || (unheard && !bridge.ping(RESUME_PING_MS) && !bridge.ping(RESUME_PING_MS))) {
            pageSuspect = false
            val s = lastTrackState
            bridge.logger?.invoke("resume: the page stopped answering while paused: loading it again")
            reloadAndPlayOn(s?.copy(isPaused = false, positionMs = s.positionMs, timestampMs = clock()), next = false)
            return
        }
        pageSuspect = false
        val stale = lastTrackState?.takeIf { authStalePending && reloadHost != null }
        if (stale != null) {
            authStalePending = false
            bridge.logger?.invoke("resume: the page's token went stale meanwhile: loading it again")
            reloadAndPlayOn(stale.copy(isPaused = false, timestampMs = clock()), next = false)
            return
        }
        val quiet = _adPlaying.value || localMuted || crossfadeArmed || _state.value?.isPaused == false || lastMedia?.active == false
        if (quiet) { command("player.resume"); return }
        fadeBackTo = lastMedia?.own?.takeIf { it > 0.01 } ?: 1.0
        fadeBack?.cancel()
        bridge.logger?.invoke("resume: rising from silence over $RISE_MS ms once the sound starts")
        // The rise is not waited for: it answers only once the sound has risen, and the resume
        // waited behind that answer. Started undispatched, its call is already on its way to the
        // page (ahead of the resume) when the launch returns; the page arms the silence and then
        // waits for the element's clock, as when it was awaited.
        scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            runCatching {
                bridge.call("media.rise", buildJsonObject { put("ms", RISE_MS); put("wait", RISE_WAIT_MS) },
                    timeoutMs = LEVEL_TIMEOUT_MS, quiet = true)
            }
        }
        try {
            command("player.resume")
        } catch (e: Throwable) {
            runCatching { restoreLevel() }
            fadeBackTo = null
            throw e
        }
        fadeBack = scope.launch {
            try { delay(RISE_WAIT_MS + RISE_MS + 200) } finally { fadeBackTo = null }
        }
    }
    override suspend fun seek(positionMs: Long) {
        outside()?.let { a -> a.seek(positionMs); return }
        endCrossfade(restore = true); command("player.seek", buildJsonObject { put("positionMs", positionMs) })
    }
    override suspend fun next() {
        pausedByUser = false
        pickBeforeAd = null
        pickAfterReload = null
        userMoveAt = clock()
        userPlays()
        outside()?.let { a -> a.next(); return }
        if (skipsHeld) { stepIntent(1); return }
        // Another device plays: a play by name pulls the sound back to the phone (reported), only the
        // plain skip reaches that device, however long it takes to report the next song.
        val elsewhere = remoteActive
        // Remembered: should the page go into an ad break instead, this song comes after it.
        val base = lastWithSong()
        val after = base?.next?.takeIf { it.kind == ItemKind.TRACK || it.kind == ItemKind.EPISODE }
        if (!elsewhere && after != null && base.item?.kind != ItemKind.AD) intent = Intent(base.contextUri, after, base.item?.uri, clock())
        val sent = intent.takeUnless { elsewhere }
        runCatching { withFade { command("player.next") } }.onFailure { e ->
            if (!heldError(e, base?.restrictions?.canSkipNext != false)) throw e
            stepIntent(1)
            return
        }
        // Reported (Pixel 8, beta.8): the page answers "ok" to the skip and stays on the song, so
        // the tap only dimmed the sound for a second. Still on it a moment later: the song the
        // skip pointed at is asked for by name.
        if (sent != null) scope.launch {
            delay(NEXT_LOST_MS)
            val i = liveIntent()
            if (i == null || i !== sent || skipsHeld || pausedByUser || remoteActive) return@launch
            val now = lastTrackState?.item?.uri
            if (now != null && now == i.from) {
                bridge.logger?.invoke("skip lost by the page: playing ${i.item.name}")
                runCatching { playIntent() }
            }
        }
    }

    /**
     * The page refused a skip because of an ad or a reload under way, not because the song
     * forbids it: "not allowed" counts only when the song's own restrictions allow the skip
     * (the ad itself is often reported seconds later).
     */
    private fun heldError(e: Throwable, allowed: Boolean): Boolean {
        val m = e.message.orEmpty()
        return skipsHeld || RELOADING.any { it in m } || (allowed && "not allowed" in m)
    }

    override suspend fun previous() {
        pausedByUser = false
        pickBeforeAd = null
        pickAfterReload = null
        userMoveAt = clock()
        userPlays()
        outside()?.let { a -> a.previous(); return }
        if (skipsHeld) { stepIntent(-1); return }
        intent = null
        withFade {
            val s = _state.value
            runCatching { command("player.previous") }.onFailure { e ->
                // Refused because of an ad or a reload: kept for after it, never a seek (that
                // restarted the ad).
                if (heldError(e, s?.restrictions?.canSkipPrevious == true)) { stepIntent(-1); return@onFailure }
                // Refused by the page (a restriction on the account or the context): the song
                // starts over, or the one before plays, as the button would have done.
                val pos = s?.positionAt(clock()) ?: 0L
                val before = s?.previous?.takeIf { it.kind == ItemKind.TRACK }?.uri
                val ctx = s?.contextUri
                bridge.logger?.invoke("previous refused (${e.message}): ${if (pos > 3_000 || before == null || ctx == null) "song restarted" else "song before played"}")
                if (pos > 3_000 || before == null || ctx == null) seek(0) else playNow(ctx, trackUri = before, order = lastOrder?.takeIf { it.first == ctx }?.second, origin = "previous refused")
            }
        }
    }
    /** A shuffle setting the page refused (during an ad), and when: set again once the ad is over. */
    @Volatile private var shufflePending: Pair<Boolean, Long>? = null

    /** The shuffle the user chose last: a play outside the page ([outside]) starts with it. */
    @Volatile private var shuffleChosen: Boolean? = null

    override suspend fun setShuffle(on: Boolean) {
        shuffleChosen = on
        shufflePending = null
        try {
            command("player.setShuffle", buildJsonObject { put("on", on) })
        } catch (e: app.myspoti.spotify.bridge.BridgeException) {
            if (!_adPlaying.value) throw e
            shufflePending = on to clock()
            bridge.logger?.invoke("shuffle ${if (on) "on" else "off"} refused during an ad: set again when it is over")
        }
    }

    /**
     * The repeat mode the user chose last, the one value of it ([followRepeat]): put back where a
     * play, a reload or the loop leaves the page on less, taken from the page where another device
     * changed it.
     */
    @Volatile private var wantedRepeat: Repeat? = null
    /** Until when a page reporting another repeat mode than [wantedRepeat] counts as having forgotten it. */
    @Volatile private var repeatRestoreUntil = 0L
    /** Until when the page has not answered our last set of [wantedRepeat] yet. */
    @Volatile private var repeatHeldUntil = 0L

    /**
     * Reported: the repeat button forgets what it was, and repeat one loops a song two or three
     * times, then the next song plays. A play command (and a reloaded page, an ad over) starts the
     * page on its own options, repeat off, and the page drops repeat one at the loop: the mode the
     * user chose is set again there. Anywhere else a repeat other than the choice is theirs (another
     * device), and never set from here while another device plays. [s] is returned as it is shown:
     * with the chosen repeat while the page is being told it.
     */
    private fun followRepeat(s: PlayerState, before: PlayerState?): PlayerState {
        repeatUnread = null
        val want = wantedRepeat ?: return s
        if (s.item == null || s.repeat == want) return s
        val now = clock()
        val afterPlay = now < repeatRestoreUntil
        val elsewhere = elsewhere()
        val how = RepeatRules.onReport(want, s.repeat, held = now < repeatHeldUntil, afterOwnPlay = afterPlay,
            atLoop = RepeatRules.atLoop(want, before, s, now), elsewhere = elsewhere, mirrored = mirrored)
        when (how) {
            RepeatRules.Report.SET_AGAIN -> {
                repeatRestoreUntil = 0L
                // Held from now: the next report may come before the set below is sent.
                repeatHeldUntil = now + RepeatRules.REPEAT_HOLD_MS
                bridge.logger?.invoke("repeat is ${s.repeat} ${if (afterPlay) "after a play" else "at the loop"}, $want was chosen: set again")
                scope.launch { runCatching { setRepeat(want) } }
            }
            RepeatRules.Report.THEIRS -> {
                wantedRepeat = s.repeat
                bridge.logger?.invoke("repeat is ${s.repeat} from the page${if (elsewhere == true) " (another device plays)" else ""}: $want no longer kept")
            }
            RepeatRules.Report.WAIT -> repeatUnread = s.repeat
            RepeatRules.Report.KEPT, RepeatRules.Report.IGNORED -> {}
        }
        return if (how == RepeatRules.Report.KEPT || how == RepeatRules.Report.SET_AGAIN || how == RepeatRules.Report.WAIT) s.copy(repeat = want) else s
    }

    /**
     * Another device plays, for the repeat: no for a play of ours since the watcher's last read,
     * which plays here and is newer than the element it read (seen in review: the first report of a
     * play taken back from the desktop, repeat off, was taken for the desktop's); null while the
     * element was never read (a cold start).
     */
    private fun elsewhere(): Boolean? = when {
        lastPlay?.let { it.third >= lastMediaAt } == true -> false
        lastMedia == null -> null
        else -> remoteActive
    }

    /** The repeat a page reported while [elsewhere] was not known ([RepeatRules.Report.WAIT]), judged at the watcher's read. */
    @Volatile private var repeatUnread: Repeat? = null

    /**
     * [repeatUnread] once the element is read: this page playing is told the choice again. Not
     * while another device plays (a page with no active device reads the same): nothing is sent,
     * and the choice is kept for a play here, that device's next report judged as any other.
     */
    private fun repeatOnRead() {
        val reported = repeatUnread ?: return
        repeatUnread = null
        val want = wantedRepeat ?: return
        if (elsewhere() != false) return
        repeatRestoreUntil = 0L
        repeatHeldUntil = clock() + RepeatRules.REPEAT_HOLD_MS
        bridge.logger?.invoke("repeat is $reported before the element was read, $want was chosen: set again")
        scope.launch { runCatching { setRepeat(want) } }
    }

    /**
     * The repeat mode chosen in an earlier run of the app: nothing is sent now. The page starts
     * with repeat off, so the mode is set again on the first song it reports once this phone is
     * known to be the one playing, as after a play.
     */
    fun restoreRepeat(mode: Repeat) {
        if (wantedRepeat != null) return
        wantedRepeat = mode
        repeatRestoreUntil = maxOf(repeatRestoreUntil, clock() + REPEAT_RESTORE_MS)
    }

    override suspend fun setRepeat(mode: Repeat) {
        wantedRepeat = mode
        repeatUnread = null
        repeatHeldUntil = clock() + RepeatRules.REPEAT_HOLD_MS
        bridge.call("player.setRepeat", buildJsonObject { put("mode", mode.ordinal) })
    }

    override suspend fun addToQueue(uris: List<Uri>) {
        bridge.call("queue.add", buildJsonObject { putJsonArray("uris") { uris.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } } })
    }

    override suspend fun removeFromQueue(items: List<NowPlaying>) {
        bridge.call("queue.remove", buildJsonObject {
            putJsonArray("items") { items.forEach { add(ref(it)) } }
        })
    }

    override suspend fun clearQueue() { bridge.call("queue.clear") }

    private fun ref(item: NowPlaying) = buildJsonObject { put("uri", item.uri); item.uid?.let { put("uid", it) } }

    /** The last state with a song: a reloading page reports none for a moment. */
    private fun lastWithSong(): PlayerState? = lastTrackState?.takeIf { it.item != null } ?: lastSong

    override suspend fun skipTo(item: NowPlaying) {
        pausedByUser = false
        userMoveAt = clock()
        userPlays()
        pickBeforeAd = null
        pickAfterReload = null
        val base = lastWithSong()
        if (skipsHeld) { setIntent(base?.contextUri, item, base?.item?.uri); return }
        if (base?.item?.kind != ItemKind.AD) intent = Intent(base?.contextUri, item, base?.item?.uri, clock())
        runCatching { withFade { command("queue.skipTo", ref(item)) } }.onFailure { e ->
            if (!heldError(e, base?.restrictions?.canSkipNext != false)) throw e
            setIntent(base?.contextUri, item, base?.item?.uri)
        }
    }

    override suspend fun playNext(uris: List<Uri>) {
        val first = _queue.value?.queued?.firstOrNull()
        if (first == null) { addToQueue(uris); return }
        insertInQueue(uris, first)
    }

    override suspend fun insertInQueue(uris: List<Uri>, before: NowPlaying) {
        bridge.call("queue.insert", buildJsonObject {
            putJsonArray("uris") { uris.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
            put("before", ref(before))
        })
    }

    override suspend fun moveInQueue(item: NowPlaying, target: NowPlaying, after: Boolean) {
        bridge.call("queue.reorder", buildJsonObject {
            putJsonArray("items") { add(ref(item)) }
            put(if (after) "after" else "before", ref(target))
        })
    }

    override suspend fun setSpeed(speed: Double) {
        bridge.call("player.setSpeed", buildJsonObject { put("speed", speed.coerceIn(0.5, 3.5)) })
    }

    override suspend fun volume(): Double = bridge.call("volume.get").jsonPrimitive.double

    override suspend fun setVolume(level: Double) {
        wantedVolume = level.coerceIn(0.0, 1.0)
        if (fadeBackTo != null) fadeBackTo = level.coerceIn(0.0, 1.0)
        bridge.call("volume.set", buildJsonObject { put("level", level.coerceIn(0.0, 1.0)) })
    }

    /** Sets the streaming quality now (when the page is up) and on every later page load. */
    suspend fun setAudioQuality(level: String) {
        audioQuality = level
        if (bridge.ready.value) applyAudioQuality()
    }

    /**
     * The page only mirrors a song the app plays itself, unheard (the app's Connect mirror): it then
     * streams at its lowest level ([MIRROR_QUALITY]) and loads no next song ahead, whatever
     * [audioQuality] says. Off: the user's quality again. Set before the song is played: the page
     * picks a song's file with the level it has when the song starts.
     */
    suspend fun setMirroring(on: Boolean) {
        if (mirroring == on) return
        mirroring = on
        publish()
        if (bridge.ready.value) applyAudioQuality()
    }

    @Volatile private var mirroring = false

    /**
     * A listen mirror plays here: the page's own ([mirroring]), or the native engine's (the page
     * then sees the engine's songs as the account's playback): the page's automatic follow-ups
     * (a skip chain's pause, a resume after an ad, repeat put back) leave it to the mirror.
     */
    private val mirrored: Boolean get() = mirroring || audio?.mirrors == true

    /** The level the page is given: the user's, or the lowest one while it only mirrors. */
    val streamingLevel: String get() = if (mirroring) MIRROR_QUALITY else audioQuality

    /** What the page plays now: bitrate (bit/s, 0 = unknown) and codec ("audio/mp4; codecs=…"). */
    suspend fun audioInfo(): Pair<Int, String> = runCatching {
        val o = bridge.call("player.audioInfo") as? JsonObject ?: return@runCatching 0 to ""
        (o["bitrate"]?.jsonPrimitive?.content?.toDoubleOrNull()?.toInt() ?: 0) to (o["mime"]?.jsonPrimitive?.content.orEmpty())
    }.getOrDefault(0 to "")

    private suspend fun applyAudioQuality() {
        val level = streamingLevel
        runCatching { bridge.call("player.setAudioQuality", buildJsonObject { put("level", level) }) }
            .onFailure { bridge.logger?.invoke("audio quality $level not set: ${it.message}") }
    }

    /** Pulls the state once, e.g. right after the bridge is ready. */
    suspend fun refresh() {
        onState(playerState(bridge.call("player.state") as? JsonObject))
        _queue.value = queue(bridge.call("player.queue"))
    }

    /**
     * Pulls the state, and the queue only when the song changed (the app's poll while another
     * device plays): the queue is the heavy half, tens of KB of JSON read for nothing every time.
     */
    suspend fun refreshState() {
        val before = _state.value?.item?.uri
        onState(playerState(bridge.call("player.state", quiet = true) as? JsonObject))
        if (_state.value?.item?.uri != before) _queue.value = queue(bridge.call("player.queue", quiet = true))
    }

    companion object {
        /**
         * An ad break the page has said nothing about for that long is over (its end was lost):
         * longer than any break seen (two 30 s ads), shorter than a song.
         */
        const val AD_FLAG_MAX_MS = 75_000L
        /** A reloaded page reports its restored state within that long. */
        const val RESTORE_WAIT_MS = 6_000L
        /** After a play on a new page, how long its queue is given to be reported before the user's is added again. */
        const val REQUEUE_WAIT_MS = 2_000L
        /** How long a play on a new page waits for its device to join Spotify Connect (see [awaitDeviceJoined]). */
        const val JOIN_WAIT_MS = 5_000L
        /** How long after a play on a new page its DEVICE_NOT_FOUND is waited for (seen 0.5 s after). */
        const val DEVICE_LOST_WAIT_MS = 3_000L
        /** A page going away reports "nothing plays" that long before it is gone, at most. */
        const val UNLOAD_NULL_MS = 5_000L
        /** Two kinds of command refused within this: the account is refused everything. */
        const val REFUSED_KINDS_MS = 120_000L
        /** The "refused everything" message, at most once in this. */
        const val REFUSED_TOLD_GAP_MS = 30_000L
        /** Errors of a page not able to take a command yet (see [command]). */
        val TRANSIENT = listOf("No such device", "not ready", "page reloaded", "local player is disabled")
        /** Refusals of a page being reloaded: a skip is then held (see [Intent]). */
        val RELOADING = listOf("local player is disabled", "not ready", "page reloaded")
        /** A held skip is forgotten after that long (an ad break and a reload fit in it). */
        const val INTENT_MAX_MS = 90_000L
        /** The intent is asked for at most that often. */
        const val INTENT_REPLAY_MS = 4_000L
        /** Plays of the intent at most, after an ad or a reload. */
        const val INTENT_PLAYS = 3
        const val COMMAND_RETRIES = 3
        const val COMMAND_RETRY_MS = 700L
        const val READY_WAIT_MS = 30_000L
        /** After a reload, the song is asked for again that many times at most. */
        const val RESTORE_ATTEMPTS = 3
        /** A restore that has not started the song by then is tried again. */
        const val RESTORE_CHECK_MS = 5_000L
        const val RESTORE_GAP_MS = 1_500L
        /** The media element read each tick; a page that cannot answer that fast is pinged. */
        const val MEDIA_TIMEOUT_MS = 3_000L
        const val LEVEL_TIMEOUT_MS = 3_000L
        /** A song the page says it plays here while its media element stands still that long. */
        const val SILENT_MS = 6_000L
        /** A licence answered 401 this recently makes a silent stall a stale token's (see [staleTokenStall]). */
        const val STALE_LICENCE_MS = 120_000L
        /** The first gap between two reloads for a stale token (see [authReloadGap]). */
        const val AUTH_RELOAD_GAP_MS = 60_000L
        const val AUTH_RELOAD_MAX_GAP_MS = 10 * 60_000L
        /** How long a play on a new page whose device has not joined waits for it before the page is loaded again. */
        const val UNJOINED_WAIT_MS = 20_000L
        /** Loads again for a page whose device never joined, before it is left to the next play. */
        const val UNJOINED_RELOADS = 2
        /** A play sent to a page whose device joined later than this is not sent again. */
        const val UNJOINED_REPLAY_MS = 10 * 60_000L
        const val SILENT = "silent"
        /** HTMLMediaElement.HAVE_FUTURE_DATA: below it, the element cannot play on. */
        const val HAVE_FUTURE_DATA = 3
        /** The next song is preloaded once the current one has played that long. */
        const val PRELOAD_AFTER_MS = 2_000L
        /** Licence answers of each page logged with what they were for (see [onLicence]). */
        const val PAGE_LICENCES_LOGGED = 3
        /** Licences refused within [LICENCE_REFUSALS_MS] that stop playback (see [LicenceRefusals]). */
        const val LICENCE_REFUSALS = 3
        const val LICENCE_REFUSALS_MS = 10_000L
        /**
         * Volume steps of the fade before a skip, [FADE_OUT_STEP_MS] apart: about 220 ms down to
         * -40 dB in even decibel steps, which the ear hears as one smooth bow-out. The 36 ms
         * three-step fade before it sounded like a cut. The command goes out while it runs, so
         * the next song is not delayed by it.
         */
        // Never 0: Chromium pauses a hidden page's media that (re)starts at volume 0 (a
        // "previous" that restarts the song stayed paused); the bridge keeps a floor.
        val FADE_OUT_STEPS: List<Double> = (1..13).map { Math.pow(10.0, -40.0 * it / 13 / 20) } + 0.002
        const val FADE_OUT_STEP_MS = 16L
        /**
         * The way back on the next song, from its first sound: only enough not to click, it is
         * the song's start.
         */
        const val SKIP_RISE_MS = 80L
        /**
         * A skip's command goes out this long after its fade began (the page changes song a few ms
         * after it), so the old song is cut at about -25 dB, not -6. This is the latency a skip pays.
         */
        const val SKIP_HOLD_MS = 120L
        /** A crossfade already below this share of the target level has nothing left to fade before a skip. */
        const val SKIP_HOLD_LEVEL = 0.1
        /** The volume guard keeps off this much longer after a ramp of ours, for a media read taken during it. */
        const val LEVEL_HELD_GRACE_MS = 400L
        /** All fade levels, for the floor check. */
        val FADE_STEPS = FADE_OUT_STEPS
        /**
         * The page holds the sound through a change of song at most this long (see [fade]): a skip
         * the page never answers, a next song that never starts. [riseWhenSongPlays] brings it up sooner.
         */
        const val SWITCH_HOLD_MAX_MS = 12_000L
        /** A next song still buffering after this is brought up anyway (see [riseWhenSongPlays]). */
        const val SWITCH_BUFFER_MAX_MS = 8_000L
        /** The next song plays, and the page has not brought it up itself after this: brought up from here. */
        const val SWITCH_GRACE_MS = 1_000L
        const val FADE_STEP_MS = 12L
        /** A skip the page reports no next song for in that long was lost: the song before is heard again. */
        const val FADE_BACK_MAX_MS = 2_500L
        /** Crossfade: the setting's range (seconds, 0 = off). */
        const val CROSSFADE_MAX_SECONDS = 12
        /**
         * Where a crossfade's ramp down ends, as a share of the page player's own level. Not 0:
         * the page keeps a floor on the element (see [FADE_STEPS]), and a media element set to 0
         * is paused by Chromium.
         */
        const val CROSSFADE_FLOOR = 0.02
        /** The floor the bridge itself keeps on a media element. */
        const val CROSSFADE_SILENT = 0.001
        /** One ramp step per that many ms; the page runs them itself (see [fade]). */
        private const val CROSSFADE_STEP_MS = 100L
        /** A 12 s ramp at 100 ms a step; more steps would not be heard. */
        private const val CROSSFADE_MAX_STEPS = 60
        /**
         * How long after the ramp was due the level is put back whatever the page did: a song
         * that stalled, a state lost in a reload, a repeated track. A volume left down is the
         * failure a user cannot work around.
         */
        const val CROSSFADE_FAILSAFE_MS = 6_000L
        /** The next song's ramp up: short, it is already playing. Ends on the own level. */
        /** The song after a crossfade rises back to its level in this long. */
        const val CROSSFADE_IN_MS = 600L
        /** Play commands: the page answers within a few seconds or not at all. */
        const val PLAY_TIMEOUT_MS = 10_000L
        /** A refusal that long after a play belongs to it. */
        const val REFUSED_WINDOW_MS = 5_000L
        const val JAM_LIST_PREFIX = "spotify:list:jam-list:"
        /** A play that leaves the page's repeat mode other than the user's: set again if it shows within this long. */
        const val REPEAT_RESTORE_MS = 20_000L
        /** An acknowledged command that never played is told 10 s after it was sent. */
        const val LATE_REFUSED_WINDOW_MS = 25_000L
        /** An ad starting this soon after a song is picked came in its place. */
        const val PICK_AD_MS = 8_000L
        /** After this the user has moved on: a pick is no longer replayed after its ad. */
        /** After an ad break, a pause nobody asked for is undone once the page's state has settled this long. */
        const val AD_END_SETTLE_MS = 1_200L
        const val PICK_REPLAY_MS = 120_000L
        /** How long a resume waits for a page that went silent while paused before loading it again. */
        const val RESUME_PING_MS = 1_500L
        /** A resumed song rises from silence to its level in this long, along the ear's curve. */
        const val RISE_MS = 500L
        /** How long the rise waits for sound before the level simply comes back. */
        const val RISE_WAIT_MS = 4_000L
        /** A pause first lowers the sound to silence in this long, along the same curve. */
        const val PAUSE_MS = 160L
        /** A plain skip still on its song after this: the page lost it. */
        const val NEXT_LOST_MS = 2_500L
        /** After the refusal fallback's transfer, before the song is asked again. */
        const val REFUSAL_SETTLE_MS = 600L
        /** Never two "frozen page" reloads closer than that. */
        const val FROZEN_RELOAD_GAP_MS = 60_000L
        /**
         * Consecutive silent ticks before the page is called dead and reloaded. One miss is not
         * enough: a busy main thread looks exactly like a frozen page for a couple of seconds, and
         * the reload that followed was itself the interruption the user complained about.
         */
        private const val FROZEN_MISSES = 2
        /** The volume is checked every that many ticks. */
        const val VOLUME_CHECK_TICKS = 3
        const val STUCK_MS = 8_000L
        /** A song over with nothing after it: the next song, quickly. */
        const val END_STUCK_MS = 1_000L
        private const val WATCH_TICK_MS = 2_000L
        /** The page's lowest streaming level (24 kbit/s), used while it only mirrors (see [setMirroring]). */
        const val MIRROR_QUALITY = "low"
        /** Nothing plays: the watcher looks only this often, or when the page pushes a state (see [watchStalls]). */
        private const val WATCH_IDLE_TICK_MS = 60_000L
        /** Another device of the account plays: the same, a little more often (its end of song, a transfer back missed). */
        private const val WATCH_ELSEWHERE_TICK_MS = 30_000L
        /** The watcher's look while the page only mirrors an extension's song (it was every 2 s). */
        private const val WATCH_MIRROR_TICK_MS = 10_000L
        /** After a pushed state woke the watcher, before it looks (a resumed element has moved by then). */
        private const val WAKE_SETTLE_MS = 400L
        /** A page not heard from for this long is pinged before a resume (see [resumePage]). */
        private const val RESUME_UNHEARD_MS = 10_000L
        /** The shortest look when a stall is due: the media element still moves visibly in it (over 0.25 s). */
        private const val DUE_TICK_MIN_MS = 500L
        /** Buffering longer than this on one song is a stall, not a slow network. */
        const val BUFFERING_STALL_MS = 25_000L
        /** An ad break the page has said nothing about for this long is read again (see [watchStalls]). */
        const val AD_STATE_RECHECK_MS = 4_000L

        /**
         * What a song or episode stuck at its end moves on to: the next playable item. B11: an
         * episode counts too; with tracks only, an episode stuck at its end found no target and
         * was played again from 0:00.
         */
        fun onwardItem(s: PlayerState, nextUp: List<NowPlaying>): NowPlaying? {
            val playable = { k: ItemKind -> k == ItemKind.TRACK || k == ItemKind.EPISODE }
            return s.next?.takeIf { playable(it.kind) } ?: nextUp.firstOrNull { playable(it.kind) }
        }

        fun onwardTarget(s: PlayerState, nextUp: List<NowPlaying>): String? = onwardItem(s, nextUp)?.uri

        /**
         * Why [s] looks stalled at [now], or null. A song is stalled when its clock ran past
         * its end without a new song, when it paused itself at its end with a song after it,
         * or while it buffers too long.
         */
        fun stallOf(s: PlayerState, now: Long, userPaused: Boolean): String? {
            val item = s.item ?: return null
            // Episodes stall exactly like songs once their duration is known (a podcast playing
            // silent with a frozen progress bar is the same bug); only ads are left out: they play
            // to their end.
            if (item.kind == ItemKind.AD || item.durationMs <= 0) return null
            if (s.isPaused) {
                val atEnd = s.positionMs >= item.durationMs - 2_000
                return if (atEnd && s.next != null && !userPaused) "paused at the end" else null
            }
            if (s.isBuffering) return if (now - s.timestampMs > BUFFERING_STALL_MS - STUCK_MS) "buffering" else null
            val clock = s.positionMs + ((now - s.timestampMs) * s.speed).toLong()
            return if (clock > item.durationMs + 1_500) "past the end" else null
        }

        /**
         * No stall reason this look, yet the silent stall of [key] keeps its attempt: the same song
         * ([uri]) is there and its element still stands ([still]). Its silence was counted again
         * from the repair, which hid it for [SILENT_MS].
         */
        fun silentKept(key: String?, uri: String?, still: Boolean): Boolean =
            still && uri != null && key == "$uri|$SILENT"

        /** A licence answered 401 [licence401Ago] ms ago, and the element waits for its key ([keyWaits]): the token is stale. */
        fun staleTokenStall(licence401Ago: Long, keyWaits: Int): Boolean =
            keyWaits > 0 && licence401Ago in 0..STALE_LICENCE_MS

        /** The gap before reload number [n] for a stale token: 1 min, doubled each time, at most [AUTH_RELOAD_MAX_GAP_MS]. */
        fun authReloadGap(n: Int): Long =
            (AUTH_RELOAD_GAP_MS shl (n - 1).coerceIn(0, 10)).coerceAtMost(AUTH_RELOAD_MAX_GAP_MS)
    }
}
