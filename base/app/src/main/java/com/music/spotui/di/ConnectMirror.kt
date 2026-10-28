package com.music.spotui.di

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import app.myspoti.api.PlayerState
import app.myspoti.host.android.MyspotiRuntime
import com.music.spotui.data.local.LocalTrack
import com.music.spotui.data.preferences.ConnectMirrorPref
import com.music.spotui.di.MirrorRules.Follow
import com.music.spotui.di.MirrorRules.Lead
import com.music.spotui.di.MirrorRules.Want
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * "Count extension plays on Spotify": while the local player plays a song an extension streams, the
 * Web Player plays the same Spotify song at the same place, unheard and at its lowest quality. The
 * phone stays one Spotify Connect device whatever makes the sound: Spotify's apps (and Myspoti on
 * another phone) see the song and command it. Their pause, seek or skip reach the page, whose state
 * the local player follows here; the phone's own controls drive the local player as before, and the
 * page follows it. The play is the page's own, counted by Spotify as any other: the app reports
 * nothing itself. Downloads and files of the device are never mirrored.
 *
 * Everything runs on the main thread, on the two players' own events (and one slow drift check).
 */
object ConnectMirror {
    private const val SPOTIFY_TRACK_PREFIX = "spotify:track:"
    /** A play is answered within that (the page loads the song, then reports it). */
    private const val PLAY_ECHO_MS = 6_000L
    /** A pause, resume or seek is answered within that. */
    private const val ECHO_MS = 2_500L
    /** The local player buffering longer than this: the page waits for it. */
    private const val STALL_MS = 1_000L
    /** The slow check of the drift between two events (the page reports changes, not its clock). */
    private const val DRIFT_CHECK_MS = 20_000L
    /** A play the page did not take is sent once more at once, then again later (see MirrorRules.backoffMs). */
    private const val MAX_RETRIES = 1
    /** After the echo window, before the state hidden by it is judged again. */
    private const val RECHECK_SLACK_MS = 100L
    /** A song the mirror asked the page to play this recently: a state on it is a late answer, not a remote command. */
    private const val RECENT_MS = 15_000L
    private const val MAX_RECENT = 4
    /** How often the asked song is sent again after late answers to earlier plays (then it is a play not taken). */
    private const val MAX_RESENDS = 2
    /** Songs the local player starts closer than this are sent to the page as one play (fast skips). */
    private const val START_COALESCE_MS = 300L
    /**
     * After an ad or a reload, the player's own recovery plays songs too (the pick before the ad, the
     * song "ahead" of the next ad): for that long, another song on the page is never a remote command.
     */
    private const val RECOVER_MS = 10_000L
    /** At most that many lists remembered as putting the page on another song. */
    private const val MAX_MISSED = 32

    private val api get() = MyspotiRuntime.api
    private val page get() = MyspotiRuntime.spotify.player
    private val main = Handler(Looper.getMainLooper())
    private val echo = MirrorRules.Echo(SystemClock::elapsedRealtime)
    private val lock = Mutex()
    /** Bumped when the mirror ends: commands still waiting for the lock are dropped. */
    private var generation = 0

    /** The mirror holds the page: unheard, at its lowest quality, following and followed. */
    @Volatile var running = false
        private set
    private var want: Want? = null
    /** The song the page was asked to be on before [want]'s (a stale state may still show it). */
    private var previousUri: String? = null
    /** Where the page stood on [want]'s song when a buffering gap began (see MirrorRules.onWeb); null: no gap. */
    private var gapFrom: Long? = null
    /** The local player's song at the last event (a new one forgets [failedUri]). */
    private var localUri: String? = null
    /** The local player at its last event: whether it played, where, when (for [MirrorRules.localMoved]). */
    private var localWasPlaying = false
    private var localAtMs = 0L
    private var localAtTime = 0L
    private var landed = false
    private var retries = 0
    private var resends = 0
    /** The plays the mirror sent lately: song and when (elapsed realtime), newest last. */
    private val recentPlays = ArrayDeque<Pair<String, Long>>()
    /** A play waiting [START_COALESCE_MS] for a quicker skip: its context. */
    private var pendingContext: String? = null
    /** The page's last state of [want]'s song (where it should be now). */
    private var lastWeb: PlayerState? = null
    private var stalled = false
    private var stallPending = false
    /**
     * A song the page would not play: the local player plays it alone meanwhile, the page paused
     * (still unheard, still at its lowest quality), and it is tried again ([backOff]).
     */
    private var failedUri: String? = null
    private var backoffStep = 0
    /** The list of the mirror's last play there (what a play not taken blames). */
    private var playedContext: String? = null
    /** Lists that put the page on another song than the asked one: their songs are played alone. */
    private val missedContexts = HashSet<String>()
    /** Until then (elapsed realtime) the page recovers from an ad or a reload (see [RECOVER_MS]). */
    private var recoverUntil = 0L
    /** Playback went to another device while mirrored: it comes back through the extensions. */
    private var handedAway = false
    private var driftJob: Job? = null
    /** The end of the last mirror (page heard again, quality back): the next command waits for it. */
    private var ending: Job? = null
    private var attached = false

