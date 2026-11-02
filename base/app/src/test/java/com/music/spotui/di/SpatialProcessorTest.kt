package com.music.spotui.di

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class SpatialProcessorTest {
    private val rate = 44_100

    private fun processor(width: Float?, channels: Int = 2, encoding: Int = C.ENCODING_PCM_16BIT) =
        SpatialProcessor { width }.also {
            it.configure(AudioProcessor.AudioFormat(rate, channels, encoding))
            it.flush()
        }

    /** Stereo frames of a sine: [left] and [right] are each channel's amplitude (a negative one is the sine upside down). */
    private fun stereo(hz: Double, left: Double, right: Double, frames: Int): ShortArray =
        ShortArray(frames * 2) { i ->
            val v = sin(2 * PI * hz * (i / 2) / rate)
            ((if (i % 2 == 0) left else right) * 32767 * v).roundToInt().toShort()
        }

    /** Through the processor in buffers of 4096 frames, as the sink hands them. */
    private fun run(p: SpatialProcessor, input: ShortArray): ShortArray {
        val out = ArrayList<Short>(input.size)
        var at = 0
        while (at < input.size) {
            val n = minOf(4096 * 2, input.size - at)
            val buf = ByteBuffer.allocateDirect(n * 2).order(ByteOrder.nativeOrder())
            for (i in 0 until n) buf.putShort(input[at + i])
            buf.flip()
            p.queueInput(buf)
            assertFalse("all input consumed", buf.hasRemaining())
            val o = p.output
            while (o.hasRemaining()) out += o.short
            at += n
        }
        return out.toShortArray()
    }

    /** The RMS of one channel (0 left, 1 right) from frame [from]. */
    private fun rms(s: ShortArray, channel: Int, from: Int = 0): Double {
        var sum = 0.0
        var n = 0
        for (i in (from * 2 + channel) until s.size step 2) { sum += s[i].toDouble() * s[i]; n++ }
        return sqrt(sum / n)
    }

    private fun noise(n: Int): ShortArray { val r = java.util.Random(11); return ShortArray(n) { (r.nextInt(65536) - 32768).toShort() } }

    @Test fun offIsAnExactCopy() {
        val input = noise(2 * 20_000)
        assertArrayEquals(input, run(processor(null), input))
    }

    @Test fun widthOneIsAnExactCopy() {
        val input = noise(2 * 20_000)
        assertArrayEquals(input, run(processor(1f), input))
    }

    @Test fun floatOffIsAnExactCopy() {
        val p = processor(null, encoding = C.ENCODING_PCM_FLOAT)
        val buf = ByteBuffer.allocateDirect(4 * 1000).order(ByteOrder.nativeOrder())
        val r = java.util.Random(5)
        val values = FloatArray(1000) { r.nextFloat() * 2 - 1 }
        values.forEach(buf::putFloat); buf.flip()
        p.queueInput(buf)
        val o = p.output
        val back = FloatArray(1000) { o.float }
        assertArrayEquals(values, back, 0f)
    }

    @Test fun onlyStereoPcmIsProcessed() {
        val mono = SpatialProcessor { 2f }.apply { configure(AudioProcessor.AudioFormat(rate, 1, C.ENCODING_PCM_16BIT)) }
        assertFalse(mono.isActive)
        val surround = SpatialProcessor { 2f }.apply { configure(AudioProcessor.AudioFormat(rate, 6, C.ENCODING_PCM_16BIT)) }
        assertFalse(surround.isActive)
        val deep = SpatialProcessor { 2f }.apply { configure(AudioProcessor.AudioFormat(rate, 2, C.ENCODING_PCM_24BIT)) }
        assertFalse(deep.isActive)
        val float = SpatialProcessor { 2f }.apply { configure(AudioProcessor.AudioFormat(rate, 2, C.ENCODING_PCM_FLOAT)) }
        assertTrue(float.isActive)
    }

    @Test fun theCentreStaysCentredAndIsOnlyTurnedDown() {
        // The same sound in both ears (a centred voice): no side to widen, only the makeup gain.
        val input = stereo(440.0, 0.4, 0.4, rate / 4)
        val out = run(processor(2f), input)
        val gain = SpatialProcessor.makeup(2f)
        for (i in input.indices step 2) {
            assertEquals(out[i], out[i + 1])
            assertEquals(input[i] * gain.toDouble(), out[i].toDouble(), 1.0)
        }
    }

    @Test fun theSideIsRaisedByTheWidth() {
        // Opposite in each ear: all side, no mid. Width 2 doubles it, the makeup turns it down.
        val input = stereo(440.0, 0.2, -0.2, rate / 2)
        val out = run(processor(2f), input)
        val expected = 2 * SpatialProcessor.makeup(2f)
        assertEquals(expected.toDouble(), rms(out, 0) / rms(input, 0), 0.01)
        assertEquals(expected.toDouble(), rms(out, 1) / rms(input, 1), 0.01)
    }

    @Test fun aHardPannedPeakIsLimitedNotClipped() {
        // Left only, near full scale: the widest setting spills it far past full scale on both sides.
        val input = stereo(60.0, 0.98, 0.0, rate)
        val out = run(processor(Spatial.MAX_WIDTH), input)
        val peak = out.maxOf { abs(it.toInt()) }
        assertTrue("under the ceiling: $peak", peak <= (0.9886 * 32768).toInt() + 1)
        // The right channel now carries the inverted side: the image is wider, not just quieter.
        assertTrue(rms(out, 1, rate / 2) > 0.2 * rms(out, 0, rate / 2))
    }

    @Test fun turningOffGlidesThenCopies() {
        var width: Float? = 2f
        val p = SpatialProcessor { width }.also { it.configure(AudioProcessor.AudioFormat(rate, 2, C.ENCODING_PCM_16BIT)); it.flush() }
        run(p, stereo(440.0, 0.3, -0.1, rate / 4))
        width = null
        val input = noise(2 * 40_000)
        val out = run(p, input)
        // The width and the limiter glide back (full-scale noise keeps the limiter busy until the
        // width is 1), then the exact copy, well within half a second.
        val tail = 2 * 20_000
        assertArrayEquals(input.copyOfRange(tail, input.size), out.copyOfRange(tail, out.size))
    }

    @Test fun aChangeGlides() {
        var width: Float? = null
        val p = SpatialProcessor { width }.also { it.configure(AudioProcessor.AudioFormat(rate, 2, C.ENCODING_PCM_16BIT)); it.flush() }
        val input = stereo(440.0, 0.2, -0.2, rate / 2)
        run(p, input.copyOfRange(0, 2 * 4096))
        width = 2f
        val out = run(p, input.copyOfRange(2 * 4096, 2 * 8192))
        // The first frames after the switch are still close to the song as it was, not doubled at once.
        val firstPeak = (0 until 2 * 32).maxOf { abs(out[it].toInt()) }
        val inputPeak = (2 * 4096 until 2 * 4096 + 2 * 32).maxOf { abs(input[it].toInt()) }
        assertTrue("first $firstPeak vs $inputPeak", firstPeak <= inputPeak * 1.15)
    }
}
