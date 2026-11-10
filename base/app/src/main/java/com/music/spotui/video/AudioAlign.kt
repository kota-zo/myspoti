package com.music.spotui.video

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.io.File
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Song time → music video time, found by aligning the two audio tracks (the clip's
 * soundtrack is the song, plus intros, skits or cuts).
 *
 * Features: spectral flux in 32 log-spaced bands (80 Hz–5 kHz), 20 ms hop, z-scored.
 * Song windows of 6 s (every 3 s) are located in the video by normalised
 * cross-correlation; a dynamic-programming pass keeps a continuous path (the video
 * only moves forward, offsets change rarely), which drops the false matches of
 * repeated choruses. Consecutive windows with the same offset form the segments.
 */
object AudioAlign {
    private const val SR = 11025
    private const val HOP = 220            // 19.95 ms
    private const val N = 1024
    private const val BANDS = 32
    private const val FRAME_S = HOP.toDouble() / SR

    data class Segment(val songStartMs: Long, val songEndMs: Long, val offsetMs: Long)

    /** Piecewise-constant map; outside the segments the nearest one applies. */
    data class TimeMap(val segments: List<Segment>, val score: Double) {
        fun toVideo(songMs: Long): Long {
            if (segments.isEmpty()) return songMs
            val s = segments.firstOrNull { songMs >= it.songStartMs && songMs < it.songEndMs }
                ?: segments.minBy { min(abs(songMs - it.songStartMs), abs(songMs - it.songEndMs)) }
            return songMs + s.offsetMs
        }

        /** Video time → song time (inverse map; in a video-only part, its nearest song time). */
        fun toSong(videoMs: Long): Long {
            if (segments.isEmpty()) return videoMs
            val s = segments.firstOrNull { videoMs - it.offsetMs >= it.songStartMs && videoMs - it.offsetMs < it.songEndMs }
                ?: segments.minBy { seg ->
                    val t = videoMs - seg.offsetMs
                    if (t < seg.songStartMs) seg.songStartMs - t else if (t >= seg.songEndMs) t - seg.songEndMs else 0L
                }
            return (videoMs - s.offsetMs).coerceIn(s.songStartMs, if (s.songEndMs == Long.MAX_VALUE) Long.MAX_VALUE else s.songEndMs - 1)
                .coerceAtLeast(0)
        }
    }

    // ── Decoding ──

