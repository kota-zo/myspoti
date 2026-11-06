/* SPDX-License-Identifier: GPL-3.0-only */
package app.myspoti.automix

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * A constant-tempo beat grid over part of a song: beat k is at [first] + k × [period] seconds.
 * [steadiness] is the share of beats whose onset peak lies within 5 % of a period (and 35 ms) of
 * the grid; [driftMs] the largest gap between the grid and the onsets, averaged over 8 beats;
 * [contrast] the onset level on the beats over that half a beat off them.
 */
data class Grid(
    val first: Double,
    val period: Double,
    val count: Int,
    val steadiness: Double,
    val driftMs: Double,
    val contrast: Double,
) {
    val bpm get() = 60.0 / period
    fun beat(k: Int) = first + k * period
    val last get() = beat(count - 1)
    /** Fractional beat index of time [t] (seconds). */
    fun index(t: Double) = (t - first) / period
    /** Steady enough to beat-match: most beats on the grid, no drift, clear onsets. */
    val reliable get() = count >= 16 && steadiness >= 0.6 && driftMs <= 30
}

/** Tempo and beats from an onset envelope at [Features.FPS] frames per second. */
object BeatTracker {
    private const val FPS = Features.FPS
    const val MIN_BPM = 55.0
    const val MAX_BPM = 210.0

    /** The onset envelope used for beats: both fluxes, local mean removed, unit variance. */
    fun envelope(f: Features): FloatArray {
        val n = f.frames
        val raw = FloatArray(n) { f.onset[it] + 1.5f * f.bassOnset[it] }
        val half = 24
        val out = FloatArray(n)
        // Running mean over ±half frames, subtracted (onsets stand out of a loud passage too).
        var sum = 0.0
        var lo = 0; var hi = -1
        for (i in 0 until n) {
            while (hi < min(n - 1, i + half)) { hi++; sum += raw[hi] }
            while (lo < i - half) { sum -= raw[lo]; lo++ }
            out[i] = max(0f, raw[i] - (sum / (hi - lo + 1)).toFloat())
        }
        var m = 0.0; for (v in out) m += v * v
        val sd = sqrt(m / n).toFloat()
        if (sd > 0) for (i in 0 until n) out[i] /= sd
        return out
    }

    /**
     * Beat period in frames over [from, to): the autocorrelation peak, weighted by a log-normal tempo
     * prior around [preferBpm] and by the bar-level (2 × lag) correlation.
     */
    fun period(env: FloatArray, from: Int = 0, to: Int = env.size, preferBpm: Double = 100.0): Double {
        val minLag = floor(FPS * 60 / MAX_BPM).toInt()
        val maxLag = min((FPS * 60 / MIN_BPM).toInt() + 1, (to - from) / 6)
        if (maxLag <= minLag + 2) return FPS * 60 / 120
        // Autocorrelation of the envelope less its mean (else every lag shares a large constant).
        var mean = 0.0
        for (i in from until to) mean += env[i]
        mean /= (to - from)
        val e = DoubleArray(to - from) { env[from + it] - mean }
        val acf = DoubleArray(4 * maxLag + 2)
        for (lag in 0 until acf.size) {
            var s = 0.0
            for (i in 0 until e.size - lag) s += e[i] * e[i + lag]
            acf[lag] = s / (e.size - lag).coerceAtLeast(1)
        }
        fun at(l: Double): Double { val i = l.toInt(); val f = l - i; return if (i + 1 >= acf.size) 0.0 else acf[i] * (1 - f) + acf[i + 1] * f }
        // Peak around a lag (onsets jitter by a frame or so).
        fun peak(l: Double): Double = maxOf(at(l - 1), at(l), at(l + 1))
        var best = -1; var bestScore = Double.NEGATIVE_INFINITY
        for (lag in minLag..maxLag) {
            if (!(acf[lag] >= acf[lag - 1] && acf[lag] >= acf[lag + 1])) continue
            val bpm = FPS * 60 / lag
            val prior = exp(-0.5 * (ln(bpm / preferBpm) / ln(2.0) / 1.0).let { it * it })
            // A beat period recurs at 2 and 4 beats (half bar, bar); a triplet or dotted period
            // (3/4 or 4/3 of the beat) does not.
            val score = prior * (acf[lag] + peak(2.0 * lag) + peak(4.0 * lag))
            if (score > bestScore) { bestScore = score; best = lag }
        }
        if (best < 0) return FPS * 60 / 120
        // Parabolic interpolation of the peak.
        val a = acf[best - 1]; val b = acf[best]; val c = acf[best + 1]
        val d = (a - 2 * b + c).let { if (it == 0.0) 0.0 else 0.5 * (a - c) / it }
        return best + d.coerceIn(-0.5, 0.5)
    }

