package app.myspoti.host.android

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException

/**
 * Direct HTTPS verification of pasted/imported Spotify cookies (`sp_dc`, `sp_key`, `sp_t`) using
 * [MyspotiRuntime.apiHttp] (which includes [TlsCompat] for Android 6–9 and routes over the active VPN).
 *
 * Two lessons are built into this file:
 *
 * 1. **The probe must not look like an old browser.** Spotify serves a reduced shell — with no
 *    session config in it — to ancient user agents. The app's own agent follows the device's
 *    WebView, which on Android 6 is Chrome 55–106; asking Spotify with that agent said nothing
 *    about the cookie and the old code turned that silence into "Spotify did not accept that
 *    cookie". The probe now always presents a current desktop Chrome.
 * 2. **Silence is not a rejection.** Since March 2025 `open.spotify.com/get_access_token` answers
 *    `isAnonymous: true` for *valid* cookies too (the web player also sends a TOTP now), and the
 *    home page's markup changes. Anything that is not a clear answer is [Result.Unverified]: the
 *    WebView, which is the authority on whether the cookie works, still gets its turn.
 *
 * Separating server-side cookie acceptance from Android 6 WebView loading prevents TLS, slow-VPN,
 * or outdated-WebView failures from being misreported to the user as "Spotify did not accept that
 * cookie".
 */
object SpotifyCookieVerifier {

    sealed interface Result {
        data class Accepted(val market: String?, val isPremium: Boolean?) : Result
        /**
         * [definitive] is false when Spotify's own answer cannot be trusted to end the import:
         * the page saying "anonymous" is worth a try in the WebView anyway (a wrong "the cookie is
         * bad" is worse than a slow login), while a cleared cookie or an auth redirect is not.
         */
        data class Rejected(val reason: String, val definitive: Boolean = true) : Result

        /**
         * Spotify answered, but nothing in the answer says whether the cookie is good. The caller
         * must treat this as "try it anyway", never as a rejection.
         */
        data class Unverified(val httpStatus: Int, val note: String) : Result
        data class NetworkError(val message: String) : Result
    }

    /** Below this Chrome version Spotify's home page may be a shell without the session config. */
    internal const val MIN_PROBE_CHROME = 120

    /** The version the probe presents when the device's own agent is older (or not a Chrome at all). */
    internal const val PROBE_CHROME = "141.0.0.0"

    private val CHROME_VERSION_RE = Regex("""Chrome/(\d+)""")
    private val CHROME_TOKEN_RE = Regex("""Chrome/\d+(?:\.\d+)*""")

    private const val HOME_URL = "https://open.spotify.com/"
    private const val TOKEN_URL =
        "https://open.spotify.com/get_access_token?reason=transport&productType=web_player"

    private const val DEFAULT_PROBE_UA =
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/$PROBE_CHROME Safari/537.36"

    private val APP_SERVER_CONFIG_RE = Regex(
        """<script[^>]*\bid=["']appServerConfig["'][^>]*>([^<]+)</script>""",
        RegexOption.IGNORE_CASE,
    )
    private val IS_ANONYMOUS_RE = Regex(""""isAnonymous"\s*:\s*(true|false)""")

    /**
     * The user agent the probe sends: the install's own agent when it claims a current Chrome (so
     * an install keeps its own fingerprint), otherwise the same string with its Chrome version
     * brought forward. An Android 6 WebView's `Chrome/55.0.0.0` is exactly what fails here.
     */
    internal fun probeUserAgent(deviceUserAgent: String?): String {
        val ua = deviceUserAgent?.trim().orEmpty()
        if (ua.isEmpty()) return DEFAULT_PROBE_UA
        val major = CHROME_VERSION_RE.find(ua)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: return DEFAULT_PROBE_UA
        if (major >= MIN_PROBE_CHROME) return ua
        return CHROME_TOKEN_RE.replace(ua, "Chrome/$PROBE_CHROME")
    }

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

