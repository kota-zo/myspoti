package app.myspoti.host.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The CPU lock while this phone plays, the Wi-Fi lock only while a network stream loads, none for a remote. */
class PlaybackLocksTest {
    private fun info(id: String = "spotify:track:a", playing: Boolean = true, buffering: Boolean = false, position: Long = 0L, streamed: Boolean = true) =
        NowPlayingInfo(id, "t", "a", "al", null, 200_000L, position, playing, buffering, streamed = streamed)

    private val hold = PlaybackLocks.WIFI_HOLD_MS

    @Test fun aRemoteHoldsNoLock() {
        val l = PlaybackLocks()
        assertEquals(PlaybackLocks.Held(cpu = false, wifi = false), l.decide(info(), sounding = true, remote = true, now = 0))
    }

    @Test fun nothingSoundingHoldsNoLock() {
        val l = PlaybackLocks()
        assertEquals(PlaybackLocks.Held(cpu = false, wifi = false), l.decide(info(playing = false), sounding = false, remote = false, now = 0))
    }

    @Test fun aFileOfThePhoneHoldsTheCpuOnly() {
        val l = PlaybackLocks()
        assertEquals(PlaybackLocks.Held(cpu = true, wifi = false), l.decide(info(streamed = false), sounding = true, remote = false, now = 0))
    }

    @Test fun aStreamHoldsTheWifiWhileItLoadsThenLetsItGo() {
        val l = PlaybackLocks()
        // A song starts: the Wi-Fi is held, and the service is told when to look again.
        val start = l.decide(info(), sounding = true, remote = false, now = 0)
        assertTrue(start.cpu && start.wifi)
        assertEquals(hold, start.recheckInMs)
        // Its window over, the same song playing on: the Wi-Fi goes, the CPU stays.
        assertEquals(PlaybackLocks.Held(cpu = true, wifi = false), l.decide(info(position = hold), sounding = true, remote = false, now = hold))
    }

    @Test fun bufferingHoldsTheWifiUntilItEndsAndForTheWindowAfter() {
        val l = PlaybackLocks()
        l.decide(info(), sounding = true, remote = false, now = 0)
        assertEquals(PlaybackLocks.Held(cpu = true, wifi = true), l.decide(info(buffering = true, position = 60_000), sounding = true, remote = false, now = 60_000))
        val after = l.decide(info(position = 70_000), sounding = true, remote = false, now = 70_000)
        assertTrue(after.wifi)
        assertEquals(hold, after.recheckInMs)
        assertFalse(l.decide(info(position = 70_000 + hold), sounding = true, remote = false, now = 70_000 + hold).wifi)
    }

    @Test fun aSeekOrANewSongLoadsAgain() {
        val l = PlaybackLocks()
        l.decide(info(), sounding = true, remote = false, now = 0)
        assertFalse(l.decide(info(position = 30_000), sounding = true, remote = false, now = 30_000).wifi)
        // A seek: the position is far from where the clock had it.
        assertTrue(l.decide(info(position = 150_000), sounding = true, remote = false, now = 31_000).wifi)
        assertFalse(l.decide(info(position = 150_000 + hold), sounding = true, remote = false, now = 31_000 + hold).wifi)
        assertTrue(l.decide(info(id = "spotify:track:b"), sounding = true, remote = false, now = 200_000).wifi)
    }

    @Test fun aFetchAheadIsCovered() {
        val l = PlaybackLocks()
        l.decide(info(), sounding = true, remote = false, now = 0)
        l.noteLoad(100_000)
        assertTrue(l.decide(info(position = 100_000), sounding = true, remote = false, now = 100_000).wifi)
    }

    @Test fun aResumeAfterAPauseOrARemoteLoadsAgain() {
        val l = PlaybackLocks()
        l.decide(info(), sounding = true, remote = false, now = 0)
        l.decide(info(position = 30_000), sounding = true, remote = true, now = 30_000)
        assertTrue(l.decide(info(position = 31_000), sounding = true, remote = false, now = 100_000).wifi)
    }
}
