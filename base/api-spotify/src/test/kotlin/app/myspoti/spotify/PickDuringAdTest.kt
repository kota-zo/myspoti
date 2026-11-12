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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A pick made while an ad is on or due must reach the page after the ad, in its order (reported
 * on build 2022: the old queue went on and the song had to be picked again).
 */
class PickDuringAdTest {
    private class FakeHost : BridgeHost {
        override val messages = MutableSharedFlow<String>(extraBufferCapacity = 64)
        /** Every call the player made: method to its arguments (json text). */
        val calls = CopyOnWriteArrayList<Pair<String, String>>()
        override suspend fun evaluate(script: String) {
            val m = Regex("""Myspoti\.call\((\d+), "([^"]+)", (.*)\)\.catch""", RegexOption.DOT_MATCHES_ALL).find(script) ?: return
            calls += m.groupValues[2] to m.groupValues[3]
            messages.emit("""{"type":"result","id":${m.groupValues[1]},"ok":true,"value":null}""")
        }
        suspend fun post(json: String) = messages.emit(json)
    }

    private val time = VirtualTime()
    private lateinit var scope: CoroutineScope
    private lateinit var host: FakeHost
    private lateinit var player: SpotifyPlayer

    @BeforeEach fun up() {
        AdSchedule.clear()
        scope = time.scope
        host = FakeHost()
        player = SpotifyPlayer(Bridge(host, scope, time::now), scope)
    }

    @AfterEach fun down() {
        AdSchedule.clear()
        scope.cancel()
    }

    private fun track(name: String, ms: Long = 200_000) =
        """{"kind":"track","uri":"spotify:track:$name","uid":"u$name","name":"$name","durationMs":$ms}"""

    private fun state(item: String, next: String? = null, ctx: String = "spotify:playlist:C") =
        """{"type":"state","state":{"track":$item,"contextUri":"$ctx","next":${next ?: "null"},"isPaused":false,"isBuffering":false,
            |"positionMs":0,"timestamp":${time.now()},"restrictions":{}}}""".trimMargin()

    private val ad = """{"kind":"ad","uri":"spotify:ad:1","name":"ad","durationMs":30000}"""

    private suspend fun waitFor(what: String, ms: Long = 3_000, cond: () -> Boolean) = time.waitFor(what, ms, cond)

    private fun plays() = host.calls.filter { it.first == "player.play" }.map { it.second }

    @Test fun `a pick during an ad is held and played in its order once the ad is over`() = time.test {
        host.post("""{"type":"ready"}""")
        host.post(state(track("A"), track("B")))
        host.post(state(ad))
        waitFor("ad flag") { player.adPlaying.value }
        player.play("spotify:playlist:C", trackUri = "spotify:track:P", order = listOf("spotify:track:P", "spotify:track:Q"))
        delay(300)
        assertTrue(plays().isEmpty(), "a play during an ad is refused by the page: nothing sent, got ${plays()}")
        host.post(state(track("B"), track("C2")))
        waitFor("the held pick played") { plays().isNotEmpty() }
        val sent = plays().single()
        assertTrue("spotify:track:P" in sent && "spotify:track:Q" in sent, "pick and its order sent: $sent")
    }

    @Test fun `a pick on a song the page already plays after the ad is not played twice`() = time.test {
        host.post("""{"type":"ready"}""")
        host.post(state(track("A"), track("B")))
        host.post(state(ad))
        waitFor("ad flag") { player.adPlaying.value }
        player.play("spotify:playlist:C", trackUri = "spotify:track:P")
        host.post(state(track("P"), track("Q")))
        delay(400)
        assertTrue(plays().isEmpty(), "P plays by itself: ${plays()}")
    }

    @Test fun `a pick outside an ad is sent at once`() = time.test {
        host.post("""{"type":"ready"}""")
        host.post(state(track("A"), track("B")))
        waitFor("state") { player.state.value != null }
        player.play("spotify:playlist:C", trackUri = "spotify:track:P")
        waitFor("play sent") { plays().isNotEmpty() }
        assertEquals(1, plays().size)
    }

    @Test fun `the fallback after a refused pick keeps the order it was picked in`() = time.test {
        host.post("""{"type":"ready"}""")
        host.post(state(track("A"), track("B")))
        waitFor("state") { player.state.value != null }
        player.play("spotify:playlist:C", trackUri = "spotify:track:P", order = listOf("spotify:track:P", "spotify:track:Q"))
        host.post("""{"type":"refused"}""")
        waitFor("fallback play") { plays().size == 2 }
        val fallback = plays()[1]
        assertTrue("spotify:track:Q" in fallback && "trackUri" !in fallback, "the list from its start, same order: $fallback")
    }
}
