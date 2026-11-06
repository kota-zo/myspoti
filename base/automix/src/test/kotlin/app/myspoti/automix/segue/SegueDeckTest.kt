/* SPDX-License-Identifier: GPL-3.0-only */
package app.myspoti.automix.segue

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

class SegueDeckTest {
    private val rate = 44_100
    private val m = SegueFormat.T_MAX
    private val step = KnotForm.Step
    private val ramp = KnotForm.Ramp

    private fun lane(side: LaneSide, target: Target, vararg k: SeguePlan.Knot) = SeguePlan.Lane(side, target, target.unit, k.toList())
    private fun plan(vararg lanes: SeguePlan.Lane, lengthUs: Long = 1_000_000, anchorA: Long = 1_000_000, anchorB: Long = 1_000_000, rateB: Long = SegueFormat.RATE_NOMINAL) =
        SeguePlan(SeguePlan.Origin("t", "t"), SeguePlan.Side("a", null, anchorA, SegueFormat.RATE_NOMINAL), SeguePlan.Side("b", null, anchorB, rateB),
            SeguePlan.Span(lengthUs, null, UnitKind.BAR, Clock.ELAPSED), SeguePlan.Pulse(PulseState.FREE, null, null), lanes.toList())

    private fun tone(hz: Double, seconds: Double, channels: Int = 1) = FloatArray((seconds * rate).toInt() * channels) { i ->
        (0.5 * sin(2 * PI * hz * (i / channels) / rate)).toFloat()
    }

    private fun rms(x: FloatArray, from: Double, to: Double): Double {
        val a = (from * rate).toInt(); val b = (to * rate).toInt()
        var s = 0.0
        for (i in a until b) s += x[i] * x[i]
        return sqrt(s / (b - a))
    }
    private fun db(x: Double) = 20 * log10(x)

    /** The source runs from 0 for [seconds]; the window starts at 1 s and lasts 1 s. */
    private fun run(p: SeguePlan, side: LaneSide, x: FloatArray, channels: Int = 1, tuning: SegueTuning = SegueTuning()): FloatArray {
        val out = x.copyOf()
        SegueDeck(p, side, rate, channels, tuning).process(out, out.size / channels, 0)
        return out
    }

    @Test fun neutralLanesLeaveTheSamplesExactly() {
        val p = plan(lane(LaneSide.OUTGOING, Target.LEVEL, SeguePlan.Knot(0, 0, step), SeguePlan.Knot(m, 0, ramp)),
            lane(LaneSide.OUTGOING, Target.SHELF_BOTTOM, SeguePlan.Knot(0, null, step), SeguePlan.Knot(m, null, step)))
        val x = tone(440.0, 3.0)
        assertArrayEquals(x, run(p, LaneSide.OUTGOING, x), 0f)
    }

    @Test fun aLaneOfTheOtherSideDoesNothing() {
        val p = plan(lane(LaneSide.INCOMING, Target.LEVEL, SeguePlan.Knot(0, null, step), SeguePlan.Knot(m, null, step)))
        val x = tone(440.0, 3.0)
        assertArrayEquals(x, run(p, LaneSide.OUTGOING, x), 0f)
    }

    @Test fun beforeItsWindowAnIncomingSongIsSilentAndAfterItWhole() {
        val p = plan(lane(LaneSide.INCOMING, Target.LEVEL, SeguePlan.Knot(0, null, step), SeguePlan.Knot(m, 0, ramp)))
        val out = run(p, LaneSide.INCOMING, tone(440.0, 3.0))
        // Automation is read per block, so the rise can begin up to one block early.
        for (i in 0 until rate - SegueTuning().blockFrames) assertEquals(0f, out[i], 0f)
        assertEquals(db(rms(tone(440.0, 3.0), 2.1, 2.9)), db(rms(out, 2.1, 2.9)), 0.01)
    }

    @Test fun afterItsWindowAnOutgoingSongIsSilent() {
        val p = plan(lane(LaneSide.OUTGOING, Target.LEVEL, SeguePlan.Knot(0, 0, step), SeguePlan.Knot(m, null, ramp)))
        val out = run(p, LaneSide.OUTGOING, tone(440.0, 3.0))
        for (i in (2.01 * rate).toInt() until out.size) assertEquals(0f, out[i], 0f)
        val whole = rms(tone(440.0, 3.0), 0.1, 0.9)
        assertEquals(db(whole) - 30, db(rms(out, 1.49, 1.51)), 0.5) // half way to the −60 dB floor
    }

