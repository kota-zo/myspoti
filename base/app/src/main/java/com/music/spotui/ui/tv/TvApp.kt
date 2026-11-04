/* SPDX-License-Identifier: GPL-3.0-only */
package com.music.spotui.ui.tv

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import app.myspoti.engine.R
import com.music.spotui.data.api.Response
import com.music.spotui.data.entity.ContextPageModel
import com.music.spotui.data.entity.LibraryEntry
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.di.Graph
import com.music.spotui.di.SongPlayer
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

/**
 * Android TV: a short version of the app for the remote. Your Library and Liked Songs, search, a
 * list's songs and the player's transport; everything moves with the D-pad (the focused item is
 * outlined and a little bigger) and Back goes up a level. The rest of the app (settings, extensions,
 * downloads, lyrics) stays on the phone; playback, extensions and Connect are the same engine.
 */
object Tv {
    /** A TV (leanback) device, or a phone or tablet in TV mode. */
    fun isTv(context: Context): Boolean =
        (context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager)?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
}

private val Bg = Color(0xFF0E0E10)
private val Card = Color(0xFF1C1C21)
private val Accent = Color(0xFF1ED760)
private val Dim = Color.White.copy(alpha = 0.65f)

/** What the screen shows: a tab, or one list opened from it. */
private sealed interface TvPage {
    data object Library : TvPage
    data object Search : TvPage
    data class List(val entry: LibraryEntry) : TvPage
}

@Composable
fun TvApp() {
    val stack = remember { mutableStateListOf<TvPage>(TvPage.Library) }
    val page = stack.last()
    BackHandler(enabled = stack.size > 1) { stack.removeAt(stack.lastIndex) }
    Column(Modifier.fillMaxSize().background(Bg).padding(horizontal = 48.dp, vertical = 27.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Myspoti", color = Accent, fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 24.dp))
            TvTab(stringResource(R.string.aa_library), page == TvPage.Library || page is TvPage.List) { stack.clear(); stack += TvPage.Library }
            TvTab(stringResource(R.string.navigationbar_musicappitems_search_title), page == TvPage.Search) { stack.clear(); stack += TvPage.Search }
        }
        Spacer(Modifier.height(20.dp))
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (page) {
                TvPage.Library -> TvLibrary(onOpen = { stack += TvPage.List(it) })
                TvPage.Search -> TvSearch(onOpen = { stack += TvPage.List(it) })
                is TvPage.List -> TvSongs(page.entry)
            }
        }
        TvNowPlaying()
    }
}

/** The focus look: outlined in the accent and slightly bigger, as TV launchers do. */
@Composable
private fun Modifier.tvFocusable(shape: RoundedCornerShape = RoundedCornerShape(10.dp), onClick: () -> Unit): Modifier {
    var focused by remember { mutableStateOf(false) }
    return this
        .scale(if (focused) 1.05f else 1f)
        .clip(shape)
        .border(2.dp, if (focused) Accent else Color.Transparent, shape)
        .onFocusChanged { focused = it.isFocused }
        .clickable(onClick = onClick)
}

@Composable
private fun TvTab(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(label, color = if (selected) Color.White else Dim, fontSize = 20.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        modifier = Modifier.tvFocusable(RoundedCornerShape(50), onClick).background(if (selected) Card else Color.Transparent).padding(horizontal = 20.dp, vertical = 8.dp))
}

/** Liked Songs, then the playlists and albums of Your Library (a folder opens its own list of them). */
@Composable
private fun TvLibrary(onOpen: (LibraryEntry) -> Unit) {
    val context = LocalContext.current
    var folder by remember { mutableStateOf<LibraryEntry?>(null) }
    var entries by remember { mutableStateOf<List<LibraryEntry>?>(null) }
    var failed by remember { mutableStateOf(false) }
    var attempt by remember { mutableStateOf(0) }
    BackHandler(enabled = folder != null) { folder = null }
    LaunchedEffect(folder, attempt) {
        failed = false
        val f = folder
        val flow = if (f == null) Graph.api.getLibraryRoot() else Graph.api.getLibraryFolder(f.folderUri)
        when (val r = flow.first { it !is Response.Loading }) {
            is Response.Success -> {
                val liked = LibraryEntry("collection", context.getString(R.string.collection_liked_songs_title), "", "", isPlaylist = true, uri = LIKED)
                entries = (if (f == null) listOf(liked) else emptyList()) + r.data.filter { it.folderUri.isNotEmpty() || it.opens() }.filterNot { it.uri == LIKED }
            }
            else -> failed = true
        }
    }
    val list = entries
    when {
        failed && list == null -> TvRetry { attempt++ }
        list == null -> Unit
        else -> TvGrid(list) { e -> if (e.folderUri.isNotEmpty()) folder = e else onOpen(e) }
    }
}

