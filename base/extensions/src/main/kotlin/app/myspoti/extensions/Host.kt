package app.myspoti.extensions

import kotlinx.serialization.Serializable
import java.io.ByteArrayOutputStream
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * What the app gives the sandbox. Every call arrives here already checked against the rights the
 * user granted (see [ExtensionRuntime]): an implementation only does the work.
 */
interface Host {
    /** A request to one of the extension's hosts, without cookies or any account. */
    fun http(extensionId: String, request: HttpRequest): HttpResponse = BasicHttp.send(request, userAgent(extensionId))

    fun log(extensionId: String, message: String)

    /** The extension's own small store (key → JSON text). */
    fun storage(extensionId: String): Storage

    /** The user's value for one of the extension's settings, as JSON text, or null when unset. */
    fun setting(extensionId: String, key: String): String?

    /** The song playing now, as the hooks receive a track (JSON), or null. */
    fun nowPlaying(): String? = null

    /**
     * play, pause, next, previous, seek (argument: milliseconds), asked by [extensionId]. False when
     * it could not be done, or the app's [PlaybackArbiter] dropped it.
     */
    fun control(extensionId: String, action: String, argument: Long?): Boolean = false

    fun notify(extensionId: String, text: String) = log(extensionId, "notify: $text")

    /** The extension's blocks on [surface] (home, search, menu, player, trackinfo) changed: ask it again when shown. */
    fun refresh(extensionId: String, surface: String) {}

    /** A message from the extension to its own web page, if it is open (JSON text). */
    fun postToPage(extensionId: String, json: String) {}

    /**
     * Runs one `myspoti.spotify` call as the signed-in account and answers its result as JSON.
     * The method is known and the right granted already; [SpotifyDispatcher] does the work over a
     * [app.myspoti.api.MyspotiApi]. Suspends: the script's thread waits, within its time limit.
     */
    suspend fun spotify(extensionId: String, call: SpotifyCall): String = throw UnsupportedOperationException("this app has no Spotify account for extensions")

    /** Whether the raw GraphQL operation [name] changes something. Unknown to the host: it does. */
    fun spotifyMutation(name: String): Boolean = true

    /**
     * Asks the user to allow a destructive change an extension is about to make ([text] says what,
     * in plain words). False refuses it, which is also what a host without a screen answers.
     */
    suspend fun confirm(extensionId: String, text: String): Boolean = false

    /** The writes the user allowed [extensionId] for good on `raw.request` ([RawGrant]). */
    fun rawGrants(extensionId: String): List<RawGrant> = emptyList()

    /**
     * Like [confirm], for a `raw.request` write, with a third answer: always allow [offer] to this
     * extension. On [Consent.ALWAYS] the host keeps [offer] and lists it in the extension's rights,
     * where the user revokes it. A host that cannot keep grants asks a plain [confirm].
     */
    suspend fun confirmWrite(extensionId: String, text: String, offer: RawGrant): Consent =
        if (confirm(extensionId, text)) Consent.ONCE else Consent.REFUSE

    /**
     * Opens a sign-in page for [extensionId] ([Action.WEBVIEW_AUTH], [AuthRequest] checked) and
     * returns at once with its id; the outcome comes later as the `auth` event ([AuthResult]).
     * The page runs in a web view of its own: its cookies are never the app's, never Spotify's,
     * never given to the extension. Throws when it cannot be opened now.
     */
    fun openAuth(extensionId: String, request: AuthRequest): String = throw UnsupportedOperationException("this app has no sign-in pages for extensions")

    /**
     * Asks for the `online` event once the device has a network ([Action.NETWORK]): at once when
     * it has one, else when it comes back, also after the app was restarted. Asking again before
     * it fires changes nothing.
     */
    fun whenOnline(extensionId: String) {}

    /** The keys behind `myspoti.attest` ([Action.ATTEST]); null: this host cannot make any, and the script gets an error. */
    fun attestation(): Attestation? = null

    fun userAgent(extensionId: String) = "Myspoti-Extension/$API_VERSION ($extensionId)"
}

interface Storage {
    fun get(key: String): String?
    fun set(key: String, json: String)
    fun remove(key: String)
    /** Characters stored, to keep the store small. */
    fun size(): Int
}

/** A store in memory, for tests and the desktop runner. */
class MemoryStorage : Storage {
    private val values = java.util.concurrent.ConcurrentHashMap<String, String>()
    override fun get(key: String) = values[key]
    override fun set(key: String, json: String) { values[key] = json }
    override fun remove(key: String) { values.remove(key) }
    override fun size() = values.entries.sumOf { it.key.length + it.value.length }
    fun snapshot(): Map<String, String> = values.toMap()
}

/**
 * `myspoti.auth.open({ url, callback, hidden? })`: where the sign-in starts, and the address it ends
 * on. [hidden]: the page first runs out of sight (a check that passes by itself needs no screen) and
 * is shown only if it has not reached the callback after [Companion.HIDDEN_MS].
 */
