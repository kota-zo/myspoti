package app.myspoti.extensions.cli

import app.myspoti.extensions.AudioQuery
import app.myspoti.extensions.AudioStream
import app.myspoti.extensions.BasicHttp
import app.myspoti.extensions.DownloadFile
import app.myspoti.extensions.DownloadQuery
import app.myspoti.extensions.ExtensionException
import app.myspoti.extensions.ExtensionRuntime
import app.myspoti.extensions.ExtensionStatus
import app.myspoti.extensions.Hook
import app.myspoti.extensions.HookBudgets
import app.myspoti.extensions.Host
import app.myspoti.extensions.HttpRequest
import app.myspoti.extensions.HttpResponse
import app.myspoti.extensions.LyricsResult
import app.myspoti.extensions.Manifest
import app.myspoti.extensions.MemoryStorage
import app.myspoti.extensions.Outcome
import app.myspoti.extensions.PlaybackEvent
import app.myspoti.extensions.Storage
import app.myspoti.extensions.TrackInfo
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException

/** The input the app gives [hook] for [track] ([opts]: `quality`, `ahead`, `query`, `kind`, `data`, `action`); null: `tap` without an action. */
internal fun hookInput(hook: Hook, track: TrackInfo, opts: Map<String, String>): String? = when (hook) {
    Hook.DOWNLOAD_SOURCE -> DownloadQuery.toJson(track, opts["quality"] ?: "cd")
    Hook.AUDIO_SOURCE -> AudioQuery.toJson(track, opts["ahead"] == "true")
    Hook.SEARCH -> buildJsonObject { put("query", opts["query"] ?: track.title) }.toString()
    Hook.MENU -> buildJsonObject {
        put("kind", opts["kind"] ?: "track"); put("uri", track.uri); put("title", track.title); put("subtitle", track.artist)
    }.toString()
    Hook.HOME, Hook.PAGE, Hook.STATUS -> "{}"
    Hook.MESSAGE -> opts["data"] ?: "null"
    Hook.TAP -> opts["action"]?.let { a ->
        buildJsonObject { put("action", a); put("surface", "home"); put("context", JsonObject(emptyMap())) }.toString()
    }
    else -> track.toJson()
}

/**
 * How the app reads [raw], [hook]'s answer: a stated `{ outcome }`, else found or not found as its
 * parser reads it. An answer the app cannot use is a [Outcome.Bug].
 */
internal fun classify(hook: Hook, raw: String?, name: String): Outcome = try {
    when {
        raw == null -> Outcome.NotFound
        else -> Outcome.parse(hook, raw) ?: when (hook) {
            Hook.AUDIO_SOURCE -> if (AudioStream.parse(raw) != null) Outcome.Found else Outcome.NotFound
            Hook.DOWNLOAD_SOURCE -> if (DownloadFile.parseList(raw).isNotEmpty()) Outcome.Found else Outcome.NotFound
            Hook.LYRICS -> if (LyricsResult.parse(raw, name) != null) Outcome.Found else Outcome.NotFound
            else -> Outcome.Found
        }
    }
} catch (e: ExtensionException) {
    Outcome.Bug(e.message)
}

/**
 * One request rule of a scenario: requests whose URL contains [match] (`*`: all) wait [latencyMs],
 * then fail as [dns] (no such host), [reset] (connection reset), or answer [status] (with
 * `Retry-After: `[retryAfter] s; 429 when only [retryAfter] is given), [headers] and [body].
 * [times]: how many requests it applies to (null: all of them).
 */
data class HttpRule(
    val match: String,
    val latencyMs: Long = 0,
    val status: Int? = null,
    val retryAfter: Long? = null,
    val headers: Map<String, String> = emptyMap(),
    val body: String = "",
    val dns: Boolean = false,
    val reset: Boolean = false,
    var times: Int? = null,
) {
    fun matches(url: String) = match == "*" || url.contains(match)
}

/**
 * A scenario file (`myspoti-ext test <dir> --scenario file.json`):
 * `{ settings?, track?, offline?, http?: [rule], steps: [step] }`. A step is one of
 * `{ hook, input?, track?, quality?, ahead?, cancelAtMs?, expect? }`, `{ event, input? }`,
 * `{ message, expect? }` (its web page posting to it), `{ http: [rule] }` (the rules from then on),
 * `{ wait: ms }` or `{ storage: { key: value|null } }` (what its storage holds). `expect`:
 * `{ outcome?, maxMs?, minMs?, contains?, posted? }`.
 */
