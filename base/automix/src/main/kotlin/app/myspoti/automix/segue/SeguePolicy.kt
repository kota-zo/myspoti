/* SPDX-License-Identifier: GPL-3.0-only */
package app.myspoti.automix.segue

import app.myspoti.automix.Grid
import app.myspoti.automix.MixPlan
import app.myspoti.automix.MixPlanner
import app.myspoti.automix.TrackAnalysis
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** The fallback order. Normal playback is not a failure: no effect beats a bad effect. */
enum class SegueLevel(val cls: SegueClass?) {
    LOCKED_LONG(SegueClass.LOCKED),
    LOCKED_SHORT(SegueClass.LOCKED),
    LOCKED_BEAT(SegueClass.LOCKED),
    FILTERED(SegueClass.FILTERED),
    HARD(SegueClass.HARD),
    SIMPLE_FADE(SegueClass.FADE),
    NORMAL(null),
}

enum class SegueCause { NO_GRID, TEMPO_OUT_OF_REACH, BASS_NOT_MANAGED, LENGTH_NOT_ALLOWED, NO_CANDIDATE, GUARDS, BELOW_THRESHOLD, INVALID_PLAN, ALBUM_CONTINUITY }

/** Why a level was not used: every level above the chosen one has one. */
data class SegueAttempt(val level: SegueLevel, val cause: SegueCause, val detail: String = "") {
    override fun toString() = if (detail.isEmpty()) "$level: $cause" else "$level: $cause ($detail)"
}

/** The policy's answer: the level, the plan (none for normal playback), and why the levels above failed. */
class SegueDecision(
    val level: SegueLevel,
    val plan: SeguePlan?,
    val attempts: List<SegueAttempt>,
    val score: Double? = null,
    /** Where the bass changes hands, 0..1 of the window. */
    val swapShare: Double = 0.5,
    /** Seconds the incoming song takes to return to its own tempo. */
    val restoreSec: Double = 0.0,
    /** How the transition sounds, for the journal: the plan's lanes are what plays. */
    val shape: SegueShape? = null,
) {
    val why: String get() = attempts.joinToString("; ")
    val fingerprint: String? get() = plan?.let(SegueCodec::fingerprint)
}

class SegueRequest(
    val a: TrackAnalysis,
    val b: TrackAnalysis,
    /** Nothing may begin before this point of A: its preparation lead is already counted. */
    val earliestExitUs: Long = 0,
    val listenerRate: Long = SegueFormat.RATE_NOMINAL,
    /** The listener's settings may only tighten the guards. */
    val maxRatePpm: Long? = null,
    val maxBars: Int? = null,
    val eq: Boolean = true,
    val harmonic: Boolean = true,
    /** Both songs are on one album: a junction without silence is left to play as it is. */
    val sameAlbum: Boolean = false,
    val tuning: SegueTuning = SegueTuning(),
)

/**
 * Generates candidates, rejects by guards, scores, keeps the best if it clears its class's threshold,
 * otherwise moves to the next level. Deterministic: the same request gives the same plan.
 */
object SeguePolicy {
    val ORIGIN = SeguePlan.Origin("myspoti-segue-2", "uncalibrated")

    fun decide(r: SegueRequest): SegueDecision = Run(r).decide()

    /** The plan's timing as the players need it. */
    fun timing(d: SegueDecision): MixPlan? {
        val p = d.plan ?: return null
        val style = when (d.level.cls) {
            SegueClass.LOCKED -> MixPlan.Style.BLEND
            SegueClass.HARD -> MixPlan.Style.CUT
            else -> MixPlan.Style.FADE
        }
        val rate = p.incoming.ratePpm.toDouble() / SegueFormat.RATE_NOMINAL
        val beats = if (p.span.unitKind == UnitKind.BAR) (p.span.musicalUnits ?: 0) * 4 else p.span.musicalUnits ?: 0
        return MixPlan(style, p.outgoing.anchorUs / 1e6, p.incoming.anchorUs / 1e6, p.span.lengthUs / 1e6, rate, beats,
            d.swapShare, if (beats > 0) 1.0 / beats else 0.1, 1.0, d.restoreSec,
            "segue ${d.level}" + (d.shape?.let { " $it" } ?: "") + (d.score?.let { String.format(java.util.Locale.ROOT, " score %.2f", it) } ?: "") +
                (if (d.attempts.isEmpty()) "" else " | ${d.why}"),
            filtered = d.level == SegueLevel.FILTERED, segue = p)
    }

    /** Checks a plan against the analyses it must match; a non-empty answer means it is recomputed. */
    fun stale(p: SeguePlan, a: TrackAnalysis, b: TrackAnalysis): List<SegueViolation> {
        val (fa, fb) = facts(a, b)
        return SegueValidator.stale(p, fa, fb)
    }

    internal fun facts(a: TrackAnalysis, b: TrackAnalysis) =
        SegueSideFacts(a.audioKey, a.beatKey, us(a.duration)) to SegueSideFacts(b.audioKey, b.beatKey, us(b.duration))

    private fun us(seconds: Double): Long = (seconds * 1e6).roundToLong()

    private class Point(val us: Long, val bar: Int, val rank: Double, val boundary: Boolean)

