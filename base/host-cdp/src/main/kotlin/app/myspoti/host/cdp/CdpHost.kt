package app.myspoti.host.cdp

import app.myspoti.spotify.bridge.AdBlock
import app.myspoti.spotify.bridge.BridgeHost
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.first
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
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * [BridgeHost] on a desktop Chrome started with `--remote-debugging-port`.
 *
 * Attaches to the open.spotify.com tab (or opens one), relays bridge messages through a CDP
 * binding, and fails the requests [AdBlock] refuses with `BlockedByClient`, like the
 * Android host does in `shouldInterceptRequest`.
 */
class CdpHost private constructor(private val socket: WebSocket, private val listener: Listener) : BridgeHost {

    override val messages: SharedFlow<String> = listener.messages

    /** Suspends until the next page load; the API re-injects the bridge then. */
    suspend fun awaitLoad() { listener.pageLoads.first() }

    override suspend fun evaluate(script: String) {
        send("Runtime.evaluate", buildJsonObject { put("expression", script); put("awaitPromise", false) })
    }

    /** Sets each cookie ([name] to [value]) on [domain] for a year, secure, on the whole site. */
    suspend fun setCookies(cookies: List<Pair<String, String>>, domain: String) {
        val expires = System.currentTimeMillis() / 1000.0 + 365 * 24 * 3600
        for ((name, value) in cookies) send("Network.setCookie", buildJsonObject {
            put("name", name); put("value", value); put("domain", domain); put("path", "/")
            put("secure", true); put("expires", expires)
        })
    }

    suspend fun reload() {
        send("Page.reload", buildJsonObject {})
    }

    fun close() = socket.close(1000, null)

    private suspend fun send(method: String, params: JsonObject): JsonObject = listener.send(socket, method, params)

    internal class Listener : WebSocketListener() {
        val messages = MutableSharedFlow<String>(extraBufferCapacity = 256)
        private val ids = AtomicInteger(0)
        private val pending = ConcurrentHashMap<Int, CompletableDeferred<JsonObject>>()
        val opened = CompletableDeferred<Unit>()
        private val loads = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
        val pageLoads: SharedFlow<Unit> = loads

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
                "Runtime.bindingCalled" ->
                    if (params["name"]?.jsonPrimitive?.content == BINDING) messages.tryEmit(params["payload"]!!.jsonPrimitive.content)
                "Fetch.requestPaused" -> {
                    val requestId = params["requestId"]!!.jsonPrimitive.content
                    val request = params["request"]!!.jsonObject
                    val url = request["url"]!!.jsonPrimitive.content
                    // The device registration goes through as the Web Player sends it (see
                    // DeviceRegistration.withDeviceName): the desktop host keeps the browser's own name.
                    val reply = if (AdBlock.isBlocked(url)) {
                        buildJsonObject { put("id", ids.incrementAndGet()); put("method", "Fetch.failRequest"); putJsonObject("params") { put("requestId", requestId); put("errorReason", "BlockedByClient") } }
                    } else {
                        buildJsonObject { put("id", ids.incrementAndGet()); put("method", "Fetch.continueRequest"); putJsonObject("params") { put("requestId", requestId) } }
                    }
                    webSocket.send(reply.toString())
                }
                "Page.loadEventFired" -> loads.tryEmit(Unit)
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

        /** Attaches to the Web Player tab of the Chrome listening on [cdpUrl]. */
        suspend fun connect(cdpUrl: String = "http://localhost:9222", http: OkHttpClient = defaultHttp): CdpHost {
            val targets = withContext(Dispatchers.IO) {
                http.newCall(Request.Builder().url("$cdpUrl/json/list").build()).execute().use { it.body.string() }
            }
            val page = json.parseToJsonElement(targets).jsonArray.map { it.jsonObject }.firstOrNull {
                it["type"]?.jsonPrimitive?.content == "page" && it["url"]!!.jsonPrimitive.content.startsWith("https://open.spotify.com")
            } ?: withContext(Dispatchers.IO) {
                val created = http.newCall(Request.Builder().url("$cdpUrl/json/new?https://open.spotify.com/").put(okhttp3.RequestBody.EMPTY).build())
                    .execute().use { it.body.string() }
                json.parseToJsonElement(created).jsonObject
            }
            val wsUrl = page["webSocketDebuggerUrl"]!!.jsonPrimitive.content
            val listener = Listener()
            val socket = http.newWebSocket(Request.Builder().url(wsUrl).build(), listener)
            listener.opened.await()
            val host = CdpHost(socket, listener)
            host.send("Runtime.enable", buildJsonObject {})
            host.send("Page.enable", buildJsonObject {})
            host.send("Runtime.addBinding", buildJsonObject { put("name", BINDING) })
            host.send("Fetch.enable", buildJsonObject {
                put("patterns", buildJsonArray {
                    AdBlock.hosts.forEach { h -> add(buildJsonObject { put("urlPattern", "*://$h/*") }) }
                    add(buildJsonObject { put("urlPattern", "*/track-playback/v1/devices") })
                })
            })
            return host
        }

        private val defaultHttp = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).build()
    }
}

/**
 * The browser as a whole (not one tab): what is asked of it whichever tab or window the user is in,
 * as a sign-in that went through another page or a popup.
 */
object CdpBrowser {
    private val json = Json { ignoreUnknownKeys = true }
    private val http = OkHttpClient.Builder().readTimeout(10, TimeUnit.SECONDS).build()

    /** The names of the browser's cookies on [domain] (and its subdomains): names only, never their values. */
    suspend fun cookieNames(cdpUrl: String, domain: String): Set<String> {
        val version = withContext(Dispatchers.IO) {
            http.newCall(Request.Builder().url("$cdpUrl/json/version").build()).execute().use { it.body.string() }
        }
        val ws = json.parseToJsonElement(version).jsonObject["webSocketDebuggerUrl"]!!.jsonPrimitive.content
        val answer = kotlinx.coroutines.CompletableDeferred<String>()
        val socket = http.newWebSocket(Request.Builder().url(ws).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { webSocket.send("""{"id":1,"method":"Storage.getCookies"}""") }
            override fun onMessage(webSocket: WebSocket, text: String) { if (text.startsWith("{\"id\":1")) answer.complete(text) }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { answer.completeExceptionally(t) }
        })
        try {
            val text = kotlinx.coroutines.withTimeout(10_000) { answer.await() }
            val cookies = json.parseToJsonElement(text).jsonObject["result"]?.jsonObject?.get("cookies")?.jsonArray.orEmpty()
            return cookies.mapNotNull { c ->
                val o = c.jsonObject
                val d = o["domain"]?.jsonPrimitive?.content?.trimStart('.') ?: return@mapNotNull null
                if (d == domain || d.endsWith(".$domain")) o["name"]?.jsonPrimitive?.content else null
            }.toSet()
        } finally { socket.close(1000, null) }
    }
}
