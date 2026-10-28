package app.myspoti.extensions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OutcomeTest {
    @Test fun anOrdinaryAnswerIsNoOutcome() {
        assertNull(Outcome.parse(Hook.AUDIO_SOURCE, """{"url":"https://a.example/x.flac"}"""))
        assertNull(Outcome.parse(Hook.AUDIO_SOURCE, "[1,2]"))
        assertNull(Outcome.parse(Hook.AUDIO_SOURCE, """{"outcome":3}"""))
        // Only the source hooks may answer an outcome.
        assertNull(Outcome.parse(Hook.HOME, """{"outcome":"rate-limited"}"""))
    }

    @Test fun statedOutcomesAreParsed() {
        assertEquals(Outcome.NotFound, Outcome.parse(Hook.LYRICS, """{"outcome":"not-found"}"""))
        assertEquals(Outcome.SignIn("Sign in again"), Outcome.parse(Hook.AUDIO_SOURCE, """{"outcome":"sign-in","detail":"  Sign in   again "}"""))
        assertEquals(Outcome.RateLimited(30_000), Outcome.parse(Hook.DOWNLOAD_SOURCE, """{"outcome":"rate-limited","retryAfterMs":30000}"""))
        assertEquals(Outcome.RateLimited(null), Outcome.parse(Hook.DOWNLOAD_SOURCE, """{"outcome":"rate-limited","retryAfterMs":-5}"""))
        assertEquals(Outcome.QualityUnavailable(listOf("cd", "small")),
            Outcome.parse(Hook.DOWNLOAD_SOURCE, """{"outcome":"quality-unavailable","available":["cd"," small ",""]}"""))
        assertEquals(Outcome.ServerDown(5_000), Outcome.parse(Hook.AUDIO_SOURCE, """{"outcome":"server-down","untilMs":5000}"""))
        assertEquals(Outcome.ServerDown(1_000 + 60_000), Outcome.parse(Hook.AUDIO_SOURCE, """{"outcome":"server-down","retryAfterMs":60000}""", now = 1_000))
        assertEquals("network", Outcome.parse(Hook.AUDIO_SOURCE, """{"outcome":"network"}""")!!.key)
        val unknown = Outcome.parse(Hook.AUDIO_SOURCE, """{"outcome":"maybe"}""")
        assertTrue(unknown is Outcome.Bug)
        assertEquals(Outcome.MAX_DETAIL, (Outcome.parse(Hook.LYRICS, """{"outcome":"sign-in","detail":"${"x".repeat(500)}"}""") as Outcome.SignIn).detail!!.length)
    }

    @Test fun failuresAreClassified() {
        assertEquals("network", Outcome.of(ExtensionTimeout("took longer than 15000 ms")).key)
        assertEquals("network", Outcome.of(ExtensionException("Error: http: api.example.com: no such host (main.js:3)")).key)
        assertEquals("network", Outcome.of(ExtensionException("Error: http: timeout (main.js:3)")).key)
        // A request the sandbox refused is the extension's own bug.
        assertEquals("bug", Outcome.of(ExtensionException("Error: http: evil.example is not in this extension's network hosts (main.js:3)")).key)
        assertEquals("bug", Outcome.of(ExtensionException("Error: http: 10.0.0.1 is a local address (main.js:3)")).key)
        assertEquals("bug", Outcome.of(ExtensionException("TypeError: x is undefined (main.js:9)")).key)
        assertEquals("bug", Outcome.of(ExtensionFault("out of memory")).key)
        assertEquals("skipped", Outcome.of(ExtensionBusy("busy")).key)
    }

    @Test fun onlyBugsCount() {
        assertTrue(Outcome.Bug("x").counts)
        listOf(Outcome.Found, Outcome.NotFound, Outcome.SignIn(), Outcome.RateLimited(), Outcome.QualityUnavailable(),
            Outcome.ServerDown(), Outcome.Network(), Outcome.Skipped()).forEach { assertFalse(it.key, it.counts) }
    }
}

class SourceCooldownTest {
    private var now = 1_000_000L
    private val c = SourceCooldown { now }

