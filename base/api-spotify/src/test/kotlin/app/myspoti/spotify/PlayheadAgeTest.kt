package app.myspoti.spotify

import app.myspoti.api.ItemKind
import app.myspoti.api.NowPlaying
import app.myspoti.api.PlayerRestrictions
import app.myspoti.api.PlayerState
import app.myspoti.api.Repeat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * The playhead on a page too busy to hand over a fresh state (a TV).
 *
 * Reported there: songs skipped by themselves, "play after a stop plays a little and stops", and
 * a "previous" that left the bar at 0:00 before jumping to about a minute. All three came from
 * guessing the position of a page from a snapshot seconds old, and from judging a song's end on
 * that guess. These pin down the widened window, the end check's age allowance, and the resume
 * that does not seek to a place the song never reached.
 */
class PlayheadAgeTest {
    private val song = NowPlaying(kind = ItemKind.TRACK, uri = "spotify:track:a", uid = null, name = "A",
        artists = emptyList(), album = null, imageUrl = null, durationMs = 200_000)
    private val after = song.copy(uri = "spotify:track:b", name = "B")

    private fun state(pos: Long, at: Long, paused: Boolean = false, next: NowPlaying? = after) =
        PlayerState(item = song, contextUri = "spotify:playlist:x", next = next, isPaused = paused,
            isBuffering = false, positionMs = pos, timestampMs = at, speed = 1.0, shuffle = false,
            repeat = Repeat.OFF, restrictions = PlayerRestrictions())

    @Test fun `a state a moment old is not past a song's end`() {
        // 199 s into a 200 s song, read 3 s later: the extrapolation runs 2 s past the end.
        assertNull(SpotifyPlayer.stallOf(state(199_000, 0), 3_000, false))
        // Even a badly stale one (10 s) is left alone: the end check asks the page first.
        assertNull(SpotifyPlayer.stallOf(state(195_000, 0), 10_000, false))
    }

    @Test fun `a song really over still moves on once the slack is spent`() {
        assertEquals("past the end", SpotifyPlayer.stallOf(state(180_000, 0), 40_000, false))
        // The old window (1.5 s) would have moved it on here, long before its end.
        assertNull(SpotifyPlayer.stallOf(state(190_000, 0), 12_000, false))
    }

    @Test fun `the age a state explains is its own`() {
        assertEquals(3_000L, SpotifyPlayer.reasonAge(3_000, state(0, 0)))
        assertEquals(4_500L, SpotifyPlayer.reasonAge(3_000, state(0, 0).copy(speed = 1.5)))
        // A state from the future (a page's clock ahead) explains nothing.
        assertEquals(0L, SpotifyPlayer.reasonAge(1_000, state(0, 5_000)))
    }

    @Test fun `a pause at the end is only a stall with a next song and no user pause`() {
        assertEquals("paused at the end", SpotifyPlayer.stallOf(state(199_500, 0, paused = true), 60_000, false))
        assertNull(SpotifyPlayer.stallOf(state(199_500, 0, paused = true), 60_000, true))
        assertNull(SpotifyPlayer.stallOf(state(199_500, 0, paused = true, next = null), 60_000, false))
    }
}
