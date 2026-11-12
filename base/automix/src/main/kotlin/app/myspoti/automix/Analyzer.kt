/* SPDX-License-Identifier: GPL-3.0-only */
package app.myspoti.automix

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Song analysis: beats, downbeats, bars, phrases, key, loudness and the audible bounds. */
object Analyzer {
    /** Last global grid fitted (bench diagnostics only). */
    @Volatile var lastGlobal: Grid? = null
    @Volatile var lastModelBeats: DoubleArray = DoubleArray(0)
    @Volatile var lastWhole: BeatTracker.Quality? = null

    private const val FPS = Features.FPS
    /** Seconds of the song's start and end the head and tail grids are fitted on. */
    /** How near a bar start the model's downbeat must be to speak for it [I]. */
    private const val DOWN_NEAR_SEC = 0.07
    const val HEAD_SEC = 75.0
    const val TAIL_SEC = 90.0

    /**
     * [mid] and [side] (or null) at [Features.RATE] Hz. With [model], Beat This reads the whole song
     * for the metrical level and the downbeats; without it (or when it finds no
     * beats) the onsets alone decide. [length]: the samples of the signals that are the song
     * (see [Features.of]).
     */
    fun analyze(mid: FloatArray, side: FloatArray?, model: BeatThis? = null, cancelled: () -> Boolean = { false }, threads: Int = 1, length: Int = mid.size): TrackAnalysis {
        // The onset features and Beat This side by side (the model reads the whole file; its beats
        // outside the audible part are dropped below).
        var feats: Features? = null
        var act: BeatThis.Activations? = null
        val budget = max(1, threads)
        val split = max(1, budget / 2)
        parallel(if (model != null) 2 else 1, if (model != null && budget > 1) 2 else 1) { task ->
            if (task == 0) feats = Features.of(mid, side, cancelled, if (model != null) split else budget, length)
            else act = model!!.run(mid, 0, length, cancelled, max(1, budget - split))
        }
        if (cancelled()) throw InterruptedException("analysis cancelled")
        val f = feats!!
        val n = f.frames
        val env = BeatTracker.envelope(f)
        val level = BeatTracker.smooth(f.rmsDb, 4.0)
        val peak = percentile(level, 0.95)
        val gate = max(peak - 45f, -60f)
        val startF = (0 until n).firstOrNull { level[it] > gate } ?: 0
        val endF = (n - 1 downTo 0).firstOrNull { level[it] > gate } ?: (n - 1)
        val contentStart = startF / FPS
        val contentEnd = min(f.durationSec, (endF + 1) / FPS)

        // Beat This: metrical level, downbeats, and the beats of a song whose tempo moves.
        val inside = { t: Double -> t >= contentStart - 0.1 && t <= contentEnd + 0.1 }
        val modelBeats = act?.let { a -> BeatThis.peaks(a, a.beat).filter { inside(it.first) } }.orEmpty()
        val modelDowns = act?.let { a -> BeatThis.peaks(a, a.downbeat).filter { inside(it.first) } }.orEmpty()
        val mb = modelBeats.map { it.first }.toDoubleArray()
        val modelPeriod = modelPeriod(mb)

        val p0 = modelPeriod?.times(FPS) ?: BeatTracker.period(env, startF, endF + 1)
        val global = BeatTracker.fit(env, startF, endF + 1, p0)?.let { onModel(it, mb) }
        lastGlobal = global; lastModelBeats = mb
        // The global grid where it holds near the start and near the end, else a grid of its own there.
        val headSpan = contentStart to min(contentEnd, contentStart + HEAD_SEC)
        val tailSpan = max(contentStart, contentEnd - TAIL_SEC) to contentEnd
        fun holds(g: Grid, span: Pair<Double, Double>): Boolean {
            val q = BeatTracker.quality(env, g, span.first, span.second)
            return q.beats >= 16 && q.driftMs <= 40 && (q.steadiness >= 0.8 || (q.steadiness >= 0.6 && agreement(g, mb, span) >= 0.75))
        }
        // A grid of its own there: on the onsets, else on the model's beats alone (sparse or uneven
        // onsets, as in much trap, where the model still hears a steady beat).
        val why = StringBuilder()
        /**
         * The longest span from the song's start or end (60–90 s, then 45, then 25: a mix only needs
         * that much) where a grid holds: the global one, else one fitted on the onsets there; only
         * then one on the model's beats alone (it can be wrong where the onsets are right).
         */
        fun near(start: Boolean): Grid? {
            val spans = doubleArrayOf(if (start) HEAD_SEC else TAIL_SEC, 45.0, 25.0).map { len ->
                if (start) contentStart to min(contentEnd, contentStart + len) else max(contentStart, contentEnd - len) to contentEnd
            }
            val notes = StringBuilder()
            for (span in spans) {
                if (global != null && holds(global, span)) return global
                val g = BeatTracker.fit(env, (span.first * FPS).toInt(), min(n, (span.second * FPS).toInt()), global?.period?.times(FPS) ?: p0)?.let { onModel(it, mb) }
                if (g != null && holds(g, span)) return g
                notes.append(String.format("%.0f s: onsets %s; ", span.second - span.first, g?.let { BeatTracker.quality(env, it, span.first, span.second).toString() } ?: "no fit"))
            }
            for (span in spans) {
                val (fb, reason) = fromBeats(mb, span, global?.period ?: (p0 / FPS))
                if (fb != null) return fb
                notes.append(String.format("%.0f s: model %s; ", span.second - span.first, reason))
            }
            why.append(if (start) "start: " else "end: ").append(notes)
            return null
        }
        // One grid for the whole song (made on a fixed tempo, as most are): it holds in quiet intros too.
        val whole = global?.let { BeatTracker.quality(env, it, contentStart, contentEnd) }
        lastWhole = whole
        val steady = global != null && whole != null && whole.driftMs <= 40 &&
            (whole.steadiness >= 0.75 || (whole.steadiness >= 0.6 && agreement(global, mb, contentStart to contentEnd) >= 0.75))
        val head = if (steady) global else near(start = true)
        val tail = if (steady) global else near(start = false)
        val period = global?.period?.times(FPS) ?: p0
        val beats = (when {
            steady -> DoubleArray(global!!.count) { global.beat(it) }
            mb.size >= 16 -> mb
            else -> BeatTracker.track(env, period).map { it + ONSET_LEAD }.toDoubleArray()
        }).filter { it >= contentStart - 0.05 && it < contentEnd }.toDoubleArray()
        val bpm = global?.bpm ?: (FPS * 60 / p0)

        // Downbeats: Beat This votes near the start and near the end; the onsets otherwise.
        val headPhase = votePhase(beats, modelDowns.filter { it.first < contentStart + HEAD_SEC })
        val tailPhase = votePhase(beats, modelDowns.filter { it.first > contentEnd - TAIL_SEC })
        val dspPhase = if (headPhase == null || tailPhase == null) downbeatPhase(beats, f, env) else 0
        val switchAt = (contentStart + contentEnd) / 2
        val starts = ArrayList<Double>()
        for (k in beats.indices) {
            val ph = (if (beats[k] < switchAt) headPhase else tailPhase) ?: dspPhase
            if (Math.floorMod(k - ph, 4) == 0) starts.add(beats[k])
        }
        val barStarts = starts.toDoubleArray()
        val bars = barStarts.size
        val barDb = FloatArray(bars); val barLow = FloatArray(bars); val barHigh = FloatArray(bars); val barMid = FloatArray(bars)
        val barOnset = FloatArray(bars); val barCentre = FloatArray(bars); val absoluteCentre = FloatArray(bars); val barBass = FloatArray(bars)
        val barChroma = Array(bars) { FloatArray(12) }
        val centreMedian = if (f.stereo) percentile(f.centre, 0.5) else 0f
        for (i in 0 until bars) {
            val a = barStarts[i]
            val b = if (i + 1 < bars) barStarts[i + 1] else min(contentEnd, a + 4 * 60 / bpm)
            val fa = (a * FPS).toInt().coerceIn(0, n - 1); val fb = (b * FPS).toInt().coerceIn(fa + 1, n)
            barDb[i] = powerMean(f.rmsDb, fa, fb); barLow[i] = powerMean(f.lowDb, fa, fb)
            barHigh[i] = powerMean(f.highDb, fa, fb); barMid[i] = powerMean(f.midDb, fa, fb); barBass[i] = powerMean(f.bassDb, fa, fb)
            var o = 0.0; for (j in fa until fb) o += env[j]; barOnset[i] = (o / (fb - fa)).toFloat()
            val ca = (a * Features.CHROMA_FPS).toInt().coerceIn(0, f.chroma.size - 1)
            val cb = (b * Features.CHROMA_FPS).toInt().coerceIn(ca + 1, f.chroma.size)
            var c = 0.0
            for (j in ca until cb) { for (p in 0 until 12) barChroma[i][p] += f.chroma[j][p]; c += f.centre[j] }
            for (p in 0 until 12) barChroma[i][p] /= (cb - ca)
            absoluteCentre[i] = if (f.stereo) (c / (cb - ca)).toFloat() else 0f
            barCentre[i] = if (f.stereo) absoluteCentre[i] - centreMedian else 0f
        }
        // A downbeat on the head and on the tail grids: the model's downbeats voted on grid beats, else
        // the bar start nearest to the grid.
        fun downOn(g: Grid?, span: Pair<Double, Double>): Double {
            if (g == null) return Double.NaN
            val vote = DoubleArray(4)
            for ((t, v) in modelDowns) if (t in max(span.first, g.first - g.period)..min(span.second, g.last + g.period)) {
                val k = Math.round(g.index(t)).toInt()
                if (abs(g.beat(k) - t) < g.period * 0.3) vote[Math.floorMod(k, 4)] += v.toDouble()
            }
            if (vote.sum() > 0) return g.beat(vote.indices.maxBy { vote[it] })
            val bar = barStarts.firstOrNull { it in span.first..span.second } ?: return Double.NaN
            return g.beat(Math.round(g.index(bar)).toInt())
        }
        val headDown = downOn(head, headSpan); val tailDown = downOn(tail, tailSpan)
        // How clearly those votes chose one phase: 0 for four equal phases or no vote, 1 for a single one.
        fun metre(g: Grid?, span: Pair<Double, Double>): Float {
            if (g == null) return 0f
            val vote = DoubleArray(4)
            for ((t, v) in modelDowns) if (t in max(span.first, g.first - g.period)..min(span.second, g.last + g.period)) {
                val k = Math.round(g.index(t)).toInt()
                if (abs(g.beat(k) - t) < g.period * 0.3) vote[Math.floorMod(k, 4)] += max(0f, v).toDouble()
            }
            val sum = vote.sum()
            return if (sum <= 0) 0f else (((vote.max() / sum) - 0.25) / 0.75).toFloat().coerceIn(0f, 1f)
        }
        // The model's word on each bar start: its logit as a probability, 0 when it placed no downbeat there.
        val barDownConfidence = if (act == null) FloatArray(0) else FloatArray(bars) { i ->
            var best = 0f
            for ((t, v) in modelDowns) if (abs(t - barStarts[i]) <= DOWN_NEAR_SEC) best = max(best, 1f / (1f + exp(-v)))
            best
        }
        val novelty = novelty(arrayOf(barDb, barLow, barMid, barHigh, barOnset), barChroma)
        val phraseOffset = phraseOffset(novelty)
        val vocalRisk = VocalRisk.estimate(barDb, barMid, absoluteCentre, barCentre, f.stereo)
        val (key, keyStrength) = key(f, level, gate)
        val loud = loudness(f.rmsDb, peak - 20f)
        return TrackAnalysis(f.durationSec, contentStart, contentEnd, loud, key, keyStrength, bpm, steady, head, tail,
            beats, barStarts, barDb, barLow, barHigh, barCentre, novelty, phraseOffset, f.stereo, headDown, tailDown, why.toString().trimEnd(' ', ';'), vocalRisk,
            signalKey(mid, side, length), barBass, phaseStats(head, mb, headSpan), phaseStats(tail, mb, tailSpan), metre(head, headSpan), metre(tail, tailSpan),
            mb, barDownConfidence)
    }

