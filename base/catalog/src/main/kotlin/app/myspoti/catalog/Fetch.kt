/* SPDX-License-Identifier: GPL-3.0-only */
package app.myspoti.catalog

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

data class Response(val status: Int, val body: String, val retryAfterMs: Long? = null)

/** One GET. Separate from the providers so their tests read files and the app reads the network. */
fun interface Fetch {
    suspend fun get(url: String): Response
}

/** A provider cannot answer right now, after its retries. Another may. */
open class ProviderDown(val provider: String, message: String, cause: Throwable? = null) : IOException("$provider: $message", cause)

/**
 * The script itself failed (an error it threw, an answer that is not one, a run too long), not the service it asks.
 * [definite]: it would fail the same way again; a run too long may only have met a slow network.
 */
class ScriptFailed(provider: String, message: String, cause: Throwable? = null, val definite: Boolean = true) : ProviderDown(provider, message, cause)

/** The provider answered: it does not have this. */
class NotFound(val provider: String, what: String) : NoSuchElementException("$provider has no $what")

/** The network, one request at a time at most every [paceMs], as each service asks. */
class HttpFetch(
    private val client: OkHttpClient,
    private val userAgent: String,
    private val paceMs: Long,
) : Fetch {
    private val gate = Mutex()
    private var nextAt = 0L

    override suspend fun get(url: String): Response {
        gate.withLock {
            val wait = nextAt - System.currentTimeMillis()
            if (wait > 0) delay(wait)
            nextAt = System.currentTimeMillis() + paceMs
        }
        return withContext(Dispatchers.IO) {
            val request = Request.Builder().url(url).header("User-Agent", userAgent).header("Accept", "application/json").build()
            client.newCall(request).execute().use { r ->
                Response(r.code, r.body.string(), r.header("Retry-After")?.toLongOrNull()?.times(1000))
            }
        }
    }
}

/**
 * What makes a provider dependable: a failure is tried again, as long as the service asks to wait, and an
 * answer already seen outlives a service that is down. An answer that says "not found" is an answer.
 */
class ReliableFetch(
    private val inner: Fetch,
    private val name: String,
    private val attempts: Int = 3,
    private val backoffMs: Long = 400,
    private val freshMs: Long = 6 * 3_600_000L,
    private val cacheSize: Int = 512,
    private val now: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) : Fetch {
    private class Kept(val response: Response, val at: Long)

    private val lock = Any()
    private val cache = object : LinkedHashMap<String, Kept>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Kept>?) = size > cacheSize
    }

    override suspend fun get(url: String): Response {
        val kept = synchronized(lock) { cache[url] }
        if (kept != null && now() - kept.at < freshMs) return kept.response
        var last: String
        var attempt = 0
        while (true) {
            var waitMs: Long? = null
            try {
                val r = inner.get(url)
                if (r.status in 200..299 || r.status == 404) {
                    synchronized(lock) { cache[url] = Kept(r, now()) }
                    return r
                }
                last = "HTTP ${r.status}"
                // Asking again would not change a refused request.
                if (r.status != 429 && r.status < 500) return kept?.response ?: throw ProviderDown(name, last)
                waitMs = r.retryAfterMs
            } catch (e: IOException) {
                if (e is ProviderDown) throw e
                last = e.message ?: e.toString()
            }
            attempt++
            if (attempt >= attempts) return kept?.response ?: throw ProviderDown(name, last)
            sleep(waitMs ?: (backoffMs shl (attempt - 1)))
        }
    }
}
