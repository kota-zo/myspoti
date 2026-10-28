package com.music.spotui.ui.immersive.pages

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import app.myspoti.api.FriendActivity
import app.myspoti.engine.R
import app.myspoti.host.android.MyspotiRuntime
import com.music.spotui.data.local.LocalMusic
import com.music.spotui.data.local.LocalTrack
import com.music.spotui.di.LocalPlayer
import com.music.spotui.di.SongPlayer
import com.music.spotui.ui.components.EpisodeDownloadButton
import com.music.spotui.ui.components.EpisodeOptionsSheet
import com.music.spotui.ui.components.episodeClock
import com.music.spotui.ui.components.episodeDateLabel
import com.music.spotui.ui.components.episodeMinutes
import com.music.spotui.ui.components.shareEpisode
import com.music.spotui.ui.components.spotifyUri
import com.music.spotui.ui.immersive.Immersive
import com.music.spotui.ui.immersive.components.ImmersiveButton
import com.music.spotui.ui.immersive.components.ImmersiveCircleButton
import com.music.spotui.ui.immersive.components.ImmersiveCover
import com.music.spotui.ui.immersive.components.ImmersiveListRow
import com.music.spotui.ui.immersive.components.ImmersiveMessageState
import com.music.spotui.ui.immersive.components.ImmersivePlayShuffleButtons
import com.music.spotui.ui.immersive.components.ImmersiveSearchField
import com.music.spotui.ui.immersive.components.ImmersiveSongRow
import com.music.spotui.ui.immersive.components.ImmersiveSongRowSkeleton
import com.music.spotui.ui.immersive.components.SectionHeader
import com.music.spotui.ui.immersive.components.immersiveClickable
import com.music.spotui.ui.immersive.components.immersiveSongListSkeleton
import com.music.spotui.ui.immersive.motion.immersiveItemPlacement
import com.music.spotui.ui.immersive.shell.ImmersiveBarAction
import com.music.spotui.ui.navigation.profileRoute
import com.music.spotui.ui.navigation.showRoute
import com.music.spotui.ui.screens.AddLocalToPlaylistSheet
import com.music.spotui.ui.screens.LocalFoldersSheet
import com.music.spotui.ui.viewmodel.EpisodeViewModel
import kotlinx.coroutines.launch

// ---------------------------------------------------------------- Friends

/**
 * Friend activity of the Immersive look: each followed person sharing their listening, with the
 * latest track and where it was played from. Tap the person for their profile, the row to play.
 */
@Composable
fun ImmersiveFriendsScreen(navController: NavController) {
    var attempt by remember { mutableIntStateOf(0) }
    var state by remember { mutableStateOf<Result<List<FriendActivity>>?>(null) }
    LaunchedEffect(attempt) { state = runCatching { MyspotiRuntime.api.users.friendActivity() } }
    val now = remember(state) { System.currentTimeMillis() }
    ImmersivePage(
        title = stringResource(R.string.friends_title),
        onBack = { navController.popBackStack() },
        actions = {
            ImmersiveBarAction(Icons.Rounded.Refresh, stringResource(R.string.friends_refresh), onClick = { state = null; attempt++ })
        },
    ) {
        val s = state
        val friends = s?.getOrNull()
        when {
            s == null -> immersiveSongListSkeleton(count = 6, circular = true)
            friends == null -> item(key = "failed") {
                ImmersiveMessageState(stringResource(R.string.friends_failed), actionLabel = stringResource(R.string.retry_button),
                    onAction = { state = null; attempt++ })
            }
            friends.isEmpty() -> item(key = "empty") { ImmersiveMessageState(stringResource(R.string.friends_empty)) }
            else -> items(friends, key = { it.user.uri }) { f ->
                Column(immersiveItemPlacement()) { ImmersiveFriendRow(f, now, navController) }
            }
        }
    }
}

