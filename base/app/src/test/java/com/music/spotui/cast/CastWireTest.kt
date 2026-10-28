package com.music.spotui.cast

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException

class CastWireTest {
    @Test fun messageEncodesTheProtobufFields() {
        val bytes = CastMessage("s", "r", "n", "{}").encode()
        val expected = byteArrayOf(
            0x08, 0x00, // protocol_version CASTV2_1_0
            0x12, 0x01, 's'.code.toByte(),
            0x1a, 0x01, 'r'.code.toByte(),
            0x22, 0x01, 'n'.code.toByte(),
            0x28, 0x00, // payload_type STRING
            0x32, 0x02, '{'.code.toByte(), '}'.code.toByte(),
        )
        assertArrayEquals(expected, bytes)
    }

    @Test fun messageRoundTripsWithLongAndUnicodePayloads() {
        val m = CastMessage("sender-1", "receiver-0", CastProtocol.NS_MEDIA, "{\"title\":\"Café ".padEnd(300, 'x') + "\"}")
        assertEquals(m, CastMessage.decode(m.encode()))
        val b = CastMessage("a", "b", "c", binary = byteArrayOf(1, 2, 3))
        assertArrayEquals(byteArrayOf(1, 2, 3), CastMessage.decode(b.encode()).binary)
    }

    @Test fun decodeSkipsUnknownFields() {
        // field 9 varint (0x48 0x05), field 10 fixed32 (0x55 + 4 bytes), then a known field
        val bytes = byteArrayOf(0x48, 0x05, 0x55, 1, 2, 3, 4) + CastMessage("x", "y", "z", "p").encode()
        assertEquals(CastMessage("x", "y", "z", "p"), CastMessage.decode(bytes))
    }

    @Test(expected = IOException::class) fun decodeRefusesATruncatedField() {
        val bytes = CastMessage("sender", "receiver", "ns", "payload").encode()
        CastMessage.decode(bytes.copyOf(bytes.size - 3))
    }

    @Test fun framesAreLengthPrefixedAndReadOneByOne() {
        val out = ByteArrayOutputStream()
        val a = CastMessage("s", "r", CastProtocol.NS_HEARTBEAT, "{\"type\":\"PING\"}")
        val b = CastMessage("r", "s", CastProtocol.NS_HEARTBEAT, "{\"type\":\"PONG\"}")
        CastMessage.write(out, a); CastMessage.write(out, b)
        val raw = out.toByteArray()
        assertEquals(a.encode().size, (raw[0].toInt() shl 24) or (raw[1].toInt() shl 16) or (raw[2].toInt() shl 8) or raw[3].toInt())
        val input = DataInputStream(ByteArrayInputStream(raw))
        assertEquals(a, CastMessage.read(input))
        assertEquals(b, CastMessage.read(input))
    }

    @Test(expected = IOException::class) fun oversizedFrameIsRefused() {
        val n = CastMessage.MAX_SIZE + 1
        CastMessage.read(DataInputStream(ByteArrayInputStream(byteArrayOf((n ushr 24).toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte()))))
    }

    @Test fun rangeParsing() {
        assertEquals(ByteRange.Full, ByteRange.parse(null, 1000))
        assertEquals(ByteRange.Part(0, 999), ByteRange.parse("bytes=0-", 1000))
        assertEquals(ByteRange.Part(100, 199), ByteRange.parse("bytes=100-199", 1000))
        assertEquals(ByteRange.Part(500, 999), ByteRange.parse("bytes=500-5000", 1000))
        assertEquals(ByteRange.Part(900, 999), ByteRange.parse("bytes=-100", 1000))
        assertEquals(ByteRange.Part(0, 999), ByteRange.parse("bytes=-5000", 1000))
        assertEquals(ByteRange.Part(10, 10), ByteRange.parse("Bytes=10-10", 1000))
        assertEquals(ByteRange.Unsatisfiable, ByteRange.parse("bytes=1000-", 1000))
        assertEquals(ByteRange.Unsatisfiable, ByteRange.parse("bytes=-0", 1000))
        // Ignored: several ranges, end before start, garbage, other units, length not known.
        assertEquals(ByteRange.Full, ByteRange.parse("bytes=0-1,5-9", 1000))
        assertEquals(ByteRange.Full, ByteRange.parse("bytes=200-100", 1000))
        assertEquals(ByteRange.Full, ByteRange.parse("bytes=abc-", 1000))
        assertEquals(ByteRange.Full, ByteRange.parse("items=0-1", 1000))
        assertEquals(ByteRange.Full, ByteRange.parse("bytes=100-", -1))
        assertEquals(100L, (ByteRange.parse("bytes=100-199", 1000) as ByteRange.Part).length)
    }

