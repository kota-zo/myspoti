package app.myspoti.spotify.sync

import app.myspoti.api.Device
import app.myspoti.api.ItemKind
import app.myspoti.api.NowPlaying
import app.myspoti.api.PlayerRestrictions
import app.myspoti.api.PlayerState
import app.myspoti.api.Queue
import app.myspoti.api.Repeat
import app.myspoti.api.Uri
import app.myspoti.api.sync.ListenSyncHost
import app.myspoti.api.sync.LocalNow
import app.myspoti.api.sync.QueueTake
import app.myspoti.api.sync.SyncTrack
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The Spotify listen sync driven end to end against a page and a local player of the test's own. */
@OptIn(ExperimentalCoroutinesApi::class)
class SpotifyListenSyncTest {
    private val idA = "4uLU6hMCjMI75M1A2tKUQC"
    private val idB = "7ouMYWpwJ422jRcDASZB7P"
    private val a = "spotify:track:$idA"
    private val b = "spotify:track:$idB"
    private val list = "spotify:playlist:37i9dQZF1DXcBWIGoYBM5M"

    private class Page : SyncPage {
        val calls = ArrayList<String>()
        override val ready = MutableStateFlow(true)
        override val adPlaying = MutableStateFlow(false)
        var adDue: String? = null
        override fun adDueAfter() = adDue
        override val joined = MutableStateFlow(true)
        override val joinReported = true
        override val playsElsewhere = MutableStateFlow(false)
        override val state = MutableStateFlow<PlayerState?>(null)
        override val queue = MutableStateFlow<Queue?>(null)
        override var localMute = false
        override val devices = MutableStateFlow<List<Device>>(emptyList())
        override var ownStates = false
        override fun licenceLimited() = false
        override fun licenceRefusedWithin(ms: Long) = false
        override suspend fun refreshDevices() { calls += "devices" }
        override suspend fun pullHere() { calls += "pull" }
        override suspend fun setMirroring(on: Boolean) { calls += "mirroring $on" }
        override suspend fun setLocalMute(on: Boolean) { localMute = on; calls += "mute $on" }
        override suspend fun play(contextUri: Uri, trackUri: Uri?, positionMs: Long) { calls += "play $contextUri $trackUri $positionMs" }
        override suspend fun pause() { calls += "pause" }
        override suspend fun resume() { calls += "resume" }
        override suspend fun seek(positionMs: Long) { calls += "seek $positionMs" }
        override suspend fun skipTo(item: NowPlaying) { calls += "skipTo ${item.uri}" }
        override suspend fun addToQueue(uris: List<Uri>) { calls += "add $uris" }
        override suspend fun insertInQueue(uris: List<Uri>, before: NowPlaying) { calls += "insert $uris" }
        override suspend fun removeFromQueue(items: List<NowPlaying>) { calls += "remove ${items.map { it.uri }}" }
    }

    /** A local player whose position runs on [clock] from where [now] was set. */
    private class Host(private val clock: () -> Long) : ListenSyncHost {
        var now: LocalNow? = null
            set(value) { field = value; nowAt = clock() }
        private var nowAt = 0L
        val calls = ArrayList<String>()
        val running = ArrayList<Boolean>()
        override fun local() = now?.let { if (it.playing) it.copy(positionMs = it.positionMs + clock() - nowAt) else it }
        override fun queued() = emptyList<SyncTrack>()
        override val localActive get() = now != null
        override val routing = false
        override var enabled = true
        override val dataSaver = false
        override val engineIntended = false
        override fun pause() { calls += "pause" }
        override fun resume() { calls += "resume" }
        override fun seekTo(ms: Long) { calls += "seek $ms" }
        override fun next() { calls += "next" }
        override fun previous() { calls += "previous" }
        override fun stop() { calls += "stop" }
        override fun playRemotePick(item: NowPlaying, contextUri: Uri?, positionMs: Long) { calls += "pick ${item.uri}" }
        override fun playHereAgain() { calls += "here again" }
        override fun syncRunning(on: Boolean) { running += on }
        override fun log(line: String) {}
    }