    /** [score] decides whether a level is good enough; [pick] decides between candidates of one level. */
    private class Found(val plan: SeguePlan, val score: Double, val swap: Double, val shape: SegueShape? = null, val restoreSec: Double = 0.0, val pick: Double = score)

    private class Run(val r: SegueRequest) {
        val a = r.a; val b = r.b; val t = r.tuning
        val sa = MixPlanner.summary(a); val sb = MixPlanner.summary(b)
        val compiler = SegueCompiler(t)
        val ga: Grid? = a.tail?.takeIf { !a.tailDown.isNaN() }
        val gb: Grid? = b.head?.takeIf { !b.headDown.isNaN() }
        val attempts = ArrayList<SegueAttempt>()
        val keyDistance = if (r.harmonic) MixPlanner.keyDistance(a, b) else 0.0
        val zoneStartUs = us(a.contentStart + (sa.musicEnd - a.contentStart) * (1 - t.exitZoneShare))

        fun decide(): SegueDecision {
            if (albumContinuity()) {
                val out = SegueLevel.values().filter { it != SegueLevel.NORMAL }.map { SegueAttempt(it, SegueCause.ALBUM_CONTINUITY) }
                return SegueDecision(SegueLevel.NORMAL, null, out)
            }
            for (level in SegueLevel.values()) {
                if (level == SegueLevel.NORMAL) break
                val found = attempt(level) ?: continue
                val problems = SegueValidator.against(found.plan, facts(a, b).first, facts(a, b).second)
                if (problems.isNotEmpty()) { attempts += SegueAttempt(level, SegueCause.INVALID_PLAN, problems.joinToString(",")); continue }
                return SegueDecision(level, found.plan, attempts.toList(), found.score, found.swap, found.restoreSec, found.shape)
            }
            return SegueDecision(SegueLevel.NORMAL, null, attempts.toList())
        }

        private fun albumContinuity(): Boolean {
            if (!r.sameAlbum) return false
            val silenceA = us(a.duration - a.contentEnd); val silenceB = us(b.contentStart)
            return silenceA <= t.gaplessMaxSilenceUs && silenceB <= t.gaplessMaxSilenceUs
        }

        private fun fail(level: SegueLevel, cause: SegueCause, detail: String = ""): Found? {
            attempts += SegueAttempt(level, cause, detail); return null
        }

        private fun attempt(level: SegueLevel): Found? = when (level) {
            SegueLevel.LOCKED_LONG -> locked(level, listOf(t.lockedLongBars), phrases = true)
            SegueLevel.LOCKED_SHORT -> locked(level, listOf(t.lockedMiddleBars, t.lockedShortBars), phrases = true)
            SegueLevel.LOCKED_BEAT -> if (!r.eq) fail(level, SegueCause.BASS_NOT_MANAGED) else locked(level, listOf(t.lockedShortBars, t.lockedTinyBars), phrases = false)
            SegueLevel.FILTERED -> filtered(level)
            SegueLevel.HARD -> hard(level)
            SegueLevel.SIMPLE_FADE -> simple(level)
            SegueLevel.NORMAL -> null
        }

        // ── Reliability: an uncertain measure weighs less ──
        private fun tempoReliability(g: Grid?): Double = g?.steadiness?.coerceIn(0.0, 1.0) ?: 0.0
        private fun pulseReliability() = min(min(tempoReliability(ga), tempoReliability(gb)), min(a.tailMetre, b.headMetre).toDouble())
        private fun structureReliability(s: TrackAnalysis, bar: Int) = max(s.sectionStrength(bar), t.reliabilityPhraseFallback)
        private fun vocalReliability() = when {
            a.voiceKnown && b.voiceKnown -> 1.0
            a.stereo && b.stereo -> t.reliabilityVocalStereo
            else -> t.reliabilityVocalMono
        }
        private fun keyReliability() = if (a.key >= 0 && b.key >= 0) min(a.keyStrength, b.keyStrength).coerceIn(0.0, 1.0) else 0.0

        private fun reliable(g: Grid?) = g != null && g.count >= t.gridMinBeats && g.steadiness >= t.gridMinSteadiness && g.driftMs <= t.gridMaxDriftMs

        // ── Candidates ──
        private fun boundary(s: TrackAnalysis, bar: Int) = bar >= 0 && (s.phrasePos(bar) == 0 || s.sectionStrength(bar) > 0)

        /** A's points where a window of [lengthUs] fits, instrumental endings first. */
        private fun exits(times: Sequence<Double>, lengthUs: Long, limitUs: Long, phrases: Boolean): List<Point> {
            val zone = max(zoneStartUs, r.earliestExitUs)
            val all = times.map { it to us(it) }.filter { (_, u) -> u >= zone && u + lengthUs <= limitUs }.mapNotNull { (time, u) ->
                val bar = a.barAt(time + 0.05)
                val on = boundary(a, bar)
                if (phrases && !on) null else Point(u, bar, instrumentalAfter(bar) + u.toDouble() / max(1L, limitUs) * 1e-3, on)
            }.toList()
            // The latest ones stay in the running even when a voice lasts to the end: the score weighs that against what leaving early loses.
            return (all.sortedByDescending { it.rank }.take(t.candidatesPerSide) + all.sortedByDescending { it.us }.take(t.lateCandidates)).distinctBy { it.us }
        }

