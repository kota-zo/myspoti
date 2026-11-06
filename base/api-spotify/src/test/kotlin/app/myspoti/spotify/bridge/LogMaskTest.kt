package app.myspoti.spotify.bridge

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LogMaskTest {
    @Test fun `tokens, cookies, passwords and e-mail in JSON are hidden, the rest stays`() {
        val out = LogMask.redact("""{"accessToken":"BQabc","uri":"spotify:track:4uLU6hMCjMI75M1A2tKUQC","email":"me@example.com","password":"hunter2","ms":1200}""")
        assertEquals("""{"accessToken":"(hidden)","uri":"spotify:track:4uLU6hMCjMI75M1A2tKUQC","email":"(hidden)","password":"(hidden)","ms":1200}""", out)
    }

    @Test fun `headers, bearer values and query secrets are hidden`() {
        assertEquals("Authorization: (hidden)", LogMask.redact("Authorization: Bearer abc.def"))
        assertEquals("Cookie: (hidden)", LogMask.redact("Cookie: sp_dc=xyz; sp_key=1"))
        assertEquals("https://x/cb?code=(hidden)&state=ok", LogMask.redact("https://x/cb?code=1234&state=ok"))
        assertEquals("sent Bearer (hidden) twice", LogMask.redact("sent Bearer abc123 twice"))
    }

    @Test fun `a stray address and a long blob are hidden`() {
        assertEquals("user (e-mail hidden) failed", LogMask.redact("user me@example.com failed"))
        assertEquals("token (long value hidden)", LogMask.redact("token " + "A".repeat(200)))
    }

    @Test fun `what a bug report reads is kept`() {
        val line = "call #4 player.play (queue) {\"context\":\"spotify:playlist:37i9dQZF1DXcBWIGoYBM5M\",\"state=playing\":1} ok in 85 ms: status=402 code=COMMAND_FAILED"
        assertEquals(line, LogMask.redact(line))
    }

    @Test fun `shown cuts a big result and says by how much`() {
        val out = LogMask.shown("x".repeat(50), 20)
        assertTrue(out.startsWith("x".repeat(20)))
        assertTrue(out.endsWith("(+30)"))
        assertFalse(LogMask.shown("short").contains("+"))
    }

    @Test fun `shown masks the head of a huge result only`() {
        // A queue dump: only the head is masked, and the count still says the real size.
        val out = LogMask.shown("\"access_token\":\"BQsecret\"" + "x".repeat(50_000), 20)
        assertFalse(out.contains("BQsecret"))
        assertTrue(out.endsWith("(+50005)"))
    }

    @Test fun `shown masks a value cut at the end of the head`() {
        // The blob shrinks to a placeholder, so the token cut at the head's end would be kept unmasked.
        val text = "{\"x\":\"" + "A".repeat(700) + "\",\"v\":\"" + "B".repeat(300) + "\"}"
        val out = LogMask.shown(text, 200)
        assertFalse(out.contains("BBBB"))
        assertFalse(out.contains("AAAA"))
    }
}
