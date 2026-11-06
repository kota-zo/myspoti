/* SPDX-License-Identifier: GPL-3.0-only */
package com.music.spotui.ui.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.viewmodel.compose.viewModel
import app.myspoti.engine.R
import com.music.spotui.data.api.Response
import com.music.spotui.data.entity.ArtistOverviewModel
import com.music.spotui.data.entity.LibraryEntry
import com.music.spotui.data.entity.ShowPage
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.di.Graph
import com.music.spotui.di.SongPlayer
import com.music.spotui.ui.viewmodel.PlayerViewModel
import kotlinx.coroutines.delay
import java.text.NumberFormat

/** What opens over a tab: a list of songs, an artist or a podcast. */
internal sealed interface TvPage {
    data class Songs(val entry: LibraryEntry) : TvPage
    data class Artist(val id: String, val name: String) : TvPage
    data class Show(val id: String, val name: String) : TvPage
}

/** An artist: its picture and name, Play, the popular songs, then its releases and the artists like it. */
@Composable
internal fun TvArtist(page: TvPage.Artist, onOpen: (TvPage) -> Unit, onPlayed: () -> Unit) {
    val vm: PlayerViewModel = viewModel()
    var overview by remember(page) { mutableStateOf<ArtistOverviewModel?>(null) }
    var failed by remember(page) { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(page, attempt) {
        failed = false
        Graph.api.getArtistOverview(page.name, page.id).collect { r ->
            when (r) {
                is Response.Success -> overview = r.data
                is Response.Loading -> Unit
                else -> if (overview == null) failed = true
            }
        }
    }
    val o = overview
    when {
        failed && o == null -> TvRetry { attempt++ }
        o == null -> TvShelfSkeleton(rows = 2)
        else -> TvArtistPage(o, onOpen) { index ->
            // As the phone's artist page: its popular songs become the queue.
            val songs = o.topTracks.map { it.song }
            val s = songs.getOrNull(index) ?: return@TvArtistPage
            vm.updateQueue(songs)
            SongPlayer.playSong(s.url, contextUri = "spotify:artist:${o.id}", songs = songs)
            vm.updateSongState(s.coverUri, s.title, s.singer, true, s.id, index)
            onPlayed()
        }
    }
}

@Composable
internal fun TvArtistPage(o: ArtistOverviewModel, onOpen: (TvPage) -> Unit, onPlayTrack: (Int) -> Unit) {
    val playFocus = remember { FocusRequester() }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 60.dp), verticalArrangement = Arrangement.spacedBy(28.dp)) {
        item("head") {
            Box(Modifier.fillMaxWidth()) {
                // The header picture, faded into the black under the name.
                if (o.headerImage.isNotBlank()) {
                    TvCover(o.headerImage, Modifier.fillMaxWidth().height(300.dp), RoundedCornerShape(0.dp))
                    Box(Modifier.matchParentSize().background(Brush.verticalGradient(0f to Color(0x33000000), 1f to TvBg)))
                }
                Row(Modifier.padding(start = 64.dp, top = if (o.headerImage.isNotBlank()) 150.dp else 24.dp), verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                    if (o.headerImage.isBlank()) TvCover(o.avatarImage, Modifier.size(180.dp), RoundedCornerShape(90.dp))
                    Column {
                        Text(o.name, color = Color.White, fontSize = 44.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        o.monthlyListeners?.let {
                            Text(stringResource(R.string.monthly_listeners_text, NumberFormat.getIntegerInstance().format(it)), color = TvSubtle, fontSize = 16.sp)
                        }
                        Row(Modifier.padding(top = 16.dp)) {
                            if (o.topTracks.isNotEmpty()) TvPillButton(Icons.Default.PlayArrow, stringResource(R.string.immersive_player_play), filled = true,
                                modifier = Modifier.focusRequester(playFocus)) { onPlayTrack(0) }
                        }
                    }
                }
            }
        }
        if (o.topTracks.isNotEmpty()) item("popular") {
            Column(Modifier.padding(horizontal = 64.dp)) {
                TvSectionTitle(stringResource(R.string.artist_popular_tracks), Modifier.padding(start = 0.dp))
                // Two columns of five, as a TV is wide.
                val tracks = o.topTracks.take(10)
                Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                    listOf(tracks.take(5), tracks.drop(5)).forEachIndexed { col, part ->
                        Column(Modifier.weight(1f)) {
                            part.forEachIndexed { i, t -> TvSongRow(t.song, col * 5 + i + 1) { onPlayTrack(col * 5 + i) } }
                        }
                    }
                }
            }
        }
        val releases = o.popularReleases.filter { it.spotifyId.isNotBlank() }
        if (releases.isNotEmpty()) item("releases") {
            TvShelf(stringResource(R.string.popular_releases_section_title)) {
                itemsIndexed(releases, key = { i, a -> "$i:${a.spotifyId}" }) { _, a ->
                    TvAlbumCard(a.name, a.year, a.coverUri, 140.dp) {
                        onOpen(TvPage.Songs(LibraryEntry(a.spotifyId, a.name, a.artists, a.coverUri, isPlaylist = false, uri = "spotify:album:${a.spotifyId}")))
                    }
                }
            }
        }
        val related = o.relatedArtists.filter { it.id.isNotBlank() }
        if (related.isNotEmpty()) item("related") {
            TvShelf(stringResource(R.string.fans_also_like_section_title)) {
                itemsIndexed(related, key = { i, a -> "$i:${a.id}" }) { _, a ->
                    TvAlbumCard(a.name, "", a.coverUri, 130.dp, round = true) { onOpen(TvPage.Artist(a.id, a.name)) }
                }
            }
        }
    }
    LaunchedEffect(o.id) { runCatching { delay(80); playFocus.requestFocus() } }
}

