package app.myspoti.spotify

import app.myspoti.spotify.AdRecoveryRules.PageBack
import app.myspoti.spotify.AdRecoveryRules.Stuck
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AdRecoveryRulesTest {
    @Test fun spotifyAloneKeepsItsLevers() {
        assertEquals(Stuck.RELOAD, AdRecoveryRules.stuck(1, playing = false, mirrored = false))
        assertEquals(Stuck.NEXT, AdRecoveryRules.stuck(2, playing = false, mirrored = false))
        assertEquals(Stuck.NEXT, AdRecoveryRules.stuck(1, playing = true, mirrored = false))
        assertEquals(Stuck.RELOAD, AdRecoveryRules.stuck(2 + SpotifyPlayer.AD_SKIPS_AFTER_RELOAD, playing = false, mirrored = false))
    }

    @Test fun aMirroringPageIsOnlyReloaded() {
        for (attempt in 1..5) for (playing in listOf(false, true))
            assertEquals(Stuck.RELOAD_ONLY, AdRecoveryRules.stuck(attempt, playing, mirrored = true))
        assertFalse(AdRecoveryRules.playsAfterOwnReload(mirrored = true))
        assertTrue(AdRecoveryRules.playsAfterOwnReload(mirrored = false))
    }

    @Test fun theMirrorsPlayIsNoPickBeforeAnAd() {
        assertFalse(AdRecoveryRules.keepsPickBeforeAd(mirrored = true))
        assertTrue(AdRecoveryRules.keepsPickBeforeAd(mirrored = false))
    }

    @Test fun aPageBackWhileMirroringResumesNothing() {
        // Log seen: a pick held, the song before the ad lost, the reload ours but over by then.
        assertEquals(PageBack.MIRROR, AdRecoveryRules.pageBack(ownReload = false, mirrored = true, pickHeld = true, lost = true, intent = true))
        assertEquals(PageBack.MIRROR, AdRecoveryRules.pageBack(ownReload = true, mirrored = true, pickHeld = false, lost = true, intent = false))
    }

    @Test fun aPageBackOnSpotifyAloneIsUnchanged() {
        assertEquals(PageBack.NOTHING, AdRecoveryRules.pageBack(ownReload = true, mirrored = false, pickHeld = true, lost = true, intent = true))
        assertEquals(PageBack.PICK, AdRecoveryRules.pageBack(ownReload = false, mirrored = false, pickHeld = true, lost = true, intent = true))
        assertEquals(PageBack.RESUME, AdRecoveryRules.pageBack(ownReload = false, mirrored = false, pickHeld = false, lost = true, intent = true))
        assertEquals(PageBack.INTENT, AdRecoveryRules.pageBack(ownReload = false, mirrored = false, pickHeld = false, lost = false, intent = true))
        assertEquals(PageBack.NOTHING, AdRecoveryRules.pageBack(ownReload = false, mirrored = false, pickHeld = false, lost = false, intent = false))
    }
}
