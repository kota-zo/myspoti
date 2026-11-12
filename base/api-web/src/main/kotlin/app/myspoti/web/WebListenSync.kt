/* SPDX-License-Identifier: GPL-3.0-only */
package app.myspoti.web

import app.myspoti.api.PlayerState
import app.myspoti.api.sync.ListenSync
import app.myspoti.api.sync.ListenSyncHost
import app.myspoti.api.sync.ListenSyncRules
import app.myspoti.api.sync.ListenSyncRules.Follow
import app.myspoti.api.sync.ListenSyncRules.Lead
import app.myspoti.api.sync.ListenSyncRules.Want
import app.myspoti.api.sync.ListenSyncStatus
import app.myspoti.api.sync.LocalNow
import app.myspoti.api.sync.QueueTake
import app.myspoti.api.sync.SyncTrack
import app.myspoti.api.sync.TrackIdResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The listen sync of a provider whose web player has no documented remote control (YouTube Music):
 * while the local player plays a song an extension streams live, the provider's own player in the
 * hidden page ([page]) plays the same track, unheard, at the same place, so the play is a real one in
 * the user's history. The page follows the local player (play, pause, seek, skip, repeat), is put back
 * in step when the two drift apart, and a song it would not take is tried again later. Nothing else
 * commands the page, so what it does by itself is never followed by the local player: a pause of its
 * own is resumed (twice at most, then the song is left alone: the page is not fought), another song it
 * went to is replaced by the asked one, a jump is put back. While the page plays an ad, the sync waits
 * (its commands are held) and puts the page back in step after it; it never skips or blocks one.
 * Only the page's own playback counts the listen: the sync sends no report of its own. Downloads and
 * files of the phone are never synced.
 *
 * The decisions are [ListenSyncRules]'s, as for Spotify; songs are the provider's ids ([isTrackId]
 * checks their form), given by [resolver]. Runs on [scope] (the main thread in the app). [clock] is
 * monotonic (ms), [wallClock] the one the page's states are dated with.
 */