        private fun instrumentalAfter(bar: Int): Double {
            if (a.bars == 0 || bar < 0) return 0.0
            var risk = 0.0; var n = 0
            for (i in bar until a.bars) { risk += a.barVocalRisk.getOrElse(i) { 0f }; n++ }
            return 1 - risk / max(1, n)
        }

        private fun gridTimes(g: Grid, down: Double, from: Double, to: Double): Sequence<Double> {
            val bar = 4 * g.period
            return generateSequence(MixPlanner.firstDown(g, down, from)) { it + bar }.takeWhile { it <= to }
        }

        /** B's first downbeat at its attack, never before the song's start. */
        private fun firstEntry(g: Grid?): Double =
            if (g != null) MixPlanner.firstDown(g, b.headDown, max(0.0, b.contentStart - 0.08)).coerceAtLeast(0.0) else b.contentStart

        /** B's useful attack, its first boundaries; leaving out a start must be worth its note. */
        private fun entries(g: Grid?, spanUs: Long, phrases: Boolean): List<Point> {
            val limit = us(b.contentEnd)
            val startUs = us(b.contentStart)
            val first = firstEntry(g)
            val out = ArrayList<Point>()
            out += Point(us(first), b.barAt(first + 0.05), 1.0, true)
            if (g != null) {
                val zone = min(us(b.contentStart) + t.entryZoneUs, limit / 3)
                var at = first + 4 * g.period
                while (us(at) < zone && at < g.last) {
                    val bar = b.barAt(at + 0.05)
                    val on = boundary(b, bar)
                    if (on || !phrases) {
                        val base = if (b.sectionStrength(bar) > 0) t.entryNoteSection else t.entryNoteOther
                        out += Point(us(at), bar, base * (1 - (us(at) - startUs).toDouble() / t.entryZoneUs), on)
                    }
                    at += 4 * g.period
                }
            }
            val last = if (g != null) min(limit, us(g.last + g.period)) else limit
            return out.filter { it.us + spanUs <= last }.sortedByDescending { it.rank }.take(t.candidatesPerSide)
        }

        // ── Levels ──
        private fun locked(level: SegueLevel, barsAsked: List<Int>, phrases: Boolean): Found? {
            val allowed = barsAsked.filter { it <= (r.maxBars ?: Int.MAX_VALUE) }
            if (allowed.isEmpty()) return fail(level, SegueCause.LENGTH_NOT_ALLOWED)
            val limit = min(t.maxRateDeviationPpm, r.maxRatePpm ?: Long.MAX_VALUE)
            val tally = Tally()
            var best: Found? = null
            var closestPpm = Long.MAX_VALUE
            var tooLong = false
            var stretches = false
            for (bars in allowed) {
                val outs = stretchExits(bars, phrases, tally)
                stretches = stretches || outs.isNotEmpty()
                // The incoming bars of the same length of time: as many, or half or twice as many between half and double tempos.
                for ((barsB, m) in listOf(bars to 1.0, bars / 2 to 2.0, bars * 2 to 0.5)) {
                    if (m != 1.0 && (bars > t.halfDoubleMaxBars || barsB < t.lockedTinyBars || barsB * 2 != bars * (if (m == 2.0) 1 else 4))) continue
                    val ins = stretchEntries(barsB, phrases, tally)
                    for (e in outs) for (n in ins) {
                        val rate = n.stretch.lengthSec / e.stretch.lengthSec
                        val ratePpm = (rate * SegueFormat.RATE_NOMINAL).roundToLong()
                        val deviation = abs(ratePpm - SegueFormat.RATE_NOMINAL)
                        closestPpm = min(closestPpm, deviation)
                        if (deviation > limit) continue
                        // The lower the reliability, the shorter the window.
                        val pulse = min(e.stretch.reliability, n.stretch.reliability)
                        val cap = if (pulse >= t.reliabilityForLong) t.lockedLongBars else if (pulse >= t.reliabilityForMiddle) t.lockedMiddleBars else t.lockedShortBars
                        if (bars > cap) { tooLong = true; continue }
                        tally.seen++
                        val lengthUs = us(e.stretch.lengthSec)
                        val spanUs = us(n.stretch.lengthSec)
                        val (phaseMedian, phaseP95) = Stretches.phase(e.stretch, n.stretch, rate) ?: (null to null)
                        val ga = e.stretch.grid; val gb = n.stretch.grid
                        val base = measures(e.point, n.point, lengthUs, spanUs).copy(rateDeviationPpm = deviation, phaseMedianUs = phaseMedian, phaseP95Us = phaseP95,
                            driftBeats = hypot(ga.driftMs, gb.driftMs) / 1000 / ga.period)
                        if (tally.refused(SegueGuards.crossed(SegueClass.LOCKED, base, t))) continue
                        val swap = MixPlanner.bassSwapBar(b, sb, gb, n.stretch.start, bars, 4 * ga.period, rate, gb.first) / bars
                        val beatShare = 1.0 / (bars * 4)
                        val shape = lockedShape(e.point, n.point, bars, barsB, swap, lengthUs, spanUs)
                        val lanes = compiler.locked(shape, swap, beatShare, matchA(), matchB(), r.eq)
                        val plan = plan(e.stretch.startUs, n.stretch.startUs, ga, gb, ratePpm, lengthUs, bars, PulseState.LOCKED, us60(ga), phaseMedian, lanes)
                            .copy(echoUs = if (shape == SegueShape.ECHO_TAIL) echoOf(ga.period) else null)
                        val score = score(e.point, n.point, lengthUs, spanUs, phaseMedian, deviation, lanes, pulse, bars, barsB)
                        val pick = score.total - t.lostPickLocked * skipped(e.point, n.point, lengthUs)
                        if (best == null || pick > best.pick) best = Found(plan, score.total, swap, shape, restore(ratePpm, gb.period), pick)
                    }
                }
                // The longest length that works: shorter only when it has to be.
                if (best != null && best.score >= t.thresholdLocked) break
            }
            if (tally.seen == 0) return when {
                !stretches -> fail(level, SegueCause.NO_GRID, tally.toString())
                tooLong -> fail(level, SegueCause.LENGTH_NOT_ALLOWED, "reliability")
                closestPpm == Long.MAX_VALUE -> fail(level, SegueCause.NO_CANDIDATE, tally.toString())
                else -> fail(level, SegueCause.TEMPO_OUT_OF_REACH, String.format(java.util.Locale.ROOT, "%.1f %%", closestPpm / 1e4))
            }
            return settle(level, tally, best, t.thresholdLocked)
        }

