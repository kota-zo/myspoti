/* SPDX-License-Identifier: GPL-3.0-only */
package app.myspoti.automix.segue

import app.myspoti.automix.Grid
import app.myspoti.automix.PhaseStats
import app.myspoti.automix.TrackAnalysis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max

class CompactPolicyTest {
    private val t = SegueTuning()

    /** A controlled song: one bar every 4 beats, a steady grid unless told otherwise. */
    private fun song(
        key: String,
        duration: Double = 240.0,
        bpm: Double = 120.0,
        grid: Boolean = true,
        steadiness: Double = 1.0,
        contentStart: Double = 0.0,
        barLevel: (Int) -> Float = { -10f },
        risk: (Int) -> Float = { 0f },
        musicKey: Int = 0,
        beats: DoubleArray? = null,
        modelBeats: DoubleArray = DoubleArray(0),
    ): TrackAnalysis {
        val beat = 60 / bpm
        val n = (duration / (4 * beat)).toInt()
        val g = Grid(0.0, beat, (duration / beat).toInt(), steadiness, 0.0, 1.0)
        return TrackAnalysis(duration, contentStart, duration, -10.0, musicKey, 1.0, bpm, grid,
            if (grid) g else null, if (grid) g else null, beats ?: DoubleArray(g.count) { g.beat(it) }, DoubleArray(n) { it * 4 * beat },
            FloatArray(n, barLevel), FloatArray(n) { -16f }, FloatArray(n) { -20f }, FloatArray(n), FloatArray(n), 0, true,
            if (grid) 0.0 else Double.NaN, if (grid) 0.0 else Double.NaN, barVocalRisk = FloatArray(n, risk),
            audioKey = key, barBassDb = FloatArray(n) { -18f }, headPhase = PhaseStats(3_000, 6_000), tailPhase = PhaseStats(3_000, 6_000),
            headMetre = 1f, tailMetre = 1f, modelBeats = modelBeats)
    }

    private fun decide(a: TrackAnalysis, b: TrackAnalysis, tuning: SegueTuning = t, eq: Boolean = true) =
        SeguePolicy.decide(SegueRequest(a, b, eq = eq, tuning = tuning))

    private fun locked(d: SegueDecision) = d.level.cls == SegueClass.LOCKED

    @Test fun aLargeLocalLevelGapUsesAGradualHandoverWithoutChangingTheCues() {
        val a = song("loud-a")
        val quiet = song("quiet-b", barLevel = { -22f })
        val d = decide(a, quiet)
        assertTrue(d.why, locked(d))
        assertEquals(SegueShape.HANDOVER, d.shape)
        val outgoing = LaneCurve(d.plan!!.lanes.first { it.side == LaneSide.OUTGOING && it.target == Target.LEVEL })
        assertEquals(0.0, outgoing.value(0.0), 0.0)
        assertTrue(outgoing.value(SegueFormat.T_MAX / 2.0) < -4.0)
        assertEquals(SegueShape.HANDOVER, decide(a, quiet, eq = false).shape)
    }

    @Test fun phaseOutliersCannotHideBehindAGoodMedian() {
        assertTrue(CompactPolicy.phaseAllowed(5_000L to 35_000L, t))
        assertTrue(!CompactPolicy.phaseAllowed(5_000L to 60_000L, t))
        assertTrue(!CompactPolicy.phaseAllowed(25_000L to 35_000L, t))
        assertTrue(CompactPolicy.phaseAllowed(null, t)) // No model retains the checked DSP path.
        val grid = Grid(0.0, 0.5, 32, 1.0, 0.0, 1.0)
        val a = Stretch(0, 8, grid, DoubleArray(32) { it * 0.5 + if (it % 8 == 4) 0.03 else 0.0 }, 1.0)
        val b = Stretch(0, 8, grid, DoubleArray(32) { it * 0.5 - if (it % 8 == 4) 0.03 else 0.0 }, 1.0)
        val measured = Stretches.phase(a, b, 1.0)!!
        assertEquals(0L, measured.first)
        assertEquals(60_000L, measured.second)
        assertTrue(!CompactPolicy.phaseAllowed(measured, t))
    }

