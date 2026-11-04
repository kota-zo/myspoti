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

    class Pcm(val mid: FloatArray, val side: FloatArray) {
        /** Of the decode, the time spent waiting for the bytes (network, decryption), ms; and their size. */
        var readMs = 0L; var bytes = 0L
        /** Of the decode, the time spent turning the codec's output into these signals, ms; reads elsewhere. */
        var convertMs = 0L; var restarts = 0
    }

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
            return run(extractor, format, mime, durationUs, cancelled).also { it.readMs = source.readNs / 1_000_000; it.bytes = source.readBytes; it.restarts = source.restarts }
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
        var convertNs = 0L
        // Copied out in one go per buffer (a value at a time through the ByteBuffer is slow).
        fun takeTimed(buffer: ByteBuffer) {
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
        fun take(buffer: ByteBuffer) {
            val t0 = System.nanoTime()
            try { takeTimed(buffer) } finally { convertNs += System.nanoTime() - t0 }
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
            return out.result().also { it.convertMs = convertNs / 1_000_000 }
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
        return out.result().also { it.convertMs = convertNs / 1_000_000 }
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

        private var step = RATE.toDouble() / inRate
        fun rate(r: Int) { if (r in 8000..192000) { inRate = r; step = RATE.toDouble() / r } }

        fun push(l: Float, r: Float) {
            accM += (l + r) * 0.5; accS += (l - r) * 0.5; cnt++
            pos += step
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

    /**
     * Random-access adapter over the player's data source, reading ahead: a thread of its own fetches
     * the bytes after the ones asked (up to [AHEAD_BYTES]) while the codec decodes. Read in turns, the
     * network and the decoder added up (on the phone, 24 MB of a Tidal FLAC: 8.2 s waiting for bytes
     * plus 7.8 s decoding). A read elsewhere (a seek) starts again from there.
     */
    private class ByteSource(private val uri: Uri, private val cancelled: () -> Boolean) : MediaDataSource() {
        private val data = OfflineCrypto.Factory(Graph.app).createDataSource()
        private var size = -1L
        private var sizeKnown = false
        /** [data] is open at 0 from [getSize], not read yet: the first read from 0 goes on from it. */
        private var openAtStart = false
        private var ahead: Ahead? = null

        /** Time the decoder waited for bytes (ns), the bytes read, and the reads elsewhere (each starts again). */
        var readNs = 0L; var readBytes = 0L; var restarts = 0
        /**
         * The last bytes handed to the decoder, by their offset: an MP4's extractor goes back a few
         * hundred KB at each fragment (to its samples after the box saying where they are), and each
         * of those went to the network again (39 to 75 times a song, ~0.2 s each).
         */
        private val history = ArrayDeque<Pair<Long, ByteArray>>()
        private var historyBytes = 0L

        private fun remember(start: Long, c: ByteArray) {
            history.addLast(start to c); historyBytes += c.size
            while (historyBytes > HISTORY_BYTES && history.size > 1) historyBytes -= history.removeFirst().second.size
        }

        /** Bytes at [position] (before [until]) from [history], or -1 when they are not there. */
        private fun fromHistory(position: Long, until: Long, buffer: ByteArray, offset: Int, length: Int): Int {
            for (i in history.indices.reversed()) {
                val (start, c) = history[i]
                if (position < start || position >= start + c.size) continue
                val from = (position - start).toInt()
                val n = minOf(length.toLong(), c.size - from.toLong(), until - position).toInt()
                if (n <= 0) return -1
                System.arraycopy(c, from, buffer, offset, n)
                return n
            }
            return -1
        }

        @Synchronized override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
            if (cancelled()) throw IOException("cancelled")
            if (length == 0) return 0
            if (position < 0) return -1
            if (sizeKnown && size >= 0 && position >= size) return -1
            val t0 = System.nanoTime()
            try {
                var a = ahead
                // A little back (the extractor going back to a fragment's samples): from memory.
                if (a != null && position < a.pos) {
                    val n = fromHistory(position, a.pos, buffer, offset, length)
                    if (n > 0) { readBytes += n; return n }
                }
                // A little further on (the extractor skipping a box): taken from the bytes read ahead.
                if (a != null && position > a.pos && position - a.pos <= SKIP_BYTES) a.skip(position - a.pos)
                if (a == null || a.pos != position) {
                    if (a != null) restarts++
                    a?.close()
                    a = Ahead(position, openAtStart && position == 0L).also { ahead = it }
                    openAtStart = false
                }
                val n = a.read(buffer, offset, length)
                if (n > 0) readBytes += n
                return n
            } finally { readNs += System.nanoTime() - t0 }
        }

        @Synchronized override fun getSize(): Long {
            if (!sizeKnown) {
                ahead?.close(); ahead = null
                if (openAtStart) runCatching { data.close() }
                val total = data.open(DataSpec.Builder().setUri(uri).build())
                size = if (total >= 0) total else -1
                sizeKnown = true; openAtStart = true
            }
            return size
        }

        @Synchronized override fun close() {
            ahead?.close(); ahead = null
            if (openAtStart) { runCatching { data.close() }; openAtStart = false }
        }

        /** The bytes from [start] on, fetched by a thread of its own into a bounded queue of chunks. */
        private inner class Ahead(start: Long, private val alreadyOpen: Boolean) {
            /** Where the next byte handed to the decoder is. */
            var pos = start
            private val chunks = java.util.concurrent.LinkedBlockingQueue<ByteArray>(AHEAD_BYTES / CHUNK)
            @Volatile private var stopped = false
            @Volatile private var failure: Throwable? = null
            private var chunk: ByteArray? = null
            private var at = 0
            private val thread = Thread {
                try {
                    if (!alreadyOpen) {
                        val remaining = data.open(DataSpec.Builder().setUri(uri).setPosition(start).build())
                        if (!sizeKnown) { size = if (remaining >= 0) start + remaining else -1; sizeKnown = true }
                    }
                    while (!stopped) {
                        val buf = ByteArray(CHUNK)
                        var n = 0
                        while (n < CHUNK && !stopped) {
                            val r = data.read(buf, n, CHUNK - n)
                            if (r == C.RESULT_END_OF_INPUT) break
                            n += r
                        }
                        if (n > 0) chunks.put(if (n == CHUNK) buf else buf.copyOf(n))
                        if (n < CHUNK) break
                    }
                } catch (t: Throwable) {
                    if (!stopped) failure = t
                } finally {
                    runCatching { data.close() }
                    // The end (or the failure) for the reader: an empty chunk.
                    chunks.offer(END)
                }
            }.apply { isDaemon = true; name = "automix-read"; start() }

            fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                var c = chunk
                if (c == null || at >= c.size) {
                    var next: ByteArray? = null
                    while (next == null) {
                        if (cancelled()) throw IOException("cancelled")
                        next = chunks.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS)
                    }
                    if (next.isEmpty()) {
                        chunk = END; at = 0
                        // Put back for any later read of the end.
                        chunks.offer(END)
                        failure?.let { throw if (it is IOException) it else IOException(it) }
                        return -1
                    }
                    c = next; chunk = c; at = 0
                    remember(pos, c)
                }
                val n = minOf(length, c.size - at)
                System.arraycopy(c, at, buffer, offset, n)
                at += n; pos += n
                return n
            }

            /** Drops [n] bytes ahead (they come in order): the next read is [n] further on. */
            fun skip(n: Long) {
                var left = n
                val scratch = ByteArray(minOf(left, CHUNK.toLong()).toInt())
                while (left > 0) {
                    val r = read(scratch, 0, minOf(left, scratch.size.toLong()).toInt())
                    if (r <= 0) return
                    left -= r
                }
            }

            fun close() {
                stopped = true
                chunks.clear()
                thread.interrupt()
                runCatching { thread.join(2_000) }
            }
        }

        companion object {
            private const val CHUNK = 256 * 1024
            private const val AHEAD_BYTES = 16 * 1024 * 1024
            private const val SKIP_BYTES = 4L * 1024 * 1024
            private const val HISTORY_BYTES = 4L * 1024 * 1024
            private val END = ByteArray(0)
        }
    }
}
