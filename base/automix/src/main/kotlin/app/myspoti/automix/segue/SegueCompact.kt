/* SPDX-License-Identifier: GPL-3.0-only */
package app.myspoti.automix.segue

import app.myspoti.automix.DurationBounds
import app.myspoti.automix.Grid
import app.myspoti.automix.MixPlanner
import app.myspoti.automix.TrackAnalysis
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sin

/** How a compact transition sounds: each is lanes in the plan, so the engine has nothing to know of it. */
enum class SegueShape {
    /** Both songs at full level; the incoming song opens down from a high-pass until the middle, the outgoing one thins up from it. */
    THIN_SWAP,
    /** Both at full level; the incoming song opens up from a low-pass until the middle, the outgoing one darkens from it. */
    DARK_SWAP,
    /** Each song alone at its own end of the window, both over its middle fifth, the bass changing hands at the middle. */
    OVERLAP_SWAP,
    /** The incoming song rises until the middle, the outgoing one falls after it, the bass changing hands there. */
    HANDOVER,
    /** An equal-power fade and nothing else, for songs whose pulses do not meet. */
    PLAIN_FADE,
}

/**
 * The planner ([SeguePolicy.decide]). Two songs whose pulses meet are mixed over a few bars
 * close to the end of the outgoing song's music, on a one-bar grid: windows of 2, 4, 8 or 16 bars,
 * each scored from a fixed top less graded costs (its length in seconds, a key clash, the tempo gap,
 * where it sits in each song, voices), quantised so that near-equal candidates are equal; equals go
 * to the longer window, then to the earlier exit, then to the earlier entry. Songs whose pulses do
 * not meet, without half or double time, or whose beat cannot be relied on, get a plain fade over
 * the end of the music instead. Deterministic: the same request gives the same plan.
 */
internal object CompactPolicy {
    val ORIGIN = SeguePlan.Origin("myspoti-adaptive-2", "uncalibrated")

    /** Typical alignment cannot hide a smaller run of badly displaced beats. */
    internal fun phaseAllowed(phase: Pair<Long, Long>?, t: SegueTuning): Boolean =
        phase == null || (phase.first <= t.phaseMedianMaxUs && phase.second <= t.phaseP95MaxUs)

    fun decide(r: SegueRequest): SegueDecision = Run(r).decide()

    private fun us(seconds: Double): Long = (seconds * 1e6).roundToLong()

    /** One side's stretch and what its place costs. */
    private class Placed(val stretch: Stretch, val index: Int, val cost: Double, val levelDb: Double?)

    private class Candidate(val exit: Placed, val entry: Placed, val bars: Int, val ratePpm: Long, val phaseUs: Long?, val score: Double)

    /** Same Camelot number, its neighbours and the relative key are free; two or more numbers apart is a clash. */
    internal fun keyClash(a: TrackAnalysis, b: TrackAnalysis): Boolean {
        if (a.key < 0 || b.key < 0 || a.keyStrength < 0.5 || b.keyStrength < 0.5) return false
        val na = MixPlanner.camelot(a.key).first; val nb = MixPlanner.camelot(b.key).first
        return min(Math.floorMod(na - nb, 12), Math.floorMod(nb - na, 12)) >= 2
    }

    /**
     * Seconds of B over which it returns to its own tempo once A is gone (the player eases along
     * [app.myspoti.automix.MixPlan.speedAfter], whose steepest point is 1.5 times its mean slope):
     * at least [CompactTuning.restoreMinBars] bars of [barSec], long enough that the speed never
     * changes by more than [CompactTuning.restoreMaxPerBar] in a bar. A gap under
     * [CompactTuning.restoreFromGap] is not heard as a change: none.
     */
    internal fun restoreSec(ratePpm: Long, barSec: Double, t: SegueTuning): Double {
        val gap = abs(ratePpm - SegueFormat.RATE_NOMINAL).toDouble() / SegueFormat.RATE_NOMINAL
        if (gap < t.compact.restoreFromGap || barSec <= 0) return 0.0
        val bars = max(t.compact.restoreMinBars, 1.5 * gap / t.compact.restoreMaxPerBar)
        return ceil(bars * barSec * 10) / 10.0
    }

    /** What a window of [seconds] costs: nothing up to a free length, then growing faster and faster. */
    internal fun lengthCost(seconds: Double, t: SegueTuning): Double {
        val x = max(0.0, seconds - t.compact.lengthFreeSec)
        return t.compact.lengthSlope * x + t.compact.lengthCurve * x * x
    }

    /**
     * Where the outgoing song's music ends for the plain fade: the end of its last bar at least as
     * loud as [CompactTuning.fadeEndDb], moved by [CompactTuning.fadeEndOffsetUs]; a song without
     * bars, or with none that loud, ends with its content.
     */
    internal fun fadeEnd(a: TrackAnalysis, t: SegueTuning): Double {
        for (i in a.bars - 1 downTo 0) if (a.barDb[i] >= t.compact.fadeEndDb) return a.barEnd(i) + t.compact.fadeEndOffsetUs / 1e6
        return a.contentEnd
    }

