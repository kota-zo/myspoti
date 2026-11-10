package com.music.spotui.data.offline

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Re-encodes a downloaded song (FLAC, or anything Android decodes) into AAC in an .m4a
 * file, with the phone's own codecs: the "smallest" download quality. The FLAC is
 * checked first, so the small file is still the right recording.
 */
object AacTranscoder {
    const val BITRATE = 96_000
    private const val TIMEOUT_US = 10_000L

    /** Writes [output]; throws on any codec error (the caller marks the song failed). */
    fun transcode(input: File, output: File, bitrate: Int = BITRATE) {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        try {
            extractor.setDataSource(input.path)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw IOException("no audio track")
            extractor.selectTrack(track)
            val inFormat = extractor.getTrackFormat(track)
            val inRate = inFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            // Phone AAC encoders stop at 48 kHz: hi-res rates are divided down (96 → 48, 88.2 → 44.1).
            val step = decimation(inRate)
            val sampleRate = inRate / step
            val channels = inFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtMost(2)

            decoder = MediaCodec.createDecoderByType(inFormat.getString(MediaFormat.KEY_MIME)!!).apply {
                configure(inFormat, null, null, 0); start()
            }
            val outFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
            }
            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
                configure(outFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); start()
            }
            muxer = MediaMuxer(output.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var decodeDone = false
            var encodeDone = false
            var muxTrack = -1
            var pcmEncoding = android.media.AudioFormat.ENCODING_PCM_16BIT
            var decodedChannels = channels
            var framesFed = 0L
            // PCM waiting for room in the encoder (a decoder buffer can be bigger than its input).
            var pending: ByteArray? = null
            var pendingOffset = 0

            // Encoder → file; true once the end of the stream is written.
            fun drain(): Boolean {
                while (true) {
                    val o = encoder.dequeueOutputBuffer(info, 0)
                    when {
                        o == MediaCodec.INFO_TRY_AGAIN_LATER -> return false
                        o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            muxTrack = muxer.addTrack(encoder.outputFormat)
                            muxer.start()
                        }
                        o >= 0 -> {
                            val buf = encoder.getOutputBuffer(o)!!
                            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                            if (info.size > 0 && muxTrack >= 0) {
                                buf.position(info.offset); buf.limit(info.offset + info.size)
                                muxer.writeSampleData(muxTrack, buf, info)
                            }
                            encoder.releaseOutputBuffer(o, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return true
                        }
                    }
                }
            }

            while (!encodeDone) {
                if (Thread.interrupted()) throw InterruptedException()
                // 1. File → decoder.
                if (!inputDone) {
                    val i = decoder.dequeueInputBuffer(TIMEOUT_US)
                    if (i >= 0) {
                        val buf = decoder.getInputBuffer(i)!!
                        val n = extractor.readSampleData(buf, 0)
                        if (n < 0) {
                            decoder.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(i, 0, n, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                // 2. Decoder → 16-bit PCM (its end-of-stream buffer too, even empty).
                if (pending == null && !decodeDone) {
                    val o = decoder.dequeueOutputBuffer(info, TIMEOUT_US)
                    if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val f = decoder.outputFormat
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N && f.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            pcmEncoding = f.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        }
                        decodedChannels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    } else if (o >= 0) {
                        val buf = decoder.getOutputBuffer(o)!!
                        buf.position(info.offset); buf.limit(info.offset + info.size)
                        pending = toPcm16(buf, pcmEncoding, decodedChannels, channels, step)
                        pendingOffset = 0
                        decoder.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) decodeDone = true
                    }
                }
                // 3. PCM → encoder, the clock counted in frames.
                val pcm = pending
                if (pcm != null) {
                    val i = encoder.dequeueInputBuffer(TIMEOUT_US)
                    if (i >= 0) {
                        val buf = encoder.getInputBuffer(i)!!
                        val n = minOf(buf.capacity(), pcm.size - pendingOffset)
                        buf.clear(); buf.put(pcm, pendingOffset, n)
                        val pts = framesFed * 1_000_000L / sampleRate
                        framesFed += n / (2 * channels)
                        pendingOffset += n
                        val last = pendingOffset >= pcm.size
                        encoder.queueInputBuffer(i, 0, n, pts, if (last && decodeDone) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                        if (last) pending = null
                    }
                }
                // 4. Encoder → file.
                encodeDone = drain()
            }
        } finally {
            runCatching { decoder?.stop() }; runCatching { decoder?.release() }
            runCatching { encoder?.stop() }; runCatching { encoder?.release() }
            runCatching { muxer?.stop() }; runCatching { muxer?.release() }
            extractor.release()
        }
    }

    /** How many input frames make one output frame, so the rate is 48 kHz or less. */
    internal fun decimation(rate: Int): Int {
        var step = 1
        while (rate / step > 48_000) step *= 2
        return step
    }

    /**
     * Decoder output (16-bit, float or 24/32-bit PCM, any channel count) → 16-bit PCM with
     * [outChannels] channels, every [step] frames averaged into one (a plain low-pass).
     */
    internal fun toPcm16(buf: ByteBuffer, encoding: Int, inChannels: Int, outChannels: Int, step: Int = 1): ByteArray {
        val b = buf.slice().order(ByteOrder.nativeOrder())
        // The usual case (CD FLAC, 16-bit stereo): the decoder's bytes are already what the encoder takes.
        if (step == 1 && inChannels == outChannels &&
            encoding != android.media.AudioFormat.ENCODING_PCM_FLOAT && encoding != android.media.AudioFormat.ENCODING_PCM_32BIT &&
            encoding != android.media.AudioFormat.ENCODING_PCM_24BIT_PACKED
        ) return ByteArray(b.remaining()).also { b.get(it) }
        val bytesPerSample = when (encoding) {
            android.media.AudioFormat.ENCODING_PCM_FLOAT, android.media.AudioFormat.ENCODING_PCM_32BIT -> 4
            android.media.AudioFormat.ENCODING_PCM_24BIT_PACKED -> 3
            else -> 2
        }
        val frames = b.remaining() / (bytesPerSample * inChannels)
        fun sample(f: Int, c: Int): Int {
            val p = (f * inChannels + c) * bytesPerSample
            return when (encoding) {
                android.media.AudioFormat.ENCODING_PCM_FLOAT -> (b.getFloat(p).coerceIn(-1f, 1f) * 32767f).toInt()
                android.media.AudioFormat.ENCODING_PCM_32BIT -> b.getInt(p) shr 16
                android.media.AudioFormat.ENCODING_PCM_24BIT_PACKED ->
                    ((b.get(p + 2).toInt() shl 16) or ((b.get(p + 1).toInt() and 0xFF) shl 8) or (b.get(p).toInt() and 0xFF)) shr 8
                else -> b.getShort(p).toInt()
            }
        }
        val outFrames = frames / step
        val out = ByteBuffer.allocate(outFrames * outChannels * 2).order(ByteOrder.nativeOrder())
        for (o in 0 until outFrames) {
            for (c in 0 until outChannels) {
                val from = minOf(c, inChannels - 1)
                var sum = 0
                for (k in 0 until step) sum += sample(o * step + k, from)
                out.putShort((sum / step).coerceIn(-32768, 32767).toShort())
            }
        }
        return out.array()
    }
}
