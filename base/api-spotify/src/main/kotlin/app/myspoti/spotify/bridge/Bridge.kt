package app.myspoti.spotify.bridge

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
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

class BridgeException(message: String) : Exception(message)

/** Request/response over a [BridgeHost], plus the page's pushed events. */
class Bridge(private val host: BridgeHost, scope: CoroutineScope) {
    private val ids = AtomicInteger(0)

    /** Diagnostics: receives one line per call, result and page message. */
    @Volatile var logger: ((String) -> Unit)? = null
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<JsonElement>>()

    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready

    /** `state` and `queue` messages, raw payload of each. DROP_OLDEST: under a stall the
     * newest state is the only one that matters; SUSPEND would drop exactly that one. */
    private val _events = MutableSharedFlow<Pair<String, JsonElement>>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events: SharedFlow<Pair<String, JsonElement>> = _events

    init {
        scope.launch {
            // A message the bridge did not write (another script, a future page) must never
            // end this collector: it runs on the main thread, where a throw kills the app.
            host.messages.collect { raw ->
                runCatching { handle(raw) }.onFailure { logger?.invoke("page message dropped: ${it.message}") }
            }
        }
    }

    /** The last track the page's state named, to count the songs it started. */
    private var lastTrack: String? = null
    private var lastStateLine: String? = null
    private var lastStateKey: String? = null
    private var lastStateAt = 0L

    /**
     * The page posts its state several times a second while a song buffers (a "page state" line
     * was the most frequent of every report, 755 in the Discord attachments). Logged when the song
     * or paused changes; a buffering flip of the same song at most every [STATE_EVERY_MS].
     */
    private fun stateWorthLogging(line: String, key: String): Boolean {
        val now = System.currentTimeMillis()
        if (line == lastStateLine) return false
        val changed = key != lastStateKey
        if (!changed && now - lastStateAt < STATE_EVERY_MS) return false
        lastStateLine = line; lastStateKey = key; lastStateAt = now
        return true
    }

    /**
     * The page's resource notes: only failed ones are logged (status 0 is "not readable", left
     * out too unless it is a command). Every audio licence answer goes to the 402 summary's counts
     * instead of the log (it came three times per answer: page net, http, licence answer).
     */
    private fun netWorthLogging(msg: JsonObject): Boolean {
        val url = (msg["url"] as? JsonPrimitive)?.contentOrNull ?: return true
        val status = (msg["status"] as? JsonPrimitive)?.intOrNull ?: 0
        if ("widevine-license" in url) return false
        return status >= 400 || (status == 0 && "player/command" in url)
    }

    private fun licenceNote(msg: JsonObject) {
        val url = (msg["url"] as? JsonPrimitive)?.contentOrNull ?: return
        if ("widevine-license" !in url) return
        app.myspoti.spotify.report.Refusal402.licence((msg["status"] as? JsonPrimitive)?.intOrNull ?: 0)
    }

    private fun handle(raw: String) {
        val msg = runCatching { json.parseToJsonElement(raw) }.getOrNull() as? JsonObject ?: return
        // Lenient reads: a field of an unexpected type counts as missing.
        fun field(name: String) = msg[name] as? JsonPrimitive
        val type = field("type")?.contentOrNull
        if (type == "state") {
            val track = ((msg["state"] as? JsonObject)?.get("track") as? JsonObject)?.get("name")?.toString()
            if (track != null && track != lastTrack) { lastTrack = track; app.myspoti.spotify.report.Refusal402.song() }
        }
        if (type == "ready") app.myspoti.spotify.report.Refusal402.pageReady()
        if (type == "net") licenceNote(msg)
        logger?.let { log ->
            if (type == "state") {
                val s = msg["state"] as? JsonObject
                val line = "page state: " + (s?.get("track") as? JsonObject)?.get("name") + " paused=" + s?.get("isPaused") + " buffering=" + s?.get("isBuffering")
                if (stateWorthLogging(line, s?.get("track")?.toString() + s?.get("isPaused"))) log(line)
            } else if (type == "result") {
                // Results are logged by call(); never write their values (tokens) here.
            } else if (type == "net" && !netWorthLogging(msg)) {
                // A playback request that went through, or a licence answer (counted in the 402 summary).
            } else if (type != "queue") log("page $type: " + raw.take(300))
        }
        when (type) {
            "ready" -> { gone = false; _ready.value = true }
            "result" -> {
                val id = field("id")?.intOrNull ?: return
                val waiter = pending.remove(id) ?: return
                if (field("ok")?.booleanOrNull == true) waiter.complete(msg["value"] ?: JsonNull)
                else waiter.completeExceptionally(BridgeException(field("error")?.contentOrNull ?: "bridge error"))
            }
            "state" -> _events.tryEmit("state" to (msg["state"] ?: JsonNull))
            "queue" -> _events.tryEmit("queue" to (msg["queue"] ?: JsonNull))
            // Spotify refused a playback command (HTTP 402/403 on Connect), see ControlRelay.
            "refused" -> _events.tryEmit("refused" to msg)
            // The page's EME probe (see player-bridge.js): whether Widevine works in this WebView.
            "drm" -> _events.tryEmit("drm" to msg)
            "error" -> _ready.value = false
        }
    }

