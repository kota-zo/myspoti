/* SPDX-License-Identifier: GPL-3.0-only */
package app.myspoti.automix.segue

import app.myspoti.automix.BeatTracker
import app.myspoti.automix.Grid
import app.myspoti.automix.TrackAnalysis
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * A run of whole bars judged on its own beats. A song whose ending as a whole holds no single grid
 * (a tempo that wanders, a bridge without drums) can still have stretches that do, and a mix only
 * needs one. [grid] is fitted on these beats alone: beat 0 on the first bar's start.
 */
internal class Stretch(
    val bar: Int,
    val bars: Int,
    val grid: Grid,
    /** The beats measured inside, seconds. */
    val beats: DoubleArray,
    /** How far it can be trusted, 0..1: the worst of its beats' share and its downbeats' confidence. */
    val reliability: Double,
) {
    val start get() = grid.first
    val end get() = grid.first + grid.period * 4 * bars
    val startUs get() = (start * 1e6).roundToLong()
    val lengthSec get() = end - start
}

internal object Stretches {
    private fun lowerBound(x: DoubleArray, value: Double): Int {
        var lo = 0; var hi = x.size
        while (lo < hi) { val m = (lo + hi) ushr 1; if (x[m] < value) lo = m + 1 else hi = m }
        return lo
    }

    /**
     * The stretch of [bars] bars from [bar], or null when its beats cannot be relied on. [metre] stands
     * in for the downbeats' confidence on an analysis that has none.
     */
    fun measure(s: TrackAnalysis, bar: Int, bars: Int, metre: Double, t: SegueTuning): Stretch? {
        if (bar < 0 || bar + bars >= s.bars) return null
        val from = s.barStarts[bar]; val to = s.barStarts[bar + bars]
        val beatCount = 4 * bars
        val rough = (to - from) / beatCount
        if (rough <= 0 || 60 / rough !in BeatTracker.MIN_BPM..BeatTracker.MAX_BPM) return null
        val source = if (s.modelBeats.isNotEmpty()) s.modelBeats else s.beats
        val i0 = lowerBound(source, from - rough / 4); val i1 = lowerBound(source, to - rough / 4)
        val n = i1 - i0
        if (n < t.stretchMinBeatShare * beatCount || n > beatCount + 1 || n < 2) return null
        val beats = source.copyOfRange(i0, i1)
        // A straight line through the beats by their place in the stretch.
        var sk = 0.0; var st = 0.0; var skk = 0.0; var skt = 0.0
        val ks = IntArray(n) { ((beats[it] - from) / rough).roundToInt() }
        for (i in 0 until n) { val k = ks[i].toDouble(); sk += k; st += beats[i]; skk += k * k; skt += k * beats[i] }
        val den = n * skk - sk * sk
        if (den <= 0) return null
        val period = (n * skt - sk * st) / den
        val first = (st - period * sk) / n
        if (period <= 0 || abs(period / rough - 1) > 0.05) return null
        val gaps = DoubleArray(n) { abs(beats[it] - (first + period * ks[it])) }.also { it.sort() }
        if (gaps[((n - 1) * 0.95).toInt()] * 1e6 > t.phaseP95MaxUs) return null
        // The worst slide of a bar's average off the line.
        var drift = 0.0
        var j = 0
        while (j < n) {
            val e = min(n, j + 4); var sum = 0.0
            for (i in j until e) sum += beats[i] - (first + period * ks[i])
            drift = max(drift, abs(sum / (e - j)))
            j = e
        }
        if (drift * 1000 > t.gridMaxDriftMs) return null
        val down = if (s.barDownConfidence.isEmpty()) metre else {
            var sum = 0.0
            for (b in bar until bar + bars) sum += s.barDownConfidence.getOrElse(b) { 0f }
            sum / bars
        }
        if (down < t.stretchMinDownConfidence) return null
        val share = min(1.0, n.toDouble() / beatCount)
        return Stretch(bar, bars, Grid(first, period, beatCount, share, drift * 1000, 1.0), beats, min(share, down))
    }

    /**
     * A build-up has too few clear beats of its own to be measured, and the song arrives right after it:
     * the [bars] bars from [bar] take the beat of the bars they lead into, carried back, when it lands
     * on their start. Null when what follows does not hold or does not line up.
     */
    fun carried(s: TrackAnalysis, bar: Int, bars: Int, metre: Double, t: SegueTuning): Stretch? {
        if (bar < 0) return null
        val beatCount = 4 * bars
        for (gap in 0..t.carrySearchBars) {
            val next = measure(s, bar + bars + gap, t.carryFromBars, metre, t) ?: continue
            val period = next.grid.period
            val first = next.grid.first - (beatCount + 4 * gap) * period
            if (abs(first - s.barStarts[bar]) * 1e6 > t.carryMaxOffUs) return null
            return Stretch(bar, bars, Grid(first, period, beatCount, 1.0, next.grid.driftMs, 1.0),
                DoubleArray(beatCount) { first + it * period }, min(next.reliability, t.reliabilityForMiddle))
        }
        return null
    }

    /**
     * How far apart the two stretches' beats fall once the incoming one plays at [rate] from its start
     * on the outgoing one's start: the sparser beats against the nearest of the denser. Median and
     * 95th percentile, microseconds; null with too few to tell.
     */
    fun phase(a: Stretch, b: Stretch, rate: Double): Pair<Long, Long>? {
        // Everything on the outgoing song's time, from the start of the window.
        val xa = DoubleArray(a.beats.size) { a.beats[it] - a.start }
        val xb = DoubleArray(b.beats.size) { (b.beats[it] - b.start) / rate }
        val (sparse, dense) = if (xa.size <= xb.size) xa to xb else xb to xa
        if (sparse.size < 2) return null
        val gaps = DoubleArray(sparse.size) { i ->
            val at = lowerBound(dense, sparse[i])
            min(if (at < dense.size) dense[at] - sparse[i] else Double.MAX_VALUE, if (at > 0) sparse[i] - dense[at - 1] else Double.MAX_VALUE)
        }.also { it.sort() }
        return (gaps[gaps.size / 2] * 1e6).roundToLong() to (gaps[((gaps.size - 1) * 0.95).toInt()] * 1e6).roundToLong()
    }
}
