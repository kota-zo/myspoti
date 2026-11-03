/* SPDX-License-Identifier: GPL-3.0-only */
package app.myspoti.automix

import java.util.WeakHashMap
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/** Plans transitions on checked beat grids, local section boundaries and aligned voice-like activity. */
object MixPlanner {
    class Settings(
        /** Largest tempo change applied to B, as a share (0.08 = 8 %). */
        val maxStretch: Double = 0.08,
        /** Longest mix, seconds, in addition to [maxBars]. */
        val maxSec: Double = 32.0,
        /** Fade length without a beat grid, seconds. */
        val fadeSec: Double = 6.0,
        /** -1 short, 0 balanced, 1 long. */
        val lengthBias: Int = 0,
        /** BLEND chooses a safe beat blend, then a cut or fade; CUT and FADE request those styles. */
        val style: MixPlan.Style = MixPlan.Style.BLEND,
        /** Prefer at least these bars when a musically safe blend of that length fits. */
        val minBars: Int = 2,
        val maxBars: Int = 16,
        /** 0 ignores keys, 2 strongly discourages known harmonic clashes. Unknown is not a clash. */
        val keyWeight: Double = 1.0,
        val smoothSec: Double = 8.0,
        /** Allow a structural exit earlier in the second half, never before A's last loud section. */
        val djMode: Boolean = false,
        /** Filtered fade between tempos too far apart to match, seconds. */
        val filterSec: Double = 10.0,
    )

    fun plan(a: TrackAnalysis, b: TrackAnalysis, s: Settings = Settings()): MixPlan =
        planAfter(a, b, a.contentStart, s) ?: MixPlan(MixPlan.Style.FADE, a.contentStart, b.contentStart,
            0.05, 1.0, 0, 0.5, 0.1, matchGain(a, b), 0.0, "no remaining audio")

    /**
     * A plan whose exit is at or after [earliestExit] (A seconds). A missed cue is never returned
     * again. The caller includes its preparation/run-up lead; null means A has no useful time left.
     */
    fun planAfter(a: TrackAnalysis, b: TrackAnalysis, earliestExit: Double, s: Settings = Settings()): MixPlan? {
        if (!earliestExit.isFinite() || a.contentEnd <= max(a.contentStart, earliestExit) + 0.05 || b.contentEnd <= b.contentStart) return null
        val sa = summary(a); val sb = summary(b)
        if (s.style == MixPlan.Style.FADE) return fade(a, b, sa, "smooth fade asked", s.smoothSec, earliestExit)
        val ga = a.tail; val gb = b.head
        val match = if (ga != null && gb != null && !a.tailDown.isNaN() && !b.headDown.isNaN()) tempoMatch(ga.bpm, gb.bpm) else null
        if (s.style == MixPlan.Style.BLEND && match != null && abs(match.speed - 1) <= s.maxStretch) {
            blend(a, b, sa, sb, ga!!, gb!!, match, s, earliestExit)?.let { return it }
        }
        // Quiet low/high bands and little voice-like activity can support a longer unsynced
        // handover. Do not reduce such a pair to a one-second cut merely for its BPM gap.
        if (s.style == MixPlan.Style.BLEND && (s.keyWeight == 0.0 || keyDistance(a, b) < 1.5)) {
            val seconds = max(s.fadeSec, s.smoothSec)
            val soft = fade(a, b, sa, "soft passages, tempos untouched", seconds, earliestExit)
            if (soft != null && sa.softTexture(a, soft.exitA, soft.endA) &&
                sb.softTexture(b, soft.entryB, soft.entryB + soft.length)) return soft
        }
        // Tempos too far apart to match: a filtered fade (B in without its bass, the bass handed over on
        // one of B's downbeats, A's top end closing) rather than a one-second cut at the very end.
        val inRange = match != null && abs(match.speed - 1) <= s.maxStretch
        if (s.style == MixPlan.Style.BLEND && !inRange && ga != null && !a.tailDown.isNaN()) {
            filterFade(a, b, sa, sb, ga, gb?.takeIf { !b.headDown.isNaN() }, s, earliestExit)?.let { return it }
        }
        if (ga != null && !a.tailDown.isNaN()) cut(a, b, sa, sb, ga, gb, match, s, earliestExit)?.let { return it }
        return fade(a, b, sa, if (match == null) "no checked beat pair" else "no safe blend ahead", s.fadeSec, earliestExit)
    }

