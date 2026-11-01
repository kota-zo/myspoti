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
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class EqualizerProcessorTest {
    private val rate = 44_100

    private fun processor(tuning: EqTuning?, channels: Int = 2, encoding: Int = C.ENCODING_PCM_16BIT) =
        EqualizerProcessor { tuning }.also {
            it.configure(AudioProcessor.AudioFormat(rate, channels, encoding))
            it.flush()
        }

    private fun sine(hz: Double, amplitude: Double, frames: Int, channels: Int = 2): ShortArray =
        ShortArray(frames * channels) { i -> (amplitude * 32767 * sin(2 * PI * hz * (i / channels) / rate)).roundToInt().toShort() }

    /** Through the processor in buffers of 4096 frames, as the sink hands them. */
    private fun run(p: EqualizerProcessor, input: ShortArray, channels: Int = 2): ShortArray {
        val out = ArrayList<Short>(input.size)
        var at = 0
        while (at < input.size) {
            val n = minOf(4096 * channels, input.size - at)
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

    private fun rms(s: ShortArray, from: Int): Double {
        var sum = 0.0
        for (i in from until s.size) sum += s[i].toDouble() * s[i]
        return sqrt(sum / (s.size - from))
    }

    private fun db(ratio: Double) = 20 * log10(ratio)

    private fun noise(n: Int): ShortArray { val r = java.util.Random(7); return ShortArray(n) { (r.nextInt(65536) - 32768).toShort() } }

    @Test fun offIsAnExactCopy() {
        val input = noise(2 * 20_000)
        assertArrayEquals(input, run(processor(null), input))
    }

    @Test fun flatIsAnExactCopy() {
        val input = noise(2 * 20_000)
        assertArrayEquals(input, run(processor(EqTuning(FloatArray(10), 0f)), input))
    }

    @Test fun floatOffIsAnExactCopy() {
        val p = processor(null, encoding = C.ENCODING_PCM_FLOAT)
        val buf = ByteBuffer.allocateDirect(4 * 1000).order(ByteOrder.nativeOrder())
        val r = java.util.Random(3)
        val values = FloatArray(1000) { r.nextFloat() * 2 - 1 }
        values.forEach(buf::putFloat); buf.flip()
        p.queueInput(buf)
        val o = p.output
        val back = FloatArray(1000) { o.float }
        assertArrayEquals(values, back, 0f)
    }

    @Test fun otherEncodingsPassUntouched() {
        val p = EqualizerProcessor { EqTuning(FloatArray(10) { 6f }, 0f) }
        p.configure(AudioProcessor.AudioFormat(rate, 2, C.ENCODING_PCM_24BIT))
        assertFalse(p.isActive)
    }

    @Test fun oneBandRaisesItsFrequencyOnly() {
        val gains = FloatArray(10).also { it[5] = 6f } // 1 kHz
        val frames = rate // a second
        val atBand = sine(1000.0, 0.2, frames)
        val raised = run(processor(EqTuning(gains, 0f)), atBand)
        assertEquals(6.0, db(rms(raised, 2 * 4410) / rms(atBand, 2 * 4410)), 0.3)
        // Three octaves below, the bell does next to nothing.
        val far = sine(125.0, 0.2, frames)
        val kept = run(processor(EqTuning(gains, 0f)), far)
        assertEquals(0.0, db(rms(kept, 2 * 4410) / rms(far, 2 * 4410)), 0.3)
    }

    @Test fun shelvesAtTheEnds() {
        val gains = FloatArray(10).also { it[0] = -6f; it[9] = 6f }
        // Below the low shelf's corner, toward its full cut; above the high one's, toward its boost.
        val low = sine(10.0, 0.2, rate)
        val lowDb = db(rms(run(processor(EqTuning(gains, 0f)), low), 2 * 4410) / rms(low, 2 * 4410))
        assertTrue("low shelf $lowDb dB", lowDb in -6.5..-4.0)
        val high = sine(20_000.0, 0.2, rate)
        val highDb = db(rms(run(processor(EqTuning(gains, 0f)), high), 2 * 4410) / rms(high, 2 * 4410))
        assertTrue("high shelf $highDb dB", highDb in 3.0..6.5)
        // In the middle, neither.
        val mid = sine(1000.0, 0.2, rate)
        assertEquals(0.0, db(rms(run(processor(EqTuning(gains, 0f)), mid), 2 * 4410) / rms(mid, 2 * 4410)), 0.3)
    }

    @Test fun preampScalesEverything() {
        val input = sine(440.0, 0.5, rate / 4)
        val out = run(processor(EqTuning(FloatArray(10), -6.0206f)), input)
        // Started on the curve (no glide at the start of a stream): half, to the rounding.
        for (i in input.indices) assertEquals(input[i] / 2.0, out[i].toDouble(), 1.0)
    }

    @Test fun aBoostNearFullScaleIsLimitedNotClipped() {
        val gains = FloatArray(10) { if (it < 3) 12f else 0f }
        val input = sine(60.0, 0.95, rate)
        val out = run(processor(EqTuning(gains, 0f)), input)
        val peak = out.maxOf { abs(it.toInt()) }
        assertTrue("under the ceiling: $peak", peak <= (0.9886 * 32768).toInt() + 1)
        // Clipping flattens the tops: runs of the same extreme sample (four times over full scale,
        // a clipped sine sits there over a third of the time: thousands in half a second). A
        // limited one keeps its shape: at most a pair or two equal at each crest, from the rounding
        // to 16 bits (60 crests in half a second at 60 Hz).
        val half = out.copyOfRange(out.size / 2, out.size) // past the filters' own settling
        val top = half.maxOf { abs(it.toInt()) }
        var flat = 0
        for (i in 2 until half.size step 2) if (abs(half[i].toInt()) >= top - 1 && half[i] == half[i - 2]) flat++
        assertTrue("tops kept round ($flat flat)", flat <= 2 * 60)
    }

    @Test fun monoWorks() {
        val gains = FloatArray(10).also { it[5] = -6f }
        val input = sine(1000.0, 0.3, rate / 2, channels = 1)
        val out = run(processor(EqTuning(gains, 0f), channels = 1), input, channels = 1)
        assertEquals(-6.0, db(rms(out, 4410) / rms(input, 4410)), 0.3)
    }

    @Test fun turningOffGlidesThenCopies() {
        var tuning: EqTuning? = EqTuning(FloatArray(10).also { it[5] = 6f }, -3f)
        val p = EqualizerProcessor { tuning }.also { it.configure(AudioProcessor.AudioFormat(rate, 2, C.ENCODING_PCM_16BIT)); it.flush() }
        run(p, sine(1000.0, 0.2, rate / 4))
        tuning = null
        val input = noise(2 * 20_000)
        val out = run(p, input)
        // A few milliseconds of glide, then the exact copy.
        val tail = 2 * 10_000
        assertArrayEquals(input.copyOfRange(tail, input.size), out.copyOfRange(tail, out.size))
    }
}
