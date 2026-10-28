package com.music.spotui.ui.immersive.pages

import android.text.format.Formatter
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import app.myspoti.engine.R
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.data.offline.OfflineCollections
import com.music.spotui.data.offline.OfflineDownloader
import com.music.spotui.data.offline.OfflineStatus
import com.music.spotui.data.offline.OfflineStore
import com.music.spotui.di.Haptics
import com.music.spotui.di.LocalPlayer
import com.music.spotui.di.SongPlayer
import com.music.spotui.ui.components.DownloadBadge
import com.music.spotui.ui.components.FastScrollbar
import com.music.spotui.ui.immersive.sheets.ImmersiveSongOptionsSheet
import com.music.spotui.ui.immersive.Immersive
import com.music.spotui.ui.immersive.components.ImmersiveListRow
import com.music.spotui.ui.immersive.components.ImmersivePlayShuffleButtons
import com.music.spotui.ui.immersive.components.ImmersiveSongRow
import com.music.spotui.ui.immersive.components.ImmersiveRowDividerInset
import com.music.spotui.ui.immersive.components.immersiveClickable
import com.music.spotui.ui.immersive.screens.ImmersiveRowDivider
import com.music.spotui.ui.immersive.shell.ImmersiveLargeTitle
import com.music.spotui.ui.immersive.shell.ImmersiveTopBar
import com.music.spotui.ui.immersive.shell.immersiveHazeSource
import com.music.spotui.ui.immersive.shell.immersiveTopContentPadding
import com.music.spotui.ui.navigation.Routes

/**
 * "Downloaded" of the Immersive look, replacing the Classic `DownloadsScreen`: a large title with
 * the count and size, Play / Shuffle pills, the notice cards (SpotiFLAC verification, stop all),
 * the songs still on their way and the downloaded songs, played from the phone by [LocalPlayer].
 * A downloaded row swipes away to un-download it; its menu is the song sheet.
 */
