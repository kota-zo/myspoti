package app.myspoti.spotify.gql

import app.myspoti.spotify.bridge.Bridge
import app.myspoti.spotify.bridge.BridgeException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID

class GqlException(val operation: String, message: String) : Exception("$operation: $message")

/** Persisted GraphQL operations of the Web Player: name -> hash. */
class GqlOps(private val hashes: Map<String, String>) {
    fun hash(name: String): String = hashes[name] ?: throw GqlException(name, "unknown operation")
    val names: Set<String> get() = hashes.keys

    companion object {
        fun bundled(): GqlOps {
            val text = GqlOps::class.java.getResourceAsStream("/spotify/gql-ops.json")!!.bufferedReader().use { it.readText() }
            val ops = Bridge.json.parseToJsonElement(text).jsonObject["operations"]!!.jsonObject
            return GqlOps(ops.mapValues { it.value.jsonObject["hash"]!!.jsonPrimitive.content })
        }
    }
}

/** Where the current operation table comes from (it can change while the app runs). */
interface OpsSource { val ops: GqlOps }

/** Runs a persisted GraphQL operation and returns the whole `{data: ...}` response. */
interface Gql {
    suspend fun query(name: String, variables: JsonObject): JsonElement
}

/** Through the page's own loader: always works once the page is ready, a bit slower. */
class BridgeGql(private val bridge: Bridge, private val source: OpsSource) : Gql {
    private val ops get() = source.ops
    override suspend fun query(name: String, variables: JsonObject): JsonElement =
        bridge.call("gql.query", buildJsonObject {
            put("name", name)
            // The page's own definitions say "mutation" for writes (never cached or deduplicated).
            put("operation", if (isMutation(name)) "mutation" else "query")
            put("hash", ops.hash(name))
            put("variables", variables)
        }).also { checkErrors(name, it) }
}

class HttpException(val code: Int, message: String) : Exception("HTTP $code: $message")

/**
 * Direct HTTP to Spotify with the Web Player's credentials: the web token from the bridge,
 * and a client token minted here and kept about 14 days.
 */
