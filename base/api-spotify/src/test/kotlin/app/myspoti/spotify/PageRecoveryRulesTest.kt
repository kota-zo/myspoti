package app.myspoti.spotify

import app.myspoti.spotify.PageRecoveryRules.PageBack
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PageRecoveryRulesTest {
    @Test fun aMirroringPagePlaysNothingAfterOurReload() {
        assertFalse(PageRecoveryRules.playsAfterOwnReload(mirrored = true))
        assertTrue(PageRecoveryRules.playsAfterOwnReload(mirrored = false))
    }

    @Test fun theMirrorsPlayIsNoPickBeforeAnAd() {
        assertFalse(PageRecoveryRules.keepsPickBeforeAd(mirrored = true))
        assertTrue(PageRecoveryRules.keepsPickBeforeAd(mirrored = false))
    }

    @Test fun aPageBackWhileMirroringResumesNothing() {
        // Log seen: a pick held, the song before the ad lost, the reload ours but over by then.
        assertEquals(PageBack.MIRROR, PageRecoveryRules.pageBack(ownReload = false, mirrored = true, pickHeld = true, lost = true, intent = true))
        assertEquals(PageBack.MIRROR, PageRecoveryRules.pageBack(ownReload = true, mirrored = true, pickHeld = false, lost = true, intent = false))
    }

    @Test fun aPageBackOnSpotifyAloneIsUnchanged() {
        assertEquals(PageBack.NOTHING, PageRecoveryRules.pageBack(ownReload = true, mirrored = false, pickHeld = true, lost = true, intent = true))
        assertEquals(PageBack.PICK, PageRecoveryRules.pageBack(ownReload = false, mirrored = false, pickHeld = true, lost = true, intent = true))
        assertEquals(PageBack.RESUME, PageRecoveryRules.pageBack(ownReload = false, mirrored = false, pickHeld = false, lost = true, intent = true))
        assertEquals(PageBack.INTENT, PageRecoveryRules.pageBack(ownReload = false, mirrored = false, pickHeld = false, lost = false, intent = true))
        assertEquals(PageBack.NOTHING, PageRecoveryRules.pageBack(ownReload = false, mirrored = false, pickHeld = false, lost = false, intent = false))
    }

    @Test fun theUsersQueueIsAddedAgainAfterOurReload() {
        val q = listOf("t:a", "t:b", "t:c")
        // Played on from the queue's first song: the others come again, in their order.
        assertEquals(listOf("t:b", "t:c"), PageRecoveryRules.requeued(q, playing = "t:a", has = emptySet()))
        // Another song played on (not from the queue): all of it.
        assertEquals(q, PageRecoveryRules.requeued(q, playing = "t:x", has = emptySet()))
        // What the new page lists already is not added twice.
        assertEquals(listOf("t:c"), PageRecoveryRules.requeued(q, playing = "t:a", has = setOf("t:b")))
        assertEquals(emptyList<String>(), PageRecoveryRules.requeued(q, playing = "t:c", has = emptySet()))
    }

    @Test fun aRestoredOccurrenceDoesNotRemoveEveryDuplicateFromTheQueue() {
        val q = listOf("t:a", "t:b", "t:b", "t:c")
        assertEquals(listOf("t:b", "t:c"), PageRecoveryRules.requeued(q, playing = "t:a", has = listOf("t:b")))
        assertEquals(listOf("t:c"), PageRecoveryRules.requeued(q, playing = "t:a", has = listOf("t:b", "t:b")))
        assertEquals(listOf("t:b", "t:b", "t:c"), PageRecoveryRules.requeued(q, playing = "t:a", has = emptyList()))
    }
}
