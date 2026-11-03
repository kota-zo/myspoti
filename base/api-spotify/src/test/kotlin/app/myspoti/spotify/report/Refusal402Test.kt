package app.myspoti.spotify.report

import app.myspoti.spotify.AccountPlan
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class Refusal402Test {
    private var now = 0L

    private fun fresh() { Refusal402.reset(); Refusal402.clock = { now } }

    @AfterEach fun restore() { Refusal402.reset(); Refusal402.clock = { System.nanoTime() / 1_000_000 } }

    private fun summary() = Refusal402.summary("product=FREE country=ID", "7-30 d ago").toMap()

    @Test fun `on a Premium account the ads line says there is none`() {
        fresh()
        AccountPlan.set(true)
        try { assertEquals("none (Premium)", summary()["ads"]) } finally { AccountPlan.set(null) }
    }

    @Test fun `a refused session reads in one screen`() {
        fresh()
        Refusal402.pageReady()
        now = 1_200; Refusal402.registered(200)
        Refusal402.cluster("devices=2 types=COMPUTER×2 kinds=self×1,stale-own×1 active=none player_state=absent")
        Refusal402.connectStatePut(200, "content-type,date,server")
        now = 5_000
        val refusal = listOf("x-envoy-upstream-service-time" to "12", "authorization" to "Bearer secret", "set-cookie" to "sp=1", "server" to "envoy")
        Refusal402.attempt("play", "okhttp", 402, 380, refusal, """{"error_type":"COMMAND_FAILED","message":"Command failed"}""")
        Refusal402.attempt("play", "HTTP_ENGINE", 402, 210, refusal, """{"error_type":"COMMAND_FAILED"}""")
        Refusal402.command("play", 402, retried = true)
        Refusal402.pageFetch(200)
        Refusal402.pageKept(false)
        Refusal402.licence(200); Refusal402.licence(429); Refusal402.licenceRetryAfter("60"); Refusal402.song()
        now = 20_000; Refusal402.adBreak()
        now = 40_000
        Refusal402.attempt("transfer", "okhttp", 402, 90, emptyList(), "{}")
        Refusal402.command("transfer", 402, retried = true)
        val s = summary()
        assertEquals("every command refused (2 of 2), no route played", s["verdict"])
        assertEquals("product=FREE country=ID, app installed 7-30 d ago", s["account"])
        assertTrue(s.getValue("timing").startsWith("first command play -> 402 after a refusal, 5.0 s after page ready, 3.8 s after registration"), s["timing"])
        assertEquals("play×1 transfer×1; within 60 s after an ad: 1, after a transfer: 0", s["refused"])
        assertEquals("COMMAND_FAILED×2", s["body"])
        assertEquals("x-envoy-upstream-service-time=12", s["refusal hdr"])
        assertEquals("okhttp 402×2 (median 380 ms) · HTTP_ENGINE 402×1 (median 210 ms)", s["routes"])
        assertEquals("no other route answered 2xx", s["retry 2xx"])
        assertEquals("200×1; audio in 10 s 0/1", s["page fetch"])
        assertEquals("200×1; headers: content-type,date,server", s["connect PUT"])
        assertEquals("200×1 429×1; over 1 songs; retry-after=60; 402 within 30 s of a licence 403/429: 1 of 2", s["licence"])
        assertEquals("blocked by the app; ad breaks this session=1, ad states left out=0", s["ads"])
        val all = s.values.joinToString(" ")
        assertFalse("secret" in all || "sp=1" in all, all)
    }

    @Test fun `a retry on another route that plays is told apart from one that does not`() {
        fresh()
        Refusal402.attempt("play", "okhttp", 402, 300, emptyList(), "")
        Refusal402.attempt("play", "CRONET", 200, 150, emptyList(), "")
        Refusal402.command("play", 200, retried = true)
        Refusal402.audioAfterRetry("CRONET", true)
        val s = summary()
        assertEquals("1 of 1 commands refused first; 1 accepted in the end; page fetch played 0×", s["verdict"])
        assertEquals("CRONET 2xx×1, audio in 10 s 1/1", s["retry 2xx"])
    }

    @Test fun `a session never refused stays short`() {
        fresh()
        Refusal402.command("play", 200, retried = false)
        val s = summary()
        assertEquals("no command refused (1 of 1 accepted)", s["verdict"])
        assertFalse("refused" in s || "retry 2xx" in s || "page fetch" in s)
    }

    @Test fun `only headers a refusal explains itself with`() {
        assertTrue(Refusal402.explaining("Retry-After"))
        assertTrue(Refusal402.explaining("www-authenticate"))
        assertTrue(Refusal402.explaining("x-spotify-reason"))
        assertFalse(Refusal402.explaining("x-auth-token"))
        assertFalse(Refusal402.explaining("authorization"))
        assertFalse(Refusal402.explaining("server"))
    }
}