    /** Next: a short mix from the next downbeat; either analysis may be unavailable. */
    fun planSkip(a: TrackAnalysis?, b: TrackAnalysis?, now: Double, lead: Double, s: Settings = Settings()): MixPlan {
        val from = now + lead
        val ga = a?.let { gridAt(it, from) }
        val gb = b?.head?.takeIf { !b.headDown.isNaN() }
        val entry = if (b != null && gb != null) firstDown(gb, b.headDown, b.contentStart - 0.08) else b?.contentStart ?: 0.0
        val gain = if (a != null && b != null) matchGain(a, b) else 1.0
        if (a != null && ga != null && s.style != MixPlan.Style.FADE) {
            val exit = firstDown(ga.first, ga.second, from)
            val barA = 4 * ga.first.period
            val m = gb?.let { tempoMatch(ga.first.bpm, it.bpm) }
            if (s.style == MixPlan.Style.BLEND && m != null && abs(m.speed - 1) <= s.maxStretch) {
                val bars = if (2 * barA <= 5.5) 2 else 1
                return MixPlan(MixPlan.Style.BLEND, exit, entry, bars * barA, m.speed, bars * 4, 0.5, 1.0 / (bars * 4), gain,
                    restore(m.speed, gb.period), String.format("skip: %d bar(s) from A's downbeat at %.1f, %.1f→%.1f BPM", bars, exit, ga.first.bpm, gb.bpm))
            }
            return MixPlan(MixPlan.Style.CUT, exit, entry, 2 * ga.first.period, 1.0, 2, 0.0, 0.5, gain, 0.0,
                String.format("skip: cut on A's downbeat at %.1f", exit))
        }
        return MixPlan(MixPlan.Style.FADE, from, entry, 2.5, 1.0, 0, 0.5, 0.1, gain, 0.0,
            if (s.style == MixPlan.Style.FADE) "skip: fade" else "skip: fade (no beat grid here)")
    }

    private fun gridAt(a: TrackAnalysis, t: Double): Pair<Grid, Double>? {
        val tail = a.tail?.takeIf { !a.tailDown.isNaN() && t >= max(it.first, a.contentEnd - Analyzer.TAIL_SEC) && t < it.last }
        if (tail != null) return tail to a.tailDown
        val head = a.head?.takeIf { !a.headDown.isNaN() && t < it.last && (a.steady || t <= a.contentStart + Analyzer.HEAD_SEC) }
        return head?.let { it to a.headDown }
    }

    class TempoMatch(val speed: Double, val beatsPerB: Double)

    fun tempoMatch(bpmA: Double, bpmB: Double): TempoMatch =
        listOf(0.5, 1.0, 2.0).map { m -> TempoMatch(bpmA / (bpmB * m), m) }.minBy { abs(ln(it.speed)) }

    private class Cue(val time: Double, val score: Double, val label: String, val strongBeat: Double = Double.NaN) {
        // Formatting every discarded cue costs more than its score on a cold Android runtime.
        val why: String get() = if (strongBeat.isNaN()) String.format("%s at %.1f", label, time)
            else String.format("B from %.1f for its strong beat at %.1f", time, strongBeat)
    }
    private class Entry(val cue: Cue, val end: Double, val score: Double, val relativeLevel: Double)
    private class Choice(val exit: Cue, val entry: Cue, val bars: Int, val score: Double)
    private const val MIN_BLEND_SCORE = 0.5
    /** Beyond this stretch a blend is kept to 16 bars, and B's tempo comes back a little faster. */
    private const val WIDE_STRETCH = 0.08
    /** Per-bar voice-like risk from which a bar counts as sung or rapped (see [VocalRisk]). */
    private const val VOICE = 0.6f
    private val BARS = intArrayOf(32, 24, 16, 12, 8, 6, 4, 2)