    @Test fun positionsAreSourceTimeUnderTheRate() {
        // At 2× the window lasts 2 s of source: its middle is at 2 s from the anchor at 1 s... 1 + 1.
        val p = plan(lane(LaneSide.INCOMING, Target.LEVEL, SeguePlan.Knot(0, 0, step), SeguePlan.Knot(m, -40_000, ramp)), rateB = 2_000_000)
        val out = run(p, LaneSide.INCOMING, tone(440.0, 4.0))
        assertEquals(db(rms(tone(440.0, 4.0), 0.1, 0.9)) - 20, db(rms(out, 1.99, 2.01)), 0.5)
    }

    @Test fun aSplitBlockSoundsTheSameAsAWholeOne() {
        val p = plan(lane(LaneSide.INCOMING, Target.LEVEL, SeguePlan.Knot(0, null, step), SeguePlan.Knot(m, 0, ramp)),
            lane(LaneSide.INCOMING, Target.SHELF_BOTTOM, SeguePlan.Knot(0, -30_000, step), SeguePlan.Knot(m, 0, ramp)))
        val x = tone(100.0, 3.0)
        val whole = x.copyOf(); SegueDeck(p, LaneSide.INCOMING, rate, 1).process(whole, whole.size, 0)
        val parts = x.copyOf(); val d = SegueDeck(p, LaneSide.INCOMING, rate, 1)
        // Whole numbers of blocks, as the sink's buffers mostly are: same reading points.
        val chunk = 64 * 37
        var at = 0
        while (at < parts.size) {
            val n = minOf(chunk, parts.size - at)
            val piece = parts.copyOfRange(at, at + n)
            d.process(piece, n, Math.round(at * 1e6 / rate))
            piece.copyInto(parts, at); at += n
        }
        for (i in whole.indices) assertEquals(whole[i], parts[i], 1e-3f)
    }

    @Test fun theBassShelfCutsLowsAndSparesHighs() {
        val p = plan(lane(LaneSide.OUTGOING, Target.SHELF_BOTTOM, SeguePlan.Knot(0, -30_000, step), SeguePlan.Knot(m, -30_000, step)))
        val bass = tone(60.0, 2.0); val treble = tone(5_000.0, 2.0)
        assertTrue(db(rms(run(p, LaneSide.OUTGOING, bass), 1.0, 1.9)) < db(rms(bass, 1.0, 1.9)) - 20)
        assertEquals(db(rms(treble, 1.0, 1.9)), db(rms(run(p, LaneSide.OUTGOING, treble), 1.0, 1.9)), 1.0)
    }

    @Test fun theTopShelfAndTheCoreDoTheirOwnBands() {
        val top = plan(lane(LaneSide.OUTGOING, Target.SHELF_TOP, SeguePlan.Knot(0, -20_000, step), SeguePlan.Knot(m, -20_000, step)))
        val core = plan(lane(LaneSide.OUTGOING, Target.BAND_CORE, SeguePlan.Knot(0, -20_000, step), SeguePlan.Knot(m, -20_000, step)))
        val treble = tone(10_000.0, 2.0); val middle = tone(700.0, 2.0)
        assertTrue(db(rms(run(top, LaneSide.OUTGOING, treble), 1.0, 1.9)) < db(rms(treble, 1.0, 1.9)) - 15)
        assertEquals(db(rms(middle, 1.0, 1.9)), db(rms(run(top, LaneSide.OUTGOING, middle), 1.0, 1.9)), 1.0)
        assertTrue(db(rms(run(core, LaneSide.OUTGOING, middle), 1.0, 1.9)) < db(rms(middle, 1.0, 1.9)) - 15)
    }

    @Test fun theSweepClosesTheFilter() {
        val p = plan(lane(LaneSide.OUTGOING, Target.SWEEP_HZ, SeguePlan.Knot(0, null, step), SeguePlan.Knot(m, 400_000, KnotForm.Octave)))
        val x = tone(5_000.0, 3.0)
        val out = run(p, LaneSide.OUTGOING, x)
        assertEquals(db(rms(x, 0.2, 0.9)), db(rms(out, 0.2, 0.9)), 0.1)
        assertTrue(db(rms(out, 2.1, 2.9)) < db(rms(x, 2.1, 2.9)) - 30)
        val low = tone(100.0, 3.0)
        assertEquals(db(rms(low, 2.1, 2.9)), db(rms(run(p, LaneSide.OUTGOING, low), 2.1, 2.9)), 1.0)
    }

