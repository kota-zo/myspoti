/* SPDX-License-Identifier: GPL-3.0-only */
package app.myspoti.automix.segue

import kotlin.math.exp

/** A term rewarded: its weight, how far its measurement can be trusted, and its note, all 0..1 but the weight. */
data class SegueNote(val name: String, val weight: Double, val reliability: Double, val note: Double)

/** A term punished: its penalty and its defect, 0..1. */
data class SegueDefect(val name: String, val penalty: Double, val defect: Double)

/** score = Σ weight × reliability × note − Σ penalty × defect. An uncertain measure weighs less; it does not count as average. */
class SegueScore(val notes: List<SegueNote>, val defects: List<SegueDefect>) {
    val total: Double = notes.sumOf { it.weight * it.reliability.coerceIn(0.0, 1.0) * it.note.coerceIn(0.0, 1.0) } -
        defects.sumOf { it.penalty * it.defect.coerceIn(0.0, 1.0) }

    companion object {
        /** Full up to [full], falling to 0 at [zero]. */
        fun fallingFrom(value: Double, full: Double, zero: Double): Double =
            if (value <= full) 1.0 else if (value >= zero || zero <= full) 0.0 else (zero - value) / (zero - full)

        /** 0 up to [free], rising to 1 at [full]. */
        fun risingFrom(value: Double, free: Double, full: Double): Double = 1.0 - fallingFrom(value, free, full)

        fun gaussian(distance: Double, sigma: Double): Double = exp(-distance * distance / (2 * sigma * sigma))
    }
}
