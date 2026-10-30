package com.music.spotui.di

import android.app.Application
import app.myspoti.host.android.MyspotiRuntime
import com.music.spotui.data.api.Api
import com.music.spotui.ui.repository.AppRepository

/** Manual replacement for Spotui's Hilt graph: app-wide singletons. */
object Graph {
    lateinit var app: Application
        private set

    fun init(application: Application) {
        app = application
        // The data saver first: on a metered network with it on, nothing may go out, not even the page.
        com.music.spotui.data.DataSaver.init(application)
        // The Spotify session: hidden Web Player + API (operation hashes refresh themselves).
        MyspotiRuntime.start(application, loadPage = !com.music.spotui.data.DataSaver.active)
        // The streaming quality the user chose, set on the player at every page load.
        com.music.spotui.data.preferences.applyStreamingQuality(application)
        // The crossfade length (0 = off), in the player from the first song on.
        com.music.spotui.data.preferences.applyCrossfade(com.music.spotui.data.preferences.getCrossfadeSeconds(application))
        com.music.spotui.data.local.ListenHistory.init(application)
        // Downloaded songs (private storage), and the downloads left unfinished last time.
        // Supporter thank-yous (accent colour) before any screen draws.
        com.music.spotui.data.api.SupportReminder.init(application)
        com.music.spotui.data.offline.OfflineStore.init(application)
        com.music.spotui.data.offline.OfflineCollections.init(application)
        SongPlayer.startLibraryOutbox(application)
        com.music.spotui.data.offline.OfflineCollections.refreshAll(application)
        com.music.spotui.data.local.LocalPlaylists.init(application)
        com.music.spotui.data.local.LibraryOrder.init(application)
        runCatching { com.music.spotui.data.DataSaverGlide.install(application) }
        com.music.spotui.data.offline.OfflineDownloader.resumePending()
        // Episodes have their own queue and their own file: resumed the same way.
        com.music.spotui.data.offline.PodcastDownloader.resumePending()
        // Installed extensions: each enabled one loads in its own sandbox, off the main thread.
        com.music.spotui.extensions.Extensions.init(application)
        // Live Radio (radio/ module): its own setting and its own favorites file. A station
        // plays through LocalPlayer (a one-item "queue"), so it already shares LocalPlayer's
        // own arbitration with Spotify — no separate watch is needed here any more.
        com.music.spotui.radio.RadioFeaturePref.init(application)
        com.music.spotui.radio.RadioFavoritesStore.init(application)
    }

    val api: Api by lazy { Api(app) }
    val repository: AppRepository by lazy { AppRepository(api) }
    val currentSongState: CurrentSongState by lazy { CurrentSongState() }
}