@Composable
fun ImmersiveDownloadsScreen(navController: NavController) {
    val context = LocalContext.current
    val title = stringResource(R.string.collection_downloaded)
    // Snapshot reads: the list follows downloads as they finish.
    val tracks = OfflineStore.tracks.values.sortedByDescending { it.addedAt }
    val waiting = OfflineDownloader.requests.values.filter { !OfflineStore.isDownloaded(it.trackId) }
    // Only queued or running songs can be stopped; a failed one has nothing left to cancel.
    val stoppable = waiting.count {
        val s = OfflineStore.status[it.trackId]
        s == OfflineStatus.Queued || s is OfflineStatus.Downloading
    }
    val np by SongPlayer.nowPlaying
    val playingHere = LocalPlayer.active.value && LocalPlayer.contextName == title
    var menuSong by remember { mutableStateOf<SongsModel?>(null) }
    // Reported: the search of the Immersive look found songs in the whole catalogue, not here.
    // The page's own find field, as Local Files has.
    var find by remember { mutableStateOf("") }
    val shown = if (find.isBlank()) tracks else tracks.filter { com.music.spotui.ui.components.matchesFind(find, it.title, it.artist, it.album) }
    menuSong?.let { sel ->
        ImmersiveSongOptionsSheet(song = sel, navController = navController, context = context, onDismiss = { menuSong = null })
    }
    fun play(index: Int, shuffle: Boolean = false) {
        if (tracks.isEmpty()) return
        LocalPlayer.play(tracks.map(OfflineStore::toLocalTrack), index, shuffle = shuffle, context = title)
    }

    val listState = rememberLazyListState()
    ImmersivePageFrame {
        if (tracks.isEmpty() && waiting.isEmpty()) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp),
            ) {
                Image(
                    painterResource(R.drawable.downloads_cover), null,
                    modifier = Modifier.size(120.dp).clip(Immersive.CoverMedium),
                )
                Text(
                    stringResource(R.string.downloads_empty_title),
                    style = Immersive.Title, textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 24.dp),
                )
                Text(
                    stringResource(R.string.downloads_empty_subtitle),
                    style = Immersive.Subhead, textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        } else {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(top = immersiveTopContentPadding(), bottom = Immersive.BottomChrome),
                modifier = Modifier.fillMaxSize().immersiveHazeSource(),
            ) {
                item(key = "downloads:title") {
                    val n = tracks.size
                    val size = Formatter.formatShortFileSize(context, OfflineStore.totalBytes())
                    ImmersiveLargeTitle(
                        title = title,
                        subtitle = pluralStringResource(R.plurals.local_files_count, n, n) + " · " +
                            stringResource(R.string.downloads_size, size),
                    )
                    Text(
                        stringResource(R.string.downloads_source),
                        style = Immersive.Caption,
                        modifier = Modifier.padding(horizontal = Immersive.Gutter).padding(bottom = 14.dp),
                    )
                }
                if (tracks.isNotEmpty()) item(key = "downloads:buttons") {
                    ImmersivePlayShuffleButtons(
                        onPlay = {
                            when {
                                playingHere && SongPlayer.isPlaying() -> SongPlayer.pause()
                                playingHere -> SongPlayer.play()
                                else -> play(0)
                            }
                        },
                        onShuffle = { play(tracks.indices.random(), shuffle = true) },
                        modifier = Modifier.padding(bottom = 14.dp),
                    )
                }
                if (tracks.isNotEmpty()) item(key = "downloads:find") {
                    com.music.spotui.ui.immersive.components.ImmersiveSearchField(
                        query = find, onQueryChange = { find = it }, onSubmit = {},
                        placeholder = stringResource(R.string.immersive_detail_find),
                        modifier = Modifier.padding(horizontal = Immersive.Gutter, vertical = 6.dp),
                    )
                }
                // No extension gives download files: say it where downloads would fail.
                if (!com.music.spotui.extensions.Extensions.hasDownloadSource()) item(key = "source") {
                    NoticeCard(
                        title = stringResource(R.string.downloads_need_source),
                        detail = null,
                        onClick = { navController.navigate(Routes.Extensions.route) },
                    )
                }
                // One tap empties the queue; the songs already downloaded stay untouched.
                if (stoppable > 0) item(key = "stop_all") {
                    NoticeCard(
                        title = stringResource(R.string.downloads_stop_all_title),
                        detail = stringResource(R.string.downloads_stop_all_detail),
                        icon = true,
                        onClick = { Haptics.click(); OfflineDownloader.stopAll() },
                    )
                }
                // Songs still on their way (or that could not be downloaded).
                items(waiting, key = { "w:" + it.trackId }) { r ->
                    val status = OfflineStore.status[r.trackId]
                    val line = when (status) {
                        OfflineStatus.Unavailable -> stringResource(R.string.download_unavailable)
                        OfflineStatus.Failed ->
                            stringResource(R.string.download_badge_error_content_description) + " · " + stringResource(R.string.download_retry)
                        else -> stringResource(R.string.download_notification_downloading)
                    }
                    ImmersiveListRow(
                        title = r.title,
                        subtitle = line,
                        imageUrl = r.coverUrl,
                        round = false,
                        onClick = { if (status == OfflineStatus.Failed) OfflineDownloader.retry(r.trackId) },
                        modifier = Modifier.animateItem(),
                        trailing = {
                            DownloadBadge(status, size = 20.dp)
                            Spacer(Modifier.width(12.dp))
                            Icon(
                                Icons.Rounded.Close,
                                contentDescription = stringResource(R.string.download_cancel),
                                tint = Immersive.TextSecondary,
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .immersiveClickable(onClick = { Haptics.click(); OfflineDownloader.cancel(r.trackId) }, pressedScale = 0.9f)
                                    .padding(6.dp),
                            )
                        },
                    )
                }
                if (shown.isEmpty() && find.isNotBlank()) item(key = "downloads:find-empty") {
                    com.music.spotui.ui.immersive.components.ImmersiveMessageState(stringResource(R.string.assisted_curation_search_empty_view_no_result_title, find.trim()))
                }
                itemsIndexed(shown, key = { _, t -> t.trackId }) { i, t ->
                    val current = LocalPlayer.active.value && np.uri == "local:${OfflineStore.localIdOf(t.trackId)}"
                    val song = OfflineStore.toSong(t)
                    Column(Modifier.animateItem()) {
                        ImmersiveSwipeToRemove(t.trackId, onRemove = {
                            val gone = OfflineCollections.removeSong(t.trackId)
                            Toast.makeText(
                                context,
                                context.getString(if (gone) R.string.toast_undownload else R.string.download_kept_by_list),
                                Toast.LENGTH_SHORT,
                            ).show()
                        }) {
                            ImmersiveSongRow(
                                song = song,
                                playing = current && SongPlayer.isPlaying(),
                                current = current,
                                // The whole list plays from this song, even when it is filtered.
                                onClick = { play(tracks.indexOf(t).coerceAtLeast(0)) },
                                onMore = { menuSong = song },
                            )
                        }
                        if (i < shown.lastIndex) ImmersiveRowDivider(ImmersiveRowDividerInset)
                    }
                }
            }
            FastScrollbar(listState, Modifier.align(Alignment.CenterEnd))
        }
        ImmersiveTopBar(title = title, listState = listState, onBack = { navController.navigateUp() })
    }
}

/** A rounded notice under the buttons: a title, an optional detail line and, with [icon], a cross. */
@Composable
private fun NoticeCard(title: String, detail: String?, onClick: () -> Unit, icon: Boolean = false) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .padding(horizontal = Immersive.Gutter, vertical = 6.dp)
            .fillMaxWidth()
            .immersiveClickable(onClick = onClick, pressedScale = 0.98f)
            .clip(Immersive.Card)
            .background(Immersive.Raised)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = Immersive.Body.copy(fontSize = 14.sp, lineHeight = 19.sp))
            if (detail != null) Text(detail, style = Immersive.Caption)
        }
        if (icon) {
            Spacer(Modifier.width(12.dp))
            Icon(Icons.Rounded.Close, contentDescription = title, tint = Immersive.TextSecondary, modifier = Modifier.size(20.dp))
        }
    }
}
