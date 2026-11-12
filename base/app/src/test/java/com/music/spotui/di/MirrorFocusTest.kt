/* SPDX-License-Identifier: GPL-3.0-only */
package com.music.spotui.di

import app.myspoti.api.account.AccountProviders
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The local player gives up the audio focus only to a mirror on a page, which asks for it as it
 * plays; the native engine's mirror is muted and asks for none, so the local player keeps its own
 * and still pauses for a call or another app.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class MirrorFocusTest {
    @Test fun theEnginesMirrorLeavesTheFocusToTheLocalPlayer() {
        assertFalse(AccountSync.mirrorYieldsFocus(on = true, key = "spotify:engine"))
    }

    @Test fun aPagesMirrorTakesItAsBefore() {
        assertTrue(AccountSync.mirrorYieldsFocus(on = true, key = AccountProviders.SPOTIFY))
        assertTrue(AccountSync.mirrorYieldsFocus(on = true, key = AccountProviders.YTMUSIC))
    }

    @Test fun noMirrorNoYield() {
        assertFalse(AccountSync.mirrorYieldsFocus(on = false, key = AccountProviders.SPOTIFY))
        assertFalse(AccountSync.mirrorYieldsFocus(on = false, key = "spotify:engine"))
    }
}