@Composable
private fun TvGrid(entries: List<LibraryEntry>, onClick: (LibraryEntry) -> Unit) {
    val first = remember { FocusRequester() }
    LazyVerticalGrid(GridCells.Adaptive(170.dp), contentPadding = PaddingValues(8.dp), horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)) {
        items(entries, key = { it.uri.ifEmpty { it.folderUri.ifEmpty { it.spotifyId } } }) { e ->
            Column(Modifier.then(if (e === entries.first()) Modifier.focusRequester(first) else Modifier).tvFocusable { onClick(e) }.background(Card).padding(10.dp)) {
                TvCover(e.coverUri, Modifier.fillMaxWidth().aspectRatio(1f))
                Text(e.name, color = Color.White, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
                if (e.subtitle.isNotBlank()) Text(e.subtitle, color = Dim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
    LaunchedEffect(entries) { runCatching { delay(50); first.requestFocus() } }
}

/** Songs, playlists and albums matching what was typed (the TV's own keyboard opens on the field). */
@Composable
private fun TvSearch(onOpen: (LibraryEntry) -> Unit) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var asked by remember { mutableStateOf("") }
    var songs by remember { mutableStateOf<List<SongsModel>>(emptyList()) }
    var lists by remember { mutableStateOf<List<LibraryEntry>>(emptyList()) }
    var failed by remember { mutableStateOf(false) }
    val field = remember { FocusRequester() }
    LaunchedEffect(asked) {
        if (asked.isBlank()) return@LaunchedEffect
        failed = false
        when (val r = Graph.api.searchEverything(asked).first { it !is Response.Loading }) {
            is Response.Success -> {
                songs = r.data.songs
                lists = r.data.playlists + r.data.albums.map {
                    LibraryEntry(it.spotifyId, it.name, it.artists, it.coverUri, isPlaylist = false, uri = "spotify:album:${it.spotifyId}")
                }.filter { it.spotifyId.isNotBlank() }
            }
            else -> failed = true
        }
    }
    LaunchedEffect(Unit) { runCatching { delay(50); field.requestFocus() } }
    Column {
        TextField(query, { query = it }, singleLine = true, placeholder = { Text(stringResource(R.string.immersive_shell_search_hint)) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { asked = query.trim() }),
            modifier = Modifier.width(520.dp).focusRequester(field))
        Spacer(Modifier.height(16.dp))
        when {
            failed -> Text(stringResource(R.string.search_failed), color = Dim, fontSize = 16.sp)
            asked.isNotBlank() && songs.isEmpty() && lists.isEmpty() ->
                Text(context.getString(R.string.assisted_curation_search_empty_view_no_result_title, asked), color = Dim, fontSize = 16.sp)
            else -> Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(songs, key = { it.url }) { s -> TvSongRow(s) { play(s, null, listOf(s)) } }
                }
                Box(Modifier.weight(1f)) { TvGrid(lists, onOpen) }
            }
        }
    }
}

/** A list's songs; a song played plays the list on from it. */
@Composable
private fun TvSongs(entry: LibraryEntry) {
    var page by remember(entry) { mutableStateOf<ContextPageModel?>(null) }
    var failed by remember { mutableStateOf(false) }
    var attempt by remember { mutableStateOf(0) }
    LaunchedEffect(entry, attempt) {
        failed = false
        val id = entry.uri.substringAfterLast(':')
        val flow = when {
            entry.uri == LIKED -> Graph.api.getLikedPage()
            ":album:" in entry.uri -> Graph.api.getAlbumPage(id)
            else -> Graph.api.getPlaylistPage(id)
        }
        // The pages come in as they are read (a big list): each one shown, the last one kept.
        flow.collect { r ->
            when (r) {
                is Response.Success -> page = r.data
                is Response.Loading -> Unit
                else -> if (page == null) failed = true
            }
        }
    }
    val p = page
    when {
        failed && p == null -> TvRetry { attempt++ }
        p == null -> Unit
        else -> Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
            Column(Modifier.width(260.dp)) {
                TvCover(p.coverUri.ifBlank { entry.coverUri }, Modifier.size(260.dp))
                Text(p.name.ifBlank { entry.name }, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 2, modifier = Modifier.padding(top = 12.dp))
                if (p.ownerName.isNotBlank()) Text(p.ownerName, color = Dim, fontSize = 15.sp)
            }
            val first = remember { FocusRequester() }
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(p.tracks, key = { it.url + it.id }) { s ->
                    TvSongRow(s, if (s === p.tracks.first()) Modifier.focusRequester(first) else Modifier) { play(s, p.uri, p.tracks) }
                }
            }
            LaunchedEffect(p.tracks.isNotEmpty()) { if (p.tracks.isNotEmpty()) runCatching { delay(50); first.requestFocus() } }
        }
    }
}

