package app.myspoti.spotify

import app.myspoti.spotify.bridge.AdBlock
import app.myspoti.spotify.bridge.Bridge
import app.myspoti.spotify.bridge.BridgeHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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
    private class FakeHost : BridgeHost {
        override val messages = MutableSharedFlow<String>(extraBufferCapacity = 64)
        /** Every call the player made: method and when it was sent. */
        val calls = CopyOnWriteArrayList<Pair<String, Long>>()
        override suspend fun evaluate(script: String) {
            val m = Regex("""Myspoti\.call\((\d+), "([^"]+)", (.*)\)\.catch""", RegexOption.DOT_MATCHES_ALL).find(script) ?: return
            calls += m.groupValues[2] to System.currentTimeMillis()
            messages.emit("""{"type":"result","id":${m.groupValues[1]},"ok":true,"value":null}""")
        }
        suspend fun post(json: String) = messages.emit(json)
    }

    private lateinit var scope: CoroutineScope
    private lateinit var host: FakeHost
    private lateinit var bridge: Bridge
    private lateinit var player: SpotifyPlayer
    /** When the reloaded page said ready (0: not yet). */
    @Volatile private var newPageAt = 0L

    @BeforeEach fun up() {
        AdBlock.ahead.value = null
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        host = FakeHost()
        bridge = Bridge(host, scope)
        player = SpotifyPlayer(bridge, scope)
        // The new page starts loading only [SLOW_START_MS] after the reload (as the host does,
        // the old page is gone at that moment), then says ready.
        player.reloadHost = {
            scope.launch {
                delay(SLOW_START_MS)
                bridge.pageGone()
                delay(300)
                newPageAt = System.currentTimeMillis()
                host.post("""{"type":"ready"}""")
            }
        }
        // A message posted before the bridge listens is lost (a slow CI machine): wait for it.
        waitFor("bridge listening") { host.messages.subscriptionCount.value > 0 }
    }

    @AfterEach fun down() {
        AdBlock.ahead.value = null
        scope.cancel()
    }

    private fun track(name: String) =
        """{"kind":"track","uri":"spotify:track:$name","uid":"u$name","name":"$name","durationMs":200000}"""

    private fun state(item: String, next: String? = null, buffering: Boolean = false) =
        """{"type":"state","state":{"track":$item,"contextUri":"spotify:playlist:C","next":${next ?: "null"},"isPaused":false,
            |"isBuffering":$buffering,"positionMs":0,"timestamp":${System.currentTimeMillis()},"restrictions":{}}}""".trimMargin()

    /** An ad whose audio was refused: current, buffering at 0:00, until the page is reloaded. */
    private val ad = """{"kind":"ad","uri":"spotify:ad:1","name":"ad","durationMs":30000}"""

    private fun waitFor(what: String, ms: Long = 4_000, cond: () -> Boolean) = runBlocking {
        val end = System.currentTimeMillis() + ms
        while (!cond()) {
            check(System.currentTimeMillis() < end) { "timed out: $what" }
            delay(20)
        }
    }

    @Test fun `a reload waits for the new page, not for the old one's last ready`() = runBlocking {
        host.post("""{"type":"ready"}""")
        waitFor("old page ready") { bridge.ready.value }
        val asked = System.currentTimeMillis()
        assertTrue(player.reloadPage(), "the new page came")
        assertTrue(newPageAt > 0 && System.currentTimeMillis() >= newPageAt,
            "returned ${System.currentTimeMillis() - asked} ms after the reload, before the new page was ready")
    }

    @Test fun `the song after a stuck ad is played on the reloaded page`() = runBlocking {
        host.post("""{"type":"ready"}""")
        waitFor("old page ready") { bridge.ready.value }
        host.post(state(track("A"), track("B")))
        host.post(state(ad, buffering = true))
        waitFor("ad flag") { player.adPlaying.value }
        // The watcher reloads ~3 s after the ad started; the play comes once the new page is up.
        waitFor("play after the reload", ms = 20_000) { host.calls.any { it.first == "player.play" } }
        val playAt = host.calls.first { it.first == "player.play" }.second
        assertTrue(newPageAt > 0 && playAt >= newPageAt,
            "play sent ${if (newPageAt == 0L) "before the new page started" else "${newPageAt - playAt} ms before the new page was ready"}")
    }

    private companion object {
        /** Longer than the 1.5 s the reload once waited, as on a slow network. */
        const val SLOW_START_MS = 3_500L
    }
}
