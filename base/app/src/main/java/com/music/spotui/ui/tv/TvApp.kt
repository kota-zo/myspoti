/*
 * The shell, Home shelves and list page follow Apple Music TV
 * (https://github.com/JObersi10/apple-music-tv), GPL-3.0, android/app/src/main/java/com/applemusicktv/ui/
 * AppShell.kt, screens/HomeScreen.kt and screens/PlaylistDetailScreen.kt at 7e5c594.
 * Modified for Myspoti, 2026-11.
 *
 * Changes: Myspoti's own data (Home feed, Your Library, search, lists) and engine; one stack of
 * lists over the open tab instead of a navigation graph; no onboarding, video, radio, settings
 * or update checks (those stay on the phone).
 */
package com.music.spotui.ui.tv

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.myspoti.engine.R
import com.music.spotui.data.api.Api
import com.music.spotui.data.api.Response
import com.music.spotui.data.entity.ContextPageModel
import com.music.spotui.data.entity.HomeItem
import com.music.spotui.data.entity.LibraryEntry
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.data.entity.withoutDj
import com.music.spotui.di.Graph
import com.music.spotui.di.SongPlayer
import com.music.spotui.ui.viewmodel.HomeViewModel
import com.music.spotui.ui.viewmodel.PlayerViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

/**
 * Android TV: a version of the app made for the remote. A floating bar of tabs (Home, Your
 * Library, Search, Now Playing) over shelves of covers; a list opens over its tab and Back closes
 * it; a song played goes to the full-screen player. Settings, extensions and downloads stay on the
 * phone; playback, extensions and Connect are the same engine.
 */
object Tv {
    /** A TV (leanback) device, or a phone or tablet in TV mode. */
    fun isTv(context: Context): Boolean =
        (context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager)?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
}

@Composable
fun TvApp() {
    var tab by remember { mutableStateOf(TvTab.Home) }
    // The tab the player was opened from, for Back.
    var from by remember { mutableStateOf(TvTab.Home) }
    val lists = remember { mutableStateListOf<LibraryEntry>() }
    fun select(t: TvTab) {
        if (t == TvTab.NowPlaying && tab != TvTab.NowPlaying) from = tab
        lists.clear()
        tab = t
    }
    val open: (LibraryEntry) -> Unit = { lists += it }
    val played: () -> Unit = { select(TvTab.NowPlaying) }

    BackHandler(enabled = lists.isNotEmpty()) { lists.removeAt(lists.lastIndex) }
    BackHandler(enabled = lists.isEmpty() && tab == TvTab.NowPlaying) { tab = from }
    BackHandler(enabled = lists.isEmpty() && (tab == TvTab.Library || tab == TvTab.Search)) { tab = TvTab.Home }

    val labels = mapOf(
        TvTab.Home to stringResource(R.string.navigationbar_musicappitems_home_title),
        TvTab.Library to stringResource(R.string.aa_library),
        TvTab.Search to stringResource(R.string.navigationbar_musicappitems_search_title),
        TvTab.NowPlaying to stringResource(R.string.immersive_shell_now_playing),
    )
    Box(Modifier.fillMaxSize().background(TvBg)) {
        // The player runs under the bar to the top of the screen; the other pages start below it.
        Box(Modifier.fillMaxSize().padding(top = if (tab == TvTab.NowPlaying) 0.dp else 72.dp)) {
            when {
                tab == TvTab.NowPlaying -> TvNowPlaying()
                lists.isNotEmpty() -> TvSongs(lists.last(), played)
                tab == TvTab.Home -> TvHome(open, played)
                tab == TvTab.Library -> TvLibrary(open)
                else -> TvSearch(open, played)
            }
        }
        TvTopBar(tab, labels, ::select, Modifier.align(Alignment.TopCenter))
    }
}

