/* SPDX-License-Identifier: GPL-3.0-only */
package app.myspoti.automix

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Frame features of a song, from its mid (L+R)/2 and side (L-R)/2 signals at [RATE] Hz.
 * Onset frames are [HOP] samples apart ([FPS] per second).
 */
class Features private constructor(
    /** Spectral flux on log bands, all bands. */
    val onset: FloatArray,
    /** Spectral flux of the bands under 150 Hz (kicks, 808s). */
    val bassOnset: FloatArray,
    /** Band levels in dB per onset frame: low (< 180 Hz), mid (180 Hz – 2.5 kHz), high (> 2.5 kHz), all. */
    val lowDb: FloatArray, val midDb: FloatArray, val highDb: FloatArray, val rmsDb: FloatArray,
    /** Level in dB per onset frame of the band where two bass lines mask each other ([SegueTuning.BASS_LOW_HZ] to [SegueTuning.BASS_HIGH_HZ]). */
    val bassDb: FloatArray,
    /** Pitch-class profile, 12 values per chroma frame ([CHROMA_HOP] samples apart). */
    val chroma: Array<FloatArray>,
    /** Voice-band (300 Hz – 3.4 kHz) level of the mid signal over the side signal, in dB per chroma frame. */
    val centre: FloatArray,
    /** True when the song has a side signal at all (not mono): [centre] means something only then. */
    val stereo: Boolean,
    val durationSec: Double,
) {
    val frames get() = onset.size

    companion object {
        const val RATE = 22050
        const val HOP = 256
        const val N = 1024
        const val CHROMA_N = 4096
        const val CHROMA_HOP = 2048
        const val FPS = RATE.toDouble() / HOP
        const val CHROMA_FPS = RATE.toDouble() / CHROMA_HOP
        const val LOW_HZ = 180.0
        const val HIGH_HZ = 2500.0
        private const val BANDS = 40
        private const val POWER_NORM = N * N / 32.0
        // FFT tables and windows are immutable; every worker owns only its scratch buffers.
        private val onsetFft = Fft(N)
        private val chromaFft = Fft(CHROMA_N)
        private val onsetWindow = hann(N)
        private val chromaWindow = hann(CHROMA_N)

        /**
         * [threads] is the total worker budget, including the chroma task and the calling thread.
         * [length]: the samples of [mid] and [side] that are the song (the decoder's buffers are
         * longer, and a copy to size took a second song's worth of memory: reported OOM).
         */
        fun of(mid: FloatArray, side: FloatArray?, cancelled: () -> Boolean = { false }, threads: Int = 1, length: Int = mid.size): Features {
            if (cancelled()) throw InterruptedException("analysis cancelled")
            val win = onsetWindow
            val frames = max(1, (length - N) / HOP + 1)
            val binHz = RATE.toDouble() / N
            // Log-spaced bands, 30 Hz to 10 kHz, each at least one bin wide.
            val edges = IntArray(BANDS + 1) { i ->
                val hz = 30.0 * (10_000.0 / 30.0).pow(i.toDouble() / BANDS)
                (hz / binHz).roundToInt().coerceIn(1, N / 2)
            }
            for (i in 1..BANDS) if (edges[i] <= edges[i - 1]) edges[i] = minOf(edges[i - 1] + 1, N / 2)
            val bassBands = (0 until BANDS).count { edges[it + 1] * binHz <= 150.0 }.coerceAtLeast(2)
            val lowBin = (LOW_HZ / binHz).toInt()
            val highBin = (HIGH_HZ / binHz).toInt()
            val bassFrom = ceil(app.myspoti.automix.segue.SegueTuning.BASS_LOW_HZ / binHz).toInt().coerceAtLeast(1)
            val bassTo = floor(app.myspoti.automix.segue.SegueTuning.BASS_HIGH_HZ / binHz).toInt()

            val onset = FloatArray(frames)
            val bassOnset = FloatArray(frames)
            val lowDb = FloatArray(frames); val midDb = FloatArray(frames); val highDb = FloatArray(frames); val rmsDb = FloatArray(frames); val bassDb = FloatArray(frames)
            var chromaResult: Triple<Array<FloatArray>, FloatArray, Boolean>? = null
            val budget = max(1, threads)
            val slices = max(1, budget - 1)
            // Task 0: the chroma; tasks 1..slices: the onset frames, a slice each (from the frame before it, for the flux).
            parallel(slices + 1, budget) { task ->
              if (task == 0) chromaResult = chromaAndCentre(mid, side, cancelled, length) else {
              val f0 = frames.toLong() * (task - 1) / slices; val f1 = frames.toLong() * task / slices
              val fft = onsetFft
              val frame = DoubleArray(N); val mag = DoubleArray(N / 2 + 1)
              val re = DoubleArray(N); val im = DoubleArray(N)
              var prev = DoubleArray(BANDS); var cur = DoubleArray(BANDS)
              for (f in max(0, f0.toInt() - 1) until f1.toInt()) {
                if (f and 1023 == 0 && cancelled()) throw InterruptedException("analysis cancelled")
                val start = f * HOP
                for (i in 0 until N) frame[i] = sample(mid, start + i, length) * win[i]
                fft.magnitudes(frame, mag, re, im)
                var low = 0.0; var mi = 0.0; var hi = 0.0; var ba = 0.0
                for (k in 1..N / 2) {
                    val p = mag[k] * mag[k]
                    when { k < lowBin -> low += p; k < highBin -> mi += p; else -> hi += p }
                    if (k in bassFrom..bassTo) ba += p
                }
                // Power to dB of a full-scale sine ~ 0 dB (Hann window sum N/2, one-sided spectrum).
                lowDb[f] = db(low / POWER_NORM); midDb[f] = db(mi / POWER_NORM); highDb[f] = db(hi / POWER_NORM); rmsDb[f] = db((low + mi + hi) / POWER_NORM); bassDb[f] = db(ba / POWER_NORM)
                for (b in 0 until BANDS) {
                    var s = 0.0
                    for (k in edges[b] until edges[b + 1]) s += mag[k]
                    cur[b] = ln(1.0 + 1000.0 * s / (N / 4.0))
                }
                if (f > 0 && f >= f0) {
                    var all = 0.0; var bass = 0.0
                    for (b in 0 until BANDS) {
                        val d = cur[b] - prev[b]
                        if (d > 0) { all += d; if (b < bassBands) bass += d }
                    }
                    onset[f] = all.toFloat(); bassOnset[f] = bass.toFloat()
                }
                val t = prev; prev = cur; cur = t
              }
              }
            }

            val (chroma, centre, stereo) = chromaResult!!
            return Features(onset, bassOnset, lowDb, midDb, highDb, rmsDb, bassDb, chroma, centre, stereo, length.toDouble() / RATE)
        }

        /** [signal]'s sample [i], 0 past its [length]. */
        private fun sample(signal: FloatArray, i: Int, length: Int): Float = if (i < length) signal[i] else 0f

        private fun chromaAndCentre(mid: FloatArray, side: FloatArray?, cancelled: () -> Boolean, length: Int): Triple<Array<FloatArray>, FloatArray, Boolean> {
            val fft = chromaFft
            val win = chromaWindow
            val frames = max(1, (length - CHROMA_N) / CHROMA_HOP + 1)
            val binHz = RATE.toDouble() / CHROMA_N
            // Pitch class of each bin from 65 Hz (C2) to 2.1 kHz (C7); weights favour the bins near a semitone centre.
            val first = (65.0 / binHz).toInt(); val last = (2100.0 / binHz).toInt()
            val pc = IntArray(last + 1) { k -> if (k < first) -1 else Math.floorMod((12 * log2(k * binHz / 440.0)).roundToInt() + 9, 12) }
            val voiceLo = (300 / binHz).toInt(); val voiceHi = (3400 / binHz).toInt()
            val chroma = Array(frames) { FloatArray(12) }
            val centre = FloatArray(frames)
            val frame = DoubleArray(CHROMA_N); val mag = DoubleArray(CHROMA_N / 2 + 1); val smag = DoubleArray(CHROMA_N / 2 + 1)
            val re = DoubleArray(CHROMA_N); val im = DoubleArray(CHROMA_N)
            // A decoder can supply a zero side buffer for a mono file: no second FFT is needed.
            val sideSignal = side?.takeIf { samples -> (0 until length).any { samples[it] != 0f } }
            var sideEnergy = 0.0; var midEnergy = 0.0
            for (f in 0 until frames) {
                if (f and 255 == 0 && cancelled()) throw InterruptedException("analysis cancelled")
                val start = f * CHROMA_HOP
                for (i in 0 until CHROMA_N) frame[i] = sample(mid, start + i, length) * win[i]
                fft.magnitudes(frame, mag, re, im)
                val c = chroma[f]
                for (k in first..last) c[pc[k]] += ln(1.0 + 100.0 * mag[k] / (CHROMA_N / 4.0)).toFloat()
                var m = 0.0
                for (k in voiceLo..voiceHi) m += mag[k] * mag[k]
                midEnergy += m
                if (sideSignal != null) {
                    for (i in 0 until CHROMA_N) frame[i] = sample(sideSignal, start + i, length) * win[i]
                    fft.magnitudes(frame, smag, re, im)
                    var s = 0.0
                    for (k in voiceLo..voiceHi) s += smag[k] * smag[k]
                    sideEnergy += s
                    centre[f] = (db(m + 1e-9) - db(s + 1e-9)).toFloat()
                }
                val norm = c.max()
                if (norm > 0) for (i in 0 until 12) c[i] /= norm
            }
            // A side signal 30 dB under the mid one is a mono master: no centre measure.
            val stereo = sideSignal != null && sideEnergy > midEnergy * 1e-3
            return Triple(chroma, centre, stereo)
        }

        fun db(power: Double): Float = (10 * log10(power + 1e-12)).toFloat().coerceAtLeast(-120f)
    }
}