    private fun blend(a: TrackAnalysis, b: TrackAnalysis, sa: Summary, sb: Summary, ga: Grid, gb: Grid,
                      m: TempoMatch, s: Settings, earliest: Double): MixPlan? {
        val barA = 4 * ga.period
        val exits = exitCues(a, sa, ga, s, earliest)
        val entries = entryCues(b, sb, gb)
        if (exits.isEmpty() || entries.isEmpty()) return null
        val keyPenalty = keyDistance(a, b)
        val strong = drops(b, sb, gb)
        var best: Choice? = null
        var preferred: Choice? = null
        for (bars in BARS) {
            if (bars > max(2, s.maxBars)) continue
            // A large stretch is heard on B for the second half and its way back: kept to 16 bars.
            if (abs(m.speed - 1) > WIDE_STRETCH && bars > 16) continue
            // Half or double time (71 against 130 BPM): two grooves that meet every other beat only,
            // a mess over a long mix (reported on the phone: 40 s of it).
            if (m.beatsPerB != 1.0 && bars > 8) continue
            val len = bars * barA
            if (len > s.maxSec + 0.01 && bars > 2) continue
            val spanB = len * m.speed
            // Each B candidate and its structural/level scores are shared by all A exit candidates.
            val distinctEntries = (entries + dropEntries(b, gb, strong, spanB))
                .groupBy { (it.time * 1000).roundToInt() }.values.map { same -> same.maxBy { it.score } }
            val prepared = distinctEntries.mapNotNull { n ->
                val end = n.time + spanB
                if (end > b.contentEnd - 10 || end > gb.last + gb.period || n.time < gb.first - gb.period) null
                else Entry(n, end, n.score + sb.dropAt(b, end) * 2.5 + changeNear(b, sb, end, 4 * gb.period * m.beatsPerB) * 0.6,
                    sb.levelAt(b, n.time, end))
            }
            if (prepared.isEmpty()) continue
            val lengthScore = lengthPreference(bars, s.lengthBias)
            val harmonicCost = keyPenalty * s.keyWeight * bars / 8.0
            val stretchCost = abs(m.speed - 1) / max(0.01, s.maxStretch) * bars / 20.0
            for (e in exits) {
                val endA = e.time + len
                if (endA > sa.musicEnd + barA || endA > a.contentEnd + 0.01 || endA > ga.last + ga.period + 0.01) continue
                if (!keepsLastLoud(sa, s, e.time, len, 0.75, barA)) continue
                val lost = sa.loudCut(a, endA) * if (s.djMode) 0.08 else 0.6
                val endBar = a.barAt(endA + 0.05)
                val structure = if (s.djMode) a.sectionStrength(endBar) * 2.0 else 0.0
                val scoreA = e.score + lengthScore - harmonicCost - stretchCost - lost + structure -
                    max(0.0, endA - sa.musicEnd) / barA * 0.5
                val relativeA = sa.levelAt(a, e.time, endA)
                for (n in prepared) {
                    val co = if (sa.maxVoice == 0f || sb.maxVoice == 0f) CoVocal.NONE else coVocal(a, e.time, endA, b, n.cue.time, n.end)
                    val runBars = co.run / barA
                    // Two voices really on top of each other for more than 4 bars: not a long mix.
                    // Voices in different parts of the overlap (B's beat under A's verse) are fine.
                    if (bars >= 8 && runBars > 4.0) continue
                    val score = scoreA + n.score - co.total / barA * 0.35 -
                        max(0.0, relativeA + n.relativeLevel) * 0.08 * bars / 8.0
                    if (score < MIN_BLEND_SCORE) continue
                    if (best == null || score > best.score) best = Choice(e, n.cue, bars, score)
                    // Length is a real preference among safe candidates, not a penalty that a drop
                    // can accidentally outweigh. Known clashes/high vocal risk may still shorten it.
                    val safeLong = (s.keyWeight == 0.0 || keyPenalty < 1.5) && runBars <= 2.0 &&
                        abs(m.speed - 1) <= max(0.01, s.maxStretch * 0.85)
                    if (bars >= s.minBars && safeLong && (preferred == null || score > preferred.score)) {
                        preferred = Choice(e, n.cue, bars, score)
                    }
                }
            }
        }
        val c = preferred ?: best ?: return null
        val swapBar = bassSwapBar(b, sb, gb, c.entry.time, c.bars, barA, m.speed)
        val keyNote = if (knownKey(a) && knownKey(b)) keyText(keyPenalty) else "unknown (not penalised)"
        return MixPlan(MixPlan.Style.BLEND, c.exit.time, c.entry.time, c.bars * barA, m.speed, c.bars * 4,
            swapBar / c.bars, 1.0 / (c.bars * 4), matchGain(a, b), restore(m.speed, gb.period), matchA = matchGainA(a, b),
            note = String.format("%s, %s, %.1f→%.1f BPM, key %s, score %.1f", c.exit.why, c.entry.why, ga.bpm, gb.bpm, keyNote, c.score))
    }

