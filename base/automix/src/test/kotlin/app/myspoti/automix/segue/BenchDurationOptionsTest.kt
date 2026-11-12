/* SPDX-License-Identifier: GPL-3.0-only */
package app.myspoti.automix.segue

import app.myspoti.automix.DurationBounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BenchDurationOptionsTest {
    @Test fun automaticAndFiniteLimitsReachTheSharedDurationContract() {
        assertEquals(DurationBounds(), SegueBench.durationBounds(emptyList()))
        assertEquals(DurationBounds(), SegueBench.durationBounds(listOf("min-seconds=0", "max-seconds=0")))
        assertEquals(DurationBounds(4.0, 12.0), SegueBench.durationBounds(listOf("min-seconds=4", "max-seconds=12")))
    }

    @Test fun malformedOrConflictingTrialsFailInsteadOfSilentlyIgnoringConstraints() {
        for (options in listOf(listOf("min-seconds=NaN"), listOf("max-seconds=Infinity"),
            listOf("min-seconds=-1"), listOf("min-seconds=4", "max-seconds=2"),
            listOf("max-seconds=4", "max-seconds=8"))) {
            assertThrows(IllegalArgumentException::class.java) { SegueBench.durationBounds(options) }
        }
    }
}
