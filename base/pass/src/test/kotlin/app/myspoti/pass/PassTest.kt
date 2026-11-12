package app.myspoti.pass

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class PassTest {
    private val payload = PassPayload(from = "phone", prefs = mapOf("settings_prefs" to mapOf("look" to PassPref("s", "glass"))),
        cookies = listOf(PassCookie("a", "b")))

    @Test fun `a sealed payload opens with the receiver's key and token only`() {
        val mine = PassCrypto.keyPair()
        val token = PassCrypto.randomBytes(16)
        val sealed = PassCrypto.seal(mine.public.encoded, token, payload.toBytes())
        assertEquals(payload, PassPayload.of(PassCrypto.open(mine, token, sealed)))
        try { PassCrypto.open(mine, PassCrypto.randomBytes(16), sealed); fail("another token opened it") } catch (_: Exception) {}
        try { PassCrypto.open(PassCrypto.keyPair(), token, sealed); fail("another key opened it") } catch (_: Exception) {}
    }

    @Test fun `the invitation survives its QR text and refuses a public address`() {
        val i = PassInvite("192.168.1.20", 41234, PassCrypto.keyPair().public.encoded, PassCrypto.randomBytes(16), "Salon TV")
        val back = PassInvite.parse(i.toText())!!
        assertEquals(i.host, back.host); assertEquals(i.port, back.port); assertEquals(i.name, back.name)
        assertTrue(i.key.contentEquals(back.key)); assertTrue(i.token.contentEquals(back.token))
        assertNull(PassInvite.parse(i.copy(host = "8.8.8.8").toText()))
        assertNull(PassInvite.parse("https://open.spotify.com/"))
    }

    private val invite = PassInvite("192.168.1.20", 41234, PassCrypto.keyPair().public.encoded, PassCrypto.randomBytes(16), "Salon TV")
    private fun b64(b: ByteArray) = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(b)
    private fun same(a: PassInvite, b: PassInvite?) {
        assertNotNull(b); b!!
        assertEquals(a.host, b.host); assertEquals(a.port, b.port); assertEquals(a.name, b.name)
        assertTrue(a.key.contentEquals(b.key)); assertTrue(a.token.contentEquals(b.token))
    }

    @Test fun `the QR code is a link of the app's scheme that a camera opens`() {
        val text = invite.toText()
        assertTrue(text, text.startsWith("orpheon://pass?v=1&a=192.168.1.20:41234&k="))
        val uri = java.net.URI(text)
        assertEquals("orpheon", uri.scheme); assertEquals("pass", uri.host)
        same(invite, PassInvite.parse(text))
        // As a camera or a browser may hand it on: other case for the scheme and host, a slash, spaces.
        same(invite, PassInvite.parse("  ORPHEON://Pass/?" + text.substringAfter('?') + "\n"))
    }

    @Test fun `the codes of before still read`() {
        val old = "orpheon-pass:1?a=192.168.1.20:41234&k=${b64(invite.key)}&t=${b64(invite.token)}&n=Salon+TV"
        same(invite, PassInvite.parse(old))
    }

    @Test fun `a link of another host, scheme, version or without its parts is not an invitation`() {
        val q = invite.toText().substringAfter('?')
        listOf(
            "orpheon://other?$q", "orpheon://pass.example.com?$q", "orpheon://pass/x?$q", "orpheon://pass",
            "orpheon://pass?", "https://pass?$q", "https://example.com/pass?$q", "orpheon-pass:2?$q", "orpheon:pass?$q",
            "orpheon://pass?${q.replace("v=1", "v=2")}",
            "orpheon://pass?${q.replace(Regex("&t=[^&]*"), "")}",
            "orpheon://pass?${q.replace(Regex("&k=[^&]*"), "&k=AAAA")}",
            "orpheon://pass?${q.replace(Regex("&t=[^&]*"), "&t=AAAA")}",
            "orpheon://pass?${q.replace("192.168.1.20:41234", "192.168.1.20")}",
            "orpheon://pass?${q.replace("192.168.1.20:41234", "192.168.1.20:99999")}",
            "orpheon://pass?${q.replace("192.168.1.20", "8.8.8.8")}",
            "orpheon://pass?${q.replace("192.168.1.20", "example.com")}",
            "orpheon://pass?${q.replace("192.168.1.20", "127.0.0.1")}",
            "", "orpheon", "Salon TV",
        ).forEach { assertNull(it, PassInvite.parse(it)) }
    }

    @Test fun `the name shown on the confirmation loses its control and direction characters`() {
        val back = PassInvite.parse(invite.copy(name = "TV\u202E\nvt\u0000").toText())
        assertEquals("TVvt", back!!.name)
    }

    @Test fun `a transfer reaches the receiver over the network once`() {
        val got = CountDownLatch(1)
        var received: PassPayload? = null
        val server = PassServer("TV", onPayload = { received = it; true }, onDone = { got.countDown() }, host = "127.0.0.1").start()
        // Loopback is not a local network address; the invitation is built by hand for the test.
        PassClient.send(server.invite, payload)
        assertTrue(got.await(5, TimeUnit.SECONDS))
        assertNotNull(received); assertEquals("phone", received!!.from)
        try { PassClient.send(server.invite, payload); fail("a second transfer was taken") } catch (_: Exception) {}
    }

    @Test fun `a transfer sealed for another code is refused`() {
        val server = PassServer("TV", onPayload = { true }, host = "127.0.0.1").start()
        try {
            PassClient.send(server.invite.copy(token = PassCrypto.randomBytes(16)), payload)
            fail("taken")
        } catch (e: java.io.IOException) { assertTrue(e.message!!.contains("refused")) } finally { server.stop() }
    }

    @Test fun `the link answers a question sealed with its secret, and only once`() {
        val secret = PassCrypto.randomBytes(PassLink.SECRET_BYTES)
        PassLink.Server(secret, allowed = { it == "127.0.0.1" }) { path, body -> "$path:$body" }.start().use { server ->
            assertEquals("/session:fresh", PassLink.ask("127.0.0.1", server.port, secret, "/session", "fresh"))
            // Another secret: no answer (the frame does not open).
            val other = PassCrypto.randomBytes(PassLink.SECRET_BYTES)
            try { PassLink.ask("127.0.0.1", server.port, other, "/session"); fail("answered a wrong secret") } catch (_: Exception) {}
            // The same sealed question sent again is not answered.
            val key = PassLink.key(secret)
            val q = PassLink.seal(key, "q".encodeToByteArray(), "${System.currentTimeMillis()}\n/x\n".encodeToByteArray())
            fun send(): Boolean = java.net.Socket("127.0.0.1", server.port).use { s ->
                s.soTimeout = 3_000
                PassLink.write(java.io.DataOutputStream(s.getOutputStream()), q)
                runCatching { PassLink.read(java.io.DataInputStream(s.getInputStream())) }.isSuccess
            }
            assertTrue(send())
            assertTrue(!send())
        }
    }
}