    /** The model's beats against a grid: median and 95th percentile of their distance; null with too few to tell. */
    private fun phaseStats(g: Grid?, modelBeats: DoubleArray, span: Pair<Double, Double>): PhaseStats? {
        if (g == null) return null
        val gaps = modelBeats.filter { it in max(span.first, g.first - g.period / 2)..min(span.second, g.last + g.period / 2) }
            .map { t -> abs(t - g.beat(Math.round(g.index(t)).toInt())) }.filter { it < g.period * 0.25 }.sorted()
        if (gaps.size < 8) return null
        return PhaseStats(Math.round(gaps[gaps.size / 2] * 1e6), Math.round(gaps[(gaps.size * 0.95).toInt().coerceAtMost(gaps.size - 1)] * 1e6))
    }

    /** SHA-256 of the signal the analysis read. */
    private fun signalKey(mid: FloatArray, side: FloatArray?, length: Int): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val bytes = java.nio.ByteBuffer.allocate(64 * 1024).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        for (signal in listOfNotNull(mid, side)) {
            for (i in 0 until length) {
                bytes.putFloat(signal[i])
                if (!bytes.hasRemaining()) { digest.update(bytes.array(), 0, bytes.position()); bytes.clear() }
            }
        }
        digest.update(bytes.array(), 0, bytes.position())
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Which of every 4 beats starts a bar: the beat phase with the most kick (low-band onsets), the
     * most harmonic change and the most level change from before it to after it.
     */
    fun downbeatPhase(beats: DoubleArray, f: Features, env: FloatArray): Int {
        if (beats.size < 8) return 0
        val nb = beats.size
        val bass = DoubleArray(nb); val chroma = Array(nb) { DoubleArray(12) }; val low = DoubleArray(nb)
        for (k in 0 until nb) {
            val c = (beats[k] * FPS).toInt()
            var m = 0f
            for (j in c - 3..c + 3) m = max(m, f.bassOnset.getOrElse(j) { 0f })
            bass[k] = m.toDouble()
            val end = if (k + 1 < nb) beats[k + 1] else beats[k] + (beats[k] - beats[k - 1])
            val ca = (beats[k] * Features.CHROMA_FPS).toInt().coerceIn(0, f.chroma.size - 1)
            val cb = (end * Features.CHROMA_FPS).toInt().coerceIn(ca + 1, f.chroma.size)
            for (j in ca until cb) for (p in 0 until 12) chroma[k][p] += f.chroma[j][p].toDouble()
            val fa = (beats[k] * FPS).toInt().coerceIn(0, f.frames - 1); val fb = (end * FPS).toInt().coerceIn(fa + 1, f.frames)
            low[k] = powerMean(f.lowDb, fa, fb).toDouble()
        }
        // Harmonic change: the two beats from here against the two before.
        val change = DoubleArray(nb)
        for (k in 2 until nb - 2) {
            val a = DoubleArray(12) { chroma[k - 2][it] + chroma[k - 1][it] }
            val b = DoubleArray(12) { chroma[k][it] + chroma[k + 1][it] }
            change[k] = 1 - cosine(a, b)
        }
        val lowStep = DoubleArray(nb)
        for (k in 1 until nb) lowStep[k] = max(0.0, low[k] - low[k - 1])
        val scores = DoubleArray(4)
        for (feature in listOf(bass to 1.0, change to 1.0, lowStep to 0.5)) {
            val per = DoubleArray(4); val cnt = IntArray(4)
            for (k in 0 until nb) { per[k % 4] += feature.first[k]; cnt[k % 4]++ }
            for (p in 0 until 4) per[p] /= max(1, cnt[p])
            val mean = per.average(); val sd = sqrt(per.sumOf { (it - mean) * (it - mean) } / 4).takeIf { it > 1e-9 } ?: continue
            for (p in 0 until 4) scores[p] += feature.second * (per[p] - mean) / sd
        }
        return scores.indices.maxBy { scores[it] }
    }

