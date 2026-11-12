package app.myspoti.web

import app.myspoti.api.NowPlaying
import app.myspoti.api.Uri
import app.myspoti.api.sync.ListenSyncHost
import app.myspoti.api.sync.LocalNow
import app.myspoti.api.sync.SyncTrack
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The web player listen sync driven end to end against a page and a local player of the test's own. */
@OptIn(ExperimentalCoroutinesApi::class)
class WebListenSyncTest {
    private val a = "aaaaaaaaaaa"
    private val b = "bbbbbbbbbbb"

    /** A page that lands on what it is asked at once (its clock is the test's). */
    private class Page(private val clock: () -> Long) : SyncPagePlayer {
        val calls = ArrayList<String>()
        override val ready = MutableStateFlow(true)
        override val adPlaying = MutableStateFlow(false)
        override val state = MutableStateFlow<PageState?>(null)
        var lands = true

        fun show(id: String?, paused: Boolean, at: Long, ad: Boolean = false) {
            state.value = PageState(id, paused, buffering = false, positionMs = at, timestampMs = clock(), durationMs = 200_000, ad = ad)
        }

        override suspend fun play(trackId: String, positionMs: () -> Long) {
            val at = positionMs()
            calls += "play $trackId $at"
            if (lands) show(trackId, paused = false, at = at)
        }
        override suspend fun pause() { calls += "pause"; state.value?.let { show(it.trackId, true, it.positionAt(clock())) } }
        override suspend fun resume() { calls += "resume"; state.value?.let { show(it.trackId, false, it.positionMs) } }
        override suspend fun seek(positionMs: Long) { calls += "seek $positionMs"; state.value?.let { show(it.trackId, it.paused, positionMs) } }
        override suspend fun release() { calls += "release" }
        override suspend fun reload() { calls += "reload" }
    }

    private class Host(private val clock: () -> Long) : ListenSyncHost {
        var now: LocalNow? = null
            set(value) { field = value; nowAt = clock() }
        private var nowAt = 0L
        val calls = ArrayList<String>()
        val running = ArrayList<Boolean>()
        val lines = ArrayList<String>()
        override fun local() = now?.let { if (it.playing) it.copy(positionMs = it.positionMs + clock() - nowAt) else it }
        override fun queued() = emptyList<SyncTrack>()
        override val localActive get() = now != null
        override val routing = false
        override var enabled = true
        override var dataSaver = false
        override val engineIntended = false
        override fun pause() { calls += "pause" }
        override fun resume() { calls += "resume" }
        override fun seekTo(ms: Long) { calls += "seek $ms" }
        override fun next() { calls += "next" }
        override fun previous() { calls += "previous" }
        override fun stop() { calls += "stop" }
        override fun playRemotePick(item: NowPlaying, contextUri: Uri?, positionMs: Long) { calls += "pick" }
        override fun playHereAgain() { calls += "here again" }
        override fun syncRunning(on: Boolean) { running += on }
        override fun log(line: String) { lines += line }
    }

    private fun track(id: String) = SyncTrack(ids = mapOf("ytmusic" to id), title = "t-$id")

    private fun live(id: String = a, at: Long = 10_000, live: Boolean = true, playing: Boolean = true, rate: Float = 1f) = LocalNow(
        track = track(id), live = live, playing = playing, positionMs = at, durationMs = 200_000, knownLengthMs = 200_000, rate = rate,
    )

    private class Rig(val page: Page, val host: Host, val sync: WebListenSync)

    /** The page of these tests posts no state by itself: its stall watch is off but in the tests made for it. */
    private val timing = WebListenSync.Timing(pageStallMs = 0)

    private fun TestScope.rig(timing: WebListenSync.Timing = this@WebListenSyncTest.timing): Rig {
        val clock = { testScheduler.currentTime }
        val page = Page(clock)
        val host = Host(clock)
        val sync = WebListenSync("ytmusic", page, host, backgroundScope, clock = clock, isTrackId = { it.length == 11 }, wallClock = clock, timing = timing)
        sync.attach()
        runCurrent()
        return Rig(page, host, sync)
    }

    private fun TestScope.started(): Rig = rig().also { r ->
        r.host.now = live()
        r.sync.onLocal(r.host.now!!)
        advanceTimeBy(timing.startCoalesceMs + 1); runCurrent()
    }

    private fun TestScope.afterEcho() { advanceTimeBy(timing.playEchoMs + timing.recheckSlackMs + 10); runCurrent() }

