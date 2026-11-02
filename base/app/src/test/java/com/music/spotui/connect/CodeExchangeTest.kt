package com.music.spotui.connect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLDecoder

class CodeExchangeTest {
    private fun fields(form: String) = form.split('&').associate { URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8") }

    @Test fun theExchangeAsksACodeForTheSpeaker() {
        val f = fields(CodeExchange.exchangeForm("tok+/=", "9b37", CodeExchange.DESKTOP_CLIENT_ID))
        assertEquals("urn:ietf:params:oauth:grant-type:token-exchange", f["grant_type"])
        assertEquals("tok+/=", f["subject_token"])
        assertEquals("urn:ietf:params:oauth:token-type:access_token", f["subject_token_type"])
        assertEquals("urn:spotify:params:oauth:authorization_code", f["requested_token_type"])
        assertEquals("urn:spotify:resources:connect", f["resource"])
        assertEquals("streaming", f["scope"])
        assertEquals("9b37", f["audience"])
        assertEquals("65b708073fc0480ea92a077233ca87bd", f["client_id"])
        assertEquals(8, f.size)
    }

    @Test fun theAudienceIsTheDevicesClientElseSonos() {
        val info = Zeroconf.parseInfo("""{"status":101,"deviceID":"d","clientID":"abc","tokenType":"authorization_code"}""")!!
        assertEquals("abc", CodeExchange.audience(info))
        assertEquals(CodeExchange.SONOS_CLIENT_ID, CodeExchange.audience(info.copy(clientId = "")))
    }

    @Test fun refreshAndConsentForms() {
        val r = fields(CodeExchange.refreshForm("r1"))
        assertEquals(mapOf("grant_type" to "refresh_token", "refresh_token" to "r1", "client_id" to CodeExchange.DESKTOP_CLIENT_ID), r)
        val c = fields(CodeExchange.consentForm("c1", "v1"))
        assertEquals("authorization_code", c["grant_type"])
        assertEquals("c1", c["code"])
        assertEquals("http://127.0.0.1:4381/login", c["redirect_uri"])
        assertEquals("v1", c["code_verifier"])
        assertEquals(CodeExchange.DESKTOP_CLIENT_ID, c["client_id"])
    }

    @Test fun theConsentPageIsPkceForTheDesktopClient() {
        val url = CodeExchange.authorizeUrl("ch", "st")
        assertTrue(url.startsWith("https://accounts.spotify.com/authorize?"))
        val q = fields(url.substringAfter('?'))
        assertEquals(CodeExchange.DESKTOP_CLIENT_ID, q["client_id"])
        assertEquals("code", q["response_type"])
        assertEquals("http://127.0.0.1:4381/login", q["redirect_uri"])
        assertEquals("S256", q["code_challenge_method"])
        assertEquals("ch", q["code_challenge"])
        assertEquals("streaming", q["scope"])
        assertEquals("st", q["state"])
    }

    @Test fun pkceChallengeIsSha256InBase64Url() {
        assertEquals("qjrzSW9gMiUgpUvqgEPE4_-8swvyCtfOVvg55o5S_es", CodeExchange.challenge("M25iVXpKU3puUjFaYWg3T1NDTDQtcW1ROUY5YXlwalNoc0hhakxifmZHag"))
        val v = CodeExchange.verifier()
        assertEquals(64, v.length)
        assertTrue(v.all { it.isLetterOrDigit() || it == '-' || it == '_' })
        assertTrue(CodeExchange.randomLoginId().matches(Regex("[0-9a-f]{32}")))
    }

    @Test fun onlyTheDesktopRedirectEndsTheConsent() {
        assertEquals(mapOf("code" to "a b", "state" to "s"), CodeExchange.redirected("http://127.0.0.1:4381/login?code=a%20b&state=s"))
        assertEquals("access_denied", CodeExchange.redirected("http://127.0.0.1:4381/login?error=access_denied&state=s")!!["error"])
        assertNull(CodeExchange.redirected("https://accounts.spotify.com/login?code=x"))
        assertNull(CodeExchange.redirected("http://127.0.0.1:4382/login?code=x"))
        assertNull(CodeExchange.redirected("http://127.0.0.1:4381/other?code=x"))
    }

    @Test fun answersOfTheTokenEndpoint() {
        val ok = CodeExchange.parse(200, """{"access_token":"AQC123","expires_in":600,"issued_token_type":"urn:spotify:params:oauth:authorization_code","scope":"streaming","token_type":"Bearer"}""")
        assertTrue(ok.isCode)
        assertEquals("AQC123", ok.accessToken)
        assertEquals("HTTP 200 issued=authorization_code expires=600s len=6", ok.summary)
        assertFalse(ok.summary.contains("AQC123"))

        val token = CodeExchange.parse(200, """{"access_token":"BQ1","refresh_token":"R","expires_in":3600,"issued_token_type":"urn:ietf:params:oauth:token-type:access_token"}""")
        assertTrue(token.isToken)
        assertFalse(token.isCode)
        assertEquals("R", token.refreshToken)
        assertTrue(token.summary.endsWith("+refresh"))

        val refused = CodeExchange.parse(400, """{"error":"unauthorized_client","error_description":"Client not allowed"}""")
        assertFalse(refused.isCode)
        assertEquals("HTTP 400 unauthorized_client Client not allowed", refused.summary)

        val garbage = CodeExchange.parse(502, "<html>")
        assertFalse(garbage.isToken)
        assertEquals("HTTP 502 -", garbage.summary)
    }
}
