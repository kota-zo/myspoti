package app.myspoti.host.android

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class SpotifyCookieVerifierTest {

    @Test
    fun buildCookieHeaderOrdersSpotifySessionCookiesOnly() {
        val header = SpotifyCookieVerifier.buildCookieHeader(
            mapOf(
                "sp_t" to "tokenT",
                "other" to "ignored",
                "sp_dc" to "tokenDc",
                "sp_key" to "tokenKey",
            ),
        )
        assertEquals("sp_dc=tokenDc; sp_key=tokenKey; sp_t=tokenT", header)
    }

    @Test
    fun parseAppServerConfigHandlesStandardAndUrlSafeBase64() {
        val json = """{"isAnonymous":false,"isPremium":true,"market":"NL"}"""
        val stdB64 = Base64.getEncoder().encodeToString(json.toByteArray(Charsets.UTF_8))
        val urlB64 = Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray(Charsets.UTF_8))

        val parsedStd = SpotifyCookieVerifier.parseAppServerConfig(
            """<html><head><script id="appServerConfig" type="text/plain">$stdB64</script></head></html>""",
        )
        assertNotNull(parsedStd)
        assertFalse(parsedStd!!.getBoolean("isAnonymous"))
        assertTrue(parsedStd.getBoolean("isPremium"))
        assertEquals("NL", parsedStd.getString("market"))

        val parsedUrl = SpotifyCookieVerifier.parseAppServerConfig(
            """<script id='appServerConfig'>$urlB64</script>""",
        )
        assertNotNull(parsedUrl)
        assertEquals("NL", parsedUrl!!.getString("market"))
        assertNull(SpotifyCookieVerifier.parseAppServerConfig("<html><body>no config</body></html>"))
    }

    @Test
    fun classifyResponseDistinguishesAcceptedRejectedAndBlocked() {
        val acceptedJson = """{"isAnonymous":false,"isPremium":false,"market":"US"}"""
        val acceptedB64 = Base64.getEncoder().encodeToString(acceptedJson.toByteArray(Charsets.UTF_8))
        val accepted = SpotifyCookieVerifier.classifyResponse(
            statusCode = 200,
            location = null,
            setCookieHeaders = emptyList(),
            body = """<script id="appServerConfig">$acceptedB64</script>""",
        )
        assertEquals(SpotifyCookieVerifier.Result.Accepted(market = "US", isPremium = false), accepted)

        val anonJson = """{"isAnonymous":true}"""
        val anonB64 = Base64.getEncoder().encodeToString(anonJson.toByteArray(Charsets.UTF_8))
        val anon = SpotifyCookieVerifier.classifyResponse(
            statusCode = 200,
            location = null,
            setCookieHeaders = emptyList(),
            body = """<script id="appServerConfig">$anonB64</script>""",
        )
        assertTrue(anon is SpotifyCookieVerifier.Result.Rejected)
        // Nothing Spotify says about a cookie may end the import on its own: the WebView still tries.
        assertFalse((anon as SpotifyCookieVerifier.Result.Rejected).definitive)

        val authFailed = SpotifyCookieVerifier.classifyResponse(
            statusCode = 302,
            location = "https://open.spotify.com/?_authfailed=1",
            setCookieHeaders = emptyList(),
            body = "",
        )
        assertTrue(authFailed is SpotifyCookieVerifier.Result.Rejected)

        val clearedCookie = SpotifyCookieVerifier.classifyResponse(
            statusCode = 302,
            location = "/",
            setCookieHeaders = listOf("sp_dc=; Domain=.spotify.com; Path=/; Expires=Thu, 01 Jan 1970 00:00:00 GMT"),
            body = "",
        )
        assertTrue(clearedCookie is SpotifyCookieVerifier.Result.Rejected)

        val unauthorized = SpotifyCookieVerifier.classifyResponse(
            statusCode = 401,
            location = null,
            setCookieHeaders = emptyList(),
            body = "",
        )
        assertTrue(unauthorized is SpotifyCookieVerifier.Result.Rejected)

        // HTTP 403 without clearing sp_dc (e.g. WAF/VPN CDN block) must not claim Spotify rejected sp_dc
        val cdnBlocked = SpotifyCookieVerifier.classifyResponse(
            statusCode = 403,
            location = null,
            setCookieHeaders = emptyList(),
            body = "Error 54113",
        )
        assertTrue(cdnBlocked is SpotifyCookieVerifier.Result.Unverified)
        assertEquals(403, (cdnBlocked as SpotifyCookieVerifier.Result.Unverified).httpStatus)
    }

    @Test
    fun aPageWithoutSessionInfoIsUnverifiedNotRejected() {
        // This is the Android 6 bug: Spotify's home page answered 200 with no session config
        // (it serves a reduced shell to old user agents), and the app told the user the cookie
        // was wrong after a 60 s wait.
        val result = SpotifyCookieVerifier.classifyResponse(
            statusCode = 200,
            location = null,
            setCookieHeaders = emptyList(),
            body = "<html><body><div id=\"main-view\">Home</div></body></html>",
        )
        assertTrue(result is SpotifyCookieVerifier.Result.Unverified)
        assertEquals(200, (result as SpotifyCookieVerifier.Result.Unverified).httpStatus)
    }

    @Test
    fun aRawAnonymousMarkerInTheMarkupCountsWhenTheScriptTagIsGone() {
        val signedIn = SpotifyCookieVerifier.classifyResponse(
            statusCode = 200,
            location = null,
            setCookieHeaders = emptyList(),
            body = """<html><script>window.__CONFIG__ = {"isAnonymous":false,"market":"NL"}</script></html>""",
        )
        assertTrue(signedIn is SpotifyCookieVerifier.Result.Accepted)

        // Both answers in one page (the player's own anonymous token blob): no answer at all.
        val mixed = SpotifyCookieVerifier.classifyResponse(
            statusCode = 200,
            location = null,
            setCookieHeaders = emptyList(),
            body = """{"isAnonymous":false} ... {"isAnonymous":true}""",
        )
        assertTrue(mixed is SpotifyCookieVerifier.Result.Unverified)
    }

    @Test
    fun theCookieProbeBringsAnOldWebViewUserAgentUpToADesktopChrome() {
        // Android 6 TV: WebView Chrome 55. Spotify served that agent a shell with no session info.
        val old = SpotifyCookieVerifier.probeUserAgent(
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/55.0.0.0 Safari/537.36",
        )
        assertTrue(old.contains("Chrome/${SpotifyCookieVerifier.PROBE_CHROME}"))
        assertFalse(old.contains("Chrome/55"))

        // A current WebView keeps the install's own agent.
        val current = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"
        assertEquals(current, SpotifyCookieVerifier.probeUserAgent(current))

        // No Chrome at all (a test or a stripped agent): a plausible desktop Chrome.
        assertTrue(SpotifyCookieVerifier.probeUserAgent("TestAgent/1.0").contains("Chrome/"))
    }

    @Test
    fun base64DecodingWorksWithoutTheJavaUtilDesugaring() {
        val json = """{"isAnonymous":false,"market":"NL"}"""
        val b64 = java.util.Base64.getEncoder().encodeToString(json.toByteArray(Charsets.UTF_8))
        assertEquals(json, SpotifyCookieVerifier.decodeBase64(b64))
    }

    @Test
    fun verifyRejectsMissingSpDcWithoutNetwork() = runBlocking {
        val result = SpotifyCookieVerifier.verify(
            baseClient = OkHttpClient(),
            cookies = mapOf("sp_key" to "only_key"),
            userAgent = "TestAgent/1.0",
        )
        assertTrue(result is SpotifyCookieVerifier.Result.Rejected)
    }

    @Test
    fun cookieJarContainsTokenMatchesExactNameWithValue() {
        assertTrue(WebPlayerHost.cookieJarContainsToken("sp_t=abc; sp_dc=AQD123; sp_key=k", "sp_dc"))
        assertFalse(WebPlayerHost.cookieJarContainsToken("sp_dc=; sp_t=abc", "sp_dc"))
        assertFalse(WebPlayerHost.cookieJarContainsToken("sp_dc_other=123", "sp_dc"))
        assertFalse(WebPlayerHost.cookieJarContainsToken(null, "sp_dc"))
    }
}