    /**
     * The constant grid that best fits the onsets of [from, to) frames, period near [period0]:
     * a coarse search of period and phase, then a weighted least-squares fit on the onset peaks
     * found near each grid beat, outliers dropped.
     */
    fun fit(env: FloatArray, from: Int, to: Int, period0: Double): Grid? {
        if (to - from < period0 * 8) return null
        val s = smooth(env, 1.5)
        // Coarse: period within ±3 %, phase in half frames.
        var bestP = period0; var bestPhase = 0.0; var bestScore = Double.NEGATIVE_INFINITY
        var p = period0 * 0.97
        while (p <= period0 * 1.03) {
            var ph = 0.0
            while (ph < p) {
                var sum = 0.0; var cnt = 0
                var t = from + ph
                while (t < to - 1) { sum += interp(s, t); cnt++; t += p }
                val score = sum / max(1, cnt)
                if (score > bestScore) { bestScore = score; bestP = p; bestPhase = from + ph }
                ph += 0.5
            }
            p += period0 * 0.0005
        }
        // Fine: least squares on the peaks next to each beat.
        var first = bestPhase; var per = bestP
        var kept = 0; var steady = 0.0; var drift = 0.0
        val count = floor((to - 1 - first) / per).toInt() + 1
        if (count < 8) return null
        val peakT = DoubleArray(count); val peakW = DoubleArray(count)
        repeat(3) { pass ->
            val win = per * (if (pass == 0) 0.15 else 0.1)
            for (k in 0 until count) {
                val c = first + k * per
                val lo = max(from, floor(c - win).toInt()); val hi = min(to - 2, (c + win).toInt() + 1)
                var at = -1; var v = 0f
                for (i in lo..hi) if (s[i] > v && s[i] >= s.getOrElse(i - 1) { 0f } && s[i] >= s.getOrElse(i + 1) { 0f }) { v = s[i]; at = i }
                if (at < 0 || v <= 0f) { peakW[k] = 0.0; continue }
                val y0 = s.getOrElse(at - 1) { v }; val y2 = s.getOrElse(at + 1) { v }
                val den = y0 - 2 * v + y2
                peakT[k] = at + if (den != 0f) (0.5 * (y0 - y2) / den).coerceIn(-0.5, 0.5) else 0.0
                peakW[k] = v.toDouble()
            }
            // Robust: weights cut for residuals over 6 % of a period.
            var sw = 0.0; var sk = 0.0; var st = 0.0; var skk = 0.0; var skt = 0.0
            for (k in 0 until count) {
                if (peakW[k] <= 0) continue
                val r = peakT[k] - (first + k * per)
                if (pass > 0 && abs(r) > per * 0.06) continue
                val w = peakW[k]
                sw += w; sk += w * k; st += w * peakT[k]; skk += w * k * k; skt += w * k * peakT[k]
            }
            val den = sw * skk - sk * sk
            if (sw > 0 && den > 1e-9) {
                val newPer = (sw * skt - sk * st) / den
                if (newPer in period0 * 0.95..period0 * 1.05) { per = newPer; first = (st - per * sk) / sw }
            }
        }
        // Metrics.
        val tol = min(per * 0.05, 0.035 * FPS)
        val residual = DoubleArray(count)
        var weightAll = 0.0; var weightOn = 0.0
        for (k in 0 until count) {
            residual[k] = if (peakW[k] > 0) peakT[k] - (first + k * per) else Double.NaN
            val w = max(peakW[k], 0.2)
            weightAll += w
            if (peakW[k] > 0 && abs(residual[k]) <= tol) { weightOn += w; kept++ }
        }
        steady = weightOn / weightAll
        // Drift: weighted mean residual over 8 consecutive beats, largest absolute value.
        for (k in 0..count - 8) {
            var sw = 0.0; var sr = 0.0
            for (j in k until k + 8) if (!residual[j].isNaN() && abs(residual[j]) < per * 0.2) { sw += peakW[j]; sr += peakW[j] * residual[j] }
            if (sw > 0) drift = max(drift, abs(sr / sw))
        }
        // Contrast: onset level on the beats over the level half a beat off them.
        var on = 0.0; var off = 0.0
        for (k in 0 until count - 1) { on += interp(s, first + k * per); off += interp(s, first + (k + 0.5) * per) }
        val contrast = if (off > 0) on / off else 9.0
        while (first - per >= from) first -= per
        val n = floor((to - 1 - first) / per).toInt() + 1
        return Grid(first / FPS, per / FPS, n, steady, drift / FPS * 1000, contrast)
    }