    /**
     * Onset-flux peaks come about 27 ms before the beat a listener (and Beat This) places; grids are
     * moved by that much without the model, so that every song's beats mean the same instant.
     */
    private const val ONSET_LEAD = 0.027

    /** Mean beat interval of the model's beats near their median (seconds), or null with too few. */
    private fun modelPeriod(beats: DoubleArray): Double? {
        val gaps = beats.toList().zipWithNext { a, b -> b - a }.filter { it in 60 / BeatTracker.MAX_BPM..60 / BeatTracker.MIN_BPM }
        if (gaps.size < 12) return null
        val m = gaps.sorted()[gaps.size / 2]
        return gaps.filter { abs(it - m) < m * 0.1 }.average()
    }

    /**
     * The onset grid put on the model's beats: moved by half a beat when they fall between its beats
     * (an off-beat lock), then by the median gap to them. Without the model, by [ONSET_LEAD].
     */
    private fun onModel(grid: Grid, modelBeats: DoubleArray): Grid {
        if (modelBeats.size < 8) return grid.copy(first = grid.first + ONSET_LEAD)
        var g = grid
        var c = 0.0; var s = 0.0
        for (t in modelBeats) { val a = 2 * Math.PI * g.index(t); c += Math.cos(a); s += Math.sin(a) }
        if (abs(Math.atan2(s, c) / (2 * Math.PI)) > 0.25) g = g.copy(first = g.first + g.period / 2, count = g.count - 1)
        val gaps = modelBeats.filter { it >= g.first - g.period / 2 && it <= g.last + g.period / 2 }
            .map { t -> (t - g.beat(Math.round(g.index(t)).toInt())) }.filter { abs(it) < g.period * 0.25 }.sorted()
        return if (gaps.size < 8) g else g.copy(first = g.first + gaps[gaps.size / 2])
    }

