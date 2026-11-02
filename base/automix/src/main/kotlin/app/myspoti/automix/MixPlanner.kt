/* SPDX-License-Identifier: GPL-3.0-only */
package app.myspoti.automix

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Picks the transition between two analysed songs: where A goes out, where B comes in, how long the
 * mix lasts and its style.
 *
 * Rules (from DJ practice, Mixxx's intro/outro model and Zehren et al. 2020 on cue points):
 * - Cues sit on downbeats, preferably on 8-bar phrase starts; the mix lasts 2 to 16 bars.
 * - A goes out in its outro: late, where its level drops, without cutting a loud part short.
 * - B comes in at its first downbeat (or a later phrase start within its intro), and its first big
 *   change (the drop, the first verse) should land when A is gone.
 * - Beat-matching only with clear grids on both sides and a tempo change within [Settings.maxStretch]
 *   (half and double time count); keys a fifth apart or more and two voices at once shorten the mix.
 * - Otherwise a cut on A's downbeat (A has a grid) or a plain fade.
 */
object MixPlanner {
    class Settings(
        /** Largest tempo change applied to B, as a share (0.08 = 8 %). */
        val maxStretch: Double = 0.08,
        /** Longest mix, seconds. */
        val maxSec: Double = 32.0,
        /** Fade length without a beat grid, seconds. */
        val fadeSec: Double = 6.0,
    )

    fun plan(a: TrackAnalysis, b: TrackAnalysis, s: Settings = Settings()): MixPlan {
        val ga = a.tail; val gb = b.head
        val match = if (ga != null && gb != null && !a.tailDown.isNaN() && !b.headDown.isNaN()) tempoMatch(ga.bpm, gb.bpm) else null
        if (match != null && abs(match.speed - 1) <= s.maxStretch) {
            blend(a, b, ga!!, gb!!, match, s)?.let { return it }
        }
        if (ga != null && !a.tailDown.isNaN()) cut(a, b, ga, gb, match, s)?.let { return it }
        return fade(a, b, s, if (match == null) "no beat grid" else String.format("tempo %.1f → %.1f too far", ga!!.bpm, gb!!.bpm))
    }

    /**
     * The mix for a skip (the user pressed Next at A's time [now]; the next song can be heard [lead]
     * seconds later at the earliest): short, from A's next downbeat. Two bars beat-matched (one when
     * two are over 5.5 s), else a cut on the downbeat, else a 2.5 s fade. Either analysis may be missing.
     */
    fun planSkip(a: TrackAnalysis?, b: TrackAnalysis?, now: Double, lead: Double, s: Settings = Settings()): MixPlan {
        val from = now + lead
        val ga = a?.let { gridAt(it, from) }
        val gb = b?.head?.takeIf { !b.headDown.isNaN() }
        val entry = if (b != null && gb != null) firstDown(gb, b.headDown, b.contentStart - 0.08) else b?.contentStart ?: 0.0
        val gain = if (a != null && b != null) matchGain(a, b) else 1.0
        if (a != null && ga != null) {
            val exit = firstDown(ga.first, ga.second, from)
            val barA = 4 * ga.first.period
            val m = gb?.let { tempoMatch(ga.first.bpm, it.bpm) }
            if (m != null && abs(m.speed - 1) <= s.maxStretch) {
                val bars = if (2 * barA <= 5.5) 2 else 1
                return MixPlan(MixPlan.Style.BLEND, exit, entry, bars * barA, m.speed, bars * 4, 0.5, 1.0 / (bars * 4), gain,
                    restore(m.speed, gb.period), String.format("skip: %d bar(s) from A's downbeat at %.1f, %.1f→%.1f BPM", bars, exit, ga.first.bpm, gb.bpm))
            }
            return MixPlan(MixPlan.Style.CUT, exit, entry, 2 * ga.first.period, 1.0, 2, 0.0, 0.5, gain, 0.0,
                String.format("skip: cut on A's downbeat at %.1f", exit))
        }
        return MixPlan(MixPlan.Style.FADE, from, entry, 2.5, 1.0, 0, 0.5, 0.1, gain, 0.0, "skip: fade (no beat grid here)")
    }

    /** The grid (and one of its downbeats) holding at A's time [t]: the tail's near the end, the head's near the start, the one grid of a steady song. */
    private fun gridAt(a: TrackAnalysis, t: Double): Pair<Grid, Double>? {
        val tail = a.tail?.takeIf { !a.tailDown.isNaN() && t >= max(it.first, a.contentEnd - Analyzer.TAIL_SEC) && t < it.last }
        if (tail != null) return tail to a.tailDown
        val head = a.head?.takeIf { !a.headDown.isNaN() && t < it.last && (a.steady || t <= a.contentStart + Analyzer.HEAD_SEC) }
        return head?.let { it to a.headDown }
    }

    /** B's speed so its beat (or half or double beat) falls on A's; [beatsPerB] A beats per B beat. */
    class TempoMatch(val speed: Double, val beatsPerB: Double)

    fun tempoMatch(bpmA: Double, bpmB: Double): TempoMatch =
        listOf(0.5, 1.0, 2.0).map { m -> TempoMatch(bpmA / (bpmB * m), m) }.minBy { abs(ln(it.speed)) }

    private class Cue(val time: Double, val score: Double, val why: String)

    private fun blend(a: TrackAnalysis, b: TrackAnalysis, ga: Grid, gb: Grid, m: TempoMatch, s: Settings): MixPlan? {
        val barA = 4 * ga.period
        val exits = exitCues(a, ga)
        val entries = entryCues(b, gb)
        if (exits.isEmpty() || entries.isEmpty()) return null
        val keyPenalty = keyDistance(a, b)
        var best: MixPlan? = null; var bestScore = Double.NEGATIVE_INFINITY
        val drops = drops(b, gb)
        for (e in exits) for (bars in intArrayOf(16, 12, 8, 6, 4, 2)) for (n in entries + dropEntries(b, gb, drops, bars * barA * m.speed)) {
            val len = bars * barA
            if (len > s.maxSec + 0.01 && bars > 2) continue
            // A must still be playing (its audible part) for most of the mix.
            if (e.time + len > musicEnd(a) + barA) continue
            val bEnd = n.time + len * m.speed
            if (bEnd > b.contentEnd - 10) continue
            // The whole mix where both grids were checked (a grid of a song's first 25 s holds there only).
            if (bEnd > gb.last + gb.period || n.time < gb.first - gb.period || e.time < ga.first - ga.period) continue
            var score = e.score + n.score
            // Long mixes suit songs in compatible keys with steady grids; clashing keys favour short ones.
            score += when (bars) { 16 -> 1.0; 12 -> 0.6; 8 -> 1.5; 6 -> 0.4; 4 -> 0.8; else -> 0.0 } - keyPenalty * bars / 8.0
            // Strong tempo changes are heard: shorter mixes then.
            score -= abs(m.speed - 1) / 0.08 * bars / 16.0
            // A's loud bars after the mix (cut short) and a long silence of A inside the mix.
            score -= loudCut(a, e.time + len) * 0.6
            score -= max(0.0, e.time + len - musicEnd(a)) / barA * 0.5
            // B's strong beat (the drop, the first verse: where its level and bass jump) when A is gone,
            // the mix's best moment; a lesser change there still counts.
            score += dropAt(b, bEnd) * 2.5 + changeNear(b, bEnd, 4 * gb.period * m.beatsPerB) * 0.6
            // Two voices at once.
            score -= voiceClash(a, e.time, e.time + len, b, n.time, bEnd) * bars / 8.0
            // Two loud songs over each other for long: worse than a loud A over a quiet intro.
            score -= max(0.0, levelAt(a, e.time, e.time + len) + levelAt(b, n.time, bEnd)) * 0.08 * bars / 8.0
            if (score > bestScore) {
                bestScore = score
                val swapBar = (bars / 2.0).let { if (bars >= 4) floor(it) else it }
                best = MixPlan(MixPlan.Style.BLEND, e.time, n.time, len, m.speed, bars * 4, swapBar / bars, 1.0 / (bars * 4),
                    matchGain(a, b), restore(m.speed, gb.period), String.format("%s, %s, %.1f→%.1f BPM, key %s, score %.1f", e.why, n.why, ga.bpm, gb.bpm, keyText(keyPenalty), score))
            }
        }
        return best
    }

    private fun cut(a: TrackAnalysis, b: TrackAnalysis, ga: Grid, gb: Grid?, m: TempoMatch?, s: Settings): MixPlan? {
        val exits = exitCues(a, ga)
        if (exits.isEmpty()) return null
        // B starts on its first downbeat when it has one, else where its audio starts.
        val entry = if (gb != null && !b.headDown.isNaN()) entryCues(b, gb).maxBy { it.score }.time else b.contentStart
        val e = exits.maxBy { it.score - loudCut(a, it.time + 2 * ga.period) * 0.6 }
        val synced = m != null && abs(m.speed - 1) <= s.maxStretch
        val speed = if (synced) m!!.speed else 1.0
        return MixPlan(MixPlan.Style.CUT, e.time, entry, 2 * ga.period, speed, 2, 0.0, 0.5, matchGain(a, b),
            if (synced) restore(speed, gb!!.period) else 0.0, "cut on ${e.why}" + if (synced) "" else ", tempos apart")
    }

    private fun fade(a: TrackAnalysis, b: TrackAnalysis, s: Settings, why: String): MixPlan {
        val len = min(s.fadeSec, (a.contentEnd - a.contentStart) / 4)
        // On a bar of A (its bars come from the model's beats even without a steady grid), with B's
        // first downbeat on it when B has one.
        val target = max(a.contentStart, musicEnd(a) - len * 0.6)
        val exit = a.barStarts.lastOrNull { it <= target && it >= target - 6 } ?: target
        val gb = b.head?.takeIf { !b.headDown.isNaN() }
        val entry = if (gb != null) firstDown(gb, b.headDown, b.contentStart - 0.08) else b.contentStart
        return MixPlan(MixPlan.Style.FADE, exit, entry, len, 1.0, 0, 0.5, 0.1, matchGain(a, b), 0.0, "fade: $why")
    }

    /**
     * A's exit cues: downbeats of its tail grid in its last minute and a half, scored for phrase starts,
     * a level drop after them (the outro) and being late in the song.
     */
    private fun exitCues(a: TrackAnalysis, g: Grid): List<Cue> {
        val end = musicEnd(a)
        // Within the grid's own span (a grid of the last 25 s holds there only).
        val from = maxOf(a.contentStart + (end - a.contentStart) * 0.5, end - 90, g.first)
        val to = min(end, g.last) - 2 * g.period
        val out = ArrayList<Cue>()
        val loud = loudLevel(a)
        var t = firstDown(g, a.tailDown, from)
        while (t <= to) {
            val bar = a.barAt(t + 0.05)
            var score = 0.0; var why = ""
            if (bar >= 0) {
                val pos = a.phrasePos(bar)
                score += when (pos) { 0 -> 2.0; 4 -> 1.0; else -> 0.0 }
                score += (a.barNovelty.getOrElse(bar) { 0f }).toDouble().coerceAtMost(3.0) * 0.4
                val before = levelBars(a, bar - 4, bar); val after = levelBars(a, bar, bar + 4)
                if (before - after > 3) { score += 1.5; why = "outro" }
                why = (if (pos == 0) "A phrase" else if (pos == 4) "A half phrase" else "A bar") + (if (why.isEmpty()) "" else " ($why)") +
                    String.format(" at %.1f", t)
                // Already quiet (the song's end fading): fine, but no later than needed.
                if (after < loud - 12) score -= 0.5
            } else why = String.format("A bar at %.1f", t)
            // Later is better: less of A is lost.
            score += (t - from) / max(1.0, to - from) * 1.5
            out.add(Cue(t, score, why))
            t += 4 * g.period
        }
        return out
    }

    /** B's strong beats in its first minute: downbeats of its grid where it comes in (see [dropAt]). */
    private fun drops(b: TrackAnalysis, g: Grid): List<Double> {
        val out = ArrayList<Double>()
        var t = firstDown(g, b.headDown, b.contentStart + 4 * g.period)
        while (t < b.contentStart + 60 && t < g.last) { if (dropAt(b, t) >= 0.5) out.add(t); t += 4 * g.period }
        return out
    }

    /** Entries that put one of B's strong beats [drops] right at the end of a mix lasting [lengthB] of B's seconds. */
    private fun dropEntries(b: TrackAnalysis, g: Grid, drops: List<Double>, lengthB: Double): List<Cue> =
        drops.mapNotNull { d ->
            val n = d - lengthB
            if (n < b.contentStart - 0.1) return@mapNotNull null
            // On B's own downbeat (the mix lasts whole bars of B when the tempos match beat for beat).
            val down = firstDown(g, b.headDown, n - 2 * g.period)
            if (abs(down - n) > 0.1 * g.period) null
            else Cue(down, 1.5 - (down - b.contentStart) / 25.0, String.format("B from %.1f for its strong beat at %.1f", down, d))
        }

    /** B's entry cues: its first downbeat, and phrase starts in its first 32 s (each skip costs). */
    private fun entryCues(b: TrackAnalysis, g: Grid): List<Cue> {
        val first = firstDown(g, b.headDown, b.contentStart - 0.08)
        val out = arrayListOf(Cue(first, 1.0, String.format("B first downbeat %.1f", first)))
        var t = first + 4 * g.period
        while (t < b.contentStart + 32 && t < b.contentEnd / 3 && t < g.last - 8 * g.period) {
            val bar = b.barAt(t + 0.05)
            if (bar >= 0 && b.phrasePos(bar) == 0) {
                // Skipping a quiet intro is fine, skipping music costs.
                val skipped = t - b.contentStart
                val quiet = levelBars(b, 0, bar) < loudLevel(b) - 9
                out.add(Cue(t, 1.5 - skipped / (if (quiet) 30.0 else 12.0), String.format("B phrase %.1f", t)))
            }
            t += 4 * g.period
        }
        return out
    }

    /**
     * Where A's music really ends: the end of its last bar within 15 dB of its loud level (a long
     * fade-out or a quiet tail is left to the mix).
     */
    fun musicEnd(a: TrackAnalysis): Double {
        val loud = loudLevel(a)
        val last = (a.bars - 1 downTo 0).firstOrNull { a.barDb[it] > loud - 15 } ?: return a.contentEnd
        return min(a.contentEnd, a.barEnd(last))
    }

    /** The first downbeat of the grid at or after [from]. */
    fun firstDown(g: Grid, down: Double, from: Double): Double {
        val bar = 4 * g.period
        return down + ceil((from - down) / bar - 1e-6) * bar
    }

    private fun loudLevel(a: TrackAnalysis): Double = a.barDb.sorted().let { if (it.isEmpty()) -20.0 else it[(it.size * 0.7).toInt()].toDouble() }

    private fun levelBars(a: TrackAnalysis, from: Int, to: Int): Double {
        var s = 0.0; var c = 0
        for (i in max(0, from) until min(a.bars, to)) { s += 10.0.pow(a.barDb[i] / 10.0); c++ }
        return if (c == 0) -60.0 else 10 * Math.log10(s / c)
    }

    private fun levelAt(a: TrackAnalysis, from: Double, to: Double): Double {
        val i = max(0, a.barAt(from)); val j = max(i + 1, a.barAt(to))
        return levelBars(a, i, j) - loudLevel(a)
    }

    /** Bars of A at full level that the mix ending at [end] leaves out. */
    private fun loudCut(a: TrackAnalysis, end: Double): Double {
        val loud = loudLevel(a)
        var n = 0.0
        for (i in max(0, a.barAt(end) + 1) until a.bars) if (a.barDb[i] > loud - 4) n += 1.0
        return n
    }

    /**
     * How strongly B "comes in" at [t]: the rise of level and of bass from the 4 bars before the bar
     * starting nearest [t] (within half a bar) to the 4 after, 0 (none) to 2 (a big drop).
     */
    private fun dropAt(b: TrackAnalysis, t: Double): Double {
        val i = (0 until b.bars).minByOrNull { abs(b.barStarts[it] - t) } ?: return 0.0
        val bar = if (i + 1 < b.bars) b.barStarts[i + 1] - b.barStarts[i] else 2.0
        if (abs(b.barStarts[i] - t) > bar / 2 || i < 2) return 0.0
        fun mean(x: FloatArray, from: Int, to: Int): Double {
            var s = 0.0; var c = 0
            for (k in max(0, from) until min(b.bars, to)) { s += 10.0.pow(x[k] / 10.0); c++ }
            return if (c == 0) -60.0 else 10 * Math.log10(s / c)
        }
        val level = mean(b.barDb, i, i + 4) - mean(b.barDb, i - 4, i)
        val bass = mean(b.barLowDb, i, i + 4) - mean(b.barLowDb, i - 4, i)
        return ((max(0.0, level) + 0.5 * max(0.0, bass)) / 4.0).coerceIn(0.0, 2.0)
    }

    /** A large novelty (a new section) within a bar of [t] in B. */
    private fun changeNear(b: TrackAnalysis, t: Double, bar: Double): Double {
        val i = b.barAt(t + 0.05)
        if (i < 0) return 0.0
        val mean = b.barNovelty.average().takeIf { it > 0 } ?: return 0.0
        var top = 0.0
        for (j in max(0, i - 1)..min(b.bars - 1, i + 1)) {
            if (abs(b.barStarts[j] - t) <= bar * 1.01) top = max(top, b.barNovelty[j] / mean)
        }
        return (top - 1).coerceIn(0.0, 2.0)
    }

    /** Both songs' voice bands high at once (stereo songs only): 0 (none) to 1 (all along). */
    private fun voiceClash(a: TrackAnalysis, a0: Double, a1: Double, b: TrackAnalysis, b0: Double, b1: Double): Double {
        if (!a.stereo || !b.stereo) return 0.0
        fun share(x: TrackAnalysis, t0: Double, t1: Double): Double {
            val i = max(0, x.barAt(t0)); val j = max(i + 1, x.barAt(t1))
            var c = 0; var v = 0
            for (k in i until min(x.bars, j)) { c++; if (x.barCentre[k] > 3f) v++ }
            return if (c == 0) 0.0 else v.toDouble() / c
        }
        return share(a, a0, a1) * share(b, b0, b1)
    }

    /** 0: same or neighbouring Camelot keys; 1: two steps; 2: further. Unknown or weak keys: 0.5. */
    fun keyDistance(a: TrackAnalysis, b: TrackAnalysis): Double {
        if (a.key < 0 || b.key < 0 || a.keyStrength < 0.5 || b.keyStrength < 0.5) return 0.5
        val (na, la) = camelot(a.key); val (nb, lb) = camelot(b.key)
        val d = min(Math.floorMod(na - nb, 12), Math.floorMod(nb - na, 12)) + if (la != lb) 1 else 0
        return when { d <= 1 -> 0.0; d == 2 -> 1.0; else -> 2.0 }
    }

    /** Camelot number (1..12) and letter (true = B, major). */
    fun camelot(key: Int): Pair<Int, Boolean> {
        val major = key < 12
        val tonic = if (major) key else Math.floorMod(key - 12 + 3, 12)
        return (Math.floorMod(tonic * 7 + 7, 12) + 1) to major
    }

    private fun keyText(d: Double) = when (d) { 0.0 -> "compatible"; 0.5 -> "unsure"; 1.0 -> "near"; else -> "clash" }

    /** B louder than A by more than 1.5 dB is brought down to A's level during the mix (down to −6 dB). */
    private fun matchGain(a: TrackAnalysis, b: TrackAnalysis): Double {
        val d = a.loudnessDb - b.loudnessDb
        return if (d < -1.5) 10.0.pow(max(d, -6.0) / 20) else 1.0
    }

    /** B's speed back to 1: about 0.3 % per beat, at least 8 beats. */
    private fun restore(speed: Double, period: Double): Double {
        val beats = max(8.0, abs(speed - 1) / 0.003)
        return if (abs(speed - 1) < 1e-4) 0.0 else (beats * period).let { (it * 10).roundToInt() / 10.0 }
    }
}
