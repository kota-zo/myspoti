package app.myspoti.automix

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * Reported: AutoMix closed the app with an OutOfMemoryError (a 49 MB allocation on a 256 MB heap).
 * The decoder copied its buffers to the song's size before the analysis, a second song's worth of
 * memory. The analysis now reads the song's [length] in the longer buffers: the same result.
 */
class SignalLengthTest {
    private fun song(seconds: Int): Pair<FloatArray, FloatArray> {
        val n = seconds * Features.RATE
        val mid = FloatArray(n) { i -> val t = i.toDouble() / Features.RATE; (0.5 * sin(2 * PI * 110 * t) * (if ((t * 2).toInt() % 2 == 0) 1.0 else 0.2)).toFloat() }
        val side = FloatArray(n) { i -> (0.1 * sin(2 * PI * 330 * i.toDouble() / Features.RATE)).toFloat() }
        return mid to side
    }

    @Test fun `a song read in longer buffers is analysed as the song alone`() {
        val (mid, side) = song(40)
        val exact = Analyzer.analyze(mid, side)
        // The decoder's buffers: the song, then a second of zeros it never filled.
        val padded = Analyzer.analyze(mid.copyOf(mid.size + Features.RATE), side.copyOf(side.size + Features.RATE), length = mid.size)
        assertEquals(exact.audioKey, padded.audioKey)
        assertEquals(exact.bars, padded.bars)
        assertEquals(exact.contentEnd, padded.contentEnd, 0.0)
        assertEquals(exact.bpm, padded.bpm, 0.0)
        assertEquals(exact.duration, padded.duration, 0.0)
    }
}
