package com.music.spotui.di

import app.myspoti.host.android.Logbook
import app.myspoti.host.android.MyspotiRuntime
import app.myspoti.host.android.PlaybackService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow

/** Session-wide actions the screens trigger. */
object SessionActions {
    private val _signedOut = MutableStateFlow(false)
    /** True from a logout until the next sign-in: the activity shows the login page, then starts over. */
    val signedOut: StateFlow<Boolean> = _signedOut

    /**
     * Signs out and drops everything tied to the account, so the next account on
     * this phone starts clean: prefs and in-memory caches, the saved screens,
     * the listen history (the Recap), and the Web Player's cookies, Web storage
     * and cache (in the host). Downloads are put aside under the account (OfflineAccounts), not deleted.
     */
    fun logout(context: android.content.Context) {
        val app = context.applicationContext
        // This account's downloads go aside: the next account on the phone sees none of them.
        val account = com.music.spotui.data.api.ProfileCache.id
        MyspotiRuntime.scope.launch {
            runCatching { com.music.spotui.data.offline.OfflineAccounts.park(app, account) }
                .onFailure { Logbook.log("offline: sign-out park failed: ${it.message}") }
        }
        app.getSharedPreferences("myspoti_prefs", android.content.Context.MODE_PRIVATE)
            .edit().putBoolean("LoggedIn", false).remove("CurrentAccountName").apply()
        // Nothing of the account keeps playing or showing: local songs, the clip, the notification.
        LocalPlayer.stop()
        com.music.spotui.video.MusicVideoController.clearSession()
        PlaybackService.end()
        runCatching { MyspotiRuntime.scope.launch { runCatching { MyspotiRuntime.api.player.pause() } } }
        com.music.spotui.ui.screens.ContextViewModel.clearCache()
        com.music.spotui.data.api.Api.HomeCache.clear()
        com.music.spotui.data.api.Api.LikedCache.tracks = null
        com.music.spotui.data.api.TopStats.clear()
        com.music.spotui.ui.screens.clearMessagesCache()
        com.music.spotui.data.api.ProfileCache.clearSession()
        // Likes kept for later stay this account's, sent when it signs in again; the next account gets none.
        com.music.spotui.data.LibraryOutbox.signedOut()
        SongPlayer.clearSession()
        listOf("PlaybackState", "LibraryCache", "RecentItems", "LikedSongs",
            "LikedSongsFromSpotify", "LikedAlbums", "FollowedArtists").forEach { name ->
            app.getSharedPreferences(name, android.content.Context.MODE_PRIVATE).edit().clear().apply()
        }
        com.music.spotui.data.local.ListenHistory.clear()
        AutoBrowser.clearSession()
        _signedOut.value = true
        MyspotiRuntime.host.logout()
    }

    /**
     * The Spotify session was lost without a sign-out (an anonymous token, Spotify's sign-in page
     * back): whose account comes next is not known, maybe another one. What follows the account
     * forgets it as a sign-out does (the profile, so ProfileCache.id is read again as soon as the
     * next session is ready; the likes kept for later stay their account's, sent to it only), and
     * nothing kept by account is removed: the downloads stay in place for the account read next
     * (OfflineAccounts.claim puts another account's aside then). Nothing else of a sign-out is done.
     */
    fun sessionLost() {
        Logbook.log("session lost without a sign-out: the account is read again at the next session")
        com.music.spotui.data.api.ProfileCache.clearSession()
        com.music.spotui.data.LibraryOutbox.signedOut()
    }

    /** The new account is signed in. */
    fun signedIn() { _signedOut.value = false }
}
