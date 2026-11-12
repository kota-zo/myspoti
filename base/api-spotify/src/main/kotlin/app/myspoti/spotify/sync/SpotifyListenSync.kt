package app.myspoti.spotify.sync

import app.myspoti.api.Device
import app.myspoti.api.PlayerState
import app.myspoti.api.sync.ListenSync
import app.myspoti.api.sync.ListenSyncHost
import app.myspoti.api.sync.ListenSyncRules
import app.myspoti.api.sync.ListenSyncRules.Follow
import app.myspoti.api.sync.ListenSyncRules.Lead
import app.myspoti.api.sync.ListenSyncRules.Want
import app.myspoti.api.sync.ListenSyncStatus
import app.myspoti.api.sync.LocalNow
import app.myspoti.api.sync.QueueSyncRules
import app.myspoti.api.sync.QueueTake
import app.myspoti.api.sync.SyncTrack
import app.myspoti.api.sync.TrackIdResolver
import app.myspoti.spotify.sync.SpotifySyncRules.TRACK_PREFIX
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

/**
 * Spotify's listen sync ("Count extension plays on Spotify"): while the local player plays a song an
 * extension streams live, the hidden Web Player page plays the same Spotify track at the same place,
 * unheard and at its lowest quality. The phone stays one Spotify Connect device whatever makes the
 * sound: Spotify's apps (and Myspoti on another phone) see the song and command it. Their pause,
 * seek or skip reach the page, whose state the local player follows here ([ListenSyncHost]); the
 * phone's own controls drive the local player as before, and the page follows it. The play is the
 * page's own, counted by Spotify as any other: nothing is reported besides the page's own playback.
 * Downloads and files of the phone are never synced.
 *
 * The track is the one [resolver] gives for the local song ([TrackIdResolver.KnownIds]: the
 * Spotify id it already carries). The decisions are [ListenSyncRules] and [QueueSyncRules]; this
 * runs them on [scope] (the main thread in the app), on the two players' own events and one slow
 * drift check. [clock] is a monotonic clock in ms, [wallClock] the one the page's states are dated with.
 */