/** Home: Spotify's shelves as rows of covers, the first one big. Artists and shows stay on the phone. */
@Composable
private fun TvHome(onOpen: (LibraryEntry) -> Unit, onPlayed: () -> Unit) {
    val vm: HomeViewModel = viewModel()
    val home by vm.home.collectAsState()
    val context = LocalContext.current
    val likedName = stringResource(R.string.collection_liked_songs_title)
    val feed = (home as? Response.Success)?.data
    val sections = remember(feed) {
        feed?.sections.orEmpty().withoutDj()
            .map { s -> s.copy(items = s.items.filter { it.entry(likedName) != null }) }
            .filter { it.items.isNotEmpty() }
    }
    when {
        feed == null && home is Response.Loading -> TvShelfSkeleton()
        feed == null -> TvRetry { vm.retry() }
        else -> {
            val first = remember { FocusRequester() }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 20.dp, bottom = 60.dp), verticalArrangement = Arrangement.spacedBy(30.dp)) {
                itemsIndexed(sections, key = { i, s -> "$i:${s.title}" }) { i, section ->
                    val lead = i == 0
                    Column(Modifier.fillMaxWidth()) {
                        val title = section.title.ifBlank { feed.greeting.ifBlank { context.getString(R.string.navigationbar_musicappitems_home_title) } }
                        Text(title, fontSize = if (lead) 24.sp else 20.sp, fontWeight = FontWeight.Bold, color = Color.White,
                            modifier = Modifier.padding(start = 48.dp, bottom = 14.dp))
                        LazyRow(contentPadding = PaddingValues(horizontal = 48.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(if (lead) 20.dp else 16.dp)) {
                            itemsIndexed(section.items, key = { j, it -> "$j:${it.name}:${it.imageUrl}" }) { j, item ->
                                val focus = if (lead && j == 0) Modifier.focusRequester(first) else Modifier
                                val entry = item.entry(likedName) ?: return@itemsIndexed
                                if (lead) TvGradientCard(item.name, item.subtitleText(), item.imageUrl, 240.dp, focus) { onOpen(entry) }
                                else TvAlbumCard(item.name, item.subtitleText(), item.imageUrl, 160.dp, modifier = focus) { onOpen(entry) }
                            }
                        }
                    }
                }
            }
            LaunchedEffect(sections.isNotEmpty()) { if (sections.isNotEmpty()) runCatching { delay(80); first.requestFocus() } }
        }
    }
}

/** The list a Home card opens, or null for what the TV does not open (artists, shows). */
private fun HomeItem.entry(likedName: String): LibraryEntry? = when (this) {
    is HomeItem.Album -> if (id.isBlank()) null else LibraryEntry(id, name, artists.ifBlank { subtitle }, imageUrl, isPlaylist = false, uri = "spotify:album:$id")
    is HomeItem.Playlist -> when {
        id == Api.HomeCache.LIKED_SONGS_ID -> LibraryEntry("collection", likedName, "", imageUrl, isPlaylist = true, uri = LIKED)
        id.isNotBlank() -> LibraryEntry(id, name, subtitle, imageUrl, isPlaylist = true, uri = "spotify:playlist:$id")
        else -> null
    }
    else -> null
}

private fun HomeItem.subtitleText(): String = when (this) {
    is HomeItem.Album -> artists.ifBlank { subtitle }
    is HomeItem.Playlist -> subtitle
    else -> ""
}

/** Liked Songs, then the playlists and albums of Your Library (a folder opens its own list of them). */
@Composable
private fun TvLibrary(onOpen: (LibraryEntry) -> Unit) {
    val context = LocalContext.current
    var folder by remember { mutableStateOf<LibraryEntry?>(null) }
    var entries by remember { mutableStateOf<List<LibraryEntry>?>(null) }
    var failed by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    BackHandler(enabled = folder != null) { folder = null }
    LaunchedEffect(folder, attempt) {
        failed = false
        val f = folder
        val flow = if (f == null) Graph.api.getLibraryRoot() else Graph.api.getLibraryFolder(f.folderUri)
        when (val r = flow.first { it !is Response.Loading }) {
            is Response.Success -> {
                val liked = LibraryEntry("collection", context.getString(R.string.collection_liked_songs_title), "", Api.HomeCache.LIKED_COVER, isPlaylist = true, uri = LIKED)
                entries = (if (f == null) listOf(liked) else emptyList()) + r.data.filter { it.folderUri.isNotEmpty() || it.opens() }.filterNot { it.uri == LIKED }
            }
            else -> failed = true
        }
    }
    val list = entries
    when {
        failed && list == null -> TvRetry { attempt++ }
        list == null -> TvShelfSkeleton()
        else -> TvGrid(list) { e -> if (e.folderUri.isNotEmpty()) folder = e else onOpen(e) }
    }
}