    /** Injects the bridge into the current page. Hosts call this after each page load. */
    suspend fun inject() {
        // onPageFinished can fire twice for one page load (a redirect settling): a second
        // injection into the SAME page must not announce "ready" again, or every ready
        // collector (state refresh, web token, audio quality, volume) runs twice. The page
        // re-announces only when the host is not ready (a page it thought it had lost).
        host.evaluate("window.__mspReannounce = ${!_ready.value}; ${BridgeScript.source}")
    }

    /**
     * The page is going away (a reload or a new load started): not ready until the bridge is
     * injected again, and the calls still waiting fail now instead of each timing out.
     */
    fun pageGone() {
        pagesGone++
        gone = true
        PageDevice.pageGone()
        _ready.value = false
        pending.values.forEach { it.completeExceptionally(BridgeException("page reloaded")) }
    }

    /** How many pages went away ([pageGone]): a reload waits for this to move, not for "ready" alone. */
    @Volatile var pagesGone = 0
        private set

    /** Between [pageGone] and the new page's "ready". */
    @Volatile private var gone = false

    /** When a call last timed out (0: never). A frozen page times out every call. */
    @Volatile var lastTimeoutAt = 0L
        private set

    /**
     * True when the page answers a trivial call within [timeoutMs]. `bridge.version` is
     * synchronous in the page: no answer means the page itself is frozen, not the player busy.
     */
    suspend fun ping(timeoutMs: Long = 3_000): Boolean = runCatching { call("bridge.version", timeoutMs = timeoutMs) }.isSuccess

    /** [quiet]: a poll made every few seconds; only its failures are logged. */
    suspend fun call(method: String, args: JsonObject = JsonObject(emptyMap()), timeoutMs: Long = 20_000, quiet: Boolean = false): JsonElement {
        // The page is reloading: nothing would answer, the call would only wait for its timeout.
        if (gone) throw BridgeException("not ready")
        val id = ids.incrementAndGet()
        val waiter = CompletableDeferred<JsonElement>()
        pending[id] = waiter
        try {
            val started = System.currentTimeMillis()
            if (!quiet) logger?.invoke("call #$id $method ${args.toString().take(200)}")
            host.evaluate("window.Myspoti && window.Myspoti.call($id, ${json.encodeToString(JsonElement.serializer(), kotlinx.serialization.json.JsonPrimitive(method))}, $args).catch(() => {})")
            return try {
                // withTimeoutOrNull, not withTimeout: a timeout must surface as a
                // BridgeException, not as a CancellationException — callers in plain
                // launch {} blocks would otherwise end silently, indistinguishable
                // from the caller itself being cancelled (which still throws).
                val result = withTimeoutOrNull(timeoutMs) { waiter.await() }
                    ?: throw BridgeException("timeout after $timeoutMs ms: $method")
                result.also {
                    if (quiet) return@also
                    val shown = if (method == "token.get") "(token hidden)" else it.toString().take(200)
                    logger?.invoke("call #$id $method ok in ${System.currentTimeMillis() - started} ms: $shown")
                }
            } catch (e: Exception) {
                if (e is BridgeException && e.message?.startsWith("timeout") == true) lastTimeoutAt = System.currentTimeMillis()
                logger?.invoke("call #$id $method${if (quiet) " " + args.toString().take(100) else ""} FAILED after ${System.currentTimeMillis() - started} ms: ${e.message}")
                throw e
            }
        } finally {
            pending.remove(id)
        }
    }

    companion object {
        val json = Json { ignoreUnknownKeys = true }

        /** A buffering flip of the same song is logged at most this often (see stateWorthLogging). */
        const val STATE_EVERY_MS = 5_000L
    }
}

/** Calls `service.method(...args)` on one of the page's services and returns its JSON copy. */
suspend fun Bridge.service(service: String, method: String, vararg args: JsonElement): JsonElement =
    call("service.call", kotlinx.serialization.json.buildJsonObject {
        put("service", kotlinx.serialization.json.JsonPrimitive(service))
        put("method", kotlinx.serialization.json.JsonPrimitive(method))
        put("args", kotlinx.serialization.json.JsonArray(args.toList()))
    })