        /** A stretch and its place among the candidates. */
        private inner class Placed(val stretch: Stretch, val point: Point)

        private val outgoingStretches = HashMap<Long, Stretch?>()
        private val incomingStretches = HashMap<Long, Stretch?>()

        private fun cached(cache: HashMap<Long, Stretch?>, s: TrackAnalysis, bar: Int, bars: Int, metre: Double): Stretch? =
            cache.getOrPut(bar.toLong() * 1000 + bars) { Stretches.measure(s, bar, bars, metre, t) }

        /** A's stretches where a window fits, instrumental endings first. */
        private fun stretchExits(bars: Int, phrases: Boolean, tally: Tally): List<Placed> {
            val zone = max(zoneStartUs, r.earliestExitUs)
            val limit = us(a.contentEnd)
            val out = ArrayList<Placed>()
            for (bar in 0 until a.bars) {
                val start = us(a.barStarts[bar])
                if (start < zone - 50_000) continue
                val on = boundary(a, bar)
                if (phrases && !on) continue
                val s = cached(outgoingStretches, a, bar, bars, a.tailMetre.toDouble())
                if (s == null) { if (bar + bars < a.bars) tally.refused(listOf(SegueGuard.GRID_RELIABILITY)); continue }
                if (s.startUs < zone || us(s.end) > limit) continue
                out += Placed(s, Point(s.startUs, bar, instrumentalAfter(bar) + s.startUs.toDouble() / max(1L, limit) * 1e-3, on))
            }
            return (out.sortedByDescending { it.point.rank }.take(t.candidatesPerSide) + out.sortedByDescending { it.point.us }.take(t.lateCandidates)).distinctBy { it.point.us }
        }

        /** B's stretches from its useful attack; leaving out a start must be worth its note. */
        private fun stretchEntries(bars: Int, phrases: Boolean, tally: Tally): List<Placed> {
            val startUs = us(b.contentStart)
            val zone = min(startUs + t.entryZoneUs, us(b.contentEnd) / 3)
            val firstBar = (0 until b.bars).firstOrNull { b.barStarts[it] >= b.contentStart - 0.08 } ?: return emptyList()
            val out = ArrayList<Placed>()
            for (bar in firstBar until b.bars) {
                val at = us(b.barStarts[bar])
                if (bar != firstBar && at >= zone) break
                val on = boundary(b, bar)
                if (phrases && !on && bar != firstBar) continue
                val s = cached(incomingStretches, b, bar, bars, b.headMetre.toDouble())
                if (s == null) { if (bar + bars < b.bars) tally.refused(listOf(SegueGuard.GRID_RELIABILITY)); continue }
                val rank = if (bar == firstBar) 1.0
                    else (if (b.sectionStrength(bar) > 0) t.entryNoteSection else t.entryNoteOther) * (1 - (at - startUs).toDouble() / t.entryZoneUs)
                out += Placed(s, Point(s.startUs, bar, rank, on || bar == firstBar))
            }
            return out.sortedByDescending { it.point.rank }.take(t.candidatesPerSide)
        }

        // ── Shapes: what the music asks for first, then a choice that stays the same for the same pair ──
        private fun seeded(options: List<SegueShape>, exitUs: Long, entryUs: Long): SegueShape {
            var h = -3750763034362895579L
            for (c in "${a.audioKey}|${b.audioKey}|$exitUs|$entryUs") { h = (h xor c.code.toLong()) * 1099511628211L }
            return options[Math.floorMod(h, options.size.toLong()).toInt()]
        }

