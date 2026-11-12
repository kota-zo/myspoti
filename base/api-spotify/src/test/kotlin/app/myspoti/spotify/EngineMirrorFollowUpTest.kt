package app.myspoti.spotify

import app.myspoti.spotify.bridge.AdSchedule
import app.myspoti.spotify.bridge.Bridge
import app.myspoti.spotify.bridge.BridgeHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The native engine plays the account's songs and holds the listen mirror (an extension's song
 * played unheard there): the page sees the engine's changes of song as the account's, and the
 * page's own follow-ups must leave them to the mirror. Log seen (future28): the mirror's song and
 * the account's previous one swapped three times in a few seconds, the skip chain paused the
 * engine, and Spotify saw nothing playing.
 */
class EngineMirrorFollowUpTest {
    private class FakeHost : BridgeHost {
        override val messages = MutableSharedFlow<String>(extraBufferCapacity = 64)
        val calls = CopyOnWriteArrayList<String>()
        override suspend fun evaluate(script: String) {
            val m = Regex("""Myspoti\.call\((\d+), "([^"]+)", (.*)\)\.catch""", RegexOption.DOT_MATCHES_ALL).find(script) ?: return
            calls += m.groupValues[2]
            // The page's element stands still: another device (the engine) plays.
            val value = if (m.groupValues[2] == "media.state") """{"paused":true,"muted":false,"level":1,"own":1,"time":0,"active":false}""" else "null"
            messages.emit("""{"type":"result","id":${m.groupValues[1]},"ok":true,"value":$value}""")
        }
        suspend fun post(json: String) = messages.emit(json)
    }

    private class FakeEngine : AccountAudio {
        override val on = true
        override val deviceId = "engine"
        override val holdsPlayback = true
        @Volatile var mirroring = false
        override val mirrors: Boolean get() = mirroring
        val calls = CopyOnWriteArrayList<String>()
        override suspend fun play(contextUri: String, trackUri: String?, index: Int?, positionMs: Long?, order: List<String>?, shuffle: Boolean?) { calls += "play $contextUri $trackUri" }
        override suspend fun pause() { calls += "pause" }
        override suspend fun resume() { calls += "resume" }
        override suspend fun seek(positionMs: Long) { calls += "seek $positionMs" }
        override suspend fun next() { calls += "next" }
        override suspend fun previous() { calls += "previous" }
    }

    private val time = VirtualTime()
    private lateinit var scope: CoroutineScope
    private lateinit var host: FakeHost
    private lateinit var engine: FakeEngine
    private lateinit var player: SpotifyPlayer

    @BeforeEach fun up() {
        AdSchedule.clear()
        scope = time.scope
        host = FakeHost()
        player = SpotifyPlayer(Bridge(host, scope, time::now), scope)
        engine = FakeEngine()
        player.audio = engine
    }

    @AfterEach fun down() {
        AdSchedule.clear()
        scope.cancel()
    }

    private fun track(name: String) =
        """{"kind":"track","uri":"spotify:track:$name","uid":"u$name","name":"$name","durationMs":200000}"""

    private fun state(name: String, context: String) =
        """{"type":"state","state":{"track":${track(name)},"contextUri":"$context","isPaused":false,"isBuffering":false,
            |"repeat":0,"positionMs":0,"timestamp":${time.now()},"restrictions":{}}}""".trimMargin()

    /** The engine's song and the mirror's swapping, a second apart each, as in the log. */
    private suspend fun swaps() {
        host.post("""{"type":"ready"}""")
        host.post(state("Old", "spotify:playlist:P"))
        for (i in 0 until 4) {
            delay(1_000)
            host.post(if (i % 2 == 0) state("Mirror", "spotify:track:Mirror") else state("Old", "spotify:playlist:P"))
        }
        delay(1_000)
    }

    @Test fun `the skip chain leaves the engine alone while the mirror holds it`() = time.test {
        engine.mirroring = true
        swaps()
        assertEquals(emptyList<String>(), engine.calls.toList(), "nothing sent to the engine for the mirror's songs")
        assertEquals(0, host.calls.count { it == "player.pause" })
    }

    @Test fun `the skip chain still pauses the engine's own songs skipping by themselves`() = time.test {
        swaps()
        assertEquals(1, engine.calls.count { it == "pause" }, "the account's songs skipping by themselves are paused once")
    }
}