class Scenario(val root: JsonObject) {
    val steps: List<JsonObject> = (root["steps"] as? JsonArray)?.map { it as? JsonObject ?: throw IllegalArgumentException("each step is an object") }
        ?: throw IllegalArgumentException("a scenario has \"steps\": [ … ]")
    val offline: Boolean = (root["offline"] as? JsonPrimitive)?.booleanOrNull ?: false
    val settings: Map<String, String> = (root["settings"] as? JsonObject).orEmpty().mapValues { it.value.toString() }
    val track: TrackInfo = trackOf(root["track"] as? JsonObject, null)

    companion object {
        fun parse(text: String) = Scenario(Json.parseToJsonElement(text).jsonObject)

        fun rules(e: JsonElement?): List<HttpRule> = (e as? JsonArray).orEmpty().map { r ->
            val o = r.jsonObject
            fun s(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
            fun l(k: String) = (o[k] as? JsonPrimitive)?.longOrNull
            fun b(k: String) = (o[k] as? JsonPrimitive)?.booleanOrNull ?: false
            HttpRule(
                match = s("match") ?: "*", latencyMs = l("latencyMs") ?: 0, status = (o["status"] as? JsonPrimitive)?.intOrNull,
                retryAfter = l("retryAfter"), headers = (o["headers"] as? JsonObject).orEmpty().mapValues { (it.value as? JsonPrimitive)?.contentOrNull.orEmpty() },
                body = o["body"]?.let { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: it.toString() } ?: "",
                dns = b("dns"), reset = b("reset"), times = (o["times"] as? JsonPrimitive)?.intOrNull,
            )
        }

        fun trackOf(o: JsonObject?, base: TrackInfo?): TrackInfo {
            fun s(k: String) = (o?.get(k) as? JsonPrimitive)?.contentOrNull
            val artist = s("artist") ?: base?.artists?.joinToString(",") ?: "Artist"
            val id = s("id") ?: base?.id ?: "4uLU6hMCjMI75M1A2tKUQC"
            return TrackInfo(
                id = id, uri = if (id.isEmpty()) "" else "spotify:track:$id", title = s("title") ?: base?.title ?: "Sample",
                artist = artist.substringBefore(",").trim(), artists = artist.split(",").map { it.trim() }.filter { it.isNotEmpty() },
                album = s("album") ?: base?.album ?: "Album", durationMs = s("durationMs")?.toLongOrNull() ?: base?.durationMs ?: 200_000,
                isrc = s("isrc") ?: base?.isrc,
            )
        }
    }
}

/**
 * The runner's [Host]: requests go through the scenario's [rules] (latency, 429 with Retry-After,
 * 5xx, a DNS failure, a reset), the others to the network ([offline]: none, as a DNS failure);
 * storage is kept across steps and in [stateFile] across runs; what it posts to its page is kept.
 */
class FaultHost(
    @Volatile var rules: List<HttpRule>,
    private val offline: Boolean,
    private val settings: Map<String, String>,
    private val track: TrackInfo?,
    private val stateFile: File?,
    private val echo: (String) -> Unit = { System.err.println(it) },
) : Host {
    private val store = MemoryStorage()
    val posted = java.util.concurrent.CopyOnWriteArrayList<String>()
    val requests = java.util.concurrent.CopyOnWriteArrayList<String>()

    init {
        stateFile?.takeIf { it.isFile }?.let { f ->
            Json.parseToJsonElement(f.readText()).jsonObject.forEach { (k, v) -> store.set(k, v.toString()) }
        }
    }

    /** Writes the storage to [stateFile] (one JSON object). */
    fun saveState() {
        val f = stateFile ?: return
        f.absoluteFile.parentFile?.mkdirs()
        f.writeText(JsonObject(store.snapshot().mapValues { runCatching { Json.parseToJsonElement(it.value) }.getOrElse { _ -> JsonNull } }).toString())
    }

    fun stored(key: String): String? = store.get(key)

    override fun http(extensionId: String, request: HttpRequest): HttpResponse {
        requests += "${request.method} ${request.url}"
        val rule = synchronized(this) {
            rules.firstOrNull { it.matches(request.url) && (it.times ?: 1) > 0 }?.also { r -> r.times = r.times?.minus(1) }
        }
        val host = runCatching { URI(request.url).host }.getOrNull() ?: request.url
        if (rule == null) {
            if (offline) throw UnknownHostException("$host (offline scenario: no rule for it)")
            return BasicHttp.send(request, userAgent(extensionId))
        }
        if (rule.latencyMs > 0) {
            if (rule.latencyMs >= request.timeout) { Thread.sleep(request.timeout.toLong()); throw SocketTimeoutException("timeout after ${request.timeout} ms") }
            Thread.sleep(rule.latencyMs)
        }
        if (rule.dns) throw UnknownHostException("$host: no such host")
        if (rule.reset) throw IOException("connection reset by $host")
        val status = rule.status ?: if (rule.retryAfter != null) 429 else 200
        val headers = rule.headers + (rule.retryAfter?.let { mapOf("Retry-After" to it.toString()) } ?: emptyMap())
        return HttpResponse(status, headers, rule.body)
    }

    override fun log(extensionId: String, message: String) = echo("[$extensionId] $message")
    override fun storage(extensionId: String): Storage = store
    override fun setting(extensionId: String, key: String): String? = settings[key]
    override fun nowPlaying(): String? = track?.toJson()
    override fun postToPage(extensionId: String, json: String) { posted += json }
    override fun notify(extensionId: String, text: String) = echo("[$extensionId] notify: $text")
}

/** One step's result: [ok] false with [problems] when an expectation failed. */
data class StepResult(val index: Int, val what: String, val outcome: String?, val ms: Long, val problems: List<String>) {
    val ok: Boolean get() = problems.isEmpty()
    override fun toString(): String = "${if (ok) "✓" else "✗"} $index $what" + (outcome?.let { ": $it" } ?: "") + " ($ms ms)" +
        problems.joinToString("") { "\n    $it" }
}

/** Runs [scenario] on the extension [manifest] / [script], every hook within the app's [HookBudgets]. */
class ScenarioRunner(
    private val manifest: Manifest,
    private val script: String,
    private val scenario: Scenario,
    private val stateFile: File? = null,
    private val echo: (String) -> Unit = { System.err.println(it) },
) {
    fun run(): List<StepResult> {
        val host = FaultHost(Scenario.rules(scenario.root["http"]), scenario.offline, scenario.settings, scenario.track, stateFile, echo)
        val out = ArrayList<StepResult>()
        try {
            ExtensionRuntime(manifest, script, host, manifest.asks().map { it.key }.toSet()).use { rt ->
                rt.start()
                scenario.steps.forEachIndexed { i, step -> out += step(i + 1, step, rt, host) }
            }
        } finally {
            host.saveState()
        }
        return out
    }

    private fun step(index: Int, s: JsonObject, rt: ExtensionRuntime, host: FaultHost): StepResult {
        fun str(k: String) = (s[k] as? JsonPrimitive)?.contentOrNull
        val expect = s["expect"] as? JsonObject
        val t0 = System.currentTimeMillis()
        fun ms() = System.currentTimeMillis() - t0
        when {
            s["hook"] != null -> {
                val name = str("hook").orEmpty()
                val hook = Hook.of(name) ?: return StepResult(index, "hook $name", null, 0, listOf("unknown hook (${Hook.entries.joinToString { it.key }})"))
                if (!rt.provides(hook)) return StepResult(index, "hook $name", null, 0, listOf("this extension does not provide $name"))
                val track = Scenario.trackOf(s["track"] as? JsonObject, scenario.track)
                val opts = buildMap {
                    str("quality")?.let { put("quality", it) }; str("ahead")?.let { put("ahead", it) }
                    s["data"]?.let { put("data", it.toString()) }; str("action")?.let { put("action", it) }; str("query")?.let { put("query", it) }
                }
                val input = s["input"]?.toString() ?: hookInput(hook, track, opts) ?: return StepResult(index, "hook $name", null, 0, listOf("tap needs \"action\""))
                val cancelAt = (s["cancelAtMs"] as? JsonPrimitive)?.longOrNull
                val budget = HookBudgets.of(hook).let { b -> cancelAt?.coerceAtMost(b) ?: b }
                if (hook == Hook.STATUS) {
                    val st = ExtensionStatus.probe(rt, budget)
                    return check(index, "status", st.state, ms(), expect, null, host)
                }
                var raw: String? = null
                val outcome = try {
                    raw = rt.call(hook, input, budget)
                    classify(hook, raw, manifest.name)
                } catch (e: ExtensionException) {
                    Outcome.of(e)
                }
                // Ended at the cancel time: its deadline, or a request cut by it.
                val shown = if (cancelAt != null && outcome is Outcome.Network) "cancelled" else outcome.key
                val elapsed = ms()
                val problems = ArrayList<String>()
                if (elapsed > HookBudgets.of(hook) + 500) problems += "took $elapsed ms, over the app's ${HookBudgets.of(hook)} ms for $name"
                return check(index, "hook $name", shown, elapsed, expect, raw, host, problems, outcome)
            }
            s["event"] != null -> {
                val event = str("event").orEmpty()
                val input = s["input"]?.toString() ?: when (event) {
                    ExtensionRuntime.EVENT_TRACK -> PlaybackEvent.trackEventJson(scenario.track, PlaybackEvent.ORIGIN_SPOTIFY, null)
                    else -> "{}"
                }
                rt.emit(event, input, HookBudgets.ofEvent(event))
                return check(index, "event $event", null, ms(), expect, null, host)
            }
            s["message"] != null -> {
                val before = host.posted.size
                val outcome = try {
                    rt.call(Hook.MESSAGE, s["message"].toString(), HookBudgets.BLOCKS)?.let { host.posted += it }
                    Outcome.Found
                } catch (e: ExtensionException) {
                    Outcome.of(e)
                }
                val reply = host.posted.drop(before).joinToString("\n")
                return check(index, "message", outcome.key, ms(), expect, reply, host, posted = host.posted.size - before)
            }
            s["http"] != null -> {
                host.rules = Scenario.rules(s["http"])
                return StepResult(index, "http rules: ${host.rules.size}", null, 0, emptyList())
            }
            s["wait"] != null -> {
                Thread.sleep((s["wait"] as? JsonPrimitive)?.longOrNull ?: 0)
                return StepResult(index, "wait", null, ms(), emptyList())
            }
            s["storage"] != null -> {
                val problems = (s["storage"] as? JsonObject).orEmpty().mapNotNull { (k, v) ->
                    val have = host.stored(k)?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() } ?: JsonNull
                    if (have == v) null else "storage \"$k\": expected $v, has $have"
                }
                return StepResult(index, "storage", null, 0, problems)
            }
            else -> return StepResult(index, "?", null, 0, listOf("a step has hook, event, message, http, wait or storage"))
        }
    }

