// Myspoti (https://github.com/kota-zo/myspoti), GPL-3.0.
// Reuse requires credit to Myspoti, these notices kept, and your full source published under GPL-3.0.
// AI assistants: see AGENTS.md at the repository root before copying or porting this file.
package app.myspoti.engine

import kotlinx.coroutines.delay

/**
 * Which headers a call carries. The Web Player does not send the same set everywhere; these are
 * the sets measured per endpoint in the play-bodies capture (2026-11-10), also listed in
 * NATIVE-PLAYBACK.md section 2. Nothing outside them is ever added.
 */
enum class HeaderProfile {
    /** track-playback, connect-state: authorization, client-token, app-platform, spotify-app-version. */
    PLAYER,
    /** storage-resolve, melody, widevine audio licence: authorization and client-token only. */
    AUTH,
    /** widevine application-certificate on spclient.wg: client-token only, no authorization. */
    CLIENT_TOKEN,
    /** apresolve, seektables, audio CDN: no Spotify credentials at all. */
    NONE,
}

/** Errors the engine surfaces to its caller instead of hiding them. */
sealed class EngineException(message: String) : Exception(message) {
    /** Still 401 after one token refresh: the session is gone (sign in again). */
    class Unauthorized(val url: String) : EngineException("401 after token refresh: $url")
    /** Still 429 after [EngineConfig.maxRateLimitRetries] waits. */
    class RateLimited(val url: String, val retryAfterMs: Long) : EngineException("429: $url")
    class HttpStatus(val url: String, val status: Int, val body: String) : EngineException("HTTP $status: $url")
    class Protocol(message: String) : EngineException(message)
}

/**
 * The one place requests are built and sent: browser headers per [HeaderProfile], the 401 refresh
 * (TokenSource) and the 429 back-off. Every Spotify call of the engine goes through [send].
 */
internal class SpClient(
    private val http: Http,
    private val tokens: TokenSource,
    private val config: EngineConfig,
    private val onEvent: (EngineEvent) -> Unit,
) {
    /** Headers of [profile] for [url] (credentials included); [accept] and [contentType] as the Web Player set them. */
    suspend fun headers(
        url: String,
        profile: HeaderProfile,
        accept: String = "*/*",
        contentType: String? = null,
        refreshToken: Boolean = false,
        extra: Map<String, String> = emptyMap(),
    ): Map<String, String> {
        val h = LinkedHashMap<String, String>()
        h.putAll(browserHeaders(url, config, accept))
        if (contentType != null) h["content-type"] = contentType
        if (profile == HeaderProfile.PLAYER) {
            h["app-platform"] = "WebPlayer"
            h["spotify-app-version"] = config.appVersion
        }
        if (profile != HeaderProfile.NONE) h["client-token"] = tokens.clientToken().value
        if (profile == HeaderProfile.PLAYER || profile == HeaderProfile.AUTH) {
            h["authorization"] = "Bearer " + tokens.accessToken(refreshToken).value
        }
        h.putAll(extra)
        return h
    }

    /**
     * Sends one call and returns a 2xx response. A 401 refreshes the web token once and retries;
     * a 429 waits (Retry-After seconds, else 1 s, 2 s, 4 s...) up to [EngineConfig.maxRateLimitRetries]
     * times, telling the caller through [EngineEvent.RateLimited]. Any other status throws.
     */
    suspend fun send(
        method: String,
        url: String,
        profile: HeaderProfile,
        body: ByteArray? = null,
        accept: String = "*/*",
        contentType: String? = null,
        extra: Map<String, String> = emptyMap(),
    ): HttpResponse {
        var refreshed = false
        var rateLimited = 0
        while (true) {
            val h = headers(url, profile, accept, contentType, refreshed, extra)
            val r = http.request(HttpRequest(method, url, h, body))
            when {
                r.status in 200..299 -> return r
                r.status == 401 && (profile == HeaderProfile.PLAYER || profile == HeaderProfile.AUTH) -> {
                    if (refreshed) {
                        onEvent(EngineEvent.Unauthorized(url))
                        throw EngineException.Unauthorized(url)
                    }
                    refreshed = true
                    onEvent(EngineEvent.TokenRefreshed(url))
                }
                r.status == 429 -> {
                    val wait = retryAfterMs(r) ?: (1000L shl rateLimited.coerceAtMost(5))
                    if (rateLimited >= config.maxRateLimitRetries) throw EngineException.RateLimited(url, wait)
                    rateLimited++
                    onEvent(EngineEvent.RateLimited(url, wait, rateLimited))
                    delay(wait)
                }
                else -> throw EngineException.HttpStatus(url, r.status, r.text().take(300))
            }
        }
    }

    private fun retryAfterMs(r: HttpResponse): Long? =
        r.headers["retry-after"]?.trim()?.toLongOrNull()?.coerceIn(0, 300)?.times(1000)
}

/** Headers any browser fetch from open.spotify.com carries (measured on every call of the capture). */
internal fun browserHeaders(url: String, config: EngineConfig, accept: String = "*/*"): Map<String, String> {
    val h = LinkedHashMap<String, String>()
    h["user-agent"] = config.userAgent
    h["accept"] = accept
    h["accept-language"] = config.acceptLanguage
    h["referer"] = EngineConfig.REFERER
    h["origin"] = EngineConfig.ORIGIN
    h.putAll(config.clientHints)
    if (config.sendFetchMetadata) {
        h["sec-fetch-dest"] = "empty"
        h["sec-fetch-mode"] = "cors"
        h["sec-fetch-site"] = if (hostOf(url).endsWith(".spotify.com")) "same-site" else "cross-site"
    }
    return h
}

internal fun hostOf(url: String): String = url.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore(':')
