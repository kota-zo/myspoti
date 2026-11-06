/* SPDX-License-Identifier: GPL-3.0-only */
package com.music.spotui.automix

import org.junit.Assert.assertFalse
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
}