    /** Where the plain fade sits in the outgoing song, and where the incoming one starts: microseconds. */
    internal class FadePlace(val exitUs: Long, val lengthUs: Long, val entryUs: Long)

    /**
     * The plain fade: centred on the end of the outgoing song's music ([fadeEnd]), cut by the end of its file and
     * never shorter than the floor there; the incoming song starts a little before its first sound.
     */
    internal fun fadePlace(a: TrackAnalysis, b: TrackAnalysis, t: SegueTuning, earliestUs: Long = 0, bounds: DurationBounds = DurationBounds(), playbackRate: Double = 1.0): FadePlace? {
        if (!bounds.isUnbounded) {
            if (bounds.minUs > bounds.maxUs) return null
            val entry = max(0L, us(b.contentStart) - t.compact.fadeLeadUs)
            val wanted = t.compact.fadeUs.coerceIn(bounds.minUs, bounds.maxUs)
            val start = max(max(0L, earliestUs), min(us(fadeEnd(a, t)) - wanted / 2, us(a.duration) - wanted))
            val length = minOf(wanted, us(a.duration) - start, us(b.duration) - entry)
            return if (bounds.containsUs(length)) coverQuietArrival(a, b, t, earliestUs, bounds, playbackRate, FadePlace(start, length, entry)) else null
        }
        val durationUs = us(a.duration)
        val endUs = us(fadeEnd(a, t))
        val start = max(max(0L, earliestUs), min(endUs - t.compact.fadeUs / 2, durationUs - t.compact.fadeMinUs))
        val length = min(t.compact.fadeUs, durationUs - start)
        if (length <= 0) return null
        val entry = max(0L, us(b.contentStart) - t.compact.fadeLeadUs)
        if (entry + length > us(b.duration)) return null
        return coverQuietArrival(a, b, t, earliestUs, bounds, playbackRate, FadePlace(start, length, entry))
    }

    /** Engineering guards, independent of reference-player measurements: retain the incoming
     * opening, but keep A underneath a clear early rise rather than finish before that rise.
     * A full measured arrival bar must fit in at most 12 listener seconds. No cue or gain changes.
     */
    private fun coverQuietArrival(a: TrackAnalysis, b: TrackAnalysis, t: SegueTuning, earliestUs: Long,
                                  bounds: DurationBounds, playbackRate: Double, original: FadePlace): FadePlace {
        if (b.bars < 3 || !playbackRate.isFinite() || playbackRate <= 0) return original
        val first = (0 until b.bars).firstOrNull { b.barEnd(it) > original.entryUs / 1e6 } ?: return original
        // Extend only to bridge an actual energy deficit while A is still musically present.
        // Already soft endings keep their original fade; use the existing music-end level guard.
        val outgoing = LocalLevels.level(a, original.exitUs / 1e6, (original.exitUs + original.lengthUs) / 1e6) ?: return original
        val opening = LocalLevels.level(b, original.entryUs / 1e6, (original.entryUs + original.lengthUs) / 1e6) ?: return original
        if (outgoing < t.compact.fadeEndDb || outgoing < opening + 12.0) return original
        val initial = b.barDb[first].toDouble()
        val body = MixPlanner.summary(b).loud
        // A substantial quiet opening and a nine-dB rise, sustained for two measured bars.
        if (!initial.isFinite() || initial > body - 12.0) return original
        val limit = min(bounds.maxUs, us(12.0 * playbackRate))
        for (i in first + 1 until b.bars - 1) {
            val target = us(b.barEnd(i)) - original.entryUs
            if (target > limit) break
            val level = b.barDb[i].toDouble(); val next = b.barDb[i + 1].toDouble()
            if (!level.isFinite() || !next.isFinite() || level < body - 12.0 || next < body - 12.0 ||
                level < initial + 9.0 || next < initial + 9.0) continue
            if (target <= original.lengthUs || !bounds.containsUs(target)) return original
            val end = original.exitUs + original.lengthUs
            val exit = end - target
            if (exit < max(us(a.contentStart), max(0L, earliestUs)) || original.entryUs + target > us(b.duration)) return original
            val overlap = MixPlanner.coVocal(a, exit / 1e6, end / 1e6, b,
                original.entryUs / 1e6, (original.entryUs + target) / 1e6)
            if (overlap.total > 0.5) return original
            return FadePlace(exit, target, original.entryUs)
        }
        return original
    }