    @Test fun twoLeadVoicesUseTheShortOverlapRatherThanFullWindowFilters() {
        val d = decide(song("singers-a", risk = { 1f }), song("singers-b", risk = { 1f }))
        assertTrue(d.why, locked(d))
        assertEquals(SegueShape.OVERLAP_SWAP, d.shape)
        assertEquals(SegueShape.OVERLAP_SWAP,
            decide(song("different-a", risk = { 1f }), song("different-b", risk = { 1f })).shape)
    }

    @Test fun strongBassAndBrightPassagesChooseTheirOwnFilterShape() {
        val a = song("same-a"); val b = song("same-b")
        a.barBassDb.fill(-14f); b.barBassDb.fill(-14f)
        assertEquals(SegueShape.THIN_SWAP, decide(a, b).shape)
        a.barBassDb.fill(-24f); b.barBassDb.fill(-24f)
        a.barHighDb.fill(-15f); b.barHighDb.fill(-15f)
        assertEquals(SegueShape.DARK_SWAP, decide(a, b).shape)
        val withoutEq = decide(a, b, eq = false)
        assertTrue(withoutEq.shape == SegueShape.HANDOVER || withoutEq.shape == SegueShape.OVERLAP_SWAP)
    }

    @Test fun theSameTempoLocksAShortWindowJustBeforeTheEnd() {
        val a = song("a"); val d = decide(a, song("b"))
        assertTrue(d.why, locked(d))
        val p = d.plan!!
        assertEquals(CompactPolicy.ORIGIN, p.origin)
        // 2 and 4 bars of a 120 BPM song cost the same: the longer wins.
        assertEquals(4, p.span.musicalUnits)
        val endS = (p.outgoing.anchorUs + p.span.lengthUs) / 1e6
        // The window ends within the last bars of the music, never before two thirds of the song.
        assertTrue("$endS", endS <= a.contentEnd + 2.0 && endS >= a.contentEnd - 16.0)
        assertTrue(p.outgoing.anchorUs >= 0.67 * 240e6)
        // B enters a couple of bars in.
        assertEquals(4_000_000L, p.incoming.anchorUs)
        assertTrue(SegueValidator.against(p, SeguePolicy.facts(a, song("b")).first, SeguePolicy.facts(a, song("b")).second).isEmpty())
    }

    @Test fun aNearTempoLocksWithBsSpeedOnly() {
        val d = decide(song("a"), song("b", bpm = 126.0))
        assertTrue(d.why, locked(d))
        assertEquals(1_000_000L, d.plan!!.outgoing.ratePpm)
        // B's bars are shorter: it slows down to A's.
        assertEquals(120.0 / 126.0, d.plan!!.incoming.ratePpm / 1e6, 0.002)
    }

    @Test fun halfOrDoubleTimeLocksOnlyAroundTheUsualDanceTempo() {
        // 120 against 60: the slower song's bars count two each, B over half as many bars.
        val half = decide(song("a"), song("b", bpm = 60.0))
        assertTrue(half.why, locked(half))
        assertEquals(1.0, half.plan!!.incoming.ratePpm / 1e6, 0.002)
        // 160 against 80: past the range, the beats as tracked do not meet.
        val fast = decide(song("a", bpm = 160.0), song("b", bpm = 80.0))
        assertEquals(SegueLevel.SIMPLE_FADE, fast.level)
        assertTrue(fast.why, fast.attempts.any { it.cause == SegueCause.TEMPO_OUT_OF_REACH })
        val off = t.copy(compact = t.compact.copy(pairFoldMaxBpm = 0.0))
        assertEquals(SegueLevel.SIMPLE_FADE, decide(song("a"), song("b", bpm = 60.0), off).level)
        // Double time past what a stretch may hold is not even measured.
        assertEquals(SegueLevel.SIMPLE_FADE, decide(song("a"), song("b", bpm = 240.0)).level)
    }

    @Test fun moreThanTenPercentApartFallsBack() {
        assertEquals(SegueLevel.SIMPLE_FADE, decide(song("a"), song("b", bpm = 134.0)).level)
        assertTrue(locked(decide(song("a"), song("b", bpm = 131.0))))
    }

