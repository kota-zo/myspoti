// Myspoti (https://github.com/kota-zo/myspoti), GPL-3.0.
// Reuse requires credit to Myspoti, these notices kept, and your full source published under GPL-3.0.
package com.music.spotui.connect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class QrLoginProtocolTest {

    @Test
    fun parsePairUriAcceptsHttpAndDeepLinkOnLanOnly() {
        val httpTarget = QrLoginProtocol.parsePairUri("http://192.168.1.42:49210/pair?t=abcDEF123456_xyz")
        assertNotNull(httpTarget)
        assertEquals("192.168.1.42", httpTarget!!.host)
        assertEquals(49210, httpTarget.port)
        assertEquals("abcDEF123456_xyz", httpTarget.token)

        val deepTarget = QrLoginProtocol.parsePairUri(
            "myspoti://pair?host=10.0.0.15&port=51234&t=token_1234567890&name=Living+Room+TV",
        )
        assertNotNull(deepTarget)
        assertEquals("10.0.0.15", deepTarget!!.host)
        assertEquals(51234, deepTarget.port)
        assertEquals("token_1234567890", deepTarget.token)
        assertEquals("Living Room TV", deepTarget.name)

        // Reject public IPs, DNS hostnames, loopback, privileged ports, and bad tokens
        assertNull(QrLoginProtocol.parsePairUri("http://8.8.8.8:8080/pair?t=abcDEF123456_xyz"))
        assertNull(QrLoginProtocol.parsePairUri("http://evil.example:8080/pair?t=abcDEF123456_xyz"))
        assertNull(QrLoginProtocol.parsePairUri("http://127.0.0.1:8080/pair?t=abcDEF123456_xyz"))
        assertNull(QrLoginProtocol.parsePairUri("http://192.168.1.42:80/pair?t=abcDEF123456_xyz"))
        assertNull(QrLoginProtocol.parsePairUri("https://192.168.1.42:8080/pair?t=abcDEF123456_xyz"))
        assertNull(QrLoginProtocol.parsePairUri("myspoti://pair?host=1.1.1.1&port=51234&t=token_1234567890"))
    }

    @Test
    fun parseManualPairTargetSupportsHostPortAndPin() {
        val t = QrLoginProtocol.parseManualPairTarget("192.168.50.9:43210", "123 456")
        assertNotNull(t)
        assertEquals("192.168.50.9", t!!.host)
        assertEquals(43210, t.port)
        assertEquals("123456", t.pin)

        assertNull(QrLoginProtocol.parseManualPairTarget("192.168.50.9:43210", "12345"))
        assertNull(QrLoginProtocol.parseManualPairTarget("93.184.216.34:43210", "123456"))
    }

    @Test
    fun parseCookieInputHandlesAllExportFormatsAndRejectsUnsafe() {
        val sampleDc = "AQD_testCookieValue1234567890"
        val sampleKey = "keyValue_9876543210abcdef"

        // 1) Bare sp_dc token
        assertEquals(mapOf("sp_dc" to sampleDc), QrLoginProtocol.parseCookieInput(sampleDc))

        // 2) Cookie header string with other irrelevant/unwanted keys ignored
        val fromHeader = QrLoginProtocol.parseCookieInput("Cookie: sp_dc=$sampleDc; other=ignored_value; sp_key=$sampleKey")
        assertEquals(mapOf("sp_dc" to sampleDc, "sp_key" to sampleKey), fromHeader)

        // 3) JSON object (map or wrapped cookies) and Cookie-Editor JSON array
        val fromJsonObj = QrLoginProtocol.parseCookieInput("""{"cookies":{"sp_dc":"$sampleDc","sp_t":"t_1234567890abcdef"}}""")
        assertEquals(mapOf("sp_dc" to sampleDc, "sp_t" to "t_1234567890abcdef"), fromJsonObj)

        val fromJsonArr = QrLoginProtocol.parseCookieInput(
            """[{"name":"sp_dc","value":"$sampleDc"},{"name":"foo","value":"bar_12345678901234"}]""",
        )
        assertEquals(mapOf("sp_dc" to sampleDc), fromJsonArr)

        // 4) Netscape cookies.txt
        val netscape = ".spotify.com\tTRUE\t/\tTRUE\t2147483647\tsp_dc\t$sampleDc\n"
        assertEquals(mapOf("sp_dc" to sampleDc), QrLoginProtocol.parseCookieInput(netscape))

        // Reject too-short / injection / missing sp_dc
        assertNull(QrLoginProtocol.parseCookieInput("short"))
        assertNull(QrLoginProtocol.parseCookieInput("sp_key=$sampleKey"))
        assertNull(QrLoginProtocol.parseCookieInput("$sampleDc;evil"))
        assertNull(QrLoginProtocol.parseCookieInput("AQD bad value with spaces 123456"))
    }

    @Test
    fun pairingHtmlHidesTokenAndDeepLinkWithoutAuthorizedQueryToken() {
        val unauthHtml = QrHttp.pairingHtml("Living Room TV", "192.168.1.50", 49152, authorizedToken = null)
        assertFalse(unauthHtml.contains("myspoti://pair"))
        assertTrue(unauthHtml.contains("id=\"pinRow\" style=\"display:block\""))

        val authHtml = QrHttp.pairingHtml("Living Room TV", "192.168.1.50", 49152, authorizedToken = "secretToken123456")
        assertTrue(authHtml.contains("myspoti://pair?host=192.168.1.50&amp;port=49152&amp;t=secretToken123456"))
        assertTrue(authHtml.contains("id=\"pinRow\" style=\"display:none\""))
        assertTrue(authHtml.contains("d.state==='failed'"))
        assertTrue(authHtml.contains("d.message"))
    }

    @Test
    fun httpParserReadsPostJsonPayload() {
        val body = """{"token":"abcDEF123456","cookieText":"AQD_testCookieValue1234567890"}"""
        val raw = buildString {
            append("POST /submit HTTP/1.1\r\n")
            append("Host: 192.168.1.50:49152\r\n")
            append("Content-Type: application/json; charset=utf-8\r\n")
            append("Content-Length: ").append(body.toByteArray(Charsets.UTF_8).size).append("\r\n\r\n")
            append(body)
        }
        val req = QrHttp.readRequest(ByteArrayInputStream(raw.toByteArray(Charsets.UTF_8)))
        assertNotNull(req)
        assertEquals("POST", req!!.method)
        assertEquals("/submit", req.path)
        val json = QrHttp.parseJsonObject(req.body)
        assertNotNull(json)
    }

    @Test
    fun pickBestLanAddressIgnoresVpnTunnelsAndPrefersRealWifi() {
        val candidates = listOf(
            LanCandidate(java.net.InetAddress.getByName("10.8.0.2"), interfaceName = "tun0", isPointToPoint = true),
            LanCandidate(java.net.InetAddress.getByName("10.14.0.2"), interfaceName = "wg0", isVpnOrVirtual = true),
            LanCandidate(java.net.InetAddress.getByName("100.64.0.5"), interfaceName = "tailscale0"),
            LanCandidate(java.net.InetAddress.getByName("192.168.1.45"), interfaceName = "wlan0", isWifiOrEthernet = true),
            LanCandidate(java.net.InetAddress.getByName("fe80::1"), interfaceName = "wlan0", isWifiOrEthernet = true),
        )
        val picked = QrLoginProtocol.pickBestLanAddress(candidates)
        assertEquals("192.168.1.45", picked?.hostAddress)

        // When ONLY VPN tunnels exist, returns null rather than advertising a broken VPN tunnel IP
        val onlyVpn = listOf(
            LanCandidate(java.net.InetAddress.getByName("10.8.0.2"), interfaceName = "tun0"),
            LanCandidate(java.net.InetAddress.getByName("172.16.0.9"), interfaceName = "tun1", isVpnOrVirtual = true),
        )
        assertNull(QrLoginProtocol.pickBestLanAddress(onlyVpn))
    }
}
