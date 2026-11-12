package app.myspoti.spotify

import app.myspoti.api.ItemKind
import app.myspoti.api.NowPlaying
import app.myspoti.api.PlayerRestrictions
import app.myspoti.api.PlayerState
import app.myspoti.api.Repeat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class StallTest {
    private val song = NowPlaying(kind = ItemKind.TRACK, uri = "spotify:track:a", uid = null, name = "A", artists = emptyList(), album = null, imageUrl = null, durationMs = 200_000)
    private val after = song.copy(uri = "spotify:track:b", name = "B")
    private fun state(pos: Long, at: Long, paused: Boolean = false, buffering: Boolean = false, next: NowPlaying? = after) =
        PlayerState(item = song, contextUri = "spotify:playlist:x", next = next, isPaused = paused, isBuffering = buffering,
            positionMs = pos, timestampMs = at, speed = 1.0, shuffle = false, repeat = Repeat.OFF, restrictions = PlayerRestrictions())

    @Test fun `a playing song is fine`() = assertNull(SpotifyPlayer.stallOf(state(100_000, 0), 50_000, false))
    @Test fun `clock past the end`() = assertEquals("past the end", SpotifyPlayer.stallOf(state(190_000, 0), 20_000, false))
    @Test fun `paused by itself at the end`() = assertEquals("paused at the end", SpotifyPlayer.stallOf(state(199_500, 0, paused = true), 60_000, false))
    @Test fun `paused by the user at the end`() = assertNull(SpotifyPlayer.stallOf(state(199_500, 0, paused = true), 60_000, true))
    @Test fun `last song of the list`() = assertNull(SpotifyPlayer.stallOf(state(199_500, 0, paused = true, next = null), 60_000, false))
    @Test fun `a user pause at the end never expires`() = assertNull(SpotifyPlayer.stallOf(state(199_500, 0, paused = true), 600_000, true))
    @Test fun `paused mid-song`() = assertNull(SpotifyPlayer.stallOf(state(50_000, 0, paused = true), 60_000, false))
    @Test fun `buffering for long`() = assertEquals("buffering", SpotifyPlayer.stallOf(state(0, 0, buffering = true), 20_000, false))
    @Test fun `buffering a moment`() = assertNull(SpotifyPlayer.stallOf(state(0, 0, buffering = true), 5_000, false))

    // Reported (2.3.0): a silent song got "attempt 1" every 8 s for minutes, its count reset in between.
    @Test fun `a silent song repaired once keeps its attempt while its element stands`() {
        assertEquals(true, SpotifyPlayer.silentKept("spotify:track:a|silent", "spotify:track:a", still = true))
        assertEquals(false, SpotifyPlayer.silentKept("spotify:track:a|silent", "spotify:track:a", still = false))
        assertEquals(false, SpotifyPlayer.silentKept("spotify:track:a|silent", "spotify:track:b", still = true))
        assertEquals(false, SpotifyPlayer.silentKept("spotify:track:a|buffering", "spotify:track:a", still = true))
        assertEquals(false, SpotifyPlayer.silentKept(null, "spotify:track:a", still = true))
    }

    @Test fun `a key waited for after a licence answered 401 is a stale token`() {
        assertEquals(true, SpotifyPlayer.staleTokenStall(18_000, keyWaits = 1))
        assertEquals(false, SpotifyPlayer.staleTokenStall(18_000, keyWaits = 0))
        assertEquals(false, SpotifyPlayer.staleTokenStall(SpotifyPlayer.STALE_LICENCE_MS + 1, keyWaits = 1))
        // Never answered 401: the time since is the whole clock.
        assertEquals(false, SpotifyPlayer.staleTokenStall(System.currentTimeMillis(), keyWaits = 1))
    }

    @Test fun `reloads for a stale token are spaced out`() {
        assertEquals(60_000L, SpotifyPlayer.authReloadGap(1))
        assertEquals(120_000L, SpotifyPlayer.authReloadGap(2))
        assertEquals(SpotifyPlayer.AUTH_RELOAD_MAX_GAP_MS, SpotifyPlayer.authReloadGap(9))
    }

    private val episode = song.copy(kind = ItemKind.EPISODE, uri = "spotify:episode:e", name = "E")
    private val nextEpisode = episode.copy(uri = "spotify:episode:f", name = "F")
    private val ad = song.copy(kind = ItemKind.AD, uri = "spotify:ad:x")

    @Test fun `a song at its end moves on to the next song`() = assertEquals(after.uri, SpotifyPlayer.onwardTarget(state(199_500, 0), emptyList()))
    @Test fun `an episode at its end moves on to the next episode`() =
        assertEquals(nextEpisode.uri, SpotifyPlayer.onwardTarget(state(199_500, 0, next = nextEpisode).copy(item = episode), emptyList()))
    @Test fun `the queue is read when the state names no next item`() =
        assertEquals(nextEpisode.uri, SpotifyPlayer.onwardTarget(state(199_500, 0, next = null).copy(item = episode), listOf(ad, nextEpisode)))
    @Test fun `the song autoplay brings is told from the list's own`() {
        val auto = after.copy(uri = "spotify:track:auto", provider = "autoplay")
        assertEquals("autoplay", SpotifyPlayer.onwardItem(state(199_500, 0, next = auto), emptyList())?.provider)
        assertEquals("autoplay", SpotifyPlayer.onwardItem(state(199_500, 0, next = null), listOf(auto))?.provider)
    }
    @Test fun `an ad is never a target`() = assertNull(SpotifyPlayer.onwardTarget(state(199_500, 0, next = ad), listOf(ad)))

    // Chromium pauses a hidden page's media that (re)starts at volume 0.
    @Test fun `a fade never reaches volume 0`() = SpotifyPlayer.FADE_STEPS.forEach { assertEquals(true, it > 0.0) }
}
