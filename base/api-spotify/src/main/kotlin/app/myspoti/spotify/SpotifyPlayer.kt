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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
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
 * Ads: hosts refuse the ad audio they know. Such an ad then stays current, buffering at
 * 0:00, and the page never moves on by itself; it refuses every skip and play ("ad_disallow").
 * After [AD_STUCK_MS] the page is reloaded (a new device, which the ad does not follow) and the
 * song after the last one is played. An ad that "plays" (it came without a file, or from
 * another market's ad server) is muted at once and got past the same way after
 * [AD_PLAYING_SKIP_MS]; if the page still holds on to it after that reload, it stays muted
 * until it ends. [adPlaying] says an ad is
 * on; ads are never exposed in [state] (the last song stays there, buffering).
 *
 * Stalls: on some phones the page stops between two songs (stuck past the end, paused at
 * the end with a next song, buffering for ever). After [STUCK_MS] the next song is played;
 * if that does not help either, [reloadHost] reloads the page and playback resumes there.
 */
class SpotifyPlayer(private val bridge: Bridge, private val scope: CoroutineScope) : Player {
    private val _state = MutableStateFlow<PlayerState?>(null)
    override val state: StateFlow<PlayerState?> = _state
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
    @Volatile private var adState: PlayerState? = null
    /**
     * An ad is muted: the page's own media element is set to an unheard volume ("media.mute"),
     * not the player's volume, which goes through Spotify Connect and is refused on some
     * accounts (DEVICE_DOES_NOT_SUPPORT_COMMAND), leaving the ad audible.
     */
    @Volatile private var adMuted = false
    /** When [muteForAd] was applied, so a mute outliving the break is lifted (never stuck). */
    @Volatile private var adMutedAt = 0L
    /** When the page last reported the ad break (it never reports the end of one). */
    @Volatile private var adStateAt = 0L
    /** The page's own ad managers were switched off for this break (see [BridgeHost]). */
    @Volatile private var adsOffSent = false
    /** How many times the ad mute was sent this break: the media element may appear late. */
    @Volatile private var adMuteTries = 0
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
     * A pick before an ad whose break ended in a reload of ours: played on the new page, however
     * long the reload took (it is the user's), unless they played or skipped something since.
     */
    @Volatile private var pickAfterReload: Pick? = null
    /**
     * The page was reloaded to get past an ad: the new page stays muted until a real song plays
     * (it can start on an ad before its state says so). See [muteForAd] / [onState].
     */
    @Volatile private var muteUntilSong = false
    /** When the user last played, skipped or picked: what an ad-avoiding skip started earlier must not undo. */
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

    /** Streaming quality ("auto", "low", "normal", "high", "very_high"), kept for every page load. */
    @Volatile var audioQuality: String = "auto"
        private set

    /**
     * Wakes the watcher before its next tick (an ad has just started). Declared before init:
     * the watcher starts there, at once on the main dispatcher.
     */
    private val wake = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)

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
                }
            }
        }
        scope.launch { watchStalls() }
        // One collector, so a mute and the unmute after it never run out of order.
        scope.launch { _adPlaying.collect { if (it) muteForAd() else unmuteAfterAd() } }
        // An ad on both ways on from the song now playing: the next song is played just before
        // it ends, so the page never reaches the ad (see AdBlock.adAhead).
        scope.launch { app.myspoti.spotify.bridge.AdBlock.ahead.collectLatest { a -> if (a != null) playBeforeAd(a) } }
        // A reloaded page starts on "auto": the chosen quality is set again.
        scope.launch {
            bridge.ready.collect {
                if (!it) {
                    // The ad ahead belonged to the page that went: the new one registers a new
                    // device, which that ad does not follow (seen: a play "before the ad" sent to a
                    // page still reloading, and refused).
                    app.myspoti.spotify.bridge.AdBlock.ahead.value = null
                    // The page went away (reloaded by Android, the WebView or the host): what
                    // played then is picked up again once the new page is there.
                    // A page that unloads reports "nothing plays" first: the song before that counts.
                    val now = System.currentTimeMillis()
                    val last = lastTrackState ?: lastSong?.takeIf { now - lastSongUntil < UNLOAD_NULL_MS }
                    if (playingAtLoss == null) playingAtLoss = last?.takeIf { s -> !s.isPaused && !pausedByUser && s.item != null }
                        ?.let { s -> s.copy(positionMs = s.positionAt(now), timestampMs = now) }
                    return@collect
                }
                val lost = playingAtLoss
                playingAtLoss = null
                // Read now: the hush below waits for the page (0.5 s seen), by when our own reload
                // can be over, and this page was then taken for one that was not ours (log seen: the
                // song before an ad resumed next to the mirror's).
                val own = ownReload
                val mirrored = mirroring
                // A new page forgot it was unheard (clip sound, a mirror): told again before it plays on.
                if (clipHushed || localMuted) { hushedUri = null; applyHush() }
                // A pick held over a reload that was not ours, or whose own play did not run: played now.
                when (AdRecoveryRules.pageBack(own, mirrored, pickAfterReload != null, lost != null, liveIntent() != null)) {
                    AdRecoveryRules.PageBack.MIRROR -> {
                        pickAfterReload = null
                        if (lost != null) bridge.logger?.invoke("page back: it mirrors a song played here, ${lost.item?.name} not resumed (the mirror plays its song again)")
                    }
                    AdRecoveryRules.PageBack.PICK -> scope.launch { delay(RESTORE_GAP_MS); playPickAfterReload() }
                    AdRecoveryRules.PageBack.RESUME -> scope.launch { runCatching { resumeAfterReload(lost ?: return@runCatching) } }
                    AdRecoveryRules.PageBack.INTENT -> scope.launch { runCatching { delay(RESTORE_GAP_MS); playIntent() } }
                    AdRecoveryRules.PageBack.NOTHING -> {}
                }
                repeatRestoreUntil = System.currentTimeMillis() + REPEAT_RESTORE_MS
                if (streamingLevel != "auto") applyAudioQuality()
                // The page keeps its volume: an app closed during a muted ad must not stay silent.
                runCatching { if (volume() == 0.0) setVolume(wantedVolume?.takeIf { v -> v > 0.0 } ?: 1.0) }
                // A reloaded page during an ad break plays the ad again: muted again at once. So is a
                // page reloaded to get past an ad, until a real song plays (an ad can come first).
                if (_adPlaying.value || muteUntilSong) muteForAd()
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
        if (recoveryHeld()) { bridge.logger?.invoke("reload held: a sign-in page is open or Spotify is limiting playback"); return }
        ownReload = true
        try {
            runCatching {
                if (!reloadAndWait(reload)) return@runCatching
                // The page only mirrors: the mirror plays its song again there, nothing from here.
                if (!AdRecoveryRules.playsAfterOwnReload(mirroring)) {
                    pickAfterReload = null
                    bridge.logger?.invoke("page back after a reload: it mirrors a song played here, the mirror plays it again")
                    return@runCatching
                }
                if (playPickAfterReload()) return@runCatching
                if (s == null) return@runCatching
                if (next) { delay(1_500); playOn(s) } else resumeAfterReload(s)
            }
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
        bridge.logger?.invoke("playing the song picked before the ad, on the new page: ${pick.track ?: pick.context}")
        runCatching { playNow(pick.context, trackUri = pick.track, index = pick.index, positionMs = pick.positionMs, order = pick.order, refusable = true) }
        return true
    }

    /**
     * Reloads the page and waits until it is ready again; nothing is played. For a page whose
     * Spotify Connect layer hangs (a transfer from a device that went away never ends).
     */
    suspend fun reloadPage(): Boolean {
        val reload = reloadHost ?: return false
        if (recoveryHeld()) { bridge.logger?.invoke("reload held: a sign-in page is open or Spotify is limiting playback"); return false }
        ownReload = true
        try {
            return reloadAndWait(reload)
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
        reload()
        return withTimeoutOrNull(45_000) { delay(1_500); bridge.ready.first { it && bridge.pagesGone != gone } } != null
    }

    /**
     * A new page after [lost] was playing: the page restores the session paused, or not at
     * all, and takes commands only once its device is registered again. The song goes on from
     * where it was, checked and tried again, unless the user paused or played something since.
     */
    private suspend fun resumeAfterReload(lost: PlayerState) {
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
                    resume()
                    if (kotlin.math.abs(s.positionMs - at) > 5_000) seek(at)
                } else {
                    // A state without a list would play the song alone, and Spotify then goes on
                    // with its own autoplay: the list of the song before it is the next best.
                    val ctx = lost.contextUri ?: lastSong?.contextUri ?: uri
                    playNow(ctx, trackUri = uri.takeIf { ctx != it }, positionMs = at, order = lastOrder?.takeIf { it.first == ctx }?.second)
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

    /**
     * Another device of the account is Spotify Connect's active one: this page's element stands
     * still while the state is that device's. Judged on the element too, as the active flag is read
     * every few seconds and lags a transfer to this phone (whose element then moves).
     */
    internal val remoteActive: Boolean get() = lastMedia?.let { it.active == false && it.paused } == true

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
        if (s.positionAt(System.currentTimeMillis()) < PRELOAD_AFTER_MS) return
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
    ) {
        fun describe() = "paused=$paused muted=$muted volume=$level own=$own t=${"%.1f".format(time)} ready=$readyState net=$networkState error=${error ?: "none"} active=$active"
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
        )
    }

    /**
     * Every [WATCH_TICK_MS]: the page answers (else it is frozen and reloaded), the song it
     * says it plays is heard (its media element moves), the sound is on, ads are got past,
     * and a song stuck between two others moves on.
     */
    private suspend fun watchStalls() {
        var key: String? = null
        var since = 0L
        var attempts = 0
        var adAttempts = 0
        var adReloaded = false
        var frozenReloadAt = 0L
        /** Consecutive ticks where the page answered nothing (see FROZEN_MISSES). */
        var frozenMisses = 0
        var tick = 0
        var lastMediaTime = -1.0
        var silentSince = 0L
        /** Since when the sound stands still, for [stalled]. */
        var stillSince = 0L
        fun notStalled() { stillSince = 0L; _stalled.value = false }
        while (true) {
            // A song plays (or an ad is on): the watcher looks every [WATCH_TICK_MS] to hear a
            // stall in seconds. Nothing plays: there is less to see, so it looks less often.
            val quiet = !_adPlaying.value && _state.value.let { it == null || it.isPaused }
            // An ad starting wakes it at once: its recovery starts AD_PLAYING_SKIP_MS after it.
            if (withTimeoutOrNull(if (quiet) WATCH_IDLE_TICK_MS else WATCH_TICK_MS) { wake.receive() } != null)
                delay(AD_PLAYING_SKIP_MS + 50)
            tick++
            // Repairs held (a sign-in page open, playback limited): nothing is judged, not even a
            // silent page, which is what a sign-in page looks like from here.
            if (!bridge.ready.value || ownReload || recoveryHeld()) { key = null; attempts = 0; silentSince = 0L; frozenMisses = 0; notStalled(); continue }
            val now = System.currentTimeMillis()
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
                    val playing = lastTrackState?.takeIf { !pausedByUser && !it.isPaused }
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
            _playsElsewhere.value = remoteActive
            val s = _state.value
            preloadNextSong(s, media)
            tickCrossfade(s, media, now)
            if (localMuted && s?.item?.uri != hushedUri) { hushedUri = s?.item?.uri; applyHush() }
            if (tick % VOLUME_CHECK_TICKS == 0) guardVolume(s, media)
            if (_adPlaying.value) {
                // The screens show the break (or the song before it, waiting), not a stall.
                notStalled()
                val ad = adState
                // The break just started: the page's own ad managers are switched off once more
                // (the bridge also does it on every state push). This does not keep the server from
                // queueing the next audio ad; the host leaves those out (AdBlock.withoutAds).
                if (!adsOffSent) {
                    adsOffSent = true
                    runCatching { bridge.call("ads.off") }
                }
                // The mute ran once when the break started, but the player's media element lives in
                // the player's own objects (not the DOM) and can appear late: sending it again here,
                // bounded, is what keeps an ad from being heard.
                if (adMuteTries < AD_MUTE_TRIES) {
                    adMuteTries++
                    runCatching { bridge.call("media.mute", buildJsonObject { put("on", true) }) }
                }
                // The mute outlived the break: the page went quiet without ever publishing the
                // next song, so nothing clears the ad flag and the phone stays silent for good
                // (reported as "no sound until the app is restarted"). The sound comes back
                // anyway, and the state is read again.
                if (adMutedAt > 0 && now - adMutedAt > AD_MUTE_MAX_MS) {
                    bridge.logger?.invoke("ad mute older than ${AD_MUTE_MAX_MS / 1000}s: unmuted")
                    runCatching { unmuteAfterAd() }
                    if (adStateAt > 0 && now - adStateAt > AD_MUTE_MAX_MS) {
                        adState = null; adStateAt = 0L
                        adAttempts = 0; adReloaded = false; adsOffSent = false
                        _adPlaying.value = false
                        runCatching { refresh() }
                    }
                    key = null; silentSince = 0L
                    continue
                }
                // The page's state push that ends the break can be lost on the way (the events buffer
                // drops the oldest under a stalled main thread), leaving the screen on "Advertisement"
                // through the next song: the page is asked again when it has said nothing for a while.
                if (now - adStateAt > AD_STATE_RECHECK_MS) {
                    val asked = runCatching { bridge.call("player.state", timeoutMs = MEDIA_TIMEOUT_MS, quiet = true) as? JsonObject }.getOrNull()
                    // Only a state with an item counts: one read between the ad and the next song
                    // ("nothing plays") would end the break and unmute the ad.
                    val st = asked?.let { playerState(it) }
                    if (st?.item != null) onState(st)
                    if (!_adPlaying.value) {
                        bridge.logger?.invoke("ad flag cleared: the page plays ${_state.value?.item?.name}")
                        key = null; silentSince = 0L
                        continue
                    }
                }
                // A refused ad sits at 0:00, buffering; a real one plays to its end.
                val refused = ad != null && (ad.isBuffering || ad.speed == 0.0) && now - adSince > AD_STUCK_MS
                // A video ad reports neither a position nor a speed the way an audio one does: it
                // is taken as playing from the start, so it is got past instead of waited on.
                val videoAd = ad?.item?.mediaType == "video"
                val playing = ad != null && !refused && (videoAd || now - adSince > AD_PLAYING_SKIP_MS) && !adReloaded
                val stuck = refused || playing || now - adSince > AD_BREAK_MAX_MS
                // Measured on 20/10 (desktop, free account, a real ad with its audio refused): the
                // page now keeps a refused ad current for good (20 min, buffering at 0:00), and
                // neither the in-stream manager's skipToNext nor playing the next song moves it
                // (every skip and play is refused with "ad_disallow"). Only a reload leaves it: the
                // new page registers a new device, which the ad does not follow, and the song after
                // it plays within a second. So the first try is the reload, at once; going through
                // the other levers first cost ~20 s of silence. If an ad is still there after
                // that, playing on is tried, then the reload again.
                if (stuck) {
                    adAttempts++
                    val last = lastTrackState
                    val how = AdRecoveryRules.stuck(adAttempts, playing = playing && !refused, mirrored = mirroring)
                    if (how == AdRecoveryRules.Stuck.NEXT) {
                        bridge.logger?.invoke("ad stuck on ${ad?.item?.name}: playing the next song (attempt $adAttempts)")
                        last?.let { runCatching { playOn(it) } }
                    } else if (how == AdRecoveryRules.Stuck.RELOAD_ONLY) {
                        // The page only mirrors (unheard): a stuck ad keeps the song from being
                        // counted and refuses every play, so the page is reloaded; nothing is
                        // resumed there, the mirror plays its song again once the device has joined.
                        adReloaded = true
                        muteUntilSong = true
                        pickBeforeAd = null
                        bridge.logger?.invoke("ad stuck on ${ad?.item?.name}: the page only mirrors, reloading it with nothing resumed (attempt $adAttempts)")
                        reloadAndPlayOn(null)
                    } else {
                        adReloaded = true
                        // Until a real song plays on the new page, it stays muted (see [muteUntilSong]).
                        muteUntilSong = true
                        bridge.logger?.invoke("ad stuck on ${ad?.item?.name}: reloading the player (attempt $adAttempts)")
                        if (reloadHost != null) reloadAndPlayOn(last) else last?.let { runCatching { playOn(it) } }
                    }
                    adSince = System.currentTimeMillis()
                }
                key = null; silentSince = 0L
                continue
            }
            // A song really playing again: the ad break is over.
            if (s != null && !s.isPaused && !s.isBuffering) { adAttempts = 0; adReloaded = false }
            // Another device of the account plays (Spotify Connect, the desktop app): the state is
            // its, followed and never repaired. A stall judged on its clock — "past the end" on a
            // state that came a moment late, "buffering" on its report — was answered with a seek or
            // a play of the next song, i.e. a command that skipped a track on that other device.
            if (remoteActive) {
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
            if (recoveryHeld()) { key = null; attempts = 0; continue }
            if (s == null || reason == null) { key = null; attempts = 0; continue }
            val k = "${s.item?.uri}|$reason"
            if (k != key) { key = k; since = now; attempts = 0; if (reason != SILENT) continue }
            val wait = when (reason) { "past the end" -> END_STUCK_MS; SILENT -> 0L; else -> STUCK_MS }
            if (now - since < wait) continue
            since = now
            attempts++
            silentSince = 0L
            bridge.logger?.invoke("player stalled ($reason) on ${s.item?.name}, attempt $attempts; media element: ${media?.describe() ?: "none"}")
            // A song stuck at its end moves on; one stuck in its middle goes on where it was.
            val onward = reason == "past the end" || reason == "paused at the end"
            // A frozen page answers nothing: straight to the reload.
            if (attempts == 1 && !bridge.ping()) attempts = 2
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
        // Its volume is set through Connect: with another device playing it would be that device's.
        if (remoteActive) return
        // A crossfade owns the element level until it ends: the guard must not undo the ramp.
        // Past its deadline, though, it is exactly the lost unmute it exists to catch.
        if (crossfadeArmed) {
            if (System.currentTimeMillis() >= crossfadeUntil) {
                bridge.logger?.invoke("crossfade overdue: volume restored")
                endCrossfade(restore = true)
            }
            return
        }
        // The unmute after an ad was lost (page busy): the song would play silent.
        if (adMuted) unmuteAfterAd()
        else if (media?.muted == true) {
            bridge.logger?.invoke("media element muted during a song: unmuted")
            runCatching { bridge.call("media.mute", buildJsonObject { put("on", false) }) }
        }
        val wanted = wantedVolume ?: 1.0
        if (wanted <= 0.0) return
        // The page's own volume is read with the media element (one call, no second round trip).
        val v = media?.playerVolume ?: runCatching { volume() }.getOrNull() ?: return
        if (v <= 0.0) {
            bridge.logger?.invoke("page silent during a song: volume back to $wanted")
            runCatching { setVolume(wanted) }
        }
        // A fade that never came back: the element back to the page player's own volume.
        // (Not while the song is hushed on purpose.)
        if (!clipHushed && !localMuted && media != null && media.active != false && media.own > 0.001 && media.level < media.own * 0.5) {
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
        val now = System.currentTimeMillis()
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
        }, timeoutMs = PLAY_TIMEOUT_MS)
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
                    lastPlay = last.copy(third = System.currentTimeMillis())
                    runCatching { playCtx(last.second, index) }
                }
            }
            3 -> {
                bridge.logger?.invoke("play refused by Spotify: playing the list on shuffle")
                scope.launch {
                    runCatching { setShuffle(true) }
                    lastPlay = last.copy(third = System.currentTimeMillis())
                    runCatching { playCtx(null) }
                }
            }
            else -> _refused.tryEmit(Unit)
        }
    }

    private fun refusedEverything() {
        refusalStep = 0
        val now = System.currentTimeMillis()
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
            if (reloadPage() && pick != null) runCatching { play(pick.first, trackUri = pick.second, index = lastIndex?.takeIf { pick.second != null }, order = order) }
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

    private fun liveIntent(): Intent? = intent?.takeIf { System.currentTimeMillis() - it.at < INTENT_MAX_MS }

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
        val now = System.currentTimeMillis()
        intent = Intent(context, item, from, now, adSeen = _adPlaying.value || liveIntent()?.adSeen == true)
        bridge.logger?.invoke("skip held (${if (_adPlaying.value) "ad" else "page reloading"}): ${item.name} goes on next")
        showIntent()
    }

    /** The screen shows the song the user is on, waiting, while the page is elsewhere. */
    private fun showIntent() {
        val i = liveIntent() ?: return
        val base = lastWithSong() ?: return
        val now = System.currentTimeMillis()
        _state.value = base.copy(item = i.item, positionMs = 0, timestampMs = now, isPaused = false, isBuffering = true)
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
                val now = System.currentTimeMillis()
                // Asked for a moment ago (a slow page): not again, wait for it below.
                if (uri != intentPlayedUri || now - intentPlayedAt >= INTENT_REPLAY_MS) {
                    intentPlayedUri = uri
                    intentPlayedAt = now
                    bridge.logger?.invoke("playing the song the skips pointed at: ${i.item.name}")
                    val ctx = i.context
                    runCatching {
                        if (ctx != null) playNow(ctx, trackUri = uri, order = lastOrder?.takeIf { it.first == ctx }?.second)
                        else playNow(uri)
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
     * Plays [a]'s next song [AD_AHEAD_LEAD_MS] before the song [a] names ends, unless another song
     * plays by then (a skip, a pick). Waits while it is paused or buffering.
     */
    private suspend fun playBeforeAd(a: app.myspoti.spotify.bridge.AdBlock.AdAhead) {
        fun isThat(item: NowPlaying?) = item != null && ((a.songUid != null && item.uid == a.songUid) || item.uri == a.songUri)
        // The reply comes as the song starts: its state may come a moment later.
        withTimeoutOrNull(AD_AHEAD_WAIT_MS) { _state.first { isThat(it?.item) } } ?: return
        // A pick or a skip made from here on is the user's: this song's end is no longer the one
        // to avoid the ad at (the page has not reported the new song yet, so the state alone
        // would still say "that song" and the pick would be overridden by the old queue's next).
        val movedAt = userMoveAt
        while (true) {
            if (!bridge.ready.value || userMoveAt != movedAt) return
            val s = _state.value ?: return
            if (!isThat(s.item)) return
            if (s.isPaused || s.isBuffering) {
                _state.first { it == null || !isThat(it.item) || (!it.isPaused && !it.isBuffering) }
                continue
            }
            val left = s.item!!.durationMs - s.positionAt(System.currentTimeMillis())
            if (left <= AD_AHEAD_LEAD_MS) break
            // Re-read now and then: a seek or a late state moves the end.
            delay((left - AD_AHEAD_LEAD_MS).coerceAtMost(AD_AHEAD_CHECK_MS))
        }
        val ctx = _state.value?.contextUri ?: return
        if (userMoveAt != movedAt) return
        // Another device plays this list (the ad is this phone's page's, not its): not skipped from here.
        if (remoteActive) return
        // Clearing the flow below cancels this very block (collectLatest): what follows must
        // finish, or the skip is sent and its put-back never is.
        withContext(NonCancellable) {
            app.myspoti.spotify.bridge.AdBlock.ahead.compareAndSet(a, null)
            bridge.logger?.invoke("ad after this song: ${a.nextUri} played before its end")
            if (skipPastAd(a)) return@withContext
            runCatching { playNow(ctx, trackUri = a.nextUri, order = lastOrder?.takeIf { it.first == ctx }?.second) }
                .onFailure { bridge.logger?.invoke("play before the ad refused: ${it.message}") }
        }
    }

    @Volatile private var adRestore: Job? = null

    /**
     * Skips within the current queue to [a]'s next song, which a new play would reshuffle (measured
     * 21/10, desktop, free account, shuffle on: a new `player.play` in a shuffled context
     * reshuffles the queue, songs vanish from what comes next). With an ad due, `queue.skipTo`
     * leaves the order alone but makes the ad current for ~180 ms (its MP3 is refused by the host),
     * then the page moves on by itself to the song AFTER the target: the target is lost. So it is
     * put back first in the user queue once the page plays on ([restoreAfterAd]).
     * False (nothing done): the target is not in the queue or the skip was refused; the caller plays it.
     */
    private suspend fun skipPastAd(a: app.myspoti.spotify.bridge.AdBlock.AdAhead): Boolean {
        val target = adTargetIn(upcoming(), a.nextUri) ?: return false
        val ok = runCatching { withFade { command("queue.skipTo", ref(target)) } }
            .onFailure { bridge.logger?.invoke("skip past the ad refused (${it.message}): playing ${target.name}") }
            .isSuccess
        if (!ok) return false
        adRestore?.cancel()
        adRestore = scope.launch { restoreAfterAd(a, target) }
        return true
    }

    /**
     * Waits for the song after the ad ([AD_RESTORE_WAIT_MS] at most), then queues [target] first,
     * unless the page plays it itself. `queue.insert` before the first queued item puts it ahead of
     * the user's own queue ([playNext]); with an empty user queue, `queue.add` does (the user queue
     * plays before the context).
     */
    private suspend fun restoreAfterAd(a: app.myspoti.spotify.bridge.AdBlock.AdAhead, target: NowPlaying) {
        val now = withTimeoutOrNull(AD_RESTORE_WAIT_MS) { _state.first { adSkipSettled(it?.item, a) }?.item }
        if (now != null && now.uri == target.uri) { bridge.logger?.invoke("skip past the ad: ${target.name} plays, nothing to put back"); return }
        runCatching { playNext(listOf(target.uri)) }
            .onSuccess { bridge.logger?.invoke("skip past the ad: ${target.name} put back to play next (page on ${now?.name ?: "nothing yet"})") }
            .onFailure { bridge.logger?.invoke("skip past the ad: ${target.name} not put back: ${it.message}") }
    }

    private suspend fun playOn(s: PlayerState) {
        // The user skipped meanwhile: their song, not the one after the last state seen.
        if (playIntent()) return
        // Or picked one during the ad that is being got past: that one, in the order it was picked.
        pickBeforeAd?.takeIf { System.currentTimeMillis() - it.at < PICK_REPLAY_MS }?.let { pick ->
            pickBeforeAd = null
            bridge.logger?.invoke("playing the song picked during the ad: ${pick.track ?: pick.context}")
            playNow(pick.context, trackUri = pick.track, index = pick.index, positionMs = pick.positionMs, order = pick.order, refusable = true)
            return
        }
        val ctx = s.contextUri
        val target = onwardTarget(s, _queue.value?.nextUp.orEmpty())
        // A new play reshuffles a shuffled list (measured 21/10) and does not find a song the user
        // queued in the list: both go on within the queue the page has, as a skip would.
        val inQueue = target?.let { t -> upcoming().firstOrNull { it.uri == t } }
        if (inQueue != null && (s.shuffle || inQueue.provider == "queue") &&
            runCatching { command("queue.skipTo", ref(inQueue), timeoutMs = PLAY_TIMEOUT_MS) }.isSuccess) return
        when {
            ctx != null && target != null -> playNow(ctx, trackUri = target, order = lastOrder?.takeIf { it.first == ctx }?.second)
            target != null -> next()
            // Nothing after it: a song is started again (the page's own end-of-context behaviour);
            // an episode is left finished rather than replayed from the start.
            ctx != null && s.item?.kind == ItemKind.TRACK -> playNow(ctx, trackUri = s.item?.uri)
        }
    }

    private val skipChain = TrackChanges.Chain()
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
        val now = System.currentTimeMillis()
        val played = old.positionAt(now)
        val sinceCommand = now - maxOf(userMoveAt, lastPlay?.third ?: 0L)
        val cause = TrackChanges.cause(played, from.durationMs, sinceCommand, adJustEnded, crossfadeArmed)
        bridge.logger?.invoke("track change: ${from.name} -> ${to.name} (${cause.text}, left at ${TrackChanges.clock(played)} of ${TrackChanges.clock(from.durationMs)}${if (playbackHeld()) ", playback held" else ""})")
        if (playbackHeld()) { skipChain.reset(); return }
        if (skipChain.record(cause, played, now)) {
            bridge.logger?.invoke("skip chain: the page moved on by itself three times in a row after a few seconds each (now on ${to.name}): paused")
            _skipChained.tryEmit(Unit)
            scope.launch { runCatching { pause() } }
        }
    }

    private fun onState(s: PlayerState?) {
        stateSeq++
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
                adSince = System.currentTimeMillis()
                adMuteTries = 0
                bridge.logger?.invoke("ad break started")
                app.myspoti.spotify.report.Refusal402.adBreak()
                // A song picked a moment ago and not playing yet: the ad came in its place.
                val p = lastPlay
                val came = p?.takeIf {
                    adSince - it.third < PICK_AD_MS && it.second != null && lastTrackState?.item?.uri != it.second
                }?.let { Pick(it.first, it.second, it.third, lastOrder?.takeIf { o -> o.first == it.first }?.second, index = lastIndex) }
                // A pick held from an earlier break stays until it was played or went stale. Not
                // while the page only mirrors: that play was the mirror's, which sends its song again.
                pickBeforeAd = if (AdRecoveryRules.keepsPickBeforeAd(mirroring)) came ?: pickBeforeAd else null
            }
            adState = s
            adStateAt = System.currentTimeMillis()
            if (!_adPlaying.value) wake.trySend(Unit)
            _adPlaying.value = true
            intent = intent?.copy(adSeen = true)
            _state.value = lastTrackState?.copy(isBuffering = true) ?: s.copy(item = null)
            showIntent()
            return
        }
        val adJustEnded = _adPlaying.value
        _adPlaying.value = false
        if (adJustEnded) bridge.logger?.invoke("ad break over after ${(System.currentTimeMillis() - adSince) / 1000} s: ${s?.item?.name ?: "nothing"}${if (s?.isPaused == true) " (paused)" else ""}")
        // A page reloaded to get past an ad: heard again only once a real song plays on it.
        if (muteUntilSong && s?.item != null && !s.isPaused && !s.isBuffering) {
            muteUntilSong = false
            // The ad's mute only: a page that mirrors stays unheard ('media.mute' keeps the hush).
            bridge.logger?.invoke("a song plays after the reload: ${s.item?.name} ${if (localMuted || clipHushed) "out of the ad's mute, still unheard (hushed)" else "heard again"}")
            scope.launch { unmuteAfterAd() }
        }
        val pick = pickBeforeAd
        if (adJustEnded && pick != null && !AdRecoveryRules.keepsPickBeforeAd(mirroring)) {
            pickBeforeAd = null
            bridge.logger?.invoke("song picked before the ad: left to the mirror (the page only mirrors)")
        } else if (adJustEnded && pick != null) {
            pickBeforeAd = null
            // Reported (beta.9): a song picked in another playlist, an ad, then the old queue went
            // on and the song had to be picked again. Not on it after the ad: asked for again.
            // A pick of a whole list (no song) has landed when the page plays that list.
            val landed = if (pick.track != null) s?.item?.uri == pick.track else s?.contextUri == pick.context
            if (!landed && System.currentTimeMillis() - pick.at < PICK_REPLAY_MS && liveIntent() == null) {
                // The break "ended" because our own reload took the page away (an ad stuck): the
                // page going away takes no command ("local player is disabled"). The pick waits
                // for the new page and is played there ([playPickAfterReload]).
                if (ownReload || !bridge.ready.value) {
                    pickAfterReload = pick
                    bridge.logger?.invoke("song picked before the ad: played once the page is back")
                } else {
                    bridge.logger?.invoke("song picked before the ad not played: asked again")
                    // With the order it was picked in: a sorted list or a smart shuffle is not the list's own.
                    scope.launch { runCatching { play(pick.context, trackUri = pick.track, index = pick.index, positionMs = pick.positionMs, order = pick.order) } }
                }
            }
        }
        shufflePending?.takeIf { System.currentTimeMillis() - it.second < PICK_REPLAY_MS }?.let { (on, _) ->
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
                    lastPlay?.third == playedAt && !remoteActive && !mirroring) {
                    bridge.logger?.invoke("paused after the ad break, nobody paused: ${now.item?.name} resumed")
                    runCatching { resume() }
                }
            }
        }
        adsOffSent = false
        adMuteTries = 0
        // A real song is playing: the fallback after a refusal (play the list without picking the
        // song) is armed again for the next command, not held once per five seconds for all of them.
        refusalStep = 0
        if (s?.item != null) lastSong = s
        else if (lastTrackState?.item != null) lastSongUntil = System.currentTimeMillis()
        // Another song: the stall was the last one's (the watcher judges this one on its next tick).
        if (s?.item?.uri != lastTrackState?.item?.uri) _stalled.value = false
        noteTrackChange(lastTrackState, s, adJustEnded)
        lastTrackState = s
        _state.value = s
        // Reported (2.0.2-beta.3, log seen): Spotify refusing the audio licences (429), the page
        // skips on by itself through every song it gets no licence for, to the end of the list and
        // round again. The hold's one pause was sent while the page was loading the next song and
        // never took (the page kept playing for minutes): each song it settles on while held is
        // paused, once.
        if (!playbackHeld()) heldPausedUri = null
        else if (s?.item != null && !s.isPaused && !s.isBuffering && s.item?.uri != heldPausedUri) {
            heldPausedUri = s.item?.uri
            bridge.logger?.invoke("playback held (Spotify limiting): ${s.item?.name} paused")
            scope.launch { runCatching { pause() } }
        }
        // Reported: the repeat button forgets what it was. A play command (and a reloaded page) starts
        // the page on its own options, repeat off; the mode the user chose is set again, once, when the
        // first song of that play reports another. Only within a few seconds of our own play: a repeat
        // changed from another device later is theirs.
        wantedRepeat?.let { want ->
            if (s?.item != null && s.repeat != want && System.currentTimeMillis() < repeatRestoreUntil) {
                repeatRestoreUntil = 0L
                bridge.logger?.invoke("repeat is ${s.repeat} after a play, $want was chosen: set again")
                scope.launch { runCatching { setRepeat(want) } }
            }
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
        if (s == null || s.isPaused || _adPlaying.value || adMuted) { if (crossfading) runCatching { restoreLevel() }; block(); return }
        // The level as the watcher last read it: no extra round trip before the skip.
        val seen = lastMedia
        if (seen?.active == false) { if (crossfading) runCatching { restoreLevel() }; block(); return }
        val target = fadeBackTo ?: seen?.own?.takeIf { crossfading && it > 0.01 } ?: seen?.level?.takeIf { it > 0.01 } ?: 1.0
        val from = s.item?.uri
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
     * The song unheard on this phone while it keeps playing (the music video's own sound is heard
     * instead, see the app's clip sound). Sent again on each new song: the element can change.
     */
    suspend fun setHushed(on: Boolean): Int {
        clipHushed = on
        return applyHush()
    }

    /**
     * An extension plays the song itself and only mirrors it here (to count the listen): the page
     * keeps playing, unheard. Sent again on a new song, whose element can change.
     */
    override suspend fun setLocalMute(on: Boolean) {
        localMuted = on
        hushedUri = if (on) _state.value?.item?.uri else null
        applyHush()
    }

    override val localMute: Boolean get() = localMuted

    @Volatile private var clipHushed = false
    @Volatile private var localMuted = false
    @Volatile private var hushedUri: String? = null

    private suspend fun applyHush(): Int =
        runCatching { bridge.call("media.hush", buildJsonObject { put("on", clipHushed || localMuted) }).toString().trim('"').toIntOrNull() ?: 0 }.getOrDefault(0)

    private suspend fun muteForAd() = runCatching {
        adMuted = true
        adMutedAt = System.currentTimeMillis()
        var muted = bridge.call("media.mute", buildJsonObject { put("on", true) })
        // No media element reached (the page is reloading, or the ad is taking the stream over):
        // once more before the count is trusted.
        if (muted.toString().trim('"').toIntOrNull() == 0) {
            delay(200)
            muted = bridge.call("media.mute", buildJsonObject { put("on", true) })
        }
        bridge.logger?.invoke("ad muted ($muted media elements)")
    }

    private suspend fun unmuteAfterAd() = runCatching {
        if (!adMuted) return@runCatching
        bridge.call("media.mute", buildJsonObject { put("on", false) })
        adMuted = false
        adMutedAt = 0L
        // The song after an ad is preloaded again: the key (current|next) is usually the same as
        // before the break, so without this the preload of that very song was skipped and the next
        // skip after an ad was slow.
        preloadedFor = null
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

    /** True while a crossfade is fading a song out, or the next one in. */
    fun isCrossfading(): Boolean = crossfadeArmed

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
                tickCrossfade(st, lastMedia, System.currentTimeMillis())
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
        crossfadeUntil = System.currentTimeMillis() + remainingMs + CROSSFADE_FAILSAFE_MS
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
     * was given, when the ad is over (see [onState]) or when the ad recovery plays on ([playOn]).
     */
    override suspend fun play(contextUri: Uri, trackUri: Uri?, index: Int?, positionMs: Long?, order: List<Uri>?) {
        userMoveAt = System.currentTimeMillis()
        // The ad due after the song that played before: a new play brings its own queue, and the
        // put-back of an earlier ad skip would queue that old song after the pick.
        adRestore?.cancel()
        app.myspoti.spotify.bridge.AdBlock.ahead.value = null
        // Held whether or not the song comes with its place in the list (SongPlayer.rowIndex).
        if (_adPlaying.value) {
            if (trackUri == null || trackUri != liveIntent()?.item?.uri) intent = null
            lastOrder = order?.let { contextUri to it }
            pausedByUser = false
            pickBeforeAd = Pick(contextUri, trackUri, System.currentTimeMillis(), order, positionMs, index)
            bridge.logger?.invoke("pick during an ad: ${trackUri ?: contextUri} held until the ad is over")
            return
        }
        pickBeforeAd = null
        pickAfterReload = null
        playNow(contextUri, trackUri, index, positionMs, order, refusable = true)
    }

    /** A play sent to the page; [refusable]: the user's own, whose refusal walks [onRefused]'s steps. */
    private suspend fun playNow(contextUri: Uri, trackUri: Uri? = null, index: Int? = null, positionMs: Long? = null, order: List<Uri>? = null, refusable: Boolean = false) {
        // A new song picked (or the intent itself played): any earlier skip is over.
        if (trackUri == null || trackUri != liveIntent()?.item?.uri) intent = null
        lastOrder = order?.let { contextUri to it }
        lastPlay = Triple(contextUri, trackUri, System.currentTimeMillis())
        lastIndex = index
        lastPlayRefusable = refusable
        refusalStep = 0
        pausedByUser = false
        repeatRestoreUntil = System.currentTimeMillis() + REPEAT_RESTORE_MS
        withFade {
            try {
                command("player.play", timeoutMs = PLAY_TIMEOUT_MS, args = buildJsonObject {
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
    private suspend fun command(method: String, args: JsonObject = JsonObject(emptyMap()), timeoutMs: Long = 20_000): kotlinx.serialization.json.JsonElement {
        var attempt = 0
        while (true) {
            try {
                return bridge.call(method, args, timeoutMs)
            } catch (e: app.myspoti.spotify.bridge.BridgeException) {
                val transient = e.message.orEmpty().let { m -> TRANSIENT.any { it in m } }
                if (!transient || ++attempt > COMMAND_RETRIES) throw e
                bridge.logger?.invoke("$method not taken (${e.message}): sent again")
                delay(COMMAND_RETRY_MS * attempt)
                if (!bridge.ready.value) withTimeoutOrNull(READY_WAIT_MS) { bridge.ready.first { it } }
            }
        }
    }

    override suspend fun pause() {
        pausedByUser = true
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
        val fadeOut = level != null && !_adPlaying.value && !adMuted && lastMedia?.active != false
        if (fadeOut) {
            fadeBack?.cancel()
            runCatching {
                bridge.call("media.ramp", buildJsonObject { put("to", 0); put("ms", PAUSE_MS) }, timeoutMs = LEVEL_TIMEOUT_MS, quiet = true)
            }
            delay(PAUSE_MS)
        }
        try {
            command("player.pause")
        } finally {
            // The element back at its level while paused (not heard): a new song started next
            // must not begin at the bottom of the fade.
            if (fadeOut) runCatching { fade(listOf(level!!), restore = true, ms = 1) }
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
        pausedByUser = false
        // A page that went silent while paused (frozen in the background): a quick ping, and if it
        // still says nothing, the page is loaded again and plays on where it was, instead of the
        // resume waiting out its 20 s timeout.
        if (pageSuspect && !bridge.ping(RESUME_PING_MS)) {
            pageSuspect = false
            val s = lastTrackState
            bridge.logger?.invoke("resume: the page stopped answering while paused: loading it again")
            reloadAndPlayOn(s?.copy(isPaused = false, positionMs = s.positionMs, timestampMs = System.currentTimeMillis()), next = false)
            return
        }
        pageSuspect = false
        val quiet = _adPlaying.value || adMuted || localMuted || clipHushed || crossfadeArmed || _state.value?.isPaused == false || lastMedia?.active == false
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
    override suspend fun seek(positionMs: Long) { endCrossfade(restore = true); command("player.seek", buildJsonObject { put("positionMs", positionMs) }) }
    override suspend fun next() {
        pausedByUser = false
        pickBeforeAd = null
        pickAfterReload = null
        userMoveAt = System.currentTimeMillis()
        adRestore?.cancel()
        if (skipsHeld) { stepIntent(1); return }
        // An ad is due right after this song: Spotify sends its skip to the ad too, and a page that
        // steps onto an ad that way stays on it (its audio refused). Going to the next song by name
        // gets past it within a second (measured 20/10, desktop), as at the end of the song.
        // Played as a skip within the queue now, not a new play (that reshuffles; see [skipPastAd]).
        val due = app.myspoti.spotify.bridge.AdBlock.ahead.value
        val ctx = _state.value?.contextUri
        if (due != null && ctx != null && adDueFor(due, _state.value?.item)) {
            app.myspoti.spotify.bridge.AdBlock.ahead.compareAndSet(due, null)
            bridge.logger?.invoke("next with an ad due: ${due.nextUri} skipped to instead of a plain skip")
            if (!skipPastAd(due)) playNow(ctx, trackUri = due.nextUri, order = lastOrder?.takeIf { it.first == ctx }?.second)
            return
        }
        // Remembered: should the page go into an ad break instead, this song comes after it.
        val base = lastWithSong()
        val after = base?.next?.takeIf { it.kind == ItemKind.TRACK || it.kind == ItemKind.EPISODE }
        if (after != null && base.item?.kind != ItemKind.AD) intent = Intent(base.contextUri, after, base.item?.uri, System.currentTimeMillis())
        val sent = intent
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
            if (i == null || i !== sent || skipsHeld || pausedByUser) return@launch
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
        userMoveAt = System.currentTimeMillis()
        adRestore?.cancel()
        if (skipsHeld) { stepIntent(-1); return }
        intent = null
        // Spotify points skip_prev of a song with an ad due at the ad as well (measured 21/10), so
        // a step back would land on the ad: the song starts over, the ad stays due for its end.
        val due = app.myspoti.spotify.bridge.AdBlock.ahead.value
        if (adDueFor(due, _state.value?.item)) {
            bridge.logger?.invoke("previous with an ad due: song restarted instead of a step back onto the ad")
            seek(0)
            return
        }
        withFade {
            val s = _state.value
            runCatching { command("player.previous") }.onFailure { e ->
                // Refused because of an ad or a reload: kept for after it, never a seek (that
                // restarted the ad).
                if (heldError(e, s?.restrictions?.canSkipPrevious == true)) { stepIntent(-1); return@onFailure }
                // Refused by the page (a restriction on the account or the context): the song
                // starts over, or the one before plays, as the button would have done.
                val pos = s?.positionAt(System.currentTimeMillis()) ?: 0L
                val before = s?.previous?.takeIf { it.kind == ItemKind.TRACK }?.uri
                val ctx = s?.contextUri
                bridge.logger?.invoke("previous refused (${e.message}): ${if (pos > 3_000 || before == null || ctx == null) "song restarted" else "song before played"}")
                if (pos > 3_000 || before == null || ctx == null) seek(0) else playNow(ctx, trackUri = before, order = lastOrder?.takeIf { it.first == ctx }?.second)
            }
        }
    }
    /** A shuffle setting the page refused (during an ad), and when: set again once the ad is over. */
    @Volatile private var shufflePending: Pair<Boolean, Long>? = null

    override suspend fun setShuffle(on: Boolean) {
        shufflePending = null
        try {
            command("player.setShuffle", buildJsonObject { put("on", on) })
        } catch (e: app.myspoti.spotify.bridge.BridgeException) {
            if (!_adPlaying.value) throw e
            shufflePending = on to System.currentTimeMillis()
            bridge.logger?.invoke("shuffle ${if (on) "on" else "off"} refused during an ad: set again when it is over")
        }
    }

    /** The repeat mode the user chose last: a play or a reload that leaves the page on another one is put back (see [onState]). */
    @Volatile private var wantedRepeat: Repeat? = null
    /** Until when a page reporting another repeat mode than [wantedRepeat] counts as having forgotten it. */
    @Volatile private var repeatRestoreUntil = 0L

    /**
     * The repeat mode chosen in an earlier run of the app: nothing is sent now. The page starts
     * with repeat off, so the mode is set again on the first song it reports, as after a play.
     */
    fun restoreRepeat(mode: Repeat) {
        if (wantedRepeat != null) return
        wantedRepeat = mode
        repeatRestoreUntil = maxOf(repeatRestoreUntil, System.currentTimeMillis() + REPEAT_RESTORE_MS)
    }

    override suspend fun setRepeat(mode: Repeat) {
        wantedRepeat = mode
        bridge.call("player.setRepeat", buildJsonObject { put("mode", mode.ordinal) })
    }

    override suspend fun addToQueue(uris: List<Uri>) {
        bridge.call("queue.add", buildJsonObject { putJsonArray("uris") { uris.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } } })
    }

    override suspend fun removeFromQueue(items: List<NowPlaying>) {
        bridge.call("queue.remove", buildJsonObject {
            putJsonArray("items") {
                items.forEach { item -> addJsonObject { put("uri", item.uri); item.uid?.let { put("uid", it) } } }
            }
        })
    }

    override suspend fun clearQueue() { bridge.call("queue.clear") }

    private fun ref(item: NowPlaying) = buildJsonObject { put("uri", item.uri); item.uid?.let { put("uid", it) } }

    /** The last state with a song: a reloading page reports none for a moment. */
    private fun lastWithSong(): PlayerState? = lastTrackState?.takeIf { it.item != null } ?: lastSong

    override suspend fun skipTo(item: NowPlaying) {
        pausedByUser = false
        userMoveAt = System.currentTimeMillis()
        adRestore?.cancel()
        pickBeforeAd = null
        pickAfterReload = null
        val base = lastWithSong()
        if (skipsHeld) { setIntent(base?.contextUri, item, base?.item?.uri); return }
        if (base?.item?.kind != ItemKind.AD) intent = Intent(base?.contextUri, item, base?.item?.uri, System.currentTimeMillis())
        runCatching { withFade { command("queue.skipTo", ref(item)) } }.onFailure { e ->
            if (!heldError(e, base?.restrictions?.canSkipNext != false)) throw e
            setIntent(base?.contextUri, item, base?.item?.uri)
        }
    }

    override suspend fun playNext(uris: List<Uri>) {
        val first = _queue.value?.queued?.firstOrNull()
        if (first == null) { addToQueue(uris); return }
        bridge.call("queue.insert", buildJsonObject {
            putJsonArray("uris") { uris.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
            put("before", ref(first))
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
        if (bridge.ready.value) applyAudioQuality()
    }

    @Volatile private var mirroring = false

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

    companion object {
        /** How long before the end of the song before an ad its next song is played. */
        const val AD_AHEAD_LEAD_MS = 1_000L
        /** The song an [app.myspoti.spotify.bridge.AdBlock.AdAhead] names, awaited that long. */
        const val AD_AHEAD_WAIT_MS = 30_000L
        /** While waiting for that song's end, the end is computed again at least that often. */
        const val AD_AHEAD_CHECK_MS = 5_000L
        /** After a skip past an ad, the song after the ad is awaited that long before the target is put back. */
        const val AD_RESTORE_WAIT_MS = 6_000L
        /** A refused ad left current that long: the page will not move on by itself. */
        const val AD_STUCK_MS = 3_000L
        /**
         * An ad mute that old is lifted. The page never reports the end of a break, so a lost
         * unmute used to leave the phone silent until the app was restarted; longer than any ad
         * break (15-30 s), shorter than a song.
         */
        const val AD_MUTE_MAX_MS = 75_000L
        /** A reloaded page reports its restored state within that long. */
        const val RESTORE_WAIT_MS = 6_000L
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
        const val SILENT = "silent"
        /** HTMLMediaElement.HAVE_FUTURE_DATA: below it, the element cannot play on. */
        const val HAVE_FUTURE_DATA = 3
        /** The next song is preloaded once the current one has played that long. */
        const val PRELOAD_AFTER_MS = 2_000L
        /** Longer than any ad break: the page is stuck in it. */
        const val AD_BREAK_MAX_MS = 4 * 60_000L
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
        /** Extra ad-mute sends during a break (the media element may appear after the break starts). */
        private const val AD_MUTE_TRIES = 2
        /** The volume is checked every that many ticks. */
        const val VOLUME_CHECK_TICKS = 3
        /**
         * An ad that plays (silent, muted) is got past after that long. Not sooner: an ad at the
         * start of a new play, its audio refused, moves on by itself within about a second.
         */
        const val AD_PLAYING_SKIP_MS = 3_000L
        const val STUCK_MS = 8_000L
        /** A song over with nothing after it: where an ad was left out. Next song quickly. */
        const val END_STUCK_MS = 1_000L
        private const val WATCH_TICK_MS = 2_000L
        /** The page's lowest streaming level (24 kbit/s), used while it only mirrors (see [setMirroring]). */
        const val MIRROR_QUALITY = "low"
        /** Nothing plays: the watcher looks less often (see [watchStalls]). */
        private const val WATCH_IDLE_TICK_MS = 4_000L
        /** Buffering longer than this on one song is a stall, not a slow network. */
        const val BUFFERING_STALL_MS = 25_000L
        /** An ad still there after a reload: tries at playing on before the next reload. */
        const val AD_SKIPS_AFTER_RELOAD = 2
        /** An ad break the page has said nothing about for this long is read again (see [watchStalls]). */
        const val AD_STATE_RECHECK_MS = 4_000L

        /**
         * What the [attempt]-th try on a stuck ad does: "reload" first (the only lever a refused
         * ad yields to, see [watchStalls]), then "next" (play on) before reloading again. An ad
         * that [playing] (muted, not refused) is first played past ("next"), which costs no
         * reload when the page takes it; the reload stays the fallback.
         */
        fun adRecovery(attempt: Int, playing: Boolean = false): String = when {
            playing && attempt == 1 -> "next"
            playing -> adRecovery(attempt - 1)
            attempt == 1 || attempt > 1 + AD_SKIPS_AFTER_RELOAD -> "reload"
            else -> "next"
        }

        /**
         * Why [s] looks stalled at [now], or null. A song is stalled when its clock ran past
         * its end without a new song, when it paused itself at its end with a song after it,
         * or while it buffers too long.
         */
        /**
         * What a song or episode stuck at its end moves on to: the next playable item. B11: an
         * episode counts too; with tracks only, an episode stuck at its end found no target and
         * was played again from 0:00.
         */
        /** [due] is the ad due right after [playing]. */
        fun adDueFor(due: app.myspoti.spotify.bridge.AdBlock.AdAhead?, playing: NowPlaying?): Boolean =
            due != null && playing != null && ((due.songUid != null && playing.uid == due.songUid) || playing.uri == due.songUri)

        /** The queue entry (with its uid, for `queue.skipTo`) of the song [uri], or null. */
        fun adTargetIn(upcoming: List<NowPlaying>, uri: String): NowPlaying? = upcoming.firstOrNull { it.uri == uri }

        /** [item] is a real song or episode other than the one the ad was due after: the skip past the ad has landed. */
        fun adSkipSettled(item: NowPlaying?, due: app.myspoti.spotify.bridge.AdBlock.AdAhead): Boolean =
            item != null && (item.kind == ItemKind.TRACK || item.kind == ItemKind.EPISODE) &&
                !((due.songUid != null && item.uid == due.songUid) || item.uri == due.songUri)

        fun onwardTarget(s: PlayerState, nextUp: List<NowPlaying>): String? {
            val playable = { k: ItemKind -> k == ItemKind.TRACK || k == ItemKind.EPISODE }
            return s.next?.takeIf { playable(it.kind) }?.uri ?: nextUp.firstOrNull { playable(it.kind) }?.uri
        }

        fun stallOf(s: PlayerState, now: Long, userPaused: Boolean): String? {
            val item = s.item ?: return null
            // Episodes stall exactly like songs once their duration is known (a podcast playing
            // silent with a frozen progress bar is the same bug); only ads are left out, their
            // recovery is [adRecovery].
            if (item.kind == ItemKind.AD || item.durationMs <= 0) return null
            if (s.isPaused) {
                val atEnd = s.positionMs >= item.durationMs - 2_000
                return if (atEnd && s.next != null && !userPaused) "paused at the end" else null
            }
            if (s.isBuffering) return if (now - s.timestampMs > BUFFERING_STALL_MS - STUCK_MS) "buffering" else null
            val clock = s.positionMs + ((now - s.timestampMs) * s.speed).toLong()
            return if (clock > item.durationMs + 1_500) "past the end" else null
        }
    }
}
