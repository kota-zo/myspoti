/*
 * Adapted from BitChord (https://github.com/kushagrasinghx/BitChord), GPL-3.0-or-later,
 * app/src/main/java/com/music/bitchord/playback/EqualizerProcessor.kt at b690dc1.
 * Modified for Myspoti, 2026-10.
 *
 * Changes: the trapezoidal state-variable sections (shelves at both ends, bells between),
 * their coefficients and the gliding of a change are BitChord's; the bands are Myspoti's ten
 * (31 Hz to 16 kHz), the tone controls, balance and loudness stage are left out, the float
 * path and an exact copy when the curve is flat are added, and the limiter is a small one of
 * our own.
 */
package com.music.spotui.di

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.math.tan

/** The equalizer's bands and its ranges (see [EqualizerProcessor]). */
object EqBands {
    /** The centre of each band, in Hz: the first is a low shelf, the last a high shelf, bells between. */
    val FREQUENCIES = floatArrayOf(31f, 62f, 125f, 250f, 500f, 1_000f, 2_000f, 4_000f, 8_000f, 16_000f)
    val COUNT = FREQUENCIES.size
    const val MIN_DB = -12f
    const val MAX_DB = 12f
    const val PREAMP_MIN_DB = -12f
    const val PREAMP_MAX_DB = 12f
}

/** A curve: the gain of each band and the preamp, in dB. Swapped whole, never changed in place. */
class EqTuning(gainsDb: FloatArray, val preampDb: Float) {
    val gainsDb: FloatArray = FloatArray(EqBands.COUNT) { gainsDb.getOrElse(it) { 0f }.coerceIn(EqBands.MIN_DB, EqBands.MAX_DB) }
    /** Nothing to hear: the audio passes as it came, bit for bit. */
    val flat: Boolean get() = abs(preampDb) < SETTLED_DB && gainsDb.all { abs(it) < SETTLED_DB }

    companion object {
        internal const val SETTLED_DB = 0.01f
    }
}

/** The curve the local player's equalizers follow; null: off (see EqualizerPref). Read on the audio thread. */
object Equalizer {
    @Volatile var current: EqTuning? = null
}

/**
 * The equalizer of the local player (extensions' streams, downloads, the phone's files): a
 * media3 audio processor in ExoPlayer's chain, ahead of the silence skipping and the speed. Each
 * player has its own (the fading-out copy of a crossfade too), all following [source].
 *
 * Off, or on with a flat curve (every band and the preamp at 0 dB), it copies its input as it
 * came: a lossless stream stays bit for bit what the extension gave. A change glides over a few
 * milliseconds rather than stepping (no click); a curve that would push a peak past full scale is
 * caught by a peak limiter at -0.1 dBFS rather than clipped. 16-bit and float PCM; anything else
 * passes untouched (the processor is then inactive).
 */
@UnstableApi
class EqualizerProcessor(private val source: () -> EqTuning? = { Equalizer.current }) : BaseAudioProcessor() {
    private val slots = EqBands.COUNT
    private var channels = 0
    private var sampleRate = 0
    private var float = false

