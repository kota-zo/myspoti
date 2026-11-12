/* SPDX-License-Identifier: GPL-3.0-only */
package app.myspoti.automix

import app.myspoti.automix.segue.*
import org.junit.Assert.*
import org.junit.Test

class DurationBoundsTest {
    private fun track(grid: Boolean = true): TrackAnalysis {
        val g = Grid(0.0, 0.5, 480, 1.0, 0.0, 1.0)
        return TrackAnalysis(240.0, 0.0, 240.0, -10.0, 0, 1.0, 120.0,
            true, if (grid) g else null, if (grid) g else null,
            DoubleArray(480) { it * 0.5 }, DoubleArray(120) { it * 2.0 },
            FloatArray(120) { -10f }, FloatArray(120) { -16f }, FloatArray(120) { -20f },
            FloatArray(120), FloatArray(120), 0, false, 0.0, 0.0,
            audioKey = "duration-fixture", headMetre = 1f, tailMetre = 1f)
    }
    @Test fun validatesBounds() {
        for ((lo, hi) in listOf(-1.0 to 2.0, Double.NaN to 2.0, Double.POSITIVE_INFINITY to Double.POSITIVE_INFINITY,
            0.0 to 0.0, 0.0 to Double.NaN, 0.0 to Double.NEGATIVE_INFINITY, 3.0 to 2.0)) {
            try { DurationBounds(lo, hi); fail("accepted $lo..$hi") } catch (_: IllegalArgumentException) { }
        }
        assertTrue(DurationBounds().isUnbounded)
    }
    private fun signature(p: MixPlan) = listOf(p.style, p.exitA, p.entryB, p.length, p.speedB, p.beats,
        p.swapAt, p.beatShare, p.matchB, p.matchA, p.restoreSec, p.filtered, p.note)

