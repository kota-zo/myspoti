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

    @Test fun djModeLeavesALongQuietOutroEarlyWithoutChangingTempo() {
        fun outgoing(): TrackAnalysis = track().also { a ->
            // Loud until bar 88 (176 s), then 32 bars of quiet outro.
            for (i in 88 until 120) a.barDb[i] = -24f
            a.barNovelty[88] = 3f
        }
        val dj = MixPlanner.plan(outgoing(), track(), MixPlanner.Settings(djMode = true))
        assertEquals(MixPlan.Style.BLEND, dj.style)
        // Out well before the end of the outro, but not before the loud part is (nearly) over.
        assertTrue("$dj", dj.endA < 215 && dj.exitA + dj.length * 0.75 >= 176.0 - 16)
        assertEquals(176.0, MixPlanner.lastLoudEnd(outgoing()), 1e-9)
        assertEquals(1.0, dj.speedB, 1e-9)
    }

    @Test fun djModeDoesNotSkipTheLastLoudSectionForAnEarlierBreak() {
        val a = track().also { for (i in 80 until 88) it.barDb[i] = -22f; it.barNovelty[80] = 3f }
        val dj = MixPlanner.plan(a, track(), MixPlanner.Settings(djMode = true, maxSec = 64.0, lengthBias = 1, minBars = 12, maxBars = 32))
        // The break at 160 s is followed by 32 loud bars: A is still clearly heard until at most 8 bars before 240 s.
        assertTrue("$dj", dj.exitA + dj.length * 0.75 >= 240.0 - 8 * 2.0 - 1e-9)
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
        val plan = MixPlanner.plan(track(), slower, MixPlanner.Settings(lengthBias = 1, smoothSec = 14.0, filterSec = 12.0))
        // 120 → 100 BPM (20 %): no stretch, no one-second cut, but a filtered fade with the bass handed over.
        assertEquals(MixPlan.Style.FADE, plan.style)
        assertTrue("$plan", plan.filtered)
        assertEquals(12.0, plan.length, 1e-9)
        assertEquals(1.0, plan.speedB, 1e-9)
        // B's bass comes in on one of its own downbeats (every 2.4 s from its entry).
        val swapB = plan.entryB + plan.swapAt * plan.length
        assertEquals(0.0, Math.floorMod(Math.round((swapB - plan.entryB) * 1000), 2400L).toDouble(), 1.0)
        val before = plan.levels(plan.swapAt - 0.05); val after = plan.levels(plan.swapAt + 0.2)
        assertEquals(MixPlan.KILL, before.lowB, 1e-9); assertEquals(0.0, before.lowA, 1e-9)
        assertEquals(0.0, after.lowB, 1e-9); assertEquals(MixPlan.KILL, after.lowA, 1e-9)
        for (i in 0..100) plan.levels(i / 100.0).let { assertTrue(it.gainA * it.gainA + it.gainB * it.gainB <= 1.0 + 1e-9) }
    }

    @Test fun filteredFadeAvoidsTwoVoicesAndCutOnlyWhenAskedFor() {
        val g = Grid(0.0, 0.6, 400, 1.0, 0.0, 1.0)
        val base = track(risk = { if (it < 6) 0f else 1f })
        val slower = TrackAnalysis(base.duration, base.contentStart, base.contentEnd, base.loudnessDb, base.key, base.keyStrength,
            100.0, true, g, g, base.beats, base.barStarts, base.barDb, base.barLowDb, base.barHighDb, base.barCentre, base.barNovelty,
            0, false, 0.0, 0.0, barVocalRisk = base.barVocalRisk)
        val fade = MixPlanner.plan(track(risk = { 1f }), slower, MixPlanner.Settings(filterSec = 10.0))
        assertTrue("$fade", fade.filtered)
        val co = MixPlanner.coVocal(track(risk = { 1f }), fade.exitA, fade.endA, slower, fade.entryB, fade.entryB + fade.length)
        assertTrue("$fade co=${co.run}", co.run <= fade.length * 0.5)
        assertEquals(MixPlan.Style.CUT, MixPlanner.plan(track(), slower, MixPlanner.Settings(style = MixPlan.Style.CUT)).style)
    }

    @Test fun coVocalMeasuresOnlyVoicesHeardTogether() {
        val a = track(risk = { if (it < 4) 1f else 0f })
        val b = track(risk = { if (it in 4..7) 1f else 0f })
        assertEquals(0.0, MixPlanner.coVocal(a, 0.0, 16.0, b, 0.0, 16.0).total, 1e-9)
        val both = track(risk = { 1f })
        // Over 16 s, the audible middle (15–85 %) is 11.2 s, all of it two voices.
        assertEquals(11.2, MixPlanner.coVocal(both, 0.0, 16.0, both, 0.0, 16.0).run, 1e-6)
    }

    @Test fun twoVoicesForAFewBarsDoNotRuleOutALongMix() {
        // B raps from its bar 12 on: a 32-bar mix from B's start has its voice under A's for the last part only.
        val a = track(risk = { if (it >= 104) 0f else 1f })
        val b = track(risk = { if (it < 12) 0f else 1f })
        val plan = MixPlanner.plan(a, b, long())
        assertEquals(MixPlan.Style.BLEND, plan.style)
        assertTrue("$plan", plan.beats >= 48)
    }

    @Test fun wideStretchKeepsBlendsTo16Bars() {
        val b = track()
        val g = Grid(0.0, 60.0 / 109.0, 430, 1.0, 0.0, 1.0)
        val slower = TrackAnalysis(b.duration, b.contentStart, b.contentEnd, b.loudnessDb, b.key, b.keyStrength,
            109.0, true, g, g, b.beats, b.barStarts, b.barDb, b.barLowDb, b.barHighDb, b.barCentre, b.barNovelty,
            0, false, 0.0, 0.0)
        val plan = MixPlanner.plan(track(), slower, MixPlanner.Settings(maxStretch = 0.12, maxSec = 90.0, lengthBias = 1, minBars = 12, maxBars = 32))
        assertEquals(MixPlan.Style.BLEND, plan.style)
        assertTrue("$plan", plan.beats <= 64 && plan.speedB > 1.08)
    }
}
