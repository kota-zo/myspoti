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

    private val _switching = MutableStateFlow(false)
    /**
     * True from an account switch until that account's session is confirmed: the activity waits for
     * it on its own loading screen (the sign-in page only if that account's session is gone), then starts over.
     */
    val switching: StateFlow<Boolean> = _switching

    /**
     * Signs out and drops everything tied to the account, so the next account on
     * this phone starts clean: prefs and in-memory caches, the saved screens,
     * the listen history (the Recap), and the Web Player's cookies, Web storage
     * and cache (in the host). Downloads are put aside under the account (OfflineAccounts), not deleted.
     * With other accounts kept on the phone, this one is removed and the one used last takes over.
     */
    fun logout(context: android.content.Context) {
        val host = MyspotiRuntime.host
        val list = host.accounts.list.value
        if (host.multiProfile && list.others.isNotEmpty()) {
            removeAccount(context, list.active)
            return
        }
        leaveAccount(context.applicationContext, forget = true)
        _signedOut.value = true
        host.logout()
    }

    /**
     * Everything that follows the account leaves with it: nothing of it keeps playing or showing,
     * its caches are dropped, its downloads put aside under it (OfflineAccounts). [forget]: a sign-out
     * or a removal, its listen history goes; otherwise (a switch) it is put aside under it too.
     * Extensions are the app's and keep running; their settings and the quality are the account's (ProfilePrefs).
     */
    private fun leaveAccount(app: android.content.Context, forget: Boolean) {
        // This account's downloads go aside: the next account on the phone sees none of them.
        val account = com.music.spotui.data.api.ProfileCache.id
        val key = account?.let { com.music.spotui.data.offline.OfflineAccounts.keyOf(it) } ?: MyspotiRuntime.host.accounts.list.value.current?.key
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
        com.music.spotui.data.api.ProfileCache.clearSession()
        // Likes kept for later stay this account's, sent when it signs in again; the next account gets none.
        com.music.spotui.data.LibraryOutbox.signedOut()
        SongPlayer.clearSession()
        // The desktop token a Sonos login kept, and the way that logged one in.
        com.music.spotui.connect.ConnectCode.clear(app)
        com.music.spotui.data.api.EditablePlaylists.clear()
        // The last track, likes, albums and followed artists of older builds are never read
        // again: cleared too, so no ids of this account outlive it.
        listOf("LibraryCache", "RecentItems", "PlaybackState", "LikedSongs",
            "LikedSongsFromSpotify", "LikedAlbums", "FollowedArtists").forEach { name ->
            app.getSharedPreferences(name, android.content.Context.MODE_PRIVATE).edit().clear().apply()
        }
        if (forget) com.music.spotui.data.local.ListenHistory.clear()
        else com.music.spotui.data.local.ListenHistory.park(key)
        AutoBrowser.clearSession()
        // The API's token, username and Jam, and the plan: the next account's are read.
        MyspotiRuntime.accountChanged()
    }

    /**
     * Another account kept on the phone takes over ([profile], a WebView profile of its own): playback
     * stops, what follows this account leaves ([leaveAccount]), and the page is rebuilt on the other
     * profile, signed in as it was left. Without WebView profiles, one account at a time: signs out.
     */
    fun switchAccount(context: android.content.Context, profile: String) {
        val host = MyspotiRuntime.host
        if (!host.multiProfile) { logout(context); return }
        val list = host.accounts.list.value
        if (profile == list.active || list.accounts.none { it.profile == profile }) return
        Logbook.log("account: switching to another account kept on the phone")
        leaveAccount(context.applicationContext, forget = false)
        _switching.value = true
        host.switchProfile(profile)
    }

    /** "Add account": this one stays kept, and Spotify's sign-in page opens in a new profile. Without profiles: signs out. */
    fun addAccount(context: android.content.Context) {
        val host = MyspotiRuntime.host
        if (!host.multiProfile) { logout(context); return }
        Logbook.log("account: adding an account")
        leaveAccount(context.applicationContext, forget = false)
        _signedOut.value = true
        host.addProfile()
    }

    /**
     * Back on the sign-in page of an added account (or of one whose session was gone): the account
     * used last takes over again. False when there is none to go back to.
     */
    fun backToPreviousAccount(context: android.content.Context): Boolean {
        val host = MyspotiRuntime.host
        if (!host.multiProfile) return false
        val list = host.accounts.list.value
        val previous = list.others.firstOrNull() ?: return false
        val left = list.current
        switchAccount(context, previous.profile)
        // An added profile nobody signed in to is not kept.
        if (left != null && !left.known) host.removeProfile(left.profile)
        return true
    }

    /**
     * [profile] is removed from the phone: its sign-in and Web data are deleted (its profile), its listen
     * history too; its downloads stay put aside (OfflineAccounts), back if it is added again. The active
     * account is first left for the one used last; the only account left is signed out instead.
     */
    fun removeAccount(context: android.content.Context, profile: String) {
        val host = MyspotiRuntime.host
        val list = host.accounts.list.value
        val gone = list.accounts.firstOrNull { it.profile == profile } ?: return
        if (profile == list.active) {
            val next = list.others.firstOrNull()
            if (!host.multiProfile || next == null) {
                leaveAccount(context.applicationContext, forget = true)
                _signedOut.value = true
                host.logout()
                return
            }
            Logbook.log("account: removing the active account, the one used last takes over")
            leaveAccount(context.applicationContext, forget = true)
            _switching.value = true
            host.switchProfile(next.profile)
        } else {
            Logbook.log("account: removing an account kept on the phone")
            gone.key?.let { com.music.spotui.data.local.ListenHistory.forget(it) }
        }
        host.removeProfile(profile)
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
    fun signedIn() { _signedOut.value = false; _switching.value = false }
}
