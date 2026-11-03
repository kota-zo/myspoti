package com.music.spotui.connect

import com.music.spotui.connect.RemoteAdMute.Rules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteAdMuteTest {
    private fun mutes(
        ad: Boolean = true, elsewhere: Boolean = true, activeId: String? = "denon", chosenId: String? = "denon",
        level: Double? = 0.4, jamMember: Boolean = false, setAside: Set<String> = emptySet(),
    ) = Rules.mutes(ad, elsewhere, activeId, chosenId, level, jamMember, setAside)

    @Test fun anAdOnThePickedDeviceIsMuted() = assertTrue(mutes())

    @Test fun noAdOrAnAdOnThisPhoneIsLeftAlone() {
        assertFalse(mutes(ad = false))
        assertFalse(mutes(elsewhere = false))
    }

    @Test fun onlyTheDevicePickedInMyspoti() {
        assertFalse(mutes(chosenId = null))
        assertFalse(mutes(chosenId = "desktop"))
        assertFalse(mutes(activeId = null))
    }

    @Test fun neverAJamOrADeviceWithoutVolume() {
        assertFalse(mutes(jamMember = true))
        assertFalse(mutes(activeId = "social-connect-1", chosenId = "social-connect-1"))
        assertFalse(mutes(level = null))
        assertFalse(mutes(level = 0.0))
    }

    @Test fun aDeviceWhoseMutedAdNeverEndedIsSetAside() = assertFalse(mutes(setAside = setOf("denon")))

    @Test fun theLevelIsTheReportedOneElseTheBars() {
        assertEquals(0.4, Rules.levelOf(0.4, 80)!!, 1e-9)
        assertEquals(0.8, Rules.levelOf(null, 80)!!, 1e-9)
        assertNull(Rules.levelOf(null, 0))
        // A fixed volume (negative) or a device already at 0: nothing to mute or restore.
        assertNull(Rules.levelOf(-1.0, 80))
        assertNull(Rules.levelOf(0.0, 80))
    }

    @Test fun anOwedLevelGoesBackWhenItsDevicePlaysWithoutAnAd() {
        val now = 1_000_000L
        assertTrue(Rules.restoresOwed("denon", now - 5_000, "denon", ad = false, now = now))
        assertFalse(Rules.restoresOwed("denon", now - 5_000, "phone", ad = false, now = now))
        assertFalse(Rules.restoresOwed("denon", now - 5_000, "denon", ad = true, now = now))
        assertFalse(Rules.restoresOwed(null, now, "denon", ad = false, now = now))
        assertFalse(Rules.restoresOwed("denon", now - 25 * 3600_000L, "denon", ad = false, now = now + 0))
    }
}
