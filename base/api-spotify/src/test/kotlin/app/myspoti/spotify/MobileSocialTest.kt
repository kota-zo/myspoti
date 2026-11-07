package app.myspoti.spotify

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The mobile client's protobuf services: the wire codec, the friend feed
 * (`listening-activity/v1/feed`) and Messages (`speechless/v1`). Fixtures follow the shapes seen
 * live (2026-10-30), with made-up ids.
 */
class MobileSocialTest {
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

    private fun ProtoWriter.hierarchy(id: String, members: List<String>, name: String? = null, seen: Boolean = true, status: Long = 2) = message(1) {
        message(1) { string(1, id); varint(2, 1); string(3, "spotify:chat:$id") }
        message(2) { members.forEach { string(2, it) } }
        message(3) { if (seen) varint(2, 1); string(5, "rev") }
        message(4) { string(1, members.first()); varint(2, status); varint(3, 2); varint(5, if (name == null) 1 else 2) }
        message(5) { name?.let { string(1, it) }; members.forEach { m -> message(2) { string(1, m) } } }
    }

    private fun ProtoWriter.node(field: Int, id: String, author: String, ms: Long, text: String? = null, share: String? = null, deleted: Boolean = false) = message(field) {
        message(1) { string(1, id); string(3, "spotify:chat-message:$id") }
        if (deleted) message(5) { message(5) { timestamp(1, ms) } }
        message(6) { string(1, author); message(2) { timestamp(1, ms); string(2, "event") } }
        message(8) { message(1) {
            text?.let { message(1) { string(1, it) } }
            share?.let { message(2) { string(1, it); string(2, "spotify:chat-share-preview:x") } }
        } }
    }

    @Test fun `chat list reads conversations with their latest message`() {
        val body = proto {
            message(1) { hierarchy("c1", listOf("anna", "me"), seen = false); node(2, "m1", "anna", 2_000, text = "Salut ça va ?") }
            message(1) { hierarchy("c2", listOf("me", "ben", "cleo"), name = "Band"); node(2, "m2", "me", 1_000, share = "spotify:track:t1?si=abc") }
            message(1) { hierarchy("c3", listOf("dan", "me"), status = 4) }
        }
        val chats = rawChats(body)
        assertEquals(listOf("c1", "c2"), chats.map { it.id })
        assertNull(chats[0].name)
        assertTrue(chats[0].unread)
        assertEquals("Salut ça va ?", chats[0].last?.text)
        assertEquals("Band", chats[1].name)
        assertFalse(chats[1].unread)
        assertEquals(listOf("me", "ben", "cleo"), chats[1].usernames)
        assertEquals("spotify:track:t1", chats[1].last?.sharedUri)
    }

    @Test fun `reactions, chat events and GIFs are read, receipts are not messages`() {
        val body = proto {
            // A text with a reaction (a child node, as seen live) and an old fixed one (no emoji, emoti 2).
            message(2) {
                message(1) { string(1, "m1") }
                message(3) { message(1) { string(1, "r1") }; message(8) { message(2) { message(1) { varint(1, 9); string(2, "anna"); string(3, "🔥") } } } }
                message(3) { message(1) { string(1, "r2") }; message(8) { message(2) { message(1) { varint(1, 2); string(2, "ben") } } } }
                message(6) { string(1, "me"); message(2) { timestamp(1, 1_000) } }
                message(8) { message(1) { message(1) { string(1, "hi") } } }
            }
            // Ben added Cleo.
            message(2) { message(1) { string(1, "m2") }; message(6) { string(1, "ben"); message(2) { timestamp(1, 2_000) } }
                message(8) { message(1) { message(5) { message(1) { message(2) { string(1, "cleo") } } } } } }
            // A GIF, and a photo.
            message(2) { message(1) { string(1, "m3") }; message(6) { string(1, "anna"); message(2) { timestamp(1, 3_000) } }
                message(8) { message(1) { message(6) { message(1) { string(1, "gif42") } } } } }
            message(2) { message(1) { string(1, "m4") }; message(6) { string(1, "anna"); message(2) { timestamp(1, 4_000) } }
                message(8) { message(1) { message(6) { message(3) { message(1) { string(1, "asset") } } } } } }
            // A receipt: not a message.
            message(2) { message(1) { string(1, "m5") }; message(8) { message(2) { message(2) { varint(1, 3) } } } }
        }
        val m = rawMessages(body)
        assertEquals(listOf("m1", "m2", "m3", "m4"), m.map { it.id })
        assertEquals(listOf(RawReaction("r1", "🔥", "anna"), RawReaction("r2", "❤️", "ben")), m[0].reactions)
        assertEquals(app.myspoti.api.ChatEvent.Kind.ADDED to "cleo", m[1].event)
        assertEquals("gif42", m[2].gifId)
        assertTrue(m[3].media)
    }

