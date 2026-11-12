package app.myspoti.spotify.bridge

import app.myspoti.spotify.AccountPlan
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The ad blocker for Spotify Connect devices: when it acts, and what the bundled list refuses then. */
class AdBlockTest {
    @AfterEach fun forget() { AdBlock.setActive(false); AccountPlan.set(null) }

    private val adAudio = "https://adstudio-assets.scdn.co/mp3/d0448345e619692e0b13626ad4994d4b824f7107.mp3"
    private val tracker = "https://www.googletagmanager.com/gtm.js?id=x"

    @Test fun `the blocker acts only when a remote Connect device the app sent the music to is the active player`() {
        // The phone's own page plays (the account's player, or the listen-sync mirror): off.
        assertFalse(AdBlock.activeFor(elsewhere = false, activeId = null, chosenId = null, jamMember = false, premium = false))
        assertFalse(AdBlock.activeFor(elsewhere = false, activeId = "speaker", chosenId = "speaker", jamMember = false, premium = false))
        // Projected to the speaker picked in the app: on.
        assertTrue(AdBlock.activeFor(elsewhere = true, activeId = "speaker", chosenId = "speaker", jamMember = false, premium = false))
        // Another device took playback by itself (the desktop app), or none was picked here: off.
        assertFalse(AdBlock.activeFor(elsewhere = true, activeId = "desktop", chosenId = "speaker", jamMember = false, premium = false))
        assertFalse(AdBlock.activeFor(elsewhere = true, activeId = "desktop", chosenId = null, jamMember = false, premium = false))
        // A Jam's device, a Jam joined as a member, a Premium account: off.
        assertFalse(AdBlock.activeFor(elsewhere = true, activeId = "social-connect-x", chosenId = "social-connect-x", jamMember = false, premium = false))
        assertFalse(AdBlock.activeFor(elsewhere = true, activeId = "speaker", chosenId = "speaker", jamMember = true, premium = false))
        assertFalse(AdBlock.activeFor(elsewhere = true, activeId = "speaker", chosenId = "speaker", jamMember = false, premium = true))
    }

    @Test fun `while the blocker is off the page is refused nothing, ads and trackers alike`() {
        assertFalse(AdBlock.active.value)
        assertFalse(AdBlock.isBlocked(adAudio))
        assertFalse(AdBlock.isBlocked("https://pubads.g.doubleclick.net/gampad/ads?x=1"))
        assertFalse(AdBlock.isBlocked(tracker))
        assertFalse(AdBlock.isBlocked("https://www.google-analytics.com/collect"))
        assertFalse(AdBlock.isBlocked("https://open.spotifycdn.com/cdn/js/retargeting-pixels.4c1d.js"))
    }

    @Test fun `while it acts the ad hosts and paths of the list are refused`() {
        AdBlock.setActive(true)
        assertTrue(AdBlock.isBlocked(adAudio))
        assertTrue(AdBlock.isBlocked("https://pubads.g.doubleclick.net/gampad/ads?x=1"))
        assertTrue(AdBlock.isBlocked("https://imasdk.googleapis.com/js/sdkloader/ima3.js"))
        // From uAssets: the whole 2mdn.net (s0 included), adxcel, amillionads, and scdn.co's ad mp3 path.
        assertTrue(AdBlock.isBlocked("https://s0.2mdn.net/ads/richmedia/studio/abc.mp4"))
        assertTrue(AdBlock.isBlocked("https://x.2mdn.net/videoplayback/abc"))
        assertTrue(AdBlock.isBlocked("https://cdn.adxcel.com/a.mp3"))
        assertTrue(AdBlock.isBlocked("https://media.amillionads.com/x.mp3"))
        assertTrue(AdBlock.isBlocked("https://audio-ak.scdn.co/mp3-ad/abc"))
        // The trackers of the list too, on a Connect device only.
        assertTrue(AdBlock.isBlocked(tracker))
        assertTrue(AdBlock.isBlocked("https://www.google-analytics.com/collect"))
        // Never the songs, the covers or Spotify's own endpoints (a client that never calls them stands out).
        assertFalse(AdBlock.isBlocked("https://audio-fa.scdn.co/audio/abc"))
        assertFalse(AdBlock.isBlocked("https://audio-ak.scdn.co/mp3/abc"))
        assertFalse(AdBlock.isBlocked("https://i.scdn.co/image/ab67616d0000b273"))
        assertFalse(AdBlock.isBlocked("https://spclient.wg.spotify.com/ads/v2/config"))
        assertFalse(AdBlock.isBlocked("https://gew4-spclient.spotify.com/ad-logic/state/config?a=b"))
        assertFalse(AdBlock.isBlocked("https://gew4-spclient.spotify.com/track-playback/v1/devices"))
        assertFalse(AdBlock.isBlocked("https://open.spotify.com/ads/"))
        // A host named in a path or a query is not the request's host.
        assertFalse(AdBlock.isBlocked("https://evil.com/adstudio-assets.scdn.co/x.mp3"))
        assertFalse(AdBlock.isBlocked("https://evil.com/x?u=https://s0.2mdn.net/x.mp4"))
        assertFalse(AdBlock.isBlocked("https://notadxcel.com/a.mp3"))
    }