    /** Follows the page's Connect status (read by its watcher, no call of its own). Once, at start. */
    fun attach() {
        if (attached) return
        attached = true
        MyspotiRuntime.scope.launch { page.playsElsewhere.collect { away -> if (away) wentAway() else cameBack() } }
        // Commands are held during an ad and while the page reloads (it refuses them: "local player
        // is disabled"); the page usable again gets the song the local player is on, where it is.
        MyspotiRuntime.scope.launch {
            combine(api.ready, page.adPlaying) { ready, ad -> ready && !ad }.distinctUntilChanged().collect { usable -> if (usable) pageBack() }
        }
    }

    /** The page can take commands: loaded, and no ad on. */
    private fun pageUsable(): Boolean = api.ready.value && !page.adPlaying.value

    /** The local player's song or state changed (SongPlayer.onLocalState, main thread). */
    fun onLocal(t: LocalTrack, playing: Boolean, positionMs: Long, durationMs: Long) {
        val id = LocalPlayer.spotifyIdOf(t)
        val origin = when (t.uri.scheme) {
            com.music.spotui.extensions.ExtensionAudio.SCHEME -> MirrorRules.Origin.AUDIO_SOURCE
            "file" -> MirrorRules.Origin.DOWNLOAD
            else -> MirrorRules.Origin.LOCAL_FILE
        }
        val uri = SPOTIFY_TRACK_PREFIX + id
        val now = SystemClock.elapsedRealtime()
        val expected = if (uri != localUri) null else localAtMs + if (localWasPlaying) now - localAtTime else 0L
        val moved = MirrorRules.localMoved(localWasPlaying, playing, expected, positionMs)
        if (uri != localUri) { localUri = uri; failedUri = null; backoffStep = 0 }
        localWasPlaying = playing; localAtMs = positionMs; localAtTime = now
        if (!MirrorRules.eligible(origin, id, ConnectMirrorPref.on, com.music.spotui.data.DataSaver.active)) {
            if (running) stop(if (origin == MirrorRules.Origin.AUDIO_SOURCE) "not mirrored: ${t.title}" else "a download or a file of the phone plays")
            return
        }
        if (running) trackStall(playing)
        // Backing off a song the page would not play: a play, resume or seek here tries it again now.
        if (running && failedUri == uri && pendingContext != null && moved && playing) {
            log("tried again on the page at a play or seek: $uri")
            main.removeCallbacks(sendPlay); sendPlay.run()
        }
        val w = want
        val local = MirrorRules.Local(uri, playing, positionMs, durationMs)
        // Another device has Connect: a pause or a seek from here would act on it.
        val elsewhere = page.playsElsewhere.value
        when (val lead = MirrorRules.onLocal(local, w, stalled, echo.quiet(), webAt(w), previousUri)) {
            Lead.Nothing -> {}
            is Lead.Start -> start(lead.uri, lead.positionMs)
            // A play still waiting (see [start]) is held instead: nothing plays there yet.
            Lead.Pause -> { want = w?.copy(paused = true); if (!elsewhere && pendingContext == null) command(ECHO_MS) { api.player.pause() } }
            is Lead.Resume -> {
                // Another device took Connect meanwhile: a resume would resume it; the song is played here again.
                if (elsewhere) start(uri, positionMs)
                else if (pendingContext != null) { want = w?.copy(paused = false); main.removeCallbacks(sendPlay); sendPlay.run() }
                else {
                    want = w?.copy(paused = false)
                    command(ECHO_MS) { lead.alignMs?.let { api.player.seek(it) }; api.player.resume() }
                }
            }
            is Lead.Align -> if (!elsewhere && pendingContext == null) { log("${lead.ms - (webAt(w) ?: lead.ms)} ms out of step: the page is put back"); command(ECHO_MS) { api.player.seek(lead.ms) } }
        }
    }