    @Test fun `a live stream with an id plays on the page from the local position`() = runTest {
        val r = started()
        assertTrue(r.sync.running)
        assertEquals(listOf(true), r.host.running)
        assertEquals(1, r.page.calls.size)
        assertTrue(r.page.calls[0].startsWith("play $a "), r.page.calls.toString())
        afterEcho()
        assertTrue(r.sync.status().landed)
        assertEquals(false, r.sync.status().remoteControl)
        assertEquals("ytmusic:track:$a", r.sync.status().uri)
    }

    @Test fun `a download, a file or the setting off is never synced`() = runTest {
        val r = rig()
        r.sync.onLocal(live(live = false).also { r.host.now = it })
        r.host.enabled = false
        r.sync.onLocal(live().also { r.host.now = it })
        r.host.enabled = true
        r.host.dataSaver = true
        r.sync.onLocal(live().also { r.host.now = it })
        r.host.dataSaver = false
        r.sync.onLocal(live(id = "short").also { r.host.now = it })
        advanceTimeBy(1_000); runCurrent()
        assertFalse(r.sync.running)
        assertTrue(r.page.calls.isEmpty())
    }

    @Test fun `fast skips are one play`() = runTest {
        val r = rig()
        r.sync.onLocal(live(a).also { r.host.now = it })
        advanceTimeBy(100); runCurrent()
        r.sync.onLocal(live(b, at = 0).also { r.host.now = it })
        advanceTimeBy(timing.startCoalesceMs + 1); runCurrent()
        assertEquals(1, r.page.calls.count { it.startsWith("play") })
        assertTrue(r.page.calls[0].startsWith("play $b"))
    }

    @Test fun `local pause, resume and seek are followed by the page`() = runTest {
        val r = started()
        afterEcho()
        r.page.calls.clear()
        val paused = live(at = r.host.local()!!.positionMs, playing = false)
        r.host.now = paused; r.sync.onLocal(paused); runCurrent()
        assertEquals(listOf("pause"), r.page.calls)
        advanceTimeBy(5_000); runCurrent()
        val resumed = live(at = paused.positionMs + 60_000)
        r.host.now = resumed; r.sync.onLocal(resumed); runCurrent()
        assertEquals(listOf("pause", "seek ${resumed.positionMs}", "resume"), r.page.calls)
    }

    @Test fun `a pause the page makes by itself is resumed twice, then the song is left alone`() = runTest {
        val r = started()
        afterEcho()
        repeat(3) {
            r.page.calls.clear()
            r.page.show(a, paused = true, at = r.host.local()!!.positionMs)
            runCurrent()
            advanceTimeBy(timing.echoMs + 200); runCurrent()
        }
        assertTrue(r.page.calls.contains("pause"), r.page.calls.toString())
        assertNotNull(r.sync.status().note)
        assertEquals("ytmusic:track:$a", r.sync.status().failedUri)
        assertTrue(r.host.calls.isEmpty(), "the local player is never commanded: ${r.host.calls}")
        // Not fought: further pauses of the page are left as they are.
        r.page.calls.clear()
        r.page.show(a, paused = true, at = r.host.local()!!.positionMs); runCurrent()
        advanceTimeBy(60_000); runCurrent()
        assertFalse(r.page.calls.any { it == "resume" || it.startsWith("play") }, r.page.calls.toString())
    }

    @Test fun `another song the page goes to is replaced by the asked one, never followed`() = runTest {
        val r = started()
        afterEcho()
        r.page.calls.clear()
        r.page.show(b, paused = false, at = 0)
        runCurrent()
        assertTrue(r.page.calls.any { it.startsWith("play $a") }, r.page.calls.toString())
        assertTrue(r.host.calls.isEmpty())
    }

    @Test fun `during an ad nothing is sent, after it the page is put back in step`() = runTest {
        val r = started()
        afterEcho()
        r.page.calls.clear()
        r.page.adPlaying.value = true
        r.page.show(a, paused = false, at = 0, ad = true)
        runCurrent()
        val paused = live(at = r.host.local()!!.positionMs, playing = false)
        r.host.now = paused; r.sync.onLocal(paused); runCurrent()
        assertTrue(r.page.calls.isEmpty(), "held during the ad: ${r.page.calls}")
        val resumed = live(at = paused.positionMs)
        r.host.now = resumed; r.sync.onLocal(resumed); runCurrent()
        r.page.show(a, paused = false, at = 0)
        r.page.adPlaying.value = false
        runCurrent()
        assertEquals(listOf("seek ${resumed.positionMs}", "resume"), r.page.calls)
    }

    @Test fun `drift is put back`() = runTest {
        val r = started()
        afterEcho()
        r.page.calls.clear()
        r.page.show(a, paused = false, at = r.host.local()!!.positionMs - 5_000)
        runCurrent()
        assertTrue(r.page.calls.any { it.startsWith("seek") }, r.page.calls.toString())
    }

