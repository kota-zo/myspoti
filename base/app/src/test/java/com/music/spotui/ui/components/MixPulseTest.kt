/* SPDX-License-Identifier: GPL-3.0-only */
package com.music.spotui.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The player's motion after an AutoMix mix plays through from its start, on frames, without a jump. */
class MixPulseTest {
    private val frame = 16_666_667L

    /** A mix drawn in for [seconds], then over at the returned time. */
    private fun mixed(p: MixPulse, seconds: Int = 4): Long {
        var t = 1_000_000_000L
        repeat(seconds * 60) { p.step(t, 0.8f, 1f); t += frame }
        return t
    }

    @Test fun theReleaseAsksForFramesFromItsStart() {
        val p = MixPulse()
        val end = mixed(p)
        // The loop's check sees the mix over: the release starts and still wants frames.
        p.step(end, 0f, null)
        assertTrue(p.moving)
        assertEquals(0f, p.wave()!!, 1e-3f)
        // Its first frames go on from there, not from halfway.
        p.step(end + frame, 0f, null)
        assertTrue(p.wave()!! < 0.05f)
    }

    @Test fun aFrameTimeJustBeforeTheReleaseNeverReadsBeforeIt() {
        val p = MixPulse()
        val end = mixed(p)
        p.step(end, 0f, null)
        val from = p.squeeze()
        // The next frame's time is a little earlier than the check's clock.
        p.step(end - 5_000_000L, 0f, null)
        assertTrue("the spring went past where it let go: ${p.squeeze()} > $from", p.squeeze() <= from + 1e-4f)
        assertNotNull(p.wave())
    }

    @Test fun theBeatFadesOutAfterTheMix() {
        val p = MixPulse()
        val end = mixed(p)
        p.step(end, 0f, null)
        val first = p.level()
        assertTrue("the beat fell to nothing at once", first > 0.5f)
        var t = end
        repeat(60) { t += frame; p.step(t, 0f, null) }
        assertEquals(0f, p.level(), 1e-3f)
    }

    @Test fun everythingSettlesAndThenNoFrames() {
        val p = MixPulse()
        var t = mixed(p)
        repeat(120) { p.step(t, 0f, null); t += frame }
        assertFalse(p.moving)
        assertEquals(0f, p.squeeze(), 1e-4f)
    }

    @Test fun aMixHeldByAPauseAsksForNoFrames() {
        val p = MixPulse()
        mixed(p)
        assertFalse(p.moving)
    }
}