    /**
     * A constant grid fitted on the model's beats in [span] alone (least squares on their times, a
     * missed beat counted as such), kept when they sit on it: median gap ≤ 15 ms, 90 % within 35 ms,
     * no 8-beat stretch off by more than 30 ms, at least 16 beats.
     */
    private fun fromBeats(beats: DoubleArray, span: Pair<Double, Double>, period: Double): Pair<Grid?, String> {
        val t = beats.filter { it >= span.first && it <= span.second }
        if (t.size < 16) return null to "${t.size} beats"
        val gaps = t.zipWithNext { a, b -> b - a }.filter { abs(it - period) < 0.15 * period }.sorted()
        if (gaps.size < 12) return null to "${gaps.size} regular gaps of ${t.size}"
        var per = gaps[gaps.size / 2]; var first = t[0]
        val keep = BooleanArray(t.size) { true }
        repeat(3) { pass ->
            var n = 0.0; var sk = 0.0; var st = 0.0; var skk = 0.0; var skt = 0.0
            for (i in t.indices) {
                val k = Math.round((t[i] - first) / per).toDouble()
                if (pass > 0) keep[i] = abs(t[i] - (first + k * per)) < 0.08 * per
                if (!keep[i]) continue
                n++; sk += k; st += t[i]; skk += k * k; skt += k * t[i]
            }
            val den = n * skk - sk * sk
            if (n < 12 || den <= 0) return null to "too few beats on a grid"
            per = (n * skt - sk * st) / den; first = (st - per * sk) / n
        }
        // Extra detections (off-beats the model also marked) are left out before judging: the beats
        // that sit on the grid must be most of them, close to it, and not drift.
        val all = t.map { x -> x - (first + Math.round((x - first) / per) * per) }
        val res = all.filter { abs(it) <= 0.12 * per }
        if (res.size < 16) return null to "${res.size} of ${t.size} beats on a grid"
        val abs = res.map { abs(it) }.sorted()
        var drift = 0.0
        for (i in 0..res.size - 8) drift = max(drift, abs(res.subList(i, i + 8).average()))
        val share = res.size.toDouble() / t.size
        val text = String.format("%d beats, %.0f %% on the grid, median %.0f ms, 90%% %.0f ms, drift %.0f ms", t.size, share * 100,
            abs[abs.size / 2] * 1000, abs[(abs.size * 0.9).toInt()] * 1000, drift * 1000)
        if (share < 0.75 || abs[abs.size / 2] > 0.015 || abs[(abs.size * 0.9).toInt()] > 0.035 || drift > 0.030) return null to text
        val count = Math.round((t.last() - first) / per).toInt() + 1
        return Grid(first, per, count, res.count { abs(it) <= 0.035 }.toDouble() / res.size, drift * 1000, 1.0) to text
    }