    @Test fun `a group the account administers, and a request sent to it`() {
        val body = proto {
            message(1) { message(1) {
                message(1) { string(1, "g1") }
                message(4) { varint(2, 2); varint(3, 2); varint(5, 2) }
                message(5) { string(1, "Band"); message(2) { string(1, "me"); varint(3, 2) }; message(2) { string(1, "anna"); varint(3, 1) } }
            } }
            message(1) { message(1) {
                message(1) { string(1, "d1") }
                message(4) { varint(2, 1); varint(3, 3); varint(5, 1); message(9) { varint(1, 2) } }
                message(5) { message(2) { string(1, "me") }; message(2) { string(1, "zed") } }
            } }
            // One the account left: not listed.
            message(1) { message(1) { message(1) { string(1, "gone") }; message(4) { varint(2, 2); varint(3, 7) } } }
        }
        val chats = rawChats(body)
        assertEquals(listOf("g1", "d1"), chats.map { it.id })
        assertTrue(chats[0].group)
        assertEquals(setOf("me"), chats[0].admins)
        assertFalse(chats[1].group)
        assertEquals(app.myspoti.api.ChatRequest.INCOMING, chats[1].request)
    }

    @Test fun `a created conversation's id is read from the answer's hierarchy`() {
        assertEquals("c9", createdChatId(proto { hierarchy("c9", listOf("me", "anna")) }))
        assertNull(createdChatId(ByteArray(0)))
    }

    @Test fun `the existing direct conversation is found among the shared ones`() {
        // As seen live: a direct conversation (type 1) and a two-person group (type 2) with the same person.
        val body = proto {
            message(1) { string(1, "louis"); varint(2, 1) }
            message(2) {
                string(1, "louis")
                message(2) {
                    message(1) { message(1) { string(1, "group1"); string(3, "spotify:chat:group1") }; varint(2, 2) }
                    message(1) { message(1) { string(1, "dm1"); string(3, "spotify:chat:dm1") }; varint(2, 1) }
                }
            }
        }
        assertEquals("dm1", directChatId(body, "louis"))
        assertNull(directChatId(body, "zoe"))
        assertNull(directChatId(proto { message(1) { string(1, "louis"); varint(2, 1) } }, "louis"))
    }

    @Test fun `messages come oldest first, without deleted ones or reactions`() {
        val body = proto {
            hierarchy("c1", listOf("anna", "me"))
            node(2, "m3", "anna", 3_000, text = "third")
            node(2, "m2", "me", 2_000, share = "spotify:album:a1")
            node(2, "gone", "anna", 1_500, text = "deleted", deleted = true)
            // A reaction: an activity payload (8.2), not a contribution.
            message(2) { message(1) { string(1, "r1") }; message(6) { string(1, "anna") }; message(8) { message(2) { message(1) { varint(1, 2) } } } }
            node(2, "m1", "anna", 1_000, text = "first")
        }
        val messages = rawMessages(body)
        assertEquals(listOf("m1", "m2", "m3"), messages.map { it.id })
        assertEquals(listOf(1_000L, 2_000L, 3_000L), messages.map { it.timestampMs })
        assertEquals("spotify:album:a1", messages[1].sharedUri)
        assertEquals("me", messages[1].authorUsername)
    }

    @Test fun `a conversation whose last message was deleted keeps it as deleted, with its time`() {
        val body = proto { message(1) { hierarchy("c1", listOf("anna", "me")); node(2, "m9", "me", 5_000, text = "gone", deleted = true) } }
        val last = rawChats(body).single().last!!
        assertTrue(last.deleted)
        assertEquals(5_000L, last.timestampMs)
        assertNull(last.text)
    }

    @Test fun `gif search answers are read, without the ones lacking an id or a url`() {
        val body = proto {
            message(1) { string(1, "g1"); string(2, "https://media.giphy.com/g1/200w_d.gif"); string(3, "Test"); varint(4, 2); varint(5, 200); varint(6, 150) }
            message(1) { string(1, "g2"); varint(5, 200) }
            message(1) { string(2, "https://media.giphy.com/x.gif") }
        }
        val gifs = rawGifs(body)
        assertEquals(listOf("g1"), gifs.map { it.id })
        assertEquals(200, gifs[0].width)
        assertEquals(150, gifs[0].height)
        assertEquals("https://media.giphy.com/g1/200w_d.gif", gifs[0].url)
    }

    @Test fun `a message, a conversation or a gif sent twice is listed once`() {
        val messages = proto { node(2, "m1", "anna", 1_000, text = "hi"); node(2, "m1", "anna", 1_000, text = "hi") }
        assertEquals(listOf("m1"), rawMessages(messages).map { it.id })
        val chat = proto { message(1) { hierarchy("c1", listOf("anna", "me")) }; message(1) { hierarchy("c1", listOf("anna", "me")) } }
        assertEquals(listOf("c1"), rawChats(chat).map { it.id })
        val gifs = proto { repeat(2) { message(1) { string(1, "g1"); string(2, "https://media.giphy.com/g1/200w_d.gif") } } }
        assertEquals(listOf("g1"), rawGifs(gifs).map { it.id })
    }
}
