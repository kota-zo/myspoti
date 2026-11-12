/* SPDX-License-Identifier: GPL-3.0-only */
package com.music.spotui.automix

import app.myspoti.automix.DurationBounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MixPreparationTest {
    @Test fun lateAnalysisUpgradesFallbackUntilTheMutedRunUpStarted() {
        assertTrue(MixPreparation.mayReplacePlan(false, true, true))
        assertFalse(MixPreparation.mayReplacePlan(true, true, true))
        assertFalse(MixPreparation.mayReplacePlan(false, true, false))
    }

    @Test fun bufferingOrUnalignedDeckCannotReplaceAudibleSong() {
        assertFalse(MixPreparation.readyToTakeOver(false, 5_000, 100_000, 0.0, true))
        assertFalse(MixPreparation.readyToTakeOver(true, 100, 100_000, 0.0, true))
        assertFalse(MixPreparation.readyToTakeOver(true, 5_000, 100_000, 180.0, true))
        assertTrue(MixPreparation.readyToTakeOver(true, 5_000, 100_000, 12.0, true))
        assertTrue(MixPreparation.readyToTakeOver(true, 5_000, 100_000, null, false))
    }

    @Test fun incomingStallPreservesTheSongThatStillPlays() {
        assertTrue(MixPreparation.preserveOutgoing(false, true))
        assertFalse(MixPreparation.preserveOutgoing(true, true))
        assertFalse(MixPreparation.preserveOutgoing(false, false))
    }

    @Test fun recoveredFadeUsesOnlyTheOriginalWindowThatRemains() {
        assertEquals(5.0, MixPreparation.recoveryFadeSeconds(10.0, 11.0, 9.0, DurationBounds(6.0, 12.0))!!, 0.0)
        assertEquals(1.0, MixPreparation.recoveryFadeSeconds(10.0, 11.0, 4.0, DurationBounds(0.0, 2.0))!!, 0.0)
        assertNull(MixPreparation.recoveryFadeSeconds(10.0, 12.1, 4.0, DurationBounds(0.0, 2.0)))
    }

    @Test fun recoveredFadeTreatsPlaybackRateAdjustedBoundsAsSourceSecondsAndKeepsTheOriginalCue() {
        val sourceBounds = DurationBounds(3.0, 8.0).forPlaybackRate(2.0)
        assertEquals(5.0, MixPreparation.recoveryFadeSeconds(10.0, 11.0, 8.0, sourceBounds)!!, 0.0)
        // A later recovery still counts from the first cue: 4 s already elapsed, leaving 2 s of the 6 s minimum.
        assertEquals(2.0, MixPreparation.recoveryFadeSeconds(10.0, 14.0, 8.0, sourceBounds, preferredSourceSec = 1.0)!!, 0.0)
    }
}