    /**
     * The local player played its song again from 0:00 (repeat): the page plays it anew too, a new
     * play as Spotify's own repeat counts one (a seek back to 0 would go on with the same play).
     */
    fun onLocalRepeat() {
        if (!running || page.playsElsewhere.value) return
        val uri = localUri ?: return
        if (want?.uri != uri) return
        start(uri, 0L)
    }

    /** A state of the page while the mirror runs (SongPlayer.engineWhileLocal): a remote command, or our own echo. */
    fun onWeb(s: PlayerState) {
        val w = want ?: return
        val item = s.item ?: return
        val now = System.currentTimeMillis()
        // An ad, a song still loading, Spotify limiting plays: nothing to judge on. The page's clock
        // after it lies between where it stood then and where its last state puts it: both are kept
        // for the seek test (a seek just before the gap is still followed, a stall in it is not one).
        if (s.isBuffering || page.adPlaying.value || MyspotiRuntime.host.licenseLimited()) {
            if (gapFrom == null) gapFrom = lastWeb?.takeIf { it.item?.uri == w.uri }?.positionAt(now)
            return
        }
        val web = MirrorRules.Web(item.uri, s.isPaused, s.positionAt(now))
        val before = lastWeb?.takeIf { it.item?.uri == w.uri }
        val predicted = before?.takeIf { item.uri == w.uri }?.positionAt(now)
        // Where the page is in the asked song, and its length as Spotify has it (the end it reaches).
        val wantAt = if (item.uri == w.uri) web.positionMs else before?.positionAt(now)
        val wantLength = (if (item.uri == w.uri) item.durationMs else before?.item?.durationMs) ?: 0L
        val gap = gapFrom
        gapFrom = null
        if (item.uri == w.uri) lastWeb = s
        if (item.uri == w.uri && !s.isPaused) {
            if (failedUri == w.uri) log("the page plays ${w.uri} again")
            landed = true; failedUri = null; backoffStep = 0
        }
        val t = LocalPlayer.current() ?: return
        val (previousId, nextId) = LocalPlayer.neighbourIds()
        val local = MirrorRules.Local(
            SPOTIFY_TRACK_PREFIX + LocalPlayer.spotifyIdOf(t), LocalPlayer.playWhenReady(), LocalPlayer.position(), LocalPlayer.duration(),
            nextUri = nextId.takeIf { it.isNotBlank() }?.let { SPOTIFY_TRACK_PREFIX + it },
            previousUri = previousId.takeIf { it.isNotBlank() }?.let { SPOTIFY_TRACK_PREFIX + it },
        )
        val quiet = echo.quiet()
        // A state the echo window hides is looked at again once it is over (a remote pause right after a command).
        if (quiet) { main.removeCallbacks(recheck); main.postDelayed(recheck, echo.remainingMs() + RECHECK_SLACK_MS) }
        // Recovering from an ad or a reload: a song other than the asked one is the player's own play, sent back.
        val sure = landed && SystemClock.elapsedRealtime() >= recoverUntil
        when (val f = MirrorRules.onWeb(web, w, local, quiet, sure, predicted, previousUri, gap, wantAt, wantLength, recentUris())) {
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
            Follow.Pause -> { log("paused on another device"); want = w.copy(paused = true); LocalPlayer.pause() }
            Follow.Resume -> { log("resumed on another device"); want = w.copy(paused = false); LocalPlayer.resume() }
            is Follow.Seek -> { log("moved to ${f.ms} ms on another device"); LocalPlayer.seekTo(f.ms) }
            Follow.Next -> { log("next on another device"); moved(web, s); LocalPlayer.next() }
            Follow.Previous -> { log("previous on another device"); moved(web, s); LocalPlayer.previousItem() }
            Follow.Resend -> if (pendingContext != null) Unit else if (resends++ < MAX_RESENDS) {
                log("the page is still on ${web.uri}, played earlier by the mirror: ${w.uri} sent again")
                playNow(w.uri, w.uri)
            } else backOff(w)
            is Follow.Play -> { log("${f.uri} picked on another device"); moved(web, s); SongPlayer.followRemotePlay(item, s.contextUri, f.positionMs) }
            is Follow.Align -> command(ECHO_MS) { api.player.seek(f.ms) }
        }
    }

