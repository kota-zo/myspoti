package app.myspoti.spotify

import app.myspoti.api.PlayerState
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The end check the stall watcher asks the page for, as arithmetic (see `watchStalls`).
 *
 * Reported on a TV: every song was skipped after a few seconds, and the log's "not the end"
 * line was the only sign of it. The clock is extrapolated from the page's last snapshot, and a
 * page that is busy hands over one seconds old: the guess ran past the end of a song playing in
 * its middle, the end check asked the page, and the page's fresh answer (still 30 s from the
 * end) was rejected because it was already inside the old window (4 s) — the repair played the
 * next song. The window now also covers what the ages of the two states already explain.
 */
class EndCheckTest {
    private val duration = 200_000L

    /** Whether the watcher takes the page's fresh answer for "the song is over". */
    private fun endTaken(reasonAgeMs: Long, freshAgeMs: Long, freshAtMs: Long): Boolean {
        val slack = minOf(SpotifyPlayer.PAST_END_SLACK_MS, reasonAgeMs + freshAgeMs)
        return !(freshAtMs < duration - SpotifyPlayer.END_VERIFY_MS - slack)
    }

    @Test fun `a song still in its middle stays`() {
        // The reason was computed from a state 30 s old, and the page says there is 30 s left:
        // the old window (duration - 4 s) rejected that answer, and the song was moved on.
        assertFalse(endTaken(reasonAgeMs = 30_000, freshAgeMs = 0, freshAtMs = 170_000))
        assertFalse(endTaken(reasonAgeMs = 0, freshAgeMs = 0, freshAtMs = 170_000))
    }

    @Test fun `a song at its end moves on`() {
        assertTrue(endTaken(reasonAgeMs = 0, freshAgeMs = 0, freshAtMs = 199_000))
        assertTrue(endTaken(reasonAgeMs = 30_000, freshAgeMs = 0, freshAtMs = 199_000))
    }

    @Test fun `a fresh read a moment before the end is the end when the reason was old`() {
        // The reason's own age put the end there (a page 40 s behind), and the fresh read is 3 s
        // before it: nothing is left to play.
        assertTrue(endTaken(reasonAgeMs = 40_000, freshAgeMs = 0, freshAtMs = 197_000))
        // The same read with the reason a moment old: 3 s from the end is inside the end window
        // (4 s), which is the end — a song's last seconds are the end, not a skip's to make.
        assertTrue(endTaken(reasonAgeMs = 0, freshAgeMs = 0, freshAtMs = 197_000))
        // And with more than the window left, the song stays.
        assertFalse(endTaken(reasonAgeMs = 0, freshAgeMs = 0, freshAtMs = 190_000))
    }

    @Test fun `the slack never goes past its own window`() {
        assertTrue(endTaken(reasonAgeMs = 600_000, freshAgeMs = 0, freshAtMs = duration - 1))
        val slack = minOf(SpotifyPlayer.PAST_END_SLACK_MS, 600_000L + 5_000L)
        assertTrue(slack <= SpotifyPlayer.PAST_END_SLACK_MS)
    }

    @Test fun `reasonAge reads a state's own age in position`() {
        val s = PlayerState(item = null, contextUri = null, isPaused = false, isBuffering = false,
            positionMs = 0, timestampMs = 1_000, speed = 2.0, shuffle = false,
            repeat = app.myspoti.api.Repeat.OFF, restrictions = app.myspoti.api.PlayerRestrictions())
        assertTrue(SpotifyPlayer.reasonAge(3_000, s) == 4_000L)
        assertTrue(SpotifyPlayer.reasonAge(0, s) == 0L)
    }
}
