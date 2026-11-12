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
 * #159: a Samsung soundbar went to 100 % when the music was sent to it. The page player's volume
 * is the Connect volume of the active device: the page's own repairs (a page back after a reload,
 * the silent-song guard) set it only when this page is that device, never on a speaker at 0.
 */
class ConnectVolumeTest {
    private class FakeHost : BridgeHost {
        override val messages = MutableSharedFlow<String>(extraBufferCapacity = 64)
        /** This page is Connect's active device (the page's device list). */
        @Volatile var active = false
        /** The page player's volume: the active device's. */
        @Volatile var playerVolume = 0.0
        val sets = java.util.concurrent.CopyOnWriteArrayList<String>()
        @Volatile var time = 10.0
        override suspend fun evaluate(script: String) {
            val m = Regex("""Myspoti\.call\((\d+), "([^"]+)", (.*)\)\.catch""", RegexOption.DOT_MATCHES_ALL).find(script) ?: return
            val value = when (m.groupValues[2]) {
                // Right after a transfer: the element has not stopped yet, the volume read is the speaker's.
                "media.state" -> {
                    time += 2.0
                    """{"paused":false,"muted":false,"volume":1,"level":1,"own":1,"time":$time,"readyState":4,"ended":false,"playerVolume":$playerVolume,"active":$active}"""
                }
                "volume.get" -> "$playerVolume"
                "volume.set" -> { sets += m.groupValues[3]; "null" }
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

    private suspend fun waitFor(what: String, ms: Long = 4_000, cond: () -> Boolean) = time.waitFor(what, ms, cond)

    private suspend fun playing() {
        host.post("""{"type":"ready"}""")
        waitFor("page ready") { bridge.ready.value }
        val item = """{"kind":"track","uri":"spotify:track:A","uid":"uA","name":"A","durationMs":200000}"""
        host.post("""{"type":"state","state":{"track":$item,"contextUri":"spotify:playlist:C","next":null,"isPaused":false,"isBuffering":false,
            |"positionMs":10000,"timestamp":${time.now()},"restrictions":{}}}""".trimMargin())
        waitFor("state") { player.state.value?.item?.name == "A" }
    }

    @Test fun `a speaker at 0 that plays is never set to full volume by the page`() = time.test {
        playing()
        // Past the page back and two volume checks of the watcher.
        delay(14_000)
        assertEquals(emptyList<String>(), host.sets.toList())
    }

    @Test fun `this page silent while it is the active device gets its volume back`() = time.test {
        host.active = true
        playing()
        waitFor("volume set", ms = 14_000) { host.sets.isNotEmpty() }
    }
}
