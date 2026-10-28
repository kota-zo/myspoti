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
}
