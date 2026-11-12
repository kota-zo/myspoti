package com.music.spotui.di

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrossfadeRampTest {
    @Test fun fadeIsNeverOverHalfTheSong() {
        assertEquals(6000L, CrossfadeRamp.fadeMs(6, 200_000))
        assertEquals(5000L, CrossfadeRamp.fadeMs(12, 10_000))
        assertEquals(0L, CrossfadeRamp.fadeMs(0, 200_000))
    }

    @Test fun startsOnlyInTheLastSeconds() {
        assertFalse(CrossfadeRamp.shouldStart(6, 200_000, 100_000))
        assertTrue(CrossfadeRamp.shouldStart(6, 200_000, 194_000))
        assertFalse(CrossfadeRamp.shouldStart(0, 200_000, 199_000))
        assertFalse(CrossfadeRamp.shouldStart(6, 0, 1000))
    }

    @Test fun equalPowerKeepsLoudnessLevel() {
        for (i in 0..10) {
            val t = i / 10f
            val a = CrossfadeRamp.incomingGain(t); val b = CrossfadeRamp.outgoingGain(t)
            assertEquals(1f, a * a + b * b, 1e-4f)
        }
        assertEquals(0f, CrossfadeRamp.incomingGain(0f), 1e-6f)
        assertEquals(1f, CrossfadeRamp.incomingGain(1f), 1e-6f)
        assertEquals(1f, CrossfadeRamp.progress(9999, 5000), 0f)
    }

    @Test fun noCrossfadeMeansNoTimerAndOneLooksSoonOnlyNearTheFade() {
        assertEquals(null, CrossfadeRamp.nextLookMs(0, 200_000, 10_000))
        // 6 s fade on a 200 s song, at 10 s: looked at again in 5 s at most.
        assertEquals(CrossfadeRamp.MAX_LOOK_MS, CrossfadeRamp.nextLookMs(6, 200_000, 10_000))
        // 2 s before the fade: in 1.8 s (just ahead of it), twice as soon at double speed.
        assertEquals(1_800L, CrossfadeRamp.nextLookMs(6, 200_000, 192_000))
        assertEquals(900L, CrossfadeRamp.nextLookMs(6, 200_000, 192_000, speed = 2f))
        assertEquals(CrossfadeRamp.MIN_LOOK_MS, CrossfadeRamp.nextLookMs(6, 200_000, 195_000))
    }
}
