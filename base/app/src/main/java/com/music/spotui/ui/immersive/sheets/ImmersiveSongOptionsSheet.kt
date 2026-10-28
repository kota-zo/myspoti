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
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.runtime.Composable
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
                toast(context.getString(if (gone) R.string.toast_undownload else R.string.download_kept_by_list))
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