class SpotifyHttp(
    private val http: OkHttpClient,
    private val bridge: Bridge,
    private val language: String = "en",
    /**
     * The browser this app presents to Spotify, drawn once per install by the host
     * (`Fingerprint`). Handing every install the same user agent made the whole user base one
     * profile; null keeps the previous constant.
     */
    private val browserUserAgent: String? = null,
    /**
     * The id this client announces when it mints its client token. It used to be a fresh UUID at
     * every launch, so Spotify saw a brand-new client every start (a new client token each time,
     * one more device). The host passes an id kept across restarts; null keeps the old behaviour.
     */
    private val deviceId: String = java.util.UUID.randomUUID().toString(),
) {
    private data class WebToken(val access: String, val expiresAtMs: Long, val clientId: String, val appVersion: String, val anonymous: Boolean,
                                val fetchedAtMs: Long = System.currentTimeMillis())
    private data class ClientToken(val value: String, val expiresAtMs: Long)

    private val lock = Mutex()
    private var web: WebToken? = null
    private var client: ClientToken? = null

    /** Drops the cached web token (it belongs to one account); the client token is kept. */
    suspend fun forgetWebToken() = lock.withLock { web = null }

    /** True when the page's token belongs to a signed-in user (the cached one is reused). */
    /**
     * Asks the page, never the cached token: a token cached before a logout (valid up to an hour)
     * said "signed in" while the user was still on the e-mail code step, the sign-in page was put
     * away under them and the code was lost (log seen). A fresh answer also refreshes the cache.
     * "Fresh" is a few seconds: several checks at once (startup asks five times within five
     * seconds) share one answer, and every request waits behind this lock while the page answers
     * (0.3 to 2 s each, measured at startup).
     */
    suspend fun isSignedIn(): Boolean = lock.withLock {
        if (web?.let { System.currentTimeMillis() - it.fetchedAtMs < SIGNED_IN_FRESH_MS } != true) web = null
        !webToken().anonymous
    }

    /** What the token in hand says (null: none yet), without asking the page; for logs only. */
    fun signedInHint(): Boolean? = web?.let { !it.anonymous }

    /** The account's token, fetched again only when the cached one is missing or stale. */
    private suspend fun webToken(): WebToken {
        val now = System.currentTimeMillis()
        return web?.takeIf { it.expiresAtMs - now > 60_000 } ?: run {
            val t = bridge.call("token.get").jsonObject
            // A page that answers without a field (a build that changed) is a bridge error, not a crash.
            fun field(name: String): JsonPrimitive = t[name] as? JsonPrimitive ?: throw BridgeException("token.get: no $name")
            WebToken(
                access = field("accessToken").content,
                expiresAtMs = field("expiresAtMs").long,
                clientId = field("clientId").content,
                appVersion = field("appVersion").content,
                anonymous = t["isAnonymous"]?.jsonPrimitive?.contentOrNull == "true",
            ).also { web = it }
        }
    }

    /** Sends a request with browser headers. Returns the status and body text. */
    suspend fun send(method: String, url: String, body: String? = null, contentType: String = JSON_TYPE, accept: String = "application/json"): Pair<Int, String> {
        // The account's token only ever goes to Spotify itself.
        val host = url.toHttpUrl().host
        require(host == "spotify.com" || host.endsWith(".spotify.com")) { "not a Spotify host: $host" }
        val (w, c) = tokens()
        val builder = Request.Builder().url(url)
            .header("authorization", "Bearer ${w.access}")
            .header("client-token", c.value)
            .header("app-platform", "WebPlayer")
            .header("spotify-app-version", w.appVersion)
            .header("accept", accept)
            .header("accept-language", language)
            .header("origin", "https://open.spotify.com")
            .header("referer", "https://open.spotify.com/")
            .header("user-agent", browserUserAgent ?: USER_AGENT)
        // Bytes keep the content type exactly as given (OkHttp appends a charset to String bodies,
        // which some Spotify endpoints reject with 400).
        builder.method(method, body?.toByteArray()?.toRequestBody(contentType.toMediaType()) ?: if (method == "GET" || method == "DELETE") null else ByteArray(0).toRequestBody(null))
        val call = http.newCall(builder.build())
        // A read is given up with its caller (a timeout, a song skipped): a blocking execute ignores
        // the coroutine's cancellation, and a caller's 3 s timeout then lasted the client's 10 s
        // (log seen: an extension asked for a skipped-to song 10 s late, behind its ISRC lookup).
        // Writes still run to their end: a caller gone must not leave one half sent.
        val result = if (method == "GET") call.executeCancellable() else withContext(Dispatchers.IO) { call.execute().use { it.code to it.body.string() } }
        if (result.first == 401) lock.withLock { web = null }
        return result
    }

    /** Runs the call on the IO pool; the coroutine cancelled, the call is cancelled too (it then fails at once). */
    private suspend fun okhttp3.Call.executeCancellable(): Pair<Int, String> = coroutineScope {
        val call = this@executeCancellable
        val done = java.util.concurrent.atomic.AtomicBoolean(false)
        val watch = launch(start = CoroutineStart.UNDISPATCHED) { try { awaitCancellation() } finally { if (!done.get()) call.cancel() } }
        try {
            withContext(Dispatchers.IO) { call.execute().use { it.code to it.body.string() } }
        } finally {
            done.set(true)
            watch.cancel()
        }
    }

    /** What [sendRaw] got back: the status, lower-case header names (values joined by ", "), the body text. */
    data class RawResponse(val status: Int, val headers: Map<String, String>, val body: String)

    /**
     * Any method on a Spotify address, for the extensions' raw door. `*.spotify.com` gets the
     * account's credentials as every other call here (web token, client token, the Web Player's
     * headers); `*.spotifycdn.com` (public files) gets none. Other hosts, plain http, and caller
     * headers that would replace the credentials are refused or dropped. No X-Requested-With.
     */
    suspend fun sendRaw(method: String, url: String, body: ByteArray?, headers: Map<String, String>): RawResponse {
        val target = url.toHttpUrl()
        val kind = rawTarget(target.scheme, target.host) ?: throw IllegalArgumentException("not a Spotify address: ${target.host}")
        val m = method.uppercase()
        require(m in RAW_METHODS) { "method $method" }
        val extra = headers.filterKeys { it.lowercase() !in RAW_DROPPED }
        val contentType = extra.entries.firstOrNull { it.key.equals("content-type", true) }?.value ?: JSON_TYPE
        val builder = Request.Builder().url(target)
        if (kind == RawTarget.ACCOUNT) {
            val (w, c) = tokens()
            builder.header("authorization", "Bearer ${w.access}")
                .header("client-token", c.value)
                .header("app-platform", "WebPlayer")
                .header("spotify-app-version", w.appVersion)
                .header("origin", "https://open.spotify.com")
                .header("referer", "https://open.spotify.com/")
        }
        builder.header("accept", "application/json").header("accept-language", language)
            .header("user-agent", browserUserAgent ?: USER_AGENT)
        extra.forEach { (k, v) -> if (!k.equals("content-type", true)) builder.header(k, v) }
        val payload = body?.toRequestBody(contentType.toMediaType())
            ?: if (m == "GET" || m == "HEAD" || m == "DELETE") null else ByteArray(0).toRequestBody(null)
        builder.method(m, payload)
        val result = withContext(Dispatchers.IO) {
            http.newCall(builder.build()).execute().use { r ->
                RawResponse(r.code, r.headers.names().associate { n -> n.lowercase() to r.headers.values(n).joinToString(", ") },
                    if (m == "HEAD") "" else r.body.string())
            }
        }
        if (result.status == 401 && kind == RawTarget.ACCOUNT) lock.withLock { web = null }
        return result
    }

    /**
     * A protobuf call on a spclient service the mobile client uses (friend feed, messages): the
     * account's credentials as in [send], bytes in and out. Returns the status and the body bytes.
     */
    suspend fun sendProto(method: String, url: String, body: ByteArray?): Pair<Int, ByteArray> {
        val host = url.toHttpUrl().host
        require(host == "spotify.com" || host.endsWith(".spotify.com")) { "not a Spotify host: $host" }
        val (w, c) = tokens()
        val request = Request.Builder().url(url)
            .header("authorization", "Bearer ${w.access}")
            .header("client-token", c.value)
            .header("app-platform", "WebPlayer")
            .header("spotify-app-version", w.appVersion)
            .header("accept", "application/x-protobuf")
            .header("accept-language", language)
            .header("origin", "https://open.spotify.com")
            .header("referer", "https://open.spotify.com/")
            .header("user-agent", browserUserAgent ?: USER_AGENT)
            .method(method, body?.toRequestBody("application/x-protobuf".toMediaType()) ?: if (method == "GET" || method == "DELETE") null else ByteArray(0).toRequestBody(null))
            .build()
        val result = withContext(Dispatchers.IO) { http.newCall(request).execute().use { it.code to it.body.bytes() } }
        if (result.first == 401) lock.withLock { web = null }
        return result
    }

    suspend fun getJson(url: String): JsonElement? {
        val (code, text) = send("GET", url)
        return when (code) {
            200 -> Bridge.json.parseToJsonElement(text)
            204, 404 -> null
            else -> throw HttpException(code, text.take(200))
        }
    }

    private suspend fun tokens(): Pair<WebToken, ClientToken> = lock.withLock {
        val now = System.currentTimeMillis()
        val w = webToken()
        val c = client?.takeIf { it.expiresAtMs - now > 3_600_000 } ?: mintClientToken(w).also { client = it }
        w to c
    }

    private suspend fun mintClientToken(w: WebToken): ClientToken {
        val body = buildJsonObject {
            putJsonObject("client_data") {
                put("client_version", w.appVersion)
                put("client_id", w.clientId)
                putJsonObject("js_sdk_data") {
                    put("device_brand", "unknown")
                    put("device_model", "unknown")
                    put("os", "linux")
                    put("os_version", "unknown")
                    put("device_id", deviceId)
                    put("device_type", "computer")
                }
            }
        }
        val request = Request.Builder()
            .url("https://clienttoken.spotify.com/v1/clienttoken")
            // Bytes, not a String: OkHttp would append "; charset=utf-8", which this endpoint rejects with 400.
            .post(body.toString().toByteArray().toRequestBody("application/json".toMediaType()))
            .header("accept", "application/json")
            .header("user-agent", browserUserAgent ?: USER_AGENT)
            .build()
        val (code, text) = withContext(Dispatchers.IO) { http.newCall(request).execute().use { it.code to it.body.string() } }
        val granted = runCatching { Bridge.json.parseToJsonElement(text).jsonObject["granted_token"]?.jsonObject }.getOrNull()
            ?: throw GqlException("clienttoken", "HTTP $code: ${text.take(200)}")
        val seconds = (granted["expires_after_seconds"] as? JsonPrimitive)?.long ?: 86_400
        val token = (granted["token"] as? JsonPrimitive)?.content ?: throw GqlException("clienttoken", "no token in the answer")
        return ClientToken(token, System.currentTimeMillis() + seconds * 1000)
    }

    /** Who a raw request may go to: the account's own hosts (with its credentials) or Spotify's public CDN. */
    enum class RawTarget { ACCOUNT, PUBLIC }

    companion object {
        private val RAW_METHODS = setOf("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE")
        /** The credentials and what the transport sets itself: never taken from an extension. */
        private val RAW_DROPPED = setOf(
            "authorization", "client-token", "cookie", "host", "content-length", "connection", "transfer-encoding",
            "x-requested-with", "origin", "referer", "app-platform", "spotify-app-version",
        )

        /** [RawTarget] for https on `spotify.com` / `*.spotify.com` / `spotifycdn.com` / `*.spotifycdn.com`; null for anything else. */
        fun rawTarget(scheme: String, host: String): RawTarget? {
            if (scheme != "https") return null
            val h = host.lowercase()
            return when {
                h == "spotify.com" || h.endsWith(".spotify.com") -> RawTarget.ACCOUNT
                h == "spotifycdn.com" || h.endsWith(".spotifycdn.com") -> RawTarget.PUBLIC
                else -> null
            }
        }

        /** How long a signed-in check answers from the token just fetched (see isSignedIn). */
        const val SIGNED_IN_FRESH_MS = 10_000L
        const val JSON_TYPE = "application/json;charset=UTF-8"
        const val USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/155.0.0.0 Safari/537.36"
    }
}

