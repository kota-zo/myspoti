/* SPDX-License-Identifier: GPL-3.0-only */
package com.music.spotui.pass

import android.content.Intent
import android.net.Uri
import app.myspoti.pass.PassCrypto
import app.myspoti.pass.PassInvite
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The pass QR code read by a phone's camera: its link opens this app's main activity (the
 * manifest's filters), and only the pass's links are taken by [PassLinks].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class PassLinkIntentTest {
    private val invite = PassInvite("192.168.1.20", 41234, PassCrypto.keyPair().public.encoded, PassCrypto.randomBytes(16), "Salon TV")

    private fun opens(link: String): List<String> {
        val app = RuntimeEnvironment.getApplication()
        val view = Intent(Intent.ACTION_VIEW, Uri.parse(link)).addCategory(Intent.CATEGORY_BROWSABLE).setPackage(app.packageName)
        return app.packageManager.queryIntentActivities(view, 0).map { it.activityInfo.name }
    }

    @Test fun `the QR code's link opens the main activity, as the codes of before`() {
        assertEquals(listOf("app.myspoti.MainActivity"), opens(invite.toText()))
        val old = invite.toText().replace("orpheon://pass?v=1&", "orpheon-pass:1?")
        assertEquals(listOf("app.myspoti.MainActivity"), opens(old))
    }

    @Test fun `another link of the scheme opens nothing`() {
        assertTrue(opens("orpheon://settings?x=1").isEmpty())
        assertTrue(opens("https://example.com/pass").isEmpty())
    }

    @Test fun `only the pass's links are taken`() {
        assertTrue(PassLinks.isPassLink(invite.toText()))
        assertTrue(PassLinks.isPassLink("orpheon://pass?broken"))
        assertTrue(PassLinks.isPassLink("orpheon-pass:1?a=1"))
        assertFalse(PassLinks.isPassLink("https://open.spotify.com/track/0000000000000000000000"))
        assertFalse(PassLinks.isPassLink("spotify:track:0000000000000000000000"))
    }
}