    private fun lengthPreference(bars: Int, bias: Int): Double {
        val base = when (bars) { 32 -> 2.2; 24 -> 2.0; 16 -> 1.8; 12 -> 1.4; 8 -> 1.2; 6 -> 0.6; 4 -> 0.4; else -> 0.0 }
        return base + bias * ((min(bars, 16) - 8) / 8.0 + max(0, bars - 16) / 16.0) * 1.6
    }

    /** Bass changes on a shared downbeat, near a local arrival when one exists, otherwise halfway. */
    private fun bassSwapBar(b: TrackAnalysis, sb: Summary, gb: Grid, entry: Double, bars: Int, barA: Double, speed: Double): Double {
        var chosen = if (bars >= 4) floor(bars / 2.0) else bars / 2.0
        var score = 0.0
        for (k in max(1, ceil(bars * 0.25).toInt())..floor(bars * 0.75).toInt()) {
            val t = entry + k * barA * speed
            if (abs(firstDown(gb, b.headDown, t - 0.1 * gb.period) - t) > 0.1 * gb.period) continue
            val value = sb.dropAt(b, t) * 1.5 + b.sectionStrength(b.barAt(t + 0.05)) - abs(k.toDouble() / bars - 0.5) * 0.35
            if (value > score) { score = value; chosen = k.toDouble() }
        }
        return chosen
    }

    private fun cut(a: TrackAnalysis, b: TrackAnalysis, sa: Summary, sb: Summary, ga: Grid, gb: Grid?, m: TempoMatch?,
                    s: Settings, earliest: Double): MixPlan? {
        val exits = exitCues(a, sa, ga, s, earliest).filter { it.time + 2 * ga.period <= a.contentEnd + 0.01 }
            .let { all -> all.filter { keepsLastLoud(sa, s, it.time, 0.0, 0.0, 4 * ga.period, 2) }.ifEmpty { all } }
        if (exits.isEmpty()) return null
        val entry = if (gb != null && !b.headDown.isNaN()) entryCues(b, sb, gb).maxBy { it.score }.time else b.contentStart
        val e = exits.maxBy { it.score - sa.loudCut(a, it.time + 2 * ga.period) * if (s.djMode) 0.08 else 0.6 }
        val synced = m != null && abs(m.speed - 1) <= s.maxStretch
        val speed = if (synced) m!!.speed else 1.0
        return MixPlan(MixPlan.Style.CUT, e.time, entry, 2 * ga.period, speed, 2, 0.0, 0.5, matchGain(a, b),
            if (synced) restore(speed, gb!!.period) else 0.0, "cut on ${e.why}" + if (synced) "" else ", no common tempo")
    }

