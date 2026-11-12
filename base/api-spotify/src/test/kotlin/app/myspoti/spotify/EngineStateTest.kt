package app.myspoti.spotify

import app.myspoti.api.Repeat
import app.myspoti.engine.NativeEngine
import app.myspoti.spotify.bridge.AdSchedule
import app.myspoti.spotify.bridge.Bridge
import app.myspoti.spotify.bridge.BridgeHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The account's player while the native engine plays its songs: the state shown is the engine's
 * own playback (live) laid over the page's (late), and with no page at all the engine's alone.
 */
class EngineStateTest {
    private class FakeHost : BridgeHost {
        override val messages = MutableSharedFlow<String>(extraBufferCapacity = 64)
        override suspend fun evaluate(script: String) {
            val m = Regex("""Myspoti\.call\((\d+), "([^"]+)", (.*)\)\.catch""", RegexOption.DOT_MATCHES_ALL).find(script) ?: return
            val value = if (m.groupValues[2] == "media.state") """{"paused":true,"muted":false,"level":1,"own":1,"time":0,"active":false}""" else "null"
            messages.emit("""{"type":"result","id":${m.groupValues[1]},"ok":true,"value":$value}""")
        }
        suspend fun post(json: String) = messages.emit(json)
    }

    private class FakeEngine : AccountAudio {
        override var on = true
        override val deviceId = "engine"
        override val now = MutableStateFlow<NativeEngine.Now?>(null)
        override val holdsPlayback get() = now.value != null
        override var mirrors = false
        val calls = mutableListOf<String>()
        override suspend fun play(contextUri: String, trackUri: String?, index: Int?, positionMs: Long?, order: List<String>?, shuffle: Boolean?) { calls += "play $trackUri" }
        override suspend fun pause() { calls += "pause" }
        override suspend fun resume() { calls += "resume" }
        override suspend fun seek(positionMs: Long) { calls += "seek $positionMs" }
        override suspend fun next() { calls += "next" }
        override suspend fun previous() { calls += "previous" }
    }

    private val time = VirtualTime()
    private lateinit var scope: CoroutineScope
    private lateinit var host: FakeHost
    private lateinit var player: SpotifyPlayer
    private lateinit var engine: FakeEngine

    @BeforeEach fun up() {
        AdSchedule.clear()
        scope = time.scope
        host = FakeHost()
        player = SpotifyPlayer(Bridge(host, scope, time::now), scope)
        engine = FakeEngine()
        player.audio = engine
    }

    @AfterEach fun down() { AdSchedule.clear(); scope.cancel() }

    private fun track(name: String) =
        """{"kind":"track","uri":"spotify:track:$name","uid":"u$name","name":"$name","artists":[{"uri":"spotify:artist:X","name":"Artist $name"}],"durationMs":200000}"""

    private fun pageState(item: String, paused: Boolean, positionMs: Long, next: String = track("N")) =
        """{"type":"state","state":{"track":$item,"contextUri":"spotify:playlist:C","next":$next,"isPaused":$paused,"isBuffering":false,
            |"shuffle":true,"repeat":1,"positionMs":$positionMs,"timestamp":${time.now()},"restrictions":{}}}""".trimMargin()

    private fun now(name: String, playing: Boolean, positionMs: Long) = NativeEngine.Now(
        uri = "spotify:track:$name", name = "$name (engine)", artists = "Artist $name", album = "Album", imageUrl = null,
        durationMs = 201_000, playing = playing, positionMs = positionMs, atMs = time.now(), contextUri = "spotify:playlist:C",
        authors = listOf("spotify:artist:X" to "Artist $name"), albumUri = "spotify:album:Y",
    )

    private suspend fun waitFor(what: String, cond: () -> Boolean) = time.waitFor(what, 4_000, cond)

    @Test fun `the engine's song shows at once with no page up`() = time.test {
        engine.now.value = now("A", playing = true, positionMs = 1_000)
        waitFor("engine state") { player.state.value != null }
        val s = player.state.value!!
        assertEquals("spotify:track:A", s.item?.uri)
        assertEquals("A (engine)", s.item?.name)
        assertEquals(listOf("Artist A"), s.item?.artists?.map { it.name })
        assertEquals("spotify:album:Y", s.item?.album?.uri)
        assertFalse(s.isPaused)
        assertEquals(1_000, s.positionMs)
        assertNull(player.pageState.value)
    }

    @Test fun `the page's late state of the same song keeps its details and takes the engine's place and pause`() = time.test {
        host.post("""{"type":"ready"}""")
        engine.now.value = now("A", playing = false, positionMs = 42_000)
        // The page still says it plays, six seconds back.
        host.post(pageState(track("A"), paused = false, positionMs = 36_000))
        waitFor("page state") { player.pageState.value != null }
        val s = player.state.value!!
        assertEquals("A", s.item?.name)
        assertTrue(s.isPaused)
        assertEquals(42_000, s.positionMs)
        assertEquals(201_000, s.item?.durationMs)
        assertTrue(s.shuffle)
        assertEquals(Repeat.CONTEXT, s.repeat)
        assertEquals("spotify:track:N", s.next?.uri)
    }

    @Test fun `the engine moved on to the page's next song, shown before the page says so`() = time.test {
        host.post("""{"type":"ready"}""")
        engine.now.value = now("A", playing = true, positionMs = 0)
        host.post(pageState(track("A"), paused = false, positionMs = 0))
        waitFor("page state") { player.pageState.value != null }
        engine.now.value = now("N", playing = true, positionMs = 300)
        waitFor("next song shown") { player.state.value?.item?.uri == "spotify:track:N" }
        val s = player.state.value!!
        assertEquals("spotify:track:A", s.previous?.uri)
        assertEquals("uN", s.item?.uid)
        assertTrue(s.shuffle)
        assertEquals("spotify:track:A", player.pageState.value?.item?.uri)
    }

    @Test fun `the engine playing the listen mirror is not shown as the account's`() = time.test {
        host.post("""{"type":"ready"}""")
        host.post(pageState(track("A"), paused = true, positionMs = 5_000))
        waitFor("page state") { player.pageState.value != null }
        engine.mirrors = true
        engine.now.value = now("M", playing = true, positionMs = 0)
        runCurrent()
        assertEquals("spotify:track:A", player.state.value?.item?.uri)
        assertTrue(player.state.value!!.isPaused)
    }

    @Test fun `the engine off or left aside, the page's state as it is`() = time.test {
        host.post("""{"type":"ready"}""")
        engine.on = false
        engine.now.value = now("A", playing = true, positionMs = 9_000)
        host.post(pageState(track("A"), paused = true, positionMs = 3_000))
        waitFor("page state") { player.pageState.value != null }
        assertTrue(player.state.value!!.isPaused)
        assertEquals(3_000, player.state.value!!.positionMs)
    }

    @Test fun `the engine stops, the page's state shows again`() = time.test {
        host.post("""{"type":"ready"}""")
        engine.now.value = now("A", playing = true, positionMs = 9_000)
        host.post(pageState(track("A"), paused = true, positionMs = 3_000))
        waitFor("page state") { player.pageState.value != null }
        assertFalse(player.state.value!!.isPaused)
        engine.now.value = null
        waitFor("page shown") { player.state.value?.isPaused == true }
        assertEquals(3_000, player.state.value!!.positionMs)
    }

    @Test fun `commands go to the engine while it is on`() = time.test {
        engine.now.value = now("A", playing = true, positionMs = 0)
        player.pause()
        player.resume()
        player.seek(10_000)
        player.next()
        assertEquals(listOf("pause", "resume", "seek 10000", "next"), engine.calls)
    }
}