    @Test fun defaultEntryPointsAreIdentical() {
        val a = track(); val b = track()
        val p = MixPlanner.plan(a, b); val q = MixPlanner.planBounded(a, b)!!
        assertEquals(signature(p), signature(q))
        val x = MixPlanner.planSkip(a, b, 10.0, 1.0); val y = MixPlanner.planSkipBounded(a, b, 10.0, 1.0)!!
        assertEquals(signature(x), signature(y))
        val d = SeguePolicy.decide(SegueRequest(a, b))
        assertEquals(d.fingerprint, SeguePolicy.decide(SegueRequest(a, b, durationBounds = DurationBounds())).fingerprint)
    }
    @Test fun normalStylesSearchAdmissibleDurations() {
        for ((style, seconds) in listOf(MixPlan.Style.BLEND to 8.0, MixPlan.Style.CUT to 0.5, MixPlan.Style.FADE to 12.0)) {
            val s = MixPlanner.Settings(style = style, durationBounds = DurationBounds(seconds, seconds))
            val p = MixPlanner.planBounded(track(), track(), s)!!
            assertEquals(seconds, p.length, 0.0)
            assertEquals(style, p.style)
            assertNull(MixPlanner.planAfter(track(), track(), 239.8, s))
        }
    }
    @Test fun skipsRespectLeadBoundsAndRemainingAudio() {
        for ((style, seconds) in listOf(MixPlan.Style.BLEND to 2.0, MixPlan.Style.CUT to 0.5, MixPlan.Style.FADE to 4.0)) {
            val s = MixPlanner.Settings(style = style, durationBounds = DurationBounds(seconds, seconds))
            val p = MixPlanner.planSkipBounded(track(), track(), 10.0, 1.0, s)!!
            assertTrue(p.exitA >= 11.0); assertEquals(seconds, p.length, 0.0)
            assertEquals(style, p.style)
            assertNull(MixPlanner.planSkipBounded(track(), track(), 239.9, 0.0, s))
        }
        val longer = MixPlanner.planSkipBounded(track(), track(), 10.0, 1.0,
            MixPlanner.Settings(durationBounds = DurationBounds(6.0, 6.0)))!!
        assertEquals(MixPlan.Style.BLEND, longer.style)
        assertEquals(6.0, longer.length, 0.0)
        val s = MixPlanner.Settings(durationBounds = DurationBounds(4.0, 4.0))
        assertEquals(4.0, MixPlanner.planSkipFull(track(), track(), 10.0, 1.0, s)!!.length, 0.0)
    }
    @Test fun impossibleRequestsNeverLeakAnUnboundedFallback() {
        val s = MixPlanner.Settings(durationBounds = DurationBounds(300.0, 301.0))
        assertNull(MixPlanner.planBounded(track(), track(), s))
        assertNull(MixPlanner.planSkipBounded(track(), track(), 10.0, 0.0, s))
        assertNull(MixPlanner.planSkipFull(track(), track(), 10.0, 0.0, s))
        val d = SeguePolicy.decide(SegueRequest(track(), track(), durationBounds = s.durationBounds))
        assertNull(d.plan); assertEquals(SegueLevel.NORMAL, d.level)
        assertTrue(d.attempts.any { it.cause == SegueCause.LENGTH_NOT_ALLOWED })
        try { MixPlanner.plan(track(), track(), s); fail() } catch (_: IllegalArgumentException) { }
        try { MixPlanner.planSkip(track(), track(), 0.0, 0.0, s); fail() } catch (_: IllegalArgumentException) { }
    }
    @Test fun listenerRateKeepsElapsedBoundsOnFadesAndLocks() {
        for (rate in listOf(0.8, 1.25)) {
            val requested = DurationBounds(6.0, 12.0)
            val sourceBounds = requested.forPlaybackRate(rate)
            assertEquals(6.0 * rate, sourceBounds.minSeconds, 0.0)
            assertEquals(12.0 * rate, sourceBounds.maxSeconds, 0.0)
            for (grid in listOf(false, true)) {
                val decision = SeguePolicy.decide(SegueRequest(track(grid), track(grid),
                    listenerRate = (rate * SegueFormat.RATE_NOMINAL).toLong(), durationBounds = requested))
                val elapsed = decision.plan!!.span.lengthUs / 1e6 / rate
                assertTrue("$rate: $elapsed", requested.contains(elapsed))
            }
            val exact = SeguePolicy.decide(SegueRequest(track(false), track(false),
                listenerRate = (rate * SegueFormat.RATE_NOMINAL).toLong(), durationBounds = DurationBounds(10.0, 10.0)))
            assertEquals(10.0, exact.plan!!.span.lengthUs / 1e6 / rate, 1e-9)
            val classic = MixPlanner.planBounded(track(), track(),
                MixPlanner.Settings(style = MixPlan.Style.FADE, durationBounds = sourceBounds))!!
            assertTrue(requested.contains(classic.length / rate))
        }
    }

    @Test fun microsecondLateCueIsNeverReusedWithBounds() {
        val a = track(); val b = track()
        for (style in listOf(MixPlan.Style.BLEND, MixPlan.Style.CUT)) {
            val s = MixPlanner.Settings(style = style, durationBounds = DurationBounds(0.1, 12.0))
            val first = MixPlanner.planBounded(a, b, s)!!
            val earliest = first.exitA + 0.000001
            val next = MixPlanner.planAfter(a, b, earliest, s)
            assertTrue(next == null || next.exitA >= earliest)
        }
    }

    @Test fun compactFadeSupportsLongerMinimumAndExpiredCue() {
        val bounds = DurationBounds(12.0, 12.0)
        val d = SeguePolicy.decide(SegueRequest(track(false), track(false), durationBounds = bounds))
        assertEquals(12_000_000L, d.plan!!.span.lengthUs)
        assertNull(SeguePolicy.decide(SegueRequest(track(), track(), earliestExitUs = 239_900_000,
            durationBounds = bounds)).plan)
        val locked = SeguePolicy.decide(SegueRequest(track(), track(), durationBounds = DurationBounds(4.0, 4.0)))
        assertEquals(4_000_000L, locked.plan!!.span.lengthUs)
    }
}
