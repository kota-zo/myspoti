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
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Reported (#224, log seen): a stuck ad, the page reloaded past it, then the song played muted
 * until the player was restarted. On the new page the end of the break sent the unmute and the
 * new page's mute (held until a song plays) went out while it was still on its way: the unmute
 * answered last and the app took the page for unmuted, so nothing unmuted it when the song played.
 */
class AdMuteAfterReloadTest {
    private class FakeHost(private val scope: CoroutineScope) : BridgeHost {
        override val messages = MutableSharedFlow<String>(extraBufferCapacity = 64)
        /** Every "media.mute" sent: true for a mute, false for an unmute, in the order sent. */
        val mutes = CopyOnWriteArrayList<Boolean>()
        override suspend fun evaluate(script: String) {
            val m = Regex("""Myspoti\.call\((\d+), "([^"]+)", (.*)\)\.catch""", RegexOption.DOT_MATCHES_ALL).find(script) ?: return
            val id = m.groupValues[1]
            if (m.groupValues[2] != "media.mute") {
                // The page takes a moment over each call: the new page's mute comes after its other calls.
                scope.launch {
                    delay(OTHER_ANSWER_MS)
                    messages.emit("""{"type":"result","id":$id,"ok":true,"value":null}""")
                }
                return
            }
            val on = m.groupValues[3].contains("\"on\":true")
            mutes += on
            // As on the phone: the unmute waits for the page's player (219 ms in the log), the mute does not.
            scope.launch {
                if (!on) delay(UNMUTE_ANSWER_MS)
                messages.emit("""{"type":"result","id":$id,"ok":true,"value":1}""")
            }
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
        host = FakeHost(scope)
        bridge = Bridge(host, scope)
        player = SpotifyPlayer(bridge, scope)
        player.reloadHost = {
            scope.launch {
                delay(300)
                bridge.pageGone()
                delay(300)
                host.post("""{"type":"ready"}""")
                // As in the log: the new page reports the song after the ad, still loading, which
                // ends the break while the page is being set up (and muted until the song plays).
                host.post(state(track("B"), buffering = true))
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

    @Test fun `the song after a reload past a stuck ad is heard`() = runBlocking {
        host.post("""{"type":"ready"}""")
        waitFor("page ready") { bridge.ready.value }
        host.post(state(track("A"), track("B")))
        host.post(state(ad, buffering = true))
        waitFor("ad flag") { player.adPlaying.value }
        // The watcher reloads the page a few seconds into the stuck ad. On the new page the break
        // ends and the page is muted until a song plays.
        waitFor("the break's end on the new page", ms = 20_000) { host.mutes.contains(false) }
        delay(UNMUTE_ANSWER_MS * 3)
        host.post(state(track("B")))
        // Settled: the song plays, and the last word the page got is the unmute.
        delay(UNMUTE_ANSWER_MS * 3)
        assertEquals(false, host.mutes.last(), "mutes sent, in order: ${host.mutes}")
    }

    private companion object {
        const val UNMUTE_ANSWER_MS = 300L
        const val OTHER_ANSWER_MS = 50L
    }
}
