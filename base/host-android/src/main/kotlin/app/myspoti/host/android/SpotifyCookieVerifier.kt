package app.myspoti.host.android

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.Base64

/**
 * Direct HTTPS verification of pasted/imported Spotify cookies (`sp_dc`, `sp_key`, `sp_t`) using
 * [MyspotiRuntime.apiHttp] (which includes [TlsCompat] for Android 6–9 and routes over the active VPN).
 *
 * Separating server-side cookie acceptance from Android 6 WebView loading prevents TLS, slow-VPN,
 * or outdated-WebView failures from being misreported to the user as "Spotify did not accept that cookie".
 */
object SpotifyCookieVerifier {

    sealed interface Result {
        data class Accepted(val market: String?, val isPremium: Boolean?) : Result
        data class Rejected(val reason: String) : Result
        data class Indeterminate(val httpStatus: Int) : Result
        data class NetworkError(val message: String) : Result
    }

    private val APP_SERVER_CONFIG_RE = Regex(
        """<script[^>]*\bid=["']appServerConfig["'][^>]*>([^<]+)</script>""",
        RegexOption.IGNORE_CASE,
    )

    internal fun buildCookieHeader(cookies: Map<String, String>): String {
        val ordered = listOf("sp_dc", "sp_key", "sp_t")
        return ordered.mapNotNull { key ->
            cookies[key]?.trim()?.takeIf { it.isNotEmpty() }?.let { "$key=$it" }
        }.joinToString("; ")
    }

    internal fun clearsSpDc(setCookieHeaders: List<String>): Boolean =
        setCookieHeaders.any { header ->
            val firstPart = header.substringBefore(';').trim()
            val eq = firstPart.indexOf('=')
            if (eq <= 0) return@any false
            val name = firstPart.substring(0, eq).trim()
            val value = firstPart.substring(eq + 1).trim().removeSurrounding("\"")
            name.equals("sp_dc", ignoreCase = false) && value.isEmpty()
        }

    internal fun parseAppServerConfig(html: String): JSONObject? {
        val raw = APP_SERVER_CONFIG_RE.find(html)?.groupValues?.getOrNull(1)?.trim() ?: return null
        val decoded = runCatching {
            String(Base64.getDecoder().decode(raw), Charsets.UTF_8)
        }.recoverCatching {
            String(Base64.getUrlDecoder().decode(raw), Charsets.UTF_8)
        }.getOrDefault(raw)
        return runCatching { JSONObject(decoded) }.getOrNull()
    }

    internal fun classifyResponse(
        statusCode: Int,
        location: String?,
        setCookieHeaders: List<String>,
        body: String,
    ): Result {
        if (clearsSpDc(setCookieHeaders)) {
            return Result.Rejected("Spotify cleared sp_dc cookie (HTTP $statusCode)")
        }
        val loc = location.orEmpty()
        if (statusCode in 300..399 && (loc.contains("_authfailed", ignoreCase = true) || loc.contains("accounts.spotify.com", ignoreCase = true))) {
            return Result.Rejected("Spotify redirected to $loc (HTTP $statusCode)")
        }
        if (statusCode == 401) {
            return Result.Rejected("Spotify returned HTTP $statusCode")
        }
        if (statusCode in 200..299) {
            val cfg = parseAppServerConfig(body)
            if (cfg != null && cfg.has("isAnonymous")) {
                val anonymous = cfg.optBoolean("isAnonymous", true)
                return if (!anonymous) {
                    Result.Accepted(
                        market = cfg.optString("market").takeIf { it.isNotBlank() },
                        isPremium = if (cfg.has("isPremium")) cfg.optBoolean("isPremium") else null,
                    )
                } else {
                    Result.Rejected("Spotify session reported isAnonymous=true")
                }
            }
        }
        return Result.Indeterminate(statusCode)
    }

    suspend fun verify(
        baseClient: OkHttpClient,
        cookies: Map<String, String>,
        userAgent: String,
        acceptLanguage: String = "en",
    ): Result = withContext(Dispatchers.IO) {
        val cookieHeader = buildCookieHeader(cookies)
        if (cookieHeader.isEmpty() || !cookies.containsKey("sp_dc")) {
            return@withContext Result.Rejected("Missing sp_dc cookie")
        }
        val noRedirectClient = baseClient.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()

        var url = "https://open.spotify.com/"
        try {
            repeat(3) {
                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", userAgent)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", acceptLanguage.ifBlank { "en" })
                    .header("Sec-Fetch-Dest", "document")
                    .header("Sec-Fetch-Mode", "navigate")
                    .header("Sec-Fetch-Site", "none")
                    .header("Upgrade-Insecure-Requests", "1")
                    .header("Cookie", cookieHeader)
                    .get()
                    .build()
                noRedirectClient.newCall(req).execute().use { resp ->
                    val code = resp.code
                    val location = resp.header("Location")
                    val setCookies = resp.headers("Set-Cookie")
                    val body = if (code in 300..399) "" else resp.body.string()
                    val classified = classifyResponse(code, location, setCookies, body)
                    if (classified !is Result.Indeterminate || code !in 300..399 || location.isNullOrBlank()) {
                        return@withContext classified
                    }
                    val resolved = resp.request.url.resolve(location)
                    if (resolved == null || !resolved.host.equals("open.spotify.com", ignoreCase = true)) {
                        return@withContext classified
                    }
                    url = resolved.toString()
                }
            }
            Result.Indeterminate(302)
        } catch (e: IOException) {
            val msg = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
            Result.NetworkError(msg)
        }
    }
}
