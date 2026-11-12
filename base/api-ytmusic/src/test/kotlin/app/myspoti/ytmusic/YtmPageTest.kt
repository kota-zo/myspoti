package app.myspoti.ytmusic

import app.myspoti.web.PageHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** [YtmPage] against a stand-in of the page and its script (what `ytm-page.js` answers). */
@OptIn(ExperimentalCoroutinesApi::class)
class YtmPageTest {
    private class FakePage(private val scope: CoroutineScope, private val now: () -> Long, var signedIn: Boolean = true, val inApp: Boolean = false) : PageHost {
        override val messages = MutableSharedFlow<String>(extraBufferCapacity = 64)
        override val loads = MutableSharedFlow<String>(extraBufferCapacity = 8)
        val calls = ArrayList<String>()
        var video: String? = null
        var paused = true
        var pos = 0L
        private var gen = 0
        private val call = Regex("""MyspotiPage\.call\((\d+), "(\w+)", (\{.*?\})\)""")

        private fun post(s: String) { messages.tryEmit(s) }
        private fun state() = post("""{"type":"state","state":{"trackId":${video?.let { "\"$it\"" } ?: "null"},"paused":$paused,"buffering":false,"positionMs":$pos,"durationMs":200000,"ad":false,"at":${now()}}}""")

        override suspend fun evaluate(script: String) {
            if (script.startsWith("window.__mspPageGen = ")) {
                gen = script.removePrefix("window.__mspPageGen = ").substringBefore(';').toInt()
                post("""{"type":"ready","page":$gen,"signedIn":$signedIn,"clientVersion":"1.2.3"}""")
                if (video != null) state()
                return
            }
            val m = call.find(script) ?: return
            val (id, method, rawArgs) = m.destructured
            val args = Json.parseToJsonElement(rawArgs).jsonObject
            calls += method + (args["ms"]?.jsonPrimitive?.longOrNull?.let { " $it" } ?: args["videoId"]?.jsonPrimitive?.content?.let { " $it" } ?: "")
            when (method) {
                "open" -> if (inApp) {
                    // The web app's own navigation: the song comes on the same page, no load.
                    scope.launch { delay(1_000); video = args["videoId"]!!.jsonPrimitive.content; paused = false; pos = 0; state() }
                    post("""{"type":"result","id":$id,"ok":true,"value":"inapp"}""")
                    return
                } else scope.launch { delay(1_000); video = args["videoId"]!!.jsonPrimitive.content; paused = false; pos = 0; loads.emit("https://music.youtube.com/watch") }
                "seek" -> { pos = args["ms"]!!.jsonPrimitive.longOrNull!!; state() }
                "resume" -> { paused = false; state() }
                "pause" -> { paused = true; state() }
            }
            post("""{"type":"result","id":$id,"ok":true,"value":true}""")
        }

        override suspend fun navigate(url: String) {
            calls += "navigate ${url.substringAfter("v=", "home")}"
            scope.launch { delay(1_000); video = url.substringAfter("v=", "").ifEmpty { null }; paused = false; pos = 0; loads.emit(url) }
        }
    }

    @Test fun `a song opened inside the web app is waited for on the same page`() = runTest {
        val host = FakePage(backgroundScope, { testScheduler.currentTime }, inApp = true)
        val bridge = app.myspoti.web.PageBridge(host, YtmPage.SCRIPT, backgroundScope)
        val page = YtmPage(host, bridge, backgroundScope, wallClock = { testScheduler.currentTime })
        runCurrent()
        host.loads.emit(Ytm.HOME_URL); runCurrent()
        val gen = bridge.generation
        val played = async { page.play("vidAAAAAAA1") { 42_000 } }
        runCurrent(); advanceTimeBy(1_500); runCurrent()
        played.await()
        assertEquals(listOf("open vidAAAAAAA1", "seek 42000", "resume"), host.calls)
        assertEquals(gen, bridge.generation, "no page load")
        assertEquals("vidAAAAAAA1", page.state.value?.trackId)
    }

    @Test fun `a song is played by loading its watch page, then sought to the local place`() = runTest {
        val host = FakePage(backgroundScope, { testScheduler.currentTime })
        val page = YtmPage(host, app.myspoti.web.PageBridge(host, YtmPage.SCRIPT, backgroundScope), backgroundScope, wallClock = { testScheduler.currentTime })
        runCurrent()
        host.loads.emit(Ytm.HOME_URL); runCurrent()
        assertTrue(page.ready.value)
        assertEquals("1.2.3", page.pageInfo("clientVersion"))
        val played = async { page.play("vidAAAAAAA1") { 42_000 } }
        runCurrent(); advanceTimeBy(1_500); runCurrent()
        played.await()
        assertEquals(listOf("open vidAAAAAAA1", "seek 42000", "resume"), host.calls)
        assertEquals("vidAAAAAAA1", page.state.value?.trackId)
        // The same song again: no load, only kept in step.
        host.calls.clear()
        page.play("vidAAAAAAA1") { 42_500 }
        assertEquals(listOf("want vidAAAAAAA1", "resume"), host.calls)
        page.release()
        assertEquals("pause", host.calls.last())
    }

    @Test fun `a page without its script yet is loaded plainly, and signed out it takes no commands`() = runTest {
        val host = FakePage(backgroundScope, { testScheduler.currentTime }, signedIn = false)
        val page = YtmPage(host, app.myspoti.web.PageBridge(host, YtmPage.SCRIPT, backgroundScope), backgroundScope, wallClock = { testScheduler.currentTime })
        runCurrent()
        val played = async { page.play("vidAAAAAAA2") { 0 } }
        runCurrent(); advanceTimeBy(1_500); runCurrent()
        played.await()
        assertEquals(listOf("navigate vidAAAAAAA2", "want vidAAAAAAA2", "resume"), host.calls)
        assertFalse(page.ready.value, "signed out: not ready for the sync")
        assertEquals(false, page.signedIn)
    }

    @Test fun `the script's state message`() {
        val s = YtmPage.parseState(Json.parseToJsonElement("""{"trackId":"vidAAAAAAA1","paused":false,"buffering":true,"positionMs":1200,"durationMs":5000,"ad":true,"at":10}"""))!!
        assertEquals("vidAAAAAAA1", s.trackId); assertTrue(s.buffering); assertTrue(s.ad); assertEquals(1200, s.positionAt(99_999))
    }
}
