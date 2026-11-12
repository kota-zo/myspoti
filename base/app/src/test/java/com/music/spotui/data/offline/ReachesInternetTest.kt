package com.music.spotui.data.offline

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reported (#205): airplane mode with a VPN left on counted as online, and downloads stayed silent. */
class ReachesInternetTest {
    @Test fun `a VPN with nothing under it is offline`() {
        assertFalse(reachesInternet(internet = true, vpn = true, hasNetworkUnderVpn = { false }))
    }

    @Test fun `a VPN over Wi-Fi or mobile data is online`() {
        assertTrue(reachesInternet(internet = true, vpn = true, hasNetworkUnderVpn = { true }))
    }

    @Test fun `without a VPN the networks under it are not looked at`() {
        assertTrue(reachesInternet(internet = true, vpn = false, hasNetworkUnderVpn = { error("not a VPN") }))
    }

    @Test fun `a network without internet is offline`() {
        assertFalse(reachesInternet(internet = false, vpn = false, hasNetworkUnderVpn = { true }))
    }
}