    @Test fun aRateLimitIsBounded() {
        assertEquals(SourceCooldown.MIN_MS, c.note("a", Outcome.RateLimited(1)))
        assertEquals(SourceCooldown.MAX_MS, c.note("b", Outcome.RateLimited(24 * 3600_000L)))
        assertEquals(SourceCooldown.RATE_LIMITED_MS, c.note("c", Outcome.RateLimited(null)))
        assertEquals(90_000L, c.note("d", Outcome.RateLimited(90_000)))
        assertTrue(c.reason("d")!!.startsWith("rate-limited, 90 s left"))
        now += 90_000
        assertNull(c.reason("d"))
    }

    @Test fun aServerDownIsBounded() {
        assertEquals(SourceCooldown.MAX_MS, c.note("a", Outcome.ServerDown(now + 7 * 24 * 3600_000L)))
        assertEquals(SourceCooldown.MIN_MS, c.note("b", Outcome.ServerDown(now - 1)))
        assertEquals(SourceCooldown.SERVER_DOWN_MS, c.note("c", Outcome.ServerDown(null)))
    }

    @Test fun networkErrorsInARowSetASourceAside() {
        assertNull(c.note("a", Outcome.Network()))
        assertNull(c.note("a", Outcome.Network()))
        assertEquals(SourceCooldown.MIN_MS, c.note("a", Outcome.Network()))
        assertEquals(2 * SourceCooldown.MIN_MS, c.note("a", Outcome.Network()))
        repeat(20) { c.note("a", Outcome.Network()) }
        assertEquals(SourceCooldown.MAX_MS, c.note("a", Outcome.Network()))
        // An answer ends it.
        assertNull(c.note("a", Outcome.NotFound))
        assertNull(c.reason("a"))
        assertNull(c.note("a", Outcome.Network()))
    }

    @Test fun bugsAndSkipsChangeNothing() {
        assertNull(c.note("a", Outcome.Bug("x")))
        assertNull(c.note("a", Outcome.Skipped("busy")))
        assertNull(c.reason("a"))
    }
}

class HookBudgetsTest {
    @Test fun everyHookHasABudget() {
        Hook.entries.forEach { assertTrue(it.key, HookBudgets.of(it) in 1_000L..30_000L) }
        assertEquals(8_000L, HookBudgets.of(Hook.LYRICS))
        assertEquals(15_000L, HookBudgets.of(Hook.AUDIO_SOURCE))
        assertEquals(30_000L, HookBudgets.of(Hook.DOWNLOAD_SOURCE))
        assertEquals(3_000L, HookBudgets.of(Hook.HOME))
        assertEquals(HookBudgets.SETTING, HookBudgets.ofEvent(ExtensionRuntime.EVENT_SETTING))
        assertEquals(HookBudgets.TRACK, HookBudgets.ofEvent(ExtensionRuntime.EVENT_PLAYBACK))
    }
}

class PlayAttemptTest {
    @Test fun addressesAreMasked() {
        val a = PlayAttempt(
            1, 0, "t1", "Song",
            asked = listOf(PlayAttempt.Ask("x.ext", "network", 120, detail = "Error: http: https://api.example.com/find?token=SECRET failed")),
            chosen = "x.ext",
            open = PlayAttempt.Open(false, 300, 403, "refused https://cdn.example.com/a.flac?sig=SECRET&exp=1", "https://user:pw@cdn.example.com/a.flac?sig=SECRET#frag"),
            error = "source error at https://cdn.example.com/a.flac?sig=SECRET",
        )
        val json = a.toJson().toString()
        assertFalse(json, json.contains("SECRET"))
        assertFalse(json, json.contains("pw@"))
        assertTrue(json, json.contains("https://cdn.example.com/a.flac"))
        assertFalse(a.summary(), a.summary().contains("SECRET"))
        assertEquals("not a url", PlayAttempt.maskUrl("not a url?x=1"))
    }

    @Test fun theRingKeepsTheLastOnes() {
        val r = PlayAttempts(capacity = 3)
        val ids = (1..5).map { r.start("t$it", "S$it") }
        assertEquals(listOf("t5", "t4", "t3"), r.last().map { it.trackId })
        r.update(ids[0]) { it.copy(chosen = "gone") }
        r.updateLatest("t4") { it.copy(chosen = "a") }
        r.updateLatest("t4") { it.copy(open = PlayAttempt.Open(true, 10)) }
        assertEquals("a", r.latest("t4")!!.chosen)
        assertTrue(r.latest("t4")!!.open!!.ok)
        assertNull(r.latest("t1"))
        assertEquals(2, r.toJson(2).size)
    }
}