    private class Run(val r: SegueRequest) {
        val a = r.a; val b = r.b; val t = r.tuning
        // Span and anchors use A's timeline; listenerRate applies to both decks at playback.
        val bounds = r.durationBounds.forPlaybackRate(r.listenerRate.toDouble() / SegueFormat.RATE_NOMINAL)
        val sa = MixPlanner.summary(a); val sb = MixPlanner.summary(b)
        val lanes = CompactLanes(t)
        val attempts = ArrayList<SegueAttempt>()
        val voices = sa.maxVoice != 0f && sb.maxVoice != 0f
        val facts = SeguePolicy.facts(a, b)

        fun decide(): SegueDecision {
            if (albumContinuity()) return SegueDecision(SegueLevel.NORMAL, null,
                listOf(SegueLevel.LOCKED_SHORT, SegueLevel.SIMPLE_FADE).map { SegueAttempt(it, SegueCause.ALBUM_CONTINUITY) })
            locked()?.let { c ->
                val level = when {
                    c.bars >= t.compact.longestBars -> SegueLevel.LOCKED_LONG
                    c.bars <= t.compact.shortestBars -> SegueLevel.LOCKED_BEAT
                    else -> SegueLevel.LOCKED_SHORT
                }
                val shape = lockedShape(c)
                val lengthUs = us(c.exit.stretch.lengthSec)
                val balance = LocalLevels.measure(a, c.exit.stretch.start, c.exit.stretch.end,
                    b, c.entry.stretch.start, c.entry.stretch.end)
                val plan = SeguePlan(ORIGIN,
                    SeguePlan.Side(a.audioKey, a.beatKey, c.exit.stretch.startUs, SegueFormat.RATE_NOMINAL, r.listenerRate),
                    SeguePlan.Side(b.audioKey, b.beatKey, c.entry.stretch.startUs, c.ratePpm, r.listenerRate),
                    SeguePlan.Span(lengthUs, c.bars, UnitKind.BAR, Clock.ELAPSED),
                    SeguePlan.Pulse(PulseState.LOCKED, (c.exit.stretch.grid.bpm * 1e6).roundToLong(), c.phaseUs),
                    LocalLevels.apply(lanes.locked(shape, lengthUs, r.eq), balance))
                val problems = SegueValidator.against(plan, facts.first, facts.second)
                if (problems.isEmpty()) return SegueDecision(level, plan, attempts.toList(), c.score, 0.5, restore(c.ratePpm, c.entry.stretch.grid.period), shape)
                attempts += SegueAttempt(level, SegueCause.INVALID_PLAN, problems.joinToString(","))
            }
            val place = fadePlace(a, b, t, r.earliestExitUs, bounds, r.listenerRate.toDouble() / SegueFormat.RATE_NOMINAL)
                ?: return SegueDecision(SegueLevel.NORMAL, null, attempts + SegueAttempt(SegueLevel.SIMPLE_FADE, if (r.durationBounds.isUnbounded) SegueCause.NO_CANDIDATE else SegueCause.LENGTH_NOT_ALLOWED))
            val plan = SeguePlan(ORIGIN,
                SeguePlan.Side(a.audioKey, null, place.exitUs, SegueFormat.RATE_NOMINAL, r.listenerRate),
                SeguePlan.Side(b.audioKey, null, place.entryUs, SegueFormat.RATE_NOMINAL, r.listenerRate),
                SeguePlan.Span(place.lengthUs, null, UnitKind.BAR, Clock.ELAPSED),
                SeguePlan.Pulse(PulseState.FREE, null, null), LocalLevels.apply(lanes.fade(),
                    LocalLevels.measure(a, place.exitUs / 1e6, (place.exitUs + place.lengthUs) / 1e6,
                        b, place.entryUs / 1e6, (place.entryUs + place.lengthUs) / 1e6)))
            val problems = SegueValidator.against(plan, facts.first, facts.second)
            if (problems.isNotEmpty()) return SegueDecision(SegueLevel.NORMAL, null, attempts + SegueAttempt(SegueLevel.SIMPLE_FADE, SegueCause.INVALID_PLAN, problems.joinToString(",")))
            return SegueDecision(SegueLevel.SIMPLE_FADE, plan, attempts.toList(), null, 0.5, 0.0, SegueShape.PLAIN_FADE)
        }

        private fun albumContinuity(): Boolean {
            if (!r.sameAlbum) return false
            return us(a.duration - a.contentEnd) <= t.gaplessMaxSilenceUs && us(b.contentStart) <= t.gaplessMaxSilenceUs
        }

        private fun fail(cause: SegueCause, detail: String = ""): Candidate? {
            attempts += SegueAttempt(SegueLevel.LOCKED_SHORT, cause, detail); return null
        }

