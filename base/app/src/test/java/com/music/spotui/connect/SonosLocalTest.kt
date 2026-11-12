package com.music.spotui.connect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class SonosLocalTest {
    private val info = Zeroconf.parseInfo(
        """{"status":101,"deviceID":"abc","remoteName":"Office","brandDisplayName":"Sonos","clientID":"c","tokenType":"authorization_code","version":"2.9.0","availability":"NOT-LOADED","publicKey":"SU5WQUxJRA=="}""",
    )!!
    private val host = InetAddress.getByAddress(byteArrayOf(192.toByte(), 168.toByte(), 1, 20))

    @Test fun aSonosByItsBrandOrItsPort() {
        assertTrue(SonosLocal.isSonos(LanDevice("s", host, 1400, "/spotifyzc", info)))
        assertTrue(SonosLocal.isSonos(LanDevice("s", host, 1400, "/spotifyzc/", info.copy(brand = ""))))
        assertFalse(SonosLocal.isSonos(LanDevice("b", host, 8200, "/zc", info.copy(brand = "Bose"))))
    }

    @Test fun theSoapRequest() {
        val text = String(SonosLocal.request("192.168.1.20", "Play", "<Speed>1</Speed>"), Charsets.UTF_8)
        assertTrue(text.startsWith("POST /MediaRenderer/AVTransport/Control HTTP/1.1\r\n"))
        assertTrue(text.contains("SOAPACTION: \"urn:schemas-upnp-org:service:AVTransport:1#Play\"\r\n"))
        assertTrue(text.contains("<u:Play xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\"><InstanceID>0</InstanceID><Speed>1</Speed></u:Play>"))
        val body = text.substringAfter("\r\n\r\n")
        assertEquals(body.toByteArray().size, Regex("Content-Length: (\\d+)").find(text)!!.groupValues[1].toInt())
    }

    @Test fun theStateAndTheFault() {
        val answer = "<s:Envelope><s:Body><u:GetTransportInfoResponse><CurrentTransportState>PAUSED_PLAYBACK</CurrentTransportState>" +
            "<CurrentTransportStatus>OK</CurrentTransportStatus></u:GetTransportInfoResponse></s:Body></s:Envelope>"
        assertEquals("PAUSED_PLAYBACK", SonosLocal.transportState(answer))
        assertNull(SonosLocal.transportState("}"))
        assertEquals(701, SonosLocal.faultCode("<UPnPError><errorCode>701</errorCode></UPnPError>"))
    }

    @Test fun playIsPressedOnlyOnAStillSpeakerForASongThatPlayed() {
        assertTrue(SonosLocal.needsPlay("PAUSED_PLAYBACK", true))
        assertTrue(SonosLocal.needsPlay("STOPPED", true))
        assertFalse(SonosLocal.needsPlay("PLAYING", true))
        assertFalse(SonosLocal.needsPlay("TRANSITIONING", true))
        assertFalse(SonosLocal.needsPlay(null, true))
        assertFalse(SonosLocal.needsPlay("PAUSED_PLAYBACK", false))
    }

    @Test fun theWakeHasNeitherBlobNorKey() {
        val text = String(Zeroconf.wakeRequest("192.168.1.20", 1400, "/spotifyzc", info), Charsets.UTF_8)
        assertTrue(text.startsWith("POST /spotifyzc?action=addUser HTTP/1.1\r\n"))
        val body = text.substringAfter("\r\n\r\n")
        assertEquals("action=addUser&version=2.9.0&tokenType=authorization_code&clientKey=&blob=", body)
    }
}