    /**
     * Between tempos too far apart to match: B comes in without its bass and with its top end held
     * back, unsynced, the bass changes hands on a downbeat of B about 60 % of the way, A's top end
     * closes as it goes. Exits and entries are the blend's (A's phrases, sections and outro; B's first
     * downbeat or an intro phrase), chosen so that two voices do not run together.
     */
    private fun filterFade(a: TrackAnalysis, b: TrackAnalysis, sa: Summary, sb: Summary, ga: Grid, gb: Grid?,
                           s: Settings, earliest: Double): MixPlan? {
        val barA = 4 * ga.period
        val exits = exitCues(a, sa, ga, s, earliest)
        val entries = if (gb != null) entryCues(b, sb, gb) else listOf(Cue(b.contentStart, 1.0, "B start"))
        if (exits.isEmpty()) return null
        val wanted = s.filterSec.coerceAtLeast(2.0)
        var best: Pair<Double, MixPlan>? = null
        for (len in doubleArrayOf(wanted, max(4.0, wanted * 0.6))) {
            for (e in exits) {
                val endA = e.time + len
                if (endA > a.contentEnd + 0.01 || endA > sa.musicEnd + barA) continue
                if (!keepsLastLoud(sa, s, e.time, len, 0.8, barA)) continue
                val scoreA = e.score - sa.loudCut(a, endA) * (if (s.djMode) 0.08 else 0.6) -
                    (if (len < wanted) 0.6 else 0.0)
                for (n in entries) {
                    val endB = n.time + len
                    if (endB > b.contentEnd - 10) continue
                    val co = if (sa.maxVoice == 0f || sb.maxVoice == 0f) CoVocal.NONE else coVocal(a, e.time, endA, b, n.time, endB)
                    if (co.run > len * 0.5) continue
                    val score = scoreA + n.score - co.total / len * 3.0
                    if (best != null && score <= best.first) continue
                    // B's bass on its own downbeat near 60 %, as a share of the fade.
                    val swapT = if (gb != null) firstDown(gb, b.headDown, n.time + len * 0.6 - 2 * gb.period) else n.time + len * 0.6
                    val swap = ((swapT - n.time) / len).coerceIn(0.4, 0.8)
                    best = score to MixPlan(MixPlan.Style.FADE, e.time, n.time, len, 1.0, 0, swap, (0.4 / len).coerceAtMost(0.1),
                        matchGain(a, b), 0.0, String.format("filtered fade: %s, %s, %.1f→%.1f BPM, score %.1f", e.why, n.why,
                            ga.bpm, gb?.bpm ?: b.bpm, score), matchA = matchGainA(a, b), filtered = true)
                }
            }
            if (best != null) break
        }
        return best?.second
    }

    private fun fade(a: TrackAnalysis, b: TrackAnalysis, sa: Summary, why: String, seconds: Double, earliest: Double): MixPlan? {
        val minExit = max(a.contentStart, earliest)
        val wanted = min(seconds, (a.contentEnd - a.contentStart) / 4).coerceAtLeast(0.05)
        val target = max(minExit, sa.musicEnd - wanted)
        val exit = a.barStarts.lastOrNull { it <= target && it >= max(minExit, target - 6) } ?: target
        val gb = b.head?.takeIf { !b.headDown.isNaN() }
        val entry = if (gb != null) firstDown(gb, b.headDown, b.contentStart - 0.08) else b.contentStart
        val len = minOf(wanted, a.contentEnd - exit, b.contentEnd - entry)
        if (len <= 0.05) return null
        return MixPlan(MixPlan.Style.FADE, exit, entry, len, 1.0, 0, 0.5, 0.1, matchGain(a, b), 0.0,
            "fade: $why", matchA = matchGainA(a, b))
    }

    private fun exitCues(a: TrackAnalysis, sa: Summary, g: Grid, s: Settings, earliest: Double): List<Cue> {
        val end = sa.musicEnd
        // Session DJ: B may come in under A's last loud section, but A is not let go before its end.
        val longest = min(s.maxSec, max(2, s.maxBars) * 4 * g.period)
        val from = maxOf(a.contentStart + (end - a.contentStart) * 0.5, end - if (s.djMode) 180 else 90, g.first, earliest,
            if (s.djMode) sa.lastLoudEnd - longest * 0.75 - 8 * 4 * g.period else 0.0)
        val to = min(end, g.last) - 2 * g.period
        val out = ArrayList<Cue>()
        var t = firstDown(g, a.tailDown, from)
        while (t <= to) {
            val bar = a.barAt(t + 0.05)
            var score = 0.0
            var label = "A bar"
            if (bar >= 0) {
                val pos = a.phrasePos(bar)
                score += when (pos) { 0 -> 1.4; 4 -> 0.6; else -> 0.0 }
                val section = a.sectionStrength(bar)
                score += section * if (s.djMode) 2.0 else 0.8
                score += a.barNovelty.getOrElse(bar) { 0f }.toDouble().coerceIn(0.0, 3.0) * 0.25
                val before = sa.levelBars(bar - 4, bar); val after = sa.levelBars(bar, bar + 4)
                if (before - after > 3) { score += 1.5; label = "A outro" }
                else if (section > 0) label = "A section"
                else if (pos == 0) label = "A phrase"
                else if (pos == 4) label = "A half phrase"
                if (after < sa.loud - 12) score -= 0.5
            }
            score += (t - from) / max(1.0, to - from) * if (s.djMode) 0.25 else 1.5
            out.add(Cue(t, score, label))
            t += 4 * g.period
        }
        return out
    }

