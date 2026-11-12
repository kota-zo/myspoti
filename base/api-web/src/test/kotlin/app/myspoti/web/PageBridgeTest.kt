package app.myspoti.web

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

@OptIn(ExperimentalCoroutinesApi::class)
class PageBridgeTest {
    private class Host : PageHost {
        override val messages = MutableSharedFlow<String>(extraBufferCapacity = 16)
        override val loads = MutableSharedFlow<String>(extraBufferCapacity = 4)
        override val gone = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
        val scripts = ArrayList<String>()
        override suspend fun evaluate(script: String) { scripts += script }
        override suspend fun navigate(url: String) { scripts += "navigate $url" }
    }

    @Test fun `injects at each load, answers calls of its own page, refuses an older page's`() = runTest {
        val host = Host()
        val bridge = PageBridge(host, "/*script*/", backgroundScope)
        runCurrent()
        host.loads.emit("https://music.example/"); runCurrent()
        assertTrue(host.scripts.single().contains("window.__mspPageGen = 1") && host.scripts.single().endsWith("/*script*/"))
        // A ready of the page before (generation 0) is not this one's.
        bridge.handle("""{"type":"ready","page":0}"""); assertFalse(bridge.ready.value)
        bridge.handle("""{"type":"ready","page":1,"signedIn":true}"""); assertTrue(bridge.ready.value)
        assertEquals("true", bridge.info.value!!["signedIn"]!!.jsonPrimitive.content)
        val answer = async { bridge.call("state") }
        runCurrent()
        assertTrue(host.scripts.last().contains("window.MyspotiPage.call(1, \"state\""), host.scripts.last())
        bridge.handle("""{"type":"result","id":1,"ok":true,"value":{"x":2}}""")
        assertEquals("""{"x":2}""", answer.await().toString())
        // A call waiting when the page reloads fails at once.
        val waiting = async { runCatching { bridge.call("pause") } }
        runCurrent()
        host.loads.emit("https://music.example/watch"); runCurrent()
        assertTrue(waiting.await().exceptionOrNull() is PageException)
        assertFalse(bridge.ready.value)
        assertThrows<PageException> { bridge.call("pause") }
    }

    @Test fun `an error answer and an unknown message`() = runTest {
        val host = Host()
        val bridge = PageBridge(host, "", backgroundScope)
        runCurrent()
        bridge.handle("""{"type":"ready"}""")
        val failed = async { runCatching { bridge.call("seek") } }
        runCurrent()
        bridge.handle("not json")
        bridge.handle("""{"type":"result","id":1,"ok":false,"error":"no player"}""")
        assertEquals("no player", failed.await().exceptionOrNull()?.message)
    }

    @Test fun `a page whose renderer died is not ready, its calls fail at once, and the next load is a new page`() = runTest {
        val host = Host()
        val bridge = PageBridge(host, "", backgroundScope)
        runCurrent()
        host.loads.emit("https://music.example/"); runCurrent()
        bridge.handle("""{"type":"ready","page":1}"""); assertTrue(bridge.ready.value)
        val waiting = async { runCatching { bridge.call("state") } }
        runCurrent()
        host.gone.emit(Unit); runCurrent()
        assertTrue(waiting.await().exceptionOrNull() is PageException)
        assertFalse(bridge.ready.value)
        // The host loads a new page at once: it is the next generation.
        host.loads.emit("https://music.example/"); runCurrent()
        assertTrue(host.scripts.last().contains("window.__mspPageGen = 3"), host.scripts.last())
    }
}