    @Test fun `a play the page does not take is tried again, then backed off`() = runTest {
        val r = rig()
        r.page.lands = false
        r.host.now = live(); r.sync.onLocal(r.host.now!!)
        advanceTimeBy(timing.startCoalesceMs + 1); runCurrent()
        r.page.show(b, paused = true, at = 0)
        afterEcho()
        afterEcho()
        assertTrue(r.page.calls.count { it.startsWith("play $a") } >= 2, r.page.calls.toString())
        assertNotNull(r.sync.status().failedUri)
        r.page.lands = true
        advanceTimeBy(5_100); runCurrent()
        assertTrue(r.page.calls.last().startsWith("play $a"), r.page.calls.toString())
    }

    @Test fun `stop releases the page and ends the run`() = runTest {
        val r = started()
        assertTrue(r.sync.stop("test"))
        runCurrent()
        assertFalse(r.sync.running)
        assertEquals("release", r.page.calls.last())
        assertEquals(listOf(true, false), r.host.running)
        assertFalse(r.sync.stop("again"))
    }

    @Test fun `a repeat plays the song anew`() = runTest {
        val r = started()
        afterEcho()
        r.page.calls.clear()
        r.host.now = live(at = 0)
        r.sync.onLocalRepeat()
        advanceTimeBy(timing.startCoalesceMs + 1); runCurrent()
        assertTrue(r.page.calls.single().startsWith("play $a"), r.page.calls.toString())
    }

    @Test fun `off 1x the song plays alone`() = runTest {
        val r = started()
        r.sync.onLocal(live(rate = 1.25f).also { r.host.now = it })
        assertFalse(r.sync.running)
    }

    @Test fun `cookie header parsing keeps the first value of each name`() {
        val m = parseCookieHeader("A=1; SAPISID=abc/def; B=2=3; A=9; bad")
        assertEquals("1", m["A"]); assertEquals("abc/def", m["SAPISID"]); assertEquals("2=3", m["B"])
        assertTrue(parseCookieHeader(null).isEmpty())
    }

    // ── The page's playback standing still: the page is loaded again ──

    private val watching = WebListenSync.Timing(pageStallMs = 25_000, pageReloadGapMs = 60_000, watchMs = 5_000)

    private fun TestScope.landedWatching(): Rig = rig(watching).also { r ->
        r.host.now = live()
        r.sync.onLocal(r.host.now!!)
        advanceTimeBy(watching.startCoalesceMs + 1); runCurrent()
        advanceTimeBy(watching.recoverMs); runCurrent()
        assertTrue(r.sync.status().landed)
    }

    /** The page posting its playing state every 5 s, its clock moving (or [frozen]) for [ms]. */
    private fun TestScope.pagePlays(r: Rig, ms: Long, frozen: Boolean = false, buffering: Boolean = false) {
        var left = ms
        while (left > 0) {
            advanceTimeBy(5_000); runCurrent(); left -= 5_000
            // Frozen: the player answers the place it stands at, a seek's target included.
            val at = if (frozen) r.page.state.value!!.positionMs else r.host.local()!!.positionMs
            r.page.state.value = PageState(a, paused = false, buffering = buffering, positionMs = at, timestampMs = testScheduler.currentTime, durationMs = 200_000)
            runCurrent()
        }
    }

    @Test fun `a page that plays on is never reloaded`() = runTest {
        val r = landedWatching()
        pagePlays(r, 120_000)
        assertFalse("reload" in r.page.calls, r.page.calls.toString())
    }

    @Test fun `a frozen clock, a buffering for good or a page that posts nothing gets the page loaded again, once a minute at most`() = runTest {
        for (way in listOf("frozen", "buffering", "silent")) {
            val r = landedWatching()
            when (way) {
                "frozen" -> pagePlays(r, 35_000, frozen = true)
                "buffering" -> pagePlays(r, 35_000, buffering = true)
                else -> { advanceTimeBy(35_000); runCurrent() }
            }
            assertEquals(1, r.page.calls.count { it == "reload" }, "$way: ${r.page.calls}")
            // Still standing: not again before the gap.
            advanceTimeBy(20_000); runCurrent()
            assertEquals(1, r.page.calls.count { it == "reload" }, "$way: ${r.page.calls}")
            r.sync.stop("next case")
        }
    }

    @Test fun `a page paused with the local player is no stall`() = runTest {
        val r = landedWatching()
        val paused = r.host.local()!!.copy(playing = false)
        r.host.now = paused
        r.sync.onLocal(paused)
        advanceTimeBy(120_000); runCurrent()
        assertFalse("reload" in r.page.calls, r.page.calls.toString())
    }
}
