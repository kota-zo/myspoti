package app.myspoti.extensions

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.mozilla.javascript.Callable
import org.mozilla.javascript.Context
import org.mozilla.javascript.ContextFactory
import org.mozilla.javascript.LambdaFunction
import org.mozilla.javascript.RhinoException
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptRuntime
import org.mozilla.javascript.ScriptableObject
import org.mozilla.javascript.Undefined
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** An extension that failed: its script threw, ran out of time, or answered something unusable. */
open class ExtensionException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The script ran past its time limit (a slow phone, a cold start), as opposed to an error of its own. */
class ExtensionTimeout(message: String, cause: Throwable? = null) : ExtensionException(message, cause)

/**
 * The script took the sandbox down (out of memory, a stack overflow, any other Error): the app
 * turns the extension off with this reason and keeps running. Not a failure to retry.
 */
class ExtensionFault(message: String, cause: Throwable? = null) : ExtensionException(message, cause)

/** The call never started: the extension was still busy with earlier ones. Not the extension's fault. */
class ExtensionBusy(message: String) : ExtensionException(message)

/**
 * One extension, running. Its script lives in its own Rhino scope on its own thread, sees only
 * `myspoti` (prelude.js) and the standard objects without Java, and every call it makes to the
 * app is checked here against the actions the user granted, a subset of what its manifest
 * declares ([Manifest.declared]). While a protected surface is on screen ([guard]), the hooks the
 * app draws (blocks, page, taps) answer nothing.
 *
 * Limits: [Limits.loadMs] to run its top level (compiling it is not counted), a per-call time limit given by the caller (counted in script
 * instructions, so a loop cannot hold the thread), [Limits.requestsPerMinute] network requests,
 * [Limits.storageChars] of storage, [Limits.spotifyCallsPerMinute] `myspoti.spotify` calls. A call that runs over is abandoned; the next one waits behind
 * it on the extension's thread, never on the app's.
 */