    /** Share of the model's beats (in [span] and the grid's) within 50 ms of a grid beat; 1 without the model. */
    private fun agreement(g: Grid, modelBeats: DoubleArray, span: Pair<Double, Double>): Double {
        val inside = modelBeats.filter { it >= max(span.first, g.first - 0.05) && it <= min(span.second, g.last + 0.05) }
        if (inside.size < 8) return 1.0
        return inside.count { t -> abs(t - g.beat(Math.round(g.index(t)).toInt())) <= 0.05 }.toDouble() / inside.size
    }

    /** The phase (beat index mod 4) that most of the model's downbeats land on, weighted by their logits. */
    private fun votePhase(beats: DoubleArray, downbeats: List<Pair<Double, Float>>): Int? {
        if (beats.size < 8 || downbeats.size < 2) return null
        val vote = DoubleArray(4)
        for ((t, v) in downbeats) {
            val k = nearest(beats, t)
            val gap = if (k + 1 < beats.size) beats[k + 1] - beats[k] else beats[k] - beats[k - 1]
            if (abs(beats[k] - t) < gap * 0.3) vote[k % 4] += v.toDouble()
        }
        return if (vote.sum() <= 0) null else vote.indices.maxBy { vote[it] }
    }

    private fun nearest(x: DoubleArray, t: Double): Int {
        var lo = 0; var hi = x.size - 1
        while (hi - lo > 1) { val m = (lo + hi) ushr 1; if (x[m] <= t) lo = m else hi = m }
        return if (abs(x[lo] - t) <= abs(x[hi] - t)) lo else hi
    }

