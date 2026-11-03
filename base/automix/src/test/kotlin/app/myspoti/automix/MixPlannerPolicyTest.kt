/* SPDX-License-Identifier: GPL-3.0-only */
package app.myspoti.automix

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Controlled analyses isolate scheduling and musical decisions from model accuracy. */
class MixPlannerPolicyTest {
    private fun track(key: Int = 0, risk: (Int) -> Float = { 0f }, tail: Grid? = null): TrackAnalysis {
        val duration = 240.0
        val beat = 0.5
        val grid = Grid(0.0, beat, 480, 1.0, 0.0, 1.0)
        return TrackAnalysis(duration, 0.0, duration, -10.0, key, if (key < 0) 0.0 else 1.0, 120.0,
            true, grid, tail ?: grid, DoubleArray(480) { it * beat }, DoubleArray(120) { it * 2.0 },
            FloatArray(120) { -10f }, FloatArray(120) { -16f }, FloatArray(120) { -20f },
            FloatArray(120), FloatArray(120), 0, false, 0.0, 0.0, barVocalRisk = FloatArray(120, risk))
    }

    private fun long() = MixPlanner.Settings(maxSec = 64.0, lengthBias = 1, minBars = 12, maxBars = 32, keyWeight = 2.0)

    @Test fun unknownKeyDoesNotShortenAnOtherwiseIdenticalLongMix() {
        val known = MixPlanner.plan(track(), track(), long())
        val unknown = MixPlanner.plan(track(-1), track(-1), long())
        assertEquals(MixPlan.Style.BLEND, unknown.style)
        assertEquals(known.length, unknown.length, 1e-9)
        assertEquals(64.0, unknown.length, 1e-9)
    }

    @Test fun safeMinimumLengthWinsOverAnEarlyDropWhenLongerMixesFit() {
        val b = track()
        for (i in 0 until 8) { b.barDb[i] = -25f; b.barLowDb[i] = -31f }
        b.barNovelty[8] = 3f
        val plan = MixPlanner.plan(track(), b, long())
        assertEquals(MixPlan.Style.BLEND, plan.style)
        assertTrue("${plan.beats / 4} bars: $plan", plan.beats >= 48)
    }

    @Test fun longRequestDoesNotForceTwoProminentVoicesTogether() {
        val plan = MixPlanner.plan(track(risk = { 1f }), track(risk = { 1f }), long())
        assertTrue("$plan", plan.style != MixPlan.Style.BLEND || plan.beats < 32)
    }

    @Test fun knownClashCanStillShortenTheMixDespiteLongPreference() {
        val match = MixPlanner.plan(track(), track(), long())
        val clash = MixPlanner.plan(track(), track(6), long())
        assertTrue("match=$match, clash=$clash", clash.beats < match.beats)
    }

    @Test fun voicesInDifferentPartsOfTheOverlapDoNotCollide() {
        val a = track(risk = { if (it < 4) 1f else 0f })
        val b = track(risk = { if (it in 4..7) 1f else 0f })
        assertEquals(0.0, MixPlanner.vocalOverlap(a, 0.0, 16.0, b, 0.0, 16.0), 1e-9)
        assertTrue(MixPlanner.vocalOverlap(a, 0.0, 16.0, a, 0.0, 16.0) > 0.4)
    }

    @Test fun voiceOverlapUsesIncomingEntryAndStretch() {
        val a = track(risk = { if (it < 4) 1f else 0f })
        val b = track(risk = { if (it in 8..15) 1f else 0f })
        assertEquals(0.0, MixPlanner.vocalOverlap(a, 0.0, 8.0, b, 0.0, 16.0), 1e-9)
        assertEquals(1.0, MixPlanner.vocalOverlap(a, 0.0, 8.0, b, 16.0, 32.0), 1e-9)
    }

    @Test fun missedCueReplansToOneThatIsStillAhead() {
        val a = track(); val b = track()
        val first = MixPlanner.plan(a, b, long())
        val earliest = first.exitA + 4.0
        val next = MixPlanner.planAfter(a, b, earliest, long())
        assertNotNull(next)
        assertTrue("$next", next!!.exitA >= earliest)
        assertTrue(next.endA <= a.contentEnd + 0.01)
    }

    @Test fun noPlanIsReturnedAfterTheSongHasEndedForAnyStyle() {
        for (style in MixPlan.Style.values()) {
            assertNull(MixPlanner.planAfter(track(), track(), 240.0, MixPlanner.Settings(style = style)))
        }
    }