        private fun lockedShape(e: Point, n: Point, bars: Int, barsB: Int, swap: Double, lengthUs: Long, spanUs: Long): SegueShape {
            // A cut is only as good as what is on both sides of it: a much louder or quieter song is heard as a jump.
            val barA = e.bar + (bars * swap).toInt(); val barB = n.bar + (barsB * swap).toInt()
            val cuts = abs(sa.levelBars(max(0, barA - 2), barA) - sb.levelBars(barB, barB + 2)) <= t.loudnessJumpMaxDb
            if (!cuts) return seeded(if (r.eq && bars > t.lockedTinyBars) listOf(SegueShape.BASS_SWAP, SegueShape.THREE_BAND) else listOf(SegueShape.BASS_SWAP), e.us, n.us)
            if (!r.eq) return seeded(listOf(SegueShape.BASS_SWAP, SegueShape.CUT_ON_BEAT), e.us, n.us)
            // Two voices in the window: they must not meet, so one takes over from the other.
            val overlap = sa.maxVoice != 0f && sb.maxVoice != 0f &&
                MixPlanner.coVocal(a, e.us / 1e6, (e.us + lengthUs) / 1e6, b, n.us / 1e6, (n.us + spanUs) / 1e6).total > 0
            if (overlap && bars <= t.lockedMiddleBars) return SegueShape.CUT_ON_BEAT
            val tail = if (rings(e.us, lengthUs)) listOf(SegueShape.ECHO_TAIL) else emptyList()
            return seeded(when {
                bars <= t.lockedTinyBars -> listOf(SegueShape.CUT_ON_BEAT, SegueShape.BASS_SWAP)
                bars <= t.lockedShortBars -> listOf(SegueShape.BASS_SWAP, SegueShape.CUT_ON_BEAT, SegueShape.FILTER_OPEN) + tail
                bars <= t.lockedMiddleBars -> listOf(SegueShape.BASS_SWAP, SegueShape.THREE_BAND, SegueShape.CUT_ON_BEAT, SegueShape.FILTER_OPEN, SegueShape.RISE_OUT) + tail
                else -> listOf(SegueShape.BASS_SWAP, SegueShape.THREE_BAND, SegueShape.FILTER_OPEN, SegueShape.RISE_OUT) + tail
            }, e.us, n.us)
        }

        private fun filteredShape(e: Point, n: Point, lengthUs: Long): SegueShape {
            val ring = rings(e.us, lengthUs)
            val all = listOf(SegueShape.SWEEP_OUT, SegueShape.EVEN_FADE, SegueShape.QUICK_OUT, SegueShape.LATE_OUT) +
                (if (ring && r.eq && ga != null) listOf(SegueShape.ECHO_OUT) else emptyList()) +
                (if (ring && r.eq) listOf(SegueShape.WASH_OUT) else emptyList())
            // A voice still there late in the window leaves early; one coming early in the incoming song gets in clear.
            val endBar = a.barAt((e.us + lengthUs) / 1e6)
            val lateVoice = ((e.bar + endBar + 1) / 2..endBar).any { a.barVocalRisk.getOrElse(it) { 0f } >= t.voiceBarRisk }
            val earlyVoice = (n.bar until b.barAt((n.us + lengthUs / 2) / 1e6) + 1).any { b.barVocalRisk.getOrElse(it) { 0f } >= t.voiceBarRisk }
            return when {
                lateVoice -> if (ring && r.eq && ga != null) seeded(listOf(SegueShape.QUICK_OUT, SegueShape.ECHO_OUT), e.us, n.us) else SegueShape.QUICK_OUT
                earlyVoice -> SegueShape.LATE_OUT
                else -> seeded(all, e.us, n.us)
            }
        }

        /** Music the listener never hears, the end of one song and the start of the other, 0..1. */
        private fun skipped(e: Point, n: Point, lengthUs: Long): Double =
            ((max(0L, us(a.contentEnd) - (e.us + lengthUs)) + max(0L, n.us - us(b.contentStart))).toDouble() / t.lostFullUs).coerceAtMost(1.0)

        /** The outgoing song runs long enough past the window for a send to ring out. */
        private fun rings(exitUs: Long, lengthUs: Long) = us(a.duration) - (exitUs + lengthUs) >= t.sendTailUs

        /** A delay in time with [period] seconds a beat: three quarters of a beat, or as near as stays usable. */
        private fun echoOf(period: Double): Long {
            var d = us(period * 0.75)
            while (d > t.echoMaxUs) d /= 2
            while (d < t.echoMinUs) d *= 2
            return d.coerceAtMost(SegueFormat.ECHO_MAX_US)
        }

        /** The downbeat nearest the usual hand-over, 0..1 of the window, not too near either end. */
        private fun throwAt(e: Point, lengthUs: Long, usual: Double): Double {
            val want = e.us + usual * lengthUs
            val bar = a.barStarts.minByOrNull { abs(us(it) - want) } ?: return usual
            val at = (us(bar) - e.us).toDouble() / lengthUs
            return if (at in 0.3..0.85) at else usual
        }