        /**
         * A song's beat can be relied on: with the beat model's own beats, each stretch is judged on
         * them when it is measured (their share, their fit, the downbeats' confidence); without them,
         * the grid of the song's end or start must hold as a whole.
         */
        private fun reliable(g: Grid?, s: TrackAnalysis) = g != null &&
            (s.modelBeats.isNotEmpty() || (g.count >= t.gridMinBeats && g.steadiness >= t.gridMinSteadiness && g.driftMs <= t.gridMaxDriftMs))

        // ── Locked ──
        /**
         * How many of this song's measured bars make one bar at the tempo window's metrical level:
         * a beat tracked at half or double the usual level for its music is folded into the window
         * ([CompactTuning.foldFromBpm], off at 0). 1 when it is off or the tempo is unknown.
         */
        private fun ourBarsPerBar(g: Grid?): Double {
            val from = t.compact.foldFromBpm
            if (from <= 0 || g == null) return 1.0
            var bpm = g.bpm; var m = 1.0
            while (bpm < from && m > 0.5) { bpm *= 2; m /= 2 }
            while (bpm >= 2 * from && m < 2) { bpm /= 2; m *= 2 }
            return m
        }

        /**
         * Two beats a metrical level apart, as a tracker often places them on music a listener hears
         * at one tempo: the slower song counts each of its bars as two when the faster tempo is in
         * [CompactTuning.pairFoldMinBpm, CompactTuning.pairFoldMaxBpm] (off at 0). Null: no change.
         */
        private fun pairLevel(bpmA: Double, bpmB: Double, limit: Double): Pair<Double, Double>? {
            if (t.compact.pairFoldMaxBpm <= 0) return null
            val r = bpmB / bpmA
            val fast = max(bpmA, bpmB)
            if (fast < t.compact.pairFoldMinBpm || fast > t.compact.pairFoldMaxBpm) return null
            return when {
                abs(r / 2 - 1) <= limit -> 0.5 to 1.0
                abs(r * 2 - 1) <= limit -> 1.0 to 0.5
                else -> null
            }
        }

        private fun locked(): Candidate? {
            val ga = a.tail; val gb = b.head
            // A song whose beat cannot be relied on is never locked, whatever its partner.
            if (!reliable(ga, a)) return fail(SegueCause.NO_GRID, "outgoing")
            if (!reliable(gb, b)) return fail(SegueCause.NO_GRID, "incoming")
            val limit = min(t.compact.lockMaxTempo, (r.maxRatePpm ?: Long.MAX_VALUE) / SegueFormat.RATE_NOMINAL.toDouble())
            val lengths = generateSequence(t.compact.shortestBars) { it * 2 }.takeWhile { it <= t.compact.longestBars }.filter { it <= (r.maxBars ?: Int.MAX_VALUE) }.toList()
            if (lengths.isEmpty() || t.compact.shortestBars <= 0) return fail(SegueCause.LENGTH_NOT_ALLOWED)
            val key = if (r.harmonic && keyClash(a, b)) t.compact.keyCost else 0.0
            var ma = ourBarsPerBar(ga); var mb = ourBarsPerBar(gb)
            pairLevel(ga!!.bpm / ma, gb!!.bpm / mb, limit)?.let { (fa, fb) -> ma *= fa; mb *= fb }
            var best: Candidate? = null
            var exitsSeen = false; var entriesSeen = false; var rateOk = false
            var closest = Double.MAX_VALUE
            for (bars in lengths) {
                val barsA = bars * ma; val barsB = bars * mb
                if (barsA < 1 || barsB < 1 || barsA % 1.0 != 0.0 || barsB % 1.0 != 0.0) continue
                val outs = exits(barsA.toInt(), bars).filter { bounds.containsUs(us(it.stretch.lengthSec)) }; val ins = entries(barsB.toInt(), mb)
                exitsSeen = exitsSeen || outs.isNotEmpty(); entriesSeen = entriesSeen || ins.isNotEmpty()
                for (e in outs) for (n in ins) {
                    val rate = n.stretch.lengthSec / e.stretch.lengthSec
                    // The bars as they are: a song in half or double time against the other is not locked.
                    val dev = abs(rate - 1)
                    closest = min(closest, dev)
                    if (dev > limit) continue
                    rateOk = true
                    val phase = Stretches.phase(e.stretch, n.stretch, rate)
                    if (!phaseAllowed(phase, t)) continue
                    val raw = 4.0 - lengthCost(e.stretch.lengthSec, t) - key - tempoCost(dev) - e.cost - n.cost - clash(e.stretch, n.stretch) -
                        LocalLevels.placementCost(e.levelDb, n.levelDb, t.compact.step)
                    val score = Math.round(raw / t.compact.step) * t.compact.step
                    val c = Candidate(e, n, bars, (rate * SegueFormat.RATE_NOMINAL).roundToLong(), phase?.first, score)
                    if (best == null || better(c, best)) best = c
                }
            }
            if (best != null) return best
            return when {
                !exitsSeen -> fail(if (r.durationBounds.isUnbounded) SegueCause.NO_GRID else SegueCause.LENGTH_NOT_ALLOWED, if (r.durationBounds.isUnbounded) "no outgoing stretch" else "no outgoing stretch within duration bounds")
                !entriesSeen -> fail(SegueCause.NO_GRID, "no incoming stretch")
                !rateOk -> fail(SegueCause.TEMPO_OUT_OF_REACH, String.format(java.util.Locale.ROOT, "%.1f %%", closest * 100))
                else -> fail(SegueCause.GUARDS, "phase")
            }
        }

