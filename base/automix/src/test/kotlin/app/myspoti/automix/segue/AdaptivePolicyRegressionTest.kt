/* SPDX-License-Identifier: GPL-3.0-only */
package app.myspoti.automix.segue

import app.myspoti.automix.Grid
import app.myspoti.automix.TrackAnalysis
import org.junit.Assert.*
import org.junit.Test

/** Checks actual selected passages and playable lanes, including unequal physical bar counts. */
class AdaptivePolicyRegressionTest {
    private fun song(id: String, bpm: Double): TrackAnalysis {
        val period = 60.0 / bpm
        val count = (240 / (4 * period)).toInt()
        val grid = Grid(0.0, period, count * 4, 1.0, 0.0, 1.0)
        return TrackAnalysis(240.0, 0.0, 240.0, -10.0, 0, 1.0, bpm, true,
            grid, grid, DoubleArray(count * 4) { it * period }, DoubleArray(count) { it * period * 4 },
            FloatArray(count) { -10f }, FloatArray(count) { -30f }, FloatArray(count) { -30f },
            FloatArray(count), FloatArray(count), 0, true, 0.0, 0.0,
            audioKey = id, barBassDb = FloatArray(count) { -30f }, headMetre = 1f, tailMetre = 1f)
    }

    @Test fun featureChoiceUsesOnlySelectedPhysicalBarsWhenTemposFold() {
        val a = song("fold-a", 120.0); val b = song("fold-b", 60.0)
        val baseline = SeguePolicy.decide(SegueRequest(a, b)).plan!!
        fun selected(s: TrackAnalysis, side: SeguePlan.Side): IntRange {
            val from = side.anchorUs / 1e6
            val end = from + baseline.span.lengthUs / 1e6 * side.ratePpm / 1e6
            return s.barAt(from + 1e-5)..s.barAt(end - 1e-5)
        }
        val rangeA = selected(a, baseline.outgoing); val rangeB = selected(b, baseline.incoming)
        assertEquals(rangeA.count(), 2 * rangeB.count())
        // Bright decoys everywhere except the selected passage must not affect the bass decision.
        for ((s, range) in listOf(a to rangeA, b to rangeB)) {
            s.barHighDb.fill(-11f)
            for (i in range) { s.barBassDb[i] = -14f; s.barHighDb[i] = -30f }
        }
        val bass = SeguePolicy.decide(SegueRequest(a, b))
        assertEquals(baseline.outgoing.anchorUs, bass.plan!!.outgoing.anchorUs)
        assertEquals(baseline.incoming.anchorUs, bass.plan!!.incoming.anchorUs)
        assertEquals(SegueShape.THIN_SWAP, bass.shape)
        // The same pair and same placement must follow the newly selected spectrum.
        for ((s, range) in listOf(a to rangeA, b to rangeB)) {
            s.barBassDb.fill(-30f)
            s.barHighDb.fill(-30f)
            for (i in range) s.barHighDb[i] = -14f
        }
        assertEquals(SegueShape.DARK_SWAP, SeguePolicy.decide(SegueRequest(a, b)).shape)
    }

    @Test fun vocalShapeActuallySilencesTheOtherDeckOutsideMiddleFifthWithoutEq() {
        val a = song("voice-a", 120.0); val b = song("voice-b", 120.0)
        a.barVocalRisk.fill(1f); b.barVocalRisk.fill(1f)
        val d = SeguePolicy.decide(SegueRequest(a, b, eq = false))
        assertEquals(SegueShape.OVERLAP_SWAP, d.shape)
        val plan = d.plan!!
        fun level(side: LaneSide) = LaneCurve(plan.lanes.single { it.side == side && it.target == Target.LEVEL })
        val incoming = level(LaneSide.INCOMING); val outgoing = level(LaneSide.OUTGOING)
        val scale = SegueFormat.T_MAX.toDouble()
        assertTrue(incoming.isNull(0.3 * scale))
        assertFalse(outgoing.isNull(0.3 * scale))
        assertFalse(incoming.isNull(0.5 * scale))
        assertFalse(outgoing.isNull(0.5 * scale))
        assertFalse(incoming.isNull(0.7 * scale))
        assertTrue(outgoing.isNull(0.7 * scale))
        assertTrue(plan.lanes.all { it.target == Target.LEVEL })
    }
}