    private fun drops(b: TrackAnalysis, sb: Summary, g: Grid): List<Double> {
        val out = ArrayList<Double>()
        var t = firstDown(g, b.headDown, b.contentStart + 4 * g.period)
        while (t < b.contentStart + 96 && t < g.last) { if (sb.dropAt(b, t) >= 0.5) out.add(t); t += 4 * g.period }
        return out
    }

    private fun dropEntries(b: TrackAnalysis, g: Grid, drops: List<Double>, lengthB: Double): List<Cue> =
        drops.mapNotNull { d ->
            val n = d - lengthB
            if (n < b.contentStart - 0.1) return@mapNotNull null
            val down = firstDown(g, b.headDown, n - 2 * g.period)
            if (abs(down - n) > 0.1 * g.period) null
            else Cue(down, 1.5 - (down - b.contentStart) / 25.0, "B from", d)
        }

    private fun entryCues(b: TrackAnalysis, sb: Summary, g: Grid): List<Cue> {
        val first = firstDown(g, b.headDown, b.contentStart - 0.08)
        val out = arrayListOf(Cue(first, 1.0, "B first downbeat"))
        var t = first + 4 * g.period
        while (t < b.contentStart + 40 && t < b.contentEnd / 3 && t < g.last - 8 * g.period) {
            val bar = b.barAt(t + 0.05)
            if (bar >= 0 && (b.phrasePos(bar) == 0 || b.sectionStrength(bar) > 0)) {
                val quiet = sb.levelBars(0, bar) < sb.loud - 9
                out.add(Cue(t, 1.5 + b.sectionStrength(bar) * 0.5 - (t - b.contentStart) / (if (quiet) 30.0 else 12.0), "B phrase"))
            }
            t += 4 * g.period
        }
        return out
    }

    fun musicEnd(a: TrackAnalysis): Double = summary(a).musicEnd
    /** Where Session DJ may start leaving A at the earliest: its last loud section kept. */
    fun lastLoudEnd(a: TrackAnalysis): Double = summary(a).lastLoudEnd
    /**
     * Session DJ leaves A early only after its last loud section: A is still clearly heard ([release],
     * share of the mix where its fade is well under way) until at most [bars] bars before that section ends.
     */
    private fun keepsLastLoud(sa: Summary, s: Settings, exit: Double, len: Double, release: Double, bar: Double, bars: Int = 8) =
        !s.djMode || exit + len * release >= sa.lastLoudEnd - bars * bar

    fun firstDown(g: Grid, down: Double, from: Double): Double {
        val bar = 4 * g.period
        return down + ceil((from - down) / bar - 1e-6) * bar
    }