        /** A higher score; at equal scores the longer window, then the earlier exit, then the earlier entry. */
        private fun better(c: Candidate, o: Candidate): Boolean {
            val eps = t.compact.step * 1e-3
            if (c.score > o.score + eps) return true
            if (c.score < o.score - eps) return false
            if (c.bars != o.bars) return c.bars > o.bars
            if (c.exit.stretch.startUs != o.exit.stretch.startUs) return c.exit.stretch.startUs < o.exit.stretch.startUs
            return c.entry.stretch.startUs < o.entry.stretch.startUs
        }

        private fun tempoCost(dev: Double): Double =
            if (dev <= t.compact.lockFreeTempo) 0.0 else t.compact.tempoCost * ((dev - t.compact.lockFreeTempo) / max(1e-6, t.compact.lockMaxTempo - t.compact.lockFreeTempo)).coerceIn(0.0, 1.0)

        /** Two voices heard together inside the window, up to the full cost from two seconds of it. */
        private fun clash(e: Stretch, n: Stretch): Double {
            if (!voices) return 0.0
            val co = MixPlanner.coVocal(a, e.start, e.end, b, n.start, n.end)
            return t.compact.clashCost * (co.total / 2.0).coerceIn(0.0, 1.0)
        }

        private fun sung(s: TrackAnalysis, from: Int, to: Int): Int {
            var n = 0
            for (i in max(0, from) until min(s.bars, to)) if (s.barVocalRisk.getOrElse(i) { 0f } >= t.voiceBarRisk) n++
            return n
        }

        /**
         * Bars too weak to carry their own beat: quiet against the song, or with too few of the beat
         * model's beats. Only those may borrow a neighbouring grid.
         */
        private fun weak(s: TrackAnalysis, sum: app.myspoti.automix.MixPlanner.Summary, bar: Int, bars: Int): Boolean {
            if (sum.levelBars(bar, bar + bars) <= sum.loud - t.compact.extendQuietDb) return true
            if (s.modelBeats.isEmpty() || bar >= s.bars) return false
            val from = s.barStarts[bar]; val to = if (bar + bars < s.bars) s.barStarts[bar + bars] else s.contentEnd
            val n = s.modelBeats.count { it >= from - 0.05 && it < to - 0.05 }
            return n < t.stretchMinBeatShare * 4 * bars
        }

        private val doubledA by lazy { Stretches.doubled(a.modelBeats) }
        private val doubledB by lazy { Stretches.doubled(b.modelBeats) }

        /**
         * The stretch measured on its own beats; where the beat model kept only every other beat of
         * these bars (half as many as the bars hold), on those beats with the missing ones halfway;
         * for weak bars, the grid of the next reliable bars carried over them.
         */
        private fun stretch(s: TrackAnalysis, sum: app.myspoti.automix.MixPlanner.Summary, bar: Int, bars: Int, metre: Double, fromAfter: Boolean): Stretch? =
            Stretches.measure(s, bar, bars, metre, t)
                ?: halfModel(s, bar, bars, metre)
                ?: if (t.compact.extendGapBars >= 0 && bars <= t.compact.extendMaxBars && weak(s, sum, bar, bars))
                    Stretches.extended(s, bar, bars, metre, t, fromAfter, t.compact.extendGapBars) else null

        /** The bars measured on the model's beats at their level: halfway beats added where it kept every other one, every other one kept where it found twice as many. */
        private fun halfModel(s: TrackAnalysis, bar: Int, bars: Int, metre: Double): Stretch? {
            if (!t.compact.halfModelBeats || s.modelBeats.isEmpty()) return null
            val n = Stretches.count(s, s.modelBeats, bar, bars)
            if (abs(n - 2 * bars) <= 1) return Stretches.measure(s, bar, bars, metre, t, if (s === a) doubledA else doubledB)
            if (abs(n - 8 * bars) <= 2) return Stretches.measure(s, bar, bars, metre, t, Stretches.halved(s, s.modelBeats, bar, bars))
            return null
        }

        private val exitCache = HashMap<Long, Stretch?>()
        private val entryCache = HashMap<Long, Stretch?>()

        /** Where the outgoing song's music ends for placing an exit ([CompactTuning.exitLandmark]). */
        private val exitEnd: Double = when (t.compact.exitLandmark) {
            1 -> a.duration
            2 -> sa.musicEnd
            else -> a.contentEnd
        }

