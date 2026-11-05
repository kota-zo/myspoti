/* SPDX-License-Identifier: GPL-3.0-only */
package app.myspoti.automix.segue

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

/** How a transition sounds: each is lanes in the plan, so the engine has nothing to know of them. */
enum class SegueShape {
    /** Locked: the bass changes hands at one point, over level ramps. */
    BASS_SWAP,
    /** Locked: bass, then middle, then top change hands one after the other. */
    THREE_BAND,
    /** Locked: one song, then the other, cut on a shared downbeat. */
    CUT_ON_BEAT,
    /** Locked: the incoming song opens from a closed filter up to the bass hand-over. */
    FILTER_OPEN,
    /** Fade: the outgoing song filtered away while the incoming one rises. */
    SWEEP_OUT,
    /** Fade: both levels equal-power over the window, the bass handed over. */
    EVEN_FADE,
    /** Fade: the outgoing song leaves early, for a voice in its way. */
    QUICK_OUT,
    /** Fade: the incoming song is up early and the outgoing one stays until late. */
    LATE_OUT,
}

/** Writes each class's treatment as lanes: every curve the engine plays is data in the plan. */
internal class SegueCompiler(private val t: SegueTuning) {
    private fun knot(x: Double, value: Double?, form: KnotForm = KnotForm.Ramp) = SeguePlan.Knot(
        (x * SegueFormat.T_MAX).roundToInt().coerceIn(0, SegueFormat.T_MAX),
        value?.let { (it * SegueFormat.VALUE_SCALE).roundToLong() }, form)

    /** Sorted, one knot per position (the last written), covering the window, the first knot a step. */
    private fun lane(side: LaneSide, target: Target, knots: List<SeguePlan.Knot>): SeguePlan.Lane {
        val one = knots.withIndex().groupBy { it.value.t }.toSortedMap().values.map { it.last().value }
        val first = one.first().copy(form = KnotForm.Step)
        return SeguePlan.Lane(side, target, target.unit, listOf(first) + one.drop(1))
    }

    private fun db(gain: Double): Double? = if (gain <= 1e-4) null else 20 * log10(gain)
    private fun smooth(x: Double): Double { val u = x.coerceIn(0.0, 1.0); return u * u * (3 - 2 * u) }

    /** Both level lanes from gains, with the summed squared gains bounded by one. */
    private fun levels(positions: List<Double>, gains: (Double) -> Pair<Double, Double>): List<SeguePlan.Lane> {
        val a = ArrayList<SeguePlan.Knot>(); val b = ArrayList<SeguePlan.Knot>()
        for (x in positions.distinct().sorted()) {
            val (ga, gb) = gains(x)
            val power = ga * ga + gb * gb
            val scale = if (power > 1) 1 / sqrt(power) else 1.0
            a += knot(x, db(ga * scale)); b += knot(x, db(gb * scale))
        }
        return listOf(lane(LaneSide.OUTGOING, Target.LEVEL, a), lane(LaneSide.INCOMING, Target.LEVEL, b))
    }

    private fun positions(vararg extra: Double): List<Double> =
        List(t.curveKnots + 1) { it.toDouble() / t.curveKnots } + extra.toList()

    private fun matched(x: Double, swap: Double, matchA: Double, matchB: Double, a: Double, b: Double): Pair<Double, Double> {
        // A eased towards B over the first half, B brought back to its own level after the swap.
        val down = 1 - (1 - matchA) * smooth(x / t.matchEaseShare)
        val up = if (x <= swap || swap >= 1) matchB else matchB + (1 - matchB) * smooth((x - swap) / (1 - swap))
        return a * down to b * up
    }

    fun locked(shape: SegueShape, swap: Double, beatShare: Double, matchA: Double, matchB: Double, eq: Boolean): List<SeguePlan.Lane> = when (shape) {
        SegueShape.THREE_BAND -> threeBand(swap, beatShare, matchA, matchB, eq)
        SegueShape.CUT_ON_BEAT -> cutOnBeat(swap, beatShare, matchA, matchB)
        SegueShape.FILTER_OPEN -> bassSwap(swap, beatShare, matchA, matchB, eq).let { lanes ->
            if (eq) lanes + lane(LaneSide.INCOMING, Target.SWEEP_HZ, listOf(knot(0.0, t.sweepClosedHz), knot(swap, null, KnotForm.Octave), knot(1.0, null))) else lanes
        }
        else -> bassSwap(swap, beatShare, matchA, matchB, eq)
    }