    /** The page's latest state, judged again when the echo window that hid it is over. */
    private val recheck: Runnable = object : Runnable {
        override fun run() {
            if (!running || !LocalPlayer.active.value || SongPlayer.routing) return
            // Renewed meanwhile (the page answered late): looked at once that one is over.
            if (echo.quiet()) { main.postDelayed(this, echo.remainingMs() + RECHECK_SLACK_MS); return }
            page.state.value?.let(::onWeb)
        }
    }

    /**
     * The page would not take [w]'s song (twice): never given up for good. It is paused meanwhile
     * (unheard, at its lowest quality, nothing else plays on the account) and the song is played there
     * again after [MirrorRules.backoffMs], or at the next play, resume or seek of the local player.
     */
    private fun backOff(w: Want) {
        val delayMs = MirrorRules.backoffMs(backoffStep++)
        failedUri = w.uri
        log("the page would not play ${w.uri}: the stream plays alone, tried again in ${delayMs / 1000} s or at the next play or seek")
        landed = false; lastWeb = null; gapFrom = null
        // One try each time from now on: a miss goes to the next, longer wait.
        retries = MAX_RETRIES; resends = MAX_RESENDS
        command(ECHO_MS) { api.player.pause() }
        pendingContext = w.uri
        main.removeCallbacks(sendPlay); main.postDelayed(sendPlay, delayMs)
    }

    /** The page is usable again after an ad or a reload: it gets the local player's song, where it is. */
    private fun pageBack() {
        val w = want?.takeIf { running } ?: return
        log("the page is back (ad over or reloaded): ${w.uri} played there again")
        landed = false; retries = 0; resends = 0; lastWeb = null; gapFrom = null
        recoverUntil = SystemClock.elapsedRealtime() + RECOVER_MS
        // The track alone: the list the page had went with the ad's or the reload's own plays.
        pendingContext = w.uri
        echo.sent(PLAY_ECHO_MS)
        main.removeCallbacks(sendPlay); main.postDelayed(sendPlay, START_COALESCE_MS)
    }

    /** The page went to [web]'s song by a remote command: that is what it is asked to be on now. */
    private fun moved(web: MirrorRules.Web, s: PlayerState) {
        previousUri = want?.uri
        want = Want(web.uri, web.paused); landed = true; retries = 0; resends = 0; lastWeb = s; gapFrom = null
        failedUri = null; backoffStep = 0
        echo.sent(PLAY_ECHO_MS)
    }

    /** The page plays [uri] from [at], unheard (the mirror starts, or goes on with the local player's next song). */
    private fun start(uri: String, at: Long) {
        if (!running) {
            running = true
            // An extension's own mirror (playback.mirror) gives way: two would fight over the page.
            com.music.spotui.extensions.Extensions.mirrorReset("Myspoti plays the song on Spotify itself")
            // The page asks Android for the audio focus as it plays: the local player must not yield it.
            LocalPlayer.setMirrorFocus(true)
            startDriftCheck()
            log("on")
        }
        handedAway = false
        previousUri = want?.uri
        want = Want(uri, paused = false); landed = false; retries = 0; resends = 0; lastWeb = null; gapFrom = null
        failedUri = null; backoffStep = 0
        // In the list the local queue came from, as the phone would play it: other devices see that queue after it.
        pendingContext = MirrorRules.playContext(LocalPlayer.contextUri, uri, missedContexts)
        log("$uri plays unheard on Spotify from $at ms")
        // A skip right after this one replaces it: one play for a burst of skips (they landed out of order).
        echo.sent(PLAY_ECHO_MS)
        main.removeCallbacks(sendPlay)
        main.postDelayed(sendPlay, START_COALESCE_MS)
    }

