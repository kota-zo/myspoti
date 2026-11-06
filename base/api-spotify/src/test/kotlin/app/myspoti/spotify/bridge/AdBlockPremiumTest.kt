package app.myspoti.spotify.bridge

import app.myspoti.spotify.AccountPlan
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The ad machinery with the account's plan: unknown and free refuse and rewrite as before, Premium does not. */
class AdBlockPremiumTest {
    @AfterEach fun forget() = AccountPlan.set(null)

    private val adAudio = "https://adstudio-assets.scdn.co/mp3/d0448345e619692e0b13626ad4994d4b824f7107.mp3"
    private val adServer = "https://pubads.g.doubleclick.net/gampad/ads?x=1"
    private val adMedia = "https://s0.2mdn.net/ads/richmedia/studio/abc.mp4"
    private val tracker = "https://www.googletagmanager.com/gtm.js?id=x"

    private fun track(type: String, name: String) =
        """{"content_type":"$type","metadata":{"uri":"spotify:${type.lowercase()}:${name.replace(' ', '-')}","name":"$name"},"manifest":{}}"""

    private fun go(to: Int?) = if (to == null) "null" else """{"state_index":$to,"paused":false,"active_alias":null}"""

    private fun state(track: Int, advance: Int? = null, next: Int? = null, show: Int? = null) =
        """{"state_id":"s$track","track":$track,"transitions":{"skip_next":${go(next)},"skip_prev":null,"show_next":${go(show)},"show_prev":null,"advance":${go(advance)}}}"""

    // The song before an ad: its end goes to the ad, its skip to the song after it (as in AdQueueTest).
    private val withAd = """{"state_machine":{"state_machine_id":"m","tracks":[${track("AD", "An ad")},${track("TRACK", "Before")},${track("TRACK", "Playing")},${track("TRACK", "After")}],"states":[${state(0)},${state(1)},${state(2, advance = 0, next = 4, show = 4)},${state(2)},${state(3)}],"attributes":{}},"updated_state_ref":{"state_index":2,"paused":false}}"""

    @Test fun `unknown and free accounts have the ad hosts refused`() {
        for (plan in listOf(null, false)) {
            AccountPlan.set(plan)
            assertTrue(AdBlock.isBlocked(adAudio), "$plan")
            assertTrue(AdBlock.isBlocked(adServer), "$plan")
            assertTrue(AdBlock.isBlocked(adMedia), "$plan")
            assertTrue(AdBlock.isBlocked(tracker), "$plan")
            assertNotNull(AdBlock.withoutAds(withAd), "$plan")
        }
    }

    @Test fun `on Premium the ad hosts are let through and the trackers stay refused`() {
        AccountPlan.set(true)
        assertFalse(AdBlock.isBlocked(adAudio))
        assertFalse(AdBlock.isBlocked(adServer))
        assertFalse(AdBlock.isBlocked(adMedia))
        assertTrue(AdBlock.isBlocked(tracker))
        assertTrue(AdBlock.isBlocked("https://www.google-analytics.com/collect"))
        assertTrue(AdBlock.isBlocked("https://scorecardresearch.com/beacon.js"))
        assertTrue(AdBlock.isBlocked("https://fastly-insights.com/x.js"))
        assertFalse(AdBlock.isBlocked("https://audio-fa.scdn.co/audio/abc"))
    }

    @Test fun `on Premium an ad in the queue is not left out and is not reported as kept`() {
        AccountPlan.set(true)
        assertNull(AdBlock.withoutAds(withAd))
        assertNull(AdBlock.adAhead(withAd))
        assertNull(AdBlock.whyKept(withAd))
    }

    @Test fun `the page keeps its ad managers on Premium`() {
        // The bridge reads the flag the host sets at each injection (Bridge.inject).
        assertTrue("window.__mspKeepAds) return 'kept (Premium)';" in BridgeScript.source)
    }
}
