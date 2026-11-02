package app.myspoti.spotify

import app.myspoti.api.Repeat
import app.myspoti.spotify.bridge.AdBlock
import app.myspoti.spotify.bridge.Bridge
import app.myspoti.spotify.bridge.BridgeHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * What the player does after the page did something the user did not ask for: a pause at the end
 * of an ad break, a repeat mode forgotten by a play, a WebView without Widevine.
 */
class PlaybackFollowUpTest {
    private class FakeHost : BridgeHost {
        override val messages = MutableSharedFlow<String>(extraBufferCapacity = 64)
        val calls = CopyOnWriteArrayList<Pair<String, String>>()
        override suspend fun evaluate(script: String) {
            val m = Regex("""Myspoti\.call\((\d+), "([^"]+)", (.*)\)\.catch""", RegexOption.DOT_MATCHES_ALL).find(script) ?: return
            calls += m.groupValues[2] to m.groupValues[3]
            // The page's media element: standing still, this phone the active device (or not).
            val value = if (m.groupValues[2] == "media.state") """{"paused":${mediaPaused},"muted":false,"level":1,"own":1,"time":0,"active":${mediaActive}}""" else "null"
            messages.emit("""{"type":"result","id":${m.groupValues[1]},"ok":true,"value":$value}""")
        }
        suspend fun post(json: String) = messages.emit(json)
        @Volatile var mediaActive = true
        @Volatile var mediaPaused = false
    }

    private lateinit var scope: CoroutineScope
    private lateinit var host: FakeHost
    private lateinit var player: SpotifyPlayer
    private lateinit var bridge: Bridge

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

    private fun state(item: String, paused: Boolean = false, repeat: Int = 0) =
        """{"type":"state","state":{"track":$item,"contextUri":"spotify:playlist:C","next":${track("N")},"isPaused":$paused,"isBuffering":false,
            |"repeat":$repeat,"positionMs":0,"timestamp":${System.currentTimeMillis()},"restrictions":{}}}""".trimMargin()

    private val ad = """{"kind":"ad","uri":"spotify:ad:1","name":"ad","durationMs":30000}"""

    private fun waitFor(what: String, ms: Long = 4_000, cond: () -> Boolean) = runBlocking {
        val end = System.currentTimeMillis() + ms
        while (!cond()) {
            check(System.currentTimeMillis() < end) { "timed out: $what" }
            delay(20)
        }
    }

    private fun called(method: String) = host.calls.count { it.first == method }

    @Test fun `a song left paused by the page after an ad break is resumed`() = runBlocking {
        host.post("""{"type":"ready"}""")
        host.post(state(track("A")))
        host.post(state(ad))
        waitFor("ad flag") { player.adPlaying.value }
        host.post(state(track("B"), paused = true))
        waitFor("resumed") { called("player.resume") > 0 }
    }

    @Test fun `a pause by the user during the ad break is left alone`() = runBlocking {
        host.post("""{"type":"ready"}""")
        host.post(state(track("A")))
        host.post(state(ad))
        waitFor("ad flag") { player.adPlaying.value }
        runCatching { player.pause() }
        val before = called("player.resume")
        host.post(state(track("B"), paused = true))
        delay(SpotifyPlayer.AD_END_SETTLE_MS + 500)
        assertEquals(before, called("player.resume"), "the user's pause holds")
    }

    @Test fun `the repeat mode the user chose is set again when a play leaves the page on another`() = runBlocking {
        host.post("""{"type":"ready"}""")
        host.post(state(track("A"), repeat = 1))
        player.setRepeat(Repeat.CONTEXT)
        val sent = called("player.setRepeat")
        player.play("spotify:playlist:C", trackUri = "spotify:track:P")
        host.post(state(track("P"), repeat = 0))
        waitFor("repeat set again") { called("player.setRepeat") == sent + 1 }
        // Set again once, not on every state until the page agrees.
        host.post(state(track("P"), repeat = 0))
        delay(300)
        assertEquals(sent + 1, called("player.setRepeat"))
    }

    @Test fun `while playback is held, each song the page moves on to by itself is paused once`() = runBlocking {
        player.playbackHeld = { true }
        host.post("""{"type":"ready"}""")
        host.post(state(track("A")))
        waitFor("A paused") { called("player.pause") == 1 }
        // The same song again (the user played it on): left alone.
        host.post(state(track("A")))
        delay(300)
        assertEquals(1, called("player.pause"))
        // The page skipped on by itself to a song it has no licence for: paused too.
        host.post(state(track("B")))
        waitFor("B paused") { called("player.pause") == 2 }
        player.playbackHeld = { false }
        host.post(state(track("C")))
        delay(300)
        assertEquals(2, called("player.pause"), "no hold, no pause")
    }

