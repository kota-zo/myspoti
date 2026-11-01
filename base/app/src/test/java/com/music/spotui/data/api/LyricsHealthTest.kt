package com.music.spotui.data.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A provider failing in a row is set aside; "no lyrics" is kept a few minutes per song and provider. */
class LyricsHealthTest {
    private var now = 1_000_000L
    private val h = LyricsHealth { now }

    @Test fun threeFailuresInARowSetAProviderAsideForTenMinutes() {
        repeat(LyricsHealth.FAILURES_IN_A_ROW - 1) { h.failed("lrclib") }
        assertNull(h.reason("lrclib"))
        h.failed("lrclib")
        assertNotNull(h.reason("lrclib"))
        now += LyricsHealth.SET_ASIDE_MS - 1_000
        assertNotNull(h.reason("lrclib"))
        now += 2_000
        assertNull(h.reason("lrclib"))
    }

    @Test fun anAnswerEndsTheRunAndOtherProvidersAreNotAffected() {
        h.failed("kugou"); h.failed("kugou")
        h.answered("kugou")
        h.failed("kugou"); h.failed("kugou")
        assertNull(h.reason("kugou"))
        repeat(3) { h.failed("netease") }
        assertNotNull(h.reason("netease"))
        assertNull(h.reason("kugou"))
    }

    @Test fun noLyricsIsKeptForFiveMinutesForThatSongAndProviderOnly() {
        h.noteMissing("track1", "lyricsplus")
        assertTrue(h.isMissing("track1", "lyricsplus"))
        assertFalse(h.isMissing("track1", "lrclib"))
        assertFalse(h.isMissing("track2", "lyricsplus"))
        now += LyricsHealth.MISSING_MS - 1
        assertTrue(h.isMissing("track1", "lyricsplus"))
        now += 2
        assertFalse(h.isMissing("track1", "lyricsplus"))
    }

    @Test fun askingAgainDropsTheSongsAnswers() {
        h.noteMissing("track1", "lyricsplus"); h.noteMissing("track1", "kugou"); h.noteMissing("track2", "kugou")
        h.forgetTrack("track1")
        assertFalse(h.isMissing("track1", "lyricsplus"))
        assertFalse(h.isMissing("track1", "kugou"))
        assertTrue(h.isMissing("track2", "kugou"))
        h.forgetAll()
        assertFalse(h.isMissing("track2", "kugou"))
    }

    @Test fun lostRequestsAreCountedSoAFailureIsNeverKeptAsNoLyrics() {
        val before = h.lostCount()
        h.failed("genius"); h.skipped()
        assertEquals(before + 2, h.lostCount())
    }
}
