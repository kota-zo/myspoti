package com.music.spotui.di

import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalRepeatTest {
    @Test fun repeatOneLoopsTheSongItself() {
        assertTrue(LocalRepeat.loopsSong(LocalRepeat.ONE, 50))
        assertTrue(LocalRepeat.loopsSong(LocalRepeat.ONE, 1))
        // ExoPlayer's REPEAT_MODE_ONE is never used: it restarted extension songs near the last seek.
        assertEquals(Player.REPEAT_MODE_OFF, LocalRepeat.playerMode(LocalRepeat.ONE, 50))
        assertEquals(Player.REPEAT_MODE_OFF, LocalRepeat.playerMode(LocalRepeat.ONE, 1))
    }

    @Test fun repeatAllOfSeveralSongsIsThePlayersOwn() {
        assertFalse(LocalRepeat.loopsSong(LocalRepeat.ALL, 2))
        assertEquals(Player.REPEAT_MODE_ALL, LocalRepeat.playerMode(LocalRepeat.ALL, 50))
    }

    @Test fun repeatAllOfOneSongLoopsItLikeRepeatOne() {
        assertTrue(LocalRepeat.loopsSong(LocalRepeat.ALL, 1))
        assertEquals(Player.REPEAT_MODE_OFF, LocalRepeat.playerMode(LocalRepeat.ALL, 1))
    }

    @Test fun offNeverLoops() {
        assertFalse(LocalRepeat.loopsSong(LocalRepeat.OFF, 1))
        assertFalse(LocalRepeat.loopsSong(LocalRepeat.OFF, 50))
        assertEquals(Player.REPEAT_MODE_OFF, LocalRepeat.playerMode(LocalRepeat.OFF, 50))
    }

    @Test fun aSongStartingLateIsSeen() {
        assertFalse(LocalRepeat.startedLate(0))
        assertFalse(LocalRepeat.startedLate(LocalRepeat.START_SLACK_MS))
        // The loops of the report: 193311, 192908 ms instead of 0.
        assertTrue(LocalRepeat.startedLate(193_311))
    }

    // ── Repeat once ──

    @Test fun repeatOncePlaysTheSongAgainOneTimeThenGoesOn() {
        assertEquals(LocalRepeat.End.REPLAY, LocalRepeat.atEnd(LocalRepeat.ONE, 50, replayed = false))
        assertEquals(LocalRepeat.End.ONCE_DONE, LocalRepeat.atEnd(LocalRepeat.ONE, 50, replayed = true))
        // The only song of the queue too: once, then the queue's end (its song radio).
        assertEquals(LocalRepeat.End.REPLAY, LocalRepeat.atEnd(LocalRepeat.ONE, 1, replayed = false))
        assertEquals(LocalRepeat.End.ONCE_DONE, LocalRepeat.atEnd(LocalRepeat.ONE, 1, replayed = true))
        // Repeat once still pauses at the end in both turns: Myspoti decides, never the player.
        assertTrue(LocalRepeat.loopsSong(LocalRepeat.ONE, 50))
    }

    @Test fun repeatAllOfOneSongLoopsForGood() {
        assertEquals(LocalRepeat.End.REPLAY, LocalRepeat.atEnd(LocalRepeat.ALL, 1, replayed = false))
        assertEquals(LocalRepeat.End.REPLAY, LocalRepeat.atEnd(LocalRepeat.ALL, 1, replayed = true))
    }

    @Test fun offAndRepeatAllOfSeveralSongsGoOn() {
        assertEquals(LocalRepeat.End.NEXT, LocalRepeat.atEnd(LocalRepeat.OFF, 50, replayed = false))
        assertEquals(LocalRepeat.End.NEXT, LocalRepeat.atEnd(LocalRepeat.OFF, 1, replayed = false))
        assertEquals(LocalRepeat.End.NEXT, LocalRepeat.atEnd(LocalRepeat.ALL, 50, replayed = false))
    }

    @Test fun aSeekToTheEndIsTheSongEnding() {
        // The report: the bar dragged to 2:11 of a 131000 ms song.
        assertTrue(LocalRepeat.seekEnds(131_000, 131_000))
        assertTrue(LocalRepeat.seekEnds(140_000, 131_000))
        assertTrue(LocalRepeat.seekEnds(130_600, 131_000))
        assertFalse(LocalRepeat.seekEnds(120_000, 131_000))
        // Not known: never taken for the end (Spotify's length is not the audio's).
        assertFalse(LocalRepeat.seekEnds(131_000, null))
    }

    @Test fun seeksStayWithinTheLengthThePlayerKnows() {
        assertEquals(130_999, LocalRepeat.seekTarget(131_000, 131_000))
        assertEquals(125_000, LocalRepeat.seekTarget(140_000, 125_001))
        assertEquals(60_000, LocalRepeat.seekTarget(60_000, 131_000))
        assertEquals(0, LocalRepeat.seekTarget(-5, 131_000))
        assertEquals(140_000, LocalRepeat.seekTarget(140_000, null))
    }

    @Test fun theSpotifyPageStartingTheSongAgainIsTheReplay() {
        assertTrue(LocalRepeat.pageRestarted(sameSong = true, beforeMs = 130_500, lengthMs = 131_000, nowMs = 40))
        assertTrue(LocalRepeat.pageRestarted(sameSong = true, beforeMs = 131_000, lengthMs = 131_000, nowMs = 2_000))
        // A seek back mid-song, another song, a state just later in the song: not a replay.
        assertFalse(LocalRepeat.pageRestarted(sameSong = true, beforeMs = 60_000, lengthMs = 131_000, nowMs = 0))
        assertFalse(LocalRepeat.pageRestarted(sameSong = false, beforeMs = 130_500, lengthMs = 131_000, nowMs = 40))
        assertFalse(LocalRepeat.pageRestarted(sameSong = true, beforeMs = 128_000, lengthMs = 131_000, nowMs = 129_000))
        assertFalse(LocalRepeat.pageRestarted(sameSong = true, beforeMs = 130_500, lengthMs = 0, nowMs = 40))
    }
}
