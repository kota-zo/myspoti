package com.music.spotui.ui.look

import com.music.spotui.data.entity.LyricLine
import com.music.spotui.data.entity.LyricWord
import org.junit.Assert.assertEquals
import org.junit.Test

/** How long the sweep takes a word to be sung, its end known or not. */
class SweptLyricTimingTest {
    @Test fun aWordWithNoEndDoesNotFillAcrossThePauseAfterIt() {
        // Spotify's word timings: starts only (end = start). "Stay" is followed by 4 s of silence.
        val line = LyricLine(1_000, "Stay here", listOf(LyricWord(1_000, 1_000, "Stay "), LyricWord(5_000, 5_000, "here")))
        val swept = line.toSwept(nextLineMs = 9_000)
        assertEquals(1_700L, swept.words[0].endMs)
        assertEquals(5_700L, swept.words[1].endMs)
        // Fully lit well before the next word, not only as it starts.
        assertEquals(4f, swept.revealedChars(1_700), 0.01f)
    }

    @Test fun aWordWithNoEndStillEndsAtTheNextOneWhenItComesSooner() {
        val line = LyricLine(0, "one two", listOf(LyricWord(0, 0, "one "), LyricWord(300, 0, "two")))
        val swept = line.toSwept(nextLineMs = 400)
        assertEquals(300L, swept.words[0].endMs)
        assertEquals(400L, swept.words[1].endMs)
    }

    @Test fun aKnownEndIsKeptEvenWhenLong() {
        val line = LyricLine(0, "Oh", listOf(LyricWord(0, 3_000, "Oh")))
        assertEquals(3_000L, line.toSwept(nextLineMs = 5_000).words[0].endMs)
    }
}
