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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * What the player does today around ads, picks and reloads, pinned down before any refactoring:
 * the last of two picks made during an ad is the one played after it, a reload ends on the new
 * page's "ready" only, and a pause made during an ad is not undone when the ad is over.
 */
class SpotifyPlayerCharacterizationTest {
    private class FakeHost : BridgeHost {
        override val messages = MutableSharedFlow<String>(extraBufferCapacity = 64)
        /** Every call the player made: method, its arguments (json text) and when it was sent. */
        val calls = CopyOnWriteArrayList<Triple<String, String, Long>>()
        override suspend fun evaluate(script: String) {
            val m = Regex("""Myspoti\.call\((\d+), "([^"]+)", (.*)\)\.catch""", RegexOption.DOT_MATCHES_ALL).find(script) ?: return
            calls += Triple(m.groupValues[2], m.groupValues[3], System.currentTimeMillis())
            messages.emit("""{"type":"result","id":${m.groupValues[1]},"ok":true,"value":null}""")
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

    private fun state(item: String, next: String? = null, paused: Boolean = false) =
        """{"type":"state","state":{"track":$item,"contextUri":"spotify:playlist:C","next":${next ?: "null"},"isPaused":$paused,
            |"isBuffering":false,"positionMs":0,"timestamp":${System.currentTimeMillis()},"restrictions":{}}}""".trimMargin()

    private val ad = """{"kind":"ad","uri":"spotify:ad:1","name":"ad","durationMs":30000}"""

    private fun waitFor(what: String, ms: Long = 10_000, cond: () -> Boolean) = runBlocking {
        val end = System.currentTimeMillis() + ms
        while (!cond()) {
            check(System.currentTimeMillis() < end) { "timed out: $what" }
            delay(20)
        }
    }

    private fun sent(method: String) = host.calls.filter { it.first == method }

    private suspend fun adBreak() {
        host.post("""{"type":"ready"}""")
        waitFor("page ready") { bridge.ready.value }
        host.post(state(track("A"), track("B")))
        waitFor("song state") { player.state.value?.item?.uri == "spotify:track:A" }
        host.post(state(ad))
        waitFor("ad flag") { player.adPlaying.value }
    }

    @Test fun `of two picks made during the same ad only the last is played after it, once`() = runBlocking {
        adBreak()
        player.play("spotify:playlist:C", trackUri = "spotify:track:P1")
        player.play("spotify:playlist:C", trackUri = "spotify:track:P2", order = listOf("spotify:track:P2", "spotify:track:Q"))
        assertTrue(sent("player.play").isEmpty(), "nothing sent during the ad: ${sent("player.play")}")
        // The break ends on the old queue's next song: the held pick is asked for again. (Were the
        // break to outlast the watcher's skip delay on a loaded machine, the recovery plays the held
        // pick instead: one play of the last pick either way.)
        host.post(state(track("B"), track("C2")))
        waitFor("the held pick played") { sent("player.play").isNotEmpty() }
        host.post(state(track("P2"), track("Q")))
        delay(2_000)
        val plays = sent("player.play").map { it.second }
        assertEquals(1, plays.size, "one play after the ad: $plays")
        assertTrue("spotify:track:P2" in plays.single() && "spotify:track:Q" in plays.single(), "the last pick, in its order: ${plays.single()}")
        assertTrue(plays.none { "spotify:track:P1" in it }, "the first pick is never played: $plays")
    }

    @Test fun `a reload does not end on the old page's ready but on the new page's after it went away`() = runBlocking {
        var goneAt = 0L
        var newReadyAt = 0L
        player.reloadHost = {
            scope.launch {
                // The old page goes on saying "ready" for a while after the reload was asked for.
                delay(1_700)
                host.post("""{"type":"ready"}""")
                delay(1_500)
                goneAt = System.currentTimeMillis()
                bridge.pageGone()
                delay(300)
                newReadyAt = System.currentTimeMillis()
                host.post("""{"type":"ready"}""")
            }
        }
        host.post("""{"type":"ready"}""")
        waitFor("old page ready") { bridge.ready.value }
        assertTrue(player.reloadPage(), "the new page came")
        val back = System.currentTimeMillis()
        assertTrue(goneAt > 0, "returned before the old page went away")
        assertTrue(newReadyAt in goneAt..back, "returned ${back - goneAt} ms after the old page went away, before the new page's ready")
        assertTrue(bridge.ready.value)
    }

    @Test fun `a pause during an ad stays paused once the ad is over`() = runBlocking {
        adBreak()
        player.pause()
        waitFor("pause sent") { sent("player.pause").isNotEmpty() }
        val pausedAt = System.currentTimeMillis()
        // The break ends before the watcher gets past it, on the next song left paused.
        host.post(state(track("B"), paused = true))
        waitFor("break over") { !player.adPlaying.value }
        // Well past the settle delay after which a song left paused by the break is resumed.
        delay(SpotifyPlayer.AD_END_SETTLE_MS + 2_500)
        assertTrue(host.calls.none { it.first == "player.resume" && it.third >= pausedAt }, "resumed after the user's pause: ${host.calls.map { it.first }}")
        assertTrue(host.calls.none { it.first == "player.play" }, "something played after the user's pause: ${sent("player.play")}")
        assertEquals(true, player.state.value?.isPaused)
    }

    /** The other side of the test above: without the user's pause, a song the break left paused is resumed. */
    @Test fun `a song left paused by an ad nobody paused is resumed after the settle delay`() = runBlocking {
        adBreak()
        host.post(state(track("B"), paused = true))
        waitFor("break over") { !player.adPlaying.value }
        waitFor("resume sent", ms = SpotifyPlayer.AD_END_SETTLE_MS + 8_000) { sent("player.resume").isNotEmpty() }
    }

    /** A page reloaded by the host: the old one goes, the new one says ready and reports nothing playing. */
    @Volatile private var reloads = 0

    private fun reloadingHost() {
        player.reloadHost = {
            reloads++
            scope.launch {
                delay(500)
                bridge.pageGone()
                delay(300)
                host.post("""{"type":"ready"}""")
                delay(300)
                host.post("""{"type":"state","state":{"track":null,"contextUri":null,"next":null,"isPaused":true,"isBuffering":false,
                    |"positionMs":0,"timestamp":${System.currentTimeMillis()},"restrictions":{}}}""".trimMargin())
            }
        }
    }

    private val pausedAd get() =
        """{"type":"state","state":{"track":$ad,"contextUri":"spotify:playlist:C","next":null,"isPaused":true,"isBuffering":false,
            |"positionMs":0,"timestamp":${System.currentTimeMillis()},"restrictions":{}}}""".trimMargin()

    @Test fun `an ad paused by the user that the watcher gets past starts no music, the user's resume plays once`() = runBlocking {
        reloadingHost()
        adBreak()
        player.pause()
        waitFor("pause sent") { sent("player.pause").isNotEmpty() }
        host.post(pausedAd)
        // Past the delay after which the watcher gets past a playing ad (it used to play the next song).
        waitFor("the ad got past", ms = SpotifyPlayer.AD_PLAYING_SKIP_MS + 15_000) { reloads > 0 || sent("player.play").isNotEmpty() }
        waitFor("break over") { !player.adPlaying.value }
        delay(2_500)
        assertTrue(sent("player.play").isEmpty() && sent("player.resume").isEmpty(), "music started after the user's pause: ${host.calls.map { it.first }}")
        assertEquals(1, reloads)
        // The user plays again: the music goes on from the song before the ad, in one play.
        player.resume()
        waitFor("play after the resume") { sent("player.play").isNotEmpty() }
        delay(1_500)
        assertEquals(1, sent("player.play").size, "one play: ${host.calls.map { it.first }}")
        assertTrue(sent("player.resume").isEmpty(), "nothing to resume on a page without a song: ${host.calls.map { it.first }}")
    }

    /** The other side of the test above: nobody paused, the watcher gets past the ad and the music goes on. */
    @Test fun `an ad nobody paused is got past and the music goes on`() = runBlocking {
        reloadingHost()
        adBreak()
        waitFor("music on after the ad", ms = SpotifyPlayer.AD_PLAYING_SKIP_MS + 15_000) { sent("player.play").isNotEmpty() }
    }

    @Test fun `a pick then a pause during an ad that ends by itself plays nothing, the resume plays the pick once`() = runBlocking {
        adBreak()
        player.play("spotify:playlist:C", trackUri = "spotify:track:P", order = listOf("spotify:track:P", "spotify:track:Q"))
        player.pause()
        waitFor("pause sent") { sent("player.pause").isNotEmpty() }
        // The break ends before the watcher gets past it, on the old queue's next song, paused.
        host.post(state(track("B"), track("C2"), paused = true))
        waitFor("break over") { !player.adPlaying.value }
        delay(SpotifyPlayer.AD_END_SETTLE_MS + 2_500)
        assertTrue(sent("player.play").isEmpty() && sent("player.resume").isEmpty(), "music started after the user's pause: ${host.calls.map { it.first }}")
        player.resume()
        waitFor("play after the resume") { sent("player.play").isNotEmpty() }
        delay(1_500)
        val plays = sent("player.play").map { it.second }
        assertEquals(1, plays.size, "one play: ${host.calls.map { it.first }}")
        assertTrue("spotify:track:P" in plays.single() && "spotify:track:Q" in plays.single(), "the pick, in its order: ${plays.single()}")
        assertTrue(sent("player.resume").isEmpty(), "the pick is played, not the old song resumed: ${host.calls.map { it.first }}")
    }

    @Test fun `a pick then a pause during an ad the watcher gets past plays nothing, the resume plays the pick once`() = runBlocking {
        reloadingHost()
        adBreak()
        player.play("spotify:playlist:C", trackUri = "spotify:track:P", order = listOf("spotify:track:P", "spotify:track:Q"))
        player.pause()
        waitFor("pause sent") { sent("player.pause").isNotEmpty() }
        host.post(pausedAd)
        waitFor("the ad got past", ms = SpotifyPlayer.AD_PLAYING_SKIP_MS + 15_000) { reloads > 0 || sent("player.play").isNotEmpty() }
        waitFor("break over") { !player.adPlaying.value }
        delay(2_500)
        assertTrue(sent("player.play").isEmpty() && sent("player.resume").isEmpty(), "music started after the user's pause: ${host.calls.map { it.first }}")
        player.resume()
        waitFor("play after the resume") { sent("player.play").isNotEmpty() }
        delay(1_500)
        val plays = sent("player.play").map { it.second }
        assertEquals(1, plays.size, "one play: ${host.calls.map { it.first }}")
        assertTrue("spotify:track:P" in plays.single() && "spotify:track:Q" in plays.single(), "the pick, in its order, not the song after A: ${plays.single()}")
    }
}