    @Test fun audioTypeFromFirstBytes() {
        fun b(vararg x: Int) = ByteArray(x.size) { x[it].toByte() }
        fun s(t: String) = t.toByteArray(Charsets.ISO_8859_1)
        assertEquals("audio/flac", CastFormats.mime(s("fLaC\u0000\u0000")))
        assertEquals("audio/ogg", CastFormats.mime(s("OggS\u0000")))
        assertEquals("audio/mp4", CastFormats.mime(s("\u0000\u0000\u0000 ftypM4A ")))
        assertEquals("audio/wav", CastFormats.mime(s("RIFF\u0000\u0000\u0000\u0000WAVEfmt ")))
        assertEquals("audio/webm", CastFormats.mime(b(0x1A, 0x45, 0xDF, 0xA3, 0x01)))
        assertEquals("audio/aac", CastFormats.mime(b(0xFF, 0xF1, 0x50, 0x80)))
        assertEquals("audio/mpeg", CastFormats.mime(b(0xFF, 0xFB, 0x90, 0x64)))
        assertNull(CastFormats.mime(s("hello")))
        assertNull(CastFormats.mime(ByteArray(0)))
        assertEquals("audio/flac", CastFormats.byName("song.FLAC"))
        assertEquals("audio/mpeg", CastFormats.byName("1234"))
    }

    @Test fun id3TagSizeIsSyncsafe() {
        // ID3v2.4, flags 0, size 0x00 0x00 0x02 0x01 = 257 (+10 header)
        val head = byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 4, 0, 0, 0, 0, 2, 1)
        assertEquals(267L, CastFormats.id3Size(head))
        head[5] = 0x10 // footer present
        assertEquals(277L, CastFormats.id3Size(head))
        assertEquals(0L, CastFormats.id3Size("fLaC000000".toByteArray()))
    }

    @Test fun loadAndStatusMessages() {
        val load = CastProtocol.load(7, "sess", "http://192.168.1.2:4000/t/a/1", "audio/flac", 185_500,
            "Title", "Artist", "Album", "https://i.example/c.jpg", 12_000, autoplay = true)
        assertEquals("LOAD", load.getString("type"))
        assertEquals(12.0, load.getDouble("currentTime"), 0.0)
        val media = load.getJSONObject("media")
        assertEquals("audio/flac", media.getString("contentType"))
        assertEquals(185.5, media.getDouble("duration"), 0.0)
        assertEquals(3, media.getJSONObject("metadata").getInt("metadataType"))
        assertEquals("https://i.example/c.jpg", media.getJSONObject("metadata").getJSONArray("images").getJSONObject(0).getString("url"))

        val receiver = CastProtocol.receiver(JSONObject("""{"type":"RECEIVER_STATUS","requestId":1,"status":{"applications":[
            {"appId":"E8C28D3C","sessionId":"x","transportId":"y"},
            {"appId":"CC1AD845","sessionId":"abc","transportId":"web-5"}],"volume":{"level":0.25,"muted":false}}}"""))
        assertEquals(CastProtocol.App("abc", "web-5"), receiver.app)
        assertEquals(0.25, receiver.volume!!, 0.0)
        assertNull(CastProtocol.receiver(JSONObject("""{"status":{"applications":[]}}""")).app)

        val st = CastProtocol.media(JSONObject("""{"type":"MEDIA_STATUS","status":[{"mediaSessionId":3,"playerState":"IDLE",
            "idleReason":"FINISHED","currentTime":61.25,"media":{"contentId":"u"}}],"requestId":0}"""))!!
        assertEquals(3, st.mediaSessionId)
        assertEquals("FINISHED", st.idleReason)
        assertEquals(61_250L, st.positionMs)
        assertNull(CastProtocol.media(JSONObject("""{"type":"MEDIA_STATUS","status":[]}""")))
        assertTrue(CastProtocol.seek(9, 3, 1500).getDouble("currentTime") == 1.5)
    }
}
