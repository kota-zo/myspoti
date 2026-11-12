/* SPDX-License-Identifier: GPL-3.0-only */
package app.myspoti.automix.segue

import app.myspoti.automix.Grid
import app.myspoti.automix.TrackAnalysis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class SegueStretchTest {
    private val t = SegueTuning()

    /** Bars every 2 s, no grid for the whole song, only the beats given. */
    private fun song(
        key: String,
        beats: DoubleArray = DoubleArray(480) { it * 0.5 },
        down: FloatArray = FloatArray(0),
        duration: Double = 240.0,
        barSeconds: Double = 2.0,
        risk: (Int) -> Float = { 0f },
        grid: Boolean = true,
    ): TrackAnalysis {
        val n = (duration / barSeconds).toInt()
        // The compact planner locks only on a song's own grid (its head and tail): the beats as one.
        val g = if (grid && beats.size > 1) Grid(0.0, beats[1] - beats[0], beats.size, 1.0, 0.0, 1.0) else null
        return TrackAnalysis(duration, 0.0, duration, -10.0, 0, 1.0, 120.0, g != null, g, g, beats, DoubleArray(n) { it * barSeconds },
            FloatArray(n) { -10f }, FloatArray(n) { -16f }, FloatArray(n) { -20f }, FloatArray(n), FloatArray(n), 0, true,
            Double.NaN, Double.NaN, barVocalRisk = FloatArray(n, risk), audioKey = key, barBassDb = FloatArray(n) { -18f },
            headMetre = 1f, tailMetre = 1f, modelBeats = beats, barDownConfidence = down)
    }

    private fun lockedLevels() = setOf(SegueLevel.LOCKED_LONG, SegueLevel.LOCKED_SHORT, SegueLevel.LOCKED_BEAT)

    @Test fun aSteadyStretchIsFittedOnItsOwnBeats() {
        val s = Stretches.measure(song("a"), 10, 4, 1.0, t)!!
        assertEquals(20.0, s.start, 1e-9)
        assertEquals(28.0, s.end, 1e-9)
        assertEquals(0.5, s.grid.period, 1e-9)
        assertEquals(1.0, s.reliability, 1e-9)
    }

    @Test fun scatteredBeatsAreRefused() {
        val beats = DoubleArray(480) { it * 0.5 + if (it % 2 == 0) 0.06 else -0.06 }
        assertNull(Stretches.measure(song("a", beats), 10, 4, 1.0, t))
    }

    @Test fun missingBeatsAreRefused() {
        val beats = DoubleArray(480) { it * 0.5 }.filterIndexed { i, _ -> i !in 40..49 }.toDoubleArray()
        assertNull(Stretches.measure(song("a", beats), 10, 4, 1.0, t))
    }

    @Test fun unsureDownbeatsAreRefused() {
        val unsure = FloatArray(120) { 0.2f }
        assertNull(Stretches.measure(song("a", down = unsure), 10, 4, 1.0, t))
        assertNotNull(Stretches.measure(song("a", down = FloatArray(120) { 0.8f }), 10, 4, 1.0, t))
    }

    @Test fun beatsAlignedAtTheRightRateHaveNoPhaseError() {
        val a = Stretches.measure(song("a"), 100, 4, 1.0, t)!!
        val b = Stretches.measure(song("b"), 0, 4, 1.0, t)!!
        val (median, p95) = Stretches.phase(a, b, 1.0)!!
        assertTrue(median < 1_000 && p95 < 1_000)
        // Off by a tenth of a beat, from the first beat to the last.
        val (_, off) = Stretches.phase(a, b, 1.05)!!
        assertTrue("$off", off > t.phaseMedianMaxUs)
    }

    @Test fun halfTempoIsMeasuredOnTheSparserBeats() {
        val a = Stretches.measure(song("a"), 100, 4, 1.0, t)!!
        val slow = song("b", DoubleArray(240) { it * 1.0 }, barSeconds = 4.0)
        val b = Stretches.measure(slow, 0, 2, 1.0, t)!!
        val (median, _) = Stretches.phase(a, b, 1.0)!!
        assertTrue("$median", median < 1_000)
    }

    @Test fun aSongWithoutAGridIsFaded() {
        // The compact planner locks only on a song's own grid: without one it fades (a limit to revisit, not a design choice).
        val d = SeguePolicy.decide(SegueRequest(song("a", grid = false), song("b")))
        assertEquals(d.why, SegueLevel.SIMPLE_FADE, d.level)
        assertTrue(d.why, d.attempts.any { it.cause == SegueCause.NO_GRID })
    }

    @Test fun theRateIsTheRatioOfTheStretchesAndOnlyTheIncomingSongMoves() {
        val quick = song("b", DoubleArray(500) { it * 0.48 }, barSeconds = 1.92)
        val d = SeguePolicy.decide(SegueRequest(song("a"), quick))
        assertTrue(d.why, d.level in lockedLevels())
        assertEquals(SegueFormat.RATE_NOMINAL, d.plan!!.outgoing.ratePpm)
        assertEquals(960_000.0, d.plan!!.incoming.ratePpm.toDouble(), 2_000.0)
    }

    @Test fun everyShapeIsAValidPlanAndPlayable() {
        val keys = "a".repeat(64)
        val lengthUs = 16_000_000L
        fun plan(lanes: List<SeguePlan.Lane>) = SeguePlan(CompactPolicy.ORIGIN,
            SeguePlan.Side(keys, keys, 0, SegueFormat.RATE_NOMINAL, SegueFormat.RATE_NOMINAL), SeguePlan.Side(keys, keys, 0, SegueFormat.RATE_NOMINAL, SegueFormat.RATE_NOMINAL),
            SeguePlan.Span(lengthUs, 8, UnitKind.BAR, Clock.ELAPSED), SeguePlan.Pulse(PulseState.LOCKED, 120_000_000, 0), lanes)
        for (eq in listOf(true, false)) for (shape in listOf(SegueShape.THIN_SWAP, SegueShape.DARK_SWAP, SegueShape.OVERLAP_SWAP, SegueShape.HANDOVER)) {
            val p = plan(CompactLanes(t).locked(shape, lengthUs, eq))
            assertEquals("$shape", emptyList<SegueViolation>(), SegueValidator.structural(p))
            assertTrue("$shape", SegueDeck.playable(p))
        }
    }

    @Test fun eachShapeHandsOverCompletely() {
        fun level(lanes: List<SeguePlan.Lane>, side: LaneSide, x: Double) =
            LaneCurve(lanes.first { it.side == side && it.target == Target.LEVEL }).value(x * SegueFormat.T_MAX)
        for (shape in listOf(SegueShape.THIN_SWAP, SegueShape.DARK_SWAP, SegueShape.OVERLAP_SWAP, SegueShape.HANDOVER)) {
            val lanes = CompactLanes(t).locked(shape, 16_000_000L, true)
            assertTrue("$shape", abs(level(lanes, LaneSide.OUTGOING, 0.0)) < 0.5)
            assertTrue("$shape", level(lanes, LaneSide.OUTGOING, 1.0) < -40)
            assertTrue("$shape", level(lanes, LaneSide.INCOMING, 0.0) < -40)
            assertTrue("$shape", abs(level(lanes, LaneSide.INCOMING, 1.0)) < 0.5)
        }
    }

    @Test fun theSameSongsGiveTheSameShape() {
        val first = SeguePolicy.decide(SegueRequest(song("a"), song("b")))
        val again = SeguePolicy.decide(SegueRequest(song("a"), song("b")))
        assertEquals(first.shape, again.shape)
        assertEquals(first.fingerprint, again.fingerprint)
    }

    @Test fun anotherAnalysisOfTheBeatsInvalidatesThePlan() {
        val a = song("a"); val b = song("b")
        val p = SeguePolicy.decide(SegueRequest(a, b)).plan!!
        assertTrue(SeguePolicy.stale(p, a, b).isEmpty())
        val moved = song("a", DoubleArray(480) { it * 0.5 + 0.01 })
        assertTrue(SeguePolicy.stale(p, moved, b).isNotEmpty())
    }

    @Test fun aNewFormatKeepsTheModelsBeats() {
        val s = song("a", down = FloatArray(120) { 0.7f })
        val out = java.io.ByteArrayOutputStream(); s.write(out)
        val back = TrackAnalysis.read(out.toByteArray().inputStream())!!
        assertTrue(s.modelBeats.contentEquals(back.modelBeats))
        assertTrue(s.barDownConfidence.contentEquals(back.barDownConfidence))
        assertEquals(s.beatKey, back.beatKey)
    }

}