    @Test fun anUnreliableBeatIsNeverLocked() {
        val d = decide(song("a"), song("b", steadiness = 0.3))
        assertEquals(SegueLevel.SIMPLE_FADE, d.level)
        assertTrue(d.why, d.attempts.any { it.cause == SegueCause.NO_GRID && it.detail == "incoming" })
        assertEquals(SegueLevel.SIMPLE_FADE, decide(song("a", grid = false), song("b")).level)
    }

    @Test fun theFadeIsCentredOnTheEndOfTheMusic() {
        // The music stops at 200 s, the file runs to 240 s.
        val a = song("a", grid = false, barLevel = { if (it < 100) -10f else -60f })
        val d = decide(a, song("b", contentStart = 4.0))
        assertEquals(SegueLevel.SIMPLE_FADE, d.level)
        assertEquals(SegueShape.PLAIN_FADE, d.shape)
        val p = d.plan!!
        // The end of its last loud bar, a little after it, less half the fade.
        assertEquals(200_000_000L + t.compact.fadeEndOffsetUs - 2_500_000L, p.outgoing.anchorUs)
        assertEquals(5_000_000L, p.span.lengthUs)
        assertEquals(1_500_000L, p.incoming.anchorUs)
        assertEquals(PulseState.FREE, p.pulse.state)
        // Levels only: no filter or band in a plain fade.
        assertEquals(setOf(Target.LEVEL), p.lanes.map { it.target }.toSet())
    }

    @Test fun theFadeIsClippedByTheFileEndAndKeepsItsFloor() {
        val d = decide(song("a", grid = false), song("b", contentStart = 1.0))
        val p = d.plan!!
        assertEquals(240_000_000L - t.compact.fadeMinUs, p.outgoing.anchorUs)
        assertEquals(t.compact.fadeMinUs, p.span.lengthUs)
        assertEquals(0L, p.incoming.anchorUs)
    }

    @Test fun equalScoresGoToTheLongerWindowThenTheEarlierExit() {
        // Length free: the longest window that fits wins.
        val free = decide(song("a"), song("b"), t.copy(compact = t.compact.copy(lengthSlope = 0.0, lengthCurve = 0.0)))
        assertEquals(SegueLevel.LOCKED_LONG, free.level)
        assertEquals(16, free.plan!!.span.musicalUnits)
        // Every exit of the zone equal: the earliest, the window ending 16 bars before the end.
        val quietEnd = song("a", barLevel = { if (it >= 100) -20f else -10f })
        // Match the quiet tail's local level so this remains a placement tie, independent of balance.
        val flat = decide(quietEnd, song("b", barLevel = { -20f }), t.copy(compact = t.compact.copy(exitBestFrom = -1.0, exitBestTo = 16.0)))
        assertEquals(4, flat.plan!!.span.musicalUnits)
        assertEquals(200_000_000L, flat.plan!!.outgoing.anchorUs)
        // A slow song's 4 bars cost more than its 2.
        assertEquals(2, decide(song("a", bpm = 60.0), song("b", bpm = 60.0)).plan!!.span.musicalUnits)
    }

    @Test fun aQuietOpeningWithoutBeatsBorrowsTheGridThatFollowsIt() {
        // No beat in B's first 30 s, which are quiet: its first bars cannot be measured on their own.
        val b = song("b", barLevel = { if (it < 15) -30f else -10f }, beats = DoubleArray(420) { 30.0 + it * 0.5 })
        // The opening not left out (that is another rule), so the entry stays in it.
        val noSkip = t.copy(compact = t.compact.copy(introMinBars = 1_000))
        val d = decide(song("a"), b, noSkip)
        assertTrue(d.why, locked(d))
        assertTrue(d.plan!!.incoming.anchorUs < 30_000_000)
        // Without the borrowed grid, nothing in reach can be measured.
        val off = decide(song("a"), b, noSkip.copy(compact = noSkip.compact.copy(extendGapBars = -1)))
        assertEquals(SegueLevel.SIMPLE_FADE, off.level)
        assertTrue(off.why, off.attempts.any { it.detail == "no incoming stretch" })
    }

