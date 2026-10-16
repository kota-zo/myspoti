package com.music.spotui.ui.components

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
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.Speed
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
import com.bumptech.glide.integration.compose.GlideImage
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.di.SongPlayer
import com.music.spotui.ui.navigation.albumRoute

private val MenuSheetColor = Color(0xFF282828)
private val MenuIconColor = Color(0xFFB3B3B3)
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
) {
    val playerViewModel: com.music.spotui.ui.viewmodel.PlayerViewModel =
        androidx.lifecycle.viewmodel.compose.viewModel()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val liked = com.music.spotui.di.rememberTrackSaved(song.spotifyTrackId)
    var showSavedIn by remember { mutableStateOf(false) }
    var showSleep by remember { mutableStateOf(false) }
    var showCredits by remember { mutableStateOf(false) }
    var showSpeed by remember { mutableStateOf(false) }
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
    if (showSleep) {
        SleepTimerSheet(onDismiss = { showSleep = false; onDismiss() })
        return
    }
    val hasId = song.spotifyTrackId.isNotBlank()
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
                    modifier = Modifier.size(48.dp).clip(RoundedCornerShape(4.dp)),
                    model = song.coverUri,
                    contentScale = ContentScale.Crop,
                    contentDescription = "",
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(song.title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(song.singer, color = MenuIconColor, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            HorizontalDivider(color = Color(0xFF3E3E3E), thickness = 0.5.dp)
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
            val offline = com.music.spotui.data.offline.OfflineStore.statusOf(song.spotifyTrackId)
            when (offline) {
                com.music.spotui.data.offline.OfflineStatus.Done -> MenuRow(
                    null, stringResource(R.string.context_menu_undownload),
                    leading = { DownloadBadge(offline, size = 22.dp) },
                ) {
                    toast(context.getString(if (com.music.spotui.data.offline.OfflineCollections.removeSong(song.spotifyTrackId)) R.string.toast_undownload else R.string.download_kept_by_list))
                    onDismiss()
                }
                com.music.spotui.data.offline.OfflineStatus.Queued, is com.music.spotui.data.offline.OfflineStatus.Downloading -> MenuRow(
                    null, stringResource(R.string.download_cancel),
                    leading = { DownloadBadge(offline, size = 22.dp) },
                ) {
                    com.music.spotui.data.offline.OfflineDownloader.cancel(song.spotifyTrackId)
                    onDismiss()
                }
                else -> MenuRow(Icons.Outlined.ArrowCircleDown, stringResource(R.string.context_menu_download), enabled = hasId) {
                    com.music.spotui.ui.components.DownloadAsk.start(context) { q ->
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
            // Hidden songs and artists (Spicetify's Trash Bin): skipped whenever they come up.
            val trash = com.music.spotui.di.TrashBin
            val songHidden = trash.isSongHidden(trackUri)
            MenuRow(if (songHidden) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff,
                stringResource(if (songHidden) R.string.trash_unhide_song else R.string.trash_hide_song), enabled = hasId) {
                trash.setSong(trackUri, !songHidden)
                if (!songHidden) toast(context.getString(R.string.trash_hidden_toast))
                com.music.spotui.di.Haptics.confirm()
                onDismiss()
            }
            val artist by androidx.compose.runtime.produceState<app.myspoti.api.ArtistRef?>(null, trackUri) {
                value = if (!hasId) null else runCatching {
                    app.myspoti.host.android.MyspotiRuntime.api.catalog.track(trackUri).artists.firstOrNull()
                }.getOrNull()
            }
            artist?.let { a ->
                val artistHidden = trash.isArtistHidden(a.uri)
                MenuRow(if (artistHidden) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff,
                    stringResource(if (artistHidden) R.string.trash_unhide_artist else R.string.trash_hide_artist, a.name)) {
                    trash.setArtist(a.uri, !artistHidden)
                    if (!artistHidden) toast(context.getString(R.string.trash_hidden_toast))
                    com.music.spotui.di.Haptics.confirm()
                    onDismiss()
                }
            }
            if (nowPlaying && song.url.startsWith("episode:")) {
                MenuRow(Icons.Outlined.Speed, stringResource(R.string.playback_speed)) { showSpeed = true }
            }
            MenuRow(Icons.Outlined.Share, stringResource(R.string.context_menu_share), enabled = hasId) {
                val send = Intent(Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, "https://open.spotify.com/track/${song.spotifyTrackId}")
                context.startActivity(Intent.createChooser(send, context.getString(R.string.action_share_label)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                onDismiss()
            }
            if (nowPlaying) {
                val active = SongPlayer.sleepEndsAt.value != null
                MenuRow(
                    Icons.Default.Bedtime,
                    stringResource(R.string.context_menu_sleep_timer),
                    tint = if (active) MenuGreen else MenuIconColor,
                ) { showSleep = true }
            }
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
        Text(label, color = Color.White.copy(alpha = alpha), fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}


/** The track's credits (performers, writers, producers), as Spotify lists them. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun CreditsSheet(song: SongsModel, onDismiss: () -> Unit) {
    val credits by androidx.compose.runtime.produceState<app.myspoti.api.Credits?>(null, song.spotifyTrackId) {
        value = runCatching { app.myspoti.host.android.MyspotiRuntime.api.extras.credits("spotify:track:${song.spotifyTrackId}") }
            .getOrDefault(app.myspoti.api.Credits(emptyList()))
    }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Color(0xFF282828), sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Text(stringResource(R.string.track_credits), color = Color.White, fontSize = 20.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                modifier = Modifier.padding(20.dp, 8.dp))
            Text(song.title, color = Color(0xFFB3B3B3), fontSize = 14.sp, modifier = Modifier.padding(horizontal = 20.dp))
            val roles = credits?.roles
            if (roles == null) androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth().padding(20.dp))
            else roles.forEach { role ->
                Column(Modifier.padding(20.dp, 10.dp)) {
                    Text(role.artists.joinToString(", ") { it.name }, color = Color.White, fontSize = 16.sp)
                    Text(role.role, color = Color(0xFFB3B3B3), fontSize = 13.sp)
                }
            }
        }
    }
}

/** Podcast playback speed (Spotify refuses speed changes on music). */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun SpeedSheet(onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Color(0xFF282828)) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Text(stringResource(R.string.playback_speed), color = Color.White, fontSize = 20.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                modifier = Modifier.padding(20.dp, 8.dp))
            listOf(0.5, 0.8, 1.0, 1.2, 1.5, 1.8, 2.0, 3.0).forEach { speed ->
                Text("${speed}×", color = Color.White, fontSize = 16.sp,
                    modifier = Modifier.fillMaxWidth().clickable { SongPlayer.setSpeed(speed); onDismiss() }.padding(20.dp, 12.dp))
            }
        }
    }
}
