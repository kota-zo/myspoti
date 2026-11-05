/* SPDX-License-Identifier: GPL-3.0-only */
package com.music.spotui.di

import com.music.spotui.di.CallPause.Action
import org.junit.Assert.assertEquals
import org.junit.Test

class CallPauseTest {
    @Test fun musicPausesForTheCallAndComesBackAfter() {
        val c = CallPause()
        assertEquals(Action.PAUSE, c.modeChanged(busy = true, playing = true, nowMs = 0))
        // Ringing, then in the call: one pause.
        assertEquals(Action.NONE, c.modeChanged(busy = true, playing = false, nowMs = 5_000))
        assertEquals(Action.RESUME, c.modeChanged(busy = false, playing = false, nowMs = 60_000))
        assertEquals(Action.NONE, c.modeChanged(busy = false, playing = true, nowMs = 61_000))
    }

    @Test fun whatWasPausedStaysPaused() {
        val c = CallPause()
        assertEquals(Action.NONE, c.modeChanged(busy = true, playing = false, nowMs = 0))
        assertEquals(Action.NONE, c.modeChanged(busy = false, playing = false, nowMs = 60_000))
    }

    @Test fun aTouchDuringTheCallIsTheLastWord() {
        val c = CallPause()
        c.modeChanged(busy = true, playing = true, nowMs = 0)
        c.userActed()
        assertEquals(Action.NONE, c.modeChanged(busy = false, playing = false, nowMs = 60_000))
    }

    @Test fun aVeryLongCallDoesNotBringTheMusicBack() {
        val c = CallPause(longestMs = 1_000)
        c.modeChanged(busy = true, playing = true, nowMs = 0)
        assertEquals(Action.NONE, c.modeChanged(busy = false, playing = false, nowMs = 2_000))
    }
}
