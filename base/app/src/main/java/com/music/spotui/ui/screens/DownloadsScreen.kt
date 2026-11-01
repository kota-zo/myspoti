package com.music.spotui.ui.screens

import com.music.spotui.ui.components.widePressable
import com.music.spotui.ui.theme.AppDestructive
import com.music.spotui.ui.theme.headerTint
import com.music.spotui.ui.theme.tone
import com.music.spotui.ui.theme.AppText
import com.music.spotui.ui.theme.AppMuted

import android.text.format.Formatter
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import app.myspoti.engine.R
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.CrossFade
import com.bumptech.glide.integration.compose.GlideImage
import com.bumptech.glide.integration.compose.placeholder
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.data.offline.OfflineDownloader
import com.music.spotui.data.offline.OfflineStatus
import com.music.spotui.data.offline.OfflineStore
import com.music.spotui.di.LocalPlayer
import com.music.spotui.di.SongPlayer
import com.music.spotui.ui.components.DownloadBadge
import com.music.spotui.ui.components.animatedRow
import com.music.spotui.ui.components.pressable
import com.music.spotui.ui.theme.AppBackground

/** "Downloaded": songs kept for offline listening, played from the phone by [LocalPlayer]. */
@OptIn(ExperimentalGlideComposeApi::class)
@Composable
fun DownloadsScreen(navController: NavController) {
    val context = LocalContext.current
    val title = stringResource(R.string.collection_downloaded)
    // Snapshot reads: the list follows downloads as they finish. Derived, so a progress tick (which
    // changes no list) leaves the sorted lists and the counts alone instead of rebuilding them.
    val tracks by remember { derivedStateOf { OfflineStore.tracks.values.sortedByDescending { it.addedAt } } }
    // Shown by where they were downloaded from: each album or playlist downloaded as a whole is one
    // row (its songs are on its own page), newest first; the songs downloaded on their own follow.
    val lists by remember {
        derivedStateOf {
            com.music.spotui.data.offline.OfflineCollections.collections.values.mapNotNull { c ->
                val mine = tracks.filter { it.trackId in c.trackIds }
                if (mine.isEmpty()) null else c to mine
            }.sortedByDescending { (_, mine) -> mine.maxOf { it.addedAt } }
        }
    }
    val singles by remember {
        derivedStateOf {
            val inLists = lists.flatMapTo(HashSet()) { (_, mine) -> mine.map { it.trackId } }
            tracks.filter { it.trackId !in inLists }
        }
    }
    val waiting by remember { derivedStateOf { OfflineDownloader.requests.values.filter { !OfflineStore.isDownloaded(it.trackId) } } }
    // A25: only queued or running songs can be stopped; a failed one has nothing left to cancel.
    val stoppable by remember {
        derivedStateOf {
            waiting.count {
                val s = OfflineStore.status[it.trackId]
                s == OfflineStatus.Queued || s is OfflineStatus.Downloading
            }
        }
    }
    val np by SongPlayer.nowPlaying
    val playingHere = LocalPlayer.active.value && LocalPlayer.contextName == title
    var menuSong by remember { mutableStateOf<SongsModel?>(null) }
    menuSong?.let { sel ->
        com.music.spotui.ui.components.SongOptionsSheet(song = sel, navController = navController, context = context, onDismiss = { menuSong = null })
    }
    fun play(index: Int, shuffle: Boolean = false) {
        if (tracks.isEmpty()) return
        LocalPlayer.play(tracks.map(OfflineStore::toLocalTrack), index, shuffle = shuffle, context = title)
    }

    Box(Modifier.fillMaxSize().background(AppBackground)) {
        if (tracks.isEmpty() && waiting.isEmpty()) Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp),
        ) {
            Image(painterResource(R.drawable.downloads_cover), null, modifier = Modifier.size(120.dp).clip(RoundedCornerShape(6.dp)))
            Text(stringResource(R.string.downloads_empty_title), color = AppText, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center, modifier = Modifier.padding(top = 24.dp))
            Text(stringResource(R.string.downloads_empty_subtitle), color = AppMuted, fontSize = 14.sp,
                textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
        } else LazyColumn(Modifier.fillMaxSize()) {
            item {
                Column(
                    Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(headerTint(Color(0xFF1D6B45), 0f), AppBackground)))
                        .statusBarsPadding().padding(horizontal = 16.dp),
                ) {
                    Spacer(Modifier.height(56.dp))
                    Image(
                        painterResource(R.drawable.downloads_cover), null,
                        modifier = Modifier.align(Alignment.CenterHorizontally).size(200.dp).clip(RoundedCornerShape(6.dp)),
                    )
                    Text(title, color = AppText, fontSize = 26.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 20.dp))
                    val n = tracks.size
                    val size = Formatter.formatShortFileSize(context, OfflineStore.totalBytes())
                    Text(pluralStringResource(R.plurals.local_files_count, n, n) + " · " + stringResource(R.string.downloads_size, size),
                        color = AppMuted, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
                    Text(stringResource(R.string.downloads_source), color = AppMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Spacer(Modifier.weight(1f))
                        Icon(painterResource(R.drawable.ic_player_shuffle), stringResource(R.string.play_mode_item_shuffle_title), tint = AppText,
                            modifier = Modifier.size(28.dp).pressable { if (tracks.isNotEmpty()) play(tracks.indices.random(), shuffle = true) })
                        Spacer(Modifier.width(20.dp))
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.size(56.dp)
                                .pressable(0.92f) {
                                    when {
                                        playingHere && SongPlayer.isPlaying() -> SongPlayer.pause()
                                        playingHere -> SongPlayer.play()
                                        else -> play(0)
                                    }
                                }
                                .clip(CircleShape).background(com.music.spotui.ui.theme.AppAccent),
                        ) {
                            Icon(painterResource(if (playingHere && SongPlayer.isPlaying()) R.drawable.ic_playing else R.drawable.play_svgrepo_com),
                                stringResource(R.string.content_description_play_button), tint = Color.Black, modifier = Modifier.size(26.dp))
                        }
                    }
                }
            }
            // No extension gives download files: say it where downloads would fail.
            if (!com.music.spotui.extensions.Extensions.hasDownloadSource()) item(key = "source") {
                Text(
                    stringResource(R.string.downloads_need_source),
                    color = AppText, fontSize = 14.sp, lineHeight = 19.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp)).background(tone(0xFF2A2A2A))
                        .pressable(0.98f) { navController.navigate(com.music.spotui.ui.navigation.Routes.Extensions.route) }
                        .padding(14.dp),
                )
            }
            // A25: one tap empties the queue; the songs already downloaded stay untouched.
            if (stoppable > 0) item(key = "stop_all") {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                        .clip(RoundedCornerShape(8.dp)).background(tone(0xFF2A2A2A))
                        .pressable(0.98f) { OfflineDownloader.stopAll() }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.downloads_stop_all_title), color = AppText, fontSize = 14.sp)
                        Text(stringResource(R.string.downloads_stop_all_detail), color = AppMuted, fontSize = 12.sp)
                    }
                    Spacer(Modifier.width(12.dp))
                    Icon(Icons.Default.Close, stringResource(R.string.downloads_stop_all_title),
                        tint = AppText.copy(alpha = 0.7f), modifier = Modifier.size(20.dp))
                }
            }
            // Songs still on their way (or that could not be downloaded).
            items(waiting, key = { "w:" + it.trackId }) { r ->
                val status = OfflineStore.status[r.trackId]
                val problem = status == OfflineStatus.Failed || status == OfflineStatus.Unavailable
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = animatedRow().fillMaxWidth()
                        .pressable(0.97f) { if (status == OfflineStatus.Failed) OfflineDownloader.retry(r.trackId) }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    GlideImage(
                        transition = CrossFade,
                        model = r.coverUrl, contentDescription = null, contentScale = ContentScale.Crop,
                        loading = placeholder(R.drawable.placeholder), failure = placeholder(R.drawable.placeholder),
                        modifier = Modifier.size(48.dp).clip(RoundedCornerShape(4.dp)),
                    )
                    Column(Modifier.padding(start = 12.dp).weight(1f)) {
                        Text(r.title, color = AppText.copy(alpha = 0.6f), fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val line = when (status) {
                            OfflineStatus.Unavailable -> stringResource(R.string.download_unavailable)
                            OfflineStatus.Failed -> stringResource(R.string.download_badge_error_content_description) + " · " + stringResource(R.string.download_retry)
                            else -> stringResource(R.string.download_notification_downloading)
                        }
                        Text(line, color = if (problem) AppDestructive else AppMuted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    DownloadBadge(status, size = 20.dp)
                    Spacer(Modifier.width(16.dp))
                    Icon(Icons.Default.Close, stringResource(R.string.download_cancel), tint = AppText.copy(alpha = 0.7f),
                        modifier = Modifier.size(24.dp).pressable(0.96f) { OfflineDownloader.cancel(r.trackId) })
                }
            }
            if (lists.isNotEmpty()) item(key = "lists_title") { SectionTitle(stringResource(R.string.downloads_lists)) }
            items(lists, key = { "l:" + it.first.uri }) { (c, mine) ->
                val kind = when (c.type) {
                    "album" -> stringResource(R.string.downloads_kind_album)
                    "liked" -> stringResource(R.string.downloads_kind_liked)
                    else -> stringResource(R.string.downloads_kind_playlist)
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = animatedRow().fillMaxWidth().pressable(0.97f) {
                        val id = c.uri.substringAfterLast(":")
                        navController.navigate(when (c.type) {
                            "album" -> com.music.spotui.ui.navigation.albumRoute(c.name, mine.first().artist, id)
                            "liked" -> com.music.spotui.ui.navigation.Routes.Liked.route
                            else -> com.music.spotui.ui.navigation.playlistRoute(id, c.name)
                        })
                    }.padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    val first = mine.first()
                    GlideImage(
                        transition = CrossFade,
                        model = if (c.type == "album") OfflineStore.coverFile(first) ?: first.coverUrl else first.coverUrl,
                        contentDescription = null, contentScale = ContentScale.Crop,
                        loading = placeholder(R.drawable.placeholder), failure = placeholder(R.drawable.downloads_cover),
                        modifier = Modifier.size(56.dp).clip(RoundedCornerShape(4.dp)),
                    )
                    Column(Modifier.padding(start = 12.dp).weight(1f)) {
                        Text(c.name, color = AppText, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            DownloadBadge(OfflineStatus.Done, size = 14.dp)
                            Spacer(Modifier.width(6.dp))
                            Text(kind + " · " + pluralStringResource(R.plurals.local_files_count, mine.size, mine.size),
                                color = AppMuted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            if (lists.isNotEmpty() && singles.isNotEmpty()) item(key = "singles_title") { SectionTitle(stringResource(R.string.downloads_singles)) }
            itemsIndexed(singles, key = { _, t -> t.trackId }) { _, t ->
                val i = tracks.indexOf(t)
                val current = LocalPlayer.active.value && np.uri == "spotify:track:${t.trackId}"
                com.music.spotui.ui.components.SwipeToRemove(t.trackId, onRemove = {
                    val gone = com.music.spotui.data.offline.OfflineCollections.removeSong(t.trackId)
                    android.widget.Toast.makeText(context, if (gone) context.getString(R.string.toast_undownload) else com.music.spotui.data.offline.OfflineCollections.keptMessage(context, t.trackId), android.widget.Toast.LENGTH_SHORT).show()
                }) { Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().pressable(0.97f) { play(i) }.padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    GlideImage(
                        transition = CrossFade,
                        model = OfflineStore.coverFile(t) ?: t.coverUrl, contentDescription = null, contentScale = ContentScale.Crop,
                        loading = placeholder(R.drawable.placeholder), failure = placeholder(R.drawable.downloads_cover),
                        modifier = Modifier.size(48.dp).clip(RoundedCornerShape(4.dp)),
                    )
                    Column(Modifier.padding(start = 12.dp).weight(1f)) {
                        Text(t.title, color = if (current) com.music.spotui.ui.theme.AppAccent else AppText, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            DownloadBadge(OfflineStatus.Done, size = 14.dp)
                            Spacer(Modifier.width(6.dp))
                            Text(t.artist, color = AppMuted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Icon(Icons.Default.MoreVert, stringResource(R.string.content_description_context_menu), tint = AppText.copy(alpha = 0.7f),
                        modifier = Modifier.size(24.dp).pressable(0.96f) { menuSong = OfflineStore.toSong(t) })
                } }
            }
            item { Spacer(Modifier.height(com.music.spotui.ui.components.bottomBarsSpace())) }
        }
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.statusBarsPadding().padding(12.dp).widePressable { navController.navigateUp() }
                .size(40.dp).clip(CircleShape).background(AppBackground.copy(alpha = 0.4f)),
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.accessibility_navigate_back), tint = AppText)
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, color = AppText, fontSize = 18.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp))
}
