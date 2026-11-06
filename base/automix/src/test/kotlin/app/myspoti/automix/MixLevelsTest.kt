/* SPDX-License-Identifier: GPL-3.0-only */
package app.myspoti.automix

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class MixLevelsTest {
    @Test fun longBlendStillHandsOverBassWithinOneBeat() {
        val beat = 1.0 / 128
        val plan = MixPlan(MixPlan.Style.BLEND, 100.0, 0.0, 64.0, 1.0, 128, 0.5, beat, 1.0, 0.0, "")
        assertEquals(0.0, plan.levels(0.5).lowA, 1e-9)
        assertEquals(MixPlan.KILL, plan.levels(0.5).lowB, 1e-9)
        assertEquals(MixPlan.KILL, plan.levels(0.5 + beat).lowA, 1e-9)
        assertEquals(0.0, plan.levels(0.5 + beat).lowB, 1e-9)
    }

    @Test fun overlapNeverAddsAnEnergySwellEvenWithoutEq() {
        for (style in MixPlan.Style.entries) for (swap in listOf(0.25, 0.5, 0.75)) {
            for (gainA in listOf(0.5, 1.0)) for (gainB in listOf(0.5, 1.0)) {
                val plan = MixPlan(style, 100.0, 2.0, 32.0, 1.0, 64, swap, 1.0 / 64,
                    gainB, 0.0, "", matchA = gainA)
                var previous = plan.levels(0.0)
                for (step in 0..1000) {
                    val at = step / 1000.0
                    val levels = plan.levels(at)
                    assertTrue("$style at $at", levels.gainA.isFinite() && levels.gainB.isFinite())
                    assertTrue("$style energy at $at", levels.gainA * levels.gainA + levels.gainB * levels.gainB <= 1.000001)
                    assertTrue("$style continuity A at $at", abs(levels.gainA - previous.gainA) < 0.07)
                    assertTrue("$style continuity B at $at", abs(levels.gainB - previous.gainB) < 0.07)
                    previous = levels
                }
                assertEquals(1.0, plan.levels(0.0).gainA, 0.0)
                assertEquals(0.0, plan.levels(0.0).gainB, 0.0)
                assertEquals(0.0, plan.levels(1.0).gainA, 0.0)
                assertEquals(gainB, plan.levels(1.0).gainB, 0.0)
            }
        }
    }
}
