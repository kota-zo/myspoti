package app.myspoti.spotify

import app.myspoti.spotify.TrackChanges.Cause
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TrackChangesTest {
    private fun cause(played: Long, duration: Long = 200_000, since: Long = 60_000, ad: Boolean = false, fade: Boolean = false) =
        TrackChanges.cause(played, duration, since, ad, fade)

    @Test fun `a recent command explains the change`() = assertEquals(Cause.COMMAND, cause(7_000, since = 800))
    @Test fun `a command long ago does not`() = assertEquals(Cause.OWN, cause(7_000, since = 20_000))
    @Test fun `the end of the song`() = assertEquals(Cause.ENDED, cause(198_000))
    @Test fun `a crossfade ends the song early`() = assertEquals(Cause.ENDED, cause(190_000, fade = true))
    @Test fun `an ad break`() = assertEquals(Cause.AD, cause(30_000, ad = true))
    @Test fun `unknown duration is not an end`() = assertEquals(Cause.OWN, cause(30_000, duration = 0))
    @Test fun `a command wins over an ad`() = assertEquals(Cause.COMMAND, cause(30_000, since = 100, ad = true))

    @Test fun `three short songs left by the page in a row`() {
        val c = TrackChanges.Chain()
        assertFalse(c.record(Cause.OWN, 400, 1_000))
        assertFalse(c.record(Cause.OWN, 300, 3_000))
        assertTrue(c.record(Cause.OWN, 500, 5_000))
    }

    @Test fun `the chain starts over after it fired`() {
        val c = TrackChanges.Chain()
        repeat(3) { c.record(Cause.OWN, 400, it * 1_000L) }
        assertFalse(c.record(Cause.OWN, 400, 4_000))
    }

    @Test fun `a command breaks the chain`() {
        val c = TrackChanges.Chain()
        c.record(Cause.OWN, 400, 1_000); c.record(Cause.OWN, 400, 2_000)
        assertFalse(c.record(Cause.COMMAND, 400, 3_000))
        assertFalse(c.record(Cause.OWN, 400, 4_000))
    }

    @Test fun `a song that played a while breaks the chain`() {
        val c = TrackChanges.Chain()
        c.record(Cause.OWN, 400, 1_000); c.record(Cause.OWN, 400, 2_000)
        assertFalse(c.record(Cause.OWN, 60_000, 3_000))
        assertFalse(c.record(Cause.OWN, 400, 4_000))
    }

    @Test fun `changes too far apart are not a chain`() {
        val c = TrackChanges.Chain()
        assertFalse(c.record(Cause.OWN, 400, 0)); assertFalse(c.record(Cause.OWN, 400, 20_000))
        assertFalse(c.record(Cause.OWN, 400, 40_000))
    }

    @Test fun `a command already used does not explain another change`() =
        assertEquals(Cause.OWN, TrackChanges.cause(400, 200_000, 1_500, afterAd = false, crossfading = false, commandUsed = true))

    @Test fun `a command explains only its first change`() {
        val c = TrackChanges.Commands()
        assertFalse(c.used(1_000, "a", target = null))
        assertTrue(c.used(1_000, "b", target = null))
        assertTrue(c.used(1_000, "c", target = null))
        // A new command explains its own first change.
        assertFalse(c.used(2_000, "d", target = null))
    }

    @Test fun `a change onto the song played is the command's`() {
        val c = TrackChanges.Commands()
        assertFalse(c.used(1_000, "x", target = "t"))
        assertFalse(c.used(1_000, "t", target = "t"))
        assertTrue(c.used(1_000, "y", target = "t"))
    }

    // The 30/10 log (kekstar63): a play, then the page moving on every second, licences refused.
    @Test fun `a page skipping after a play is a chain`() {
        val commands = TrackChanges.Commands()
        val chain = TrackChanges.Chain()
        val at = 28_156L
        val fired = listOf("t" to 29_456L, "a" to 30_283L, "b" to 31_359L, "c" to 32_014L).map { (to, now) ->
            val cause = TrackChanges.cause(0, 200_000, now - at, false, false, commands.used(at, to, target = "t"))
            chain.record(cause, 0, now)
        }
        assertEquals(listOf(false, false, false, true), fired)
    }

    @Test fun `clock`() = assertEquals("3:21", TrackChanges.clock(201_500))
}
