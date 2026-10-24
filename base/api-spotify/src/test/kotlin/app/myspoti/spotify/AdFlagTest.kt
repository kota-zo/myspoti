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
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The page's state push that ends an ad break never arrives (dropped on the way): the screen must
 * not stay on "Advertisement" through the song that plays.
 */
class AdFlagTest {
    private class FakeHost : BridgeHost {
        override val messages = MutableSharedFlow<String>(extraBufferCapacity = 64)
        /** What the page answers to "player.state": the song it really plays. */
        @Volatile var pageState = "null"
        override suspend fun evaluate(script: String) {
            val m = Regex("""Myspoti\.call\((\d+), "([^"]+)", (.*)\)\.catch""", RegexOption.DOT_MATCHES_ALL).find(script) ?: return
            val value = if (m.groupValues[2] == "player.state") pageState else "null"
            messages.emit("""{"type":"result","id":${m.groupValues[1]},"ok":true,"value":$value}""")
        }
        suspend fun post(json: String) = messages.emit(json)
    }

    private lateinit var scope: CoroutineScope
    private lateinit var host: FakeHost
    private lateinit var bridge: Bridge
    private lateinit var player: SpotifyPlayer

    @BeforeEach fun up() {
        AdBlock.ahead.value = null
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        host = FakeHost()
        bridge = Bridge(host, scope)
        player = SpotifyPlayer(bridge, scope)
        // A message posted before the bridge listens is lost (a slow CI machine): wait for it.
        waitFor("bridge listening") { host.messages.subscriptionCount.value > 0 }
    }

    @AfterEach fun down() {
        AdBlock.ahead.value = null
        scope.cancel()
    }

    private fun track(name: String) =
        """{"kind":"track","uri":"spotify:track:$name","uid":"u$name","name":"$name","durationMs":200000}"""

    private fun stateOf(item: String) =
        """{"track":$item,"contextUri":"spotify:playlist:C","next":null,"isPaused":false,"isBuffering":false,
            |"positionMs":0,"timestamp":${System.currentTimeMillis()},"restrictions":{}}""".trimMargin()

    private val ad = """{"kind":"ad","uri":"spotify:ad:1","name":"ad","durationMs":30000}"""

    private fun waitFor(what: String, ms: Long = 4_000, cond: () -> Boolean) = runBlocking {
        val end = System.currentTimeMillis() + ms
        while (!cond()) {
            check(System.currentTimeMillis() < end) { "timed out: $what" }
            delay(20)
        }
    }

    @Test fun `a read that finds nothing playing does not end the break`() = runBlocking {
        host.post("""{"type":"ready"}""")
        waitFor("page ready") { bridge.ready.value }
        host.post("""{"type":"state","state":${stateOf(track("A"))}}""")
        host.post("""{"type":"state","state":${stateOf(ad)}}""")
        waitFor("ad flag") { player.adPlaying.value }
        // Between the ad and the next song the page can say "nothing plays": the ad stays muted.
        host.pageState = """{"track":null,"contextUri":null,"next":null,"isPaused":true,"isBuffering":false,"positionMs":0,"timestamp":0,"restrictions":{}}"""
        delay(8_000)
        assertEquals(true, player.adPlaying.value, "the break is still on")
    }

    @Test fun `an ad break whose end was never pushed is cleared once the page plays a song`() = runBlocking {
        host.post("""{"type":"ready"}""")
        waitFor("page ready") { bridge.ready.value }
        host.post("""{"type":"state","state":${stateOf(track("A"))}}""")
        host.post("""{"type":"state","state":${stateOf(ad)}}""")
        waitFor("ad flag") { player.adPlaying.value }
        // The break ends and B plays, but that push is lost: only a read of the page says so.
        host.pageState = stateOf(track("B"))
        waitFor("ad flag cleared", ms = 15_000) { !player.adPlaying.value }
        assertEquals("spotify:track:B", player.state.value?.item?.uri)
    }
}
