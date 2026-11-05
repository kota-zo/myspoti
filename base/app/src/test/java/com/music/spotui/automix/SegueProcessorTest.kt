/* SPDX-License-Identifier: GPL-3.0-only */
package com.music.spotui.automix

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import app.myspoti.automix.segue.Clock
import app.myspoti.automix.segue.KnotForm
import app.myspoti.automix.segue.LaneSide
import app.myspoti.automix.segue.PulseState
import app.myspoti.automix.segue.SegueFormat
import app.myspoti.automix.segue.SeguePlan
import app.myspoti.automix.segue.SegueUnit
import app.myspoti.automix.segue.Target
import app.myspoti.automix.segue.UnitKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class SegueProcessorTest {
    private val rate = 44_100

    private fun plan(anchorUs: Long, lengthUs: Long = 100_000) = SeguePlan(SeguePlan.Origin("t", "t"),
        SeguePlan.Side("a", null, anchorUs, SegueFormat.RATE_NOMINAL), SeguePlan.Side("b", null, anchorUs, SegueFormat.RATE_NOMINAL),
        SeguePlan.Span(lengthUs, null, UnitKind.BAR, Clock.ELAPSED), SeguePlan.Pulse(PulseState.FREE, null, null),
        listOf(SeguePlan.Lane(LaneSide.OUTGOING, Target.LEVEL, SegueUnit.DB, listOf(SeguePlan.Knot(0, 0, KnotForm.Step), SeguePlan.Knot(SegueFormat.T_MAX, null, KnotForm.Ramp)))))

    private fun processor(encoding: Int = C.ENCODING_PCM_FLOAT, channels: Int = 1) = SegueProcessor().apply {
        configure(AudioProcessor.AudioFormat(rate, channels, encoding)); flush()
    }

    private fun floats(n: Int, v: Float): ByteBuffer {
        val b = ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder())
        repeat(n) { b.putFloat(v) }; b.flip(); return b
    }

    private fun run(p: SegueProcessor, input: ByteBuffer): ByteBuffer { p.queueInput(input); return p.output.order(ByteOrder.nativeOrder()) }

    @Test fun unarmedItCopiesItsInputExactly() {
        val p = processor()
        val out = run(p, floats(1024, 0.25f))
        assertEquals(4096, out.remaining())
        repeat(1024) { assertEquals(0.25f, out.getFloat(), 0f) }
    }

    @Test fun itFollowsTheSinksPlaceAndCountsFromIt() {
        val p = processor()
        assertFalse(p.marked)
        p.mark(1_000_000)
        assertTrue(p.marked)
        run(p, floats(rate / 10, 0f))
        assertEquals(1_100_000, p.positionUs)
        p.mark(5_000_000)
        assertEquals(5_000_000, p.positionUs)
    }

    @Test fun armedTheSamplesFollowTheirPlaceInTheSong() {
        val p = processor()
        p.arm(plan(anchorUs = 1_000_000), LaneSide.OUTGOING)
        p.mark(500_000)
        val before = run(p, floats(1024, 0.5f))
        repeat(1024) { assertEquals(0.5f, before.getFloat(), 1e-6f) }
        p.mark(2_000_000)
        val after = run(p, floats(4096, 0.5f))
        // The new deck starts past its window: silence, whatever the player's buffers hold before it.
        repeat(1024) { after.getFloat() }
        repeat(3072) { assertEquals(0f, after.getFloat(), 0f) }
    }

    @Test fun sixteenBitAudioPassesThroughTheSameLanes() {
        val p = processor(C.ENCODING_PCM_16BIT)
        p.arm(plan(anchorUs = 0), LaneSide.OUTGOING)
        p.mark(1_000_000)
        val b = ByteBuffer.allocateDirect(8192).order(ByteOrder.nativeOrder())
        repeat(4096) { b.putShort(16384) }; b.flip()
        val out = run(p, b)
        repeat(2048) { out.getShort() }
        repeat(2048) { assertEquals(0, out.getShort().toInt()) }
    }

    @Test fun releasedItReturnsToThePlainSongAndThenDisarms() {
        val p = processor()
        p.arm(plan(anchorUs = 0), LaneSide.OUTGOING)
        p.mark(1_000_000)
        run(p, floats(4096, 0.5f))
        p.release()
        assertTrue(p.isArmed)
        val second = rate // the ramp lasts 400 ms
        val out = run(p, floats(second, 0.5f))
        repeat(second - 1) { out.getFloat() }
        assertEquals(0.5f, out.getFloat(), 1e-6f)
        assertFalse(p.isArmed)
    }

    @Test fun otherFormatsAreLeftAlone() {
        assertEquals(AudioProcessor.AudioFormat.NOT_SET, SegueProcessor().configure(AudioProcessor.AudioFormat(rate, 6, C.ENCODING_PCM_FLOAT)))
        assertEquals(AudioProcessor.AudioFormat.NOT_SET, SegueProcessor().configure(AudioProcessor.AudioFormat(rate, 2, C.ENCODING_AC3)))
    }

    @Test fun theChainAndThePlayerMustAgreeOnThePlace() {
        assertTrue(MixPreparation.placed(61_000_000, 60_000))
        assertTrue(MixPreparation.placed(60_000_000, 60_000))
        assertFalse(MixPreparation.placed(61_000_000, 10_000))
    }

    @Test fun aPlanGoesOnlyWhenBothSongsArePlacedBufferedAndAligned() {
        assertTrue(MixPreparation.segueReady(true, 800, 100_000, true, true, null))
        assertTrue(MixPreparation.segueReady(true, 800, 100_000, true, true, 50.0))
        assertFalse(MixPreparation.segueReady(false, 800, 100_000, true, true, null))
        assertFalse(MixPreparation.segueReady(true, 100, 100_000, true, true, null))
        assertFalse(MixPreparation.segueReady(true, 800, 100_000, false, true, null))
        assertFalse(MixPreparation.segueReady(true, 800, 100_000, true, false, null))
        assertFalse(MixPreparation.segueReady(true, 800, 100_000, true, true, 120.0))
    }
}
