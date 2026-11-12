/* SPDX-License-Identifier: GPL-3.0-only */
package app.myspoti.automix.segue

import app.myspoti.automix.TrackAnalysis
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * A bounded local balance, not whole-song normalization or a compressor. The louder passage
 * is held back while the songs overlap; the incoming lane reaches unity before the player
 * disarms it. No audio is boosted and no post-window player-state change is required.
 */
internal object LocalLevels {
    private const val QUIET_DB = -45.0
    private const val FREE_DB = 3.0
    private const val MAX_CUT_DB = 9.0

    class Balance(val outgoingDb: Double, val incomingDb: Double, val gapDb: Double) {
        val gradualHandover get() = abs(gapDb) >= 6.0
    }

    /** Energy weighted by actual overlap in seconds, including partial bars and unequal bar lengths. */
    internal fun level(s: TrackAnalysis, from: Double, to: Double): Double? {
        if (!from.isFinite() || !to.isFinite() || to <= from || s.bars == 0) return null
        var power = 0.0; var seconds = 0.0
        var bar = max(0, s.barAt(from))
        while (bar < s.bars && s.barStarts[bar] < to) {
            val weight = max(0.0, min(to, s.barEnd(bar)) - max(from, s.barStarts[bar]))
            val db = s.barDb.getOrElse(bar) { Float.NaN }
            if (weight > 0 && db.isFinite()) {
                power += 10.0.pow(db / 10.0) * weight
                seconds += weight
            }
            bar++
        }
        return if (seconds > 0 && power > 0) 10 * log10(power / seconds) else null
    }

    fun measure(a: TrackAnalysis, fromA: Double, toA: Double, b: TrackAnalysis, fromB: Double, toB: Double): Balance {
        return between(level(a, fromA, toA), level(b, fromB, toB))
    }

    private fun between(av: Double?, bv: Double?): Balance {
        // Silence/absent measurements do not request gain riding or a false loudness match.
        if (av == null || bv == null || min(av, bv) < QUIET_DB) return Balance(0.0, 0.0, 0.0)
        val gap = bv - av
        return Balance(-max(0.0, -gap - FREE_DB).coerceAtMost(MAX_CUT_DB),
            -max(0.0, gap - FREE_DB).coerceAtMost(MAX_CUT_DB), gap)
    }

    /** At most two score steps: a small preference alongside the existing placement and vocal costs. */
    fun placementCost(a: Double?, b: Double?, step: Double): Double =
        ((abs(between(a, b).gapDb) - FREE_DB) / 6.0).coerceIn(0.0, 2.0) * step

    /** The endpoint contract stays A=unity/silent and B=silent/unity, including for short windows. */
    fun apply(lanes: List<SeguePlan.Lane>, balance: Balance): List<SeguePlan.Lane> = lanes.map { lane ->
        val db = if (lane.side == LaneSide.OUTGOING) balance.outgoingDb else balance.incomingDb
        if (lane.target != Target.LEVEL || db == 0.0) return@map lane
        val curve = LaneCurve(lane)
        val original = lane.knots.associateBy { it.t }
        val positions = (lane.knots.map { it.t } + (0..32).map { it * SegueFormat.T_MAX / 32 }).distinct().sorted()
        lane.copy(knots = positions.mapIndexed { index, at ->
            val x = at.toDouble() / SegueFormat.T_MAX
            val smooth = x * x * (3 - 2 * x)
            val share = if (lane.side == LaneSide.OUTGOING) smooth else 1 - smooth
            val value = if ((original.containsKey(at) && original.getValue(at).valueMilli == null) || curve.isNull(at.toDouble())) null
                else ((curve.value(at.toDouble()) + db * share) * SegueFormat.VALUE_SCALE).roundToLong()
            SeguePlan.Knot(at, value, if (index == 0) KnotForm.Step else KnotForm.Ramp)
        })
    }
}
