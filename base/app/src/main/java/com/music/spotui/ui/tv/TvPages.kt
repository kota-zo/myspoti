/* SPDX-License-Identifier: GPL-3.0-only */
// Parts adapted from Android's Jetcaster sample (https://github.com/android/compose-samples), Copyright 2024 The Android Open Source Project, Apache License 2.0.
// Parts adapted from Android's JetStream sample (https://github.com/android/tv-samples), Copyright 2023 Google LLC, Apache License 2.0.
package com.music.spotui.ui.tv

import androidx.compose.foundation.background
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
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

/**
 * An artist over its picture: the header faded into the page as JetStream's MovieDetails fades its
 * poster (towards the bottom and the left), the name large, Play; then the popular songs in two
 * columns and the shelves of releases and related artists.
 */
@Composable
internal fun TvArtistPage(o: ArtistOverviewModel, onOpen: (TvPage) -> Unit, onPlayTrack: (Int) -> Unit) {
    val playFocus = remember { FocusRequester() }
    val header = o.headerImage.isNotBlank()
    TvBackdrop(o.headerImage.ifBlank { o.avatarImage }, o.name, Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 60.dp), verticalArrangement = Arrangement.spacedBy(TvSpace.section - 12.dp)) {
            item("head") {
                Box(Modifier.fillMaxWidth().then(if (header) Modifier.height(320.dp) else Modifier)) {
                    if (header) TvCover(o.headerImage, Modifier.matchParentSize().tvFadeOut(), RoundedCornerShape(0.dp))
                    Row(Modifier.align(Alignment.BottomStart).padding(start = TvSpace.start, top = 20.dp, end = TvSpace.start),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(TvSpace.large)) {
                        if (!header) TvCover(o.avatarImage, Modifier.size(200.dp).shadow(24.dp, CircleShape), RoundedCornerShape(100.dp), seed = o.name)
                        Column {
                            Text(stringResource(R.string.artist_subtitle).uppercase(), color = TvAccent, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp)
                            Text(o.name, style = TvType.displayMedium.copy(shadow = TvType.overImage), color = Color.White, maxLines = 1,
                                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                            o.monthlyListeners?.let {
                                Text(stringResource(R.string.monthly_listeners_text, NumberFormat.getIntegerInstance().format(it)), style = TvType.titleMedium,
                                    color = Color.White.copy(alpha = 0.7f))
                            }
                            Row(Modifier.padding(top = TvSpace.default)) {
                                if (o.topTracks.isNotEmpty()) TvPillButton(Icons.Default.PlayArrow, stringResource(R.string.immersive_player_play), filled = true,
                                    modifier = Modifier.focusRequester(playFocus)) { onPlayTrack(0) }
                            }
                        }
                    }
                }
            }
            if (o.topTracks.isNotEmpty()) item("popular") {
                Column(Modifier.padding(horizontal = TvSpace.start)) {
                    TvSectionTitle(stringResource(R.string.artist_popular_tracks), Modifier)
                    // Two columns of five, as a TV is wide.
                    val tracks = o.topTracks.take(10)
                    Row(horizontalArrangement = Arrangement.spacedBy(TvSpace.large)) {
                        listOf(tracks.take(5), tracks.drop(5)).forEachIndexed { col, part ->
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
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
                        TvAlbumCard(a.name, a.year, a.coverUri, 156.dp) {
                            onOpen(TvPage.Songs(LibraryEntry(a.spotifyId, a.name, a.artists, a.coverUri, isPlaylist = false, uri = "spotify:album:${a.spotifyId}")))
                        }
                    }
                }
            }
            val related = o.relatedArtists.filter { it.id.isNotBlank() }
            if (related.isNotEmpty()) item("related") {
                TvShelf(stringResource(R.string.fans_also_like_section_title)) {
                    itemsIndexed(related, key = { i, a -> "$i:${a.id}" }) { _, a ->
                        TvAlbumCard(a.name, "", a.coverUri, 140.dp, round = true) { onOpen(TvPage.Artist(a.id, a.name)) }
                    }
                }
            }
        }
    }
    LaunchedEffect(o.id) { runCatching { delay(80); playFocus.requestFocus() } }
}