@Composable
private fun TvSongRow(s: SongsModel, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(modifier.fillMaxWidth().tvFocusable(onClick = onClick).background(Card).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
        TvCover(s.coverUri, Modifier.size(48.dp))
        Column(Modifier.padding(start = 14.dp)) {
            Text(s.title, color = Color.White, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(s.singer, color = Dim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** The song playing, how far it is, and previous, play/pause and next. */
@Composable
private fun TvNowPlaying() {
    val state = Graph.currentSongState
    val title by state.title
    if (title.isBlank()) return
    val singer by state.singer
    val cover by state.coverUri
    val playing by state.playingState
    var progress by remember { mutableStateOf(0f) }
    LaunchedEffect(Unit) {
        while (true) {
            val d = SongPlayer.getDuration()
            progress = if (d > 0) (SongPlayer.getCurrentPosition().toFloat() / d).coerceIn(0f, 1f) else 0f
            delay(500)
        }
    }
    Column(Modifier.padding(top = 16.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Card)) {
        Box(Modifier.fillMaxWidth().height(3.dp).background(Color.White.copy(alpha = 0.15f))) {
            Box(Modifier.fillMaxWidth(progress).height(3.dp).background(Accent))
        }
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            TvCover(cover, Modifier.size(56.dp))
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text(title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(singer, color = Dim, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            TvButton(stringResource(R.string.immersive_player_previous), "⏮") { SongPlayer.previous() }
            TvButton(stringResource(if (playing) R.string.immersive_player_pause else R.string.immersive_player_play), if (playing) "⏸" else "▶") {
                if (SongPlayer.isPlaying()) SongPlayer.pause() else SongPlayer.play()
            }
            TvButton(stringResource(R.string.immersive_player_next), "⏭") { SongPlayer.next() }
        }
    }
}

@Composable
private fun TvButton(label: String, glyph: String, onClick: () -> Unit) {
    Box(Modifier.padding(horizontal = 6.dp).size(52.dp).tvFocusable(RoundedCornerShape(50), onClick).background(Color.White.copy(alpha = 0.08f))
        .semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        Text(glyph, color = Color.White, fontSize = 22.sp)
    }
}

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun TvCover(url: String, modifier: Modifier) {
    Box(modifier.clip(RoundedCornerShape(6.dp)).background(Color.White.copy(alpha = 0.06f))) {
        if (url.isNotBlank()) GlideImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
}

@Composable
private fun TvRetry(onRetry: () -> Unit) {
    val focus = remember { FocusRequester() }
    Text(stringResource(R.string.retry_button), color = Color.White, fontSize = 18.sp,
        modifier = Modifier.focusRequester(focus).tvFocusable(onClick = onRetry).background(Card).padding(horizontal = 24.dp, vertical = 12.dp))
    LaunchedEffect(Unit) { runCatching { delay(50); focus.requestFocus() } }
}

private const val LIKED = "spotify:collection:tracks"

/** A Library row that holds songs: a playlist or an album (artists, shows and the rest stay on the phone). */
private fun LibraryEntry.opens() = ":playlist:" in uri || ":album:" in uri || uri == LIKED

private fun play(song: SongsModel, contextUri: String?, songs: List<SongsModel>) {
    SongPlayer.playSong(song.url, contextUri = contextUri, songs = songs)
    Graph.currentSongState.updateSongState(song.coverUri, song.title, song.singer, true, song.id, songs.indexOf(song).coerceAtLeast(0), song.album)
}
