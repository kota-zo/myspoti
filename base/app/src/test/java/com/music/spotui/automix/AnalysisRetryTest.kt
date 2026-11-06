/* SPDX-License-Identifier: GPL-3.0-only */
package com.music.spotui.automix

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalysisRetryTest {
    @Test fun temporaryNetworkFailureCanRecoverWithoutRestart() {
        val retry = AnalysisRetry()
        retry.failed("song", 1_000)
        assertFalse(retry.allowed("song", 5_999))
        assertTrue(retry.allowed("song", 6_000))
        retry.failed("song", 6_000)
        assertFalse(retry.allowed("song", 15_999))
        assertTrue(retry.allowed("song", 16_000))
        retry.succeeded("song")
        assertTrue(retry.allowed("song", 16_001))
    }

    @Test fun skippingAnAnalysisDoesNotBlacklistTheSong() {
        val retry = AnalysisRetry()
        retry.failed("song", 1_000, cancelled = true)
        assertTrue(retry.allowed("song", 1_001))
    }

    @Test fun oneUnavailableRecordingDoesNotBlockAnotherSource() {
        val retry = AnalysisRetry()
        retry.failed("song:sourceA", 1_000)
        assertTrue(retry.allowed("song:sourceB", 1_001))
        retry.clear()
        assertTrue(retry.allowed("song:sourceA", 1_001))
    }
}
