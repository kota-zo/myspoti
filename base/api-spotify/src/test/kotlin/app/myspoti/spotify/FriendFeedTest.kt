package app.myspoti.spotify

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The mobile client's friend feed (`listening-activity/v1/feed`) and the protobuf codec it is read
 * with. Fixtures follow the shapes seen live (2026-10-30), with made-up ids.
 */
class FriendFeedTest {
    private fun ProtoWriter.timestamp(n: Int, ms: Long) = message(n) { varint(1, ms / 1000); varint(2, ms % 1000 * 1_000_000) }

    @Test fun `codec round-trips varints, strings and nested messages`() {
        val b = proto { varint(1, 300); string(2, "é ✓"); message(3) { varint(1, 1L shl 40) } }
        assertArrayEquals(byteArrayOf(0x08, 0xAC.toByte(), 0x02), b.copyOfRange(0, 3))
        val m = parseProto(b)
        assertEquals(300L, m.long(1))
        assertEquals("é ✓", m.string(2))
        assertEquals(1L shl 40, m.message(3)?.long(1))
        assertNull(m.long(9))
    }

    private val feed = proto {
        // A friend, played from a playlist, 2 minutes ago.
        message(1) { message(1) {
            string(1, "spotify:user:anna")
            message(2) { string(1, "spotify:track:t1"); string(2, "spotify:playlist:p1"); varint(3, 1); timestamp(4, 1_000_000_000_000) }
        } }
        // Someone followed, whose context is the track itself.
        message(1) { message(2) {
            string(1, "spotify:user:ben")
            message(2) { string(1, "spotify:track:t2"); string(2, "spotify:track:t2"); timestamp(4, 999_000_000_000) }
        } }
        // A group: Anna again (older), and Cleo.
        message(1) { message(3) {
            string(1, "Band"); string(2, "spotify:chat:g1")
            message(3) { string(1, "spotify:user:anna"); message(2) { string(1, "spotify:track:old"); timestamp(4, 1_000) } }
            message(3) { string(1, "spotify:user:cleo"); message(2) { string(1, "spotify:episode:e1"); timestamp(4, 998_000_000_000) } }
        } }
    }

    @Test fun `feed keeps each person's latest listen, latest first`() {
        val rows = listeningFeed(feed)
        assertEquals(listOf("spotify:user:anna", "spotify:user:ben", "spotify:user:cleo"), rows.map { it.userUri })
        val anna = rows[0]
        assertEquals("spotify:track:t1", anna.itemUri)
        assertEquals("spotify:playlist:p1", anna.contextUri)
        assertTrue(anna.playing)
        assertEquals(1_000_000_000_000, anna.timestampMs)
        // A context that is the item itself is no context.
        assertNull(rows[1].contextUri)
        assertFalse(rows[1].playing)
        assertEquals("spotify:episode:e1", rows[2].itemUri)
    }

    @Test fun `empty feed`() {
        assertTrue(listeningFeed(ByteArray(0)).isEmpty())
    }
}
