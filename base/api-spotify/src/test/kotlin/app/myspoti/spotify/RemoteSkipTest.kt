/* SPDX-License-Identifier: GPL-3.0-only */
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
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList

/** Reported: on another device, a skip moved the sound back to the phone. Only a plain skip reaches that device. */
class RemoteSkipTest {
    private class FakeHost(val active: Boolean) : BridgeHost {
        override val messages = MutableSharedFlow<String>(extraBufferCapacity = 64)
        val calls = CopyOnWriteArrayList<String>()
        override suspend fun evaluate(script: String) {
            val m = Regex("""Myspoti\.call\((\d+), "([^"]+)", (.*)\)\.catch""", RegexOption.DOT_MATCHES_ALL).find(script) ?: return
            calls += m.groupValues[2]
            val value = if (m.groupValues[2] == "media.state") """{"paused":${!active},"muted":false,"level":1,"own":1,"time":0,"active":$active}""" else "null"
            messages.emit("""{"type":"result","id":${m.groupValues[1]},"ok":true,"value":$value}""")
        }
    }

    private lateinit var scope: CoroutineScope

    @BeforeEach fun up() {
        AdBlock.ahead.value = null
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @AfterEach fun down() {
        AdBlock.ahead.value = null
        scope.cancel()
    }

    private fun track(name: String) = """{"kind":"track","uri":"spotify:track:$name","uid":"u$name","name":"$name","durationMs":200000}"""

    /** A skip the page answers without ever reporting the next song, long enough for the by-name play. */
    private fun skipUnanswered(active: Boolean): List<String> = runBlocking {
        val host = FakeHost(active)
        val player = SpotifyPlayer(Bridge(host, scope), scope)
        while (host.messages.subscriptionCount.value == 0) delay(10)
        host.messages.emit("""{"type":"ready"}""")
        host.messages.emit("""{"type":"state","state":{"track":${track("A")},"contextUri":"spotify:playlist:C","next":${track("B")},"isPaused":false,"isBuffering":false,
            |"repeat":0,"positionMs":0,"timestamp":${System.currentTimeMillis()},"restrictions":{}}}""".trimMargin())
        val end = System.currentTimeMillis() + 8_000
        while (player.remoteActive != !active || "media.state" !in host.calls) {
            check(System.currentTimeMillis() < end) { "the page's device was not read" }
            delay(50)
        }
        player.next()
        delay(SpotifyPlayer.NEXT_LOST_MS + 1_000)
        host.calls.toList()
    }

    @Test fun `a skip on another device never plays on this page`() {
        val calls = skipUnanswered(active = false)
        assertTrue("player.next" in calls, calls.toString())
        assertFalse("player.play" in calls, calls.toString())
    }

    @Test fun `an ad due does not bring a skip back from another device`() {
        AdBlock.ahead.value = AdBlock.AdAhead("uA", "spotify:track:A", "spotify:track:B")
        val calls = skipUnanswered(active = false)
        assertTrue("player.next" in calls, calls.toString())
        assertFalse("player.play" in calls || "queue.skipTo" in calls, calls.toString())
    }

    @Test fun `a skip the page lost here is still played by name`() {
        val calls = skipUnanswered(active = true)
        assertTrue("player.play" in calls, calls.toString())
    }
}
