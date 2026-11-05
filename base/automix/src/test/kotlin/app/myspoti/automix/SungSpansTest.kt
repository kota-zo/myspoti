/* SPDX-License-Identifier: GPL-3.0-only */
package app.myspoti.automix

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SungSpansTest {
    private fun line(start: Long, wordEnd: Long? = null, sung: Boolean = true) = SungSpans.Line(start, wordEnd, sung)
    private val five = listOf(line(1_000), line(3_000), line(5_000), line(7_000), line(9_000))

    @Test fun aLineLastsUntilTheNextOne() {
        val spans = SungSpans.of(five, 180.0)!!
        assertEquals(5, spans.size)
        assertEquals(1.0 to 3.0, spans[0])
    }

    @Test fun timedWordsSayWhereALineEnds() {
        val spans = SungSpans.of(listOf(line(1_000, 2_200)) + five.drop(1), 180.0)!!
        assertEquals(1.0 to 2.2, spans[0])
    }

    @Test fun aLongGapIsNotSung() {
        val spans = SungSpans.of(five + line(60_000), 180.0)!!
        assertEquals(9.0 to 16.0, spans[4])
    }

    @Test fun anEmptyLineIsABreak() {
        val spans = SungSpans.of(five + line(11_000, sung = false) + line(20_000), 180.0)!!
        assertEquals(9.0 to 11.0, spans[4])
        assertEquals(6, spans.size)
    }

    @Test fun theLearnedVoicesStayARiskAndFollowTheVoiceBand() {
        val quiet = FloatArray(8) { -30f }; val zero = FloatArray(8)
        val risk = FloatArray(8) { if (it < 4) 0.9f else 0f }
        val out = VocalRisk.learned(risk, quiet, quiet, quiet, zero, zero)
        assertEquals(8, out.size)
        out.forEach { assert(it in 0f..1f) }
        assert(out[0] > out[7])
    }

    @Test fun tooFewLinesOrLinesPastTheEndAreNotTrusted() {
        assertNull(SungSpans.of(five.take(4), 180.0))
        assertNull(SungSpans.of(five + line(200_000), 180.0))
    }
}
