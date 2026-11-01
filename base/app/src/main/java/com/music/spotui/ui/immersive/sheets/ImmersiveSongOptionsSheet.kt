package com.music.spotui.ui.immersive.sheets

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.AddCircleOutline
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.ArrowCircleDown
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.SortByAlpha
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Analytics
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.ThumbDownOffAlt
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import app.myspoti.engine.R
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.di.Haptics
import com.music.spotui.di.SongPlayer
import com.music.spotui.ui.components.AddToPlaylistSheet
import com.music.spotui.ui.components.CreditsSheet
import com.music.spotui.ui.components.DownloadBadge
import com.music.spotui.ui.components.SavedInSheet
import com.music.spotui.ui.components.ShareTarget
import com.music.spotui.ui.immersive.sheets.ImmersiveSleepTimerSheet as SleepTimerSheet
import com.music.spotui.ui.components.SpeedSheet
import com.music.spotui.ui.components.SpotifyCode
import com.music.spotui.ui.immersive.Immersive
import com.music.spotui.ui.navigation.albumRoute
import kotlinx.coroutines.launch

/**
 * The track menu of the Immersive look: the same rows, order and actions as the Classic
 * [com.music.spotui.ui.components.SongOptionsSheet] (it calls the same player, offline and
 * extension code), drawn with the Immersive sheet kit and wearing the song's cover colours.
 * The pickers behind a row (playlists, sleep timer, credits, speed) are the shared sheets.
 */