@Serializable
data class AuthRequest(val url: String, val callback: String, val hidden: Boolean = false) {
    /** This request, or why it is refused. */
    fun checked(): AuthRequest {
        val start = runCatching { URI(url) }.getOrNull() ?: throw IllegalArgumentException("auth.open: not a URL: $url")
        require(start.scheme == "https" && !start.host.isNullOrEmpty()) { "auth.open: the url must be https" }
        require(!RawRequest.isSpotifyHost(start.host.lowercase())) { "auth.open: not on a Spotify address" }
        val end = runCatching { URI(callback) }.getOrNull() ?: throw IllegalArgumentException("auth.open: not a URL: $callback")
        val scheme = end.scheme?.lowercase() ?: throw IllegalArgumentException("auth.open: the callback needs a scheme")
        require(scheme !in UNSAFE_SCHEMES) { "auth.open: a $scheme: callback is not allowed" }
        if (scheme == "https") require(!end.host.isNullOrEmpty() && !RawRequest.isSpotifyHost(end.host.lowercase())) { "auth.open: the callback must name a host that is not Spotify's" }
        else require(scheme.matches(Regex("[a-z][a-z0-9+.-]{1,40}"))) { "auth.open: the callback is https or a custom scheme (myextension://done)" }
        require(url.length <= 8_192 && callback.length <= 1_024) { "auth.open: address too long" }
        return this
    }

    /** Whether the page reached the callback: [address] starts with it (its query and fragment are the answer). */
    fun reached(address: String): Boolean = address.startsWith(callback, ignoreCase = false) &&
        address.length.let { it == callback.length || address[callback.length] in "?#/&" || callback.last() in "?#/&=" }

    companion object {
        /** How long a hidden sign-in runs out of sight before it is shown. */
        const val HIDDEN_MS = 8_000L
        /** A sign-in that has not reached its callback by then ends with the error "timeout". */
        const val TIMEOUT_MS = 180_000L
        private val UNSAFE_SCHEMES = setOf("http", "javascript", "data", "file", "content", "intent", "about", "blob", "filesystem", "ws", "wss")
    }
}

/** The `auth` event: `{ id, url }` when the callback was reached (with its query), `{ id, error }` otherwise. */
@Serializable
data class AuthResult(val id: String, val url: String? = null, val error: String? = null)

@Serializable
data class HttpRequest(
    val url: String,
    val method: String = "GET",
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
    /** Milliseconds; capped by the sandbox. */
    val timeout: Int = 15_000,
)

@Serializable
data class HttpResponse(val status: Int, val headers: Map<String, String>, val body: String)

/**
 * Plain HTTP for extensions (OkHttp): no cookies, no cache, no redirect followed (the script sees
 * the 3xx), a size cap, and never a connection to a local address: the names are resolved and
 * checked by [SafeNet.SafeDns] when the connection is made, IP literals by an interceptor.
 */
object BasicHttp {
    const val MAX_BODY = 5 * 1024 * 1024
    private val METHODS = setOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD")
    /** Set by the transport itself: an extension cannot pretend to be another client this way. */
    private val FORBIDDEN_HEADERS = setOf("host", "content-length", "connection", "cookie", "transfer-encoding", "proxy-authorization", "upgrade")

    fun send(request: HttpRequest, userAgent: String, client: okhttp3.OkHttpClient = SafeNet.client): HttpResponse {
        val method = request.method.uppercase()
        require(method in METHODS) { "method $method is not allowed" }
        val url = request.url.toHttpUrlOrNull() ?: throw IllegalArgumentException("not a URL: ${request.url}")
        SafeNet.checkUrlHost(url.host)
        val timeout = request.timeout.coerceIn(1_000, 20_000).toLong()
        val headers = okhttp3.Headers.Builder().add("User-Agent", userAgent)
        var contentType: String? = null
        request.headers.forEach { (k, v) ->
            if (k.lowercase() in FORBIDDEN_HEADERS) return@forEach
            if (k.equals("User-Agent", true)) return@forEach
            if (k.equals("Content-Type", true)) contentType = v
            headers.set(k, v)
        }
        val body = when {
            method == "GET" || method == "HEAD" -> null
            request.body != null -> request.body.toByteArray().toRequestBody((contentType ?: "application/x-www-form-urlencoded").toMediaTypeOrNull())
            method == "DELETE" -> null
            else -> ByteArray(0).toRequestBody(null)
        }
        val call = client.newBuilder()
            .connectTimeout(timeout, TimeUnit.MILLISECONDS).readTimeout(timeout, TimeUnit.MILLISECONDS).callTimeout(timeout * 2, TimeUnit.MILLISECONDS)
            .build()
            .newCall(okhttp3.Request.Builder().url(url).headers(headers.build()).method(method, body).build())
        call.execute().use { r ->
            val out = ByteArrayOutputStream()
            r.body.source().let { src ->
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val n = src.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > MAX_BODY) throw IllegalStateException("response larger than ${MAX_BODY / 1024 / 1024} MB")
                }
            }
            val responseHeaders = r.headers.names().associate { it.lowercase() to r.headers.values(it).joinToString(", ") }
            return HttpResponse(r.code, responseHeaders, out.toString(Charsets.UTF_8.name()))
        }
    }
}