    /** Bass swapped at one point, level ramps; the top ends hand over too. */
    private fun bassSwap(swap: Double, beatShare: Double, matchA: Double, matchB: Double, eq: Boolean): List<SeguePlan.Lane> {
        val out = ArrayList(levels(positions(swap)) { x ->
            val after = ((x - swap) / (1 - swap)).coerceIn(0.0, 1.0)
            val ga = if (x < swap) 1.0 else cos(PI / 2 * after)
            val gb = if (x < swap) t.incomingBedGain * sin(PI / 2 * (x / swap)) else t.incomingBedGain + (1 - t.incomingBedGain) * after
            matched(x, swap, matchA, matchB, ga, gb)
        })
        if (eq) {
            val end = (swap + beatShare * t.bassSwapBeats).coerceAtMost(1.0)
            out += bassHandOver(swap, end)
            out += lane(LaneSide.OUTGOING, Target.SHELF_TOP, listOf(knot(0.0, 0.0), knot(t.outgoingTopFrom, 0.0), knot(1.0, t.outgoingTopDb)))
            out += lane(LaneSide.INCOMING, Target.SHELF_TOP, listOf(knot(0.0, t.incomingTopDb), knot(swap, 0.0), knot(1.0, 0.0)))
        }
        return out
    }

    /** Each band leaves the outgoing song as it arrives from the incoming one, lowest first at the swap. */
    private fun threeBand(swap: Double, beatShare: Double, matchA: Double, matchB: Double, eq: Boolean): List<SeguePlan.Lane> {
        val out = ArrayList(levels(positions(swap)) { x ->
            val ga = cos(PI / 2 * ((x - swap) / (1 - swap)).coerceIn(0.0, 1.0))
            val gb = sin(PI / 2 * (x / swap).coerceIn(0.0, 1.0))
            matched(x, swap, matchA, matchB, ga, gb)
        })
        if (eq) {
            out += bassHandOver(swap, (swap + beatShare * t.bassSwapBeats).coerceAtMost(1.0))
            val coreFrom = swap * 0.8; val coreTo = swap + (1 - swap) * 0.5
            out += lane(LaneSide.OUTGOING, Target.BAND_CORE, listOf(knot(0.0, 0.0), knot(coreFrom, 0.0), knot(coreTo, t.coreOutDb), knot(1.0, t.coreOutDb)))
            out += lane(LaneSide.INCOMING, Target.BAND_CORE, listOf(knot(0.0, t.coreInDb), knot(coreFrom, t.coreInDb), knot(coreTo, 0.0), knot(1.0, 0.0)))
            out += lane(LaneSide.OUTGOING, Target.SHELF_TOP, listOf(knot(0.0, 0.0), knot(coreTo, 0.0), knot(1.0, t.outgoingTopDb)))
            out += lane(LaneSide.INCOMING, Target.SHELF_TOP, listOf(knot(0.0, t.topInDb), knot(swap, 0.0), knot(1.0, 0.0)))
        }
        return out
    }

    /** One song, then the other: the cut sits on the swap's downbeat, and the incoming song comes back to its level after. */
    private fun cutOnBeat(swap: Double, beatShare: Double, matchA: Double, matchB: Double): List<SeguePlan.Lane> {
        val end = (swap + beatShare * t.cutBeatShare).coerceAtMost(1.0)
        return listOf(
            lane(LaneSide.OUTGOING, Target.LEVEL, listOf(knot(0.0, db(matchA)), knot(swap, db(matchA)), knot(end, null), knot(1.0, null))),
            lane(LaneSide.INCOMING, Target.LEVEL, listOf(knot(0.0, null), knot(swap, null), knot(end, db(matchB)), knot(1.0, 0.0))),
        )
    }