@Composable
private fun ImmersiveFriendRow(f: FriendActivity, now: Long, navController: NavController) {
    val track = f.track
    val openProfile = { navController.navigate(profileRoute(f.user.uri.substringAfterLast(':'), f.user.name)) }
    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier.fillMaxWidth()
            .immersiveClickable(onClick = { if (track != null) SongPlayer.playSong(track.uri, contextUri = f.contextUri) else openProfile() }, pressedScale = 0.99f)
            .padding(horizontal = Immersive.Gutter, vertical = 10.dp),
    ) {
        Box(Modifier.clip(CircleShape).clickable(onClick = openProfile)) {
            com.music.spotui.ui.components.ProfileAvatar(url = f.user.imageUrl, name = f.user.name, size = 48.dp)
        }
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(f.user.name, style = Immersive.BodyStrong, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Spacer(Modifier.weight(1f))
                if (f.isNowPlaying(now)) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(Immersive.Accent))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.friends_now), style = Immersive.Caption.copy(color = Immersive.Accent), maxLines = 1)
                } else if (f.timestampMs > 0) {
                    Text(
                        android.text.format.DateUtils.getRelativeTimeSpanString(f.timestampMs, now,
                            android.text.format.DateUtils.MINUTE_IN_MILLIS, android.text.format.DateUtils.FORMAT_ABBREV_RELATIVE).toString(),
                        style = Immersive.Caption, maxLines = 1,
                    )
                }
            }
            if (track != null) {
                Text(listOf(track.name, track.artists.joinToString(", ") { it.name }).filter { it.isNotBlank() }.joinToString(" • "),
                    style = Immersive.Subhead.copy(color = Immersive.Text), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                val where = f.contextName ?: track.album?.name
                if (!where.isNullOrBlank()) Text(where, style = Immersive.Caption, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
            }
        }
        f.trackImageUrl?.let { cover ->
            ImmersiveCover(cover.replaceFirst("http://", "https://"), Modifier.padding(start = 10.dp).size(44.dp), Immersive.CoverSmall)
        }
    }
}

// ---------------------------------------------------------------- Local files

