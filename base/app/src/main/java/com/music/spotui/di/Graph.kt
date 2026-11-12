package com.music.spotui.di

import android.app.Application
import app.myspoti.api.account.AccountProviders
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
        // The Spotify session: hidden Web Player + API (operation hashes refresh themselves). The page
        // loads now only with Spotify as the music account; else at its first use (an episode, a
        // Spotify page opened), and never while the data saver holds the network.
        val saver = com.music.spotui.data.DataSaver.active
        MyspotiRuntime.start(application, loadPage = !saver && com.music.spotui.data.preferences.AccountProviderPref.id == AccountProviders.SPOTIFY, held = saver)
        // Metadata (searches, albums, artists) from the providers in the user's order, Spotify among them when signed in.
        com.music.spotui.data.catalog.CatalogRuntime.install()
        // The account provider other than Spotify the user chose (YouTube Music): its hidden page, once signed in.
        com.music.spotui.di.YtmRuntime.startIfChosen()
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
        // Live Radio: its own setting and favorites file (see com.music.spotui.radio).
        com.music.spotui.radio.RadioFeaturePref.init(application)
        com.music.spotui.data.preferences.LocalFilesPref.init(application)
        // A watch linked before reaches this phone over the Wi-Fi too, while the app runs.
        com.music.spotui.wear.WatchLan.startIfLinked(application)
        com.music.spotui.radio.RadioFavoritesStore.init(application)
        // SponsorBlock for podcasts: idle until turned on in the settings.
        com.music.spotui.podcast.SponsorSkip.init()
    }

    val api: Api by lazy { Api(app) }
    val repository: AppRepository by lazy { AppRepository(api) }
    val currentSongState: CurrentSongState by lazy { CurrentSongState() }
}