/** A podcast: its cover, name and publisher on the left, its episodes on the right. */
@Composable
internal fun TvShow(page: TvPage.Show, onPlayed: () -> Unit) {
    var show by remember(page) { mutableStateOf<ShowPage?>(null) }
    var failed by remember(page) { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(page, attempt) {
        failed = false
        Graph.api.getShowPage(page.id).collect { r ->
            when (r) {
                is Response.Success -> show = r.data
                is Response.Loading -> Unit
                else -> if (show == null) failed = true
            }
        }
    }
    val s = show
    when {
        failed && s == null -> TvRetry { attempt++ }
        s == null -> TvShelfSkeleton(rows = 2)
        else -> TvShowPage(s) { ep ->
            SongPlayer.playSong(ep.url, contextUri = "spotify:show:${page.id}")
            Graph.currentSongState.updateSongState(ep.coverUri, ep.title, ep.singer, true, ep.id, 0, ep.album)
            onPlayed()
        }
    }
}

@Composable
internal fun TvShowPage(s: ShowPage, onEpisode: (SongsModel) -> Unit) {
    val first = remember { FocusRequester() }
    Row(Modifier.fillMaxSize().padding(start = 64.dp, end = 48.dp, top = 24.dp), horizontalArrangement = Arrangement.spacedBy(56.dp)) {
        Column(Modifier.width(300.dp)) {
            TvCover(s.coverUri.ifBlank { s.show.coverUri }, Modifier.size(300.dp), RoundedCornerShape(12.dp))
            Text(s.show.name, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 16.dp))
            if (s.show.publisher.isNotBlank()) Text(s.show.publisher, color = TvAccent, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (s.show.description.isNotBlank()) Text(s.show.description, color = TvSubtle, fontSize = 13.sp, maxLines = 6, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 14.dp))
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 40.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            itemsIndexed(s.items, key = { i, e -> "$i:${e.url}" }) { i, e ->
                Box(if (i == 0) Modifier.focusRequester(first) else Modifier) { TvSongRow(e, null) { onEpisode(e) } }
            }
        }
    }
    LaunchedEffect(s.items.isNotEmpty()) { if (s.items.isNotEmpty()) runCatching { delay(80); first.requestFocus() } }
}

/** "Listen on": the Connect devices; the one playing is in the accent, a press moves the music there. */
@Composable
internal fun TvDevicesDialog(onDismiss: () -> Unit) {
    var devices by remember { mutableStateOf<List<SongPlayer.ConnectDevice>?>(null) }
    LaunchedEffect(Unit) { devices = runCatching { SongPlayer.devices() }.getOrDefault(emptyList()) }
    Dialog(onDismissRequest = onDismiss) {
        TvDevicesList(devices) { d -> SongPlayer.transferTo(d); onDismiss() }
    }
}

@Composable
internal fun TvDevicesList(devices: List<SongPlayer.ConnectDevice>?, onPick: (SongPlayer.ConnectDevice) -> Unit) {
    val first = remember { FocusRequester() }
    Column(Modifier.width(420.dp).clip(RoundedCornerShape(16.dp)).background(Color(0xFF1C1C1E)).padding(12.dp)) {
        Text(stringResource(R.string.listen_on), color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp))
        val list = devices
        if (list == null) TvShelfSkeletonLine()
        else list.forEachIndexed { i, d ->
            val playing = d.isActive || (d.isSelf && list.none { it.isActive })
            Row(
                (if (i == 0) Modifier.focusRequester(first) else Modifier).fillMaxWidth()
                    .tvPill(RoundedCornerShape(10.dp), focusedFill = Color(0x26FFFFFF), focusedScale = 1.02f) { onPick(d) }
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(deviceIcon(d.type), null, tint = if (playing) TvAccent else Color.White, modifier = Modifier.size(26.dp))
                Text(d.name, color = if (playing) TvAccent else Color.White, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 16.dp))
            }
        }
        LaunchedEffect(list?.isNotEmpty()) { if (!list.isNullOrEmpty()) runCatching { delay(80); first.requestFocus() } }
    }
}

private fun deviceIcon(type: String) = when (type.uppercase()) {
    "TV", "CAST_VIDEO", "GAME_CONSOLE" -> Icons.Default.Tv
    "SMARTPHONE", "TABLET" -> Icons.Default.Smartphone
    "COMPUTER" -> Icons.Default.Computer
    "CAST_AUDIO" -> Icons.Default.Cast
    else -> Icons.Default.Speaker
}

@Composable
private fun TvShelfSkeletonLine() {
    Box(Modifier.padding(12.dp).fillMaxWidth().height(20.dp).clip(RoundedCornerShape(5.dp)).background(Color(0x1FFFFFFF)))
}

@Composable
internal fun TvSectionTitle(title: String, modifier: Modifier = Modifier.padding(start = 48.dp)) {
    Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White, modifier = modifier.padding(bottom = 14.dp))
}

/** A titled row of cards. */
@Composable
internal fun TvShelf(title: String, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        TvSectionTitle(title, Modifier.padding(start = 64.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 64.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), content = content)
    }
}