        /**
         * A's windows of [ourBars] of its measured bars ([bars] at the window's level) that end
         * shortly before the end of its music: the last few bars before it are best, a little more
         * or a little past it costs a step, far before it more.
         */
        private fun exits(ourBars: Int, bars: Int): List<Placed> {
            val floorUs = max(r.earliestExitUs, us(a.duration * t.compact.exitMinShare))
            val durationUs = us(a.duration)
            val out = ArrayList<Placed>()
            for (i in 0 until a.bars) {
                if (us(a.barStarts[i]) < floorUs - 50_000) continue
                val s = exitCache.getOrPut(i.toLong() * 1000 + ourBars) { stretch(a, sa, i, ourBars, a.tailMetre.toDouble(), fromAfter = false) } ?: continue
                if (s.startUs < floorUs || us(s.end) > durationUs) continue
                val barSec = s.lengthSec / bars
                val gap = (exitEnd - s.end) / barSec
                if (gap < -t.compact.exitPastBars || gap > t.compact.exitFarBars) continue
                val place = when {
                    gap < t.compact.exitBestFrom -> 1.0
                    gap <= t.compact.exitBestTo -> 0.0
                    gap <= t.compact.exitNearBars -> ceil((gap - t.compact.exitBestTo) / ((t.compact.exitNearBars - t.compact.exitBestTo) / 2))
                    else -> 6.0
                }
                // What the listener loses after the window: a sung line cut short, or the song still in full flight.
                val endBar = i + ourBars
                val rest = min(4, sung(a, endBar, a.bars)) + (if (a.bars - endBar >= 2 && sa.levelBars(endBar, a.bars) >= sa.loud - t.compact.restLoudDb) 1 else 0)
                out += Placed(s, i, (place + rest + (if (s.carried) t.compact.extendSteps else 0)) * t.compact.step,
                    LocalLevels.level(a, s.start, s.end))
            }
            return out
        }

        /**
         * B's windows of [ourBars] of its measured bars from its first bars ([perBar] of them make a
         * bar at the window's level): a couple of bars in is best, its very start or later bars cost
         * steps, a quiet opening left out is welcome and a sung one is not.
         */
        /**
         * The bar where B arrives after a long quiet opening: its level reaches near the song's loud
         * level there, or rises well over the opening so far, after at least [CompactTuning.introMinBars] bars well under it, before the entry
         * limit. Null for a short or loud opening.
         */
        private fun arrival(first: Int, perBar: Double): Int? {
            val min = (t.compact.introMinBars * perBar).toInt()
            for (i in first + min until b.bars) {
                if (b.barStarts[i] > b.duration * t.compact.entryMaxShare) return null
                // Arrived: near the loud level, or clearly over the opening so far (a band coming in after a lone instrument).
                val opening = sb.levelBars(first, i)
                val next = sb.levelBars(i, i + 1)
                if (next >= sb.loud - t.compact.introLoudDb || next >= opening + t.compact.introQuietDb)
                    return i.takeIf { opening <= sb.loud - t.compact.introQuietDb }
                if (sb.levelBars(first, i) > sb.loud - t.compact.introQuietDb) return null
            }
            return null
        }

        private fun entries(ourBars: Int, perBar: Double): List<Placed> {
            val first = (0 until b.bars).firstOrNull { b.barStarts[it] >= b.contentStart - 0.08 } ?: return emptyList()
            val out = ArrayList<Placed>()
            // A long quiet opening is left out: the entry lands where the song arrives, as an entry inside it costs steps.
            val arrives = arrival(first, perBar)
            if (arrives != null) for (i in arrives - (2 * perBar).toInt()..arrives) {
                if (i <= first || i + ourBars >= b.bars) continue
                val s = entryCache.getOrPut(i.toLong() * 1000 + ourBars) { stretch(b, sb, i, ourBars, b.headMetre.toDouble(), fromAfter = true) } ?: continue
                val off = if (i == arrives) 0 else 1
                out += Placed(s, i, (off + 2 * min(2, sung(b, first, i)) + (if (s.carried) t.compact.extendSteps else 0)) * t.compact.step,
                    LocalLevels.level(b, s.start, s.end))
            }
            val inside = if (arrives != null) t.compact.introSteps else 0
            for (j in 0..(t.compact.entryLastBar * perBar).toInt()) {
                val i = first + j
                if (i >= b.bars || b.barStarts[i] > b.duration * t.compact.entryMaxShare) break
                if (arrives != null && i >= arrives - (2 * perBar).toInt()) break
                val k = (j / perBar).toInt()
                val s = entryCache.getOrPut(i.toLong() * 1000 + ourBars) { stretch(b, sb, i, ourBars, b.headMetre.toDouble(), fromAfter = true) } ?: continue
                val place = when {
                    k in t.compact.entryBestFrom..t.compact.entryBestTo -> 0
                    k == t.compact.entryBestFrom - 1 || k == t.compact.entryBestTo + 1 -> 1
                    k < t.compact.entryBestFrom || k <= t.compact.entryBestTo + 4 -> 2
                    else -> 3
                }
                val quiet = j > 0 && sb.levelBars(first, i) <= sb.levelBars(i, i + ourBars) - t.compact.quietIntroDb
                out += Placed(s, i, (place + inside + 2 * min(2, sung(b, first, i)) - (if (quiet) t.compact.quietSteps else 0) + (if (s.carried) t.compact.extendSteps else 0)) * t.compact.step,
                    LocalLevels.level(b, s.start, s.end))
            }
            return out
        }