class WebListenSync(
    override val provider: String,
    private val page: SyncPagePlayer,
    private val host: ListenSyncHost,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    private val isTrackId: (String) -> Boolean,
    private val resolver: TrackIdResolver = TrackIdResolver.KnownIds,
    private val wallClock: () -> Long = System::currentTimeMillis,
    private val timing: Timing = Timing(),
) : ListenSync {
    /**
     * The sync's waits. [playEchoMs]: a play is answered within that (a page load for a provider
     * that loads a page per song); [echoMs]: a pause, resume or seek.
     */
    data class Timing(
        val playEchoMs: Long = 15_000,
        val echoMs: Long = 2_500,
        val startCoalesceMs: Long = 300,
        val stallMs: Long = 1_000,
        val driftCheckMs: Long = 20_000,
        val recheckSlackMs: Long = 100,
        val recoverMs: Long = 10_000,
        val maxRetries: Int = 1,
        val maxSelfResumes: Int = 2,
        /** The page's playback standing still that long while it should play: the page is reloaded (0: never). */
        val pageStallMs: Long = 25_000,
        /** Never two reloads for a stall closer than that. */
        val pageReloadGapMs: Long = 60_000,
        /** How often the page's playback is looked at while the sync runs. */
        val watchMs: Long = 5_000,
    )

    private val echo = ListenSyncRules.Echo(clock)
    private val lock = Mutex()
    private var generation = 0
    private var attached = false

    @Volatile override var running = false
        private set
    private var want: Want? = null
    private var previousUri: String? = null
    private var landed = false
    private var retries = 0
    private var resends = 0
    private var selfResumes = 0
    private var lastWeb: PageState? = null
    private var gapFrom: Long? = null
    private var stalled = false
    private var localUri: String? = null
    private var localWasPlaying = false
    private var localAtMs = 0L
    private var localAtTime = 0L
    /** A play waits ([sendPlay]): coalescing fast skips, or backing off a song the page would not take. */
    private var pendingStart = false
    private var failedUri: String? = null
    private var backoffStep = 0
    /** A song left alone: the page kept pausing it by itself. Tried again only at the user's own play or seek. */
    private var gaveUp: String? = null
    private var note: String? = null
    private var recoverUntil = 0L
    private val recentPlays = ArrayDeque<Pair<String, Long>>()
    /** A command runs now: the page reloading under a play is not "the page is back". */
    @Volatile private var commanding = false
    private var driftJob: Job? = null
    private var watchJob: Job? = null
    private var ending: Job? = null
    private val watch = PageWatch(timing.pageStallMs)
    /** When the page was last reloaded for a stall ([clock]); 0: never. */
    private var stallReloadAt = 0L

    private val recheck: Later = Later(scope) { recheckNow() }
    private val sendPlay = Later(scope) { sendPlayNow() }
    private val stallCheck = Later(scope) {
        val now = host.local()
        if (!running || now?.buffering != true || !now.playing) return@Later
        log("the local player is buffering: the page waits for it")
        stalled = true
        poke()
    }

    override fun attach() {
        if (attached) return
        attached = true
        scope.launch { page.state.collect { s -> s?.let { watch.state(it, clock(), want?.uri) }; onPage(s) } }
        // The page usable again (an ad over, a reload not of the sync's own): put back in step.
        scope.launch {
            combine(page.ready, page.adPlaying) { ready, ad -> ready && !ad }.distinctUntilChanged()
                .collect { usable -> if (usable && !commanding) pageBack() }
        }
    }

    private fun pageUsable() = page.ready.value && !page.adPlaying.value

    private fun idOf(t: SyncTrack): String? = resolver.idFor(t, provider)?.takeIf(isTrackId)

    override fun onLocal(now: LocalNow) {
        val id = idOf(now.track)
        val uri = id.orEmpty()
        val time = clock()
        val expected = if (uri != localUri) null else localAtMs + if (localWasPlaying) time - localAtTime else 0L
        val moved = ListenSyncRules.localMoved(localWasPlaying, now.playing, expected, now.positionMs)
        if (uri != localUri) { localUri = uri; failedUri = null; backoffStep = 0; gaveUp = null; selfResumes = 0 }
        localWasPlaying = now.playing; localAtMs = now.positionMs; localAtTime = time
        if (now.rate != 1f) {
            if (running) stop("the song plays at ${now.rate}×, the page plays at 1× only")
            return
        }
        if (!ListenSyncRules.eligible(now.live, id, host.enabled, host.dataSaver)) {
            if (running) stop(if (now.live) "not synced: ${now.track.title}" else "a download or a file of the phone plays")
            return
        }
        if (running) trackStall(now.playing, now.buffering)
        // The user's own play or seek tries a song again: one backing off, or one left alone.
        if (running && moved && now.playing) {
            if (failedUri == uri && pendingStart) { log("tried again on the page at a play or seek: $uri"); sendPlay.runNow() }
            else if (gaveUp == uri) { log("tried again on the page at a play or seek: $uri"); gaveUp = null; selfResumes = 0; note = null; start(uri, now.positionMs) ; return }
        }
        val w = want
        val local = ListenSyncRules.Local(uri, now.playing, now.positionMs, now.durationMs)
        when (val lead = ListenSyncRules.onLocal(local, w, stalled, echo.quiet(), webAt(w), previousUri)) {
            Lead.Nothing -> {}
            is Lead.Start -> start(lead.uri, lead.positionMs)
            Lead.Pause -> { want = w?.copy(paused = true); if (!pendingStart && gaveUp != w?.uri) command(timing.echoMs) { page.pause() } }
            is Lead.Resume -> {
                want = w?.copy(paused = false)
                if (gaveUp == w?.uri) Unit
                else if (pendingStart) sendPlay.runNow()
                else command(timing.echoMs) { lead.alignMs?.let { page.seek(it) }; page.resume() }
            }
            is Lead.Align -> if (!pendingStart && gaveUp != w?.uri) {
                log("${lead.ms - (webAt(w) ?: lead.ms)} ms out of step: the page is put back")
                command(timing.echoMs) { page.seek(lead.ms) }
            }
        }
    }

    /** The local song plays again from 0:00 (repeat): played anew on the page, a new play. */
    override fun onLocalRepeat() {
        if (!running) return
        val uri = localUri ?: return
        if (want?.uri != uri) return
        start(uri, 0L)
    }

    /** No other app commands this page: the app's states of another provider's page are not this sync's. */
    override fun onProviderState(s: PlayerState) {}

    override fun onProviderPlayback(s: PlayerState, moved: Boolean, intended: Boolean) {}

    /** A state of the page: the sync's own echo, the page's own doing (put right), or the drift. */
    private fun onPage(s: PageState?) {
        if (!running || s == null) return
        val w = want ?: return
        val nowMs = wallClock()
        if (s.buffering || s.ad || page.adPlaying.value) {
            if (gapFrom == null) gapFrom = lastWeb?.takeIf { it.trackId == w.uri }?.positionAt(nowMs)
            return
        }
        val id = s.trackId ?: return
        val web = ListenSyncRules.Remote(id, s.paused, s.positionAt(nowMs))
        val before = lastWeb?.takeIf { it.trackId == w.uri }
        val predicted = before?.takeIf { id == w.uri }?.positionAt(nowMs)
        val wantAt = if (id == w.uri) web.positionMs else before?.positionAt(nowMs)
        val wantLength = if (id == w.uri) s.durationMs else before?.durationMs ?: 0L
        val gap = gapFrom
        gapFrom = null
        if (id == w.uri) lastWeb = s
        if (id == w.uri && !s.paused) {
            if (failedUri == w.uri) log("the page plays ${w.uri} again")
            landed = true; failedUri = null; backoffStep = 0; note = null
        }
        val now = host.local() ?: return
        val local = ListenSyncRules.Local(idOf(now.track).orEmpty(), now.playing, now.positionMs, now.knownLengthMs ?: 0L)
        val quiet = echo.quiet()
        if (quiet) recheck.schedule(echo.remainingMs() + timing.recheckSlackMs)
        val sure = landed && clock() >= recoverUntil
        val f = ListenSyncRules.onRemote(web, w, local, quiet, sure, predicted, previousUri, gap, wantAt, wantLength, recentUris(), now.repeatsCurrent)
        if (gaveUp == w.uri) return
        when (f) {
            Follow.Nothing -> {}
            Follow.Retry -> if (!pendingStart) {
                if (retries++ < timing.maxRetries) { log("the page did not take ${w.uri}: played again"); playNow(w.uri) } else backOff(w)
            }
            Follow.Resend -> if (!pendingStart) {
                if (resends++ < timing.maxRetries + 1) { log("the page is still on ${web.uri}: ${w.uri} sent again"); playNow(w.uri) } else backOff(w)
            }
            // Nobody else commands the page: a pause is its own (a prompt, a hidden page held). Resumed, not fought.
            Follow.Pause -> if (selfResumes++ < timing.maxSelfResumes) {
                log("the page paused by itself: resumed")
                command(timing.echoMs) { page.resume() }
            } else giveUp(w, "the page keeps pausing by itself: ${w.uri} plays here alone")
            Follow.Resume -> { log("the page plays while the local player is paused: paused"); command(timing.echoMs) { page.pause() } }
            is Follow.Seek -> { log("the page moved by itself to ${f.ms} ms: put back"); command(timing.echoMs) { page.seek(local.positionMs) } }
            // Another song on the page (its own autoplay or suggestion): never followed, the asked one is played again.
            Follow.Next, Follow.Previous, is Follow.Play -> if (!pendingStart) {
                if (retries++ < timing.maxRetries + 1) { log("the page went to ${web.uri} by itself: put back on ${w.uri}"); playNow(w.uri) } else backOff(w)
            }
            is Follow.Align -> command(timing.echoMs) { page.seek(f.ms) }
            Follow.Back -> if (!pendingStart) { log("the page went on by itself while the song is to play again here: put back on ${w.uri}"); playNow(w.uri) }
        }
    }

    private fun recheckNow() {
        if (!running || !host.localActive || host.routing) return
        if (echo.quiet()) { recheck.schedule(echo.remainingMs() + timing.recheckSlackMs); return }
        onPage(page.state.value)
    }

    /** A song the page would not take: tried again after [ListenSyncRules.backoffMs] or at the next play or seek here. */
    private fun backOff(w: Want) {
        val delayMs = ListenSyncRules.backoffMs(backoffStep++)
        failedUri = w.uri
        note = "the page would not play ${w.uri}"
        log("the page would not play ${w.uri}: the stream plays alone, tried again in ${delayMs / 1000} s or at the next play or seek")
        landed = false; lastWeb = null; gapFrom = null
        retries = timing.maxRetries; resends = timing.maxRetries + 1
        command(timing.echoMs) { page.pause() }
        pendingStart = true
        sendPlay.schedule(delayMs)
    }

    /** The page will not keep [w]'s song playing by itself: it is paused and left alone (the local song plays on). */
    private fun giveUp(w: Want, why: String) {
        gaveUp = w.uri
        note = why
        log(why)
        landed = false; lastWeb = null; gapFrom = null
        pendingStart = false; sendPlay.cancel()
        command(timing.echoMs) { page.pause() }
    }

    /** The page is usable again (an ad over, a reload): it is put back on the local song, where it is. */
    private fun pageBack() {
        val w = want?.takeIf { running } ?: return
        if (gaveUp == w.uri || pendingStart) return
        recoverUntil = clock() + timing.recoverMs
        val s = page.state.value
        if (s?.trackId == w.uri) {
            log("the page is back (ad over or reloaded): put back in step")
            command(timing.echoMs) {
                page.seek(host.local()?.positionMs ?: 0L)
                if (w.paused) page.pause() else page.resume()
            }
        } else {
            log("the page is back (ad over or reloaded): ${w.uri} played there again")
            landed = false; retries = 0; resends = 0; lastWeb = null; gapFrom = null
            pendingStart = true
            echo.sent(timing.playEchoMs)
            sendPlay.schedule(timing.startCoalesceMs)
        }
    }

    private fun start(uri: String, at: Long) {
        if (!running) {
            running = true
            host.syncRunning(true)
            startDriftCheck()
            startWatch()
            log("on")
        }
        previousUri = want?.uri
        want = Want(uri, paused = false); landed = false; retries = 0; resends = 0; lastWeb = null; gapFrom = null
        failedUri = null; backoffStep = 0; selfResumes = 0
        log("$uri plays unheard on $provider from $at ms")
        pendingStart = true
        echo.sent(timing.playEchoMs)
        sendPlay.schedule(timing.startCoalesceMs)
    }

    private fun sendPlayNow() {
        val w = want?.takeIf { running } ?: return
        if (!pendingStart || w.paused) return
        pendingStart = false
        playNow(w.uri)
    }

    /** Plays [uri] on the page from the local position (read when the page can seek). */
    private fun playNow(uri: String) {
        recentPlays.addLast(uri to clock())
        while (recentPlays.size > MAX_RECENT) recentPlays.removeFirst()
        recheck.schedule(timing.playEchoMs + timing.recheckSlackMs)
        command(timing.playEchoMs) { live ->
            if (!live()) return@command
            page.play(uri) { host.local()?.positionMs ?: 0L }
        }
    }

    private fun recentUris(): List<String> {
        val since = clock() - RECENT_MS
        return recentPlays.filter { it.second >= since }.map { it.first }
    }

    override fun stop(reason: String, pauseRemote: Boolean): Boolean {
        if (!running) return false
        running = false
        generation++
        want = null; previousUri = null; lastWeb = null; gapFrom = null; landed = false; stalled = false
        recheck.cancel(); sendPlay.cancel(); pendingStart = false; recentPlays.clear(); stallCheck.cancel()
        driftJob?.cancel(); driftJob = null
        watchJob?.cancel(); watchJob = null
        echo.clear()
        host.syncRunning(false)
        log("off: $reason")
        // The page never plays by itself: what it played for the sync is paused (nobody would hear it).
        ending = scope.launch {
            lock.withLock { runCatching { page.release() }.onFailure { log("ending: pause failed: ${it.message}") } }
        }
        return true
    }

    override suspend fun awaitEnded() { ending?.join() }

    override val waitsBack: Boolean get() = false

    override fun leaving(device: String) { stop("playback moved to $device") }

    override fun refresh() {
        if (!host.enabled) { stop("the setting was turned off or the account signed out"); failedUri = null; gaveUp = null }
        else scope.launch { poke() }
    }

    private fun poke() {
        if (!host.localActive || host.routing) return
        host.local()?.let(::onLocal)
    }

    private fun webAt(w: Want?): Long? = if (gapFrom != null) null else
        lastWeb?.takeIf { w != null && it.trackId == w.uri && !it.buffering }?.positionAt(wallClock())

    private fun trackStall(playing: Boolean, buffering: Boolean) {
        if (playing && buffering) {
            if (!stalled && !stallCheck.pending) stallCheck.schedule(timing.stallMs)
        } else {
            stallCheck.cancel()
            stalled = false
        }
    }

    private fun startDriftCheck() {
        driftJob?.cancel()
        driftJob = scope.launch {
            while (true) {
                delay(timing.driftCheckMs)
                if (host.local()?.audible == true) poke()
            }
        }
    }

    /**
     * The page's own playback looked at every [Timing.watchMs] while it should play the asked song
     * (landed, not paused, no ad, nothing pending): standing still for [Timing.pageStallMs] (buffering
     * for good, a clock that does not move, a page that posts nothing any more), it is loaded again
     * once (then not before [Timing.pageReloadGapMs]); it gets the song back as after any reload.
     */
    private fun startWatch() {
        if (timing.pageStallMs <= 0) return
        watchJob?.cancel()
        watch.reset(clock())
        watchJob = scope.launch {
            while (true) {
                delay(timing.watchMs)
                val w = want
                val expects = running && w != null && !w.paused && landed && pageUsable() && !pendingStart && !stalled &&
                    gaveUp != w.uri && failedUri != w.uri && clock() >= recoverUntil && host.local()?.audible == true
                if (!watch.stalled(clock(), expects)) continue
                val now = clock()
                if (stallReloadAt > 0 && now - stallReloadAt < timing.pageReloadGapMs) continue
                stallReloadAt = now
                log("the page's playback stands still: loaded again, ${w?.uri} put back once it is ready")
                landed = false; lastWeb = null; gapFrom = null
                watch.reset(now)
                lock.withLock { runCatching { page.reload() }.onFailure { log("reload failed: ${it.message}") } }
            }
        }
    }

    /** Sends [block] after the sync's earlier commands; its answers are its echo for [echoMs]. Held while the page is not usable. */
    private fun command(echoMs: Long, block: suspend (live: () -> Boolean) -> Unit) {
        echo.sent(echoMs)
        val gen = generation
        val live = { gen == generation }
        scope.launch {
            lock.withLock {
                if (!live()) return@withLock
                if (!pageUsable()) { log("held: the page is in an ad, loading or signed out"); return@withLock }
                commanding = true
                try {
                    runCatching { block(live) }.onFailure { log("command failed: ${it.message}") }
                } finally {
                    commanding = false
                }
                if (live()) echo.sent(echoMs)
            }
        }
    }

    override fun status() = ListenSyncStatus(
        provider, host.enabled, running, want?.uri?.let { "$provider:track:$it" }, want?.paused, landed, webAt(want),
        handedAway = false, failedUri = (failedUri ?: gaveUp)?.let { "$provider:track:$it" },
        remoteControl = false, note = note ?: if (running && !page.ready.value) "the page is loading or signed out" else null,
    )

    /** The provider's queue is not read or written: no documented way. */
    override fun localQueueChanged(queued: List<SyncTrack>) {}
    override fun adoptRemoteQueue(localNext: List<SyncTrack>): List<QueueTake> = emptyList()
    override fun remoteQueueTaken(taken: List<SyncTrack>) {}

    private fun log(line: String) = host.log("$provider sync: $line")

    companion object {
        const val RECENT_MS = 15_000L
        const val MAX_RECENT = 4
    }
}

/** A delayed run of [block] on [scope] that a new [schedule] replaces. */
internal class Later(private val scope: CoroutineScope, private val block: () -> Unit) {
    private var job: Job? = null
    val pending: Boolean get() = job?.isActive == true

    fun schedule(delayMs: Long) {
        job?.cancel()
        job = scope.launch { delay(delayMs); job = null; block() }
    }

    fun cancel() { job?.cancel(); job = null }

    fun runNow() { cancel(); block() }
}