    @Test fun theSweepsPeakAccentsItsCutoff() {
        fun swept(peak: Long): Double {
            val p = plan(lane(LaneSide.OUTGOING, Target.SWEEP_HZ, SeguePlan.Knot(0, 1_000_000, step), SeguePlan.Knot(m, 1_000_000, step)),
                lane(LaneSide.OUTGOING, Target.SWEEP_PEAK, SeguePlan.Knot(0, peak, step), SeguePlan.Knot(m, peak, step)))
            return db(rms(run(p, LaneSide.OUTGOING, tone(1_000.0, 2.0)), 1.0, 1.9))
        }
        assertTrue(swept(4_000) > swept(1_000) + 6)
    }

    @Test fun aMicroFadeHasNoClick() {
        val microUs = 30_000L
        val p = plan(lane(LaneSide.INCOMING, Target.LEVEL, SeguePlan.Knot(0, null, step), SeguePlan.Knot(m, 0, ramp)), lengthUs = microUs)
        val out = run(p, LaneSide.INCOMING, tone(1_000.0, 2.0))
        // The sample-to-sample step of the tone itself is about 2πf/sr × 0.5 ≈ 0.07.
        var largest = 0f
        for (i in rate - 10 until rate + 2_000) largest = maxOf(largest, abs(out[i + 1] - out[i]))
        assertTrue("$largest", largest < 0.08f)
        assertEquals(0f, out[rate - SegueTuning().blockFrames - 1], 0f)
        assertEquals(db(rms(tone(1_000.0, 2.0), 1.2, 1.9)), db(rms(out, 1.2, 1.9)), 0.01)
    }

    @Test fun echoAndRoomRingOutAfterTheFader() {
        val fader = lane(LaneSide.OUTGOING, Target.LEVEL, SeguePlan.Knot(0, 0, step), SeguePlan.Knot(m, null, ramp))
        for (send in listOf(Target.SEND_ECHO, Target.SEND_ROOM)) {
            val p = plan(fader, lane(LaneSide.OUTGOING, send, SeguePlan.Knot(0, 500, step), SeguePlan.Knot(m, null, ramp)))
            val x = tone(440.0, 2.0) + FloatArray(rate)
            val out = run(p, LaneSide.OUTGOING, x)
            assertTrue("$send", rms(out, 2.05, 2.25) > 0.001)
        }
        val dry = run(plan(fader), LaneSide.OUTGOING, tone(440.0, 2.0) + FloatArray(rate))
        assertEquals(0.0, rms(dry, 2.05, 2.25), 1e-9)
    }

    @Test fun holdBringsThePlainSongBack() {
        val p = plan(lane(LaneSide.OUTGOING, Target.LEVEL, SeguePlan.Knot(0, null, step), SeguePlan.Knot(m, null, step)))
        val d = SegueDeck(p, LaneSide.OUTGOING, rate, 1)
        val x = tone(440.0, 1.0)
        val silent = x.copyOf(); d.process(silent, silent.size, 0)
        assertEquals(0.0, rms(silent, 0.1, 0.9), 0.0)
        d.hold = true
        val back = x.copyOf(); d.process(back, back.size, 1_000_000)
        assertEquals(db(rms(x, 0.6, 0.9)), db(rms(back, 0.6, 0.9)), 0.01)
    }

    @Test fun stereoChannelsAreKeptApart() {
        val p = plan(lane(LaneSide.OUTGOING, Target.SHELF_BOTTOM, SeguePlan.Knot(0, -30_000, step), SeguePlan.Knot(m, -30_000, step)))
        val x = FloatArray(rate * 2 * 2) { i -> if (i % 2 == 0) (0.5 * sin(2 * PI * 60.0 * (i / 2) / rate)).toFloat() else (0.5 * sin(2 * PI * 5_000.0 * (i / 2) / rate)).toFloat() }
        val out = run(p, LaneSide.OUTGOING, x, channels = 2)
        val left = FloatArray(out.size / 2) { out[2 * it] }; val right = FloatArray(out.size / 2) { out[2 * it + 1] }
        assertTrue(rms(left, 1.0, 1.9) < 0.1)
        assertTrue(rms(right, 1.0, 1.9) > 0.3)
    }