    @Test fun beatsTrackedAtHalfTheBarsLevelAreFilledIn() {
        // The model kept every other beat of A: two a bar where the bars hold four.
        val a = song("a", modelBeats = DoubleArray(240) { it * 1.0 })
        assertNull(Stretches.measure(a, 110, 2, 1.0, t))
        assertTrue(locked(decide(a, song("b"))))
        assertEquals(SegueLevel.SIMPLE_FADE, decide(a, song("b"), t.copy(compact = t.compact.copy(halfModelBeats = false, extendGapBars = -1))).level)
    }

    @Test fun theIsolatorSumsFlatAndKillsOnlyTheBass() {
        val rate = 48_000
        fun db(x: FloatArray, from: Int) = 10 * kotlin.math.log10(x.drop(from).sumOf { it.toDouble() * it } / (x.size - from))
        fun through(hz: Double, gainDb: Long): Double {
            val plan = SeguePlan(CompactPolicy.ORIGIN, SeguePlan.Side("a", null, 0, SegueFormat.RATE_NOMINAL), SeguePlan.Side("b", null, 0, SegueFormat.RATE_NOMINAL),
                SeguePlan.Span(10_000_000, null, UnitKind.BAR, Clock.ELAPSED), SeguePlan.Pulse(PulseState.FREE, null, null),
                listOf(SeguePlan.Lane(LaneSide.OUTGOING, Target.BASS_BAND, SegueUnit.DB, listOf(SeguePlan.Knot(0, gainDb, KnotForm.Step), SeguePlan.Knot(SegueFormat.T_MAX, gainDb, KnotForm.Ramp)))))
            val x = FloatArray(rate * 2) { (0.5 * kotlin.math.sin(2 * Math.PI * hz * it / rate)).toFloat() }
            val y = x.copyOf()
            SegueDeck(plan, LaneSide.OUTGOING, rate, 1).process(y, y.size, 0)
            return db(y, rate) - db(x, rate)
        }
        // Whole (a hair under 0 dB, so it is in the chain): flat at, below and above the crossover.
        for (hz in listOf(50.0, 180.0, 1_000.0, 5_000.0)) assertEquals("$hz Hz", 0.0, through(hz, -10), 0.2)
        // Killed: the bass is gone, the rest stays.
        assertTrue(through(40.0, -80_000) < -40)
        assertEquals(0.0, through(2_000.0, -80_000), 0.5)
    }

    @Test fun bReturnsToItsTempoGentlyAfterTheWindow() {
        val bar = 2.0
        // Under half a percent: no return worth hearing.
        assertEquals(0.0, CompactPolicy.restoreSec(1_004_000, bar, t), 0.0)
        // 2 %: the minimum 4 bars would be 0.75 % a bar at the steepest; 6 bars keep it to 0.5 %.
        val sec = CompactPolicy.restoreSec(1_020_000, bar, t)
        assertEquals(12.0, sec, 0.1)
        val m = app.myspoti.automix.MixPlan(app.myspoti.automix.MixPlan.Style.BLEND, 100.0, 10.0, 8.0, 1.02, 16, 0.5, 0.1, 1.0, sec, "")
        val start = 10.0 + 8.0 * 1.02
        var steepest = 0.0
        var at = start
        while (at < start + sec + 1) { steepest = max(steepest, abs(m.speedAfter(at + bar) - m.speedAfter(at))); at += 0.1 }
        assertTrue("$steepest", steepest <= t.compact.restoreMaxPerBar + 1e-4)
        assertEquals(1.02, m.speedAfter(start), 1e-9)
        assertEquals(1.0, m.speedAfter(start + sec), 1e-9)
        // A compact lock carries it to the player.
        val d = decide(song("a"), song("b", bpm = 126.0))
        assertTrue(d.restoreSec > 0)
    }

    @Test fun aLongQuietOpeningIsLeftOut() {
        // The rule is off by default; switched on with the bar count it was tried with.
        val on = t.copy(compact = t.compact.copy(introMinBars = 8))
        // 16 quiet bars (32 s), then the song: B enters where it arrives.
        val d = decide(song("a"), song("b", barLevel = { if (it < 16) -24f else -10f }), on)
        assertTrue(d.why, locked(d))
        assertEquals(32_000_000L, d.plan!!.incoming.anchorUs)
        // A short quiet opening keeps the usual entry a couple of bars in.
        assertEquals(4_000_000L, decide(song("a"), song("b", barLevel = { if (it < 4) -24f else -10f }), on).plan!!.incoming.anchorUs)
    }

