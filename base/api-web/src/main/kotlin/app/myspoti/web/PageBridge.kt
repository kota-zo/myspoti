/* SPDX-License-Identifier: GPL-3.0-only */
package app.myspoti.web

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class PageException(message: String) : Exception(message)

/**
 * Requests and answers with a script injected in a provider's page ([script]), over a [PageHost].
 *
 * The script defines `window.MyspotiPage` with `call(id, method, args)` and posts one JSON message
 * per event: `{type: "ready", ...}` once the page can take calls (the other fields are kept in
 * [info]), `{type: "result", id, ok, value | error}` for a call, and any other type (`state`) as an
 * event ([events]). It is injected after each page load; a call made for a page that went away is
 * refused by the next one ([generation]).
 */
class PageBridge(private val host: PageHost, private val script: String, scope: CoroutineScope) {
    private val ids = AtomicInteger(0)
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<JsonElement>>()

    private val _ready = MutableStateFlow(false)
    /** The page is loaded, the script is in and answered "ready". */
    val ready: StateFlow<Boolean> = _ready

    private val _info = MutableStateFlow<JsonObject?>(null)
    /** The fields of the page's last "ready" (the signed-in flag...), null before. */
    val info: StateFlow<JsonObject?> = _info

    private val _events = MutableSharedFlow<Pair<String, JsonElement>>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    /** The page's own messages other than "ready" and "result", by type. */
    val events: SharedFlow<Pair<String, JsonElement>> = _events

    /** Diagnostics: one line per call and page message (never their values). */
    @Volatile var logger: ((String) -> Unit)? = null

    /** How many pages were loaded: the script's page knows its own number, older calls are refused. */
    @Volatile var generation = 0
        private set

    init {
        // Listening before the constructor returns (undispatched), as the Spotify bridge does: a
        // message, load or loss posted right after it reached no collector yet and was lost.
        // A message the script did not write (another script of the page) never ends the collector.
        scope.launch(start = CoroutineStart.UNDISPATCHED) { host.messages.collect { raw -> runCatching { handle(raw) }.onFailure { logger?.invoke("page message dropped: ${it.message}") } } }
        scope.launch(start = CoroutineStart.UNDISPATCHED) { host.loads.collect { url -> pageLoaded(url) } }
        scope.launch(start = CoroutineStart.UNDISPATCHED) { host.gone.collect { logger?.invoke("page gone (renderer)"); pageGone() } }
    }

    private suspend fun pageLoaded(url: String) {
        pageGone()
        logger?.invoke("page loaded: ${url.substringBefore('?').take(80)}")
        runCatching { inject() }.onFailure { logger?.invoke("bridge not injected: ${it.message}") }
    }

    /** The page went away (a new load started): not ready, the calls still waiting fail now. */
    fun pageGone() {
        generation++
        _ready.value = false
        val failed = pending.values.toList()
        pending.clear()
        failed.forEach { it.completeExceptionally(PageException("page reloaded")) }
    }

    /** Injects the script in the page now (the host calls it after each load; harmless twice). */
    suspend fun inject() {
        host.evaluate("window.__mspPageGen = $generation; window.__mspReannounce = ${!_ready.value}; $script")
    }

    internal fun handle(raw: String) {
        val msg = runCatching { json.parseToJsonElement(raw) }.getOrNull() as? JsonObject ?: return
        fun field(name: String) = msg[name] as? JsonPrimitive
        val type = field("type")?.contentOrNull ?: return
        when (type) {
            "ready" -> {
                // A "ready" of an older page (posted just before a reload) is not this page's.
                val gen = field("page")?.intOrNull
                if (gen != null && gen != generation) return
                _info.value = msg
                _ready.value = true
                logger?.invoke("page ready")
            }
            "result" -> {
                val id = field("id")?.intOrNull ?: return
                val waiter = pending.remove(id) ?: return
                if (field("ok")?.booleanOrNull == true) waiter.complete(msg["value"] ?: JsonNull)
                else waiter.completeExceptionally(PageException(field("error")?.contentOrNull ?: "page error"))
            }
            else -> _events.tryEmit(type to (msg[type] ?: msg))
        }
    }

    /** Calls [method] of the page script with [args]; its answer, or a [PageException] (not ready, refused, timed out). */
    suspend fun call(method: String, args: JsonObject = JsonObject(emptyMap()), timeoutMs: Long = 15_000): JsonElement {
        if (!_ready.value) throw PageException("not ready")
        val page = generation
        val id = ids.incrementAndGet()
        val waiter = CompletableDeferred<JsonElement>()
        pending[id] = waiter
        try {
            if (page != generation) throw PageException("page reloaded")
            logger?.invoke("call #$id $method")
            val name = json.encodeToString(JsonElement.serializer(), JsonPrimitive(method))
            host.evaluate("(window.MyspotiPage && window.__mspPageGen === $page) ? (window.MyspotiPage.call($id, $name, $args), true) : false")
            return withTimeoutOrNull(timeoutMs) { waiter.await() } ?: throw PageException("$method: no answer in ${timeoutMs / 1000} s")
        } finally {
            pending.remove(id)
        }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
    }
}