        /** The lengths to try: the longest that stays clear of both voices, then the others from the longest. */
        private fun fadeLengths(lengths: List<Long>): List<Long> {
            if (lengths.size < 2 || sa.maxVoice == 0f || sb.maxVoice == 0f) return lengths
            val lastA = (a.bars - 1 downTo 0).firstOrNull { a.barVocalRisk.getOrElse(it) { 0f } >= t.voiceBarRisk }
            val firstB = (0 until b.bars).firstOrNull { b.barVocalRisk.getOrElse(it) { 0f } >= t.voiceBarRisk }
            val freeA = lastA?.let { us(sa.musicEnd) - us(a.barEnd(it)) } ?: Long.MAX_VALUE
            val freeB = firstB?.let { us(b.barStarts[it]) - us(b.contentStart) } ?: Long.MAX_VALUE
            val free = max(0L, min(freeA, freeB))
            val fit = lengths.filter { it <= free }.maxOrNull() ?: lengths.min()
            return listOf(fit) + lengths.filter { it != fit }
        }

        private fun filtered(level: SegueLevel): Found? {
            val rel = tempoReliability(ga)
            val lengths = when {
                rel >= t.reliabilityForLong -> listOf(t.filteredLongUs, t.filteredMiddleUs, t.filteredShortUs)
                rel >= t.reliabilityForMiddle -> listOf(t.filteredMiddleUs, t.filteredShortUs)
                else -> listOf(t.filteredShortUs)
            }
            val tally = Tally()
            var best: Found? = null
            for (lengthUs in fadeLengths(lengths)) {
                val outs = exits(a.barStarts.asSequence(), lengthUs, us(a.contentEnd), phrases = false)
                val ins = entries(gb, lengthUs, phrases = false)
                for (e in outs) for (n in ins) {
                    tally.seen++
                    if (tally.refused(SegueGuards.crossed(SegueClass.FILTERED, measures(e, n, lengthUs, lengthUs), t))) continue
                    val shape = filteredShape(e, n, lengthUs)
                    val at = if (shape == SegueShape.ECHO_OUT) throwAt(e, lengthUs, compiler.swapOf(shape)) else compiler.swapOf(shape)
                    val beat = ga?.let { min(0.25, it.period * 1e6 / lengthUs) } ?: 0.1
                    val lanes = compiler.filtered(shape, min(1.0, 0.4 * 1e6 / lengthUs), matchA(), matchB(), r.eq, at, beat)
                    val plan = plan(e.us, n.us, ga, gb, SegueFormat.RATE_NOMINAL, lengthUs, null, PulseState.FREE, null, null, lanes)
                        .copy(echoUs = if (shape == SegueShape.ECHO_OUT) ga?.let { echoOf(it.period) } else null)
                    val score = score(e, n, lengthUs, lengthUs, null, 0, lanes)
                    val pick = score.total - t.lostPickFade * skipped(e, n, lengthUs)
                    if (best == null || pick > best.pick) best = Found(plan, score.total, at, shape, pick = pick)
                }
                if (best != null && best.score >= t.thresholdFiltered) break
            }
            return settle(level, tally, best, t.thresholdFiltered)
        }

        private fun hard(level: SegueLevel): Found? {
            if (ga == null) return fail(level, SegueCause.NO_GRID, "outgoing")
            val tally = Tally()
            var best: Found? = null
            val residual = phase(a.tailPhase?.medianUs, b.headPhase?.medianUs)
            val outs = exits(gridTimes(ga, a.tailDown, max(a.contentStart, ga.first), min(sa.musicEnd, ga.last)), t.microFadeUs, us(a.contentEnd), phrases = true)
            val ins = entries(gb, t.microFadeUs, phrases = true)
            for (e in outs) for (n in ins) {
                tally.seen++
                val jump = abs(sa.levelBars(e.bar - 2, e.bar) - sb.levelBars(n.bar, n.bar + 2))
                val m = measures(e, n, t.microFadeUs, t.microFadeUs).copy(gridReliable = reliable(ga), loudnessJumpDb = jump, voiceOverlapUs = 0)
                if (tally.refused(SegueGuards.crossed(SegueClass.HARD, m, t))) continue
                val lanes = compiler.hard()
                val plan = plan(e.us, n.us, ga, gb, SegueFormat.RATE_NOMINAL, t.microFadeUs, null, PulseState.LOOSE, us60(ga), residual, lanes)
                val score = score(e, n, t.microFadeUs, t.microFadeUs, null, 0, lanes)
                if (best == null || score.total > best.score) best = Found(plan, score.total, 0.0)
            }
            return settle(level, tally, best, t.thresholdHard)
        }

        /** From a safe point: a bar start when there is one near, otherwise the planned time. */
        private fun simple(level: SegueLevel): Found? {
            val endUs = min(us(sa.musicEnd), us(a.contentEnd))
            val lengthUs = min(t.simpleFadeUs, endUs - r.earliestExitUs)
            if (lengthUs <= 0 || us(b.contentEnd) - us(b.contentStart) < lengthUs) return fail(level, SegueCause.NO_CANDIDATE)
            val targetUs = max(r.earliestExitUs, endUs - lengthUs)
            val bar = a.barStarts.lastOrNull { us(it) in r.earliestExitUs..targetUs && targetUs - us(it) <= t.simpleFadeUs }
            val exitUs = bar?.let(::us) ?: targetUs
            val entryTime = firstEntry(gb)
            val e = Point(exitUs, a.barAt(exitUs / 1e6 + 0.05), 1.0, true)
            val n = Point(us(entryTime), b.barAt(entryTime + 0.05), 1.0, true)
            val tally = Tally().also { it.seen = 1 }
            if (tally.refused(SegueGuards.crossed(SegueClass.FADE, measures(e, n, lengthUs, lengthUs), t))) return settle(level, tally, null, 0.0)
            return Found(plan(exitUs, n.us, ga, gb, SegueFormat.RATE_NOMINAL, lengthUs, null, PulseState.FREE, null, null, compiler.fade()), 0.0, 0.5)
        }