/** A picture faded out towards the bottom and the left, into whatever lies under it (JetStream's MovieImageWithGradients). */
private fun Modifier.tvFadeOut(): Modifier = graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        drawRect(Brush.verticalGradient(0.35f to Color.Black, 1f to Color.Transparent), blendMode = BlendMode.DstIn)
        drawRect(Brush.horizontalGradient(0f to Color.Black.copy(alpha = 0.25f), 0.55f to Color.Black), blendMode = BlendMode.DstIn)
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

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun TvShowPage(s: ShowPage, onEpisode: (SongsModel) -> Unit) {
    val first = remember { FocusRequester() }
    val cover = s.coverUri.ifBlank { s.show.coverUri }
    TvBackdrop(cover, s.show.name, Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxSize().padding(start = TvSpace.detailStart, end = TvSpace.start, top = 12.dp), horizontalArrangement = Arrangement.spacedBy(TvSpace.twoColumn)) {
            Column(Modifier.width(TvDetailCover)) {
                TvCover(cover, Modifier.size(TvDetailCover).shadow(24.dp, RoundedCornerShape(12.dp)), RoundedCornerShape(12.dp), seed = s.show.name)
                Text(s.show.name, style = TvType.headlineSmall, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = TvSpace.default))
                if (s.show.publisher.isNotBlank()) Text(s.show.publisher, style = TvType.titleSmall, color = TvAccent, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (s.show.description.isNotBlank()) Text(s.show.description, style = TvType.bodySmall, maxLines = 6, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = TvSpace.small + TvSpace.tiny))
            }
            LazyColumn(Modifier.weight(1f).focusRestorer(), contentPadding = PaddingValues(top = 4.dp, bottom = 40.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                itemsIndexed(s.items, key = { i, e -> "$i:${e.url}" }) { i, e ->
                    Box(if (i == 0) Modifier.focusRequester(first) else Modifier) { TvSongRow(e, null) { onEpisode(e) } }
                }
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
    Column(Modifier.width(440.dp).clip(RoundedCornerShape(20.dp)).background(Color(0xFF232327))
        .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(20.dp)).padding(14.dp)) {
        Text(stringResource(R.string.listen_on), style = TvType.titleLarge, fontWeight = FontWeight.Bold, color = Color.White,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp))
        val list = devices
        if (list == null) TvShelfSkeletonLine()
        else list.forEachIndexed { i, d ->
            val playing = d.isActive || (d.isSelf && list.none { it.isActive })
            TvDeviceRow(d, playing, if (i == 0) Modifier.focusRequester(first) else Modifier) { onPick(d) }
        }
        LaunchedEffect(list?.isNotEmpty()) { if (!list.isNullOrEmpty()) runCatching { delay(80); first.requestFocus() } }
    }
}

/** A device as tv-material's ListItem: its icon on a disc and its name; light when focused. */
@Composable
private fun TvDeviceRow(d: SongPlayer.ConnectDevice, playing: Boolean, modifier: Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val f by animateFloatAsState(if (focused) 1f else 0f, tween(150), label = "deviceFocus")
    val fg = when { playing -> TvAccent; focused -> TvInverseOnSurface; else -> Color.White }
    Row(
        modifier.fillMaxWidth()
            .graphicsLayer { val sc = 1f + 0.02f * f; scaleX = sc; scaleY = sc }
            .clip(RoundedCornerShape(12.dp)).background(TvOnSurface.copy(alpha = f))
            .onFocusChanged { focused = it.isFocused }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(if (focused) Color.Black.copy(alpha = 0.08f) else Color.White.copy(alpha = 0.08f)),
            contentAlignment = Alignment.Center) {
            Icon(deviceIcon(d.type), null, tint = fg, modifier = Modifier.size(24.dp))
        }
        Text(d.name, style = TvType.titleMedium, fontWeight = if (playing) FontWeight.SemiBold else FontWeight.Medium, color = fg, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 16.dp))
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
internal fun TvSectionTitle(title: String, modifier: Modifier = Modifier.padding(start = TvSpace.start)) {
    // Jetcaster's section title (headline, sectionTitle padding).
    Text(title, style = TvType.headlineSmall, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = modifier.padding(bottom = 4.dp))
}

/** A titled row of cards, as Jetcaster's Catalog sections: the row gives the focus back to the card it last had. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun TvShelf(title: String, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        TvSectionTitle(title, Modifier.padding(start = TvSpace.start))
        LazyRow(Modifier.focusRestorer(), contentPadding = PaddingValues(horizontal = TvSpace.start, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(TvSpace.item), content = content)
    }
}