    /** How well [grid] holds over [fromSec, toSec): its steadiness and drift there, its constant offset to the onsets taken out. */
    class Quality(val steadiness: Double, val driftMs: Double, val beats: Int) {
        val good get() = beats >= 16 && steadiness >= 0.7 && driftMs <= 40
        override fun toString() = String.format("%.2f/%.0fms/%d", steadiness, driftMs, beats)
    }

    fun quality(env: FloatArray, grid: Grid, fromSec: Double, toSec: Double): Quality {
        val s = smooth(env, 1.5)
        val per = grid.period * FPS
        val k0 = Math.ceil(grid.index(fromSec)).toInt().coerceAtLeast(0)
        val k1 = floor(grid.index(toSec)).toInt().coerceAtMost(grid.count - 1)
        if (k1 - k0 < 8) return Quality(0.0, 999.0, max(0, k1 - k0 + 1))
        val count = k1 - k0 + 1
        val res = DoubleArray(count) { Double.NaN }; val w = DoubleArray(count)
        val win = per * 0.12
        for (i in 0 until count) {
            val c = grid.beat(k0 + i) * FPS
            val lo = max(0, floor(c - win).toInt()); val hi = min(s.size - 2, (c + win).toInt() + 1)
            var at = -1; var v = 0f
            for (j in lo..hi) if (s[j] > v && s[j] >= s.getOrElse(j - 1) { 0f } && s[j] >= s.getOrElse(j + 1) { 0f }) { v = s[j]; at = j }
            if (at < 0) continue
            res[i] = at - c; w[i] = v.toDouble()
        }
        val valid = (0 until count).filter { !res[it].isNaN() }
        if (valid.size < 8) return Quality(0.0, 999.0, count)
        val offset = valid.map { res[it] }.sorted()[valid.size / 2]
        for (i in valid) res[i] -= offset
        val tol = min(per * 0.05, 0.035 * FPS)
        var all = 0.0; var on = 0.0
        for (i in 0 until count) { val wi = max(w[i], 0.2); all += wi; if (!res[i].isNaN() && abs(res[i]) <= tol) on += wi }
        var drift = 0.0
        for (k in 0..count - 8) {
            var sw = 0.0; var sr = 0.0
            var m = 0
            for (j in k until k + 8) if (!res[j].isNaN() && abs(res[j]) < per * 0.08) { sw += w[j]; sr += w[j] * res[j]; m++ }
            if (m >= 4) drift = max(drift, abs(sr / sw))
        }
        return Quality(on / all, drift / FPS * 1000, count)
    }

    /**
     * Dynamic-programming beat tracker (Ellis 2007) for songs whose tempo moves: each beat is the
     * best previous beat about one [period] earlier plus this frame's onset.
     */
    fun track(env: FloatArray, period: Double, tightness: Double = 120.0): DoubleArray {
        val n = env.size
        if (n < period * 4) return DoubleArray(0)
        val s = smooth(env, 1.5)
        val score = DoubleArray(n); val back = IntArray(n) { -1 }
        val lo = (period * 0.5).roundToInt(); val hi = (period * 2).roundToInt()
        for (i in 0 until n) {
            var best = 0.0; var arg = -1
            for (d in lo..hi) {
                val j = i - d
                if (j < 0) break
                val pen = ln(d / period).let { -tightness * it * it }
                val v = score[j] + pen
                if (arg < 0 || v > best) { best = v; arg = j }
            }
            score[i] = s[i] + if (arg >= 0) max(best, 0.0) else 0.0
            back[i] = if (arg >= 0 && best > 0) arg else -1
        }
        // Best end in the last period, followed back.
        var end = n - 1; var bestEnd = Double.NEGATIVE_INFINITY
        for (i in max(0, n - hi) until n) if (score[i] > bestEnd) { bestEnd = score[i]; end = i }
        val beats = ArrayList<Double>()
        var i = end
        while (i >= 0) { beats.add(i / FPS); i = back[i] }
        beats.reverse()
        return beats.toDoubleArray()
    }

    internal fun smooth(x: FloatArray, sigma: Double): FloatArray {
        val r = (sigma * 3).toInt()
        val k = DoubleArray(2 * r + 1) { exp(-0.5 * ((it - r) / sigma).let { v -> v * v }) }
        val ks = k.sum()
        return FloatArray(x.size) { i ->
            var s = 0.0
            for (j in -r..r) s += k[j + r] * x.getOrElse(i + j) { 0f }
            (s / ks).toFloat()
        }
    }

    internal fun interp(x: FloatArray, t: Double): Double {
        if (t <= 0) return x.firstOrNull()?.toDouble() ?: 0.0
        val i = t.toInt()
        if (i >= x.size - 1) return x.lastOrNull()?.toDouble() ?: 0.0
        val f = t - i
        return x[i] * (1 - f) + x[i + 1] * f
    }
}