    @Test fun lateFallbackStillStartsAheadAndFitsTheRemainingAudio() {
        val plan = MixPlanner.planAfter(track(), track(), 239.0,
            MixPlanner.Settings(style = MixPlan.Style.FADE, smoothSec = 14.0))!!
        assertEquals(239.0, plan.exitA, 1e-9)
        assertEquals(1.0, plan.length, 1e-9)
        assertEquals(240.0, plan.endA, 1e-9)
    }

    @Test fun blendNeverRunsPastTheCheckedOutgoingGrid() {
        val tail = Grid(210.0, 0.5, 40, 1.0, 0.0, 1.0)
        val plan = MixPlanner.plan(track(tail = tail), track(),
            MixPlanner.Settings(maxSec = 64.0, lengthBias = 1, minBars = 12, maxBars = 32, djMode = true))
        assertEquals(MixPlan.Style.BLEND, plan.style)
        assertTrue("$plan", plan.endA <= tail.last + tail.period + 0.01)
    }

    @Test fun djModeCanUseAnEarlierStructuralBreakWithoutChangingTempo() {
        fun outgoing(): TrackAnalysis = track().also { a ->
            for (i in 80 until 88) a.barDb[i] = -22f
            a.barNovelty[80] = 3f
        }
        val preserve = MixPlanner.plan(outgoing(), track())
        val dj = MixPlanner.plan(outgoing(), track(), MixPlanner.Settings(djMode = true))
        assertEquals(MixPlan.Style.BLEND, dj.style)
        assertTrue("preserve=$preserve, DJ=$dj", dj.exitA < preserve.exitA - 10)
        assertEquals(1.0, dj.speedB, 1e-9)
    }

    @Test fun bassHandoverFollowsAStrongArrivalWithinTheMix() {
        val b = track()
        // A bass-only arrival keeps B's earlier melody worth playing: the entry stays at 0.
        for (i in 0 until 8) b.barLowDb[i] = -31f
        b.barNovelty[8] = 3f
        val plan = MixPlanner.plan(track(), b, MixPlanner.Settings(maxSec = 64.0, minBars = 32, maxBars = 32, lengthBias = 1))
        assertEquals(MixPlan.Style.BLEND, plan.style)
        assertEquals(64.0, plan.length, 1e-9)
        assertEquals(0.25, plan.swapAt, 1e-9)
    }

    @Test fun softPassagesWithDifferentTemposGetAnUnstretchedMusicalFade() {
        fun soft(bpm: Double): TrackAnalysis {
            val original = track()
            val beat = 60 / bpm
            val g = Grid(0.0, beat, (240 / beat).toInt(), 1.0, 0.0, 1.0)
            original.barLowDb.fill(-40f); original.barHighDb.fill(-35f)
            return TrackAnalysis(original.duration, original.contentStart, original.contentEnd, original.loudnessDb,
                0, 1.0, bpm, true, g, g, original.beats, original.barStarts, original.barDb, original.barLowDb,
                original.barHighDb, original.barCentre, original.barNovelty, 0, false, 0.0, 0.0)
        }
        val plan = MixPlanner.plan(soft(120.0), soft(100.0), MixPlanner.Settings(lengthBias = 1, smoothSec = 14.0))
        assertEquals(MixPlan.Style.FADE, plan.style)
        assertEquals(14.0, plan.length, 1e-9)
        assertEquals(1.0, plan.speedB, 1e-9)
    }

    @Test fun rhythmicPassagesWithDifferentTemposAreNotForcedIntoALongFade() {
        val b = track()
        val g = Grid(0.0, 0.6, 400, 1.0, 0.0, 1.0)
        val slower = TrackAnalysis(b.duration, b.contentStart, b.contentEnd, b.loudnessDb, b.key, b.keyStrength,
            100.0, true, g, g, b.beats, b.barStarts, b.barDb, b.barLowDb, b.barHighDb, b.barCentre, b.barNovelty,
            0, false, 0.0, 0.0)
        val plan = MixPlanner.plan(track(), slower, MixPlanner.Settings(lengthBias = 1, smoothSec = 14.0))
        assertEquals(MixPlan.Style.CUT, plan.style)
        assertEquals(1.0, plan.length, 1e-9)
    }
}
