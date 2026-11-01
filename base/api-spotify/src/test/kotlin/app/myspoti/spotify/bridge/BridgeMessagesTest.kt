package app.myspoti.spotify.bridge

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/** Messages the bridge did not write must be dropped, never end the message loop. */
class BridgeMessagesTest {
    /** Answers each `Myspoti.call(id, …)` with [reply] for that id. */
    private class FakeHost(val reply: (Int) -> String) : BridgeHost {
        override val messages = MutableSharedFlow<String>(extraBufferCapacity = 64)
        override suspend fun evaluate(script: String) {
            val id = Regex("""Myspoti\.call\((\d+),""").find(script)?.groupValues?.get(1)?.toInt() ?: return
            messages.emit(reply(id))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterEach fun stop() = scope.cancel()

    private val garbage = listOf(
        """{"type":"result","id":"x"}""",
        """{"type":{"nested":true}}""",
        """{"type":"result","id":1.5,"ok":"yes"}""",
        """{"type":"result","id":999,"ok":"maybe","error":{"m":1}}""",
        """[1,2,3]""",
        """not json""",
    )

    private fun bridgeAfterGarbage(reply: (Int) -> String): Bridge = runBlocking {
        val host = FakeHost(reply)
        val bridge = Bridge(host, scope)
        host.messages.subscriptionCount.first { it > 0 }
        garbage.forEach { host.messages.emit(it) }
        bridge
    }

    @Test fun `calls still answer after malformed messages`() = runBlocking {
        val bridge = bridgeAfterGarbage { id -> """{"type":"result","id":$id,"ok":true,"value":42}""" }
        assertEquals(JsonPrimitive(42), bridge.call("bridge.version", timeoutMs = 2_000))
    }

    @Test fun `a result with fields of the wrong type fails that call only`() {
        val bridge = bridgeAfterGarbage { id -> """{"type":"result","id":$id,"ok":"yes","error":{"m":1}}""" }
        assertThrows(BridgeException::class.java) { runBlocking { bridge.call("bridge.version", timeoutMs = 2_000) } }
    }

    @Test fun `a page going away fails calls at once, the next page answers again`() = runBlocking {
        val host = FakeHost { id -> """{"type":"result","id":$id,"ok":true,"value":1}""" }
        val bridge = Bridge(host, scope)
        host.messages.subscriptionCount.first { it > 0 }
        bridge.pageGone()
        val started = System.currentTimeMillis()
        val e = assertThrows(BridgeException::class.java) { runBlocking { bridge.call("bridge.version", timeoutMs = 5_000) } }
        assertEquals("not ready", e.message)
        assertEquals(true, System.currentTimeMillis() - started < 1_000)
        host.messages.emit("""{"type":"ready","version":1}""")
        bridge.ready.first { it }
        assertEquals(JsonPrimitive(1), bridge.call("bridge.version", timeoutMs = 2_000))
    }

    /** A host that tells what the page made of each script: [value], or a failure to run it. */
    private class TellingHost(val value: () -> String?) : BridgeHost {
        override val messages = MutableSharedFlow<String>(extraBufferCapacity = 64)
        val scripts = mutableListOf<String>()
        override suspend fun evaluate(script: String) { scripts += script }
        override suspend fun evaluateValue(script: String): String? { scripts += script; return value() }
    }

    @Test fun `a call that cannot reach the page fails at once, not after its timeout`() = runBlocking {
        for (host in listOf(TellingHost { "false" }, TellingHost { throw IllegalStateException("view destroyed") })) {
            val bridge = Bridge(host, scope)
            val started = System.currentTimeMillis()
            assertThrows(BridgeException::class.java) { runBlocking { bridge.call("bridge.version", timeoutMs = 5_000) } }
            assertEquals(true, System.currentTimeMillis() - started < 1_000)
        }
    }

    @Test fun `a call is made for the page of its generation`() = runBlocking {
        val host = TellingHost { "true" }
        val bridge = Bridge(host, scope)
        host.messages.subscriptionCount.first { it > 0 }
        bridge.pageGone()
        bridge.inject()
        host.messages.emit("""{"type":"ready","version":1}""")
        bridge.ready.first { it }
        runCatching { bridge.call("bridge.version", timeoutMs = 50) }
        assertEquals(true, host.scripts.first().startsWith("window.__mspPage = 1;"))
        assertEquals(true, "window.__mspPage === 1)" in host.scripts.last())
    }
}
