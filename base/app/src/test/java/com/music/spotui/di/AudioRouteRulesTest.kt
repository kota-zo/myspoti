package com.music.spotui.di

import com.music.spotui.di.AudioRouteRules.Output
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When `ACTION_AUDIO_BECOMING_NOISY` pauses the music.
 *
 * A phone pauses: headphones pulled out must not play out of its speaker. A TV's HDMI output
 * goes away and comes back on its own (a frame rate or HDR switch, a receiver re-negotiating),
 * and pausing there was the music stopping by itself — reported as "it plays a little and then
 * stops".
 */
class AudioRouteRulesTest {
    @Test fun `a phone pauses whatever went`() {
        assertTrue(AudioRouteRules.pauseWhenNoisy(setOf(Output.HEADSET), linkedNow = false, tv = false))
        assertTrue(AudioRouteRules.pauseWhenNoisy(emptySet(), linkedNow = false, tv = false))
        // Even with an HDMI output still listed: a phone with a phone on it cannot reproduce.
        assertTrue(AudioRouteRules.pauseWhenNoisy(setOf(Output.OTHER), linkedNow = true, tv = false))
    }

    @Test fun `a TV plays on when its HDMI output went`() {
        assertFalse(AudioRouteRules.pauseWhenNoisy(setOf(Output.LINK), linkedNow = false, tv = true))
    }

    @Test fun `a TV plays on while its sound is still on a link`() {
        // Nothing said about what went (the callback may answer a moment later), and an HDMI
        // output is still there: the sound did not fall back to anything.
        assertFalse(AudioRouteRules.pauseWhenNoisy(emptySet(), linkedNow = true, tv = true))
        assertFalse(AudioRouteRules.pauseWhenNoisy(setOf(Output.OTHER), linkedNow = true, tv = true))
    }

    @Test fun `a TV still pauses for headphones`() {
        // A headset on a TV (a pair in the jack, a Bluetooth one): its leaving plays out of the
        // TV's speakers, which is what the broadcast is for.
        assertTrue(AudioRouteRules.pauseWhenNoisy(setOf(Output.HEADSET), linkedNow = true, tv = true))
        assertTrue(AudioRouteRules.pauseWhenNoisy(setOf(Output.HEADSET, Output.LINK), linkedNow = false, tv = true))
    }

    @Test fun `a TV whose output fell back to its own speaker pauses`() {
        // Nothing on a link and nothing known about what went: a headset's jack is the likely
        // answer, and the old behaviour is the safe one.
        assertTrue(AudioRouteRules.pauseWhenNoisy(emptySet(), linkedNow = false, tv = true))
    }
}
