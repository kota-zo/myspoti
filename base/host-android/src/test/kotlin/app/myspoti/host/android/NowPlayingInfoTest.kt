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
}