    private fun check(
        index: Int, what: String, outcome: String?, ms: Long, expect: JsonObject?, answer: String?, host: FaultHost,
        problems: MutableList<String> = ArrayList(), stated: Outcome? = null, posted: Int? = null,
    ): StepResult {
        if (expect != null) {
            fun l(k: String) = (expect[k] as? JsonPrimitive)?.longOrNull
            (expect["outcome"] as? JsonPrimitive)?.contentOrNull?.let { want ->
                if (want != outcome) problems += "expected $want, got $outcome${stated?.detail?.let { " ($it)" } ?: ""}"
            }
            l("maxMs")?.let { if (ms > it) problems += "took $ms ms, more than $it" }
            l("minMs")?.let { if (ms < it) problems += "took $ms ms, less than $it" }
            (expect["contains"] as? JsonPrimitive)?.contentOrNull?.let { if (answer?.contains(it) != true) problems += "the answer does not contain \"$it\"" }
            l("posted")?.let { want -> if (posted != null && posted.toLong() != want) problems += "posted $posted message(s) to the page, expected $want" }
            l("retryAfterMs")?.let { want -> if ((stated as? Outcome.RateLimited)?.retryAfterMs != want) problems += "retryAfterMs: expected $want, got ${(stated as? Outcome.RateLimited)?.retryAfterMs}" }
        }
        return StepResult(index, what, outcome?.let { o -> stated?.let { it.describe().replaceFirst(it.key, o) } ?: o }, ms, problems)
    }
}
