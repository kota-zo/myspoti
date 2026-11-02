package com.music.spotui.di

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** The stereo width the local player's spatial stage follows; null: off (see EqualizerPref). Read on the audio thread. */
object Spatial {
    /** 1 is the song as it came; above, the sides are brought out. */
    const val MIN_WIDTH = 1.1f
    const val MAX_WIDTH = 2.5f
    const val DEFAULT_WIDTH = 1.6f
    @Volatile var current: Float? = null
}

/**
 * Spatial audio for the local player (extensions' streams, downloads, the phone's files): the
 * stereo image widened by mid/side processing, after the equalizer in ExoPlayer's chain. Each
 * player has its own (the fading-out copy of a crossfade too), all following [source].
 *
 * The mid (L+R)/2 stays as it is, the side (L-R)/2 is raised by the width, and the whole is turned
 * down a little so a wider song does not sound louder. A peak the wider image would push past
 * full scale is caught by a limiter at -0.1 dBFS rather than clipped. Off, or once a change back to
 * width 1 has glided there, it copies its input as it came, bit for bit. Stereo 16-bit and float
 * PCM only: mono and multichannel pass untouched (the processor is then inactive).
 */
@UnstableApi
class SpatialProcessor(private val source: () -> Float? = { Spatial.current }) : BaseAudioProcessor() {
    private var float = false
    private var width = 1f
    private var limiterGain = 1f
    private var release = 0f

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        val enc = inputAudioFormat.encoding
        if ((enc != C.ENCODING_PCM_16BIT && enc != C.ENCODING_PCM_FLOAT) || inputAudioFormat.channelCount != 2 || inputAudioFormat.sampleRate <= 0)
            return AudioProcessor.AudioFormat.NOT_SET
        float = enc == C.ENCODING_PCM_FLOAT
        // Back up in about 200 ms once a peak has passed.
        release = 1f - exp(-1f / (0.2f * inputAudioFormat.sampleRate))
        return inputAudioFormat
    }

    override fun onFlush() {
        // A new stream (or a seek) starts at the width, not gliding to it.
        width = target()
        limiterGain = 1f
    }

    override fun onReset() {
        width = 1f; limiterGain = 1f
    }

    private fun target(): Float = source()?.coerceIn(1f, Spatial.MAX_WIDTH) ?: 1f

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return
        val target = target()
        if (target == 1f && abs(width - 1f) < SETTLED && limiterGain > 1f - SETTLED) {
            // The exact copy: nothing heard is changed, nothing computed.
            width = 1f; limiterGain = 1f
            val out = replaceOutputBuffer(inputBuffer.remaining())
            out.put(inputBuffer)
            out.flip()
            return
        }
        val frameBytes = if (float) 8 else 4
        val frames = inputBuffer.remaining() / frameBytes
        if (frames == 0) return
        val out = replaceOutputBuffer(frames * frameBytes)
        inputBuffer.order(ByteOrder.nativeOrder())
        var left = frames
        while (left > 0) {
            val block = min(left, GLIDE_FRAMES)
            width += (target - width) * GLIDE_RATE
            if (abs(target - width) < SETTLED) width = target
            // Back at width 1, nothing can go past full scale any more: the limiter lets go as fast
            // as a width glides, and the copy takes over once it has.
            val unwinding = width == 1f
            if (unwinding) limiterGain += (1f - limiterGain) * GLIDE_RATE
            val gain = makeup(width)
            repeat(block) {
                val l = if (float) inputBuffer.float else inputBuffer.short * (1f / 32768f)
                val r = if (float) inputBuffer.float else inputBuffer.short * (1f / 32768f)
                val mid = (l + r) * 0.5f
                val side = (l - r) * 0.5f * width
                var outL = (mid + side) * gain
                var outR = (mid - side) * gain
                // The limiter: down at once to what keeps this frame under the ceiling, back up slowly.
                if (!unwinding) {
                    val peak = max(abs(outL), abs(outR))
                    limiterGain += (1f - limiterGain) * release
                    if (peak * limiterGain > CEILING) limiterGain = CEILING / peak
                }
                outL *= limiterGain
                outR *= limiterGain
                if (float) {
                    out.putFloat(outL); out.putFloat(outR)
                } else {
                    out.putShort((outL * 32768f).roundToInt().coerceIn(-32768, 32767).toShort())
                    out.putShort((outR * 32768f).roundToInt().coerceIn(-32768, 32767).toShort())
                }
            }
            left -= block
        }
        out.flip()
    }

    companion object {
        /**
         * The level the whole is turned down to at [width]: a quarter of what the side gained, so a
         * centred voice stays close to where it was (0.8, -1.9 dB, at width 2) and the limiter is
         * left the rare peaks of a hard-panned sound.
         */
        internal fun makeup(width: Float): Float = 1f / (1f + (width - 1f) * 0.25f)

        /** Frames between two steps of a glide (about 1.5 ms at 44.1 kHz). */
        private const val GLIDE_FRAMES = 64
        /** The part of what is left of a change made at each step (about 18 ms to get most of the way). */
        private const val GLIDE_RATE = 0.08f
        private const val SETTLED = 0.001f
        /** -0.1 dBFS. */
        private const val CEILING = 0.9886f
    }
}