    /**
     * Cheap per-song quantities, once per immutable analysis: no sorting, powers, whole-song scans
     * or level windows in the candidate loop. Values do not reference the weak map's key.
     */
    private class Summary(a: TrackAnalysis) {
        val loud = a.barDb.sortedArray().let { if (it.isEmpty()) -20.0 else it[(it.size * 0.7).toInt()].toDouble() }
        private val level = prefix(a.barDb)
        private val low = prefix(a.barLowDb)
        private val high = prefix(a.barHighDb)
        private val loudTail = IntArray(a.bars + 1)
        private val drops = DoubleArray(a.bars)
        val maxVoice = a.barVocalRisk.maxOrNull() ?: 0f
        val noveltyMean = a.barNovelty.average().takeIf { it > 0 } ?: 0.0
        val musicEnd: Double
        /** End of A's final loud section (at least 4 loud bars: its last chorus, or its last verse). */
        val lastLoudEnd: Double
        init {
            var last = -1
            for (i in a.bars - 1 downTo 0) {
                loudTail[i] = loudTail[i + 1] + if (a.barDb[i] > loud - 4) 1 else 0
                if (last < 0 && a.barDb[i] > loud - 15) last = i
            }
            musicEnd = if (last < 0) a.contentEnd else min(a.contentEnd, a.barEnd(last))
            val hot = BooleanArray(a.bars) { a.barDb[it] >= loud - 3 }
            fun hot(i: Int) = i in 0 until a.bars && (hot[i] || (i > 0 && i + 1 < a.bars && hot[i - 1] && hot[i + 1]))
            var keep = a.contentStart
            for (i in a.bars - 1 downTo 3) if ((i - 3..i).all(::hot)) { keep = a.barEnd(i); break }
            lastLoudEnd = keep
            for (i in 2 until a.bars) {
                val db = levelBars(i, i + 4) - levelBars(i - 4, i)
                val bass = mean(low, i, i + 4) - mean(low, i - 4, i)
                drops[i] = ((max(0.0, db) + 0.5 * max(0.0, bass)) / 4).coerceIn(0.0, 2.0)
            }
        }
        fun levelBars(from: Int, to: Int): Double = mean(level, from, to)
        fun levelAt(a: TrackAnalysis, from: Double, to: Double): Double {
            val i = max(0, a.barAt(from)); val j = max(i + 1, a.barAt(to))
            return levelBars(i, j) - loud
        }
        fun loudCut(a: TrackAnalysis, end: Double): Int = loudTail[(a.barAt(end) + 1).coerceIn(0, a.bars)]
        fun softTexture(a: TrackAnalysis, from: Double, to: Double): Boolean {
            val lo = max(0, a.barAt(from)); val hi = max(lo + 1, a.barAt(to)).coerceAtMost(a.bars)
            if (hi <= lo) return false
            val db = levelBars(lo, hi)
            if (mean(low, lo, hi) - db > -15 || mean(high, lo, hi) - db > -12) return false
            var voice = 0.0
            for (i in lo until hi) voice += a.barVocalRisk.getOrElse(i) { 0f }
            return voice / (hi - lo) < 0.35
        }
        fun dropAt(a: TrackAnalysis, time: Double): Double {
            val i = nearestBar(a, time)
            if (i < 2) return 0.0
            val bar = if (i + 1 < a.bars) a.barStarts[i + 1] - a.barStarts[i] else 2.0
            return if (abs(a.barStarts[i] - time) > bar / 2) 0.0 else drops[i]
        }
        private fun prefix(x: FloatArray): DoubleArray = DoubleArray(x.size + 1).also { out ->
            for (i in x.indices) out[i + 1] = out[i] + 10.0.pow(x[i] / 10.0)
        }
        private fun mean(p: DoubleArray, from: Int, to: Int): Double {
            val lo = from.coerceIn(0, p.lastIndex); val hi = to.coerceIn(lo, p.lastIndex)
            return if (hi == lo) -60.0 else 10 * Math.log10(((p[hi] - p[lo]) / (hi - lo)).coerceAtLeast(1e-12))
        }
    }
    private val summaries = WeakHashMap<TrackAnalysis, Summary>()
    private fun summary(a: TrackAnalysis): Summary = synchronized(summaries) { summaries.getOrPut(a) { Summary(a) } }

    private fun nearestBar(a: TrackAnalysis, t: Double): Int {
        val i = a.barAt(t)
        if (i < 0) return if (a.bars == 0) -1 else 0
        return if (i + 1 < a.bars && t - a.barStarts[i] > a.barStarts[i + 1] - t) i + 1 else i
    }

    private fun changeNear(b: TrackAnalysis, sb: Summary, t: Double, bar: Double): Double {
        val i = b.barAt(t + 0.05)
        if (i < 0 || sb.noveltyMean <= 0) return 0.0
        var top = 0.0
        for (j in max(0, i - 1)..min(b.bars - 1, i + 1)) {
            if (abs(b.barStarts[j] - t) <= bar * 1.01) top = max(top, b.barNovelty[j] / sb.noveltyMean)
        }
        return (top - 1).coerceIn(0.0, 2.0)
    }

    /**
     * Voice-like activity at the same audible moments, after B's time mapping. Integrates over bar
     * boundaries, with less weight at the very quiet ends of the blend. Mono uses its own risk map.
     */
    internal fun vocalOverlap(a: TrackAnalysis, a0: Double, a1: Double, b: TrackAnalysis, b0: Double, b1: Double): Double {
        if (a1 <= a0 || b1 <= b0 || a.bars == 0 || b.bars == 0) return 0.0
        val speed = (b1 - b0) / (a1 - a0)
        var t = a0
        var overlap = 0.0; var weight = 0.0
        while (t < a1 - 1e-6) {
            val ia = a.barAt(t + 1e-6); val ib = b.barAt(b0 + (t - a0) * speed + 1e-6)
            if (ia < 0 || ib < 0) return 0.0
            val to = minOf(a1, a.barEnd(ia), a0 + (b.barEnd(ib) - b0) / speed)
            if (to <= t + 1e-7) break
            val x = ((t + to) * 0.5 - a0) / (a1 - a0)
            val audible = 4 * x * (1 - x)
            val w = (to - t) * audible
            val va = a.barVocalRisk.getOrElse(ia) { 0f }.toDouble().coerceIn(0.0, 1.0)
            val vb = b.barVocalRisk.getOrElse(ib) { 0f }.toDouble().coerceIn(0.0, 1.0)
            overlap += va * vb * w; weight += w
            t = to
        }
        return if (weight > 0) overlap / weight else 0.0
    }

