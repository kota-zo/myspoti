/* SPDX-License-Identifier: GPL-3.0-only */
package com.music.spotui.automix

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import app.myspoti.automix.segue.LaneSide
import app.myspoti.automix.segue.SegueDeck
import app.myspoti.automix.segue.SeguePlan
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Plays a side of a plan in the player's audio chain. Until one is armed it copies its input as it
 * came. It runs ahead of the player's buffers and its speed, and knows from [SegueSink] where each
 * buffer sits in the song: an automation point lands on its sample, however late that sample is heard.
 */
@UnstableApi
class SegueProcessor : BaseAudioProcessor() {
    private class Armed(val plan: SeguePlan, val side: LaneSide)

    @Volatile private var armed: Armed? = null
    @Volatile private var holding = false
    private var deck: SegueDeck? = null
    private var deckOf: Armed? = null
    private var scratch = FloatArray(0)

    /** The song position of the next frame, microseconds, once a buffer has told it. */
    @Volatile var positionUs = 0L
        private set
    @Volatile var marked = false
        private set

    /** From [SegueSink], on the playback thread, ahead of the buffer it describes. */
    fun mark(sourceUs: Long) { positionUs = sourceUs; marked = true }

    fun arm(plan: SeguePlan, side: LaneSide) { holding = false; armed = Armed(plan, side) }

    /** Plain copies from the next buffer on: for a side whose lanes have settled on neutral. */
    fun disarm() { holding = false; armed = null }

    /** The plain song again over a short ramp, then a plain copy: the plan no longer applies. */
    fun release() { if (armed != null) holding = true }

    val isArmed: Boolean get() = armed != null

    override fun onConfigure(format: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (format.encoding != C.ENCODING_PCM_16BIT && format.encoding != C.ENCODING_PCM_FLOAT) return AudioProcessor.AudioFormat.NOT_SET
        if (format.channelCount !in 1..2) return AudioProcessor.AudioFormat.NOT_SET
        deck = null; deckOf = null
        return format
    }

    override fun queueInput(input: ByteBuffer) {
        val size = input.remaining()
        if (size == 0) return
        val format = inputAudioFormat
        val frames = size / format.bytesPerFrame
        val at = positionUs
        positionUs = at + frames * 1_000_000L / format.sampleRate
        val out = replaceOutputBuffer(size)
        val a = armed
        if (a == null) { deck = null; deckOf = null; out.put(input); out.flip(); return }
        if (deckOf !== a) {
            deck = SegueDeck(a.plan, a.side, format.sampleRate, format.channelCount)
            deckOf = a
        }
        val d = deck!!
        d.hold = holding
        val n = frames * format.channelCount
        if (scratch.size < n) scratch = FloatArray(n)
        val s = scratch
        val pcm = input.order(ByteOrder.nativeOrder())
        if (format.encoding == C.ENCODING_PCM_FLOAT) for (i in 0 until n) s[i] = pcm.getFloat()
        else for (i in 0 until n) s[i] = pcm.getShort() / 32768f
        d.process(s, frames, at)
        if (format.encoding == C.ENCODING_PCM_FLOAT) for (i in 0 until n) out.putFloat(s[i])
        else for (i in 0 until n) out.putShort((s[i] * 32767f).toInt().coerceIn(-32768, 32767).toShort())
        out.flip()
        if (holding && d.holdSettled) { armed = null; holding = false }
    }

    override fun onReset() { deck = null; deckOf = null; armed = null; holding = false; marked = false; positionUs = 0 }
}

/** Tells the [SegueProcessor] at the head of the chain where each buffer sits in the song. */
@UnstableApi
class SegueSink(sink: AudioSink, private val processor: SegueProcessor) : ForwardingAudioSink(sink) {
    private var offsetUs = 0L
    private var last = Long.MIN_VALUE

    override fun setOutputStreamOffsetUs(outputStreamOffsetUs: Long) {
        offsetUs = outputStreamOffsetUs
        super.setOutputStreamOffsetUs(outputStreamOffsetUs)
    }

    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        // The same buffer comes back while the sink is full: only a new one tells a new place.
        if (presentationTimeUs != last) { last = presentationTimeUs; processor.mark(presentationTimeUs - offsetUs) }
        return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
    }

    override fun flush() { last = Long.MIN_VALUE; super.flush() }
}
