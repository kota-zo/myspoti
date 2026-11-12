/* SPDX-License-Identifier: GPL-3.0-only */
package app.myspoti.automix.segue

import app.myspoti.automix.TrackAnalysis
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow

class LocalLevelsTest {
    private fun song(levels: FloatArray, bars: DoubleArray = DoubleArray(levels.size) { it * 2.0 }): TrackAnalysis =
        TrackAnalysis(bars.last() + 2.0, 0.0, bars.last() + 2.0, -12.0, -1, 0.0, 120.0, true,
            null, null, DoubleArray(0), bars, levels, levels, levels, FloatArray(levels.size),
            FloatArray(levels.size), 0, false, Double.NaN, Double.NaN)

    private fun measure(a: Float, b: Float) = LocalLevels.measure(song(floatArrayOf(a, a)), 0.0, 4.0,
        song(floatArrayOf(b, b)), 0.0, 4.0)

    @Test fun partialAndUnequalBarsAreWeightedInPowerNotDecibels() {
        val s = song(floatArrayOf(-10f, -20f), doubleArrayOf(0.0, 1.0))
        val expected = 10 * log10((0.5 * 0.1 + 2.0 * 0.01) / 2.5)
        assertEquals(expected, LocalLevels.level(s, 0.5, 3.0)!!, 1e-8)
        assertNull(LocalLevels.level(s, 3.0, 4.0))
    }

    @Test fun localOpeningRatherThanTheSongLevelControlsTheBalance() {
        val a = song(FloatArray(16) { -12f })
        val b = song(FloatArray(16) { if (it < 2) -24f else -12f })
        val opening = LocalLevels.measure(a, 0.0, 4.0, b, 0.0, 4.0)
        assertEquals(-9.0, opening.outgoingDb, 1e-9)
        assertTrue(opening.gradualHandover)
        val later = LocalLevels.measure(a, 0.0, 4.0, b, 8.0, 12.0)
        assertEquals(0.0, later.outgoingDb, 0.0)
        assertEquals(0.0, later.incomingDb, 0.0)
    }

    @Test fun quietMissingOrNonfiniteMeasurementsDoNotRequestNormalization() {
        assertEquals(0.0, measure(-12f, -70f).outgoingDb, 0.0)
        assertEquals(0.0, measure(-12f, Float.NaN).incomingDb, 0.0)
        assertEquals(0.0, measure(-12f, -14f).outgoingDb, 0.0)
        assertEquals(-9.0, measure(-40f, -10f).incomingDb, 0.0)
        assertEquals(0.0, LocalLevels.placementCost(-12.0, -14.0, 0.025), 0.0)
        assertEquals(0.05, LocalLevels.placementCost(-12.0, -40.0, 0.025), 0.0)
        assertEquals(0.0, LocalLevels.placementCost(-12.0, -70.0, 0.025), 0.0)
    }

    @Test fun gainChangesCannotBoostOrLeaveAnIncomingEndGainForThePlayerToDrop() {
        for (shape in SegueShape.entries.filter { it != SegueShape.PLAIN_FADE }) {
            val raw = CompactLanes(SegueTuning()).locked(shape, 4_000_000, false)
            for (balance in listOf(measure(-12f, -24f), measure(-24f, -12f))) {
                val balanced = LocalLevels.apply(raw, balance)
                for (side in LaneSide.entries) {
                    val before = LaneCurve(raw.first { it.target == Target.LEVEL && it.side == side })
                    val after = LaneCurve(balanced.first { it.target == Target.LEVEL && it.side == side })
                    for (step in 0..1000) {
                        val x = step * 1000.0
                        assertEquals(before.isNull(x), after.isNull(x))
                        if (!after.isNull(x)) assertTrue("$shape $side at $x", after.value(x) <= before.value(x) + 0.02)
                    }
                    if (side == LaneSide.INCOMING) {
                        assertEquals(0.0, after.value(SegueFormat.T_MAX.toDouble()), 0.0)
                        assertEquals(0.0, after.value(SegueFormat.T_MAX + 1.0), 0.0)
                        assertTrue(after.isNull(0.0))
                    } else {
                        assertEquals(0.0, after.value(0.0), 0.0)
                        assertTrue(after.isNull(SegueFormat.T_MAX.toDouble()))
                    }
                }
            }
        }
    }

    @Test fun incomingLoudPassageRisesProgressivelyAndFinishesExactlyNeutral() {
        val raw = CompactLanes(SegueTuning()).fade()
        val balanced = LocalLevels.apply(raw, measure(-24f, -12f))
        val lane = LaneCurve(balanced.first { it.side == LaneSide.INCOMING })
        val atHalf = 10.0.pow(lane.value(SegueFormat.T_MAX / 2.0) / 20)
        assertTrue(atHalf < 0.5)
        val near = 10.0.pow(lane.value(SegueFormat.T_MAX - 1.0) / 20)
        assertTrue(abs(1 - near) < 1e-5)
    }
}
