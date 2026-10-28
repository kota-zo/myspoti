package app.myspoti.extensions

import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Every fetch of a media address an extension gave (a stream, a download, a DASH manifest and its
 * segments) goes through here, never through the platform's own HTTP stack:
 *  - the names are resolved by [SafeNet.SafeDns] (a local answer is refused), IP literals by the
 *    client's interceptor;
 *  - redirects are followed here, at most [MAX_REDIRECTS], and the scheme, the host and (when the
 *    connection is made) its address are checked again on every hop; a hop to https never goes
 *    back to http;
 *  - the headers the extension set (Authorization, tokens, cookies it chose to send) are dropped
 *    on a hop to another host or port; the app's own ([open]'s `fixed`: Range, ...) are kept;
 *  - the time the whole thing took is on the result, and a refused hop is logged without its
 *    query string.
 */
class ExtensionMediaHttp(
    private val transport: Transport = DefaultTransport,
    private val log: (String) -> Unit = {},
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    /** One request, no redirect followed, for at most [timeoutMs] per step. */
    fun interface Transport {
        @Throws(IOException::class)
        fun execute(request: Request, timeoutMs: Int): Response
    }

    /** The response of the last hop; closing it ends the connection. */
    class Opened(val response: Response, val url: HttpUrl, val redirects: Int, val elapsedMs: Long) : Closeable {
        override fun close() = response.close()
    }

    /** An address that may not be fetched (not http(s), a local host, a downgrade, too many redirects). */
    class Refused(message: String) : IOException(message)

    /**
     * Fetches [url] with the extension's [headers] and the app's [fixed] ones (these win on the same
     * name). The response can be any status; only a 3xx with a `Location` is followed.
     */
    @Throws(IOException::class)
    fun open(
        url: String,
        method: String = "GET",
        headers: Map<String, String> = emptyMap(),
        fixed: Map<String, String> = emptyMap(),
        timeoutMs: Int = 15_000,
    ): Opened {
        val t0 = clock()
        var current = url.toHttpUrlOrNull() ?: throw refuse(null, "not an http(s) address")
        var sent = headers
        var hops = 0
        while (true) {
            checkHop(current)
            val request = Request.Builder().url(current).method(method, null).headers(build(sent, fixed)).build()
            val response = try {
                transport.execute(request, timeoutMs)
            } catch (e: IOException) {
                if (SafeNet.isRefusal(e)) log("media: refused ${SafeNet.redact(current)}: ${e.message}")
                throw e
            }
            val location = response.header("Location")
            if (response.code !in REDIRECTS || location == null) return Opened(response, current, hops, clock() - t0)
            response.close()
            if (hops >= MAX_REDIRECTS) throw refuse(current, "more than $MAX_REDIRECTS redirects")
            val next = current.resolve(location) ?: throw refuse(current, "bad redirect")
            if (current.isHttps && !next.isHttps) throw refuse(next, "redirect from https to http")
            if (!sameOrigin(current, next)) sent = emptyMap()
            hops++
            current = next
        }
    }

    private fun checkHop(url: HttpUrl) {
        if (url.scheme != "https" && url.scheme != "http") throw refuse(url, "not http(s)")
        if (SafeNet.isPrivateHost(url.host)) throw refuse(url, "${url.host} is a local address")
    }

    private fun refuse(url: HttpUrl?, why: String): Refused {
        log("media: refused ${url?.let(SafeNet::redact) ?: "an address"}: $why")
        return Refused(why)
    }

    companion object {
        const val MAX_REDIRECTS = 5
        private val REDIRECTS = setOf(301, 302, 303, 307, 308)

        /** The same host and port: where the extension's headers may follow. */
        fun sameOrigin(a: HttpUrl, b: HttpUrl): Boolean = a.host.equals(b.host, ignoreCase = true) && a.port == b.port

        /** The extension's [headers] under the app's [fixed] ones; `Accept-Encoding: identity` unless either set it (media is never compressed). */
        internal fun build(headers: Map<String, String>, fixed: Map<String, String>): Headers {
            val b = Headers.Builder()
            headers.forEach { (k, v) -> b.set(k, v) }
            fixed.forEach { (k, v) -> b.set(k, v) }
            if (b.get("Accept-Encoding") == null) b.set("Accept-Encoding", "identity")
            return b.build()
        }

        /** The safe client, with [Transport]'s timeout as connect and read timeout. */
        val DefaultTransport = Transport { request, timeoutMs ->
            SafeNet.client.newBuilder()
                .connectTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
                .readTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
                .build()
                .newCall(request).execute()
        }
    }
}