/** Operations that change something: not replayed when their outcome is unknown, and any error fails them. */
fun isMutation(name: String): Boolean =
    Regex("^(add|remove|move|update|delete|set|create|follow|unfollow|pin|unpin|edit|save|ban|unban|clear)[A-Z]").containsMatchIn(name)

/**
 * Throws when [response] reports a failure: errors without data for a read, any error or
 * no data at all for a mutation, where partial data would otherwise pass for a success.
 */
fun checkErrors(name: String, response: JsonElement) {
    val obj = response as? JsonObject ?: return
    val errors = obj["errors"]?.let { it as? kotlinx.serialization.json.JsonArray }?.takeIf { it.isNotEmpty() }
    val data = obj["data"]?.takeIf { it !is kotlinx.serialization.json.JsonNull } as? JsonObject
    val failed = if (isMutation(name)) errors != null || data == null
    else errors != null && data == null
    if (!failed) return
    val message = runCatching { errors!!.first().jsonObject["message"]!!.jsonPrimitive.content }.getOrDefault("no result")
    throw GqlException(name, message)
}

/** GraphQL straight to api-partner: fastest, parallel, independent of the page. Falls back to [fallback]. */
class HttpGql(private val http: SpotifyHttp, private val source: OpsSource, private val fallback: Gql) : Gql {
    private val ops get() = source.ops
    /** Calls answered directly, and calls that had to go through [fallback]. */
    val direct = java.util.concurrent.atomic.AtomicInteger()
    val fellBack = java.util.concurrent.atomic.AtomicInteger()

    override suspend fun query(name: String, variables: JsonObject): JsonElement {
        val body = buildJsonObject {
            put("variables", variables)
            put("operationName", name)
            putJsonObject("extensions") {
                putJsonObject("persistedQuery") {
                    put("version", 1)
                    put("sha256Hash", ops.hash(name))
                }
            }
        }
        val mutation = isMutation(name)
        val (code, text) = try {
            http.send("POST", "https://api-partner.spotify.com/pathfinder/v2/query", body.toString())
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: java.io.IOException) {
            // The write may have reached Spotify before the connection broke: never sent twice.
            if (mutation) throw GqlException(name, "no answer: ${e.message}")
            fellBack.incrementAndGet(); return fallback.query(name, variables)
        } catch (e: Exception) {
            fellBack.incrementAndGet(); return fallback.query(name, variables)
        }
        if (code != 200) {
            // A server error may come after the write was done; a refusal (4xx) was not applied.
            if (mutation && code >= 500) throw GqlException(name, "HTTP $code")
            fellBack.incrementAndGet(); return fallback.query(name, variables)
        }
        direct.incrementAndGet()
        val response = Bridge.json.parseToJsonElement(text)
        checkErrors(name, response)
        return response
    }
}
