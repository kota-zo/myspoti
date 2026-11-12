/* SPDX-License-Identifier: GPL-3.0-only */
package app.myspoti.automix.segue

import app.myspoti.automix.DurationBounds
import app.myspoti.automix.TrackAnalysis
import org.junit.Assert.*
import org.junit.Test

class QuietArrivalFadeTest {
    private val tuning = SegueTuning()
    private fun song(level: (Int) -> Float = { -10f }, voice: Float = 0f): TrackAnalysis =
        TrackAnalysis(60.0, 0.0, 60.0, -10.0, -1, 0.0, 120.0, false, null, null,
            DoubleArray(0), DoubleArray(30) { it * 2.0 }, FloatArray(30, level),
            FloatArray(30) { -20f }, FloatArray(30) { -20f }, FloatArray(30), FloatArray(30),
            0, true, Double.NaN, Double.NaN, barVocalRisk = FloatArray(30) { voice })
    private fun intro(arrival: Int = 3, voice: Float = 0f) = song({ if (it < arrival) -40f else -10f }, voice)

    @Test fun earlierExitCoversArrivalWithoutSkippingTheOpeningOrChangingEnd() {
        val a = song()
        val ordinary = CompactPolicy.fadePlace(a, song(), tuning)!!
        val extended = CompactPolicy.fadePlace(a, intro(), tuning)!!
        assertEquals(0L, extended.entryUs)
        assertEquals(8_000_000L, extended.lengthUs)
        assertEquals(ordinary.exitUs + ordinary.lengthUs, extended.exitUs + extended.lengthUs)
        assertTrue(extended.exitUs < ordinary.exitUs)
    }
    @Test fun boundsAndPreparationLeadCanPreventExtension() {
        val a = song(); val b = intro()
        val capped = CompactPolicy.fadePlace(a, b, tuning, bounds = DurationBounds(0.0, 6.0))!!
        assertEquals(5_000_000L, capped.lengthUs)
        val bounded = CompactPolicy.fadePlace(a, b, tuning, bounds = DurationBounds(6.0, 12.0))!!
        assertEquals(8_000_000L, bounded.lengthUs)
        val late = CompactPolicy.fadePlace(a, b, tuning, earliestUs = 55_000_000)!!
        assertTrue(late.exitUs >= 55_000_000)
        assertEquals(2_510_000L, late.lengthUs)
    }
    @Test fun distantRiseTransientAndTwoVoiceRiskKeepOriginalPlacement() {
        val a = song()
        val original = CompactPolicy.fadePlace(a, song(), tuning)!!
        val transient = song(level = { if (it == 3) -10f else if (it < 8) -40f else -10f })
        for (b in listOf(intro(6), transient, song())) {
            val p = CompactPolicy.fadePlace(a, b, tuning)!!
            assertEquals(original.exitUs, p.exitUs)
            assertEquals(original.lengthUs, p.lengthUs)
            assertEquals(original.entryUs, p.entryUs)
        }
        val voices = CompactPolicy.fadePlace(song(voice = 1f), intro(voice = 1f), tuning)!!
        assertEquals(original.lengthUs, voices.lengthUs)
    }
    @Test fun alreadySoftOutgoingPassageKeepsItsOriginalFade() {
        val a = song(level = { -25f })
        val original = CompactPolicy.fadePlace(a, song(), tuning)!!
        val quietIntro = CompactPolicy.fadePlace(a, intro(), tuning)!!
        assertEquals(original.exitUs, quietIntro.exitUs)
        assertEquals(original.lengthUs, quietIntro.lengthUs)
        assertEquals(original.entryUs, quietIntro.entryUs)
    }

    @Test fun twelveSecondCapUsesListenerElapsedTime() {
        val p = CompactPolicy.fadePlace(song(), intro(5), tuning, playbackRate = 0.8)!!
        assertEquals(2_510_000L, p.lengthUs) // 12 source seconds would take 15 listener seconds.
    }
}
