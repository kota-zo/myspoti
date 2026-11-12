/* SPDX-License-Identifier: GPL-3.0-only */
package com.music.spotui.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import app.myspoti.api.account.AccountProviders
import app.myspoti.engine.R
import com.music.spotui.data.preferences.Look
import com.music.spotui.data.preferences.LookPref
import com.music.spotui.di.Graph
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The first launch: the music account is chosen before any sign-in, Spotify's sign-in is asked only
 * for Spotify, someone already signed in is asked nothing again, and the "new look" popup never
 * follows an onboarding whose look step asked it already (it stays for an existing user who never saw it).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OnboardingTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val app get() = RuntimeEnvironment.getApplication()
    private fun prefs(name: String) = app.getSharedPreferences(name, 0)

    @Before fun setUp() {
        Graph::class.java.getDeclaredField("app").apply { isAccessible = true }.set(null, app)
        prefs("myspoti_prefs").edit().clear().commit()
        prefs("settings_prefs").edit().clear().commit()
        LookPref.clearOffered(app)
    }

    @After fun tearDown() { LookPref.set(app, Look.CLASSIC) }

    private fun entry(
        signedIn: Boolean, chosen: Boolean, provider: String, setup: Boolean = true,
        connecting: Boolean = false, choosing: Boolean = false, tv: Boolean = false,
    ) = Onboarding.entry(signedIn, chosen, provider, setup, connecting, choosing, tv)

    @Test fun `a new install chooses its account before Spotify's sign-in`() {
        // Welcome first; its button leads to the account choice, not to Spotify's sign-in page.
        assertEquals(Entry.WELCOME, entry(signedIn = false, chosen = false, provider = AccountProviders.SPOTIFY))
        assertTrue(Onboarding.welcomeChoosesAccount(tv = false))
        assertEquals(Entry.ACCOUNT, entry(signedIn = false, chosen = false, provider = AccountProviders.SPOTIFY, choosing = true))
        // Spotify chosen: its sign-in, and nothing of the app before it.
        assertEquals(AfterChoice.SPOTIFY_SIGN_IN, Onboarding.afterChoice(AccountProviders.SPOTIFY, ytmSignedIn = false))
        Setup.setAccountSeen(app)
        assertEquals(Entry.WELCOME, entry(signedIn = false, chosen = true, provider = AccountProviders.SPOTIFY))
        assertEquals(Entry.CONNECTING, entry(signedIn = false, chosen = true, provider = AccountProviders.SPOTIFY, connecting = true))
        // Signed in: the setup, which no longer asks the account (chosen before the sign-in).
        assertEquals(Entry.SETUP, entry(signedIn = true, chosen = true, provider = AccountProviders.SPOTIFY))
        val steps = Setup.steps(app)
        assertFalse(STEP_ACCOUNT in steps)
        assertEquals(listOf(STEP_INTRO, STEP_LOOK, STEP_QUALITY, STEP_PERMISSIONS, STEP_EXTENSIONS), steps)
    }

    @Test fun `no account or YouTube Music opens with no Spotify sign-in`() {
        assertEquals(AfterChoice.APP, Onboarding.afterChoice(AccountProviders.NONE, ytmSignedIn = false))
        assertEquals(Entry.SETUP, entry(signedIn = false, chosen = true, provider = AccountProviders.NONE))
        assertEquals(Entry.APP, entry(signedIn = false, chosen = true, provider = AccountProviders.NONE, setup = false))
        // YouTube Music: its own sign-in while signed out, then the app.
        assertEquals(AfterChoice.YTM_SIGN_IN, Onboarding.afterChoice(AccountProviders.YTMUSIC, ytmSignedIn = false))
        assertEquals(AfterChoice.APP, Onboarding.afterChoice(AccountProviders.YTMUSIC, ytmSignedIn = true))
        assertEquals(Entry.SETUP, entry(signedIn = false, chosen = true, provider = AccountProviders.YTMUSIC))
        assertTrue(Onboarding.opensWithoutSpotify(accountChosen = true, provider = AccountProviders.YTMUSIC, tv = false))
        // Still on the choice (nothing confirmed): another provider ticked does not open the app under it.
        assertEquals(Entry.ACCOUNT, entry(signedIn = false, chosen = false, provider = AccountProviders.NONE, choosing = true))
    }

    @Test fun `someone already signed in is asked nothing again`() {
        // An update: signed in, every step seen. Straight to the app, no choice, no sign-in.
        prefs("myspoti_prefs").edit().putBoolean("SetupDone", true).putBoolean("SetupExtensionsSeen", true)
            .putBoolean("SetupAccountSeen", true).commit()
        com.music.spotui.data.preferences.setQualityChosen(app)
        LookPref.setChosen(app)
        assertFalse(Setup.needed(app))
        for (provider in AccountProviders.OFFERED) {
            assertEquals(Entry.APP, entry(signedIn = true, chosen = true, provider = provider, setup = false))
            assertEquals(Entry.APP, entry(signedIn = true, chosen = false, provider = provider, setup = false))
        }
        // Signed in before the account step existed: only that step, after the sign-in already made.
        prefs("myspoti_prefs").edit().remove("SetupAccountSeen").commit()
        assertEquals(Entry.SETUP, entry(signedIn = true, chosen = false, provider = AccountProviders.SPOTIFY))
        assertEquals(listOf(STEP_ACCOUNT), Setup.steps(app))
    }

    @Test fun `a TV keeps its Spotify sign-in`() {
        assertFalse(Onboarding.welcomeChoosesAccount(tv = true))
        assertEquals(Entry.WELCOME, entry(signedIn = false, chosen = false, provider = AccountProviders.SPOTIFY, choosing = true, tv = true))
        assertEquals(Entry.WELCOME, entry(signedIn = false, chosen = true, provider = AccountProviders.NONE, tv = true))
    }

    @Test fun `the new look popup never follows the onboarding`() {
        // A new install: not offered yet. Never over the onboarding's steps.
        assertFalse(LookPref.offered)
        assertFalse(Onboarding.lookOfferShown(Entry.SETUP, false, false, false, LookPref.offered, tv = false))
        assertFalse(Onboarding.lookOfferShown(Entry.WELCOME, false, false, false, LookPref.offered, tv = false))
        // The look step's Continue: the popup is spent at once, without the activity starting again.
        LookPref.setChosen(app)
        assertTrue(LookPref.offered)
        assertFalse(Onboarding.lookOfferShown(Entry.APP, false, false, false, LookPref.offered, tv = false))
        assertTrue(LookPref.isOffered(app))
    }

    @Test fun `an existing user who never saw the popup still gets it, once`() {
        LookPref.clearOffered(app)
        assertTrue(Onboarding.lookOfferShown(Entry.APP, false, false, false, LookPref.offered, tv = false))
        // Not over a sign-in page, the picture-in-picture window, or on a TV.
        assertFalse(Onboarding.lookOfferShown(Entry.APP, false, true, false, LookPref.offered, tv = false))
        assertFalse(Onboarding.lookOfferShown(Entry.APP, false, false, true, LookPref.offered, tv = false))
        assertFalse(Onboarding.lookOfferShown(Entry.APP, false, false, false, LookPref.offered, tv = true))
        LookPref.setOffered(app)
        assertFalse(Onboarding.lookOfferShown(Entry.APP, false, false, false, LookPref.offered, tv = false))
    }

    /** The account choice drawn before any sign-in: Spotify's sign-in is said to come next, Continue hands over. */
    private fun accountChoice(look: Look) {
        LookPref.set(app, look)
        var done = 0
        var back = 0
        rule.setContent {
            com.music.spotui.ui.look.ProvideLook {
                AccountChoiceScreen(onBack = { back++ }, onDone = { done++ })
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText(app.getString(R.string.setup_account_title)).assertExists()
        rule.onNodeWithText(app.getString(R.string.setup_account_spotify_next)).assertExists()
        rule.onNodeWithText(app.getString(R.string.setup_continue)).performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(1, done)
        assertEquals(0, back)
    }

    @Test fun `the account choice before the sign-in, Classic`() = accountChoice(Look.CLASSIC)
    @Test fun `the account choice before the sign-in, Immersive`() = accountChoice(Look.IMMERSIVE)
}