@Composable
private fun TvGrid(entries: List<LibraryEntry>, modifier: Modifier = Modifier, onClick: (LibraryEntry) -> Unit) {
    val first = remember { FocusRequester() }
    LazyVerticalGrid(GridCells.Adaptive(176.dp), modifier, contentPadding = PaddingValues(horizontal = 48.dp, vertical = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        itemsIndexed(entries, key = { _, it -> it.uri.ifEmpty { it.folderUri.ifEmpty { it.spotifyId } } }) { i, e ->
            TvAlbumCard(e.name, e.subtitle, e.coverUri, 160.dp, modifier = if (i == 0) Modifier.focusRequester(first) else Modifier) { onClick(e) }
        }
    }
    LaunchedEffect(entries) { runCatching { delay(80); first.requestFocus() } }
}

/** Songs, playlists and albums matching what was typed (the TV's own keyboard opens on the field). */
@Composable
private fun TvSearch(onOpen: (LibraryEntry) -> Unit, onPlayed: () -> Unit) {
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
    LaunchedEffect(Unit) { runCatching { delay(80); field.requestFocus() } }
    Column(Modifier.fillMaxSize().padding(top = 16.dp)) {
        TextField(query, { query = it }, singleLine = true, placeholder = { Text(stringResource(R.string.immersive_shell_search_hint)) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { asked = query.trim() }),
            shape = RoundedCornerShape(12.dp),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color(0xFF2C2C2E), unfocusedContainerColor = Color(0xFF1C1C1E),
                focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, cursorColor = TvAccent,
            ),
            modifier = Modifier.padding(horizontal = 48.dp).width(560.dp).focusRequester(field))
        Spacer(Modifier.height(20.dp))
        when {
            failed -> Text(stringResource(R.string.search_failed), color = TvSubtle, fontSize = 16.sp, modifier = Modifier.padding(start = 48.dp))
            asked.isNotBlank() && songs.isEmpty() && lists.isEmpty() ->
                Text(context.getString(R.string.assisted_curation_search_empty_view_no_result_title, asked), color = TvSubtle, fontSize = 16.sp,
                    modifier = Modifier.padding(start = 48.dp))
            else -> Row(Modifier.fillMaxSize()) {
                LazyColumn(Modifier.weight(1f).padding(start = 48.dp), contentPadding = PaddingValues(bottom = 40.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    itemsIndexed(songs, key = { i, s -> "$i:${s.url}" }) { _, s ->
                        TvSongRow(s, null) { play(s, null, listOf(s)); onPlayed() }
                    }
                }
                Box(Modifier.weight(1f)) {
                    if (lists.isNotEmpty()) TvGrid(lists, onClick = onOpen)
                }
            }
        }
    }
}

/** A list's page: its cover, name, Play and Shuffle on the left; its songs on the right. */
@Composable
private fun TvSongs(entry: LibraryEntry, onPlayed: () -> Unit) {
    val vm: PlayerViewModel = viewModel()
    var page by remember(entry) { mutableStateOf<ContextPageModel?>(null) }
    var failed by remember(entry) { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
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
        p == null -> TvShelfSkeleton(rows = 2)
        else -> Row(Modifier.fillMaxSize().padding(start = 64.dp, end = 48.dp, top = 24.dp), horizontalArrangement = Arrangement.spacedBy(56.dp)) {
            val playFocus = remember { FocusRequester() }
            Column(Modifier.width(300.dp)) {
                TvCover(p.coverUri.ifBlank { entry.coverUri }, Modifier.size(300.dp), RoundedCornerShape(12.dp))
                Text(p.name.ifBlank { entry.name }, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold, maxLines = 2,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 16.dp))
                val owner = p.ownerName.ifBlank { entry.subtitle }
                if (owner.isNotBlank()) Text(owner, color = TvAccent, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(Modifier.padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TvPillButton(Icons.Default.PlayArrow, stringResource(R.string.immersive_player_play), filled = true, modifier = Modifier.focusRequester(playFocus)) {
                        p.tracks.firstOrNull()?.let { vm.updateShuffleState(false); play(it, p.uri, p.tracks); onPlayed() }
                    }
                    TvPillButton(Icons.Default.Shuffle, stringResource(R.string.immersive_player_shuffle_on), filled = false) {
                        if (p.tracks.isNotEmpty()) { vm.updateShuffleState(true); play(p.tracks.random(), p.uri, p.tracks); onPlayed() }
                    }
                }
            }
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 40.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                itemsIndexed(p.tracks, key = { i, s -> "$i:${s.url}" }) { i, s ->
                    TvSongRow(s, i + 1) { play(s, p.uri, p.tracks); onPlayed() }
                }
            }
            LaunchedEffect(p.tracks.isNotEmpty()) { if (p.tracks.isNotEmpty()) runCatching { delay(80); playFocus.requestFocus() } }
        }
    }
}