@Composable
fun ImmersiveSongOptionsSheet(
    song: SongsModel,
    navController: NavController,
    context: Context,
    onDismiss: () -> Unit,
    nowPlaying: Boolean = false,
    onOpenQueue: (() -> Unit)? = null,
    onRemoveFromPlaylist: (() -> Unit)? = null,
    currentPlaylistId: String? = null,
    /** The player's lyrics panel is open: the translation row works on the lyrics it shows. */
    lyricsOpen: Boolean = false,
) {
    val playerViewModel: com.music.spotui.ui.viewmodel.PlayerViewModel =
        androidx.lifecycle.viewmodel.compose.viewModel()
    val liked = com.music.spotui.di.rememberTrackSaved(song.spotifyTrackId)
    var showSavedIn by remember { mutableStateOf(false) }
    var showAddTo by remember { mutableStateOf(false) }
    var showSleep by remember { mutableStateOf(false) }
    var showCredits by remember { mutableStateOf(false) }
    var showSpeed by remember { mutableStateOf(false) }
    var showMeaning by remember { mutableStateOf(false) }
    var showSources by remember { mutableStateOf(false) }
    var showNerd by remember { mutableStateOf(false) }
    var showQuality by remember { mutableStateOf(false) }
    var showDevices by remember { mutableStateOf(false) }
    if (showQuality) { com.music.spotui.ui.components.QualitySheet(onDismiss = { showQuality = false; onDismiss() }); return }
    if (showDevices) { com.music.spotui.ui.components.DevicesSheet(onDismiss = { showDevices = false; onDismiss() }); return }
    if (showNerd) { com.music.spotui.ui.components.StatsForNerdsSheet(onDismiss = { showNerd = false; onDismiss() }); return }
    if (showSources) { com.music.spotui.ui.components.LyricsSourceSheet(onDismiss = { showSources = false; onDismiss() }); return }
    if (showCredits) { CreditsSheet(song = song, onDismiss = { showCredits = false; onDismiss() }); return }
    if (showMeaning) {
        ImmersiveMeaningSheet(song.spotifyTrackId, song.title, song.singer, null, coverUrl = song.coverUri) { showMeaning = false; onDismiss() }
        return
    }
    if (showSpeed) { SpeedSheet(onDismiss = { showSpeed = false; onDismiss() }); return }
    if (showSavedIn) { SavedInSheet(song = song, context = context, onDismiss = { showSavedIn = false; onDismiss() }); return }
    if (showAddTo) {
        AddToPlaylistSheet(song = song, context = context, onDismiss = { showAddTo = false; onDismiss() }, excludePlaylistId = currentPlaylistId)
        return
    }
    if (showSleep) { SleepTimerSheet(onDismiss = { showSleep = false; onDismiss() }); return }

    val hasId = song.spotifyTrackId.isNotBlank()
    val episodeKey = com.music.spotui.data.offline.episodeKeyOf(song)
    val downloadKey = episodeKey ?: song.spotifyTrackId
    fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()

    ImmersiveSheet(onDismiss = onDismiss, imageUrl = song.coverUri) {
        ImmersiveSheetHeader(title = song.title, subtitle = song.singer, coverUrl = song.coverUri)
        ImmersiveSheetDivider()

        ImmersiveSheetRow(
            icon = if (liked) null else Icons.Rounded.AddCircleOutline,
            label = stringResource(if (liked) R.string.collection_liked_songs_title else R.string.aligned_curation_context_menu_item_add_to_liked_songs),
            enabled = hasId,
            leading = if (liked) ({ LikedTick() }) else null,
        ) {
            SongPlayer.setTrackSaved(song.spotifyTrackId, !liked)
            toast(context.getString(if (liked) R.string.snackbar_removed_from_liked_songs else R.string.snackbar_added_to_liked_songs))
            onDismiss()
        }
        ImmersiveSheetRow(Icons.AutoMirrored.Rounded.PlaylistAdd, stringResource(R.string.context_menu_add_to_playlist), enabled = hasId) { showSavedIn = true }
        ImmersiveSheetRow(Icons.AutoMirrored.Rounded.PlaylistAdd, stringResource(R.string.context_menu_add_to_another_playlist), enabled = hasId) { showAddTo = true }
        if (onRemoveFromPlaylist != null) {
            ImmersiveSheetRow(Icons.Rounded.DeleteOutline, stringResource(R.string.context_menu_remove_from_playlist), enabled = hasId) {
                onDismiss()
                onRemoveFromPlaylist()
            }
        }
        if (!nowPlaying) {
            ImmersiveSheetRow(Icons.AutoMirrored.Rounded.PlaylistPlay, stringResource(R.string.context_menu_play_next), enabled = hasId) {
                playerViewModel.playNext(song)
                Haptics.confirm()
                toast(context.getString(R.string.context_menu_play_next))
                onDismiss()
            }
            ImmersiveSheetRow(Icons.AutoMirrored.Rounded.QueueMusic, stringResource(R.string.context_menu_add_to_queue), enabled = hasId) {
                playerViewModel.addToQueue(song)
                Haptics.confirm()
                toast(context.getString(R.string.snackbar_added_to_queue))
                onDismiss()
            }
        } else if (onOpenQueue != null) {
            ImmersiveSheetRow(Icons.AutoMirrored.Rounded.QueueMusic, stringResource(R.string.context_menu_go_to_queue)) {
                onDismiss()
                onOpenQueue()
            }
        }
        val offline = com.music.spotui.data.offline.OfflineStore.statusOf(downloadKey)
        when {
            offline == com.music.spotui.data.offline.OfflineStatus.Done -> ImmersiveSheetRow(
                null, stringResource(R.string.context_menu_undownload),
                leading = { DownloadBadge(offline, size = 22.dp) },
            ) {
                val gone = if (episodeKey != null) {
                    com.music.spotui.data.offline.PodcastDownloader.remove(downloadKey)
                    true
                } else com.music.spotui.data.offline.OfflineCollections.removeSong(downloadKey)
                toast(if (gone) context.getString(R.string.toast_undownload) else com.music.spotui.data.offline.OfflineCollections.keptMessage(context, downloadKey))
                onDismiss()
            }
            offline == com.music.spotui.data.offline.OfflineStatus.Queued ||
                offline is com.music.spotui.data.offline.OfflineStatus.Downloading -> ImmersiveSheetRow(
                null, stringResource(R.string.download_cancel),
                leading = { DownloadBadge(offline, size = 22.dp) },
            ) {
                if (episodeKey != null) com.music.spotui.data.offline.PodcastDownloader.cancel(downloadKey)
                else com.music.spotui.data.offline.OfflineDownloader.cancel(downloadKey)
                onDismiss()
            }
            else -> ImmersiveSheetRow(
                Icons.Rounded.ArrowCircleDown, stringResource(R.string.context_menu_download),
                enabled = downloadKey.isNotBlank(),
                leading = if (offline != null) ({ DownloadBadge(offline, size = 22.dp) }) else null,
            ) {
                if (episodeKey != null) {
                    com.music.spotui.data.offline.PodcastDownloader.enqueue(song)
                } else com.music.spotui.ui.components.DownloadAsk.start(context) { q ->
                    com.music.spotui.data.offline.OfflineCollections.downloadSong(song, q)
                    Haptics.confirm()
                    toast(context.getString(R.string.download_notification_downloading))
                }
                onDismiss()
            }
        }
        ImmersiveSheetRow(Icons.Rounded.Radio, stringResource(R.string.context_menu_go_to_song_radio), enabled = hasId) {
            onDismiss()
            playerViewModel.goToSongRadio(song.spotifyTrackId, song.title,
                navigate = { route -> navController.navigate(route) },
                onFailed = { toast(context.getString(R.string.song_radio_failed)) })
        }
        ImmersiveSheetRow(Icons.Rounded.Album, stringResource(R.string.context_menu_browse_album), enabled = song.album.isNotBlank() || song.albumId.isNotBlank()) {
            onDismiss()
            navController.navigate(albumRoute(song.album, song.singer, song.albumId))
        }
        ImmersiveSheetRow(Icons.Rounded.Person, stringResource(R.string.context_menu_browse_artist), enabled = song.singer.isNotBlank()) {
            onDismiss()
            playerViewModel.goToArtist(song.spotifyTrackId, song.singer) { route -> navController.navigate(route) }
        }
        ImmersiveSheetRow(Icons.Rounded.Info, stringResource(R.string.track_credits), enabled = hasId) { showCredits = true }
        if (nowPlaying && hasId && !song.url.startsWith("episode:")) {
            ImmersiveSheetRow(Icons.Rounded.Lightbulb, stringResource(R.string.lyrics_meaning_title)) { showMeaning = true }
        }
        if (nowPlaying && lyricsOpen) {
            // The lyrics panel's translation, the Classic look's: same model, shown under each line.
            val lyricsVm: com.music.spotui.ui.viewmodel.LyricsViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
            val lyricsState = lyricsVm.state.collectAsState().value
            val lyrics = lyricsState as? com.music.spotui.ui.viewmodel.LyricsViewModel.State.Loaded
            val target = com.music.spotui.data.preferences.getLyricTranslateLang(context)
                .ifBlank { java.util.Locale.getDefault().language.ifBlank { "en" } }
            val sameLanguage = lyrics?.sourceLanguage?.substringBefore('-')
                ?.equals(target.substringBefore('-'), ignoreCase = true) == true
            // Picks lines of the lyrics shown to send as text (a long press on a line does the same).
            if (lyrics != null) {
                ImmersiveSheetRow(Icons.Rounded.Share, stringResource(R.string.lyrics_share)) {
                    lyricsVm.startSelecting()
                    onDismiss()
                }
            }
            if (lyrics != null && !sameLanguage) {
                ImmersiveSheetRow(
                    Icons.Rounded.Translate,
                    stringResource(if (lyrics.showTranslation) R.string.lyrics_fullscreen_translate_button_enabled_readout_value else R.string.lyrics_fullscreen_context_menu_main_page_translate_lyrics_action),
                ) {
                    lyricsVm.toggleTranslation(target)
                    onDismiss()
                }
            }
            // The lines in Latin letters under them, when the lyrics carry them; lit while it is on.
            if (lyrics != null && lyrics.lyrics.hasRomanization) {
                val on = com.music.spotui.data.preferences.LyricsRomanizationPref.on
                ImmersiveSheetRow(
                    Icons.Rounded.SortByAlpha, stringResource(R.string.lyrics_romanization),
                    tint = if (on) LocalImmersiveSheetTint.current.accent else null,
                ) {
                    com.music.spotui.data.preferences.LyricsRomanizationPref.set(context, !on)
                    onDismiss()
                }
            }
            // Ask another provider for the lyrics (also when none were found).
            if (lyricsState !is com.music.spotui.ui.viewmodel.LyricsViewModel.State.Loading) {
                ImmersiveSheetRow(Icons.Rounded.SwapHoriz, stringResource(R.string.lyrics_other_source)) { showSources = true }
            }
        }

        // "Not interested": kept on this phone only; the song leaves the queue and lists skip it.
        if (hasId && episodeKey == null) {
            val hidden = com.music.spotui.data.preferences.HiddenSongs.isHidden(song.spotifyTrackId)
            ImmersiveSheetRow(Icons.Rounded.ThumbDownOffAlt, stringResource(if (hidden) R.string.show_song_again else R.string.hide_song)) {
                if (hidden) com.music.spotui.data.preferences.HiddenSongs.show(context, song.spotifyTrackId) else SongPlayer.hideSong(song)
                onDismiss()
            }
        }

        val trackUri = "spotify:track:${song.spotifyTrackId}"
        val excluded by produceState<Boolean?>(null, trackUri) {
            value = if (!hasId) null else runCatching { app.myspoti.host.android.MyspotiRuntime.api.extras.isExcludedFromTaste(listOf(trackUri)).single() }.getOrNull()
        }
        ImmersiveSheetRow(Icons.Rounded.Block, stringResource(if (excluded == true) R.string.include_taste else R.string.exclude_taste), enabled = hasId && excluded != null) {
            val exclude = excluded != true
            app.myspoti.host.android.MyspotiRuntime.scope.launch {
                runCatching { app.myspoti.host.android.MyspotiRuntime.api.extras.setExcludedFromTaste(trackUri, exclude) }
            }
            Haptics.confirm()
            onDismiss()
        }
        val artistBan by produceState<Pair<String, Boolean>?>(null, trackUri) {
            if (!hasId) return@produceState
            val api = app.myspoti.host.android.MyspotiRuntime.api
            value = runCatching {
                val artist = api.catalog.track(trackUri).artists.first().uri
                artist to api.extras.isArtistBanned(listOf(artist)).single()
            }.getOrNull()
        }
        ImmersiveSheetRow(Icons.Rounded.Block, stringResource(if (artistBan?.second == true) R.string.artist_unban else R.string.artist_ban), enabled = artistBan != null) {
            val (artist, banned) = artistBan ?: return@ImmersiveSheetRow
            app.myspoti.host.android.MyspotiRuntime.scope.launch {
                runCatching { app.myspoti.host.android.MyspotiRuntime.api.extras.setArtistBanned(artist, !banned) }
            }
            toast(context.getString(if (banned) R.string.artist_unbanned else R.string.artist_banned, song.singer.substringBefore(",").trim()))
            Haptics.confirm()
            onDismiss()
        }
        if (song.url.startsWith("episode:")) {
            val episodeUri = "spotify:episode:" + song.url.removePrefix("episode:")
            val saved by produceState<Boolean?>(null, episodeUri) {
                value = runCatching { app.myspoti.host.android.MyspotiRuntime.api.library.contains(listOf(episodeUri)).single() }.getOrNull()
            }
            ImmersiveSheetRow(Icons.Rounded.Bookmark, stringResource(if (saved == true) R.string.episode_unsave else R.string.episode_save), enabled = saved != null) {
                val save = saved != true
                app.myspoti.host.android.MyspotiRuntime.scope.launch {
                    runCatching { app.myspoti.host.android.MyspotiRuntime.api.podcasts.setEpisodeSaved(episodeUri, save) }
                }
                Haptics.confirm()
                onDismiss()
            }
        }
        if (nowPlaying && song.url.startsWith("episode:")) {
            ImmersiveSheetRow(Icons.Rounded.Speed, stringResource(R.string.playback_speed)) { showSpeed = true }
        }
        // The app's own playback (extensions, downloads, files): its equalizer, speed and silences.
        if (nowPlaying && com.music.spotui.di.LocalPlayer.active.value) {
            ImmersiveSheetRow(Icons.Rounded.GraphicEq, stringResource(R.string.equalizer_title)) {
                onDismiss()
                navController.navigate(com.music.spotui.ui.navigation.Routes.Equalizer.route)
            }
        }
        ImmersiveSheetRow(Icons.Rounded.Share, stringResource(R.string.context_menu_share), enabled = hasId) {
            SpotifyCode.show(ShareTarget("spotify:track:${song.spotifyTrackId}", song.title, song.singer, song.coverUri))
            onDismiss()
        }
        if (nowPlaying) {
            ImmersiveSheetRow(
                Icons.Rounded.Bedtime,
                stringResource(R.string.context_menu_sleep_timer),
                tint = if (SongPlayer.sleepEndsAt.value != null) LocalImmersiveSheetTint.current.accent else null,
            ) { showSleep = true }
            // The player's source line and capsule are turned off: their sheets are here instead.
            if (!com.music.spotui.data.preferences.PlayerSourceLinePref.on) {
                ImmersiveSheetRow(Icons.Rounded.GraphicEq, stringResource(R.string.quality_sheet_title)) { showQuality = true }
            }
            if (!com.music.spotui.data.preferences.PlayerCapsulePref.on) {
                ImmersiveSheetRow(Icons.Rounded.Devices, stringResource(R.string.listen_on)) { showDevices = true }
            }
            ImmersiveSheetRow(Icons.Rounded.Analytics, stringResource(R.string.nerd_title)) { showNerd = true }
        }
        // Entries from extensions (menu.items), after the app's own; each names its extension.
        if (hasId) com.music.spotui.extensions.ExtensionMenuEntries(
            app.myspoti.extensions.Surface.TRACK_MENU, trackUri, song.title, song.singer, onDone = onDismiss,
        )
        Spacer(Modifier.height(12.dp))
    }
}

/** The filled tick of a liked song, in the sheet's accent. */
@Composable
private fun LikedTick() {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(24.dp).background(LocalImmersiveSheetTint.current.accent, CircleShape),
    ) {
        androidx.compose.material3.Icon(Icons.Rounded.Check, contentDescription = null, tint = Color.Black, modifier = Modifier.size(16.dp))
    }
}
