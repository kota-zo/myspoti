package com.music.spotui.di

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Seen in a log: a play given up for a newer one while the engine started was taken for the
 * engine's failure, and the engine was left aside for 15 minutes.
 */
class NativeAsideTest {
    @Test fun aCancelledStartIsNotTheEnginesFailure() {
        assertFalse(engineFailedToStart(CancellationException("StandaloneCoroutine was cancelled")))
    }

    @Test fun anErrorWhileStartingIs() {
        assertTrue(engineFailedToStart(java.io.IOException("connect-state 500")))
    }
}
