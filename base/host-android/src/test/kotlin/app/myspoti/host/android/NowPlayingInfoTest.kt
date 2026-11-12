package app.myspoti.host.android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NowPlayingInfoTest {
    private fun info(id: String = "spotify:track:x", playing: Boolean = false, buffering: Boolean = false) =
        NowPlayingInfo(id, "t", "a", "al", null, 200_000L, 0L, playing, buffering)

    @Test fun playingOrLoadingMakesSound() {
        assertTrue(info(playing = true).makesSound)
        assertTrue(info(buffering = true).makesSound)
    }

    /** An ad break is shown paused, but the service keeps the foreground and the locks through it. */
    @Test fun adBreakMakesSoundThoughShownPaused() {
        assertTrue(info(id = NowPlayingInfo.AD_ID).makesSound)
    }

    @Test fun pausedSongMakesNoSound() {
        assertFalse(info().makesSound)
    }

    /** A moved position alone is published again only when it left the system's extrapolation. */
    @Test fun onlyAPositionJumpIsPublishedAgain() {
        val before = info(playing = true).copy(positionMs = 10_000)
        assertFalse(before.copy(positionMs = 40_000).positionJumpedFrom(before, elapsedMs = 30_000))
        assertFalse(before.copy(positionMs = 41_500).positionJumpedFrom(before, elapsedMs = 30_000))
        assertTrue(before.copy(positionMs = 120_000).positionJumpedFrom(before, elapsedMs = 30_000))
        // Paused, the position stands: any move over the tolerance is a seek.
        val paused = info().copy(positionMs = 10_000)
        assertFalse(paused.copy(positionMs = 10_500).positionJumpedFrom(paused, elapsedMs = 30_000))
        assertTrue(paused.copy(positionMs = 40_000).positionJumpedFrom(paused, elapsedMs = 30_000))
    }

    /** A buffering flip or a shuffle change is the session's alone: the notification is not posted again. */
    @Test fun theNotificationAndTheMetadataChangeOnlyWithWhatTheyShow() {
        val a = info(playing = true)
        assertTrue(a.sameNotification(a.copy(buffering = true, shuffle = true, liked = true, positionMs = 99_000)))
        assertFalse(a.sameNotification(a.copy(playing = false)))
        assertFalse(a.sameNotification(a.copy(title = "other")))
        assertTrue(a.sameMetadata(a.copy(playing = false, liked = true)))
        assertFalse(a.sameMetadata(a.copy(coverUrl = "https://i.scdn.co/image/x")))
        assertFalse(a.sameAs(a.copy(streamed = false)))
    }
}