    private val sendPlay = Runnable {
        val w = want?.takeIf { running } ?: return@Runnable
        val context = pendingContext ?: return@Runnable
        // The local player paused or stalled meanwhile: played when it plays (Lead.Resume).
        if (w.paused) return@Runnable
        pendingContext = null
        playNow(w.uri, context)
    }

    /** Plays [uri] (in [context]) on the page from the local position, unheard, noted as the mirror's own play. */
    private fun playNow(uri: String, context: String) {
        val at = LocalPlayer.position()
        val now = SystemClock.elapsedRealtime()
        recentPlays.addLast(uri to now)
        while (recentPlays.size > MAX_RECENT) recentPlays.removeFirst()
        playedContext = context
        // Whether it landed is judged once its echo is over, even if the page reports nothing.
        main.removeCallbacks(recheck); main.postDelayed(recheck, PLAY_ECHO_MS + RECHECK_SLACK_MS)
        // An earlier mirror's end runs under the same lock, before this: never heard under this one.
        command(PLAY_ECHO_MS) { live ->
            // Before the play: the page picks the song's file with the quality it has then.
            page.setMirroring(true)
            if (!api.player.localMute) api.player.setLocalMute(true)
            // Ended meanwhile: no play that would be heard next to the local player.
            if (live()) api.player.play(context, trackUri = uri.takeIf { it != context }, positionMs = at)
        }
    }

    /** The songs the mirror asked the page to play in the last [RECENT_MS]. */
    private fun recentUris(): List<String> {
        val since = SystemClock.elapsedRealtime() - RECENT_MS
        return recentPlays.filter { it.second >= since }.map { it.first }
    }

    /**
     * Ends the mirror: the page is heard again, at the user's quality. [pausePage]: the song it played
     * is paused first (not when another device has playback, or a transfer moves it). True when it ran.
     */
    fun stop(reason: String, pausePage: Boolean = true): Boolean {
        if (!running) return false
        running = false
        generation++
        val heard = pausePage && want?.paused == false && !page.playsElsewhere.value
        want = null; previousUri = null; lastWeb = null; gapFrom = null; landed = false; stalled = false
        main.removeCallbacks(recheck)
        main.removeCallbacks(sendPlay); pendingContext = null; recentPlays.clear()
        if (stallPending) { main.removeCallbacks(stallCheck); stallPending = false }
        driftJob?.cancel(); driftJob = null
        echo.clear()
        LocalPlayer.setMirrorFocus(false)
        log("off: $reason")
        // Behind the lock: a play still on its way lands first, then is paused here. Waited for by
        // the next transport command (SongPlayer): a song Spotify plays next must find the page
        // heard, at the user's quality, the mirrored one paused. Each step on its own: a pause that
        // fails must not leave the page unheard or at its lowest quality.
        ending = MyspotiRuntime.scope.launch {
            lock.withLock {
                if (heard) runCatching { api.player.pause() }.onFailure { log("ending: pause failed: ${it.message}") }
                runCatching { api.player.setLocalMute(false) }.onFailure { log("ending: unmute failed: ${it.message}") }
                runCatching { page.setMirroring(false) }.onFailure { log("ending: quality not restored: ${it.message}") }
            }
        }
        return true
    }

    /** Waits until the last mirror's end reached the page (see [stop]). */
    suspend fun awaitEnded() { ending?.join() }

    /** Playback was handed to another device and has not come back to play here yet (see [onEngine]). */
    val waitsBack: Boolean get() = handedAway

    /** Myspoti moves playback to another device: the mirror ends there (the transfer moves the song). */
    fun leaving(device: String) {
        if (stop("playback moved to $device", pausePage = false)) handedAway = true
    }

    /** "Count extension plays on Spotify" was switched: off ends the mirror, on starts it on the song playing. */
    fun settingChanged() {
        if (!ConnectMirrorPref.on) { stop("the setting was turned off"); failedUri = null }
        else main.post(::poke)
    }

