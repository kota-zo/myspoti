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
    val ORIGIN = SeguePlan.Origin("myspoti-segue-1", "uncalibrated")

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
            "segue ${d.level}" + (d.score?.let { String.format(java.util.Locale.ROOT, " score %.2f", it) } ?: "") +
                (if (d.attempts.isEmpty()) "" else " | ${d.why}"),
            filtered = d.level == SegueLevel.FILTERED, segue = p)
    }

    /** Checks a plan against the analyses it must match; a non-empty answer means it is recomputed. */
    fun stale(p: SeguePlan, a: TrackAnalysis, b: TrackAnalysis): List<SegueViolation> {
        val (fa, fb) = facts(a, b)
        return SegueValidator.stale(p, fa, fb)
    }

    internal fun facts(a: TrackAnalysis, b: TrackAnalysis) =
        SegueSideFacts(a.audioKey, a.tailGridKey, us(a.duration)) to SegueSideFacts(b.audioKey, b.headGridKey, us(b.duration))

    private fun us(seconds: Double): Long = (seconds * 1e6).roundToLong()

    private class Point(val us: Long, val bar: Int, val rank: Double, val boundary: Boolean)

    private class Found(val plan: SeguePlan, val score: Double, val swap: Double)

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
                return SegueDecision(level, found.plan, attempts.toList(), found.score, found.swap,
                    if (level.cls == SegueClass.LOCKED) restore(found.plan.incoming.ratePpm, gb!!.period) else 0.0)
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
            SegueLevel.LOCKED_BEAT -> if (!r.eq) fail(level, SegueCause.BASS_NOT_MANAGED) else locked(level, listOf(t.lockedShortBars), phrases = false)
            SegueLevel.FILTERED -> filtered(level)
            SegueLevel.HARD -> hard(level)
            SegueLevel.SIMPLE_FADE -> simple(level)
            SegueLevel.NORMAL -> null
        }

        // ── Reliability: an uncertain measure weighs less ──
        private fun tempoReliability(g: Grid?): Double = g?.steadiness?.coerceIn(0.0, 1.0) ?: 0.0
        private fun pulseReliability() = min(min(tempoReliability(ga), tempoReliability(gb)), min(a.tailMetre, b.headMetre).toDouble())
        private fun structureReliability(s: TrackAnalysis, bar: Int) = max(s.sectionStrength(bar), t.reliabilityPhraseFallback)
        private fun vocalReliability() = if (a.stereo && b.stereo) t.reliabilityVocalStereo else t.reliabilityVocalMono
        private fun keyReliability() = if (a.key >= 0 && b.key >= 0) min(a.keyStrength, b.keyStrength).coerceIn(0.0, 1.0) else 0.0

        private fun reliable(g: Grid?) = g != null && g.count >= t.gridMinBeats && g.steadiness >= t.gridMinSteadiness && g.driftMs <= t.gridMaxDriftMs

        // ── Candidates ──
        private fun boundary(s: TrackAnalysis, bar: Int) = bar >= 0 && (s.phrasePos(bar) == 0 || s.sectionStrength(bar) > 0)

        /** A's points where a window of [lengthUs] fits, instrumental endings first. */
        private fun exits(times: Sequence<Double>, lengthUs: Long, limitUs: Long, phrases: Boolean): List<Point> {
            val zone = max(zoneStartUs, r.earliestExitUs)
            return times.map { it to us(it) }.filter { (_, u) -> u >= zone && u + lengthUs <= limitUs }.mapNotNull { (time, u) ->
                val bar = a.barAt(time + 0.05)
                val on = boundary(a, bar)
                if (phrases && !on) null else Point(u, bar, instrumentalAfter(bar) + u.toDouble() / max(1L, limitUs) * 1e-3, on)
            }.sortedByDescending { it.rank }.take(t.candidatesPerSide).toList()
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

        /** B's useful attack, its first boundaries; leaving out a start must be worth its note. */
        private fun entries(g: Grid?, spanUs: Long, phrases: Boolean): List<Point> {
            val limit = us(b.contentEnd)
            val startUs = us(b.contentStart)
            val first: Double = if (g != null) MixPlanner.firstDown(g, b.headDown, b.contentStart - 0.08) else b.contentStart
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
            if (ga == null || gb == null) return fail(level, SegueCause.NO_GRID, if (ga == null) "outgoing" else "incoming")
            val m = MixPlanner.tempoMatch(ga.bpm, gb.bpm)
            val ratePpm = (m.speed * SegueFormat.RATE_NOMINAL).roundToLong()
            val deviation = abs(ratePpm - SegueFormat.RATE_NOMINAL)
            val limit = min(t.maxRateDeviationPpm, r.maxRatePpm ?: Long.MAX_VALUE)
            if (deviation > limit) return fail(level, SegueCause.TEMPO_OUT_OF_REACH, String.format(java.util.Locale.ROOT, "%.1f %%", deviation / 1e4))
            // The lower the reliability, the shorter the window.
            val rel = pulseReliability()
            val cap = if (rel >= t.reliabilityForLong) t.lockedLongBars else if (rel >= t.reliabilityForMiddle) t.lockedMiddleBars else t.lockedShortBars
            val allowed = barsAsked.filter { it <= cap && it <= (r.maxBars ?: Int.MAX_VALUE) && (m.beatsPerB == 1.0 || it <= t.halfDoubleMaxBars) }
            if (allowed.isEmpty()) return fail(level, SegueCause.LENGTH_NOT_ALLOWED, "reliability ${"%.2f".format(java.util.Locale.ROOT, rel)}")
            val barA = 4 * ga.period
            val phaseMedian = phase(a.tailPhase?.medianUs, b.headPhase?.medianUs)
            val phaseP95 = phase(a.tailPhase?.p95Us, b.headPhase?.p95Us)
            val drift = hypot(ga.driftMs, gb.driftMs) / 1000 / ga.period
            val tally = Tally()
            var best: Found? = null
            for (bars in allowed) {
                val lengthUs = us(bars * barA)
                val spanUs = lengthUs * ratePpm / SegueFormat.RATE_NOMINAL
                val outs = exits(gridTimes(ga, a.tailDown, max(a.contentStart, ga.first), min(sa.musicEnd, ga.last)), lengthUs, min(us(a.contentEnd), us(ga.last + ga.period)), phrases)
                val ins = entries(gb, spanUs, phrases)
                for (e in outs) for (n in ins) {
                    tally.seen++
                    val base = measures(e, n, lengthUs, spanUs).copy(rateDeviationPpm = deviation, phaseMedianUs = phaseMedian, phaseP95Us = phaseP95,
                        driftBeats = drift, gridReliable = reliable(ga) && reliable(gb))
                    if (tally.refused(SegueGuards.crossed(SegueClass.LOCKED, base, t))) continue
                    val swap = MixPlanner.bassSwapBar(b, sb, gb, n.us / 1e6, bars, barA, m.speed) / bars
                    val beatShare = 1.0 / (bars * 4)
                    val lanes = compiler.locked(swap, beatShare, matchA(), matchB(), r.eq)
                    val plan = plan(e.us, n.us, ga, gb, ratePpm, lengthUs, bars, PulseState.LOCKED, us60(ga), phaseMedian, lanes)
                    val score = score(e, n, lengthUs, spanUs, phaseMedian, deviation, lanes)
                    if (best == null || score.total > best.score) best = Found(plan, score.total, swap)
                }
            }
            return settle(level, tally, best, t.thresholdLocked)
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
            for (lengthUs in lengths) {
                val outs = exits(a.barStarts.asSequence(), lengthUs, us(a.contentEnd), phrases = false)
                val ins = entries(gb, lengthUs, phrases = false)
                for (e in outs) for (n in ins) {
                    tally.seen++
                    if (tally.refused(SegueGuards.crossed(SegueClass.FILTERED, measures(e, n, lengthUs, lengthUs), t))) continue
                    val lanes = compiler.filtered(t.filteredSwapShare, min(1.0, 0.4 * 1e6 / lengthUs), matchA(), matchB(), r.eq)
                    val plan = plan(e.us, n.us, ga, gb, SegueFormat.RATE_NOMINAL, lengthUs, null, PulseState.FREE, null, null, lanes)
                    val score = score(e, n, lengthUs, lengthUs, null, 0, lanes)
                    if (best == null || score.total > best.score) best = Found(plan, score.total, t.filteredSwapShare)
                }
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
            val entryTime = gb?.let { MixPlanner.firstDown(it, b.headDown, b.contentStart - 0.08) } ?: b.contentStart
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

        private fun score(e: Point, n: Point, lengthUs: Long, spanUs: Long, phaseUs: Long?, deviationPpm: Long, lanes: List<SeguePlan.Lane>): SegueScore {
            val a0 = e.us / 1e6; val a1 = (e.us + lengthUs) / 1e6; val b0 = n.us / 1e6; val b1 = (n.us + spanUs) / 1e6
            val endBar = a.barAt(a1)
            val energy = abs(sa.levelBars(max(0, endBar - 3), endBar + 1) - sb.levelBars(max(0, n.bar), max(0, n.bar) + 4))
            val rhythm = phaseUs?.let { SegueScore.fallingFrom(it.toDouble(), t.rhythmFullUs.toDouble(), t.phaseMedianMaxUs.toDouble()) } ?: 0.0
            val phrase = (if (e.boundary) 0.5 else 0.0) + if (n.boundary) 0.5 else 0.0
            val notes = listOf(
                SegueNote("rhythm", t.weightRhythm, pulseReliability(), rhythm),
                SegueNote("phrase", t.weightPhrase, min(structureReliability(a, e.bar), structureReliability(b, n.bar)), phrase),
                SegueNote("energy", t.weightEnergy, 1.0, SegueScore.gaussian(energy, t.energySigmaDb)),
                SegueNote("exit", t.weightExit, vocalReliability(), exitQuality(e, endBar)),
                SegueNote("entry", t.weightEntry, min(structureReliability(b, n.bar), 1.0), n.rank.coerceIn(0.0, 1.0)),
                SegueNote("key", if (r.harmonic) t.weightKey else 0.0, keyReliability(), 1 - keyDistance / 2),
            )
            val overlap = if (sa.maxVoice == 0f || sb.maxVoice == 0f) 0.0 else MixPlanner.vocalOverlap(a, a0, a1, b, b0, b1)
            val effects = lanes.count { it.target == Target.SWEEP_HZ || it.target == Target.SEND_ROOM || it.target == Target.SEND_ECHO }
            val defects = listOf(
                SegueDefect("speed", t.penaltySpeed, SegueScore.risingFrom(deviationPpm.toDouble(), t.rateCostFreePpm.toDouble(), t.maxRateDeviationPpm.toDouble())),
                SegueDefect("voice", t.penaltyVoice, overlap * lengthUs / t.voiceOverlapMaxUs),
                SegueDefect("bass", t.penaltyBass, commonBass(e.us, n.us, lengthUs, spanUs, lanes)),
                SegueDefect("lost", t.penaltyLost, max(0L, us(sa.musicEnd) - (e.us + lengthUs)).toDouble() / t.contentCutMaxUs),
                SegueDefect("sobriety", t.penaltySobriety, effects.toDouble() / t.sobrietyFullEffects),
            )
            return SegueScore(notes, defects)
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
                SeguePlan.Side(a.audioKey, if (g != null) a.tailGridKey else null, exitUs, SegueFormat.RATE_NOMINAL, r.listenerRate),
                SeguePlan.Side(b.audioKey, if (h != null) b.headGridKey else null, entryUs, ratePpm, r.listenerRate),
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
