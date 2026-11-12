package com.music.spotui.di

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The audio offload only when nothing of ours touches the samples: AutoMix, the equalizer and the crossfade keep their sound. */
class LocalOffloadTest {
    private fun allowed(eqFlat: Boolean = true, spatial: Float? = null, autoMix: Boolean = false, crossfade: Int = 0, speed: Float = 1f, skip: Boolean = false) =
        LocalOffload.allowed(eqFlat, spatial, autoMix, crossfade, speed, skip)

    @Test fun plainPlaybackIsOffloaded() = assertTrue(allowed())

    @Test fun anyStageOfOursKeepsTheAudioHere() {
        assertFalse(allowed(eqFlat = false))
        assertFalse(allowed(spatial = 1.6f))
        assertFalse(allowed(autoMix = true))
        assertFalse(allowed(crossfade = 6))
        assertFalse(allowed(speed = 1.25f))
        assertFalse(allowed(skip = true))
        assertTrue(allowed(spatial = 1f))
    }
}