        private fun settle(level: SegueLevel, tally: Tally, best: Found?, threshold: Double): Found? = when {
            tally.seen == 0 -> fail(level, SegueCause.NO_CANDIDATE)
            best == null -> fail(level, SegueCause.GUARDS, tally.toString())
            best.score < threshold -> fail(level, SegueCause.BELOW_THRESHOLD, String.format(java.util.Locale.ROOT, "%.2f < %.2f", best.score, threshold))
            else -> best
        }

        private class Tally {
            var seen = 0
            private val counts = java.util.EnumMap<SegueGuard, Int>(SegueGuard::class.java)
            fun refused(crossed: List<SegueGuard>): Boolean {
                for (g in crossed) counts[g] = (counts[g] ?: 0) + 1
                return crossed.isNotEmpty()
            }
            override fun toString() = counts.entries.joinToString(",") { "${it.key}×${it.value}" } + " of $seen"
        }

        // ── Measures ──
        private fun phase(x: Long?, y: Long?): Long? = if (x == null || y == null) null else hypot(x.toDouble(), y.toDouble()).roundToLong()

        private fun measures(e: Point, n: Point, lengthUs: Long, spanUs: Long): SegueMeasures {
            val a0 = e.us / 1e6; val a1 = (e.us + lengthUs) / 1e6
            val co = if (sa.maxVoice == 0f || sb.maxVoice == 0f) MixPlanner.CoVocal.NONE
                else MixPlanner.coVocal(a, a0, a1, b, n.us / 1e6, (n.us + spanUs) / 1e6)
            val musicUs = us(sa.musicEnd - a.contentStart)
            val cutUs = max(0L, us(sa.musicEnd) - (e.us + lengthUs))
            return SegueMeasures(voiceOverlapUs = (co.total * 1e6).roundToLong(), contentCutUs = cutUs,
                contentCutShare = if (musicUs > 0) cutUs.toDouble() / musicUs else 1.0, playedUs = e.us - us(a.contentStart))
        }

        private fun score(e: Point, n: Point, lengthUs: Long, spanUs: Long, phaseUs: Long?, deviationPpm: Long, lanes: List<SeguePlan.Lane>,
                          pulse: Double = pulseReliability(), barsA: Int? = null, barsB: Int? = null): SegueScore {
            val a0 = e.us / 1e6; val a1 = (e.us + lengthUs) / 1e6; val b0 = n.us / 1e6; val b1 = (n.us + spanUs) / 1e6
            val endBar = a.barAt(a1)
            val energy = abs(sa.levelBars(max(0, endBar - 3), endBar + 1) - sb.levelBars(max(0, n.bar), max(0, n.bar) + 4))
            val rhythm = phaseUs?.let { SegueScore.fallingFrom(it.toDouble(), t.rhythmFullUs.toDouble(), t.phaseMedianMaxUs.toDouble()) } ?: 0.0
            val phrase = phraseNote(a, e, barsA) + phraseNote(b, n, barsB)
            val notes = listOf(
                SegueNote("rhythm", t.weightRhythm, pulse, rhythm),
                SegueNote("phrase", t.weightPhrase, min(structureReliability(a, e.bar), structureReliability(b, n.bar)), phrase),
                SegueNote("energy", t.weightEnergy, 1.0, SegueScore.gaussian(energy, t.energySigmaDb)),
                SegueNote("exit", t.weightExit, vocalReliability(), exitQuality(e, endBar)),
                SegueNote("entry", t.weightEntry, min(structureReliability(b, n.bar), 1.0), n.rank.coerceIn(0.0, 1.0)),
                SegueNote("key", if (r.harmonic) t.weightKey else 0.0, keyReliability(), 1 - keyDistance / 2),
            )
            val overlap = if (sa.maxVoice == 0f || sb.maxVoice == 0f) 0.0 else MixPlanner.vocalOverlap(a, a0, a1, b, b0, b1)
            val effects = lanes.count { it.target == Target.SWEEP_HZ || it.target == Target.RISE_HZ || it.target == Target.SEND_ROOM || it.target == Target.SEND_ECHO }
            val defects = listOf(
                SegueDefect("speed", t.penaltySpeed, SegueScore.risingFrom(deviationPpm.toDouble(), t.rateCostFreePpm.toDouble(), t.maxRateDeviationPpm.toDouble())),
                SegueDefect("voice", t.penaltyVoice, overlap * lengthUs / t.voiceOverlapMaxUs),
                SegueDefect("voice cut", t.penaltyVoiceCut, voiceCut(endBar, n.bar)),
                SegueDefect("bass", t.penaltyBass, commonBass(e.us, n.us, lengthUs, spanUs, lanes)),
                SegueDefect("lost", t.penaltyLost, max(0L, us(sa.musicEnd) - (e.us + lengthUs)).toDouble() / t.contentCutMaxUs),
                SegueDefect("sobriety", t.penaltySobriety, effects.toDouble() / t.sobrietyFullEffects),
            )
            return SegueScore(notes, defects)
        }