    /** Another device of the account took playback while mirrored: the local player stops, as it would have. */
    private fun wentAway() {
        if (!running || want?.paused != false) return
        log("another device took playback: the local player stops")
        handedAway = true
        // Ends the mirror too (SongPlayer.unmuteMirror); nothing to pause here, the page stands still.
        LocalPlayer.stop()
    }

    /**
     * This page is Connect's active device again. Often paused (a transfer that restores paused,
     * the phone picked in another app's list): [handedAway] then stays until the engine plays here
     * ([onEngine]), when the song goes back to the extensions.
     */
    private fun cameBack() {
        if (handedAway) page.state.value?.let { onEngine(it, moved = false, intended = SongPlayer.engineIntended()) }
    }

    /**
     * A state of the engine while no local player plays (SongPlayer.applyState). [moved]: it started
     * or changed song; [intended]: Myspoti asked for it. Playback back on this phone, or moved here
     * by another device, goes back through the extensions (the mirror starts with the local player).
     */
    fun onEngine(s: PlayerState, moved: Boolean, intended: Boolean) {
        if (LocalPlayer.active.value || SongPlayer.routing) return
        val playingHere = !s.isPaused && !s.isBuffering && s.item != null && !page.playsElsewhere.value
        if (!playingHere) return
        val back = MirrorRules.backHere(playingHere, handedAway, moved, intended, ConnectMirrorPref.on)
        handedAway = false
        if (back) SongPlayer.backThroughExtensions()
    }

    /** The local player as it is now, judged as a state of it. */
    private fun poke() {
        if (!LocalPlayer.active.value || SongPlayer.routing) return
        val t = LocalPlayer.current() ?: return
        onLocal(t, LocalPlayer.playWhenReady(), LocalPlayer.position(), LocalPlayer.duration())
    }

    /** Where the page should be now on [w]'s song, from its last state; null when unknown. */
    private fun webAt(w: Want?): Long? = if (gapFrom != null) null else
        lastWeb?.takeIf { w != null && it.item?.uri == w.uri && !it.isBuffering }?.positionAt(System.currentTimeMillis())

    private val stallCheck = Runnable {
        stallPending = false
        if (!running || !LocalPlayer.isBuffering() || !LocalPlayer.playWhenReady()) return@Runnable
        log("the local player is buffering: the page waits for it")
        stalled = true
        poke()
    }

    /** A buffering that lasts pauses the page ([stallCheck]); the local player playing again resumes both. */
    private fun trackStall(playing: Boolean) {
        if (playing && LocalPlayer.isBuffering()) {
            if (!stalled && !stallPending) { stallPending = true; main.postDelayed(stallCheck, STALL_MS) }
        } else {
            if (stallPending) { main.removeCallbacks(stallCheck); stallPending = false }
            stalled = false
        }
    }

    /** Between events the two clocks can part slowly: looked at again every [DRIFT_CHECK_MS], while it runs. */
    private fun startDriftCheck() {
        driftJob?.cancel()
        driftJob = MyspotiRuntime.scope.launch {
            while (true) {
                delay(DRIFT_CHECK_MS)
                if (LocalPlayer.isPlaying()) poke()
            }
        }
    }

    /**
     * Sends [block] after the mirror's earlier commands (and an ending, see [stop]); its answers are its
     * echo for [echoMs]. [block] is told whether this mirror still runs (`live`), to check between steps.
     */
    private fun command(echoMs: Long, block: suspend (live: () -> Boolean) -> Unit) {
        echo.sent(echoMs)
        val gen = generation
        val live = { gen == generation }
        MyspotiRuntime.scope.launch {
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

    /** For the diagnostic (`status`): what the mirror does now. */
    data class Status(
        val on: Boolean, val running: Boolean, val uri: String?, val paused: Boolean?, val landed: Boolean,
        val webPositionMs: Long?, val handedAway: Boolean, val failedUri: String?,
    )

    fun status() = Status(ConnectMirrorPref.on, running, want?.uri, want?.paused, landed, webAt(want), handedAway, failedUri)

    private fun log(line: String) = app.myspoti.host.android.Logbook.log("mirror: $line")
}
