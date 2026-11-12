package app.myspoti.spotify

import app.myspoti.spotify.bridge.AdSchedule
import app.myspoti.spotify.bridge.Bridge
import app.myspoti.spotify.bridge.BridgeHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A reload on a slow network: the old page keeps answering "ready" for seconds after the reload
 * was asked for, until the new page starts loading. What follows the reload must go to the new page.
 */
class ReloadTest {
    private class FakeHost(private val time: VirtualTime) : BridgeHost {
        override val messages = MutableSharedFlow<String>(extraBufferCapacity = 64)
        /** Every call the player made: method and when it was sent. */
        val calls = CopyOnWriteArrayList<Pair<String, Long>>()
        override suspend fun evaluate(script: String) {
            val m = Regex("""Myspoti\.call\((\d+), "([^"]+)", (.*)\)\.catch""", RegexOption.DOT_MATCHES_ALL).find(script) ?: return
            calls += m.groupValues[2] to time.now()
            messages.emit("""{"type":"result","id":${m.groupValues[1]},"ok":true,"value":null}""")
        }
        suspend fun post(json: String) = messages.emit(json)
    }

    private val time = VirtualTime()
    private lateinit var scope: CoroutineScope
    private lateinit var host: FakeHost
    private lateinit var bridge: Bridge
    private lateinit var player: SpotifyPlayer
    /** When the reloaded page said ready (0: not yet). */
    @Volatile private var newPageAt = 0L

    @BeforeEach fun up() {
        AdSchedule.clear()
        scope = time.scope
        host = FakeHost(time)
        bridge = Bridge(host, scope, time::now)
        player = SpotifyPlayer(bridge, scope)
        // The new page starts loading only [SLOW_START_MS] after the reload (as the host does,
        // the old page is gone at that moment), then says ready.
        player.reloadHost = {
            scope.launch {
                delay(SLOW_START_MS)
                bridge.pageGone()
                delay(300)
                newPageAt = time.now()
                host.post("""{"type":"ready"}""")
            }
        }
    }

    @AfterEach fun down() {
        AdSchedule.clear()
        scope.cancel()
    }

    private suspend fun waitFor(what: String, ms: Long = 4_000, cond: () -> Boolean) = time.waitFor(what, ms, cond)

    @Test fun `a reload waits for the new page, not for the old one's last ready`() = time.test {
        host.post("""{"type":"ready"}""")
        waitFor("old page ready") { bridge.ready.value }
        val asked = time.now()
        assertTrue(player.reloadPage(), "the new page came")
        assertTrue(newPageAt > 0 && time.now() >= newPageAt,
            "returned ${time.now() - asked} ms after the reload, before the new page was ready")
    }

    private companion object {
        /** Longer than the 1.5 s the reload once waited, as on a slow network. */
        const val SLOW_START_MS = 3_500L
    }
}