    private fun track(id: String) = SyncTrack(ids = mapOf("spotify" to id), title = "t-$id")
    private fun item(uri: String) = NowPlaying(ItemKind.TRACK, uri, null, "", emptyList(), null, null, 200_000)
    private fun state(uri: String, paused: Boolean, at: Long, time: Long) = PlayerState(
        item = item(uri), contextUri = list, isPaused = paused, isBuffering = false, positionMs = at,
        timestampMs = time, speed = 1.0, shuffle = false, repeat = Repeat.OFF, restrictions = PlayerRestrictions(),
    )

    private fun live(id: String = idA, at: Long = 10_000, live: Boolean = true, rate: Float = 1f) = LocalNow(
        track = track(id), live = live, playing = true, positionMs = at, durationMs = 200_000, knownLengthMs = 200_000,
        rate = rate, next = track(idB), contextUri = list,
    )

    private class Rig(val page: Page, val host: Host, val sync: SpotifyListenSync)

    private fun TestScope.rig(own: Boolean = false): Rig {
        val page = Page().apply { ownStates = own }
        val clock = { testScheduler.currentTime }
        val host = Host(clock)
        val sync = SpotifyListenSync(page, host, backgroundScope, clock = clock, wallClock = clock)
        sync.attach()
        runCurrent()
        return Rig(page, host, sync)
    }

    /** The local player starts a live stream of [a] and the page took it. */
    private fun TestScope.started(): Rig = rig().also { r ->
        r.host.now = live()
        r.sync.onLocal(r.host.now!!)
        advanceTimeBy(SpotifyListenSync.START_COALESCE_MS + 1); runCurrent()
        r.page.state.value = state(a, paused = false, at = r.host.local()!!.positionMs, time = testScheduler.currentTime)
        r.sync.onProviderState(r.page.state.value!!)
    }

    @Test fun `a live stream with a Spotify id plays on the page unheard, at its lowest quality, in its list`() = runTest {
        val r = rig()
        r.host.now = live()
        r.sync.onLocal(r.host.now!!)
        assertTrue(r.sync.running)
        assertEquals(listOf(true), r.host.running)
        // One play for a burst of skips: nothing is sent before the coalescing delay.
        runCurrent(); assertEquals(emptyList<String>(), r.page.calls)
        advanceTimeBy(SpotifyListenSync.START_COALESCE_MS + 1); runCurrent()
        // From where the local player is when the play is sent.
        assertEquals(listOf("mirroring true", "mute true", "devices", "play $list $a 10300"), r.page.calls)
    }

    @Test fun `a page with states of its own (the native engine) is followed without the app handing them over`() = runTest {
        val r = rig(own = true)
        r.host.now = live()
        r.sync.onLocal(r.host.now!!)
        advanceTimeBy(SpotifyListenSync.START_COALESCE_MS + 1); runCurrent()
        r.page.state.value = state(a, paused = false, at = r.host.local()!!.positionMs, time = testScheduler.currentTime)
        runCurrent()
        assertTrue(r.sync.status().landed)
        advanceTimeBy(SpotifyListenSync.PLAY_ECHO_MS + 1_000); runCurrent()
        // A pause from another app reaches the engine; its state alone pauses the local player.
        r.page.state.value = state(a, paused = true, at = r.host.local()!!.positionMs, time = testScheduler.currentTime)
        runCurrent()
        assertEquals(listOf("pause"), r.host.calls)
    }

    @Test fun `playback moved to the engine's device with no local player goes back through the extensions`() = runTest {
        val r = rig(own = true)
        r.page.state.value = state(a, paused = false, at = 5_000, time = testScheduler.currentTime)
        runCurrent()
        assertEquals(listOf("here again"), r.host.calls)
        // The same song going on is no new move.
        r.page.state.value = state(a, paused = false, at = 9_000, time = testScheduler.currentTime + 4_000)
        runCurrent()
        assertEquals(listOf("here again"), r.host.calls)
    }