class ExtensionRuntime(
    val manifest: Manifest,
    private val script: String,
    private val host: Host,
    /** Ask keys the user accepted ([Ask.key]); keys of before actions are mapped ([Action.migrateGranted]). */
    grantedKeys: Set<String>,
    private val limits: Limits = Limits(),
    private val guard: SurfaceGuard = SurfaceGuard.app,
    private val raceChild: Boolean = false,
) : AutoCloseable {
    private val granted: Set<String> = Action.migrateGranted(grantedKeys)
    private val declared: Set<Action> = manifest.actionSet()

    /** Whether [a] is in the manifest and granted. */
    fun has(a: Action): Boolean = a in declared && (a.key in granted || (a == Action.NETWORK && Action.NETWORK_ANY in granted))

    /** How long a call may wait for its turn, on top of its own time (the longest hook is 30 s). */
    private val QUEUE_MS = 30_000L

    data class Limits(
        /** Running the script's top level, counted once it is compiled. */
        val loadMs: Long = 3_000,
        /** Compiling it: the app's work, not the script's, slow only on a busy phone (a cold start). */
        val compileMs: Long = 20_000,
        val requestsPerMinute: Int = 120,
        val spotifyCallsPerMinute: Int = 60,
        /** How long the user has to answer a confirmation; no answer is a refusal. */
        val confirmMs: Long = 120_000,
        /** The biggest answer a Spotify call gives the script. */
        val spotifyResultChars: Int = 2 * 1024 * 1024,
        val storageChars: Int = 512 * 1024,
        /** `myspoti.attest.sign` calls. */
        val attestSignsPerMinute: Int = 120,
        /** `myspoti.attest.enroll` calls: a new key each time, and a hardware keystore that is slow to make them. */
        val attestEnrollsPerHour: Int = 5,
    )

    private val id = manifest.id
    private val thread: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ext-$id").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 }
    }
    private var scope: ScriptableObject? = null
    @Volatile private var deadline = Long.MAX_VALUE
    private val requestTimes = ArrayDeque<Long>()
    private val spotifyTimes = ArrayDeque<Long>()
    private val notifyTimes = ArrayDeque<Long>()
    private val attestSignTimes = ArrayDeque<Long>()
    private val attestEnrollTimes = ArrayDeque<Long>()
    /** Time spent waiting for the user in the run on the thread now: it is not the script's time. */
    @Volatile private var activePause: Pause? = null

    /** The last failure, for the extension's page ("this extension failed: …"). */
    @Volatile var lastError: String? = null
        private set

    /** Loads the script. Throws [ExtensionException] when it does not load. */
    fun start() {
        // Compiling is not the script's time: on a phone busy starting the app it alone can take
        // seconds, and an extension was turned off for it. [Limits.loadMs] counts its top level only.
        val (prelude, main) = run(limits.compileMs) { cx ->
            cx.compileString(PRELUDE, "prelude.js", 1, null) to cx.compileString(script, manifest.main, 1, null)
        }
        run(limits.loadMs) { cx ->
            val s = cx.initSafeStandardObjects(null, false)
            ScriptableObject.putProperty(s, "__host", hostObject(cx, s))
            prelude.exec(cx, s)
            scope = s
            main.exec(cx, s)
            null
        }
    }

    /** Whether the user lets the script provide [hook] (its action declared and granted; `tap`: any drawn action; `status`: always). */
    fun provides(hook: Hook): Boolean = hook.free || (hook.action?.let(::has) ?: Action.BLOCKS.any(::has))

    /**
     * Calls the script's [hook] with [inputJson]; its answer as JSON text, or null when it has
     * none. Throws [ExtensionException] on failure or after [timeoutMs]. A drawn hook answers
     * nothing while a protected surface is on screen.
     */
    fun call(hook: Hook, inputJson: String, timeoutMs: Long): String? {
        faulted?.let { throw ExtensionFault("turned off: $it") }
        if (!provides(hook)) return null
        if (hook.drawn && guard.suspended) return null
        val out = run(timeoutMs) { cx -> dispatch(cx, "hook", hook.key, inputJson) }
        if (out != null) checkMedia(hook, out)
        return out
    }

    /** Set when the script crashed the sandbox ([ExtensionFault]): every later call is refused. */
    @Volatile var faulted: String? = null
        private set

    /**
     * The addresses a hook answers with must name hosts the extension declared: its `network`
     * hosts and its `media` hosts (audio and download addresses of an extension without `media`
     * keep the older rule: any public host). Images of blocks are always held to the list.
     */
    private fun checkMedia(hook: Hook, json: String) {
        val images = hook.action in Action.BLOCKS
        val sources = hook == Hook.AUDIO_SOURCE || hook == Hook.DOWNLOAD_SOURCE
        if (!images && !sources) return
        if (sources && manifest.media == null) return
        val tree = runCatching { ExtJson.parseToJsonElement(json) }.getOrNull() ?: return
        val allowed = manifest.hosts() + manifest.mediaHosts().orEmpty()
        val any = Action.NETWORK_ANY in granted && "*" in manifest.hosts() && Action.NETWORK in declared
        fun verify(url: String) {
            val h = runCatching { URI(url).host?.lowercase() }.getOrNull() ?: return
            if (any || allowed.any { it != "*" && hostMatches(it, h) }) return
            fail("${hook.key}: $h is not one of the hosts this extension declared (network hosts, or media.hosts in the manifest)", null)
        }
        fun walk(e: kotlinx.serialization.json.JsonElement, key: String?) {
            when (e) {
                is JsonObject -> e.forEach { (k, v) -> if (k != "headers") walk(v, k) }
                is JsonArray -> e.forEach { walk(it, key) }
                is JsonPrimitive -> if (e.isString) when {
                    key == "url" || key == "image" -> if (e.content.startsWith("http", ignoreCase = true)) verify(e.content)
                    key == "manifest" && sources -> runCatching { String(java.util.Base64.getMimeDecoder().decode(e.content)) }.getOrNull()
                        ?.takeIf { it.contains("<MPD") }
                        ?.let { mpd -> Regex("(?:initialization|media)=\"([^\"]+)\"").findAll(mpd).forEach { verify(it.groupValues[1].replace("&amp;", "&")) } }
                }
            }
        }
        walk(tree, null)
    }

    private val listening: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** Whether the script listens to [event] and may receive it (so the app can skip the work). */
    fun listens(event: String): Boolean = event in listening && EVENTS[event]?.let(::has) == true

    /**
     * Delivers an app event (the listeners' answers are ignored). One still waiting its turn after
     * [timeoutMs] (a long hook call held the extension's thread) is dropped quietly: not the
     * script's failure, nothing logged and no [lastError].
     */
    fun emit(event: String, inputJson: String, timeoutMs: Long = 3_000) {
        if (faulted != null) return
        if (EVENTS[event]?.let(::has) != true) return
        runCatching { run(timeoutMs, quietWhileQueued = true) { cx -> dispatch(cx, "event", event, inputJson) } }
    }

    /** Whether [close] ran: a call it cut short is not the script's failure. */
    val closed: Boolean get() = thread.isShutdown

    override fun close() {
        thread.shutdownNow()
        pool?.shutdownNow()
    }

    private fun dispatch(cx: Context, kind: String, name: String, input: String): String? {
        val s = scope ?: throw ExtensionException("not started")
        val fn = ScriptableObject.getProperty(s, "__dispatch") as Callable
        val out = fn.call(cx, s, s, arrayOf(kind, name, input))
        return if (out == null || out is Undefined) null else Context.toString(out)
    }

    /** [quietWhileQueued]: a run that never started within [timeoutMs] is dropped without a failure. */
    private fun <T> run(timeoutMs: Long, quietWhileQueued: Boolean = false, block: (Context) -> T): T {
        val pause = Pause()
        // The time limit counts from when the call runs: one waiting behind a slow call (a song
        // looked up for 15 s) has used none of its own time.
        val began = java.util.concurrent.atomic.AtomicLong(0)
        val task = thread.submit<T> {
            began.set(System.nanoTime())
            activePause = pause
            deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
            try {
                FACTORY.call { cx -> block(cx) }
            } catch (e: TimeLimit) {
                throw e
            } catch (e: Error) {
                // Out of memory, a stack overflow in the engine, anything else: the extension is
                // put out of service, the app goes on.
                throw ExtensionFault(
                    when (e) {
                        is OutOfMemoryError -> "ran out of memory"
                        is StackOverflowError -> "overflowed the stack"
                        else -> "crashed the sandbox (${e.javaClass.simpleName})"
                    }, e,
                )
            } finally {
                deadline = Long.MAX_VALUE
                activePause = null
            }
        }
        val queued = System.nanoTime()
        return try {
            // Still waiting for its turn after its own time plus the longest call: it is dropped.
            while (began.get() == 0L) {
                val waited = System.nanoTime() - queued
                // An event not run within its own time is dropped without a failure.
                if (quietWhileQueued && waited > TimeUnit.MILLISECONDS.toNanos(timeoutMs)) {
                    task.cancel(false)
                    if (began.get() == 0L) throw ExtensionException("dropped: the extension was busy")
                    break
                }
                if (waited > TimeUnit.MILLISECONDS.toNanos(timeoutMs + QUEUE_MS)) {
                    task.cancel(false)
                    if (began.get() == 0L) throw ExtensionBusy("busy with earlier calls: not run")
                    break
                }
                try { return task.get(10, TimeUnit.MILLISECONDS) } catch (_: TimeoutException) { /* not its turn yet */ }
            }
            await(task, timeoutMs, began.get(), pause)
        } catch (e: TimeoutException) {
            task.cancel(true)
            fail("took longer than $timeoutMs ms", e, timeout = true)
        } catch (e: java.util.concurrent.ExecutionException) {
            val c = e.cause
            when (c) {
                is TimeLimit -> fail("took longer than $timeoutMs ms", c, timeout = true)
                is ExtensionFault -> {
                    faulted = c.message
                    fail(c.message ?: "crashed", c, fault = true)
                }
                is RhinoException -> fail("${c.details()} (${c.sourceName()}:${c.lineNumber()})", c)
                is ExtensionException -> fail(c.message ?: "failed", c)
                else -> fail(c?.toString() ?: "failed", c)
            }
        }
    }

    /** Waits for [task] for [timeoutMs] (and a little more), plus as long as the user was asked something meanwhile. */
    private fun <T> await(task: java.util.concurrent.Future<T>, timeoutMs: Long, started: Long, pause: Pause): T {
        while (true) {
            val left = TimeUnit.MILLISECONDS.toNanos(timeoutMs + 500) + pause.nanos() - (System.nanoTime() - started)
            if (left <= 0) throw TimeoutException()
            try { return task.get(left, TimeUnit.NANOSECONDS) } catch (_: TimeoutException) { /* the pause may have grown: look again */ }
        }
    }

    private fun fail(message: String, cause: Throwable?, timeout: Boolean = false, fault: Boolean = false): Nothing {
        if (closed) throw ExtensionException("stopped", cause)
        lastError = message
        host.log(id, if (fault) "turned off: $message" else "failed: $message")
        throw if (fault) ExtensionFault(message, cause) else if (timeout) ExtensionTimeout(message, cause) else ExtensionException(message, cause)
    }

    // ── What the script can ask of the app ──

    private fun hostObject(cx: Context, s: Scriptable): Scriptable {
        val o = cx.newObject(s)
        fun def(name: String, length: Int, fn: (Array<Any?>) -> Any?) =
            ScriptableObject.putProperty(o, name, LambdaFunction(s, name, length) { _, _, _, args -> fn(args) ?: Undefined.instance })
        fun str(args: Array<Any?>, i: Int): String = args.getOrNull(i)?.takeUnless { it is Undefined }?.let { Context.toString(it) }.orEmpty()

        def("info", 0) { ExtJson.encodeToString(Info.serializer(), Info(manifest.id, manifest.name, manifest.version, has(Action.PLAYBACK_MIRROR), has(Action.ATTEST))) }
        def("mayProvide", 1) { a ->
            val key = str(a, 0)
            val hook = Hook.of(key) ?: throw error("unknown hook \"$key\" (known: ${Hook.entries.joinToString { it.key }})")
            val action = hook.action
            if (hook.free) return@def null
            if (action == null) {
                if (Action.BLOCKS.none { it in declared }) throw error("provide(\"tap\") needs an action drawn as blocks (${Action.BLOCKS.joinToString { it.key }})")
            } else if (action !in declared) throw error("provide(\"$key\") needs the ${action.key} action in the manifest")
            null
        }
        def("mayListen", 1) { a ->
            val event = str(a, 0)
            val action = EVENTS[event] ?: throw error("unknown event \"$event\" (known: ${EVENTS.keys.joinToString()})")
            if (action !in declared) throw error("on(\"$event\") needs the ${action.key} action")
            listening += event
            null
        }
        def("http", 1) { a -> http(str(a, 0)) }
        def("httpAll", 1) { a -> httpAll(str(a, 0)) }
        def("sourceRace", 3) { a -> sourceRace(str(a,0),str(a,1),str(a,2)) }
        def("storageGet", 1) { a -> need(Action.STORAGE); host.storage(id).get(str(a, 0)) }
        def("storageSet", 2) { a ->
            need(Action.STORAGE)
            val store = host.storage(id)
            val key = str(a, 0)
            val value = str(a, 1)
            val after = store.size() - (store.get(key)?.let { it.length + key.length } ?: 0) + key.length + value.length
            if (after > limits.storageChars) throw error("storage full (${limits.storageChars / 1024} KB)")
            store.set(key, value)
            null
        }
        def("storageRemove", 1) { a -> need(Action.STORAGE); host.storage(id).remove(str(a, 0)); null }
        def("setting", 1) { a ->
            val key = str(a, 0)
            val s = manifest.settings.firstOrNull { it.key == key }
            // A per-network setting: the value of the network the phone is on (one value, as any get).
            if (s == null) host.setting(id, key) else s.value({ host.setting(id, it) }, s.splitsByNetwork && host.metered())
        }
        def("nowPlaying", 0) { need(Action.PLAYBACK_EVENTS); host.nowPlaying() }
        def("control", 2) { a ->
            need(Action.PLAYBACK_CONTROL)
            val action = str(a, 0)
            if (action !in CONTROLS) throw error("unknown player action \"$action\"")
            val arg = (a.getOrNull(1) as? Number)?.toLong()?.takeIf { it >= 0 }
            host.control(id, action, arg)
        }
        def("notify", 1) { a ->
            need(Action.NOTIFY)
            // A few messages an hour: a toast is not a channel to nag the user with.
            if (!allowRequest(notifyTimes, MAX_NOTIFY_PER_HOUR, 3_600_000L)) throw error("notify: at most $MAX_NOTIFY_PER_HOUR messages an hour")
            host.notify(id, str(a, 0).take(200)); null
        }
        def("refresh", 1) { a ->
            val surface = Surface.entries.firstOrNull { it.key == str(a, 0) || it.key.substringBefore('.') == str(a, 0) }
                ?: throw error("ui.refresh: unknown place \"${str(a, 0)}\" (${Surface.entries.joinToString { it.key.substringBefore('.') }.split(", ").distinct().joinToString()})")
            need(surface.action)
            host.refresh(id, surface.key.substringBefore('.'))
            null
        }
        def("pagePost", 1) { a ->
            need(Action.WEBVIEW_PAGE)
            val json = str(a, 0)
            if (json.length > MAX_PAGE_MESSAGE) throw error("page.post: message over ${MAX_PAGE_MESSAGE / 1024} KB")
            if (!guard.suspended) host.postToPage(id, json)
            null
        }
        def("authOpen", 2) { a ->
            need(Action.WEBVIEW_AUTH)
            val request = try { AuthRequest(str(a, 0), str(a, 1), hidden = a.getOrNull(2) == true).checked() } catch (e: IllegalArgumentException) { throw error(e.message ?: "auth.open: refused") }
            runCatching { host.openAuth(id, request) }.getOrElse { throw error("auth.open: ${it.message ?: it.javaClass.simpleName}") }
        }
        def("attestEnroll", 1) { a -> attestEnroll(str(a, 0)) }
        def("attestPublicKey", 0) { attest().let { at -> ExtJson.encodeToString(String.serializer().nullable, at.publicKey(id)) } }
        def("attestSign", 1) { a -> attestSign(str(a, 0)) }
        def("attestReset", 0) { attest().reset(id); null }
        def("whenOnline", 0) { need(Action.NETWORK); host.whenOnline(id); null }
        def("log", 1) { a -> host.log(id, str(a, 0).take(2_000)); null }
        def("util", 2) { a -> util(str(a, 0), str(a, 1)) }
        def("spotifyMethods", 0) { ExtJson.encodeToString(METHOD_NAMES, SpotifyMethods.names()) }
        def("spotify", 3) { a -> spotify(str(a, 0), str(a, 1), str(a, 2)) }
        return o
    }

    private fun need(a: Action) {
        if (!has(a)) throw error(
            if (a in declared) "the user did not grant ${a.key}"
            else "${a.key} is not in this extension's manifest (actions)",
        )
    }

    /** The keys of `myspoti.attest`: the right is checked, and the host must have some. */
    private fun attest(): Attestation {
        need(Action.ATTEST)
        return host.attestation() ?: throw error("attest: this app cannot make hardware-backed keys here")
    }

    /** `attest.enroll(challenge)`: a new key, at most [Limits.attestEnrollsPerHour] an hour. */
    private fun attestEnroll(challenge: String): String {
        val at = attest()
        val bytes = challenge.toByteArray()
        if (bytes.isEmpty() || bytes.size > Attestation.MAX_CHALLENGE_BYTES) throw error("attest.enroll: the challenge is 1 to ${Attestation.MAX_CHALLENGE_BYTES} bytes")
        if (!allowRequest(attestEnrollTimes, limits.attestEnrollsPerHour, 3_600_000L)) throw error("attest.enroll: at most ${limits.attestEnrollsPerHour} an hour")
        val enrollment = runCatching { at.enroll(id, bytes) }.getOrElse { throw error("attest.enroll: ${it.message ?: it.javaClass.simpleName}") }
        return ExtJson.encodeToString(Enrollment.serializer(), enrollment)
    }

    /** `attest.sign(text)`: the signature of the UTF-8 text, [Limits.attestSignsPerMinute] a minute. */
    private fun attestSign(text: String): String {
        val at = attest()
        val bytes = text.toByteArray()
        if (bytes.size > Attestation.MAX_SIGN_BYTES) throw error("attest.sign: text over ${Attestation.MAX_SIGN_BYTES / 1024} KB")
        throttle(attestSignTimes, limits.attestSignsPerMinute, "attest.sign calls")
        return runCatching { at.sign(id, bytes) }.getOrElse { throw error("attest.sign: ${it.message ?: it.javaClass.simpleName}") }
    }

    private fun http(json: String): String {
        val r = runCatching { ExtJson.decodeFromString(HttpRequest.serializer(), json) }.getOrElse { throw error("http: unreadable request") }
        refusal(r)?.let { throw error(it) }
        throttle()
        val response = runCatching { host.http(id, r.copy(timeout = r.timeout.coerceAtMost(remainingMs()))) }
            .getOrElse { throw error("http: ${it.message ?: it.javaClass.simpleName}") }
        return ExtJson.encodeToString(HttpResponse.serializer(), response)
    }

    /** Why [r] may not be sent (its host, its scheme), or null. */
    private fun refusal(r: HttpRequest): String? {
        val uri = runCatching { URI(r.url) }.getOrElse { return "http: not a URL: ${r.url}" }
        val hostName = uri.host?.lowercase() ?: return "http: no host in ${r.url}"
        if (isPrivate(hostName)) return "http: $hostName is a local address"
        val listed = manifest.hosts()
        val anyHost = Action.NETWORK_ANY in granted && "*" in listed && Action.NETWORK in declared
        val named = listed.any { it != "*" && hostMatches(it, hostName) }
        if (!(anyHost || (Action.NETWORK.key in granted && Action.NETWORK in declared && named)))
            return "http: $hostName is not in this extension's network hosts"
        // Plain http only to a host named outright: never through a wildcard or "any host".
        val exact = listed.any { it == hostName }
        if (uri.scheme != "https" && !(uri.scheme == "http" && exact)) return "http: https only (or http to a host the manifest names exactly)"
        return null
    }

    /** For `http.all`: at most [MAX_PARALLEL] requests at a time, made only while the script waits. */
    @Volatile private var pool: ExecutorService? = null
    private fun parallel(): ExecutorService = pool ?: synchronized(this) {
        pool ?: Executors.newFixedThreadPool(MAX_PARALLEL) { r -> Thread(r, "ext-$id-http").apply { isDaemon = true } }.also { pool = it }
    }

    /**
     * `http.all([request, ...])`: up to [MAX_PARALLEL] requests sent together, each checked and
     * counted like one `http.request`; the answers in the same order, `{ error }` in place of one
     * that failed. The script waits for all of them, within its time limit.
     */
    private fun httpAll(json: String): String {
        val items = runCatching { ExtJson.parseToJsonElement(json) as JsonArray }.getOrElse { throw error("http.all: unreadable requests") }
        if (items.size > MAX_PARALLEL) throw error("http.all: at most $MAX_PARALLEL requests at once")
        val jobs = items.map { e ->
            val r = runCatching { ExtJson.decodeFromJsonElement(HttpRequest.serializer(), e) }.getOrNull()
                ?: return@map Result.failure<java.util.concurrent.Future<HttpResponse>>(IllegalArgumentException("http: unreadable request"))
            val why = refusal(r) ?: if (!allowRequest()) "more than ${limits.requestsPerMinute} requests in a minute" else null
            if (why != null) return@map Result.failure(IllegalArgumentException(why))
            val sent = r.copy(timeout = r.timeout.coerceAtMost(remainingMs()))
            Result.success(parallel().submit<HttpResponse> { host.http(id, sent) })
        }
        try {
            val answers = jobs.map { job ->
                job.mapCatching { f ->
                    try { f.get(leftMs(), TimeUnit.MILLISECONDS) } catch (e: java.util.concurrent.ExecutionException) { throw e.cause ?: e }
                }.fold(
                    { ExtJson.encodeToJsonElement(HttpResponse.serializer(), it) },
                    { e ->
                        if (e is TimeoutException || e is TimeLimit) throw TimeLimit()
                        if (e is InterruptedException) throw e
                        JsonObject(mapOf("error" to JsonPrimitive(e.message ?: e.javaClass.simpleName)))
                    },
                )
            }
            return JsonArray(answers).toString()
        } finally {
            jobs.forEach { it.getOrNull()?.cancel(true) }
        }
    }

    /** Independent source lanes: no shared Rhino scope, no recursive races or extra privileges. */
    private fun sourceRace(hookName: String, query: String, sources: String): String? {
        if (raceChild) throw error("sources.race: nested races are not allowed")
        val hook = Hook.of(hookName)?.takeIf { it == Hook.AUDIO_SOURCE || it == Hook.DOWNLOAD_SOURCE }
            ?: throw error("sources.race: audio-source or download-source only")
        hook.action?.let(::need)
        val input = ExtJson.parseToJsonElement(query) as? JsonObject ?: throw error("sources.race: query must be an object")
        val lanes = (ExtJson.parseToJsonElement(sources) as? JsonArray)?.map {
            (it as? JsonPrimitive)?.content ?: throw error("sources.race: invalid lane")
        }?.distinct() ?: throw error("sources.race: lanes must be an array")
        if (lanes.isEmpty() || lanes.size > 4 || lanes.any { !it.matches(Regex("[a-zA-Z0-9_-]{1,40}")) })
            throw error("sources.race: one to four named lanes")
        val until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(leftMs())
        val sharedHost = object : Host by host {
            override fun http(extensionId: String, request: HttpRequest): HttpResponse {
                refusal(request)?.let { throw IllegalArgumentException(it) }
                if (!allowRequest()) throw IllegalArgumentException("HTTP quota exceeded")
                return host.http(extensionId, request)
            }
            override fun notify(extensionId: String, text: String) {
                if (allowRequest(notifyTimes, MAX_NOTIFY_PER_HOUR, 3_600_000L)) host.notify(extensionId,text)
            }
        }
        val children = java.util.concurrent.ConcurrentLinkedQueue<ExtensionRuntime>()
        val completion = java.util.concurrent.ExecutorCompletionService<String?>(parallel())
        val keys = granted.intersect(setOf("download.source", "audio.source", "network", "network.any", "storage", "notify"))
        val jobs = lanes.map { lane -> completion.submit(java.util.concurrent.Callable {
            val child = ExtensionRuntime(manifest,script,sharedHost,keys,limits,guard,raceChild=true)
            children.add(child)
            try {
                if (Thread.currentThread().isInterrupted) return@Callable null
                child.start()
                val left = TimeUnit.NANOSECONDS.toMillis(until-System.nanoTime())
                if (left <= 0 || Thread.currentThread().isInterrupted) return@Callable null
                val out = child.call(hook,JsonObject(input + ("source" to JsonPrimitive(lane))).toString(),left)
                    ?: return@Callable null
                if (hook == Hook.DOWNLOAD_SOURCE) {
                    val files = DownloadFile.parseList(out)
                    val lossless = (input["quality"] as? JsonPrimitive)?.content != "small"
                    if (files.isEmpty() || (lossless && files.any { !it.lossless })) null else out
                } else if (AudioStream.parse(out) == null) null else out
            } catch (_: Exception) { null } finally { child.close() }
        }) }
        try {
            repeat(jobs.size) {
                val result = completion.poll(leftMs(), TimeUnit.MILLISECONDS)?.get()
                if (result != null) return result
            }
            return null
        } finally {
            jobs.forEach { it.cancel(true) }
            children.forEach { it.close() }
        }
    }

    private fun throttle(times: ArrayDeque<Long> = requestTimes, max: Int = limits.requestsPerMinute, what: String = "requests") {
        if (!allowRequest(times, max)) throw error("more than $max $what in a minute")
    }

    /** Counts one more call in [times], or false when [max] were made in the last minute. */
    private fun allowRequest(times: ArrayDeque<Long> = requestTimes, max: Int = limits.requestsPerMinute, windowMs: Long = 60_000): Boolean {
        val now = System.currentTimeMillis()
        synchronized(times) {
            while (times.isNotEmpty() && now - times.first() > windowMs) times.removeFirst()
            if (times.size >= max) return false
            times.addLast(now)
            return true
        }
    }

    private fun leftMs(): Long {
        val left = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
        if (left <= 0) throw TimeLimit()
        return left
    }

    private fun remainingMs(): Int = leftMs().coerceAtMost(20_000).toInt()

    /**
     * `myspoti.spotify.<area>.<method>(...)`: the method must be in [SpotifyMethods] and its right
     * granted; a destructive one runs only if the user says yes ([Host.confirm]). The script's
     * thread waits for the host, within the call's time limit.
     */
    private fun spotify(area: String, name: String, argsJson: String): String {
        val method = SpotifyMethods.find(area, name) ?: throw error("spotify.$area.$name: no such method")
        need(method.action)
        val args = SpotifyArgs(
            runCatching { ExtJson.parseToJsonElement(argsJson) as JsonArray }.getOrElse { throw error("spotify.${method.key}: unreadable arguments") },
            "spotify.${method.key}",
        )
        if (argsJson.length > MAX_SPOTIFY_ARGS) throw error("spotify.${method.key}: arguments over ${MAX_SPOTIFY_ARGS / 1024} KB")
        val destructive = try { method.isDestructive(args, host::spotifyMutation) } catch (e: IllegalArgumentException) { throw error(e.message ?: "bad arguments") }
        if (destructive && !mayWrite(method, args, argsJson)) throw error("spotify.${method.key}: the user refused")
        throttle(spotifyTimes, limits.spotifyCallsPerMinute, "Spotify calls")
        val result = try {
            runBlocking { withTimeout(leftMs()) { host.spotify(id, SpotifyCall(area, name, argsJson)) } }
        } catch (e: TimeoutCancellationException) {
            throw TimeLimit()
        } catch (e: InterruptedException) {
            throw e
        } catch (e: Exception) {
            val why = e.message ?: e.javaClass.simpleName
            throw error(if (why.startsWith("spotify.")) why else "spotify.${method.key}: $why")
        }
        if (result.length > limits.spotifyResultChars) throw error("spotify.${method.key}: answer over ${limits.spotifyResultChars / 1024 / 1024} MB")
        return result
    }

    /**
     * Whether a destructive call may run: asked each time, apart from a `raw.request` write one of
     * the user's [RawGrant]s covers; that question offers "always" for this kind of request.
     */
    private fun mayWrite(method: SpotifyMethod, args: SpotifyArgs, argsJson: String): Boolean {
        val text = "${manifest.name} wants to ${method.label}.\n${summary(argsJson)}"
        if (method.key != RAW_REQUEST) return confirm(text)
        val verb = RawRequest.check(args.str(0, "method"), args.str(1, "url"))
        val url = args.str(1, "url")
        val body = RawRequest.bodyText(args)
        if (RawRequest.covered(host.rawGrants(id), verb, url, body)) return true
        // Real operation shown; "always" only per distinct operation (none determinable: asked each time).
        val shown = RawRequest.operationOf(body)?.let { "$text\nOperation: $it" } ?: text
        val offer = RawRequest.offerFor(verb, url, body) ?: return confirm(shown)
        return paused { host.confirmWrite(id, shown, offer) }.let { it == Consent.ONCE || it == Consent.ALWAYS }
    }

    /** Asks the user through the host; the wait is not the script's time. */
    private fun confirm(text: String): Boolean = paused { host.confirm(id, text) } == true

    private fun <T> paused(ask: suspend () -> T): T? {
        val pause = activePause
        pause?.begin()
        val started = System.nanoTime()
        try {
            return try {
                runBlocking { withTimeout(limits.confirmMs) { ask() } }
            } catch (e: TimeoutCancellationException) {
                null
            }
        } finally {
            val waited = System.nanoTime() - started
            if (deadline != Long.MAX_VALUE) deadline += waited
            pause?.end(waited)
        }
    }

    private fun summary(argsJson: String) = argsJson.take(300) + if (argsJson.length > 300) "…" else ""

    /** The time a run spent waiting for the user: finished waits, and the one going on. */
    private class Pause {
        private val done = AtomicLong()
        @Volatile private var since = 0L
        fun begin() { since = System.nanoTime() }
        fun end(nanos: Long) { done.addAndGet(nanos); since = 0L }
        fun nanos(): Long = done.get() + since.let { if (it == 0L) 0L else System.nanoTime() - it }
    }

    private fun util(op: String, input: String): String = when (op) {
        "base64Encode" -> java.util.Base64.getEncoder().encodeToString(input.toByteArray())
        "base64Decode" -> runCatching { String(java.util.Base64.getDecoder().decode(input)) }.getOrElse { throw error("base64Decode: not base64") }
        "md5" -> hex(MessageDigest.getInstance("MD5").digest(input.toByteArray()))
        "sha1" -> hex(MessageDigest.getInstance("SHA-1").digest(input.toByteArray()))
        "sha256" -> hex(MessageDigest.getInstance("SHA-256").digest(input.toByteArray()))
        "hmacSha256" -> {
            val key = input.substringBefore('\u0000')
            val text = input.substringAfter('\u0000')
            val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key.toByteArray(), "HmacSHA256")) }
            hex(mac.doFinal(text.toByteArray()))
        }
        else -> throw error("unknown util $op")
    }

    @kotlinx.serialization.Serializable
    private data class Info(val id: String, val name: String, val version: String, val mirror: Boolean = false, val attest: Boolean = false)

    /** Thrown inside the script's thread when its time is up; not catchable by the script. */
    private class TimeLimit : Error("time limit")

    private class LimitedContext(f: ContextFactory) : Context(f)

    companion object {
        const val EVENT_TRACK = "track"
        /** Start, progress, pause, resume, seek, skip, end of a song (docs/extensions/API.md §3). */
        const val EVENT_PLAYBACK = "playback"
        /** A sign-in page ended ([AuthResult]). */
        const val EVENT_AUTH = "auth"
        /** The network is there after [Host.whenOnline]: the time to send what waited. */
        const val EVENT_ONLINE = "online"
        /**
         * One of its settings changed (`{ key }`), a button setting was pressed (`{ key, pressed: true }`),
         * or the network changed the value of a per-network setting (`{ key, network: true }`).
         */
        const val EVENT_SETTING = "setting"
        /** Each event, and the action an extension needs to receive it. */
        val EVENTS: Map<String, Action> = mapOf(
            EVENT_TRACK to Action.PLAYBACK_EVENTS,
            EVENT_PLAYBACK to Action.PLAYBACK_EVENTS,
            EVENT_AUTH to Action.WEBVIEW_AUTH,
            EVENT_ONLINE to Action.NETWORK,
            EVENT_SETTING to Action.SETTINGS_PAGE,
        )
        private const val RAW_REQUEST = "raw.request"
        private val METHOD_NAMES = MapSerializer(String.serializer(), ListSerializer(String.serializer()))
        private const val MAX_SPOTIFY_ARGS = 256 * 1024
        private const val MAX_PAGE_MESSAGE = 64 * 1024
        const val MAX_NOTIFY_PER_HOUR = 5
        /** `http.all`: the most requests sent together. */
        const val MAX_PARALLEL = 4
        private val CONTROLS = setOf("play", "pause", "next", "previous", "seek")

        private val PRELUDE: String = ExtensionRuntime::class.java.getResource("prelude.js")!!.readText()

        private val FACTORY = object : ContextFactory() {
            override fun makeContext(): Context = LimitedContext(this).apply {
                // Android cannot load generated classes, and instruction counting needs the interpreter.
                isInterpretedMode = true
                languageVersion = Context.VERSION_ES6
                instructionObserverThreshold = 5_000
                setMaximumInterpreterStackDepth(800)
                // No Java at all: no class can be named, built or reached from a script.
                setClassShutter { false }
            }

            override fun observeInstructionCount(cx: Context, instructionCount: Int) {
                val runtime = RUNNING.get() ?: return
                if (System.nanoTime() > runtime.deadline) throw TimeLimit()
            }
        }

        /** The runtime whose script runs on this thread, for the instruction observer. */
        private val RUNNING = ThreadLocal<ExtensionRuntime>()

        private fun error(message: String): RuntimeException = ScriptRuntime.constructError("Error", message)

        private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

        internal fun hostMatches(pattern: String, host: String): Boolean = when {
            pattern == "*" -> true
            pattern.startsWith("*.") -> host == pattern.removePrefix("*.") || host.endsWith(pattern.removePrefix("*"))
            else -> host == pattern
        }

        /** Whether [host] is a local address in any spelling (see [SafeNet]); the real check is made again when connecting. */
        internal fun isPrivate(host: String): Boolean = SafeNet.isPrivateHost(host)
    }

    init {
        // The instruction observer finds the runtime through its thread.
        thread.submit { RUNNING.set(this) }
    }
}
