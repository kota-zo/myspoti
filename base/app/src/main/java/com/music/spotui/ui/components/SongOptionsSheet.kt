package com.music.spotui.ui.components

import com.music.spotui.ui.theme.tone
import com.music.spotui.ui.theme.AppText
import com.music.spotui.ui.theme.AppMuted

import app.myspoti.engine.R

import androidx.compose.ui.res.stringResource

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material.icons.outlined.ArrowCircleDown
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Radio
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Analytics
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.ThumbDownOffAlt
import kotlinx.coroutines.launch
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.CrossFade
import com.bumptech.glide.integration.compose.GlideImage
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.di.SongPlayer
import com.music.spotui.ui.navigation.albumRoute

private val MenuSheetColor: androidx.compose.ui.graphics.Color get() = tone(0xFF282828)
private val MenuIconColor: androidx.compose.ui.graphics.Color get() = AppMuted
private val MenuGreen: Color get() = com.music.spotui.ui.theme.AppAccent

/**
 * The track menu, one for the whole app (long press on a track, ⋮ in the player),
 * with the official app's wording and order: like, add to a playlist, play next,
 * add to queue, album, artist, share; plus queue and sleep timer for the track
 * that is playing.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalGlideComposeApi::class)
@Composable
fun SongOptionsSheet(
    song: SongsModel,
    navController: NavController,
    context: Context,
    onDismiss: () -> Unit,
    nowPlaying: Boolean = false,
    onOpenQueue: (() -> Unit)? = null,
    // Removing a track only makes sense where it is listed in a playlist: the screen that
    // owns that playlist passes this, and handles the write plus its undo (it has the list).
    onRemoveFromPlaylist: (() -> Unit)? = null,
    // The playlist the track is shown in, so the "Add to another playlist" picker can hide it.
    currentPlaylistId: String? = null,
) {
    val playerViewModel: com.music.spotui.ui.viewmodel.PlayerViewModel =
        androidx.lifecycle.viewmodel.compose.viewModel()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val liked = com.music.spotui.di.rememberTrackSaved(song.spotifyTrackId)
    var showSavedIn by remember { mutableStateOf(false) }
    var showAddTo by remember { mutableStateOf(false) }
    var showSleep by remember { mutableStateOf(false) }
    var showCredits by remember { mutableStateOf(false) }
    var showSpeed by remember { mutableStateOf(false) }
    var showNerd by remember { mutableStateOf(false) }
    var showQuality by remember { mutableStateOf(false) }
    if (showNerd) {
        StatsForNerdsSheet(onDismiss = { showNerd = false; onDismiss() })
        return
    }
    if (showQuality) {
        QualitySheet(onDismiss = { showQuality = false; onDismiss() })
        return
    }
    if (showCredits) {
        CreditsSheet(song = song, onDismiss = { showCredits = false; onDismiss() })
        return
    }
    if (showSpeed) {
        SpeedSheet(onDismiss = { showSpeed = false; onDismiss() })
        return
    }
    if (showSavedIn) {
        SavedInSheet(song = song, context = context, onDismiss = { showSavedIn = false; onDismiss() })
        return
    }
    if (showAddTo) {
        AddToPlaylistSheet(song = song, context = context, onDismiss = { showAddTo = false; onDismiss() }, excludePlaylistId = currentPlaylistId)
        return
    }
    if (showSleep) {
        SleepTimerSheet(onDismiss = { showSleep = false; onDismiss() })
        return
    }
    val hasId = song.spotifyTrackId.isNotBlank()
    // An episode is keyed by its url ("episode:<id>") and has no track id: the Download row
    // (and only it) follows that key, everything else in this menu stays on the track id.
    val episodeKey = com.music.spotui.data.offline.episodeKeyOf(song)
    val downloadKey = episodeKey ?: song.spotifyTrackId
    fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MenuSheetColor,
    ) {
        Column(modifier = Modifier.navigationBarsPadding().verticalScroll(rememberScrollState())) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(16.dp, 0.dp, 16.dp, 16.dp),
            ) {
                GlideImage(
                    transition = CrossFade,
                    modifier = Modifier.size(48.dp).clip(RoundedCornerShape(4.dp)),
                    model = song.coverUri,
                    contentScale = ContentScale.Crop,
                    contentDescription = "",
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(song.title, color = AppText, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(song.singer, color = MenuIconColor, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            HorizontalDivider(color = tone(0xFF3E3E3E), thickness = 0.5.dp)
            Spacer(Modifier.height(8.dp))

            MenuRow(
                icon = if (liked) null else Icons.Outlined.AddCircleOutline,
                label = stringResource(if (liked) R.string.collection_liked_songs_title else R.string.aligned_curation_context_menu_item_add_to_liked_songs),
                enabled = hasId,
                leading = if (liked) ({ LikedBadge() }) else null,
            ) {
                SongPlayer.setTrackSaved(song.spotifyTrackId, !liked)
                toast(context.getString(if (liked) R.string.snackbar_removed_from_liked_songs else R.string.snackbar_added_to_liked_songs))
                onDismiss()
            }
            MenuRow(Icons.AutoMirrored.Filled.PlaylistAdd, stringResource(R.string.context_menu_add_to_playlist), enabled = hasId) { showSavedIn = true }
            // Copy into another playlist: one tap, one target, said out loud by the toast.
            MenuRow(Icons.AutoMirrored.Filled.PlaylistAdd, stringResource(R.string.context_menu_add_to_another_playlist), enabled = hasId) { showAddTo = true }
            // Only where a playlist is on screen (A11): the row disappears at once, the
            // screen tells the user and offers Undo.
            if (onRemoveFromPlaylist != null) {
                MenuRow(Icons.Outlined.Delete, stringResource(R.string.context_menu_remove_from_playlist), enabled = hasId) {
                    onDismiss()
                    onRemoveFromPlaylist()
                }
            }
            if (!nowPlaying) {
                MenuRow(Icons.AutoMirrored.Filled.PlaylistPlay, stringResource(R.string.context_menu_play_next), enabled = hasId) {
                    playerViewModel.playNext(song)
                    com.music.spotui.di.Haptics.confirm()
                    toast(context.getString(R.string.context_menu_play_next))
                    onDismiss()
                }
                MenuRow(Icons.AutoMirrored.Filled.QueueMusic, stringResource(R.string.context_menu_add_to_queue), enabled = hasId) {
                    playerViewModel.addToQueue(song)
                    com.music.spotui.di.Haptics.confirm()
                    toast(context.getString(R.string.snackbar_added_to_queue))
                    onDismiss()
                }
            } else if (onOpenQueue != null) {
                MenuRow(Icons.AutoMirrored.Filled.QueueMusic, stringResource(R.string.context_menu_go_to_queue)) {
                    onDismiss()
                    onOpenQueue()
                }
            }
            val offline = com.music.spotui.data.offline.OfflineStore.statusOf(downloadKey)
            when {
                offline == com.music.spotui.data.offline.OfflineStatus.Done -> MenuRow(
                    null, stringResource(R.string.context_menu_undownload),
                    leading = { DownloadBadge(offline, size = 22.dp) },
                ) {
                    // An episode is kept by its own key (no list holds it): it goes straight.
                    val gone = if (episodeKey != null) {
                        com.music.spotui.data.offline.PodcastDownloader.remove(downloadKey)
                        true
                    } else com.music.spotui.data.offline.OfflineCollections.removeSong(downloadKey)
                    toast(if (gone) context.getString(R.string.toast_undownload) else com.music.spotui.data.offline.OfflineCollections.keptMessage(context, downloadKey))
                    onDismiss()
                }
                offline == com.music.spotui.data.offline.OfflineStatus.Queued ||
                    offline is com.music.spotui.data.offline.OfflineStatus.Downloading -> MenuRow(
                    null, stringResource(R.string.download_cancel),
                    leading = { DownloadBadge(offline, size = 22.dp) },
                ) {
                    if (episodeKey != null) com.music.spotui.data.offline.PodcastDownloader.cancel(downloadKey)
                    else com.music.spotui.data.offline.OfflineDownloader.cancel(downloadKey)
                    onDismiss()
                }
                // Enabled for episodes too: refused ones keep the error badge, and a tap tries
                // again (the audio URL is resolved at that moment, so a retry can succeed).
                else -> MenuRow(
                    Icons.Outlined.ArrowCircleDown, stringResource(R.string.context_menu_download),
                    enabled = downloadKey.isNotBlank(),
                    leading = if (offline != null) ({ DownloadBadge(offline, size = 22.dp) }) else null,
                ) {
                    if (episodeKey != null) {
                        // No quality dialog: it is the publisher's own file, encoded once.
                        com.music.spotui.data.offline.PodcastDownloader.enqueue(song)
                    } else com.music.spotui.ui.components.DownloadAsk.start(context) { q ->
                        com.music.spotui.data.offline.OfflineCollections.downloadSong(song, q)
                        com.music.spotui.di.Haptics.confirm()
                        toast(context.getString(R.string.download_notification_downloading))
                    }
                    onDismiss()
                }
            }
            MenuRow(Icons.Outlined.Radio, stringResource(R.string.context_menu_go_to_song_radio), enabled = hasId) {
                onDismiss()
                playerViewModel.goToSongRadio(song.spotifyTrackId, song.title,
                    navigate = { route -> navController.navigate(route) },
                    onFailed = { toast(context.getString(R.string.song_radio_failed)) })
            }
            MenuRow(Icons.Default.Album, stringResource(R.string.context_menu_browse_album), enabled = song.album.isNotBlank() || song.albumId.isNotBlank()) {
                onDismiss()
                navController.navigate(albumRoute(song.album, song.singer, song.albumId))
            }
            MenuRow(Icons.Outlined.Person, stringResource(R.string.context_menu_browse_artist), enabled = song.singer.isNotBlank()) {
                onDismiss()
                playerViewModel.goToArtist(song.spotifyTrackId, song.singer) { route -> navController.navigate(route) }
            }
            MenuRow(Icons.Outlined.Info, stringResource(R.string.track_credits), enabled = hasId) { showCredits = true }
            // "Not interested": kept on this phone only; the song leaves the queue and lists skip it.
            if (hasId && episodeKey == null) {
                val hidden = com.music.spotui.data.preferences.HiddenSongs.isHidden(song.spotifyTrackId)
                MenuRow(Icons.Outlined.ThumbDownOffAlt, stringResource(if (hidden) R.string.show_song_again else R.string.hide_song)) {
                    if (hidden) com.music.spotui.data.preferences.HiddenSongs.show(context, song.spotifyTrackId) else SongPlayer.hideSong(song)
                    onDismiss()
                }
            }
            // Taste profile: the server says whether the track is excluded (the page's own cache can lag).
            val trackUri = "spotify:track:${song.spotifyTrackId}"
            val excluded by androidx.compose.runtime.produceState<Boolean?>(null, trackUri) {
                value = if (!hasId) null else runCatching { app.myspoti.host.android.MyspotiRuntime.api.extras.isExcludedFromTaste(listOf(trackUri)).single() }.getOrNull()
            }
            MenuRow(Icons.Outlined.Block, stringResource(if (excluded == true) R.string.include_taste else R.string.exclude_taste), enabled = hasId && excluded != null) {
                val exclude = excluded != true
                app.myspoti.host.android.MyspotiRuntime.scope.launch {
                    runCatching { app.myspoti.host.android.MyspotiRuntime.api.extras.setExcludedFromTaste(trackUri, exclude) }
                }
                com.music.spotui.di.Haptics.confirm()
                onDismiss()
            }
            // "Don't play this artist": the track's first artist, kept out of radios and mixes.
            val artistBan by androidx.compose.runtime.produceState<Pair<String, Boolean>?>(null, trackUri) {
                if (!hasId) return@produceState
                val api = app.myspoti.host.android.MyspotiRuntime.api
                value = runCatching {
                    val artist = api.catalog.track(trackUri).artists.first().uri
                    artist to api.extras.isArtistBanned(listOf(artist)).single()
                }.getOrNull()
            }
            MenuRow(Icons.Outlined.Block, stringResource(if (artistBan?.second == true) R.string.artist_unban else R.string.artist_ban),
                enabled = artistBan != null) {
                val (artist, banned) = artistBan ?: return@MenuRow
                app.myspoti.host.android.MyspotiRuntime.scope.launch {
                    runCatching { app.myspoti.host.android.MyspotiRuntime.api.extras.setArtistBanned(artist, !banned) }
                }
                toast(context.getString(if (banned) R.string.artist_unbanned else R.string.artist_banned, song.singer.substringBefore(",").trim()))
                com.music.spotui.di.Haptics.confirm()
                onDismiss()
            }
            // An episode: in or out of Your Episodes.
            if (song.url.startsWith("episode:")) {
                val episodeUri = "spotify:episode:" + song.url.removePrefix("episode:")
                val saved by androidx.compose.runtime.produceState<Boolean?>(null, episodeUri) {
                    value = runCatching { app.myspoti.host.android.MyspotiRuntime.api.library.contains(listOf(episodeUri)).single() }.getOrNull()
                }
                MenuRow(Icons.Outlined.Bookmark, stringResource(if (saved == true) R.string.episode_unsave else R.string.episode_save), enabled = saved != null) {
                    val save = saved != true
                    app.myspoti.host.android.MyspotiRuntime.scope.launch {
                        runCatching { app.myspoti.host.android.MyspotiRuntime.api.podcasts.setEpisodeSaved(episodeUri, save) }
                    }
                    com.music.spotui.di.Haptics.confirm()
                    onDismiss()
                }
            }
            if (nowPlaying && song.url.startsWith("episode:")) {
                MenuRow(Icons.Outlined.Speed, stringResource(R.string.playback_speed)) { showSpeed = true }
            }
            // The app's own playback (extensions, downloads, files): its equalizer, speed and silences.
            if (nowPlaying && com.music.spotui.di.LocalPlayer.active.value) {
                MenuRow(Icons.Outlined.GraphicEq, stringResource(R.string.equalizer_title)) {
                    onDismiss()
                    navController.navigate(com.music.spotui.ui.navigation.Routes.Equalizer.route)
                }
            }
            MenuRow(Icons.Outlined.Share, stringResource(R.string.context_menu_share), enabled = hasId) {
                SpotifyCode.show(ShareTarget("spotify:track:${song.spotifyTrackId}", song.title, song.singer, song.coverUri))
                onDismiss()
            }
            if (nowPlaying) {
                val active = SongPlayer.sleepEndsAt.value != null
                MenuRow(
                    Icons.Default.Bedtime,
                    stringResource(R.string.context_menu_sleep_timer),
                    tint = if (active) MenuGreen else MenuIconColor,
                ) { showSleep = true }
                // The source line of the player is turned off: its sheet is here instead.
                if (!com.music.spotui.data.preferences.PlayerSourceLinePref.on) {
                    MenuRow(Icons.Outlined.GraphicEq, stringResource(R.string.quality_sheet_title)) { showQuality = true }
                }
                MenuRow(Icons.Outlined.Analytics, stringResource(R.string.nerd_title)) { showNerd = true }
            }
            // Entries from extensions (menu.items), after the app's own; each names its extension.
            if (hasId) com.music.spotui.extensions.ExtensionMenuEntries(
                app.myspoti.extensions.Surface.TRACK_MENU, "spotify:track:${song.spotifyTrackId}", song.title, song.singer, onDone = onDismiss,
            )
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun LikedBadge() {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(24.dp).clip(CircleShape).background(MenuGreen),
    ) {
        Icon(Icons.Default.Check, contentDescription = null, tint = Color.Black, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun MenuRow(
    icon: ImageVector?,
    label: String,
    enabled: Boolean = true,
    tint: Color = MenuIconColor,
    leading: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    val alpha = if (enabled) 1f else 0.4f
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .then(if (enabled) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 16.dp),
    ) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            when {
                leading != null -> leading()
                icon != null -> Icon(icon, contentDescription = null, tint = tint.copy(alpha = alpha), modifier = Modifier.size(24.dp))
            }
        }
        Spacer(modifier = Modifier.width(16.dp))
        Text(label, color = AppText.copy(alpha = alpha), fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}


/** The track's credits (performers, writers, producers), as Spotify lists them. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun CreditsSheet(song: SongsModel, onDismiss: () -> Unit) {
    val credits by androidx.compose.runtime.produceState<app.myspoti.api.Credits?>(null, song.spotifyTrackId) {
        value = runCatching { app.myspoti.host.android.MyspotiRuntime.api.extras.credits("spotify:track:${song.spotifyTrackId}") }
            .getOrDefault(app.myspoti.api.Credits(emptyList()))
    }
    LookSheet(onDismissRequest = onDismiss, containerColor = tone(0xFF282828), sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            Text(stringResource(R.string.track_credits), color = AppText, fontSize = 20.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                modifier = Modifier.padding(20.dp, 8.dp))
            Text(song.title, color = AppMuted, fontSize = 14.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 20.dp))
            val roles = credits?.roles
            if (roles == null) androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth().padding(20.dp))
            else roles.forEach { role ->
                Column(Modifier.padding(20.dp, 10.dp)) {
                    Text(role.artists.joinToString(", ") { it.name }, color = AppText, fontSize = 16.sp)
                    Text(role.role, color = AppMuted, fontSize = 13.sp)
                }
            }
            // What extensions add about the song (trackinfo.provide), under Spotify's credits.
            if (song.spotifyTrackId.isNotBlank()) {
                val info = androidx.compose.runtime.remember(song.spotifyTrackId) {
                    com.music.spotui.extensions.Extensions.trackInfo(song.spotifyTrackId, song.title, song.singer, song.album, song.durationMs.toLong(), song.coverUri)
                }
                com.music.spotui.extensions.ExtensionBlocks(
                    app.myspoti.extensions.Hook.TRACK_INFO, app.myspoti.extensions.Surface.TRACK_INFO, info.toJson(),
                    context = com.music.spotui.extensions.placeJson("track", info.uri, info.title, info.artist),
                )
            }
        }
    }
}

/** Podcast playback speed (Spotify refuses speed changes on music). */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun SpeedSheet(onDismiss: () -> Unit) {
    LookSheet(onDismissRequest = onDismiss, containerColor = tone(0xFF282828)) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Text(stringResource(R.string.playback_speed), color = AppText, fontSize = 20.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                modifier = Modifier.padding(20.dp, 8.dp))
            listOf(0.5, 0.8, 1.0, 1.2, 1.5, 1.8, 2.0, 3.0).forEach { speed ->
                Text("${speed}×", color = AppText, fontSize = 16.sp,
                    modifier = Modifier.fillMaxWidth().clickable { SongPlayer.setSpeed(speed); onDismiss() }.padding(20.dp, 12.dp))
            }
        }
    }
}