    /**
     * Decodes [file] to mono float PCM at 11025 Hz, resampling on the fly (box
     * low-pass + nearest sample) so a long clip never sits in memory at 48 kHz.
     */
    fun decode(file: File, maxSeconds: Int = 0): FloatArray {
        val ex = MediaExtractor()
        var decoderToRelease: MediaCodec? = null
        var threadToStop: android.os.HandlerThread? = null
        // Set on the codec's own thread when it is released: a callback still queued then
        // must not touch a buffer of the released codec.
        var closing = false
        try {
            ex.setDataSource(file.absolutePath)
            val track = (0 until ex.trackCount).first { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)!!.startsWith("audio/") }
            ex.selectTrack(track)
            val fmt = ex.getTrackFormat(track)
            val durUs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else 0L
            val out = Resampler(fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE), decodeCapacity(durUs, maxSeconds))
            val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!).also { decoderToRelease = it }
            val codecName = codec.name
            // Asynchronous mode: the codec pipelines its buffers instead of answering one
            // frame per round trip (20 ms Opus frames made the synchronous loop crawl).
            val thread = android.os.HandlerThread("mv-decode").also { threadToStop = it; it.start() }
            val done = java.util.concurrent.CountDownLatch(1)
            var failure: Throwable? = null
            var channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var isFloat = false
            var pcm = ShortArray(16384)
            var floatPcm = FloatArray(0)
            var inputDone = false
            val t0 = System.nanoTime()
            codec.setCallback(object : MediaCodec.Callback() {
                override fun onInputBufferAvailable(c: MediaCodec, index: Int) {
                    if (inputDone || closing) return
                    if (maxSeconds > 0 && out.size() >= maxSeconds * SR) {
                        // Enough audio for a first alignment: stop here.
                        inputDone = true
                        done.countDown()
                        return
                    }
                    try {
                        val n = ex.readSampleData(c.getInputBuffer(index)!!, 0)
                        if (n < 0) {
                            c.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            c.queueInputBuffer(index, 0, n, ex.sampleTime, 0)
                            ex.advance()
                        }
                    } catch (t: Throwable) { failure = t; done.countDown() }
                }
    
                override fun onOutputBufferAvailable(c: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                    if (closing) return
                    try {
                        val bb = c.getOutputBuffer(index)!!.order(ByteOrder.nativeOrder())
                        bb.position(info.offset).limit(info.offset + info.size)
                        if (isFloat) {
                            val count = info.size / 4
                            if (floatPcm.size < count) floatPcm = FloatArray(count)
                            bb.asFloatBuffer().get(floatPcm, 0, count)
                            out.pushInterleavedFloat(floatPcm, count, channels)
                        } else {
                            val count = info.size / 2
                            if (pcm.size < count) pcm = ShortArray(count)
                            bb.asShortBuffer().get(pcm, 0, count)
                            out.pushInterleaved(pcm, count, channels)
                        }
                        c.releaseOutputBuffer(index, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) done.countDown()
                    } catch (t: Throwable) { failure = t; done.countDown() }
                }
    
                override fun onOutputFormatChanged(c: MediaCodec, f: MediaFormat) {
                    out.setRate(f.getInteger(MediaFormat.KEY_SAMPLE_RATE))
                    channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    isFloat = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N &&
                        f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                        f.getInteger(MediaFormat.KEY_PCM_ENCODING) == android.media.AudioFormat.ENCODING_PCM_FLOAT
                }
    
                override fun onError(c: MediaCodec, e: MediaCodec.CodecException) { failure = e; done.countDown() }
            }, android.os.Handler(thread.looper))
            codec.configure(fmt, null, null, 0)
            codec.start()
            if (!done.await(60, java.util.concurrent.TimeUnit.SECONDS)) error("decode timeout")
            failure?.let { throw it }
            android.util.Log.i("MusicVideo", "decode $codecName: ${(System.nanoTime() - t0) / 1_000_000} ms")
            return out.result()
        } finally {
            // Released on the codec's own thread, after the callback running there: released from
            // here it unmapped the output buffer a callback was still copying (native crash,
            // SIGSEGV in memcpy under ByteBufferAsShortBuffer.get, measured on the test phone).
            val decoder = decoderToRelease
            val looper = threadToStop?.looper
            if (decoder != null && looper != null) {
                val released = java.util.concurrent.CountDownLatch(1)
                android.os.Handler(looper).post {
                    closing = true
                    runCatching { decoder.stop() }
                    runCatching { decoder.release() }
                    released.countDown()
                }
                released.await(5, java.util.concurrent.TimeUnit.SECONDS)
            } else if (decoder != null) {
                runCatching { decoder.stop() }
                runCatching { decoder.release() }
            }
            runCatching { ex.release() }
            threadToStop?.quitSafely()
        }
    }

    /** Initial PCM reservation only; output still grows if the container understates its duration. */
    internal fun decodeCapacity(durationUs: Long, maxSeconds: Int): Int {
        val ceiling = Int.MAX_VALUE - SR
        val durationSamples = if (durationUs > 0)
            (durationUs / 1_000_000.0 * SR).coerceAtMost(ceiling.toDouble()).toInt()
        else ceiling
        if (maxSeconds <= 0 && durationUs <= 0) return 1 shl 22
        val limitSamples = if (maxSeconds > 0)
            (maxSeconds.toLong() * SR).coerceAtMost(ceiling.toLong()).toInt()
        else ceiling
        return min(durationSamples, limitSamples) + SR
    }

    private class Resampler(rate: Int, capacity: Int) {
        private var a = FloatArray(capacity)
        private var n = 0
        private var step = 1.0
        private var k = 1
        private var ring = FloatArray(8)
        private var ringPos = 0
        private var sum = 0f
        private var inIndex = 0L
        private var nextOut = 0.0
        init { setRate(rate) }
        fun setRate(rate: Int) {
            step = rate.toDouble() / SR
            k = max(1, step.toInt())
            ring = FloatArray(k); ringPos = 0; sum = 0f
        }
        /** Interleaved 16-bit frames → mono → box low-pass → resampled, in one tight loop. */
        fun pushInterleaved(s: ShortArray, count: Int, channels: Int) {
            val scale = 1f / (channels * 32768f)
            var i = 0
            var rp = ringPos; var sm = sum; var idx = inIndex; var nx = nextOut
            val r = ring; val kk = k
            while (i + channels <= count) {
                var acc = 0
                for (c in 0 until channels) acc += s[i + c]
                val v = acc * scale
                sm += v - r[rp]; r[rp] = v
                rp++; if (rp == kk) rp = 0
                if (idx >= nx) {
                    if (n == a.size) a = a.copyOf(a.size + a.size / 2)
                    a[n++] = sm / kk
                    nx += step
                }
                idx++
                i += channels
            }
            ringPos = rp; sum = sm; inIndex = idx; nextOut = nx
        }

        fun pushInterleavedFloat(s: FloatArray, count: Int, channels: Int) {
            val scale = 1f / channels
            var i = 0
            while (i + channels <= count) {
                var acc = 0f
                for (c in 0 until channels) acc += s[i + c]
                push(acc * scale)
                i += channels
            }
        }

        fun push(v: Float) {
            sum += v - ring[ringPos]
            ring[ringPos] = v
            ringPos++; if (ringPos == k) ringPos = 0
            if (inIndex >= nextOut) {
                if (n == a.size) a = a.copyOf(a.size + a.size / 2)
                a[n++] = sum / k
                nextOut += step
            }
            inIndex++
        }
        fun result() = a.copyOf(n)
        fun size() = n
    }

    // ── FFT (in place, radix 2) ──

    private fun fft(re: DoubleArray, im: DoubleArray, inverse: Boolean) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var len = 2
        while (len <= n) {
            val ang = 2 * PI / len * (if (inverse) 1 else -1)
            val wr = cos(ang); val wi = sin(ang)
            var i = 0
            while (i < n) {
                var cr = 1.0; var ci = 0.0
                for (k in 0 until len / 2) {
                    val a = i + k; val b = a + len / 2
                    val xr = re[b] * cr - im[b] * ci
                    val xi = re[b] * ci + im[b] * cr
                    re[b] = re[a] - xr; im[b] = im[a] - xi
                    re[a] += xr; im[a] += xi
                    val nr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr; cr = nr
                }
                i += len
            }
            len = len shl 1
        }
        if (inverse) for (i in 0 until n) { re[i] /= n; im[i] /= n }
    }

    // ── Features ──

    /** [frames][BANDS] spectral flux, z-scored per band. */
    fun features(x: FloatArray): Array<DoubleArray> {
        val nf = if (x.size < N) 0 else 1 + (x.size - N) / HOP
        val win = DoubleArray(N) { 0.5 - 0.5 * cos(2 * PI * it / (N - 1)) }
        val edges = DoubleArray(BANDS + 1) { 80.0 * Math.pow(5000.0 / 80.0, it.toDouble() / BANDS) }
        val binOf = IntArray(N / 2 + 1) { b ->
            val f = b.toDouble() * SR / N
            var band = -1
            for (i in 0 until BANDS) if (f >= edges[i] && f < edges[i + 1]) { band = i; break }
            band
        }
        val out = Array(nf) { DoubleArray(BANDS) }
        val re = DoubleArray(N); val im = DoubleArray(N)
        val prev = DoubleArray(BANDS)
        for (fr in 0 until nf) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException("Audio feature extraction interrupted")
            val off = fr * HOP
            for (i in 0 until N) { re[i] = x[off + i] * win[i]; im[i] = 0.0 }
            fft(re, im, false)
            val e = DoubleArray(BANDS)
            for (b in 0..N / 2) { val band = binOf[b]; if (band >= 0) e[band] += re[b] * re[b] + im[b] * im[b] }
            for (k in 0 until BANDS) {
                val v = ln(1 + e[k] * 1e3)
                out[fr][k] = if (fr == 0) 0.0 else v - prev[k]
                prev[k] = v
            }
        }
        for (k in 0 until BANDS) {
            var m = 0.0; for (f in out) m += f[k]; m /= max(1, nf)
            var s = 0.0; for (f in out) s += (f[k] - m) * (f[k] - m); s = sqrt(s / max(1, nf)) + 1e-6
            for (f in out) f[k] = (f[k] - m) / s
        }
        return out
    }

    private data class Cand(val videoFrame: Int, val score: Double)

    /** Aligns the song's audio with the video's audio. */
    suspend fun align(song: Array<DoubleArray>, video: Array<DoubleArray>): TimeMap = coroutineScope {
        currentCoroutineContext().ensureActive()
        val w = (6.0 / FRAME_S).toInt()
        val step = (3.0 / FRAME_S).toInt()
        val m = video.size
        if (song.size < w || m < w) return@coroutineScope TimeMap(emptyList(), 0.0)
        var nfft = 1; while (nfft < m + w) nfft = nfft shl 1
        // Spectra of each video band, once.
        val refRe = Array(BANDS) { DoubleArray(nfft) }
        val refIm = Array(BANDS) { DoubleArray(nfft) }
        (0 until BANDS).map { b ->
            async(Dispatchers.Default) {
                currentCoroutineContext().ensureActive()
                for (i in 0 until m) refRe[b][i] = video[i][b]
                fft(refRe[b], refIm[b], false)
            }
        }.awaitAll()
        // Energy of every video window, for the normalisation.
        val cum = DoubleArray(m + 1)
        for (i in 0 until m) { var e = 0.0; for (b in 0 until BANDS) e += video[i][b] * video[i][b]; cum[i + 1] = cum[i] + e }
        val starts = (0..(song.size - w) step step).toList()
        val cands = starts.map { st ->
            async(Dispatchers.Default) {
                currentCoroutineContext().ensureActive()
                // Window, z-scored as a whole.
                var mean = 0.0
                for (i in 0 until w) for (b in 0 until BANDS) mean += song[st + i][b]
                mean /= (w * BANDS)
                var ss = 0.0
                val wz = Array(w) { i -> DoubleArray(BANDS) { b -> (song[st + i][b] - mean).also { ss += it * it } } }
                val wn = sqrt(ss) + 1e-6
                val accRe = DoubleArray(nfft); val accIm = DoubleArray(nfft)
                val re = DoubleArray(nfft); val im = DoubleArray(nfft)
                for (b in 0 until BANDS) {
                    currentCoroutineContext().ensureActive()
                    java.util.Arrays.fill(re, 0.0); java.util.Arrays.fill(im, 0.0)
                    for (i in 0 until w) re[i] = wz[i][b]
                    fft(re, im, false)
                    val rr = refRe[b]; val ri = refIm[b]
                    for (k in 0 until nfft) {
                        // ref * conj(win)
                        accRe[k] += rr[k] * re[k] + ri[k] * im[k]
                        accIm[k] += ri[k] * re[k] - rr[k] * im[k]
                    }
                }
                fft(accRe, accIm, true)
                val n = m - w + 1
                val score = DoubleArray(n) { i -> accRe[i] / ((sqrt(max(0.0, cum[i + w] - cum[i])) + 1e-6) * wn) }
                // Top 5 peaks, at least 1 s apart.
                val picked = ArrayList<Cand>()
                val order = (0 until n).sortedByDescending { score[it] }
                for (i in order) {
                    if (picked.size == 5) break
                    if (picked.all { abs(it.videoFrame - i) > (1.0 / FRAME_S) }) picked.add(Cand(i, score[i]))
                }
                picked
            }
        }.awaitAll()
        // Dynamic programming over the candidates: maximise the matched score, the
        // video only moves forward, offset changes cost 0.6, and up to 3 ambiguous
        // windows (repeated choruses…) may be skipped at 0.35 each.
        val tol = (0.08 / FRAME_S).toInt()
        val skip = 4
        val n = cands.size
        val best = Array(n) { DoubleArray(cands[it].size) }
        val fromI = Array(n) { IntArray(cands[it].size) { -1 } }
        val fromK = Array(n) { IntArray(cands[it].size) { -1 } }
        for (i in 0 until n) {
            for ((k, c) in cands[i].withIndex()) {
                val off = c.videoFrame - starts[i]
                var bv = c.score - (if (i == 0) 0.0 else 3.0)
                var bi = -1; var bk = -1
                for (j in max(0, i - skip) until i) {
                    for ((pk, p) in cands[j].withIndex()) {
                        if (c.videoFrame <= p.videoFrame) continue
                        val poff = p.videoFrame - starts[j]
                        val v = best[j][pk] + c.score - (if (abs(off - poff) <= tol) 0.0 else 0.6) - 0.35 * (i - j - 1)
                        if (v > bv) { bv = v; bi = j; bk = pk }
                    }
                }
                best[i][k] = bv; fromI[i][k] = bi; fromK[i][k] = bk
            }
        }
        var ei = -1; var ek = -1; var ev = Double.NEGATIVE_INFINITY
        for (i in max(0, n - skip) until n) for (k in cands[i].indices) {
            val v = best[i][k] - 0.35 * (n - 1 - i)
            if (v > ev) { ev = v; ei = i; ek = k }
        }
        val chosenIdx = ArrayList<Int>(); val chosen = ArrayList<Cand>()
        while (ei >= 0) {
            chosenIdx.add(ei); chosen.add(cands[ei][ek])
            val pi = fromI[ei][ek]; val pk = fromK[ei][ek]
            ei = pi; ek = pk
        }
        chosenIdx.reverse(); chosen.reverse()
        val avg = if (chosen.isEmpty()) 0.0 else chosen.map { it.score }.average() * chosen.size / n
        // Segments of equal offset.
        val segs = ArrayList<Segment>()
        fun offsetOf(t: Int) = chosen[t].videoFrame - starts[chosenIdx[t]]
        var segStart = 0
        for (t in 1..chosen.size) {
            if (chosen.isEmpty()) break
            if (t == chosen.size || abs(offsetOf(t) - offsetOf(segStart)) > tol) {
                val offs = (segStart until t).map { offsetOf(it) }.sorted()
                val off = offs[offs.size / 2]
                val startMs = if (segStart == 0) 0L
                    else (((starts[chosenIdx[segStart - 1]] + w + starts[chosenIdx[segStart]]) / 2.0) * FRAME_S * 1000).toLong()
                val endMs = if (t == chosen.size) Long.MAX_VALUE
                    else (((starts[chosenIdx[t - 1]] + w + starts[chosenIdx[t]]) / 2.0) * FRAME_S * 1000).toLong()
                segs.add(Segment(startMs, endMs, (off * FRAME_S * 1000).toLong()))
                segStart = t
            }
        }
        TimeMap(segs, avg)
    }
}
