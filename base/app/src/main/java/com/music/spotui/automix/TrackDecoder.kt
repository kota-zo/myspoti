/* SPDX-License-Identifier: GPL-3.0-only */
package com.music.spotui.automix

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import com.music.spotui.data.offline.OfflineCrypto
import com.music.spotui.di.Graph
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Decodes a whole song to the analysis signals: mid ((L+R)/2) and side ((L-R)/2) at 22.05 kHz.
 * The bytes come through the player's own data source (downloads are decrypted there, extension
 * streams resolved, DASH answers read as one file), so a song is read the way it plays.
 */
@androidx.annotation.OptIn(UnstableApi::class)
object TrackDecoder {
    const val RATE = app.myspoti.automix.Features.RATE
    /** Longer songs are not analysed (memory: 4 bytes × 2 signals × 22 050 per second). */
    const val MAX_SECONDS = 15 * 60

    class Pcm(val mid: FloatArray, val side: FloatArray)

    fun decode(uri: Uri, cancelled: () -> Boolean): Pcm {
        val source = ByteSource(uri, cancelled)
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(source)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw IOException("no audio track")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: throw IOException("no audio codec")
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else -1L
            if (durationUs > MAX_SECONDS * 1_000_000L) throw IOException("song too long to analyse")
            return run(extractor, format, mime, durationUs, cancelled)
        } finally {
            runCatching { extractor.release() }
            source.close()
        }
    }

    private fun run(extractor: MediaExtractor, format: MediaFormat, mime: String, durationUs: Long, cancelled: () -> Boolean): Pcm {
        val out = Resampler(format.getInteger(MediaFormat.KEY_SAMPLE_RATE), durationUs)
        var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var encoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) format.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
        var shorts = ShortArray(0); var floats = FloatArray(0)
        // Copied out in one go per buffer (a value at a time through the ByteBuffer is slow).
        fun take(buffer: ByteBuffer) {
            val float = encoding == AudioFormat.ENCODING_PCM_FLOAT
            if (!float && encoding != AudioFormat.ENCODING_PCM_16BIT) throw IOException("unsupported decoded PCM")
            val values = buffer.remaining() / (if (float) 4 else 2)
            val frames = values / channels
            if (float) { if (floats.size < values) floats = FloatArray(values); buffer.asFloatBuffer().get(floats, 0, values) }
            else { if (shorts.size < values) shorts = ShortArray(values); buffer.asShortBuffer().get(shorts, 0, values) }
            for (i in 0 until frames) {
                val at = i * channels
                val l = if (float) floats[at] else shorts[at] / 32768f
                val r = if (channels == 1) l else if (float) floats[at + 1] else shorts[at + 1] / 32768f
                out.push(if (l.isFinite()) l else 0f, if (r.isFinite()) r else 0f)
            }
        }
        if (mime == "audio/raw") {
            val packet = ByteBuffer.allocateDirect(1 shl 20).order(ByteOrder.LITTLE_ENDIAN)
            while (true) {
                if (cancelled()) throw InterruptedException("cancelled")
                packet.clear()
                val size = extractor.readSampleData(packet, 0)
                if (size < 0) break
                packet.position(0); packet.limit(size)
                take(packet)
                extractor.advance()
            }
            return out.result()
        }
        val codec = MediaCodec.createDecoderByType(mime)
        var started = false
        try {
            codec.configure(format, null, null, 0)
            codec.start(); started = true
            var inputDone = false
            var ended = false
            val info = MediaCodec.BufferInfo()
            // Every free input slot filled, every ready output taken, without waiting on either while
            // the other has work (a fixed 5-ms wait per packet made a song take 10 s).
            while (!ended) {
                if (cancelled()) throw InterruptedException("cancelled")
                while (!inputDone) {
                    val slot = codec.dequeueInputBuffer(0)
                    if (slot < 0) break
                    val input = codec.getInputBuffer(slot) ?: throw IOException("no decoder input")
                    val size = extractor.readSampleData(input, 0)
                    if (size < 0) { codec.queueInputBuffer(slot, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                    else { codec.queueInputBuffer(slot, 0, size, extractor.sampleTime, 0); extractor.advance() }
                }
                var slot = codec.dequeueOutputBuffer(info, 2000)
                while (slot >= 0 || slot == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED || slot == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                    if (slot == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val f = codec.outputFormat
                        channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        encoding = if (f.containsKey(MediaFormat.KEY_PCM_ENCODING)) f.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                        out.rate(f.getInteger(MediaFormat.KEY_SAMPLE_RATE))
                    } else if (slot >= 0) {
                        val buf = codec.getOutputBuffer(slot)?.order(ByteOrder.LITTLE_ENDIAN)
                        if (buf != null && info.size > 0) { buf.position(info.offset); buf.limit(info.offset + info.size); take(buf) }
                        codec.releaseOutputBuffer(slot, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) { ended = true; break }
                    }
                    slot = codec.dequeueOutputBuffer(info, 0)
                }
            }
        } finally {
            if (started) runCatching { codec.stop() }
            codec.release()
        }
        return out.result()
    }

    /**
     * Down to 22.05 kHz: a box average over the source samples of each output sample (enough
     * against aliasing for onsets and the mel bands up to 11 kHz), linear between them.
     */
    private class Resampler(private var inRate: Int, durationUs: Long) {
        private var mid = FloatArray(if (durationUs > 0) (durationUs * RATE / 1_000_000L).toInt() + RATE else RATE * 240)
        private var side = FloatArray(mid.size)
        private var n = 0
        private var pos = 0.0
        private var accM = 0.0; private var accS = 0.0; private var cnt = 0

        fun rate(r: Int) { if (r in 8000..192000) inRate = r }

        fun push(l: Float, r: Float) {
            accM += (l + r) * 0.5; accS += (l - r) * 0.5; cnt++
            pos += RATE.toDouble() / inRate
            while (pos >= 1.0) {
                pos -= 1.0
                if (n >= mid.size) {
                    if (n >= MAX_SECONDS * RATE) throw IOException("song too long to analyse")
                    mid = mid.copyOf(mid.size * 3 / 2); side = side.copyOf(mid.size)
                }
                mid[n] = (accM / cnt).toFloat(); side[n] = (accS / cnt).toFloat(); n++
                if (pos < 1.0) { accM = 0.0; accS = 0.0; cnt = 0 }
            }
        }

        fun result(): Pcm {
            if (n < RATE * 5) throw IOException("decoded audio too short")
            return Pcm(mid.copyOf(n), side.copyOf(n))
        }
    }

    /** Random-access adapter over the player's data source: each seek reopens it at the new position. */
    private class ByteSource(private val uri: Uri, private val cancelled: () -> Boolean) : MediaDataSource() {
        private val data = OfflineCrypto.Factory(Graph.app).createDataSource()
        private var open = false
        private var cursor = 0L
        private var size = -1L
        private var sizeKnown = false

        @Synchronized override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
            if (cancelled()) throw IOException("cancelled")
            if (length == 0) return 0
            if (position < 0) return -1
            if (!open || cursor != position) {
                if (open) runCatching { data.close() }
                val remaining = data.open(DataSpec.Builder().setUri(uri).setPosition(position).build())
                if (!sizeKnown) { size = if (remaining >= 0) position + remaining else -1; sizeKnown = true }
                open = true; cursor = position
            }
            val n = data.read(buffer, offset, length)
            if (n == C.RESULT_END_OF_INPUT) return -1
            if (n > 0) cursor += n
            return n
        }

        @Synchronized override fun getSize(): Long {
            if (!sizeKnown) {
                if (open) runCatching { data.close() }
                val total = data.open(DataSpec.Builder().setUri(uri).build())
                size = if (total >= 0) total else -1
                sizeKnown = true; open = true; cursor = 0
            }
            return size
        }

        @Synchronized override fun close() { runCatching { data.close() }; open = false }
    }
}