    @Test fun `a Premium account never turns it on`() {
        AccountPlan.set(true)
        AdBlock.setActive(true)
        assertFalse(AdBlock.active.value)
        assertFalse(AdBlock.isBlocked(adAudio))
        assertFalse(AdBlock.isBlocked(tracker))
    }

    @Test fun `the EasyPrivacy path rule refuses the retargeting script only`() {
        AdBlock.setActive(true)
        assertTrue(AdBlock.isBlocked("https://open.spotifycdn.com/cdn/js/retargeting-pixels.4c1d.js"))
        assertFalse(AdBlock.isBlocked("https://open.spotifycdn.com/cdn/js/web-player.4c1d.js"))
    }

    @Test fun `the list reads its sections, comments and path rules`() {
        val r = AdBlock.parse("x.com\n[ads]\nads.example # note\nexample.net/ads/\n[other]\nignored.org\n[trackers]\nt.example\n")
        assertEquals(listOf(AdBlock.Rule("ads.example", null), AdBlock.Rule("example.net", "/ads/")), r.ads)
        assertEquals(listOf(AdBlock.Rule("t.example", null)), r.trackers)
    }

    @Test fun `the bundled list names its sources and their commits`() {
        val text = AdBlock::class.java.getResourceAsStream("/bridge/page-filter.txt")!!.bufferedReader().readText()
        assertTrue("uBlockOrigin/uAssets" in text && "dbc8ad17eea0d9f2815553c099643995f7a0ae83" in text)
        assertTrue("easylist/easylist" in text && "876bb4a29090827868d93ab8d00960f959e16028" in text)
        assertTrue("adstudio-assets.scdn.co" in AdBlock.hosts && "googletagmanager.com" in AdBlock.hosts)
    }

    @Test
    fun `promotion shelves and sponsored cards are told apart`() {
        assertTrue(app.myspoti.spotify.isPromotion("HomePromotionSectionData"))
        assertTrue(app.myspoti.spotify.isPromotion("HomePromotionShelf"))
        assertTrue(app.myspoti.spotify.isPromotion("SponsoredPlaylist"))
        assertTrue(app.myspoti.spotify.isPromotion("EmbeddedAdVisual"))
        assertFalse(app.myspoti.spotify.isPromotion("HomeGenericSectionData"))
        assertFalse(app.myspoti.spotify.isPromotion("PlaylistResponseWrapper"))
        assertFalse(app.myspoti.spotify.isPromotion("AlbumResponseWrapper"))
        assertFalse(app.myspoti.spotify.isPromotion("Audiobook"))
    }

    @Test
    fun `base62 ids convert to metadata gids`() {
        // Pair checked against a captured metadata/4 request.
        assertTrue(app.myspoti.spotify.base62ToHex("55vPnuXfuzXNW75JpuSczF") == "a734c1d4e4d84c4093c32d5bce9797a3")
    }

    @Test
    fun `bridge script is bundled, without the page's ad switches`() {
        assertTrue(BridgeScript.source.contains("window.Myspoti"))
        assertFalse("AdManagers" in BridgeScript.source)
        assertFalse("'media.mute'" in BridgeScript.source)
    }
}