        // Shape follows the actual selected passages; identity only breaks ties for sparse textures.
        private fun lockedShape(c: Candidate): SegueShape {
            val e = c.exit.stretch; val n = c.entry.stretch
            // Keep simultaneous lead lines to the middle fifth instead of opening both decks
            // throughout the window. Spectral filters cannot separate a singer from an instrument.
            if (voices && MixPlanner.coVocal(a, e.start, e.end, b, n.start, n.end).total >= 0.5)
                return SegueShape.OVERLAP_SWAP
            // A short end-cut on a full-level filtered deck can expose a much quieter passage
            // at once. Give the loudness change the entire window instead of a 20-ms hand-off.
            if (LocalLevels.measure(a, e.start, e.end, b, n.start, n.end).gradualHandover)
                return SegueShape.HANDOVER
            if (r.eq) {
                fun prominence(s: TrackAnalysis, band: FloatArray, from: Int, count: Int): Double {
                    var sum = 0.0; var size = 0
                    for (i in from until min(s.bars, from + count)) {
                        val db = band.getOrElse(i) { Float.NEGATIVE_INFINITY }
                        val level = s.barDb.getOrElse(i) { Float.NEGATIVE_INFINITY }
                        if (db.isFinite() && level.isFinite()) { sum += db - level; size++ }
                    }
                    return if (size > 0) sum / size else Double.NEGATIVE_INFINITY
                }
                val bass = min(prominence(a, a.barBassDb, c.exit.index, e.bars),
                    prominence(b, b.barBassDb, c.entry.index, n.bars))
                val high = min(prominence(a, a.barHighDb, c.exit.index, e.bars),
                    prominence(b, b.barHighDb, c.entry.index, n.bars))
                // Two prominent bass parts need a progressive high-pass handover; two bright
                // arrangements favour darkening A while B opens. Thresholds are conservative
                // engineering choices, not calibrated listening claims.
                if (bass >= -6.0) return SegueShape.THIN_SWAP
                if (high >= -8.0) return SegueShape.DARK_SWAP
            }
            var h = -3750763034362895579L
            for (c in "${a.audioKey}|${b.audioKey}") { h = (h xor c.code.toLong()) * 1099511628211L }
            val options = if (r.eq) SHAPES else SHAPES.filter { it.first == SegueShape.OVERLAP_SWAP || it.first == SegueShape.HANDOVER }
            var pick = Math.floorMod(h, options.sumOf { it.second }.toLong()).toInt()
            for ((shape, weight) in options) { if (pick < weight) return shape; pick -= weight }
            return options.last().first
        }

        private fun restore(ratePpm: Long, period: Double): Double = restoreSec(ratePpm, 4 * period, t)
    }

    /** The locked shapes and how often each is drawn [M]: the filtered hand-overs most, then the plain overlap, then the bass hand-over. */
    private val SHAPES = listOf(SegueShape.THIN_SWAP to 42, SegueShape.DARK_SWAP to 27, SegueShape.OVERLAP_SWAP to 19, SegueShape.HANDOVER to 12)
}

/** The compact planner's lanes: linear amplitudes, a bass hand-over at the middle, filters closing towards one end. */
internal class CompactLanes(private val t: SegueTuning) {
    internal fun knot(x: Double, value: Double?, form: KnotForm = KnotForm.Ramp) = SeguePlan.Knot(
        (x * SegueFormat.T_MAX).roundToInt().coerceIn(0, SegueFormat.T_MAX),
        value?.let { (it * SegueFormat.VALUE_SCALE).roundToLong() }, form)

    /** Sorted, one knot per position (the last written), the first a step. */
    internal fun lane(side: LaneSide, target: Target, knots: List<SeguePlan.Knot>): SeguePlan.Lane {
        val one = knots.groupBy { it.t }.toSortedMap().values.map { it.last() }
        return SeguePlan.Lane(side, target, target.unit, listOf(one.first().copy(form = KnotForm.Step)) + one.drop(1))
    }

