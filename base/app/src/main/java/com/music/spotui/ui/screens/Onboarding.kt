/* SPDX-License-Identifier: GPL-3.0-only */
package com.music.spotui.ui.screens

import app.myspoti.api.account.AccountProviders

/** What the main activity puts on screen. */
enum class Entry {
    /** The one-time steps after the account is in place ([SetupScreen]). */
    SETUP,
    /** The app's pages. */
    APP,
    /** Back from Spotify's sign-in page, waiting for the session. */
    CONNECTING,
    /** The music account, chosen before any sign-in ([AccountChoiceScreen]). */
    ACCOUNT,
    /** The first screen of an app with no account in place. */
    WELCOME,
}

/** What follows the music account chosen before any sign-in. */
enum class AfterChoice {
    /** Spotify's own sign-in page. */
    SPOTIFY_SIGN_IN,
    /** YouTube Music's own sign-in page, then the app. */
    YTM_SIGN_IN,
    /** The app, no sign-in asked. */
    APP,
}

/**
 * The first launch's order: the music account is chosen first (Spotify, YouTube Music or none), and
 * Spotify's sign-in is asked only when Spotify is the one chosen. Before, the welcome screen went
 * straight to Spotify's sign-in page and the account was asked after it, so whoever wanted YouTube
 * Music or no account at all had to sign in to Spotify first. Someone already signed in (an update)
 * is asked nothing again. A TV keeps its Spotify sign-in: its short app is Spotify's.
 */
object Onboarding {
    /** The app opens with no Spotify session: another music account (or none) was chosen. */
    fun opensWithoutSpotify(accountChosen: Boolean, provider: String, tv: Boolean): Boolean =
        !tv && accountChosen && provider != AccountProviders.SPOTIFY

    /** The app's pages (or the steps before them) are open to the user. */
    fun open(spotifySignedIn: Boolean, accountChosen: Boolean, provider: String, tv: Boolean): Boolean =
        spotifySignedIn || opensWithoutSpotify(accountChosen, provider, tv)

    fun entry(
        spotifySignedIn: Boolean, accountChosen: Boolean, provider: String, setupNeeded: Boolean,
        connecting: Boolean, choosingAccount: Boolean, tv: Boolean,
    ): Entry {
        val open = open(spotifySignedIn, accountChosen, provider, tv)
        return when {
            open && setupNeeded -> Entry.SETUP
            open -> Entry.APP
            connecting -> Entry.CONNECTING
            choosingAccount && !tv -> Entry.ACCOUNT
            else -> Entry.WELCOME
        }
    }

    /** The welcome screen's button leads to the account choice (a TV: to Spotify's sign-in, as before). */
    fun welcomeChoosesAccount(tv: Boolean): Boolean = !tv

    /** Continue on the account choice: Spotify's sign-in only for Spotify, YouTube Music's own for it while signed out. */
    fun afterChoice(provider: String, ytmSignedIn: Boolean): AfterChoice = when (provider) {
        AccountProviders.SPOTIFY -> AfterChoice.SPOTIFY_SIGN_IN
        AccountProviders.YTMUSIC -> if (ytmSignedIn) AfterChoice.APP else AfterChoice.YTM_SIGN_IN
        else -> AfterChoice.APP
    }

    /**
     * The "new look" popup, for an existing user who never saw it: never over the onboarding, nor
     * after it (its look step covers it, [com.music.spotui.data.preferences.LookPref.setChosen]).
     */
    fun lookOfferShown(entry: Entry, showLook: Boolean, showingLogin: Boolean, inPip: Boolean, offered: Boolean, tv: Boolean): Boolean =
        entry == Entry.APP && !showLook && !showingLogin && !inPip && !offered && !tv
}
