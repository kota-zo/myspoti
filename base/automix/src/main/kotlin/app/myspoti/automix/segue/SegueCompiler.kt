/* SPDX-License-Identifier: GPL-3.0-only */
package app.myspoti.automix.segue

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

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

    /** Bass swapped at one point, level ramps; the top ends hand over too. */
    fun locked(swap: Double, beatShare: Double, matchA: Double, matchB: Double, eq: Boolean): List<SeguePlan.Lane> {
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

    private fun bassHandOver(from: Double, to: Double): List<SeguePlan.Lane> = listOf(
        lane(LaneSide.OUTGOING, Target.SHELF_BOTTOM, listOf(knot(0.0, 0.0), knot(from, 0.0), knot(to, t.bassKillDb), knot(1.0, t.bassKillDb))),
        lane(LaneSide.INCOMING, Target.SHELF_BOTTOM, listOf(knot(0.0, t.bassKillDb), knot(from, t.bassKillDb), knot(to, 0.0), knot(1.0, 0.0))),
    )

    /** The outgoing song filtered away, a limited overlap, the incoming one rising without its bass. */
    fun filtered(swap: Double, beatShare: Double, matchA: Double, matchB: Double, eq: Boolean): List<SeguePlan.Lane> {
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
