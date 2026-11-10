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
        assertEquals(SpotifyCookieVerifier.Result.Indeterminate(403), cdnBlocked)
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