    @Test fun lengthCostFollowsSeconds() {
        assertEquals(0.0, CompactPolicy.lengthCost(4.0, t), 1e-12)
        assertEquals(0.0, CompactPolicy.lengthCost(5.0, t), 1e-12)
        assertEquals(0.0002 * 36, CompactPolicy.lengthCost(11.0, t), 1e-12)
        assertTrue(CompactPolicy.lengthCost(32.0, t) > CompactPolicy.lengthCost(16.0, t) * 4)
    }

    @Test fun aKeyClashCostsAStep() {
        // C major (8B) against D major (10B): two numbers apart; against G major (9B): neighbours.
        assertTrue(CompactPolicy.keyClash(song("a"), song("b", musicKey = 2)))
        assertTrue(!CompactPolicy.keyClash(song("a"), song("b", musicKey = 7)))
    }

    @Test fun theShapeIsTheSameForThePairAndPlainWithoutEq() {
        val a = song("a"); val b = song("b")
        assertEquals(decide(a, b).shape, decide(a, b).shape)
        val plain = decide(a, b, eq = false)
        assertTrue(plain.shape == SegueShape.OVERLAP_SWAP || plain.shape == SegueShape.HANDOVER)
        assertTrue(plain.plan!!.lanes.all { it.target == Target.LEVEL })
        // Over many pairs, every shape comes up.
        val shapes = (0 until 60).mapNotNull { decide(song("a$it"), song("b$it")).shape }.toSet()
        assertEquals(setOf(SegueShape.THIN_SWAP, SegueShape.DARK_SWAP, SegueShape.OVERLAP_SWAP, SegueShape.HANDOVER), shapes)
    }

    @Test fun twoFullFadersNeverSumPastTheCeiling() {
        val rate = 48_000
        val lengthUs = 4_000_000L
        val plan = SeguePlan(CompactPolicy.ORIGIN,
            SeguePlan.Side("a", "ga", 10_000_000, SegueFormat.RATE_NOMINAL), SeguePlan.Side("b", "gb", 0, SegueFormat.RATE_NOMINAL),
            SeguePlan.Span(lengthUs, 2, UnitKind.BAR, Clock.ELAPSED), SeguePlan.Pulse(PulseState.LOCKED, 120_000_000, null),
            CompactLanes(t).locked(SegueShape.DARK_SWAP, lengthUs, true))
        val frames = (lengthUs * rate / 1_000_000).toInt()
        fun square(phase: Int) = FloatArray(frames * 2) { if (((it / 2 + phase) / 40) % 2 == 0) 0.99f else -0.99f }
        val xa = square(0); val xb = square(0)
        // In buffers as a player hands them over.
        val chunk = 1024
        val da = SegueDeck(plan, LaneSide.OUTGOING, rate, 2); val db = SegueDeck(plan, LaneSide.INCOMING, rate, 2)
        var at = 0
        while (at < frames) {
            val n = minOf(chunk, frames - at)
            val ba = xa.copyOfRange(at * 2, (at + n) * 2); val bb = xb.copyOfRange(at * 2, (at + n) * 2)
            da.process(ba, n, 10_000_000 + at * 1_000_000L / rate); db.process(bb, n, at * 1_000_000L / rate)
            ba.copyInto(xa, at * 2); bb.copyInto(xb, at * 2)
            at += n
        }
        var peak = 0.0
        for (i in xa.indices) peak = max(peak, abs(xa[i].toDouble() + xb[i]))
        assertTrue("$peak", peak <= t.headroom.ceiling + 1e-3)
        // Off, the same plan clips.
        val off = t.copy(headroom = Headroom(ceiling = 0.0))
        val ya = square(0); val yb = square(0)
        SegueDeck(plan, LaneSide.OUTGOING, rate, 2, off).process(ya, frames, 10_000_000)
        SegueDeck(plan, LaneSide.INCOMING, rate, 2, off).process(yb, frames, 0)
        assertTrue(ya.indices.any { abs(ya[it] + yb[it]) > 1.0 })
    }
}