    private fun db(gain: Double): Double? = if (gain <= 1e-4) null else 20 * log10(gain)

    /** A level lane following [gain] in linear amplitude: knots close enough that the dB ramps between them do not show. */
    internal fun level(side: LaneSide, xs: List<Double>, gain: (Double) -> Double): SeguePlan.Lane =
        lane(side, Target.LEVEL, xs.distinct().sorted().map { knot(it, db(gain(it))) })

    internal fun grid(from: Double, to: Double, n: Int = 16) = List(n + 1) { from + (to - from) * it / n }

    /** The bass changes hands at [at] on the isolator: the outgoing song's band goes just before it as the incoming one's comes in. */
    private fun bassSwap(at: Double, jump: Double): List<SeguePlan.Lane> {
        val kill = t.compact.bassKillDb
        return listOf(
            lane(LaneSide.OUTGOING, Target.BASS_BAND, listOf(knot(0.0, 0.0), knot(at - jump, 0.0), knot(at, kill), knot(1.0, kill))),
            lane(LaneSide.INCOMING, Target.BASS_BAND, listOf(knot(0.0, kill), knot(at - jump, kill), knot(at, 0.0), knot(1.0, 0.0))),
        )
    }

    /** Both songs at full level over the window: the outgoing one stops at its end, the incoming one is there from its start. */
    private fun fullOverlap(jump: Double): List<SeguePlan.Lane> = listOf(
        lane(LaneSide.OUTGOING, Target.LEVEL, listOf(knot(0.0, 0.0), knot(1.0 - jump, 0.0), knot(1.0, null))),
        lane(LaneSide.INCOMING, Target.LEVEL, listOf(knot(0.0, null), knot(jump, 0.0), knot(1.0, 0.0))),
    )

    fun locked(shape: SegueShape, lengthUs: Long, eq: Boolean): List<SeguePlan.Lane> {
        val j = (t.compact.jumpUs.toDouble() / max(1L, lengthUs)).coerceIn(1e-4, 0.05)
        val mid = 0.5
        val out = ArrayList<SeguePlan.Lane>()
        when (shape) {
            SegueShape.OVERLAP_SWAP -> {
                out += lane(LaneSide.OUTGOING, Target.LEVEL, listOf(knot(0.0, 0.0), knot(0.6, 0.0), knot(0.6 + j, null), knot(1.0, null)))
                out += lane(LaneSide.INCOMING, Target.LEVEL, listOf(knot(0.0, null), knot(0.4 - j, null), knot(0.4, 0.0), knot(1.0, 0.0)))
            }
            SegueShape.HANDOVER -> {
                out += level(LaneSide.OUTGOING, listOf(0.0) + grid(mid, 1.0)) { x -> if (x <= mid) 1.0 else (1 - x) / (1 - mid) }
                out += level(LaneSide.INCOMING, grid(0.0, mid) + 1.0) { x -> if (x >= mid) 1.0 else x / mid }
            }
            SegueShape.THIN_SWAP -> {
                out += fullOverlap(j)
                if (eq) {
                    out += lane(LaneSide.OUTGOING, Target.RISE_HZ, listOf(knot(0.0, SegueFormat.RISE_OPEN_HZ), knot(mid, SegueFormat.RISE_OPEN_HZ), knot(1.0, t.compact.highPassHz, KnotForm.Octave)))
                    out += lane(LaneSide.INCOMING, Target.RISE_HZ, listOf(knot(0.0, t.compact.highPassHz), knot(mid, SegueFormat.RISE_OPEN_HZ, KnotForm.Octave), knot(1.0, SegueFormat.RISE_OPEN_HZ)))
                }
            }
            SegueShape.DARK_SWAP -> {
                out += fullOverlap(j)
                if (eq) {
                    out += lane(LaneSide.OUTGOING, Target.SWEEP_HZ, listOf(knot(0.0, SegueFormat.SWEEP_OPEN_HZ), knot(mid, SegueFormat.SWEEP_OPEN_HZ), knot(1.0, t.compact.lowPassHz, KnotForm.Octave)))
                    out += lane(LaneSide.INCOMING, Target.SWEEP_HZ, listOf(knot(0.0, t.compact.lowPassHz), knot(mid, SegueFormat.SWEEP_OPEN_HZ, KnotForm.Octave), knot(1.0, SegueFormat.SWEEP_OPEN_HZ)))
                }
            }
            else -> return fade()
        }
        if (eq) out += bassSwap(mid, j)
        return out
    }

    /** A plain equal-power fade: nothing but the two levels. */
    fun fade(): List<SeguePlan.Lane> {
        val xs = grid(0.0, 1.0)
        return listOf(level(LaneSide.OUTGOING, xs) { cos(PI / 2 * it) }, level(LaneSide.INCOMING, xs) { sin(PI / 2 * it) })
    }
}
