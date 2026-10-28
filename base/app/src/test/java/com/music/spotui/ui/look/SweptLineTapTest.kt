package com.music.spotui.ui.look

import org.junit.Assert.assertEquals
import org.junit.Test

class SweptLineTapTest {
    // The first word starts a little after the line's own stamp.
    private val line = SweptLine(
        timeMs = 10_000,
        text = "fuck love, fuck love",
        words = listOf(
            SweptWord(10_200, 10_500, "fuck"),
            SweptWord(10_500, 11_000, "love,"),
            SweptWord(11_400, 11_700, "fuck"),
            SweptWord(11_700, 12_300, "love"),
        ),
    )

    @Test
    fun firstWordPlaysFromTheLine() {
        assertEquals(10_000, line.startAtChar(0))
        assertEquals(10_000, line.startAtChar(3))
    }

    @Test
    fun laterWordPlaysFromItsOwnStart() {
        assertEquals(10_500, line.startAtChar(5))
        assertEquals(10_500, line.startAtChar(8))
        // The repeated word lines up with its own occurrence, not the first one.
        assertEquals(11_400, line.startAtChar(12))
        assertEquals(11_700, line.startAtChar(17))
    }

    @Test
    fun betweenTwoWordsPlaysTheOneBefore() {
        assertEquals(10_500, line.startAtChar(10)) // the space after "love,"
    }

    @Test
    fun lineWithoutWordTimingsPlaysFromItsStart() {
        assertEquals(4_000, SweptLine(4_000, "no words here").startAtChar(9))
    }
}