    @Test fun `a reload waits for the new page, not the old page still ready`() = runBlocking {
        host.post("""{"type":"ready"}""")
        waitFor("ready") { bridge.ready.value }
        // The host only asks for the load: the old page stays ready until the new one starts.
        player.reloadHost = { }
        val reloaded = async(Dispatchers.Default) { player.reloadPage() }
        delay(2_500)
        assertFalse(reloaded.isCompleted, "still the old page")
        bridge.pageGone()
        host.post("""{"type":"ready"}""")
        assertTrue(reloaded.await())
    }

    @Test fun `the repeat mode of an earlier run is set again on the first song`() = runBlocking {
        player.restoreRepeat(Repeat.CONTEXT)
        host.post("""{"type":"ready"}""")
        host.post(state(track("A"), repeat = 0))
        // Sent once the watcher read the element (this phone the one playing, not another device):
        // at its first tick, up to WATCH_IDLE_TICK_MS after it started.
        waitFor("repeat set again", ms = 10_000) { called("player.setRepeat") == 1 }
        assertEquals("{\"mode\":1}", host.calls.last { it.first == "player.setRepeat" }.second)
    }

    @Test fun `two kinds of command refused stop the fallbacks, reload the page once and tell the user`() = runBlocking {
        host.post("""{"type":"ready"}""")
        host.post(state(track("A")))
        var reloads = 0
        player.reloadHost = {
            reloads++
            bridge.pageGone()
            host.post("""{"type":"ready"}""")
        }
        var told = 0
        val watch = scope.launch { player.refusedAll.collect { told++ } }
        player.play("spotify:playlist:C", trackUri = "spotify:track:P")
        val plays = called("player.play")
        // The play refused: the first fallback (the list without the song) runs.
        host.post("""{"type":"refused","status":402,"kind":"play"}""")
        waitFor("first fallback") { called("player.play") == plays + 1 }
        // Its transfer refused too: no more fallbacks, one reload, the song asked again there.
        host.post("""{"type":"refused","status":402,"kind":"transfer"}""")
        waitFor("reloaded and asked again") { reloads == 1 && called("player.play") == plays + 2 }
        waitFor("told") { told == 1 }
        host.post("""{"type":"refused","status":402,"kind":"seek_to"}""")
        delay(2_000)
        assertEquals(1, reloads, "reloaded once only")
        assertEquals(plays + 2, called("player.play"), "no fallback play: ${host.calls.map { it.first }}")
        assertFalse(host.calls.any { "pullToLocalDevice" in it.second }, "no transfer fallback")
        watch.cancel()
    }

    @Test fun `a webview without widevine is reported`() = runBlocking {
        assertFalse(player.drmMissing.value)
        host.post("""{"type":"drm","ok":true}""")
        delay(100)
        assertFalse(player.drmMissing.value)
        host.post("""{"type":"drm","ok":false,"error":"no EME in this WebView"}""")
        waitFor("drm flag") { player.drmMissing.value }
        assertTrue(player.drmMissing.value)
    }

    // Asked at boot in 2.0.2-beta.1, the probe ran beside the player's own EME set-up and the page
    // never registered its device: it is asked only once a play has gone wrong.
    @Test fun `widevine is probed after a refusal, not at boot`() = runBlocking {
        host.post("""{"type":"ready","version":"t"}""")
        delay(300)
        assertEquals(0, host.calls.count { it.first == "drm.probe" })
        host.post("""{"type":"refused","status":402}""")
        waitFor("drm probe") { host.calls.any { it.first == "drm.probe" } }
    }

    @Test fun `a refusal told late is dropped while another device plays`() = runBlocking {
        host.mediaActive = false
        host.mediaPaused = true
        host.post("""{"type":"ready"}""")
        host.post(state(track("A")))
        player.play("spotify:playlist:C", trackUri = "spotify:track:P")
        // The watcher, idle until a song plays, reads the element at its first tick (up to
        // WATCH_IDLE_TICK_MS after it started, 4 s): the wait is a bound, not a sleep. The read
        // must also be taken in (the call alone is not): another device is known to be active.
        waitFor("another device seen", ms = 10_000) { player.remoteActive }
        host.post("""{"type":"refused","status":402,"late":true}""")
        delay(300)
        assertEquals(1, host.calls.count { it.first == "player.play" }, "no fallback play on another device: ${host.calls.map { it.first }}")
    }
}