    internal class CoVocal(val run: Double, val total: Double) { companion object { val NONE = CoVocal(0.0, 0.0) } }

    /**
     * Where two voices are really heard together: bars of A and of B (after B's entry and stretch)
     * both over [VOICE], in the audible middle of the mix (15–85 %). [CoVocal.run] is the longest
     * such stretch and [CoVocal.total] their sum, in A's seconds.
     */
    internal fun coVocal(a: TrackAnalysis, a0: Double, a1: Double, b: TrackAnalysis, b0: Double, b1: Double): CoVocal {
        if (a1 <= a0 || b1 <= b0 || a.bars == 0 || b.bars == 0) return CoVocal.NONE
        val speed = (b1 - b0) / (a1 - a0)
        val lo = a0 + (a1 - a0) * 0.15; val hi = a0 + (a1 - a0) * 0.85
        var t = lo; var run = 0.0; var top = 0.0; var total = 0.0
        while (t < hi - 1e-6) {
            val ia = a.barAt(t + 1e-6); val ib = b.barAt(b0 + (t - a0) * speed + 1e-6)
            if (ia < 0 || ib < 0) break
            val to = minOf(hi, a.barEnd(ia), a0 + (b.barEnd(ib) - b0) / speed)
            if (to <= t + 1e-7) break
            if (a.barVocalRisk.getOrElse(ia) { 0f } >= VOICE && b.barVocalRisk.getOrElse(ib) { 0f } >= VOICE) {
                run += to - t; total += to - t; top = max(top, run)
            } else run = 0.0
            t = to
        }
        return CoVocal(top, total)
    }

    private fun knownKey(a: TrackAnalysis): Boolean = a.key >= 0 && a.keyStrength >= 0.5

    /** Same/neighbouring Camelot keys: 0; two steps: 1; further: 2. Unknown carries no clash evidence. */
    fun keyDistance(a: TrackAnalysis, b: TrackAnalysis): Double {
        if (!knownKey(a) || !knownKey(b)) return 0.0
        val (na, la) = camelot(a.key); val (nb, lb) = camelot(b.key)
        val d = min(Math.floorMod(na - nb, 12), Math.floorMod(nb - na, 12)) + if (la != lb) 1 else 0
        return when { d <= 1 -> 0.0; d == 2 -> 1.0; else -> 2.0 }
    }

    fun camelot(key: Int): Pair<Int, Boolean> {
        val major = key < 12
        val tonic = if (major) key else Math.floorMod(key - 12 + 3, 12)
        return (Math.floorMod(tonic * 7 + 7, 12) + 1) to major
    }

    private fun keyText(d: Double) = when (d) { 0.0 -> "compatible"; 1.0 -> "near"; else -> "clash" }

    private fun matchGain(a: TrackAnalysis, b: TrackAnalysis): Double {
        val d = a.loudnessDb - b.loudnessDb
        return if (d < -1.5) 10.0.pow(max(d, -6.0) / 20) else 1.0
    }

    private fun matchGainA(a: TrackAnalysis, b: TrackAnalysis): Double {
        val d = a.loudnessDb - b.loudnessDb
        return if (d > 1.5) 10.0.pow(-min(d, 6.0) / 20) else 1.0
    }

    private fun restore(speed: Double, period: Double): Double {
        val beats = max(8.0, abs(speed - 1) / if (abs(speed - 1) > WIDE_STRETCH) 0.004 else 0.003)
        return if (abs(speed - 1) < 1e-4) 0.0 else (beats * period).let { (it * 10).roundToInt() / 10.0 }
    }
}