        /** A boundary at the start of the window, or less at its middle or end, where the mix's hand-over can land. */
        private fun phraseNote(s: TrackAnalysis, p: Point, bars: Int?): Double = when {
            p.boundary -> 0.5
            bars != null && p.bar >= 0 && (s.sectionStrength(p.bar + bars / 2) > 0 || s.sectionStrength(p.bar + bars) > 0) -> t.phraseNoteInside
            else -> 0.0
        }

        /** The share of each song's voice nobody hears: A's after the window, B's before the entry. */
        private fun voiceCut(endBar: Int, entryBar: Int): Double {
            fun share(x: FloatArray, range: IntRange): Double {
                val total = x.sumOf { it.toDouble() }
                if (total <= 0) return 0.0
                var part = 0.0
                for (i in range) part += x.getOrElse(i) { 0f }
                return part / total
            }
            return share(a.barVocalRisk, (endBar + 1) until a.bars) + share(b.barVocalRisk, 0 until entryBar)
        }

        /** The voice is over, or dies early in the window: leaving on it is what the listener hears. */
        private fun exitQuality(e: Point, endBar: Int): Double {
            if (e.bar < 0 || endBar < e.bar) return 0.0
            val mid = (e.bar + endBar + 1) / 2
            var risk = 0.0; var n = 0
            for (i in mid..endBar) { risk += a.barVocalRisk.getOrElse(i) { 0f }; n++ }
            return 1 - risk / max(1, n)
        }

        /** The bass energy the two songs still have in common once levels and shelves are applied, 0..1. */
        private fun commonBass(e: Long, n: Long, lengthUs: Long, spanUs: Long, lanes: List<SeguePlan.Lane>): Double {
            fun curve(side: LaneSide, target: Target) = lanes.firstOrNull { it.side == side && it.target == target }?.let(::LaneCurve)
            val levelA = curve(LaneSide.OUTGOING, Target.LEVEL); val levelB = curve(LaneSide.INCOMING, Target.LEVEL)
            val shelfA = curve(LaneSide.OUTGOING, Target.SHELF_BOTTOM); val shelfB = curve(LaneSide.INCOMING, Target.SHELF_BOTTOM)
            var common = 0.0; var total = 0.0
            for (step in 0 until t.bassSampleSteps) {
                val x = (step + 0.5) / t.bassSampleSteps
                val u = x * SegueFormat.T_MAX
                val ba = a.barAt((e + lengthUs * x) / 1e6); val bb = b.barAt((n + spanUs * x) / 1e6)
                val ea = if (ba < 0) 0.0 else 10.0.pow((a.barBassDb.getOrElse(ba) { -60f } + (levelA?.value(u) ?: 0.0) + (shelfA?.value(u) ?: 0.0)) / 10)
                val eb = if (bb < 0) 0.0 else 10.0.pow((b.barBassDb.getOrElse(bb) { -60f } + (levelB?.value(u) ?: 0.0) + (shelfB?.value(u) ?: 0.0)) / 10)
                common += min(ea, eb); total += max(ea, eb)
            }
            return if (total > 0) common / total else 0.0
        }

        // ── Plans ──
        private fun us60(g: Grid) = (g.bpm * 1e6).roundToLong()

        private fun plan(exitUs: Long, entryUs: Long, g: Grid?, h: Grid?, ratePpm: Long, lengthUs: Long, bars: Int?, state: PulseState, tempo: Long?, residual: Long?, lanes: List<SeguePlan.Lane>) =
            SeguePlan(ORIGIN,
                SeguePlan.Side(a.audioKey, if (g != null) a.beatKey else null, exitUs, SegueFormat.RATE_NOMINAL, r.listenerRate),
                SeguePlan.Side(b.audioKey, if (h != null) b.beatKey else null, entryUs, ratePpm, r.listenerRate),
                SeguePlan.Span(lengthUs, bars, UnitKind.BAR, Clock.ELAPSED),
                SeguePlan.Pulse(state, tempo, residual), lanes)

        private fun matchB(): Double {
            val d = a.loudnessDb - b.loudnessDb
            return if (d < -t.loudnessMatchFromDb) 10.0.pow(max(d, -t.loudnessMatchToDb) / 20) else 1.0
        }

        private fun matchA(): Double {
            val d = a.loudnessDb - b.loudnessDb
            return if (d > t.loudnessMatchFromDb) 10.0.pow(-min(d, t.loudnessMatchToDb) / 20) else 1.0
        }

        private fun restore(ratePpm: Long, period: Double): Double {
            val dev = abs(ratePpm - SegueFormat.RATE_NOMINAL).toDouble() / SegueFormat.RATE_NOMINAL
            if (dev < 1e-4) return 0.0
            return (max(t.restoreMinBeats, dev / t.restoreRatePerBeat) * period * 10).roundToInt() / 10.0
        }
    }
}