    @Test fun `a download, a speed off 1x or the setting off never start the page`() = runTest {
        val r = rig()
        r.sync.onLocal(live(live = false))
        r.sync.onLocal(live(rate = 1.5f))
        r.host.enabled = false
        r.sync.onLocal(live())
        advanceTimeBy(1_000); runCurrent()
        assertFalse(r.sync.running)
        assertEquals(emptyList<String>(), r.page.calls)
    }

    @Test fun `the phone's previous page device still listed as playing at the start does not stop the local player`() = runTest {
        val r = started()
        // A second after the start, Spotify still shows the previous page's device as the one playing.
        r.page.playsElsewhere.value = true
        runCurrent()
        assertEquals(emptyList<String>(), r.host.calls)
        // It is taken off: nothing was stopped.
        r.page.playsElsewhere.value = false
        advanceTimeBy(SpotifyListenSync.RECOVER_MS + 1_000); runCurrent()
        assertEquals(emptyList<String>(), r.host.calls)
        assertTrue(r.sync.running)
    }

    @Test fun `another device still playing once the start has settled stops the local player`() = runTest {
        val r = started()
        r.page.playsElsewhere.value = true
        runCurrent()
        advanceTimeBy(SpotifyListenSync.RECOVER_MS + 1_000); runCurrent()
        assertEquals(listOf("stop"), r.host.calls)
    }

    private val me = Device("page", "Phone of Sam", "computer", active = false, volume = null, local = true)
    private val phoneApp = Device("app", "Phone of Sam", "smartphone", active = true, volume = null, local = false)

    /** This phone's Spotify app takes playback of [uri], playing or not, once the start has settled. */
    private fun TestScope.appTakes(r: Rig, uri: String, playing: Boolean = true) {
        advanceTimeBy(SpotifyListenSync.RECOVER_MS + 1_000); runCurrent()
        r.page.playsElsewhere.value = false; runCurrent()
        r.page.devices.value = listOf(me, phoneApp)
        r.page.state.value = state(uri, paused = !playing, at = r.host.local()!!.positionMs, time = testScheduler.currentTime)
        r.page.playsElsewhere.value = true
        runCurrent()
        advanceTimeBy(SpotifyListenSync.START_COALESCE_MS + 1); runCurrent()
    }

    @Test fun `this phone's Spotify app taking the mirrored song gives it back to the page, the local player plays on`() = runTest {
        val r = started()
        r.page.calls.clear()
        appTakes(r, a)
        assertEquals(emptyList<String>(), r.host.calls)
        assertTrue(r.sync.running)
        // Pulled here as it plays (the song goes on there, put in step with the local player): no new play.
        assertTrue("pull" in r.page.calls, r.page.calls.toString())
        assertFalse(r.page.calls.any { it.startsWith("play ") }, r.page.calls.toString())
    }

    @Test fun `a play pressed in this phone's Spotify app while the local player is paused resumes the local player`() = runTest {
        val r = started()
        r.host.now = r.host.local()!!.copy(playing = false)
        r.sync.onLocal(r.host.now!!)
        r.page.calls.clear()
        appTakes(r, a)
        assertEquals(listOf("resume"), r.host.calls)
        assertTrue("pull" in r.page.calls, r.page.calls.toString())
    }

    @Test fun `another song picked in this phone's Spotify app keeps playback there`() = runTest {
        val r = started()
        appTakes(r, b)
        assertEquals(listOf("stop"), r.host.calls)
    }

    @Test fun `this phone's Spotify app taking playback again and again is left to it`() = runTest {
        val r = started()
        repeat(SpotifyListenSync.MAX_TAKE_BACKS) { appTakes(r, a) }
        assertEquals(emptyList<String>(), r.host.calls)
        appTakes(r, a)
        assertEquals(listOf("stop"), r.host.calls)
    }