    private fun bassHandOver(from: Double, to: Double): List<SeguePlan.Lane> = listOf(
        lane(LaneSide.OUTGOING, Target.SHELF_BOTTOM, listOf(knot(0.0, 0.0), knot(from, 0.0), knot(to, t.bassKillDb), knot(1.0, t.bassKillDb))),
        lane(LaneSide.INCOMING, Target.SHELF_BOTTOM, listOf(knot(0.0, t.bassKillDb), knot(from, t.bassKillDb), knot(to, 0.0), knot(1.0, 0.0))),
    )

    /** Where a fade's bass hand-over sits, 0..1 of the window. */
    fun swapOf(shape: SegueShape): Double = when (shape) {
        SegueShape.QUICK_OUT -> t.filteredSwapShare * 0.5
        SegueShape.LATE_OUT -> (t.filteredSwapShare + 1) / 2
        else -> t.filteredSwapShare
    }

    fun filtered(shape: SegueShape, beatShare: Double, matchA: Double, matchB: Double, eq: Boolean): List<SeguePlan.Lane> {
        val swap = swapOf(shape)
        if (shape == SegueShape.SWEEP_OUT) return sweepOut(swap, beatShare, matchA, matchB, eq)
        // The outgoing song leaves by here and the incoming one is up by there.
        val (outFrom, outTo, inTo) = when (shape) {
            SegueShape.QUICK_OUT -> Triple(0.0, swap * 2, 0.85)
            SegueShape.LATE_OUT -> Triple(swap - 0.2, 1.0, swap)
            else -> Triple(0.0, 1.0, 1.0)
        }
        val out = ArrayList(levels(positions(swap)) { x ->
            val ga = cos(PI / 2 * ((x - outFrom) / (outTo - outFrom)).coerceIn(0.0, 1.0))
            val gb = sin(PI / 2 * (x / inTo).coerceIn(0.0, 1.0))
            matched(x, swap, matchA, matchB, ga, gb)
        })
        if (eq) {
            out += bassHandOver(swap, (swap + beatShare * t.bassSwapBeats).coerceAtMost(1.0))
            out += lane(LaneSide.INCOMING, Target.SHELF_TOP, listOf(knot(0.0, t.filteredIncomingTopDb), knot(swap, 0.0), knot(1.0, 0.0)))
        }
        return out
    }

    /** The outgoing song filtered away, a limited overlap, the incoming one rising without its bass. */
    private fun sweepOut(swap: Double, beatShare: Double, matchA: Double, matchB: Double, eq: Boolean): List<SeguePlan.Lane> {
        val out = ArrayList(levels(positions(swap)) { x ->
            val after = ((x - swap) / (1 - swap)).coerceIn(0.0, 1.0)
            val ga = if (x < swap) 1.0 else cos(PI / 2 * after)
            val gb = if (x < swap) t.filteredBedGain * sin(PI / 2 * (x / swap)) else t.filteredBedGain + (1 - t.filteredBedGain) * after
            matched(x, swap, matchA, matchB, ga, gb)
        })
        if (eq) {
            out += bassHandOver(swap, (swap + beatShare * t.bassSwapBeats).coerceAtMost(1.0))
            out += lane(LaneSide.INCOMING, Target.SHELF_TOP, listOf(knot(0.0, t.filteredIncomingTopDb), knot(swap, 0.0), knot(1.0, 0.0)))
            out += lane(LaneSide.OUTGOING, Target.SWEEP_HZ, listOf(knot(0.0, null), knot(swap, null), knot(1.0, t.sweepClosedHz, KnotForm.Octave)))
            out += lane(LaneSide.OUTGOING, Target.SWEEP_PEAK, listOf(knot(0.0, null), knot(swap, null), knot(1.0, t.sweepPeakRatio)))
        }
        return out
    }

    /** A cut: an anti-click fade and nothing else. */
    fun hard(): List<SeguePlan.Lane> = listOf(
        lane(LaneSide.OUTGOING, Target.LEVEL, listOf(knot(0.0, 0.0), knot(1.0, null))),
        lane(LaneSide.INCOMING, Target.LEVEL, listOf(knot(0.0, null), knot(1.0, 0.0))),
    )

    /** A plain equal-power fade. */
    fun fade(): List<SeguePlan.Lane> = levels(positions()) { x -> cos(PI / 2 * x) to sin(PI / 2 * x) }
}
