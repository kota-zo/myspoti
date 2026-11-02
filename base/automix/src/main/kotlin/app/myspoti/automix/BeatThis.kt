/* SPDX-License-Identifier: GPL-3.0-only */
package app.myspoti.automix

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * Beat This! small0 (CPJKU, MIT code and weights), int8 ONNX, run through ONNX Runtime: beat and
 * downbeat logits at [FPS] frames per second. The phone (onnxruntime-android) and the desktop bench
 * (onnxruntime) share this code. It decides the metrical level and the downbeats; the onset grid of
 * [BeatTracker] gives the precise beat times.
 */
class BeatThis(model: ByteArray, private val defaultThreads: Int = 4) : AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()
    // One thread per run: the song's chunks run side by side instead (a run uses a core badly on its own).
    private val session = OrtSession.SessionOptions().use { o ->
        o.setIntraOpNumThreads(1)
        o.setInterOpNumThreads(1)
        o.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        env.createSession(model, o)
    }

    /** Beat and downbeat logits of mono [RATE]-Hz samples [from, to). */
    class Activations(val start: Double, val beat: FloatArray, val downbeat: FloatArray) {
        fun time(frame: Double) = start + frame / FPS
    }

    fun run(mono: FloatArray, from: Int, to: Int, cancelled: () -> Boolean = { false }, threads: Int = defaultThreads): Activations {
        val mel = MelFrontend.spectrogram(mono, from, to, threads)
        val n = mel.size
        val beat = FloatArray(n); val down = FloatArray(n)
        // Chunks of 768 frames that overlap by 12; each keeps all but its 6 edge frames.
        val starts = ArrayList<Int>()
        var start = -EDGE
        while (true) {
            starts.add(start)
            val frames = max(2 * EDGE + 1, min(CHUNK, n + EDGE - start))
            if (start + frames - EDGE >= n) break
            start += CHUNK - 2 * EDGE
        }
        parallel(starts.size, threads) { c ->
            if (cancelled()) throw InterruptedException("analysis cancelled")
            val st = starts[c]
            val frames = max(2 * EDGE + 1, min(CHUNK, n + EDGE - st))
            val flat = FloatArray(frames * MelFrontend.MELS)
            for (i in 0 until frames) if (st + i in 0 until n) mel[st + i].copyInto(flat, i * MelFrontend.MELS)
            OnnxTensor.createTensor(env, FloatBuffer.wrap(flat), longArrayOf(1, frames.toLong(), MelFrontend.MELS.toLong())).use { input ->
                session.run(mapOf("spect" to input)).use { out ->
                    val b = (out.get("beat").get() as OnnxTensor).floatBuffer
                    val d = (out.get("downbeat").get() as OnnxTensor).floatBuffer
                    // Each chunk writes its own frames (the kept ranges do not overlap).
                    for (i in EDGE until frames - EDGE) {
                        val at = st + i
                        if (at in 0 until n) { beat[at] = b.get(i); down[at] = d.get(i) }
                    }
                }
            }
        }
        return Activations(from.toDouble() / RATE, beat, down)
    }

    override fun close() = session.close()

    companion object {
        const val RATE = MelFrontend.RATE
        const val FPS = MelFrontend.FPS
        private const val CHUNK = 768
        private const val EDGE = 6

        /** Peaks of [logits] over 0 that are the largest within ±3 frames, in seconds, parabolically refined. */
        fun peaks(a: Activations, logits: FloatArray): List<Pair<Double, Float>> {
            val out = ArrayList<Pair<Double, Float>>()
            for (i in logits.indices) {
                val v = logits[i]
                if (v <= 0f) continue
                var top = true
                for (j in max(0, i - 3)..min(logits.size - 1, i + 3)) if (logits[j] > v || (logits[j] == v && j < i)) { top = false; break }
                if (!top) continue
                val y0 = logits.getOrElse(i - 1) { v }; val y2 = logits.getOrElse(i + 1) { v }
                val den = y0 - 2 * v + y2
                val d = if (den != 0f) (0.5 * (y0 - y2) / den).coerceIn(-0.5, 0.5) else 0.0
                out.add(a.time(i + d) to v)
            }
            return out
        }
    }
}