    @Test fun `this phone's own app is a phone under this device's name, never the previous page`() {
        val stalePage = Device("old", "Phone of Sam", "computer", active = true, volume = null, local = false)
        val otherPhone = Device("p2", "Alex's phone", "smartphone", active = true, volume = null, local = false)
        assertEquals(phoneApp, SpotifySyncRules.ownPhoneApp(listOf(me, phoneApp)))
        assertEquals(null, SpotifySyncRules.ownPhoneApp(listOf(me, stalePage)))
        assertEquals(null, SpotifySyncRules.ownPhoneApp(listOf(me, otherPhone)))
        assertEquals(null, SpotifySyncRules.ownPhoneApp(listOf(phoneApp)))
        // The native engine registers as a web player: the phone's name comes from the host.
        val engine = Device("engine", "Web Player (Chrome)", "COMPUTER", active = false, volume = null, local = true)
        val app = phoneApp.copy(type = "SMARTPHONE")
        assertEquals(null, SpotifySyncRules.ownPhoneApp(listOf(engine, app)))
        assertEquals(app, SpotifySyncRules.ownPhoneApp(listOf(engine, app), phoneName = "Phone of Sam"))
    }

    @Test fun `a pause on another device pauses the local player once the echo is over`() = runTest {
        val r = started()
        assertTrue(r.sync.status().landed)
        advanceTimeBy(SpotifyListenSync.PLAY_ECHO_MS + 1_000); runCurrent()
        r.page.state.value = state(a, paused = true, at = r.host.local()!!.positionMs, time = testScheduler.currentTime)
        r.sync.onProviderState(r.page.state.value!!)
        assertEquals(listOf("pause"), r.host.calls)
    }

    @Test fun `the page going to the local queue's next song on another device is a next here`() = runTest {
        val r = started()
        advanceTimeBy(SpotifyListenSync.PLAY_ECHO_MS + 1_000); runCurrent()
        r.page.state.value = state(b, paused = false, at = 0, time = testScheduler.currentTime)
        r.sync.onProviderState(r.page.state.value!!)
        runCurrent()
        assertEquals(listOf("next"), r.host.calls)
    }

    @Test fun `a move made by a device that took playback is not followed, the local player stops`() = runTest {
        val r = started()
        advanceTimeBy(SpotifyListenSync.PLAY_ECHO_MS + 1_000); runCurrent()
        r.page.devices.value = listOf(Device("x", "Laptop", "computer", active = true, volume = null, local = false))
        r.page.state.value = state(b, paused = false, at = 0, time = testScheduler.currentTime)
        r.sync.onProviderState(r.page.state.value!!)
        runCurrent()
        assertEquals(listOf("stop"), r.host.calls)
        assertTrue(r.sync.waitsBack)
    }

    @Test fun `the end pauses the song it played, then the page is heard again at the user's quality`() = runTest {
        val r = started()
        r.page.calls.clear()
        assertTrue(r.sync.stop("test"))
        assertFalse(r.sync.stop("again"))
        runCurrent()
        assertEquals(listOf("pause", "mute false", "mirroring false"), r.page.calls)
        assertEquals(listOf(true, false), r.host.running)
    }

    @Test fun `the setting turned off ends the sync`() = runTest {
        val r = started()
        r.host.enabled = false
        r.sync.refresh()
        assertFalse(r.sync.running)
    }

    @Test fun `a local player starting on a list takes Spotify's queue first`() = runTest {
        val r = rig()
        val c = "spotify:track:0VjIjW4GlUZAMYd2vXMi3b"
        r.page.queue.value = Queue(current = null, queued = listOf(item(a), item(c)), nextUp = emptyList())
        val takes = r.sync.adoptRemoteQueue(listOf(track(idA), track(idB)))
        assertEquals(listOf(QueueTake.Local(0), QueueTake.Remote(item(c))), takes)
        r.host.enabled = false
        assertEquals(emptyList<QueueTake>(), r.sync.adoptRemoteQueue(listOf(track(idA))))
    }

