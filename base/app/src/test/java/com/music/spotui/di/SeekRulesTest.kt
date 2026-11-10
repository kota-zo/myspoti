package com.music.spotui.di

import com.music.spotui.di.SeekRules.Sent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The seek guard that keeps a stale page state from moving the bar back.
 *
 * Reported on a TV: "I press previous, the bar goes to 0 and then to about 1 minute" and, from a
 * drag, the bar jumping back to where the song had been before the seek. Both were the page's
 * state of the place the seek was sent from, read after the seek (by the seek's own hold ending,
 * or by a reconcile), taken for the song moving back.
 */
class SeekRulesTest {
    private val song = "spotify:track:a"
    private val other = "spotify:track:b"
    private val sent = Sent(song, from = 61_000, target = 20_000)

    @Test fun `a state on the place a seek was sent from is the old position`() {
        assertTrue(SeekRules.stale(sent, song, 61_000, held = true, guardUntil = 0, now = 0))
        // The seek's hold is over, the guard is not: still the old position, handed over late.
        assertTrue(SeekRules.stale(sent, song, 61_500, held = false, guardUntil = 300_000, now = 200_000))
        // Both over: the page has had long enough, its word stands.
        assertFalse(SeekRules.stale(sent, song, 61_000, held = false, guardUntil = 300_000, now = 400_000))
    }

    @Test fun `the seek itself is never refused`() {
        assertFalse(SeekRules.stale(sent, song, 20_000, held = true, guardUntil = 300_000, now = 0))
        // A second off, still the seek (the code's own near window).
        assertFalse(SeekRules.stale(sent, song, 21_500, held = true, guardUntil = 300_000, now = 0))
    }

    @Test fun `a song heard on from the seek is the page's word`() {
        // Ten seconds past the target: the song plays there, nothing is refused.
        assertFalse(SeekRules.stale(sent, song, 30_000, held = true, guardUntil = 300_000, now = 0))
    }

    @Test fun `another song is never the seek's business`() {
        assertFalse(SeekRules.stale(sent, other, 61_000, held = true, guardUntil = 300_000, now = 0))
        assertFalse(SeekRules.stale(sent, "", 0, held = true, guardUntil = 300_000, now = 0))
    }

    @Test fun `a seek from the song's start is not confused with no position at all`() {
        // Pressed "previous": the new song shows at 0, and the page then reports the song it left.
        val fromStart = Sent(song, from = 0, target = 0)
        // The old position of the song left (a minute in) is the page's word for the new song.
        assertFalse(SeekRules.stale(fromStart, song, 61_000, held = true, guardUntil = 300_000, now = 0))
    }

    @Test fun `a state of the song a previous left is not the song now`() {
        // "Previous" at 1:01: the new song shows 0:00, and the page reports the song left.
        assertTrue(SeekRules.leftBySkip("spotify:track:a", movedFrom = "spotify:track:a", movedTo = "spotify:track:z",
            movedAt = 100_000, now = 100_500))
        // Seconds later the page has moved: anything it says is the new song's.
        assertFalse(SeekRules.leftBySkip("spotify:track:a", "spotify:track:a", "spotify:track:z", 100_000, now = 200_000))
        // The song asked for is the page's word at once.
        assertFalse(SeekRules.leftBySkip("spotify:track:z", "spotify:track:a", "spotify:track:z", 100_000, now = 100_500))
        // Nothing to move to (the page named no neighbour): no filter.
        assertFalse(SeekRules.leftBySkip("spotify:track:a", "spotify:track:a", null, 100_000, now = 100_500))
        // A previous onto itself (one song, repeat) is not a move.
        assertFalse(SeekRules.leftBySkip("spotify:track:a", "spotify:track:a", "spotify:track:a", 100_000, now = 100_500))
        // A state older than the skip is not explained by it.
        assertFalse(SeekRules.leftBySkip("spotify:track:a", "spotify:track:a", "spotify:track:z", 100_000, now = 99_000))
        // No skip at all.
        assertFalse(SeekRules.leftBySkip("spotify:track:a", "", null, 0, now = 0))
    }

    @Test fun `a place is moved on by the state's own age within what the seek allowed`() {
        // 40 s in, the state is 3 s old, the seek was 27 s ago: the place is 43 s.
        assertEquals(43_000.0, SeekRules.placeAt(40_000, ageMs = 3_000, speed = 1.0, playedSinceSeekMs = 27_000), 0.5)
        // An age past what the seek allowed is the seek's own allowance (a page from before it).
        assertEquals(67_000.0, SeekRules.placeAt(40_000, ageMs = 300_000, speed = 1.0, playedSinceSeekMs = 27_000), 0.5)
        // A speed of 1.5 is a place, not a rounded-down one.
        assertEquals(44_500.0, SeekRules.placeAt(40_000, ageMs = 3_000, speed = 1.5, playedSinceSeekMs = 27_000), 0.5)
        // The state's own clock is never the seek's.
        assertEquals(40_000.0, SeekRules.placeAt(40_000, ageMs = 0, speed = 1.0, playedSinceSeekMs = 0), 0.5)
    }
}