/**
 * Beat This input: Slaney-style 128-band magnitude log-mel of mono 22.05-kHz audio, 1024-point
 * FFT, hop 441 (50 frames per second), centred frames with reflected edges, log(1 + 1000 x).
 * Matches the upstream torchaudio front end (checked to 1e-4 in the earlier AutoMix work).
 */
object MelFrontend {
    const val RATE = 22050
    const val FFT_N = 1024
    const val HOP = 441
    const val MELS = 128
    const val FPS = RATE.toDouble() / HOP

    private val filters = Array(MELS) { DoubleArray(FFT_N / 2 + 1) }
    private val first = IntArray(MELS); private val last = IntArray(MELS)
    private val window = hann(FFT_N)

    init {
        val lo = mel(30.0); val hi = mel(11000.0)
        val edges = DoubleArray(MELS + 2) { hz(lo + (hi - lo) * it / (MELS + 1)) }
        for (m in 0 until MELS) {
            first[m] = FFT_N / 2 + 1
            for (k in 0..FFT_N / 2) {
                val f = k * RATE.toDouble() / FFT_N
                filters[m][k] = max(0.0, min((f - edges[m]) / (edges[m + 1] - edges[m]), (edges[m + 2] - f) / (edges[m + 2] - edges[m + 1])))
                if (filters[m][k] > 0) { first[m] = min(first[m], k); last[m] = k + 1 }
            }
        }
    }

    private fun mel(f: Double) = if (f < 1000) f / (200.0 / 3) else 15 + ln(f / 1000) / (ln(6.4) / 27)
    private fun hz(m: Double) = if (m < 15) m * (200.0 / 3) else 1000 * Math.exp((m - 15) * (ln(6.4) / 27))

    fun spectrogram(mono: FloatArray, from: Int, to: Int, threads: Int = 1): Array<FloatArray> {
        val n = to - from
        require(n >= FFT_N) { "audio window too short" }
        val frames = 1 + n / HOP
        val out = Array(frames) { FloatArray(MELS) }
        val parts = max(1, threads)
        parallel(parts, parts) { part -> slice(mono, from, n, out, frames * part / parts, frames * (part + 1) / parts) }
        return out
    }

    private fun slice(mono: FloatArray, from: Int, n: Int, out: Array<FloatArray>, t0: Int, t1: Int) {
        val fft = Fft(FFT_N)
        val re = DoubleArray(FFT_N); val im = DoubleArray(FFT_N); val mag = DoubleArray(FFT_N / 2 + 1)
        for (t in t0 until t1) {
            for (i in 0 until FFT_N) {
                var at = t * HOP + i - FFT_N / 2
                while (at < 0 || at >= n) at = if (at < 0) -at else 2 * n - 2 - at
                re[i] = mono[from + at] * window[i]; im[i] = 0.0
            }
            fft.transform(re, im)
            for (k in 0..FFT_N / 2) mag[k] = Math.hypot(re[k], im[k]) / 32
            for (m in 0 until MELS) {
                var s = 0.0
                for (k in first[m] until last[m]) s += mag[k] * filters[m][k]
                out[t][m] = Math.log1p(1000 * s).toFloat()
            }
        }
    }
}

/** Runs [task] for 0 until [count] on up to [threads] threads; the first failure is thrown. */
internal fun parallel(count: Int, threads: Int, task: (Int) -> Unit) {
    if (threads <= 1 || count <= 1) { for (i in 0 until count) task(i); return }
    val next = java.util.concurrent.atomic.AtomicInteger()
    val error = java.util.concurrent.atomic.AtomicReference<Throwable?>()
    val workers = List(min(threads, count) - 1) {
        Thread {
            while (error.get() == null) { val i = next.getAndIncrement(); if (i >= count) break; try { task(i) } catch (t: Throwable) { error.compareAndSet(null, t) } }
        }.apply { priority = Thread.currentThread().priority; isDaemon = true; start() }
    }
    while (error.get() == null) { val i = next.getAndIncrement(); if (i >= count) break; try { task(i) } catch (t: Throwable) { error.compareAndSet(null, t) } }
    workers.forEach { it.join() }
    error.get()?.let { throw it }
}
