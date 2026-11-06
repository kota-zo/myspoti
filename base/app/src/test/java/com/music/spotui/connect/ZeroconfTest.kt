package com.music.spotui.connect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class ZeroconfTest {
    private val sonosInfo = """{"status":101,"statusString":"OK","spotifyError":0,"version":"2.7.1","deviceID":"abc123","remoteName":"Kitchen",
        "activeUser":"","brandDisplayName":"Sonos","modelDisplayName":"One","clientID":"client9","tokenType":"authorization_code"}"""

    @Test fun pathAlwaysStartsWithOneSlash() {
        assertEquals("/", Zeroconf.path(null))
        assertEquals("/", Zeroconf.path(""))
        assertEquals("/spotifyzc", Zeroconf.path("/spotifyzc"))
        assertEquals("/zc", Zeroconf.path(" zc "))
    }

    @Test fun getInfoIsParsedAndLoginable() {
        val i = Zeroconf.parseInfo(sonosInfo)!!
        assertEquals("abc123", i.deviceId)
        assertEquals("Kitchen", i.name)
        assertEquals("client9", i.clientId)
        assertEquals("authorization_code", i.tokenType)
        assertTrue(i.loginable)
    }

    @Test fun anAnswerWithoutIdsOrWithAnErrorIsNotAnInfo() {
        assertNull(Zeroconf.parseInfo("""{"status":102,"statusString":"ERROR","deviceID":"x"}"""))
        assertNull(Zeroconf.parseInfo("""{"status":101}"""))
        assertNull(Zeroconf.parseInfo("not json"))
        assertFalse(Zeroconf.parseInfo("""{"status":101,"deviceID":"x"}""")!!.loginable)
    }

    @Test fun addUserIsAFormPostWithTheActionInTheQuery() {
        val info = Zeroconf.parseInfo(sonosInfo)!!
        val text = String(Zeroconf.addUserRequest("192.168.1.5", 1400, "/spotifyzc", "me", "tok+/=", info, "Pixel", "id1"), Charsets.UTF_8)
        val (head, body) = text.split("\r\n\r\n")
        assertTrue(head.startsWith("POST /spotifyzc?action=addUser HTTP/1.1"))
        assertTrue(head.contains("Host: 192.168.1.5:1400"))
        assertTrue(head.contains("Content-Length: ${body.toByteArray().size}"))
        val fields = body.split('&').associate { it.substringBefore('=') to java.net.URLDecoder.decode(it.substringAfter('='), "UTF-8") }
        assertEquals("tok+/=", fields["blob"])
        assertEquals("accesstoken", fields["tokenType"])
        assertEquals("client9", fields["clientKey"])
        assertEquals("me", fields["userName"])
        assertEquals("Pixel", fields["deviceName"])
    }

    @Test fun aCodeDeviceIsToldWhatSonosIsSent() {
        val info = Zeroconf.parseInfo(sonosInfo.replace("2.7.1", "2.9.0"))!!
        assertTrue(info.wantsCode)
        val text = String(Zeroconf.addUserCodeRequest("192.168.1.5", 1400, "/spotifyzc", "me", "me", "AQC+/=", info, "Pixel"), Charsets.UTF_8)
        val (head, body) = text.split("\r\n\r\n")
        assertTrue(head.startsWith("POST /spotifyzc?action=addUser HTTP/1.1"))
        assertTrue(head.contains("Content-Length: ${body.toByteArray().size}"))
        val fields = body.split('&').associate { it.substringBefore('=') to java.net.URLDecoder.decode(it.substringAfter('='), "UTF-8") }
        assertEquals("addUser", fields["action"])
        assertEquals("2.9.0", fields["version"])
        assertEquals("authorization_code", fields["tokenType"])
        assertEquals("", fields["clientKey"])
        assertEquals("me", fields["userName"])
        assertEquals("me", fields["loginId"])
        assertEquals("AQC+/=", fields["blob"])
        assertEquals("Pixel", fields["deviceName"])
        assertEquals("e51f224d70d3df8c10d5e456b6ab98229715fe7e", fields["deviceId"])
        assertEquals("2.7.1", fields(Zeroconf.addUserCodeRequest("h", 1, "/", "u", "u", "c", info.copy(version = ""), "P"))["version"])
    }

    private fun fields(request: ByteArray) = String(request, Charsets.UTF_8).substringAfter("\r\n\r\n").split('&')
        .associate { it.substringBefore('=') to java.net.URLDecoder.decode(it.substringAfter('='), "UTF-8") }

    @Test fun aDeviceStillStartingIsSeen() {
        val i = Zeroconf.parseInfo("""{"status":101,"deviceID":"d","clientID":"c","tokenType":"authorization_code","availability":"NOT-LOADED","publicKey":"SU5WQUxJRA==","spotifyError":0}""")!!
        assertEquals("NOT-LOADED", i.availability)
        assertEquals(Zeroconf.INVALID_KEY, i.publicKey)
        assertTrue(i.starting)
        assertFalse(i.copy(availability = "", publicKey = "abc").starting)
        assertTrue(i.copy(availability = "").starting)
        assertEquals(0, Zeroconf.parseInfo(sonosInfo)!!.spotifyError)
        assertFalse(Zeroconf.parseInfo("""{"status":101,"deviceID":"d","tokenType":"accesstoken"}""")!!.wantsCode)
    }

    @Test fun codeAnswers() {
        assertEquals(Zeroconf.CodeAnswer.ACCEPTED, Zeroconf.codeAnswer(200, """{"status":101,"statusString":"OK"}"""))
        assertEquals(Zeroconf.CodeAnswer.ACCEPTED, Zeroconf.codeAnswer(200, "}"))
        assertEquals(Zeroconf.CodeAnswer.LOGIN_FAILED, Zeroconf.codeAnswer(200, """{"status":202,"statusString":"ERROR-LOGIN-FAILED","spotifyError":0}"""))
        assertEquals(Zeroconf.CodeAnswer.INVALID_KEY, Zeroconf.codeAnswer(200, """{"status":203,"statusString":"ERROR-INVALID-PUBLICKEY"}"""))
        assertEquals(Zeroconf.CodeAnswer.GROUPED, Zeroconf.codeAnswer(500, ""))
        assertEquals(Zeroconf.CodeAnswer.GROUPED, Zeroconf.codeAnswer(500, """{"status":102}"""))
        assertEquals(Zeroconf.CodeAnswer.REFUSED, Zeroconf.codeAnswer(200, """{"status":103,"statusString":"ERROR-INVALID-ARGUMENTS"}"""))
        assertEquals(Zeroconf.CodeAnswer.REFUSED, Zeroconf.codeAnswer(404, ""))
        assertEquals("status 202 ERROR-LOGIN-FAILED spotifyError 7", Zeroconf.answerSummary("""{"status":202,"statusString":"ERROR-LOGIN-FAILED","spotifyError":7}"""))
        assertEquals("not JSON len=1", Zeroconf.answerSummary("}"))
    }

    @Test fun responsesAreReadWithAndWithoutChunks() {
        val plain = "HTTP/1.1 200 OK\r\nContent-Length: 5\r\n\r\nhello".toByteArray()
        assertEquals(200 to "hello", Zeroconf.parseResponse(plain))
        val chunked = "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n3\r\nhel\r\n2\r\nlo\r\n0\r\n\r\n".toByteArray()
        assertEquals(200 to "hello", Zeroconf.parseResponse(chunked))
        assertNull(Zeroconf.parseResponse("garbage".toByteArray()))
    }

    @Test fun anAnswerIsCompleteOnceItsLengthArrived() {
        assertFalse(Zeroconf.complete("HTTP/1.1 200 OK\r\nContent-Length: 5\r\n\r\nhel".toByteArray()))
        assertTrue(Zeroconf.complete("HTTP/1.1 200 OK\r\nContent-Length: 5\r\n\r\nhello".toByteArray()))
        assertFalse(Zeroconf.complete("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n3\r\nhel\r\n".toByteArray()))
        assertTrue(Zeroconf.complete("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n3\r\nhel\r\n0\r\n\r\n".toByteArray()))
    }

    @Test fun addUserAnswers() {
        assertTrue(Zeroconf.addUserAccepted(200, """{"status":101,"statusString":"OK"}"""))
        assertTrue(Zeroconf.addUserAccepted(404, ""))
        assertFalse(Zeroconf.addUserAccepted(200, """{"status":103,"statusString":"ERROR-INVALID-ARGUMENTS"}"""))
        assertFalse(Zeroconf.addUserAccepted(500, "oops"))
        assertEquals("status 103 ERROR-INVALID-ARGUMENTS", Zeroconf.answerSummary("""{"status":103,"statusString":"ERROR-INVALID-ARGUMENTS"}"""))
    }

    @Test fun onlyLocalAddressesAreTrusted() {
        for (a in listOf("192.168.1.20", "10.0.0.7", "172.16.4.2", "169.254.3.3", "fd12:3456::1")) assertTrue(a, Zeroconf.isLan(InetAddress.getByName(a)))
        for (a in listOf("8.8.8.8", "127.0.0.1", "2001:4860:4860::8888")) assertFalse(a, Zeroconf.isLan(InetAddress.getByName(a)))
    }

    @Test fun aDeviceIsKnownByIdOrByName() {
        val i = Zeroconf.parseInfo(sonosInfo)!!
        assertTrue(Zeroconf.inConnectList(i, setOf("abc123"), emptySet()))
        assertTrue(Zeroconf.inConnectList(i, emptySet(), setOf("Kitchen")))
        assertFalse(Zeroconf.inConnectList(i, setOf("zzz"), setOf("Bedroom")))
    }
}