/** "Local Files" of the Immersive look: the device's own audio, played by [LocalPlayer]. */
@Composable
fun ImmersiveLocalFilesScreen(navController: NavController) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(LocalMusic.hasPermission(context)) }
    var askedOnce by remember { mutableStateOf(false) }
    var tracks by remember { mutableStateOf<List<LocalTrack>?>(null) }
    var find by remember { mutableStateOf("") }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> granted = ok; askedOnce = true }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { granted = LocalMusic.hasPermission(context) }
    LaunchedEffect(granted) { if (granted) tracks = LocalMusic.tracks(context, refresh = true) }

    val np by SongPlayer.nowPlaying
    val playingLocal = LocalPlayer.active.value
    var adding by remember { mutableStateOf<LocalTrack?>(null) }
    adding?.let { t -> AddLocalToPlaylistSheet(t, onDismiss = { adding = null }) }
    var choosingFolders by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    if (choosingFolders) LocalFoldersSheet(
        onChanged = { scope.launch { tracks = LocalMusic.tracks(context) } },
        onDismiss = { choosingFolders = false },
    )

    val title = stringResource(R.string.local_files_title)
    val n = tracks?.size ?: 0
    ImmersivePage(
        title = title,
        subtitle = if (granted && tracks != null) pluralStringResource(R.plurals.local_files_count, n, n) else null,
        onBack = { navController.popBackStack() },
        actions = if (granted) {
            { ImmersiveBarAction(Icons.Rounded.Folder, stringResource(R.string.local_folders_button), onClick = { choosingFolders = true }) }
        } else null,
    ) {
        when {
            !granted -> item(key = "permission") {
                LocalEmpty(
                    stringResource(R.string.local_files_empty_section_title),
                    stringResource(R.string.local_files_empty_section_subtitle),
                    stringResource(if (askedOnce) R.string.local_files_empty_section_button_go_to_settings else R.string.local_files_empty_section_dialog_permission_rationale_accept),
                ) {
                    if (askedOnce) context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
                    else launcher.launch(LocalMusic.permission)
                }
            }
            tracks == null -> immersiveSongListSkeleton(count = 8)
            tracks?.isEmpty() == true -> item(key = "none") {
                LocalEmpty(stringResource(R.string.local_files_empty_section_no_files_title), stringResource(R.string.local_files_none_subtitle), null) {}
            }
            else -> {
                item(key = "buttons") {
                    ImmersivePlayShuffleButtons(
                        onPlay = {
                            when {
                                playingLocal && SongPlayer.isPlaying() -> SongPlayer.pause()
                                playingLocal -> SongPlayer.play()
                                else -> tracks?.let { LocalPlayer.play(it, 0) }
                            }
                        },
                        onShuffle = { tracks?.let { if (it.isNotEmpty()) LocalPlayer.play(it, it.indices.random(), shuffle = true) } },
                        modifier = Modifier.padding(bottom = 12.dp),
                    )
                }
                item(key = "find") {
                    ImmersiveSearchField(
                        query = find, onQueryChange = { find = it }, onSubmit = {},
                        placeholder = stringResource(R.string.local_files_find_hint),
                        modifier = Modifier.padding(horizontal = Immersive.Gutter, vertical = 6.dp),
                    )
                }
                val shown = tracks.orEmpty().filter { com.music.spotui.ui.components.matchesFind(find, it.title, it.artist, it.album) }
                if (shown.isEmpty() && find.isNotBlank()) item(key = "find-empty") {
                    ImmersiveMessageState(stringResource(R.string.assisted_curation_search_empty_view_no_result_title, find.trim()))
                }
                items(shown, key = { it.id }) { t ->
                    val current = playingLocal && np.uri == "local:${t.id}"
                    Column(immersiveItemPlacement()) {
                        ImmersiveListRow(
                            title = t.title, subtitle = t.artist.ifBlank { null }, imageUrl = t.artUri.toString(), round = false,
                            playing = current,
                            // The whole folder plays from this song, even when the list is filtered.
                            onClick = { tracks?.let { all -> LocalPlayer.play(all, all.indexOf(t).coerceAtLeast(0)) } },
                            onMore = { adding = t },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LocalEmpty(title: String, subtitle: String, button: String?, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp),
    ) {
        Image(painterResource(R.drawable.local_files_cover), null, modifier = Modifier.size(120.dp).clip(Immersive.CoverMedium))
        Text(title, style = Immersive.Title, modifier = Modifier.padding(top = 24.dp))
        Text(subtitle, style = Immersive.Subhead, modifier = Modifier.padding(top = 8.dp))
        if (button != null) ImmersiveButton(button, onClick, filled = true, modifier = Modifier.padding(top = 24.dp))
    }
}

// ---------------------------------------------------------------- Episode

/** One podcast episode (or book chapter) of the Immersive look; the state and actions are the Classic page's [EpisodeViewModel]. */
@Composable
fun ImmersiveEpisodeScreen(navController: NavController, episodeId: String, fallbackName: String = "") {
    val vm: EpisodeViewModel = viewModel()
    val context = LocalContext.current
    LaunchedEffect(episodeId) { vm.load(episodeId) }

    val episode by vm.episode.collectAsState()
    val finished = vm.finished.value
    var menuOpen by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    val np by SongPlayer.nowPlaying
    val uri = "spotify:episode:$episodeId"
    val playing = np.uri == uri && SongPlayer.isPlaying()
    val saved = SongPlayer.library[uri] == true

    fun play() {
        val ep = episode ?: return
        SongPlayer.playSong(ep.url, context)
        // The mini player and the notification show it before Spotify reports it.
        com.music.spotui.di.Graph.currentSongState.updateSongState(ep.coverUri, ep.title, ep.singer, true, ep.id, 0, ep.album)
    }
    episode?.let { ep ->
        if (menuOpen) EpisodeOptionsSheet(song = ep, finished = finished, onPlay = ::play, onMarkFinished = { vm.setFinished(ep, it) }, onDismiss = { menuOpen = false })
    }

    val title = episode?.title?.takeIf { it.isNotBlank() } ?: fallbackName
    ImmersivePage(title = title, onBack = { navController.popBackStack() }) {
        item(key = "hero") {
            Column(Modifier.fillMaxWidth().padding(horizontal = Immersive.Gutter), horizontalAlignment = Alignment.CenterHorizontally) {
                ImmersiveCover(episode?.coverUri, Modifier.fillMaxWidth(0.62f).aspectRatio(1f), Immersive.CoverLarge)
                val showId = episode?.showId.orEmpty()
                if (showId.isNotBlank()) {
                    Text(
                        episode?.singer.orEmpty(), style = Immersive.BodyStrong.copy(color = Immersive.Accent), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 18.dp)
                            .immersiveClickable(onClick = { navController.navigate(showRoute(showId, episode?.singer.orEmpty())) }),
                    )
                }
                val date = episodeDateLabel(episode?.releaseDate.orEmpty())
                val minutes = episodeMinutes(episode?.durationMs ?: 0)
                Text(if (date.isBlank()) minutes else "$date • $minutes", style = Immersive.Subhead, modifier = Modifier.padding(top = 4.dp))
                // Action row: save, download, share, "...", then Play.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                ) {
                    ImmersiveCircleButton(
                        if (saved) Icons.Rounded.Check else Icons.Rounded.Add,
                        stringResource(if (saved) R.string.episode_unsave else R.string.episode_save),
                        onClick = { episode?.let { SongPlayer.setSaved(it.spotifyUri, !saved) } },
                    )
                    episode?.let { EpisodeDownloadButton(it, size = 26.dp) }
                    ImmersiveCircleButton(Icons.Rounded.IosShare, stringResource(R.string.a11y_share), onClick = { episode?.let { shareEpisode(context, it) } })
                    ImmersiveCircleButton(Icons.Rounded.MoreHoriz, stringResource(R.string.episode_options), onClick = { menuOpen = true })
                    Spacer(Modifier.weight(1f))
                    ImmersiveButton(
                        stringResource(if (playing) R.string.notif_action_pause else R.string.episode_play),
                        onClick = { if (playing) SongPlayer.pause() else play() },
                        icon = if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        filled = true,
                    )
                }
            }
        }
        if (vm.loading.value) immersiveSongListSkeleton(count = 2, keyPrefix = "episode:loading")
        if (vm.failed.value) item(key = "failed") { ImmersiveMessageState(stringResource(R.string.episode_not_found)) }
        episode?.let { ep ->
            if (ep.playedPositionMs > 0 && !finished) item(key = "remaining") {
                Text(
                    stringResource(R.string.episode_remaining, episodeClock(ep.durationMs - ep.playedPositionMs)),
                    style = Immersive.Caption.copy(color = Immersive.Accent),
                    modifier = Modifier.padding(horizontal = Immersive.Gutter, vertical = 4.dp),
                )
            }
            if (ep.description.isNotBlank()) item(key = "description") {
                Column(
                    Modifier.fillMaxWidth().animateContentSize(spring(stiffness = Spring.StiffnessMediumLow)).padding(Immersive.Gutter),
                ) {
                    Text(
                        com.music.spotui.ui.components.rememberLinkedText(ep.description, Immersive.Accent), style = Immersive.Body.copy(color = Immersive.Text.copy(alpha = 0.8f)),
                        maxLines = if (expanded) Int.MAX_VALUE else 4, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        stringResource(if (expanded) R.string.episode_show_less else R.string.episode_show_more),
                        style = Immersive.Subhead.copy(color = Immersive.Accent),
                        modifier = Modifier.padding(top = 8.dp).immersiveClickable(onClick = { expanded = !expanded }),
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Queue

/** The play queue route of the Immersive look: the song playing, then what is queued and what comes next. Swipe a row to remove it. */
@Composable
fun ImmersiveQueueScreen(navController: NavController) {
    // Spotify's real queue, read from the engine's PlayerAPI while this screen is open.
    DisposableEffect(Unit) {
        SongPlayer.watchQueue(true)
        onDispose { SongPlayer.watchQueue(false) }
    }
    val q by SongPlayer.engineQueue
    ImmersivePage(title = stringResource(R.string.action_queue_label), onBack = { navController.popBackStack() }) {
        q.current?.let { cur ->
            item(key = "now:header") { SectionHeader(stringResource(R.string.default_notification_channel_name)) }
            item(key = "now") { ImmersiveSongRow(song = cur, playing = true, onClick = {}, onMore = null) }
        }
        if (q.queued.isNotEmpty()) {
            item(key = "queued:header") { SectionHeader(stringResource(R.string.next_in_queue)) }
            itemsIndexed(q.queued, key = { i, it -> "q" + i + it.uid + it.uri }) { _, item ->
                Column(immersiveItemPlacement()) {
                    ImmersiveSwipeToRemove(item.uid + item.uri, onRemove = { SongPlayer.removeFromQueue(item) }) {
                        Box(Modifier.background(Immersive.Page)) { ImmersiveSongRow(song = item.song, playing = false, onClick = { SongPlayer.skipTo(item) }, onMore = null) }
                    }
                }
            }
        }
        if (q.nextUp.isNotEmpty()) {
            item(key = "next:header") { SectionHeader(stringResource(R.string.next_up)) }
            itemsIndexed(q.nextUp, key = { i, it -> "n" + i + it.uid + it.uri }) { _, item ->
                Column(immersiveItemPlacement()) {
                    ImmersiveSwipeToRemove(item.uid + item.uri, onRemove = { SongPlayer.removeFromQueue(item) }) {
                        Box(Modifier.background(Immersive.Page)) { ImmersiveSongRow(song = item.song, playing = false, onClick = { SongPlayer.skipTo(item) }, onMore = null) }
                    }
                }
            }
        }
    }
}