    // ── Ads on the page: they play to their end, the mirror waits and comes back where the local player is ──

    @Test fun `an ad holds the mirror, and its end puts the local player's song back at once, where it is`() = runTest {
        val r = started()
        advanceTimeBy(SpotifyListenSync.PLAY_ECHO_MS + 1_000); runCurrent()
        r.page.adPlaying.value = true; runCurrent()
        r.page.calls.clear()
        // The local player goes on to its next song during the break: nothing reaches the page.
        r.host.now = live(idB, at = 0)
        r.sync.onLocal(r.host.now!!)
        advanceTimeBy(20_000); runCurrent()
        assertEquals(emptyList<String>(), r.page.calls)
        // The break is over: the song goes at once (no coalescing wait), from where the local player is.
        r.page.adPlaying.value = false; runCurrent()
        assertEquals(listOf("mirroring true", "play $list $b 20000"), r.page.calls)
    }

    @Test fun `a local player paused when the ad ends pauses the page, its resume plays the song`() = runTest {
        val r = started()
        advanceTimeBy(SpotifyListenSync.PLAY_ECHO_MS + 1_000); runCurrent()
        r.page.adPlaying.value = true; runCurrent()
        val paused = r.host.local()!!.copy(playing = false)
        r.host.now = paused
        r.sync.onLocal(paused)
        advanceTimeBy(15_000); runCurrent()
        r.page.calls.clear()
        r.page.adPlaying.value = false; runCurrent()
        assertEquals(listOf("pause"), r.page.calls)
        r.host.now = paused.copy(playing = true)
        r.sync.onLocal(r.host.now!!); runCurrent()
        assertTrue(r.page.calls.last().startsWith("play $list $a "), r.page.calls.toString())
    }

    @Test fun `a play that meets the ad Spotify had due is not taken for one the page refused`() = runTest {
        val r = started()
        advanceTimeBy(SpotifyListenSync.PLAY_ECHO_MS + 1_000); runCurrent()
        r.page.adDue = a
        r.host.now = live(idB, at = 0)
        r.sync.onLocal(r.host.now!!)
        advanceTimeBy(SpotifyListenSync.START_COALESCE_MS + 1); runCurrent()
        assertEquals(1, r.page.calls.count { it.startsWith("play $list $b") })
        // The page still shows the song before the ad past the play's echo: no second play.
        advanceTimeBy(SpotifyListenSync.PLAY_ECHO_MS + 500); runCurrent()
        r.page.state.value = state(a, paused = false, at = 199_000, time = testScheduler.currentTime)
        r.sync.onProviderState(r.page.state.value!!); runCurrent()
        assertEquals(1, r.page.calls.count { it.startsWith("play ") && b in it }, r.page.calls.toString())
        assertEquals(emptyList<String>(), r.host.calls)
    }

    @Test fun `without an ad due the same refusal is played again`() = runTest {
        val r = started()
        advanceTimeBy(SpotifyListenSync.PLAY_ECHO_MS + 1_000); runCurrent()
        r.host.now = live(idB, at = 0)
        r.sync.onLocal(r.host.now!!)
        advanceTimeBy(SpotifyListenSync.START_COALESCE_MS + 1); runCurrent()
        advanceTimeBy(SpotifyListenSync.PLAY_ECHO_MS + 500); runCurrent()
        r.page.state.value = state(a, paused = false, at = 199_000, time = testScheduler.currentTime)
        r.sync.onProviderState(r.page.state.value!!); runCurrent()
        assertEquals(2, r.page.calls.count { it.startsWith("play ") && b in it }, r.page.calls.toString())
    }
}