    /** Base64 that also works on an Android 6 build whose desugaring did not cover `java.util.Base64`. */
    internal fun decodeBase64(raw: String): String? {
        runCatching { java.util.Base64.getDecoder().decode(raw) }
            .recoverCatching { java.util.Base64.getUrlDecoder().decode(raw) }
            .onSuccess { return String(it, Charsets.UTF_8) }
        // Older/undesugared runtimes: the platform decoder. Throws on a JVM unit test, which the
        // `runCatching` swallows; nothing here is a reason to fail a verification.
        return runCatching {
            String(android.util.Base64.decode(raw, android.util.Base64.DEFAULT), Charsets.UTF_8)
        }.getOrNull()
    }

    internal fun parseAppServerConfig(html: String): JSONObject? {
        val raw = APP_SERVER_CONFIG_RE.find(html)?.groupValues?.getOrNull(1)?.trim() ?: return null
        val decoded = decodeBase64(raw) ?: raw
        return runCatching { JSONObject(decoded) }.getOrNull()
    }

    /**
     * Whether the page says the session is signed in, signed out, or nothing at all. The
     * `appServerConfig` script is the page's own answer; a raw `"isAnonymous"` in the markup is
     * the fallback for when Spotify renames or moves that script — and only counts when every
     * occurrence agrees, so an unrelated `true` in some other blob cannot fake a rejection.
     */
    internal fun sessionFromPage(html: String): Pair<Boolean, JSONObject?>? {
        parseAppServerConfig(html)?.let { cfg ->
            if (cfg.has("isAnonymous")) return !cfg.optBoolean("isAnonymous", true) to cfg
        }
        val answers = IS_ANONYMOUS_RE.findAll(html).map { it.groupValues[1] }.toSet()
        if (answers.size == 1) return (answers.first() == "false") to null
        return null
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
            val session = sessionFromPage(body)
            if (session != null) {
                val signedIn = session.first
                val cfg = session.second
                if (signedIn) {
                    return Result.Accepted(
                        market = cfg?.optString("market")?.takeIf { it.isNotBlank() },
                        isPremium = if (cfg?.has("isPremium") == true) cfg.optBoolean("isPremium") else null,
                    )
                }
                return Result.Rejected("Spotify session reported isAnonymous=true", definitive = false)
            }
        }
        return Result.Unverified(
            httpStatus = statusCode,
            note = if (statusCode in 200..299) {
                "Spotify answered HTTP $statusCode, but the page carried no session information"
            } else {
                "Spotify answered HTTP $statusCode"
            },
        )
    }

    private class Probe(
        val code: Int,
        val location: String?,
        val body: String,
        val url: String,
        val setCookies: List<String>,
    )

    /** One request, no redirects followed by OkHttp: the caller wants to see the 3xx. */
    private fun fetch(
        client: OkHttpClient,
        url: String,
        cookieHeader: String,
        userAgent: String,
        acceptLanguage: String,
        accept: String,
    ): Probe {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Accept", accept)
            .header("Accept-Language", acceptLanguage.ifBlank { "en" })
            .header("Sec-Fetch-Dest", "document")
            .header("Sec-Fetch-Mode", "navigate")
            .header("Sec-Fetch-Site", "none")
            .header("Upgrade-Insecure-Requests", "1")
            .header("Cookie", cookieHeader)
            .get()
            .build()
        client.newCall(req).execute().use { resp: Response ->
            val body = if (resp.code !in 300..399) resp.body.string() else ""
            return Probe(resp.code, resp.header("Location"), body, resp.request.url.toString(), resp.headers("Set-Cookie"))
        }
    }

    /**
     * Asks the same page the WebView will load, following the locale redirects Spotify makes
     * (`open.spotify.com/intl-de/` …) while staying on its own host.
     */
    private fun probeHome(
        client: OkHttpClient,
        cookieHeader: String,
        userAgent: String,
        acceptLanguage: String,
    ): Result {
        var url = HOME_URL
        repeat(3) {
            val probe = fetch(
                client, url, cookieHeader, userAgent, acceptLanguage,
                accept = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            )
            val classified = classifyResponse(probe.code, probe.location, probe.setCookies, probe.body)
            if (classified !is Result.Unverified || probe.code !in 300..399 || probe.location.isNullOrBlank()) {
                return classified
            }
            val resolved = runCatching {
                java.net.URI(probe.url).resolve(probe.location.trim()).toString()
            }.getOrNull() ?: return classified
            if (!runCatching { java.net.URI(resolved).host }.getOrNull()
                    .equals("open.spotify.com", ignoreCase = true)
            ) {
                return classified
            }
            url = resolved
        }
        return Result.Unverified(302, "Spotify kept redirecting the home page")
    }

    /**
     * The token endpoint as a second opinion. It answers `isAnonymous: false` with a real access
     * token for a valid cookie (a clear "yes"), and `isAnonymous: true` for anything else — which
     * since 2025 is also what it says about good cookies, so it can never be a "no" on its own.
     */
    private fun probeToken(
        client: OkHttpClient,
        cookieHeader: String,
        userAgent: String,
        acceptLanguage: String,
    ): Result {
        val probe = fetch(
            client, TOKEN_URL, cookieHeader, userAgent, acceptLanguage,
            accept = "application/json, text/plain, */*",
        )
        val code = probe.code
        if (clearsSpDc(probe.setCookies)) {
            return Result.Rejected("Spotify cleared sp_dc cookie (HTTP $code)")
        }
        if (code in 300..399) {
            return Result.Unverified(code, "Spotify redirected the token endpoint to ${probe.location.orEmpty().take(80)}")
        }
        if (code == 401 || code == 403) {
            return Result.Unverified(code, "Spotify answered $code to the token endpoint (TOTP-gated since 2025)")
        }
        if (code !in 200..299) return Result.Unverified(code, "Spotify answered HTTP $code to the token endpoint")
        val json = runCatching { JSONObject(probe.body) }.getOrNull()
            ?: return Result.Unverified(code, "The token endpoint did not answer JSON")
        val token = json.optString("accessToken")
        if (!json.optBoolean("isAnonymous", true) && token.isNotBlank()) {
            return Result.Accepted(market = null, isPremium = null)
        }
        return Result.Unverified(code, "The token endpoint answered an anonymous session")
    }

    /**
     * [baseClient] should be the app's own client ([MyspotiRuntime.apiHttp]); [userAgent] is the
     * install's normal agent, brought up to a current Chrome here when the device's is old.
     */
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
        val probeAgent = probeUserAgent(userAgent)

        try {
            val home = probeHome(noRedirectClient, cookieHeader, probeAgent, acceptLanguage)
            when (home) {
                is Result.Accepted -> return@withContext home
                is Result.Rejected -> {
                    Logbook.log("cookie check: home page rejected (${home.reason})")
                    return@withContext home
                }
                is Result.NetworkError -> return@withContext home
                is Result.Unverified -> Logbook.log("cookie check: home page unverified (${home.note})")
            }

            val token = probeToken(noRedirectClient, cookieHeader, probeAgent, acceptLanguage)
            when (token) {
                is Result.Accepted -> {
                    Logbook.log("cookie check: token endpoint confirmed the session")
                    return@withContext token
                }
                is Result.Rejected -> {
                    Logbook.log("cookie check: token endpoint rejected (${token.reason})")
                    return@withContext token
                }
                is Result.Unverified -> Logbook.log("cookie check: token endpoint unverified (${token.note})")
                is Result.NetworkError -> Unit
            }

            val homeNote = (home as? Result.Unverified)?.note
            val tokenNote = (token as? Result.Unverified)?.note
            val status = (token as? Result.Unverified)?.httpStatus ?: (home as? Result.Unverified)?.httpStatus ?: 200
            val note = listOfNotNull(homeNote, tokenNote).distinct().joinToString("; ")
                .ifBlank { "Spotify answered, but did not confirm the session" }
            Result.Unverified(httpStatus = status, note = note)
        } catch (e: IOException) {
            val msg = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
            Result.NetworkError(msg)
        } catch (e: RuntimeException) {
            // A JSON/parsing surprise must never turn into "the cookie is wrong".
            Result.NetworkError(e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName)
        }
    }
}
