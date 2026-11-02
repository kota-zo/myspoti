package app.myspoti.spotify

import app.myspoti.api.Repeat
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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Repeat one through what takes a song's end away from it: an ad, a song stuck at its end, the
 * ad-avoiding play. Reported (2.1.0 to 2.3.0): the song looped two or three times, then the next
 * one played.
 */
class RepeatOneTest {
    private class FakeHost : BridgeHost {
        override val messages = MutableSharedFlow<String>(extraBufferCapacity = 64)
        /** Every call the player made: method to its arguments (json text). */
        val calls = CopyOnWriteArrayList<Pair<String, String>>()
        override suspend fun evaluate(script: String) {
            val m = Regex("""Myspoti\.call\((\d+), "([^"]+)", (.*)\)\.catch""", RegexOption.DOT_MATCHES_ALL).find(script) ?: return
            calls += m.groupValues[2] to m.groupValues[3]
            // The page's media element: this phone the active device, or another one.
            val value = if (m.groupValues[2] == "media.state") """{"paused":$mediaPaused,"muted":false,"level":1,"own":1,"time":0,"active":$mediaActive}""" else "null"
            messages.emit("""{"type":"result","id":${m.groupValues[1]},"ok":true,"value":$value}""")
        }
        suspend fun post(json: String) = messages.emit(json)
        @Volatile var mediaActive = true
        @Volatile var mediaPaused = false
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

    private fun track(name: String, ms: Long = 200_000, provider: String? = null) =
        """{"kind":"track","uri":"spotify:track:$name","uid":"u$name","name":"$name","durationMs":$ms${provider?.let { ""","provider":"$it"""" }.orEmpty()}}"""

    private fun state(item: String, next: String? = null, repeat: Int = 2, positionMs: Long = 0, buffering: Boolean = false) =
        """{"type":"state","state":{"track":$item,"contextUri":"spotify:playlist:C","next":${next ?: "null"},"isPaused":false,"isBuffering":$buffering,
            |"repeat":$repeat,"positionMs":$positionMs,"timestamp":${System.currentTimeMillis()},"restrictions":{}}}""".trimMargin()

    private val ad = """{"kind":"ad","uri":"spotify:ad:1","name":"ad","durationMs":30000}"""

    private fun waitFor(what: String, ms: Long = 4_000, cond: () -> Boolean) = runBlocking {
        val end = System.currentTimeMillis() + ms
        while (!cond()) {
            check(System.currentTimeMillis() < end) { "timed out: $what" }
            delay(20)
        }
    }

    private fun plays() = host.calls.filter { it.first == "player.play" }.map { it.second }
    private fun called(method: String) = host.calls.count { it.first == method }

    private suspend fun readyWithRepeatOne() {
        host.post("""{"type":"ready"}""")
        waitFor("ready") { bridge.ready.value }
        player.setRepeat(Repeat.TRACK)
    }

    @Test fun `an ad over under repeat one plays the song before it again, not the one the page went to`() = runBlocking {
        readyWithRepeatOne()
        host.post(state(track("A"), track("B")))
        waitFor("A") { player.state.value?.item?.uri == "spotify:track:A" }
        host.post(state(ad))
        waitFor("ad flag") { player.adPlaying.value }
        host.post(state(track("B"), track("C")))
        waitFor("A played again") { plays().isNotEmpty() }
        assertTrue("\"trackUri\":\"spotify:track:A\"" in plays().single(), "the song before the ad: ${plays()}")
    }

    @Test fun `a skip during the ad under repeat one is the user's, the song before it is not played again`() = runBlocking {
        readyWithRepeatOne()
        host.post(state(track("A"), track("B")))
        waitFor("A") { player.state.value?.item?.uri == "spotify:track:A" }
        host.post(state(ad))
        waitFor("ad flag") { player.adPlaying.value }
        player.next()
        host.post(state(track("B"), track("C")))
        delay(600)
        assertTrue(plays().none { "spotify:track:A" in it }, "the skip stands: ${plays()}")
    }

    @Test fun `the ad-avoiding play under repeat one plays the song again, not the next one`() = runBlocking {
        readyWithRepeatOne()
        host.post(state(track("A", ms = 1_500), track("B")))
        waitFor("A") { player.state.value != null }
        AdBlock.ahead.value = AdBlock.AdAhead("uA", "spotify:track:A", "spotify:track:B")
        waitFor("played before the end") { plays().isNotEmpty() }
        val sent = plays().single()
        assertTrue("\"trackUri\":\"spotify:track:A\"" in sent && "spotify:track:B" !in sent, "the song itself: $sent")
        assertTrue(host.calls.none { it.first == "queue.skipTo" }, "no skip to the next song")
    }

    @Test fun `a song stuck past its end under repeat one is played again`() = runBlocking {
        readyWithRepeatOne()
        host.post(state(track("A"), track("B"), positionMs = 202_000))
        waitFor("stall repaired", ms = 10_000) { plays().isNotEmpty() }
        assertTrue("\"trackUri\":\"spotify:track:A\"" in plays().first(), "the song again, not B: ${plays()}")
    }

    @Test fun `a song autoplay brought, stuck past its end with nothing after it, is not played again in the list`() = runBlocking {
        host.post("""{"type":"ready"}""")
        host.post(state(track("A", provider = "autoplay"), repeat = 0, positionMs = 202_000))
        waitFor("stall repaired", ms = 10_000) { called("player.next") > 0 }
        assertTrue(plays().isEmpty(), "the list would start over (#135): ${plays()}")
    }

    @Test fun `another device playing is never given the repeat from here, and its repeat is the choice`() = runBlocking {
        host.mediaActive = false
        host.mediaPaused = true
        host.post("""{"type":"ready"}""")
        host.post(state(track("A"), repeat = 0))
        // The watcher reads the element at its first tick (up to 4 s after it started).
        waitFor("another device seen", ms = 10_000) { player.remoteActive }
        // The repeat of an earlier run, restored as after a play; the other device reports off at the loop.
        player.restoreRepeat(Repeat.TRACK)
        host.post(state(track("A"), repeat = 0, positionMs = 198_000))
        delay(400)
        host.post(state(track("A"), repeat = 0, positionMs = 199_500))
        delay(400)
        assertEquals(0, called("player.setRepeat"), "nothing sent to the other device: ${host.calls.map { it.first }}")
        assertEquals(Repeat.OFF, player.state.value?.repeat)
    }
}