    @Test fun theEngineRefusesWhatItCannotExecute() {
        val fine = plan(lane(LaneSide.OUTGOING, Target.LEVEL, SeguePlan.Knot(0, 0, step), SeguePlan.Knot(m, null, ramp)))
        assertTrue(SegueDeck.playable(fine))
        assertFalse(SegueDeck.playable(fine.copy(span = fine.span.copy(clock = Clock.SOURCE))))
        assertFalse(SegueDeck.playable(fine.copy(gestures = listOf(SeguePlan.Gesture(GestureKind.REPEAT, 0, 2)))))
        assertFalse(SegueDeck.playable(fine.copy(span = fine.span.copy(lengthUs = 0))))
    }

    @Test fun theRiseCutsLowNotesAndPassesHighOnes() {
        val closed = plan(lane(LaneSide.OUTGOING, Target.RISE_HZ, SeguePlan.Knot(0, 1_200_000, step), SeguePlan.Knot(m, 1_200_000, step)))
        val low = run(closed, LaneSide.OUTGOING, tone(60.0, 1.0))
        val high = run(closed, LaneSide.OUTGOING, tone(6_000.0, 1.0))
        assertTrue(db(rms(low, 0.5, 1.0) / rms(tone(60.0, 1.0), 0.5, 1.0)) < -30)
        assertTrue(abs(db(rms(high, 0.5, 1.0) / rms(tone(6_000.0, 1.0), 0.5, 1.0))) < 1)
    }

    @Test fun anOpenRiseLeavesTheSongAlone() {
        val open = plan(lane(LaneSide.OUTGOING, Target.RISE_HZ, SeguePlan.Knot(0, null, step), SeguePlan.Knot(m, null, step)))
        val x = tone(60.0, 0.5)
        val out = run(open, LaneSide.OUTGOING, x)
        for (i in x.indices) assertEquals(x[i], out[i], 1e-6f)
    }

    @Test fun theEchoRepeatsAtThePlansDelay() {
        // Silence with one click: the fader is shut, so all that comes out is the send.
        val shut = lane(LaneSide.OUTGOING, Target.LEVEL, SeguePlan.Knot(0, null, step), SeguePlan.Knot(m, null, step))
        val send = lane(LaneSide.OUTGOING, Target.SEND_ECHO, SeguePlan.Knot(0, 1000, step), SeguePlan.Knot(m, 1000, step))
        val at = rate / 10
        val delays = listOf(300_000L, 450_000L)
        for (us in delays) {
            val x = FloatArray(2 * rate); x[at] = 1f
            val out = run(plan(shut, send).copy(echoUs = us), LaneSide.OUTGOING, x)
            val first = out.indices.first { abs(out[it]) > 0.2f }
            assertEquals("$us", at + (us * rate / 1_000_000).toInt(), first.toLong().toInt())
            val second = out.indices.first { it > first + 100 && abs(out[it]) > 0.05f }
            assertEquals("$us", (us * rate / 1_000_000).toDouble(), (second - first).toDouble(), 2.0)
        }
    }

    @Test fun anEchoOutsideItsBoundsIsRefused() {
        val p = plan(lane(LaneSide.OUTGOING, Target.LEVEL, SeguePlan.Knot(0, 0, step), SeguePlan.Knot(m, 0, step)))
        assertTrue(SegueViolation.ECHO_INVALID in SegueValidator.structural(p.copy(echoUs = 0)))
        assertTrue(SegueViolation.ECHO_INVALID in SegueValidator.structural(p.copy(echoUs = SegueFormat.ECHO_MAX_US + 1)))
        assertEquals(emptyList<SegueViolation>(), SegueValidator.structural(p.copy(echoUs = 400_000)))
    }

    @Test fun onlySendsAskForATail() {
        val fader = lane(LaneSide.OUTGOING, Target.LEVEL, SeguePlan.Knot(0, 0, step), SeguePlan.Knot(m, null, ramp))
        assertEquals(0L, SegueDeck.tailUs(plan(fader)))
        val t = SegueTuning()
        assertEquals(t.sendTailUs, SegueDeck.tailUs(plan(fader, lane(LaneSide.OUTGOING, Target.SEND_ROOM, SeguePlan.Knot(0, 0, step), SeguePlan.Knot(m, 0, step)))))
    }
}