    private val currentGain = FloatArray(slots)
    private var currentPreamp = 0f
    private val a1 = FloatArray(slots)
    private val a2 = FloatArray(slots)
    private val a3 = FloatArray(slots)
    private val m0 = FloatArray(slots)
    private val m1 = FloatArray(slots)
    private val m2 = FloatArray(slots)
    private val active = IntArray(slots)
    private val running = BooleanArray(slots)
    /** Two integrator states per band and channel. */
    private var state = FloatArray(0)
    private var frame = FloatArray(0)
    private var limiterGain = 1f
    private var release = 0f
    private var holdFrames = 0
    private var holdLeft = 0

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        val enc = inputAudioFormat.encoding
        if ((enc != C.ENCODING_PCM_16BIT && enc != C.ENCODING_PCM_FLOAT) || inputAudioFormat.channelCount < 1 || inputAudioFormat.sampleRate <= 0)
            return AudioProcessor.AudioFormat.NOT_SET
        channels = inputAudioFormat.channelCount
        sampleRate = inputAudioFormat.sampleRate
        float = enc == C.ENCODING_PCM_FLOAT
        state = FloatArray(channels * slots * 2)
        frame = FloatArray(channels)
        // Held 50 ms after a peak (longer than a period of the lowest band), then back up in about 200 ms.
        holdFrames = sampleRate / 20
        release = 1f - kotlin.math.exp(-1f / (0.2f * sampleRate))
        return inputAudioFormat
    }

    override fun onFlush() {
        state.fill(0f); running.fill(false); limiterGain = 1f; holdLeft = 0
        // A new stream (or a seek) starts on the curve, not gliding to it.
        val t = source()
        for (i in 0 until slots) currentGain[i] = t?.gainsDb?.get(i) ?: 0f
        currentPreamp = t?.preampDb ?: 0f
    }

    override fun onReset() {
        channels = 0; sampleRate = 0; state = FloatArray(0); frame = FloatArray(0)
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return
        val target = source()?.takeUnless { it.flat }
        if (target == null && settledFlat()) {
            // The exact copy: nothing heard is changed, nothing computed.
            if (running.any { it }) { state.fill(0f); running.fill(false) }
            limiterGain = 1f; holdLeft = 0
            val out = replaceOutputBuffer(inputBuffer.remaining())
            out.put(inputBuffer)
            out.flip()
            return
        }
        val bytes = if (float) 4 else 2
        val frameBytes = bytes * channels
        val frames = inputBuffer.remaining() / frameBytes
        if (frames == 0) return
        val out = replaceOutputBuffer(frames * frameBytes)
        inputBuffer.order(ByteOrder.nativeOrder())
        val gains = target?.gainsDb
        val preampTarget = target?.preampDb ?: 0f
        var left = frames
        while (left > 0) {
            val block = min(left, GLIDE_FRAMES)
            for (i in 0 until slots) currentGain[i] += ((gains?.get(i) ?: 0f) - currentGain[i]) * GLIDE_RATE
            currentPreamp += (preampTarget - currentPreamp) * GLIDE_RATE
            val n = prepare()
            val preamp = 10f.pow(currentPreamp / 20f)
            repeat(block) {
                var peak = 0f
                for (c in 0 until channels) {
                    var s = if (float) inputBuffer.float else inputBuffer.short * (1f / 32768f)
                    for (k in 0 until n) s = section(active[k], c, s)
                    s *= preamp
                    frame[c] = s
                    if (abs(s) > peak) peak = abs(s)
                }
                // The limiter: down at once to what keeps this frame under the ceiling, held while
                // the peaks stay near it (a crest after another is turned down, not flattened), then
                // back up slowly.
                val need = if (peak > CEILING) CEILING / peak else 1f
                if (peak * limiterGain > CEILING * HOLD_NEAR) holdLeft = holdFrames
                else if (holdLeft > 0) holdLeft--
                else limiterGain += (1f - limiterGain) * release
                limiterGain = min(need, limiterGain)
                for (c in 0 until channels) {
                    val s = frame[c] * limiterGain
                    if (float) out.putFloat(s) else out.putShort((s * 32768f).roundToInt().coerceIn(-32768, 32767).toShort())
                }
            }
            flushDenormals(n)
            left -= block
        }
        out.flip()
    }

    /** No band and no preamp left to glide down: the copy can take over. */
    private fun settledFlat(): Boolean =
        abs(currentPreamp) < EqTuning.SETTLED_DB && currentGain.all { abs(it) < EqTuning.SETTLED_DB }

    /** The coefficients of the bands that do something now; their count (indices in [active]). */
    private fun prepare(): Int {
        var n = 0
        for (i in 0 until slots) {
            if (abs(currentGain[i]) >= EqTuning.SETTLED_DB) {
                coefficients(i)
                active[n++] = i
                running[i] = true
            } else if (running[i]) {
                for (c in 0 until channels) { val j = (c * slots + i) * 2; state[j] = 0f; state[j + 1] = 0f }
                running[i] = false
            }
        }
        return n
    }

    private fun coefficients(i: Int) {
        val a = 10f.pow(currentGain[i] / 40f)
        val hz = EqBands.FREQUENCIES[i].coerceIn(10f, sampleRate * 0.45f)
        val base = tan(Math.PI * hz / sampleRate).toFloat()
        val g: Float
        val k: Float
        when (i) {
            0 -> { // low shelf
                g = base / sqrt(a); k = 1f / SHELF_Q
                m0[i] = 1f; m1[i] = k * (a - 1f); m2[i] = a * a - 1f
            }
            slots - 1 -> { // high shelf
                g = base * sqrt(a); k = 1f / SHELF_Q
                m0[i] = a * a; m1[i] = k * (1f - a) * a; m2[i] = 1f - a * a
            }
            else -> { // bell
                g = base; k = 1f / (BELL_Q * a)
                m0[i] = 1f; m1[i] = k * (a * a - 1f); m2[i] = 0f
            }
        }
        val d = 1f / (1f + g * (g + k))
        a1[i] = d; a2[i] = g * d; a3[i] = g * g * d
    }

    private fun section(i: Int, c: Int, x: Float): Float {
        val j = (c * slots + i) * 2
        val ic1 = state[j]
        val ic2 = state[j + 1]
        val v3 = x - ic2
        val v1 = a1[i] * ic1 + a2[i] * v3
        val v2 = ic2 + a2[i] * ic1 + a3[i] * v3
        state[j] = 2f * v1 - ic1
        state[j + 1] = 2f * v2 - ic2
        return m0[i] * x + m1[i] * v1 + m2[i] * v2
    }

    private fun flushDenormals(n: Int) {
        for (k in 0 until n) for (c in 0 until channels) {
            val j = (c * slots + active[k]) * 2
            if (abs(state[j]) < 1e-12f) state[j] = 0f
            if (abs(state[j + 1]) < 1e-12f) state[j + 1] = 0f
        }
    }

    private companion object {
        /** Frames between two steps of a glide (about 1.5 ms at 44.1 kHz). */
        const val GLIDE_FRAMES = 64
        /** The part of what is left of a change made at each step (about 18 ms to get there). */
        const val GLIDE_RATE = 0.08f
        /** About an octave wide. */
        const val BELL_Q = 1.41f
        const val SHELF_Q = 0.707f
        /** -0.1 dBFS. */
        const val CEILING = 0.9886f
        /** Peaks within 0.5 dB of the ceiling keep the limiter's gain where it is. */
        const val HOLD_NEAR = 0.944f
    }
}