class SpotifyListenSync(
    private val page: SyncPage,
    private val host: ListenSyncHost,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    private val resolver: TrackIdResolver = TrackIdResolver.KnownIds,
    private val wallClock: () -> Long = System::currentTimeMillis,
) : ListenSync {
    override val provider: String get() = SpotifySyncRules.PROVIDER

    private val echo = ListenSyncRules.Echo(clock)
    private val lock = Mutex()
    private val queueSync = SpotifyQueueSync(page, host, scope, ::uriOf, running = { running }, landedOn = ::landedOn)
    private var ignoredLicenceSkips = 0
    /** Bumped when the sync ends: commands still waiting for the lock are dropped. */
    private var generation = 0

    @Volatile override var running = false
        private set
    private var want: Want? = null
    /** The song the page was asked to be on before [want]'s (a stale state may still show it). */
    private var previousUri: String? = null
    /** Where the page stood on [want]'s song when a buffering gap began (see [ListenSyncRules.onRemote]); null: no gap. */
    private var gapFrom: Long? = null
    /** The local player's song at the last event (a new one forgets [failedUri]). */
    private var localUri: String? = null
    /** The local player at its last event: whether it played, where, when (for [ListenSyncRules.localMoved]). */
    private var localWasPlaying = false
    private var localAtMs = 0L
    private var localAtTime = 0L
    private var landed = false
    private var retries = 0
    private var resends = 0
    /** The plays the sync sent lately: song and when ([clock]), newest last. */
    private val recentPlays = ArrayDeque<Pair<String, Long>>()
    /** A play waiting [START_COALESCE_MS] for a quicker skip: its context. */
    private var pendingContext: String? = null
    /** The page's last state of [want]'s song (where it should be now). */
    private var lastWeb: PlayerState? = null
    private var stalled = false
    /**
     * A song the page would not play: the local player plays it alone meanwhile, the page paused
     * (still unheard, still at its lowest quality), and it is tried again ([backOff]).
     */
    private var failedUri: String? = null
    private var backoffStep = 0
    /** The list of the sync's last play there (what a play not taken blames). */
    private var playedContext: String? = null
    /** Lists that put the page on another song than the asked one: their songs are played alone. */
    private val missedContexts = HashSet<String>()
    /** Until then ([clock]) the page recovers from an ad or a reload (see [RECOVER_MS]). */
    private var recoverUntil = 0L
    /**
     * Until when a takeover by another device is checked again rather than believed: the sync just
     * started, often on a page that has just loaded, whose previous device Spotify still lists as
     * the one playing (seen: the extension's song stopped a second after a cold start, the bar
     * running with no sound until the app was restarted).
     */
    private var takeoverGraceUntil = 0L
    /** The page went into an ad while the sync ran: its end puts the local player's song back at once. */
    private var heldForAd = false
    /**
     * Until then ([clock]) an ad is on its way: the sync's play of the local player's next song met the
     * ad Spotify had due after the page's song (see [AD_EXPECT_MS]); what the page shows meanwhile is
     * not judged.
     */
    private var adExpectedUntil = 0L
    /** Playback went to another device while synced: it comes back through the extensions. */
    private var handedAway = false
    /** The sync's first play of this run is still to be sent: playback is pulled here first when elsewhere. */
    private var firstPlay = false
    /** The play waiting in [sendPlay] is for a new song of the local player (not a play sent again). */
    private var newSong = false
    private var driftJob: Job? = null
    /** The end of the last sync (page heard again, quality back): the next command waits for it. */
    private var ending: Job? = null
    private var attached = false
    /** The page's device has not joined [ListenSyncRules.JOIN_WAIT_MS] after it was ready: commanded anyway. */
    private var joinWaitedOut = false

    private val joinWait = Later(scope) {
        if (!page.ready.value || page.adPlaying.value || page.joined.value) return@Later
        joinWaitedOut = true
        if (want != null && running) log("the page's device has not joined Spotify Connect ${ListenSyncRules.JOIN_WAIT_MS / 1000} s after the page was back: its song is sent anyway")
        pageBack()
    }
    private val recheck: Later = Later(scope) { recheckNow() }
    private val sendPlay = Later(scope) { sendPlayNow() }
    private val stallCheck = Later(scope) {
        val now = host.local()
        if (!running || now?.buffering != true || !now.playing) return@Later
        log("the local player is buffering: the page waits for it")
        stalled = true
        poke()
    }

    /** Follows the page's Connect status (read by its watcher, no call of its own). Once, at start. */
    override fun attach() {
        if (attached) return
        attached = true
        scope.launch { page.playsElsewhere.collect { away -> if (away) wentAway() else cameBack() } }
        // Commands are held during an ad and while the page reloads (it refuses them: "local player
        // is disabled"); the page usable again gets the song the local player is on, where it is.
        // A page ready again takes commands only once its device has joined Spotify Connect: the
        // song is sent then (or after JOIN_WAIT_MS without a join).
        scope.launch {
            combine(page.ready, page.adPlaying, page.joined) { ready, ad, joined ->
                if (ad && running && !heldForAd) { heldForAd = true; adExpectedUntil = 0L; log("an ad plays on the page: held until its end") }
                if (!ready) { joinWaitedOut = false; joinWait.cancel() }
                else if (!ad && !joined && page.joinReported && !joinWaitedOut) joinWait.schedule(ListenSyncRules.JOIN_WAIT_MS)
                pageUsable()
            }.distinctUntilChanged().collect { usable -> if (usable) pageBack() }
        }
        // A page whose states the app does not read (the native engine): they come here.
        if (page.ownStates) scope.launch { page.state.collect { s -> s?.let(::ownState) } }
    }

    /** The last state [ownState] judged: playing, and on which song. */
    private var ownPlaying = false
    private var ownUri: String? = null

    /**
     * A state of a page that feeds its own ([SyncPage.ownStates]): while the local player plays, a
     * remote command or an echo ([onProviderState]); else playback started or moved to this device
     * from another one, which goes back through the extensions ([onProviderPlayback]).
     */
    private fun ownState(s: PlayerState) {
        if (s.isBuffering) return
        val playing = !s.isPaused
        val moved = playing && (!ownPlaying || ownUri != s.item?.uri)
        ownPlaying = playing; ownUri = s.item?.uri
        if (host.localActive) { if (running) onProviderState(s); return }
        if (moved || handedAway) onProviderPlayback(s, moved, intended = host.engineIntended)
    }

    /** The page can take commands: loaded, no ad on, its device joined (see [ListenSyncRules.pageUsable]). */
    private fun pageUsable(): Boolean = ListenSyncRules.pageUsable(
        ready = page.ready.value, ad = page.adPlaying.value,
        joined = page.joined.value, joinReported = page.joinReported, waitedOut = joinWaitedOut,
    )

    /** The Spotify id [resolver] gives for [t], or null. */
    private fun idOf(t: SyncTrack): String? = resolver.idFor(t, SpotifySyncRules.PROVIDER)

    /** The Spotify uri of [t], or null when no id is known for it. */
    private fun uriOf(t: SyncTrack): String? = idOf(t)?.let { TRACK_PREFIX + it }

    override fun onLocal(now: LocalNow) {
        val id = idOf(now.track)
        val uri = TRACK_PREFIX + id.orEmpty()
        val time = clock()
        val expected = if (uri != localUri) null else localAtMs + if (localWasPlaying) time - localAtTime else 0L
        val moved = ListenSyncRules.localMoved(localWasPlaying, now.playing, expected, now.positionMs)
        if (uri != localUri) { localUri = uri; failedUri = null; backoffStep = 0 }
        localWasPlaying = now.playing; localAtMs = now.positionMs; localAtTime = time
        // Played off 1× (the equalizer's speed): Spotify's page plays music at 1× only, it would
        // be put back every few seconds; the song plays alone, as with the setting off.
        if (now.rate != 1f) {
            if (running) stop("the song plays at ${now.rate}×, Spotify plays at 1× only")
            return
        }
        if (!ListenSyncRules.eligible(now.live, id?.takeIf(SpotifySyncRules::isTrackId), host.enabled, host.dataSaver)) {
            if (running) stop(if (now.live) "not mirrored: ${now.track.title}" else "a download or a file of the phone plays")
            return
        }
        if (running) trackStall(now.playing, now.buffering)
        // Backing off a song the page would not play: a play, resume or seek here tries it again now.
        if (running && failedUri == uri && pendingContext != null && moved && now.playing) {
            log("tried again on the page at a play or seek: $uri")
            sendPlay.runNow()
        }
        val w = want
        val local = ListenSyncRules.Local(uri, now.playing, now.positionMs, now.durationMs)
        // Another device has Connect: a pause or a seek from here would act on it.
        val elsewhere = page.playsElsewhere.value
        when (val lead = ListenSyncRules.onLocal(local, w, stalled, echo.quiet(), webAt(w), previousUri)) {
            Lead.Nothing -> {}
            is Lead.Start -> start(lead.uri, lead.positionMs, now.contextUri)
            // A play still waiting (see [start]) is held instead: nothing plays there yet.
            Lead.Pause -> { want = w?.copy(paused = true); if (!elsewhere && pendingContext == null) command(ECHO_MS) { page.pause() } }
            is Lead.Resume -> {
                // Another device took Connect meanwhile: a resume would resume it; the song is played here again.
                if (elsewhere) start(uri, now.positionMs, now.contextUri)
                else if (pendingContext != null) { want = w?.copy(paused = false); sendPlay.runNow() }
                else {
                    want = w?.copy(paused = false)
                    command(ECHO_MS) { lead.alignMs?.let { page.seek(it) }; page.resume() }
                }
            }
            is Lead.Align -> if (!elsewhere && pendingContext == null) {
                log("${lead.ms - (webAt(w) ?: lead.ms)} ms out of step: the page is put back")
                command(ECHO_MS) { page.seek(lead.ms) }
            }
        }
    }

    /**
     * The local player played its song again from 0:00 (repeat): the page plays it anew too, a new
     * play as Spotify's own repeat counts one (a seek back to 0 would go on with the same play).
     */
    override fun onLocalRepeat() {
        if (!running || page.playsElsewhere.value) return
        val uri = localUri ?: return
        if (want?.uri != uri) return
        start(uri, 0L, host.local()?.contextUri.orEmpty())
    }

    /** A state of the page while the sync runs: a remote command, or our own echo. */
    override fun onProviderState(s: PlayerState) {
        val w = want ?: return
        val item = s.item ?: return
        val nowMs = wallClock()
        // An ad, a song still loading, Spotify limiting plays: nothing to judge on. The page's clock
        // after it lies between where it stood then and where its last state puts it: both are kept
        // for the seek test (a seek just before the gap is still followed, a stall in it is not one).
        if (s.isBuffering || page.adPlaying.value || page.licenceLimited()) {
            if (gapFrom == null) gapFrom = lastWeb?.takeIf { it.item?.uri == w.uri }?.positionAt(nowMs)
            return
        }
        // The ad our play met is on its way: the page still shows the song before it, or nothing.
        if (item.uri != w.uri && clock() < adExpectedUntil) return
        val web = ListenSyncRules.Remote(item.uri, s.isPaused, s.positionAt(nowMs))
        val before = lastWeb?.takeIf { it.item?.uri == w.uri }
        val predicted = before?.takeIf { item.uri == w.uri }?.positionAt(nowMs)
        // Where the page is in the asked song, and its length as Spotify has it (the end it reaches).
        val wantAt = if (item.uri == w.uri) web.positionMs else before?.positionAt(nowMs)
        val wantLength = (if (item.uri == w.uri) item.durationMs else before?.item?.durationMs) ?: 0L
        val gap = gapFrom
        gapFrom = null
        if (item.uri == w.uri) lastWeb = s
        if (item.uri == w.uri && !s.isPaused) {
            if (failedUri == w.uri) log("the page plays ${w.uri} again")
            // On the song now: Spotify's queue is checked against the local one.
            if (!landed) queueSync.landed()
            landed = true; failedUri = null; backoffStep = 0
        }
        val now = host.local() ?: return
        // The end the local player really knows (its audio's), not Spotify's length: unknown, it is never near.
        val local = ListenSyncRules.Local(
            TRACK_PREFIX + idOf(now.track).orEmpty(), now.playing, now.positionMs, now.knownLengthMs ?: 0L,
            nextUri = now.next?.let(::uriOf), previousUri = now.previous?.let(::uriOf),
        )
        val quiet = echo.quiet()
        // A state the echo window hides is looked at again once it is over (a remote pause right after a command).
        if (quiet) recheck.schedule(echo.remainingMs() + RECHECK_SLACK_MS)
        // Recovering from an ad or a reload: a song other than the asked one is the player's own play, sent back.
        val sure = landed && clock() >= recoverUntil
        val f = ListenSyncRules.onRemote(web, w, local, quiet, sure, predicted, previousUri, gap, wantAt, wantLength, recentUris(), now.repeatsCurrent)
        // A song refused its licence makes the page skip on by itself, a song a second or so: taken for
        // picks on another device, the local player followed and its songs switched at random (reported,
        // free account, 403 ×42). Those moves stay the page's; the local song plays on.
        if (ListenSyncRules.movesSong(f) && page.licenceRefusedWithin(LICENCE_SKIP_MS)) {
            if (ignoredLicenceSkips++ % 10 == 0) log("the page went to ${web.uri} after a refused licence: not followed")
            return
        }
        when (f) {
            Follow.Nothing -> {}
            // A play waits already (coalescing, backing off): not another one.
            Follow.Retry -> if (pendingContext != null) Unit else if (retries++ < MAX_RETRIES) {
                // Its list put the page elsewhere: that list's songs are played alone from now on.
                playedContext?.takeIf { it != w.uri && missedContexts.size < MAX_MISSED }?.let {
                    missedContexts += it; log("$it put the page on ${web.uri}: its songs are played alone")
                }
                log("the page did not take ${w.uri}: played again")
                playNow(w.uri, w.uri)
            } else backOff(w)
            Follow.Pause -> { log("paused on another device"); want = w.copy(paused = true); host.pause() }
            Follow.Resume -> { log("resumed on another device"); want = w.copy(paused = false); host.resume() }
            is Follow.Seek -> { log("moved to ${f.ms} ms on another device"); host.seekTo(f.ms) }
            Follow.Next -> { moved(web, s); followIfHere(web.uri, "next on another device") { host.next() } }
            Follow.Previous -> { moved(web, s); followIfHere(web.uri, "previous on another device") { host.previous() } }
            Follow.Resend -> if (pendingContext != null) Unit else if (resends++ < MAX_RESENDS) {
                log("the page is still on ${web.uri}, played earlier by the mirror: ${w.uri} sent again")
                playNow(w.uri, w.uri)
            } else backOff(w)
            is Follow.Play -> { moved(web, s); followIfHere(f.uri, "${f.uri} picked on another device") { host.playRemotePick(item, s.contextUri, f.positionMs) } }
            is Follow.Align -> command(ECHO_MS) { page.seek(f.ms) }
            Follow.Back -> if (pendingContext != null) Unit else {
                log("the page went on to ${web.uri} by itself while ${now.track.title} is to play again here: put back on ${w.uri}, not followed")
                playNow(w.uri, w.uri)
            }
        }
    }

    /** The page's latest state, judged again when the echo window that hid it is over. */
    private fun recheckNow() {
        if (!running || !host.localActive || host.routing) return
        // Renewed meanwhile (the page answered late): looked at once that one is over.
        if (echo.quiet()) { recheck.schedule(echo.remainingMs() + RECHECK_SLACK_MS); return }
        page.state.value?.let(::onProviderState)
    }

    /**
     * Another song on the page, by a remote command: followed only while this phone is still Spotify
     * Connect's active device. The page shows the active device's playback, and the watcher reports
     * a takeover a few seconds late: a song another device (or another install of the app) took
     * playback with is not followed, the local player stops (followed, the sync's play would be a
     * remote command to that device, which takes it as its own, and the two fight over it).
     */
    private fun followIfHere(uri: String, line: String, follow: () -> Unit) {
        val gen = generation
        scope.launch {
            val device = activeElsewhere()
            if (gen != generation || !running) return@launch
            if (!ListenSyncRules.followsRemote(device != null)) {
                log("$uri plays on $device, which took playback: the local player stops, not followed")
                handedAway = true
                host.stop()
                return@launch
            }
            log(line)
            follow()
        }
    }

    /** The name of the device of the account that is Spotify Connect's active one when it is not this page, or null. */
    private suspend fun activeElsewhere(): String? {
        if (page.playsElsewhere.value) return page.devices.value.firstOrNull { it.active && !it.local }?.name ?: "another device"
        withTimeoutOrNull(DEVICES_WAIT_MS) { runCatching { page.refreshDevices() } }
        return page.devices.value.firstOrNull { it.active && !it.local }?.name
    }

    /**
     * The page would not take [w]'s song (twice): never given up for good. It is paused meanwhile
     * (unheard, at its lowest quality, nothing else plays on the account) and the song is played there
     * again after [ListenSyncRules.backoffMs], or at the next play, resume or seek of the local player.
     */
    private fun backOff(w: Want) {
        val delayMs = ListenSyncRules.backoffMs(backoffStep++)
        failedUri = w.uri
        log("the page would not play ${w.uri}: the stream plays alone, tried again in ${delayMs / 1000} s or at the next play or seek")
        landed = false; lastWeb = null; gapFrom = null
        // One try each time from now on: a miss goes to the next, longer wait.
        retries = MAX_RETRIES; resends = MAX_RESENDS
        command(ECHO_MS) { page.pause() }
        pendingContext = w.uri; newSong = false
        sendPlay.schedule(delayMs)
    }

    /**
     * The page is usable again after an ad or a reload: it gets the local player's song, where the
     * local player is when the play goes. After an ad, at once: the page has gone on to Spotify's own
     * next song, which would be counted instead. The local player paused meanwhile: the page is paused
     * (Spotify's song must not play on there), and the song goes at the local player's resume.
     */
    private fun pageBack() {
        val afterAd = heldForAd
        heldForAd = false; adExpectedUntil = 0L
        val w = want?.takeIf { running } ?: return
        log(if (afterAd) "the ad is over: ${w.uri} played there again, where the local player is" else "the page is back (reloaded): ${w.uri} played there again")
        landed = false; retries = 0; resends = 0; lastWeb = null; gapFrom = null
        recoverUntil = clock() + RECOVER_MS
        // In the list the local queue came from, as at the start: the track alone would replace the
        // list on Spotify for every other device (#144).
        pendingContext = SpotifySyncRules.playContext(host.local()?.contextUri.orEmpty(), w.uri, missedContexts); newSong = false
        echo.sent(PLAY_ECHO_MS)
        when {
            w.paused -> { sendPlay.cancel(); command(ECHO_MS) { page.pause() } }
            afterAd -> sendPlay.runNow()
            else -> sendPlay.schedule(START_COALESCE_MS)
        }
    }

    /** The page went to [web]'s song by a remote command: that is what it is asked to be on now. */
    private fun moved(web: ListenSyncRules.Remote, s: PlayerState) {
        previousUri = want?.uri
        want = Want(web.uri, web.paused); landed = true; retries = 0; resends = 0; lastWeb = s; gapFrom = null
        failedUri = null; backoffStep = 0
        echo.sent(PLAY_ECHO_MS)
    }

    /** The page plays [uri] from [at], unheard (the sync starts, or goes on with the local player's next song). */
    private fun start(uri: String, at: Long, contextUri: String) {
        if (!running) {
            running = true
            firstPlay = true
            // An extension's own mute of the page gives way (two would fight over it), and the local
            // player keeps the audio focus the page asks for as it plays.
            host.syncRunning(true)
            startDriftCheck()
            takeoverGraceUntil = clock() + RECOVER_MS
            log("on")
        }
        handedAway = false
        previousUri = want?.uri
        want = Want(uri, paused = false); landed = false; retries = 0; resends = 0; lastWeb = null; gapFrom = null
        failedUri = null; backoffStep = 0
        // In the list the local queue came from, as the phone would play it: other devices see that queue after it.
        pendingContext = SpotifySyncRules.playContext(contextUri, uri, missedContexts)
        newSong = true
        log("$uri plays unheard on Spotify from $at ms")
        // A skip right after this one replaces it: one play for a burst of skips (they landed out of order).
        echo.sent(PLAY_ECHO_MS)
        sendPlay.schedule(START_COALESCE_MS)
    }

    private fun sendPlayNow() {
        val w = want?.takeIf { running } ?: return
        val context = pendingContext ?: return
        // The local player paused or stalled meanwhile: played when it plays (Lead.Resume).
        if (w.paused) return
        pendingContext = null
        playNow(w.uri, context, newSong)
        newSong = false
    }

    /**
     * Plays [uri] (in [context]) on the page from the local position, unheard, noted as the sync's own play.
     * [newSong]: the local player's new song, which may be queued on Spotify (see [QueueSyncRules.way]).
     * The run's first play pulls playback here first when another device has it ([QueueSyncRules.pullsHere]).
     */
    private fun playNow(uri: String, context: String, newSong: Boolean = false) {
        val at = host.local()?.positionMs ?: 0L
        val first = firstPlay
        firstPlay = false
        recentPlays.addLast(uri to clock())
        while (recentPlays.size > MAX_RECENT) recentPlays.removeFirst()
        playedContext = context
        // Whether it landed is judged once its echo is over, even if the page reports nothing.
        recheck.schedule(PLAY_ECHO_MS + RECHECK_SLACK_MS)
        // An earlier sync's end runs under the same lock, before this: never heard under this one.
        command(PLAY_ECHO_MS) { live ->
            // Before the play: the page picks the song's file with the quality it has then.
            page.setMirroring(true)
            if (!page.localMute) page.setLocalMute(true)
            val device = if (first) activeElsewhere() else null
            if (QueueSyncRules.pullsHere(first, elsewhere = device != null)) {
                log("$device has playback: pulled here before the page plays")
                val pulled = withTimeoutOrNull(PULL_WAIT_MS) { runCatching { page.pullHere() } }
                if (pulled?.isSuccess != true) log("pull here failed: ${pulled?.exceptionOrNull()?.message ?: "no answer"}")
            }
            // Ended meanwhile: no play that would be heard next to the local player.
            if (!live()) return@command
            val s = page.state.value
            val queued = page.queue.value?.queued.orEmpty()
            val way = if (newSong) QueueSyncRules.way(uri, s?.item?.uri, s?.isPaused == false, queued.map { it.uri }) else QueueSyncRules.Way.Play
            // Spotify has an ad due at the end of the page's song: a play or a skip to another song
            // starts that ad first, and the page shows it a moment later.
            if (s?.item?.uri != null && s.item?.uri != uri && s.item?.uri == page.adDueAfter()) {
                log("an ad is due after ${s.item?.uri} on Spotify: $uri plays there after it")
                adExpectedUntil = clock() + AD_EXPECT_MS
            }
            when (way) {
                QueueSyncRules.Way.Already -> {
                    log("the page plays $uri already (gone on to its queued copy): kept in step")
                    val web = s?.positionAt(wallClock()) ?: at
                    if (abs(web - at) > ListenSyncRules.DRIFT_MS) page.seek(at)
                }
                is QueueSyncRules.Way.Skip -> {
                    log("$uri played from Spotify's queue")
                    page.skipTo(queued[way.index])
                    queueSync.consumed(uri)
                    if (at > ListenSyncRules.DRIFT_MS) page.seek(at)
                }
                QueueSyncRules.Way.Play -> page.play(context, trackUri = uri.takeIf { it != context }, positionMs = at)
            }
        }
    }

    /** The songs the sync asked the page to play in the last [RECENT_MS]. */
    private fun recentUris(): List<String> {
        val since = clock() - RECENT_MS
        return recentPlays.filter { it.second >= since }.map { it.first }
    }

    override fun stop(reason: String, pauseRemote: Boolean): Boolean {
        if (!running) return false
        running = false
        generation++
        val heard = pauseRemote && want?.paused == false && !page.playsElsewhere.value
        want = null; previousUri = null; lastWeb = null; gapFrom = null; landed = false; stalled = false
        heldForAd = false; adExpectedUntil = 0L
        recheck.cancel()
        sendPlay.cancel(); pendingContext = null; recentPlays.clear()
        stallCheck.cancel()
        driftJob?.cancel(); driftJob = null
        echo.clear()
        host.syncRunning(false)
        log("off: $reason")
        // Behind the lock: a play still on its way lands first, then is paused here. Waited for by
        // the next transport command (the app's): a song Spotify plays next must find the page
        // heard, at the user's quality, the synced one paused. Each step on its own: a pause that
        // fails must not leave the page unheard or at its lowest quality.
        ending = scope.launch {
            lock.withLock {
                if (heard) runCatching { page.pause() }.onFailure { log("ending: pause failed: ${it.message}") }
                runCatching { page.setLocalMute(false) }.onFailure { log("ending: unmute failed: ${it.message}") }
                runCatching { page.setMirroring(false) }.onFailure { log("ending: quality not restored: ${it.message}") }
            }
        }
        return true
    }

    override suspend fun awaitEnded() { ending?.join() }

    /** The page plays [uri] for the sync (it landed there). */
    private fun landedOn(uri: String): Boolean = running && landed && want?.uri == uri

    override val waitsBack: Boolean get() = handedAway

    override fun leaving(device: String) {
        if (stop("playback moved to $device", pauseRemote = false)) handedAway = true
    }

    override fun refresh() {
        if (!host.enabled) { stop("the setting was turned off"); failedUri = null }
        else scope.launch { poke() }
    }

    /**
     * Another device of the account took playback while synced: the local player stops, as it would
     * have. Not for this phone's own Spotify app on the mirrored song ([takeBackFrom]).
     */
    private fun wentAway() {
        if (!running || want == null) return
        // The page just (re)loaded, or the sync just started: Spotify still lists this phone's previous
        // page device as the one playing until it is taken off (a second or two after a relaunch). That is not another device:
        // seen as one, the extension's song was stopped right after a relaunch (reported). Checked
        // again once the page has settled.
        val left = maxOf(recoverUntil, takeoverGraceUntil) - clock()
        if (left > 0) {
            if (want?.paused == false) log("another device seems to play while the page settles: checked again in ${left / 1000} s")
            scope.launch { delay(left + 500); if (page.playsElsewhere.value) wentAway() }
            return
        }
        val gen = generation
        scope.launch {
            withTimeoutOrNull(DEVICES_WAIT_MS) { runCatching { page.refreshDevices() } }
            if (gen != generation || !running || !page.playsElsewhere.value) return@launch
            val app = SpotifySyncRules.ownPhoneApp(page.devices.value, page.phoneName)
            if (app != null && takeBackFrom(app)) return@launch
            if (want?.paused != false) return@launch
            val device = app?.name ?: page.devices.value.firstOrNull { it.active && !it.local }?.name ?: "another device"
            log("$device took playback: the local player stops")
            handedAway = true
            // Ends the sync too (the app's stop of the local player); nothing to pause here, the page stands still.
            host.stop()
        }
    }

    /** When the sync took playback back from this phone's Spotify app lately ([clock]), oldest first. */
    private val takenBack = ArrayDeque<Long>()

    /**
     * This phone's Spotify app ([SpotifySyncRules.ownPhoneApp]) took playback of the mirrored song:
     * its notification, lock screen player or widget was used, taken for this app's own. Playback is
     * pulled back to the page and the extension's song goes on; a play pressed there (the local
     * player paused) is a play here. Another song there was picked there: it keeps playback, as
     * after [MAX_TAKE_BACKS] take-backs in [TAKE_BACK_WINDOW_MS] (the app is wanted). True: taken back.
     */
    private fun takeBackFrom(app: Device): Boolean {
        val w = want ?: return false
        val s = page.state.value
        val uri = s?.item?.uri
        if (uri != null && uri != w.uri) {
            log("this phone's Spotify app plays $uri, not ${w.uri}: picked there, it keeps playback")
            return false
        }
        val now = clock()
        while (takenBack.isNotEmpty() && now - takenBack.first() > TAKE_BACK_WINDOW_MS) takenBack.removeFirst()
        if (takenBack.size >= MAX_TAKE_BACKS) {
            log("this phone's Spotify app took playback again (${takenBack.size} times in ${TAKE_BACK_WINDOW_MS / 1000} s): left to it")
            return false
        }
        takenBack.addLast(now)
        takeoverGraceUntil = now + RECOVER_MS
        firstPlay = true
        val plays = s != null && !s.isPaused && !s.isBuffering
        if (w.paused && !plays) {
            log("this phone's Spotify app (${app.name}) took ${w.uri}, paused: pulled back at the local player's resume")
            return true
        }
        log("this phone's Spotify app (${app.name}) took ${w.uri}: pulled back to the page, the song goes on here")
        val local = host.local()
        start(w.uri, local?.positionMs ?: 0L, local?.contextUri.orEmpty())
        if (w.paused) host.resume()
        return true
    }

    /**
     * This page is Connect's active device again. Often paused (a transfer that restores paused,
     * the phone picked in another app's list): [handedAway] then stays until the page plays here
     * ([onProviderPlayback]), when the song goes back to the extensions.
     */
    private fun cameBack() {
        if (handedAway) page.state.value?.let { onProviderPlayback(it, moved = false, intended = host.engineIntended) }
    }

    override fun onProviderPlayback(s: PlayerState, moved: Boolean, intended: Boolean) {
        if (host.localActive || host.routing) return
        val playingHere = !s.isPaused && !s.isBuffering && s.item != null && !page.playsElsewhere.value
        if (!playingHere) return
        val back = ListenSyncRules.backHere(playingHere, handedAway, moved, intended, host.enabled)
        handedAway = false
        if (!back) return
        // Checked against the device list: the page shows the active device's song as playing, and
        // the watcher reports another device's takeover a few seconds late.
        scope.launch {
            val device = activeElsewhere()
            if (ListenSyncRules.backHere(playingHere, true, moved, intended, host.enabled, activeElsewhere = device != null)) host.playHereAgain()
            else if (device != null) {
                // Still away: its next state here (a real transfer back) is judged again.
                handedAway = true
                log("playback plays on $device, not on this phone: not taken back")
            }
        }
    }

    /** The local player as it is now, judged as a state of it. */
    private fun poke() {
        if (!host.localActive || host.routing) return
        host.local()?.let(::onLocal)
    }

    /** Where the page should be now on [w]'s song, from its last state; null when unknown. */
    private fun webAt(w: Want?): Long? = if (gapFrom != null) null else
        lastWeb?.takeIf { w != null && it.item?.uri == w.uri && !it.isBuffering }?.positionAt(wallClock())

    /** A buffering that lasts pauses the page ([stallCheck]); the local player playing again resumes both. */
    private fun trackStall(playing: Boolean, buffering: Boolean) {
        if (playing && buffering) {
            if (!stalled && !stallCheck.pending) stallCheck.schedule(STALL_MS)
        } else {
            stallCheck.cancel()
            stalled = false
        }
    }

    /** Between events the two clocks can part slowly: looked at again every [DRIFT_CHECK_MS], while it runs. */
    private fun startDriftCheck() {
        driftJob?.cancel()
        driftJob = scope.launch {
            while (true) {
                delay(DRIFT_CHECK_MS)
                if (host.local()?.audible == true) poke()
            }
        }
    }

    /**
     * Sends [block] after the sync's earlier commands (and an ending, see [stop]); its answers are its
     * echo for [echoMs]. [block] is told whether this sync still runs (`live`), to check between steps.
     */
    private fun command(echoMs: Long, block: suspend (live: () -> Boolean) -> Unit) {
        echo.sent(echoMs)
        val gen = generation
        val live = { gen == generation }
        scope.launch {
            lock.withLock {
                if (!live()) return@withLock
                // An ad or a reload: the page refuses commands now. Dropped: the page gets the song
                // again once it is usable ([pageBack]), what the local player does then included.
                if (!pageUsable()) { log("held: the page is in an ad or reloading"); return@withLock }
                runCatching { block(live) }.onFailure { log("command failed: ${it.message}") }
                // Ended while it ran: the ending, queued behind it, puts the page right; nothing more here.
                if (!live()) return@withLock
                // Counted from the page's answer: a slow play must not let its own states look remote.
                echo.sent(echoMs)
            }
        }
    }

    override fun status() = ListenSyncStatus(provider, host.enabled, running, want?.uri, want?.paused, landed, webAt(want), handedAway, failedUri)

    override fun localQueueChanged(queued: List<SyncTrack>) = queueSync.localChanged(queued)

    override fun adoptRemoteQueue(localNext: List<SyncTrack>): List<QueueTake> = queueSync.adopt(localNext)

    override fun remoteQueueTaken(taken: List<SyncTrack>) = queueSync.taken(taken)

    private fun log(line: String) = host.log("mirror: $line")

    companion object {
        /** How long after a refused licence the page's own song changes are not followed. */
        const val LICENCE_SKIP_MS = 10_000L
        /** A play is answered within that (the page loads the song, then reports it). */
        const val PLAY_ECHO_MS = 6_000L
        /** A pause, resume or seek is answered within that. */
        const val ECHO_MS = 2_500L
        /** The local player buffering longer than this: the page waits for it. */
        const val STALL_MS = 1_000L
        /** The slow check of the drift between two events (the page reports changes, not its clock). */
        const val DRIFT_CHECK_MS = 20_000L
        /** A play the page did not take is sent once more at once, then again later (see [ListenSyncRules.backoffMs]). */
        const val MAX_RETRIES = 1
        /** After the echo window, before the state hidden by it is judged again. */
        const val RECHECK_SLACK_MS = 100L
        /** A song the sync asked the page to play this recently: a state on it is a late answer, not a remote command. */
        const val RECENT_MS = 15_000L
        const val MAX_RECENT = 4
        /** How often the asked song is sent again after late answers to earlier plays (then it is a play not taken). */
        const val MAX_RESENDS = 2
        /** Songs the local player starts closer than this are sent to the page as one play (fast skips). */
        const val START_COALESCE_MS = 300L
        /**
         * After an ad or a reload, the player's own recovery plays songs too (the pick before the ad, the
         * song "ahead" of the next ad): for that long, another song on the page is never a remote command.
         */
        const val RECOVER_MS = 10_000L
        /** A play that met an ad due shows the ad within that (else the page is judged as usual). */
        const val AD_EXPECT_MS = 8_000L
        /** At most that many lists remembered as putting the page on another song. */
        const val MAX_MISSED = 32
        /** The device list's read before a play or a followed move is not waited for longer. */
        const val DEVICES_WAIT_MS = 1_500L
        /** A pull of playback here is not waited for longer (a device that went away can leave it pending). */
        const val PULL_WAIT_MS = 5_000L
        /** Playback is taken back from this phone's Spotify app at most [MAX_TAKE_BACKS] times in that. */
        const val TAKE_BACK_WINDOW_MS = 60_000L
        const val MAX_TAKE_BACKS = 3
    }
}

/** A delayed run of [block] on [scope] that a new [schedule] replaces (as a posted, then removed, callback). */
internal class Later(private val scope: CoroutineScope, private val block: () -> Unit) {
    private var job: Job? = null
    val pending: Boolean get() = job?.isActive == true

    fun schedule(delayMs: Long) {
        job?.cancel()
        job = scope.launch { delay(delayMs); job = null; block() }
    }

    fun cancel() { job?.cancel(); job = null }

    /** Runs it now instead of later. */
    fun runNow() { cancel(); block() }
}
