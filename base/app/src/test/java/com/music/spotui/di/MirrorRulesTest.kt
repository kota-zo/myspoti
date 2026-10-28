package com.music.spotui.di

import com.music.spotui.di.MirrorRules.Follow
import com.music.spotui.di.MirrorRules.Lead
import com.music.spotui.di.MirrorRules.Local
import com.music.spotui.di.MirrorRules.Origin
import com.music.spotui.di.MirrorRules.Want
import com.music.spotui.di.MirrorRules.Web
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MirrorRulesTest {
    private val id = "4uLU6hMCjMI75M1A2tKUQC"
    private val a = "spotify:track:$id"
    private val b = "spotify:track:7ouMYWpwJ422jRcDASZB7P"
    private val c = "spotify:track:0VjIjW4GlUZAMYd2vXMi3b"
    private val d = "spotify:track:1301WleyT98MSxVHPZCA6M"

    private fun local(uri: String = a, playing: Boolean = true, at: Long = 60_000, length: Long = 200_000) =
        Local(uri, playing, at, length, nextUri = b, previousUri = c)

    private fun onWeb(
        web: Web, want: Want = Want(a, false), local: Local = local(), quiet: Boolean = false, landed: Boolean = true,
        predicted: Long? = web.positionMs, previousUri: String? = null, gapFromMs: Long? = null, wantAtMs: Long? = null, wantLengthMs: Long = 0,
        recentUris: List<String> = emptyList(), repeats: Boolean = false,
    ) = MirrorRules.onWeb(web, want, local, quiet, landed, predicted, previousUri, gapFromMs, wantAtMs, wantLengthMs, recentUris, repeats)

    // ── A local song to play again (repeat once, a one-song queue repeated) ──

    @Test fun `the page going on at the end of a song to repeat is put back, never followed`() {
        // The report: Mentir sought to its end (130999 of 131000 ms), the page went on to the next song.
        val ending = local(at = 130_999, length = 131_000)
        assertEquals(Follow.Back, onWeb(Web(b, paused = false, positionMs = 12), local = ending, repeats = true))
        // The page's own end (another song than the queue's next, Spotify's relinked id): put back as well.
        assertEquals(Follow.Back, onWeb(Web(d, paused = false, positionMs = 0), local = local(at = 100_000), wantAtMs = 178_000, wantLengthMs = 180_000, repeats = true))
        // Without a repeat the local player goes on by itself: nothing to follow.
        assertEquals(Follow.Nothing, onWeb(Web(b, paused = false, positionMs = 12), local = ending))
    }

    @Test fun `a skip on another device mid-song is still followed with a repeat on`() {
        assertEquals(Follow.Next, onWeb(Web(b, paused = false, positionMs = 0), repeats = true))
    }

    @Test fun `a song whose length the player does not know is never near its end`() {
        // The mirror passes 0 when the audio's length is unknown: Spotify's length does not decide the end.
        assertFalse(local(at = 130_999, length = 0).nearEnd)
        assertEquals(Follow.Next, onWeb(Web(b, paused = false, positionMs = 0), local = local(at = 130_999, length = 0), repeats = true))
    }

    // ── Which songs are mirrored ──

    @Test fun `only an extension's stream with a Spotify id is mirrored`() {
        assertTrue(MirrorRules.eligible(Origin.AUDIO_SOURCE, id, on = true, dataSaver = false))
        assertFalse(MirrorRules.eligible(Origin.DOWNLOAD, id, on = true, dataSaver = false))
        assertFalse(MirrorRules.eligible(Origin.LOCAL_FILE, id, on = true, dataSaver = false))
        assertFalse(MirrorRules.eligible(Origin.AUDIO_SOURCE, "", on = true, dataSaver = false))
        assertFalse(MirrorRules.eligible(Origin.AUDIO_SOURCE, "not-an-id", on = true, dataSaver = false))
    }

    @Test fun `the setting off or the data saver leave the stream alone`() {
        assertFalse(MirrorRules.eligible(Origin.AUDIO_SOURCE, id, on = false, dataSaver = false))
        assertFalse(MirrorRules.eligible(Origin.AUDIO_SOURCE, id, on = true, dataSaver = true))
    }

    // ── Loop guard: the mirror's own commands are not remote ones ──

    @Test fun `states of the asked or the previous song during the echo of a command are not followed`() {
        assertEquals(Follow.Nothing, onWeb(Web(a, paused = true, positionMs = 60_000), quiet = true))
        assertEquals(Follow.Nothing, onWeb(Web(d, paused = false, positionMs = 0), quiet = true, previousUri = d))
        assertEquals(Follow.Nothing, onWeb(Web(d, paused = false, positionMs = 0), quiet = true, landed = false))
    }

    @Test fun `the echo window lasts from the last command`() {
        var now = 0L
        val echo = MirrorRules.Echo { now }
        echo.sent(2_500)
        now = 2_000; assertTrue(echo.quiet())
        echo.sent(2_500)
        now = 4_000; assertTrue(echo.quiet())
        now = 4_600; assertFalse(echo.quiet())
        echo.sent(1_000); echo.clear(); assertFalse(echo.quiet())
    }

    @Test fun `a state that matches what was asked is not a command`() {
        assertEquals(Follow.Nothing, onWeb(Web(a, paused = false, positionMs = 60_500)))
        assertEquals(Follow.Nothing, onWeb(Web(a, paused = true, positionMs = 60_000), want = Want(a, true), local = local(playing = false)))
    }

    @Test fun `a play that never landed is sent again, whatever the page shows`() {
        assertEquals(Follow.Retry, onWeb(Web(d, paused = false, positionMs = 10_000), landed = false))
        assertEquals(Follow.Retry, onWeb(Web(a, paused = true, positionMs = 0), landed = false))
    }

    @Test fun `a play paused on the phone before it landed is not sent again`() {
        assertEquals(Follow.Nothing, onWeb(Web(a, paused = true, positionMs = 0), want = Want(a, true), local = local(playing = false), landed = false))
    }

    // ── Remote commands the local player follows ──

    @Test fun `pause and resume from another device`() {
        assertEquals(Follow.Pause, onWeb(Web(a, paused = true, positionMs = 60_000)))
        assertEquals(Follow.Resume, onWeb(Web(a, paused = false, positionMs = 60_000), want = Want(a, true), local = local(playing = false)))
    }

    @Test fun `a jump of the page's own clock is a seek`() {
        assertEquals(Follow.Seek(120_000), onWeb(Web(a, paused = false, positionMs = 120_000), predicted = 60_200))
        // A previous after 3 s is a seek to 0 on Spotify's side.
        assertEquals(Follow.Seek(0), onWeb(Web(a, paused = false, positionMs = 0), predicted = 60_200))
    }

    @Test fun `next and previous follow the local queue when the page went there`() {
        assertEquals(Follow.Next, onWeb(Web(b, paused = false, positionMs = 0)))
        assertEquals(Follow.Previous, onWeb(Web(c, paused = false, positionMs = 0)))
    }

    @Test fun `another song is routed from where the page is`() {
        assertEquals(Follow.Play(d, 4_000), onWeb(Web(d, paused = false, positionMs = 4_000)))
    }

    @Test fun `the page moving on at the end of the song is that end, not a skip`() {
        val ending = local(at = 197_000, length = 200_000)
        assertEquals(Follow.Nothing, onWeb(Web(d, paused = false, positionMs = 0), local = ending))
        assertEquals(Follow.Nothing, onWeb(Web(a, paused = true, positionMs = 200_000), local = ending))
    }

    @Test fun `the local player already on its next song, the page is told by the local side`() {
        assertEquals(Follow.Nothing, onWeb(Web(a, paused = false, positionMs = 199_000), local = local(uri = b, at = 500)))
    }

    // ── Drift ──

    @Test fun `drift under the threshold is left alone`() {
        assertEquals(Follow.Nothing, onWeb(Web(a, paused = false, positionMs = 61_400)))
        assertEquals(Lead.Nothing, MirrorRules.onLocal(local(), Want(a, false), stalled = false, quiet = false, webAtMs = 58_600))
    }

    @Test fun `drift over the threshold puts the unheard page back on the local position`() {
        assertEquals(Follow.Align(60_000), onWeb(Web(a, paused = false, positionMs = 62_000)))
        assertEquals(Lead.Align(60_000), MirrorRules.onLocal(local(), Want(a, false), stalled = false, quiet = false, webAtMs = 57_000))
    }

    @Test fun `no correction while a command is answering or when paused`() {
        assertEquals(Lead.Nothing, MirrorRules.onLocal(local(), Want(a, false), stalled = false, quiet = true, webAtMs = 57_000))
        assertEquals(Follow.Nothing, onWeb(Web(a, paused = true, positionMs = 50_000), want = Want(a, true), local = local(playing = false), predicted = 50_000))
    }

    // ── The local player leads the page ──

    @Test fun `a new local song starts on the page once it plays`() {
        assertEquals(Lead.Start(b, 0), MirrorRules.onLocal(local(uri = b, at = 0), Want(a, false), stalled = false, quiet = false, webAtMs = null))
        assertEquals(Lead.Start(a, 60_000), MirrorRules.onLocal(local(), null, stalled = false, quiet = false, webAtMs = null))
    }

    @Test fun `a new local song that does not play yet pauses the old one on the page`() {
        assertEquals(Lead.Pause, MirrorRules.onLocal(local(uri = b, playing = false), Want(a, false), stalled = false, quiet = false, webAtMs = null))
        assertEquals(Lead.Nothing, MirrorRules.onLocal(local(uri = b, playing = false), Want(a, true), stalled = false, quiet = false, webAtMs = null))
        assertEquals(Lead.Nothing, MirrorRules.onLocal(local(playing = false), null, stalled = false, quiet = false, webAtMs = null))
    }

    @Test fun `pause and resume on the phone go to the page`() {
        assertEquals(Lead.Pause, MirrorRules.onLocal(local(playing = false), Want(a, false), stalled = false, quiet = false, webAtMs = 60_000))
        assertEquals(Lead.Resume(null), MirrorRules.onLocal(local(), Want(a, true), stalled = false, quiet = true, webAtMs = 60_300))
    }

    @Test fun `a stall pauses the page and the resume puts both on the same place`() {
        assertEquals(Lead.Pause, MirrorRules.onLocal(local(), Want(a, false), stalled = true, quiet = false, webAtMs = 61_000))
        assertEquals(Lead.Resume(60_000), MirrorRules.onLocal(local(), Want(a, true), stalled = false, quiet = false, webAtMs = 62_000))
    }

    // ── Review fixes: echo window, the page's own end, seeks across a buffering gap ──

    @Test fun `a third song during the echo of a remote skip is still followed`() {
        // A -> B on another device (the mirror now wants B, A before it), then C within the window.
        val onB = local(uri = b).copy(nextUri = d, previousUri = a)
        assertEquals(Follow.Next, onWeb(Web(d, paused = false, positionMs = 0), want = Want(b, false), local = onB, quiet = true, previousUri = a))
        // The old song and the asked one stay the echo.
        assertEquals(Follow.Nothing, onWeb(Web(a, paused = false, positionMs = 61_000), want = Want(b, false), local = onB, quiet = true, previousUri = a))
        assertEquals(Follow.Nothing, onWeb(Web(b, paused = true, positionMs = 0), want = Want(b, false), local = onB, quiet = true, previousUri = a))
    }

    @Test fun `before the asked song landed, no third song is taken for a remote one`() {
        assertEquals(Follow.Nothing, onWeb(Web(d, paused = false, positionMs = 0), want = Want(b, false), local = local(uri = b), quiet = true, landed = false, previousUri = a))
    }

    @Test fun `the local player not yet on the song a remote skip chose is not sent back`() {
        assertEquals(Lead.Nothing, MirrorRules.onLocal(local(uri = a), Want(b, false), stalled = false, quiet = true, webAtMs = null, previousUri = a))
        assertEquals(Lead.Start(a, 60_000), MirrorRules.onLocal(local(uri = a), Want(b, false), stalled = false, quiet = false, webAtMs = null, previousUri = a))
    }

    @Test fun `the page reaching its own end first is that end, even with a longer local stream`() {
        // The extension's stream is 20 s longer than Spotify's 180 s track: the local player is not near its end.
        val longer = local(at = 178_000, length = 200_000)
        assertEquals(Follow.Nothing, onWeb(Web(d, paused = false, positionMs = 0), local = longer, wantAtMs = 178_500, wantLengthMs = 180_000))
        assertEquals(Follow.Nothing, onWeb(Web(a, paused = true, positionMs = 180_000), local = longer, wantAtMs = 180_000, wantLengthMs = 180_000))
        // Far from the page's end, the same moves are remote ones.
        assertEquals(Follow.Play(d, 0), onWeb(Web(d, paused = false, positionMs = 0), local = longer, wantAtMs = 120_000, wantLengthMs = 180_000))
        assertEquals(Follow.Pause, onWeb(Web(a, paused = true, positionMs = 120_000), local = longer, wantAtMs = 120_000, wantLengthMs = 180_000))
    }

    @Test fun `a remote seek followed by buffering is still a seek`() {
        // At 60 s before the gap, predicted 64 s after it: a jump to 150 s is a seek, not drift.
        assertEquals(Follow.Seek(150_000), onWeb(Web(a, paused = false, positionMs = 150_000), local = local(at = 64_000), predicted = 64_000, gapFromMs = 60_000))
        assertEquals(Follow.Seek(10_000), onWeb(Web(a, paused = false, positionMs = 10_000), local = local(at = 64_000), predicted = 64_000, gapFromMs = 60_000))
    }

    @Test fun `a page that stalled in a buffering gap is drift, put back on the local position`() {
        assertEquals(Follow.Align(64_000), onWeb(Web(a, paused = false, positionMs = 61_000), local = local(at = 64_000), predicted = 64_000, gapFromMs = 60_000))
    }

    // ── Phone test of build 223 ──

    @Test fun `the page left on an earlier play of a fast skip burst is resent, never a remote previous`() {
        // Plays of a, c (the local queue's previous) and b in 0.7 s; the page settled on c.
        val onB = local(uri = b).copy(nextUri = d, previousUri = c)
        val recent = listOf(a, c, b)
        assertEquals(Follow.Resend, onWeb(Web(c, paused = false, positionMs = 6_000), want = Want(b, false), local = onB, recentUris = recent))
        assertEquals(Follow.Resend, onWeb(Web(c, paused = false, positionMs = 6_000), want = Want(b, false), local = onB, landed = false, recentUris = recent))
        // During the echo it is nothing at all, not the "third song" of a remote double skip.
        assertEquals(Follow.Nothing, onWeb(Web(c, paused = false, positionMs = 1_000), want = Want(b, false), local = onB, quiet = true, previousUri = a, recentUris = recent))
        // A song the mirror did not ask lately is still a remote previous.
        assertEquals(Follow.Previous, onWeb(Web(c, paused = false, positionMs = 0), want = Want(b, false), local = onB, recentUris = listOf(b)))
    }

    @Test fun `playback back on the phone goes to the extensions once it plays here`() {
        // Transferred back paused: nothing yet, the hand-over is kept for when it plays.
        assertFalse(MirrorRules.backHere(playingHere = false, handedAway = true, moved = false, intended = false, on = true))
        assertTrue(MirrorRules.backHere(playingHere = true, handedAway = true, moved = false, intended = false, on = true))
        // Resumed or skipped from another device while the engine plays here.
        assertTrue(MirrorRules.backHere(playingHere = true, handedAway = false, moved = true, intended = false, on = true))
        // Myspoti's own play, nothing moved, or the setting off: the engine plays on.
        assertFalse(MirrorRules.backHere(playingHere = true, handedAway = true, moved = true, intended = true, on = true))
        assertFalse(MirrorRules.backHere(playingHere = true, handedAway = false, moved = false, intended = false, on = true))
        assertFalse(MirrorRules.backHere(playingHere = true, handedAway = true, moved = true, intended = false, on = false))
    }

    // ── Report on build 225: plays not taken, ads and reloads ──

    @Test fun `a song the page would not play is tried again later, never given up`() {
        assertEquals(5_000, MirrorRules.backoffMs(0))
        assertEquals(15_000, MirrorRules.backoffMs(1))
        assertEquals(30_000, MirrorRules.backoffMs(2))
        assertEquals(30_000, MirrorRules.backoffMs(9))
    }

    @Test fun `an artist context or one that missed plays the song alone`() {
        val artist = "spotify:artist:7jVv8c5Fj3E9VhNjxT4snq"
        val playlist = "spotify:playlist:37i9dQZF1DXcBWIGoYBM5M"
        assertEquals(a, MirrorRules.playContext(artist, a, emptySet()))
        assertEquals(playlist, MirrorRules.playContext(playlist, a, emptySet()))
        assertEquals(a, MirrorRules.playContext(playlist, a, setOf(playlist)))
        assertEquals(a, MirrorRules.playContext("", a, emptySet()))
    }

    @Test fun `a play, a resume or a seek of the local player is a move, its clock running on is not`() {
        assertTrue(MirrorRules.localMoved(wasPlaying = false, playing = true, expectedMs = 30_000, positionMs = 30_000))
        assertTrue(MirrorRules.localMoved(wasPlaying = true, playing = true, expectedMs = 30_000, positionMs = 90_000))
        assertFalse(MirrorRules.localMoved(wasPlaying = true, playing = true, expectedMs = 30_000, positionMs = 30_400))
        assertFalse(MirrorRules.localMoved(wasPlaying = true, playing = false, expectedMs = null, positionMs = 0))
    }

    @Test fun `while the page recovers, another song is sent back, never followed`() {
        // The player's own recovery (the song "ahead" of an ad) put the page elsewhere: seen as not landed.
        assertEquals(Follow.Retry, onWeb(Web(d, paused = false, positionMs = 0), landed = false))
        assertEquals(Follow.Nothing, onWeb(Web(a, paused = false, positionMs = 60_000), landed = false))
    }

    @Test fun pageBackAfterAReloadWaitsForItsDeviceToJoin() {
        // Ready, no ad, but the device not joined yet: a play now was refused (DEVICE_NOT_FOUND).
        assertFalse(MirrorRules.pageUsable(ready = true, ad = false, joined = false, joinReported = true, waitedOut = false))
        assertTrue(MirrorRules.pageUsable(ready = true, ad = false, joined = true, joinReported = true, waitedOut = false))
        // No join came within the wait: commanded anyway.
        assertTrue(MirrorRules.pageUsable(ready = true, ad = false, joined = false, joinReported = true, waitedOut = true))
        // A host that never reports joins is not waited on.
        assertTrue(MirrorRules.pageUsable(ready = true, ad = false, joined = false, joinReported = false, waitedOut = false))
        // An ad or a page not loaded: never.
        assertFalse(MirrorRules.pageUsable(ready = true, ad = true, joined = true, joinReported = true, waitedOut = true))
        assertFalse(MirrorRules.pageUsable(ready = false, ad = false, joined = true, joinReported = true, waitedOut = true))
    }

    @Test fun `another install of the app that resumed playback is not fought over`() {
        // The page shows the other device's song as playing: the device list says it is not this phone.
        assertFalse(MirrorRules.backHere(playingHere = true, handedAway = false, moved = true, intended = false, on = true, activeElsewhere = true))
        assertFalse(MirrorRules.backHere(playingHere = true, handedAway = true, moved = false, intended = false, on = true, activeElsewhere = true))
        assertTrue(MirrorRules.backHere(playingHere = true, handedAway = true, moved = false, intended = false, on = true, activeElsewhere = false))
        // A remote next, previous or pick is followed only while this phone has playback.
        assertTrue(MirrorRules.followsRemote(activeElsewhere = false))
        assertFalse(MirrorRules.followsRemote(activeElsewhere = true))
    }
}