    /** Per bar: how far the mean feature vector of the 4 bars from it is from that of the 4 before. */
    private fun novelty(features: Array<FloatArray>, chroma: Array<FloatArray>): FloatArray {
        val bars = chroma.size
        if (bars < 9) return FloatArray(bars)
        val dims = features.size + 12
        val v = Array(bars) { i -> DoubleArray(dims) { d -> if (d < features.size) features[d][i].toDouble() else chroma[i][d - features.size].toDouble() } }
        // Standardised per dimension, chroma weighted down (12 dimensions of one kind).
        for (d in 0 until dims) {
            val m = v.sumOf { it[d] } / bars
            val sd = sqrt(v.sumOf { (it[d] - m) * (it[d] - m) } / bars).takeIf { it > 1e-9 } ?: 1.0
            val w = if (d < features.size) 1.0 else 0.35
            for (i in 0 until bars) v[i][d] = (v[i][d] - m) / sd * w
        }
        val out = FloatArray(bars)
        for (i in 1 until bars) {
            val span = min(4, min(i, bars - i))
            var s = 0.0
            for (d in 0 until dims) {
                var a = 0.0; var b = 0.0
                for (j in 0 until span) { a += v[i - 1 - j][d]; b += v[i + j][d] }
                val diff = (b - a) / span
                s += diff * diff
            }
            out[i] = sqrt(s).toFloat() * if (span < 4) span / 4f else 1f
        }
        return out
    }

