/* SPDX-License-Identifier: GPL-3.0-only */
package app.myspoti.host.cdp

import app.myspoti.web.PageHost
import app.myspoti.web.SessionCookies
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * An account provider's hidden page ([PageHost]) in a tab of a desktop Chrome started with
 * `--remote-debugging-port`, for the tests on a PC: the tab of [origin] (or a new one), the page
 * script's messages through a CDP binding, the page loads, and the session's cookies as that
 * Chrome keeps them ([SessionCookies]). Unlike the Spotify host, it blocks and rewrites nothing.
 * The user signs in in that tab themself.
 */
class CdpPageHost private constructor(
    private val socket: WebSocket,
    private val listener: Listener,
    private val sessionUrls: List<String>,
) : PageHost, SessionCookies {
    override val messages: SharedFlow<String> = listener.messages
    override val loads: SharedFlow<String> = listener.loads

    override suspend fun evaluate(script: String) {
        send("Runtime.evaluate", buildJsonObject { put("expression", script); put("awaitPromise", false) })
    }

    override suspend fun navigate(url: String) {
        send("Page.navigate", buildJsonObject { put("url", url) })
    }

    private suspend fun cookies(): List<JsonObject> =
        send("Network.getCookies", buildJsonObject { put("urls", buildJsonArray { sessionUrls.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }) })["cookies"]
            ?.jsonArray?.map { it.jsonObject }.orEmpty()

    override suspend fun header(url: String): String? {
        val list = send("Network.getCookies", buildJsonObject { put("urls", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive(url)) }) })["cookies"]
            ?.jsonArray?.map { it.jsonObject }.orEmpty()
        return list.joinToString("; ") { "${it["name"]!!.jsonPrimitive.content}=${it["value"]!!.jsonPrimitive.content}" }.ifEmpty { null }
    }

    override suspend fun clear() {
        for (c in cookies()) send("Network.deleteCookies", buildJsonObject {
            put("name", c["name"]!!.jsonPrimitive.content)
            put("domain", c["domain"]!!.jsonPrimitive.content)
            put("path", c["path"]?.jsonPrimitive?.content ?: "/")
        })
    }

    fun close() = socket.close(1000, null)

    private suspend fun send(method: String, params: JsonObject): JsonObject = listener.send(socket, method, params)

    internal class Listener : WebSocketListener() {
        val messages = MutableSharedFlow<String>(extraBufferCapacity = 256)
        val loads = MutableSharedFlow<String>(extraBufferCapacity = 8)
        private val ids = AtomicInteger(0)
        private val pending = ConcurrentHashMap<Int, CompletableDeferred<JsonObject>>()
        val opened = CompletableDeferred<Unit>()
        @Volatile private var url = ""

        suspend fun send(ws: WebSocket, method: String, params: JsonObject): JsonObject {
            val id = ids.incrementAndGet()
            val waiter = CompletableDeferred<JsonObject>()
            pending[id] = waiter
            ws.send(buildJsonObject { put("id", id); put("method", method); put("params", params) }.toString())
            return try { withTimeout(30_000) { waiter.await() } } finally { pending.remove(id) }
        }

        override fun onOpen(webSocket: WebSocket, response: Response) { opened.complete(Unit) }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val msg = json.parseToJsonElement(text).jsonObject
            msg["id"]?.let { id ->
                val waiter = pending.remove(id.jsonPrimitive.int) ?: return
                msg["error"]?.let { waiter.completeExceptionally(IllegalStateException(it.toString())) }
                    ?: waiter.complete(msg["result"]?.jsonObject ?: JsonObject(emptyMap()))
                return
            }
            val params = msg["params"]?.jsonObject ?: return
            when (msg["method"]?.jsonPrimitive?.content) {
                "Runtime.bindingCalled" -> if (params["name"]?.jsonPrimitive?.content == BINDING) messages.tryEmit(params["payload"]!!.jsonPrimitive.content)
                "Page.frameNavigated" -> params["frame"]?.jsonObject?.takeIf { it["parentId"] == null }?.let { url = it["url"]!!.jsonPrimitive.content }
                "Page.loadEventFired" -> loads.tryEmit(url)
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            opened.completeExceptionally(t)
            pending.values.forEach { it.completeExceptionally(t) }
        }
    }

    companion object {
        const val BINDING = "__mspHostPost"
        private val json = Json { ignoreUnknownKeys = true }
        private val defaultHttp = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).build()

        /** Attaches to the tab of [origin] in the Chrome listening on [cdpUrl] (opens [startUrl] in a new one when there is none). */
        suspend fun connect(origin: String, startUrl: String, sessionUrls: List<String>, cdpUrl: String = "http://localhost:9222", http: OkHttpClient = defaultHttp): CdpPageHost {
            val targets = withContext(Dispatchers.IO) { http.newCall(Request.Builder().url("$cdpUrl/json/list").build()).execute().use { it.body.string() } }
            val page = json.parseToJsonElement(targets).jsonArray.map { it.jsonObject }.firstOrNull {
                it["type"]?.jsonPrimitive?.content == "page" && it["url"]!!.jsonPrimitive.content.startsWith(origin)
            } ?: withContext(Dispatchers.IO) {
                json.parseToJsonElement(http.newCall(Request.Builder().url("$cdpUrl/json/new?$startUrl").put(okhttp3.RequestBody.EMPTY).build()).execute().use { it.body.string() }).jsonObject
            }
            val listener = Listener()
            val socket = http.newWebSocket(Request.Builder().url(page["webSocketDebuggerUrl"]!!.jsonPrimitive.content).build(), listener)
            listener.opened.await()
            val host = CdpPageHost(socket, listener, sessionUrls)
            host.send("Runtime.enable", buildJsonObject {})
            host.send("Page.enable", buildJsonObject {})
            host.send("Network.enable", buildJsonObject {})
            host.send("Runtime.addBinding", buildJsonObject { put("name", BINDING) })
            return host
        }
    }
}