/** Play or Shuffle under a list's cover: the filled one in the accent. */
@Composable
private fun TvPillButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, filled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val fg = if (filled) Color.Black else Color.White
    Row(
        modifier.tvPill(RoundedCornerShape(10.dp), base = if (filled) TvAccent else Color(0x26FFFFFF),
            focusedFill = if (filled) Color(0xFF3BE477) else Color(0x4DFFFFFF), focusedScale = 1.06f, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, null, tint = fg, modifier = Modifier.size(20.dp))
        Text(label, color = fg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** One song: its number (or cover), title and artist, and its length. */
@Composable
private fun TvSongRow(s: SongsModel, number: Int?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().tvPill(RoundedCornerShape(10.dp), focusedFill = Color(0x26FFFFFF), focusedScale = 1.02f, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (number != null) Text("$number", color = TvSubtle, fontSize = 14.sp, modifier = Modifier.width(36.dp))
        else TvCover(s.coverUri, Modifier.size(44.dp).padding(end = 0.dp), RoundedCornerShape(6.dp))
        Column(Modifier.weight(1f).padding(start = if (number == null) 14.dp else 0.dp)) {
            Text(s.title, color = Color.White, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(s.singer, color = TvSubtle, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (s.durationMs > 0) Text(formatMs(s.durationMs.toLong()), color = TvSubtle, fontSize = 13.sp, modifier = Modifier.padding(start = 16.dp))
    }
}

@Composable
private fun TvRetry(onRetry: () -> Unit) {
    val focus = remember { FocusRequester() }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(stringResource(R.string.retry_button), color = Color.White, fontSize = 18.sp,
            modifier = Modifier.focusRequester(focus).tvPill(RoundedCornerShape(10.dp), base = Color(0xFF1C1C1E), onClick = onRetry)
                .padding(horizontal = 24.dp, vertical = 12.dp))
    }
    LaunchedEffect(Unit) { runCatching { delay(80); focus.requestFocus() } }
}

private const val LIKED = "spotify:collection:tracks"

/** A Library row that holds songs: a playlist or an album (artists, shows and the rest stay on the phone). */
private fun LibraryEntry.opens() = ":playlist:" in uri || ":album:" in uri || uri == LIKED

internal fun formatMs(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}

private fun play(song: SongsModel, contextUri: String?, songs: List<SongsModel>) {
    SongPlayer.playSong(song.url, contextUri = contextUri, songs = songs)
    Graph.currentSongState.updateSongState(song.coverUri, song.title, song.singer, true, song.id, songs.indexOf(song).coerceAtLeast(0), song.album)
}
