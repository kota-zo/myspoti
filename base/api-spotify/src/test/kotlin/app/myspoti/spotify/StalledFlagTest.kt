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

/**
 * Reported: the time counted on while no song was heard. The page's state says it plays, its
 * media element stands still waiting for data: the player says so ([SpotifyPlayer.stalled]) so the
 * screens stop their clock.
 */
class StalledFlagTest {
    private class FakeHost : BridgeHost {
        override val messages = MutableSharedFlow<String>(extraBufferCapacity = 64)
        /** The media element's clock (s); it moves on each read while [moving]. */
        @Volatile var time = 10.0
        @Volatile var moving = false
        @Volatile var readyState = 2
        override suspend fun evaluate(script: String) {
            val m = Regex("""Myspoti\.call\((\d+), "([^"]+)", (.*)\)\.catch""", RegexOption.DOT_MATCHES_ALL).find(script) ?: return
            val value = when (m.groupValues[2]) {
                "media.state" -> {
                    if (moving) time += 2.0
                    """{"paused":false,"muted":false,"volume":1,"level":1,"own":1,"time":$time,"readyState":$readyState,"ended":false,"playerVolume":1,"active":true}"""
                }
                else -> "null"
            }
            messages.emit("""{"type":"result","id":${m.groupValues[1]},"ok":true,"value":$value}""")
        }
        suspend fun post(json: String) = messages.emit(json)
    }

    private val time = VirtualTime()
    private lateinit var scope: CoroutineScope
    private lateinit var host: FakeHost
    private lateinit var bridge: Bridge
    private lateinit var player: SpotifyPlayer

    @BeforeEach fun up() {
        AdSchedule.clear()
        scope = time.scope
        host = FakeHost()
        bridge = Bridge(host, scope, time::now)
        player = SpotifyPlayer(bridge, scope)
    }

    @AfterEach fun down() {
        AdSchedule.clear()
        scope.cancel()
    }

    private fun track(name: String) =
        """{"kind":"track","uri":"spotify:track:$name","uid":"u$name","name":"$name","durationMs":200000}"""

    private fun stateOf(item: String, paused: Boolean = false) =
        """{"track":$item,"contextUri":"spotify:playlist:C","next":null,"isPaused":$paused,"isBuffering":false,
            |"positionMs":10000,"timestamp":${time.now()},"restrictions":{}}""".trimMargin()

    private suspend fun waitFor(what: String, ms: Long = 4_000, cond: () -> Boolean) = time.waitFor(what, ms, cond)

    private suspend fun playing(name: String) {
        host.post("""{"type":"ready"}""")
        waitFor("page ready") { bridge.ready.value }
        host.post("""{"type":"state","state":${stateOf(track(name))}}""")
        waitFor("state") { player.state.value?.item?.name == name }
    }

    @Test fun `a song whose sound stands still waiting for data is stalled`() = time.test {
        playing("A")
        waitFor("stalled", ms = 9_000) { player.stalled.value }
        // The sound comes back: the clock goes on.
        host.moving = true
        host.readyState = 4
        waitFor("stall over", ms = 6_000) { !player.stalled.value }
    }

    @Test fun `a song that plays is never stalled`() = time.test {
        host.moving = true
        host.readyState = 4
        playing("A")
        delay(7_000)
        assertEquals(false, player.stalled.value)
    }

    @Test fun `another song clears the stall at once`() = time.test {
        playing("A")
        waitFor("stalled", ms = 9_000) { player.stalled.value }
        host.post("""{"type":"state","state":${stateOf(track("B"))}}""")
        waitFor("stall cleared with the new song", ms = 500) { !player.stalled.value }
    }

    @Test fun `a paused song is not stalled`() = time.test {
        playing("A")
        waitFor("stalled", ms = 9_000) { player.stalled.value }
        host.post("""{"type":"state","state":${stateOf(track("A"), paused = true)}}""")
        waitFor("stall over on pause", ms = 6_000) { !player.stalled.value }
    }
}