    /** The bar offset of 8-bar phrases: where the novelty peaks recur every 8 bars. */
    private fun phraseOffset(novelty: FloatArray): Int {
        if (novelty.size < 16) return 0
        val per8 = DoubleArray(8)
        for (i in novelty.indices) per8[i % 8] += novelty[i].toDouble()
        return per8.indices.maxBy { per8[it] }
    }

    private val MAJOR = doubleArrayOf(6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88)
    private val MINOR = doubleArrayOf(6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17)

    /** Krumhansl–Kessler key of the song's summed chroma, with its correlation. */
    private fun key(f: Features, level: FloatArray, gate: Float): Pair<Int, Double> {
        val sum = DoubleArray(12)
        for (j in f.chroma.indices) {
            val lf = (j / Features.CHROMA_FPS * FPS).toInt().coerceIn(0, level.size - 1)
            if (level[lf] > gate + 10) for (p in 0 until 12) sum[p] += f.chroma[j][p]
        }
        if (sum.sum() <= 0) return -1 to 0.0
        var best = -1; var bestR = -2.0
        for (mode in 0..1) for (tonic in 0 until 12) {
            val prof = if (mode == 0) MAJOR else MINOR
            val r = pearson(DoubleArray(12) { sum[it] }, DoubleArray(12) { prof[Math.floorMod(it - tonic, 12)] })
            if (r > bestR) { bestR = r; best = mode * 12 + tonic }
        }
        // Minor keys are numbered by tonic + 12 (A minor = 21).
        return best to bestR
    }

    private fun loudness(db: FloatArray, gate: Float): Double {
        var s = 0.0; var c = 0
        for (v in db) if (v > gate) { s += Math.pow(10.0, v / 10.0); c++ }
        return if (c == 0) -60.0 else 10 * Math.log10(s / c)
    }

    internal fun powerMean(db: FloatArray, from: Int, to: Int): Float {
        var s = 0.0
        val a = from.coerceIn(0, db.size - 1); val b = to.coerceIn(a + 1, db.size)
        for (j in a until b) s += Math.pow(10.0, db[j] / 10.0)
        return Features.db(s / (b - a))
    }

    internal fun percentile(x: FloatArray, q: Double): Float {
        if (x.isEmpty()) return 0f
        val s = x.copyOf(); s.sort()
        return s[((s.size - 1) * q).toInt()]
    }

    private fun cosine(a: DoubleArray, b: DoubleArray): Double {
        var ab = 0.0; var aa = 0.0; var bb = 0.0
        for (i in a.indices) { ab += a[i] * b[i]; aa += a[i] * a[i]; bb += b[i] * b[i] }
        return if (aa <= 0 || bb <= 0) 1.0 else ab / sqrt(aa * bb)
    }

    private fun pearson(a: DoubleArray, b: DoubleArray): Double {
        val ma = a.average(); val mb = b.average()
        var ab = 0.0; var aa = 0.0; var bb = 0.0
        for (i in a.indices) { ab += (a[i] - ma) * (b[i] - mb); aa += (a[i] - ma) * (a[i] - ma); bb += (b[i] - mb) * (b[i] - mb) }
        return if (aa <= 0 || bb <= 0) 0.0 else ab / sqrt(aa * bb)
    }
}
