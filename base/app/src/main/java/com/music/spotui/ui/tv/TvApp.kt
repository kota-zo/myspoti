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
import com.music.spotui.data.api.Features
import com.music.spotui.data.entity.HomeSection
import com.music.spotui.data.api.Response
import com.music.spotui.data.entity.ContextPageModel
import com.music.spotui.data.entity.HomeItem
import com.music.spotui.data.entity.LibraryEntry
import com.music.spotui.data.entity.SearchResults
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
    val pages = remember { mutableStateListOf<TvPage>() }
    fun select(t: TvTab) {
        if (t == TvTab.NowPlaying && tab != TvTab.NowPlaying) from = tab
        pages.clear()
        tab = t
    }
    val open: (TvPage) -> Unit = { pages += it }
    val played: () -> Unit = { select(TvTab.NowPlaying) }

    BackHandler(enabled = pages.isNotEmpty()) { pages.removeAt(pages.lastIndex) }
    BackHandler(enabled = pages.isEmpty() && tab == TvTab.NowPlaying) { tab = from }
    BackHandler(enabled = pages.isEmpty() && (tab == TvTab.Library || tab == TvTab.Search)) { tab = TvTab.Home }

    TvFrame(tab, ::select) {
        when {
            // The player's artist opens over the tab it was opened from.
            tab == TvTab.NowPlaying -> TvNowPlaying(onArtist = { id, name -> tab = from; pages.clear(); pages += TvPage.Artist(id, name) })
            pages.isNotEmpty() -> when (val p = pages.last()) {
                is TvPage.Songs -> TvSongs(p.entry, played)
                is TvPage.Artist -> TvArtist(p, open, played)
                is TvPage.Show -> TvShow(p, played)
            }
            tab == TvTab.Home -> TvHome(open)
            tab == TvTab.Library -> TvLibrary(open)
            else -> TvSearch(open, played)
        }
    }
}

/** The page of [tab] under the floating bar of tabs. */
@Composable
internal fun TvFrame(tab: TvTab, onSelect: (TvTab) -> Unit, page: @Composable () -> Unit) {
    val labels = mapOf(
        TvTab.Home to stringResource(R.string.navigationbar_musicappitems_home_title),
        TvTab.Library to stringResource(R.string.aa_library),
        TvTab.Search to stringResource(R.string.navigationbar_musicappitems_search_title),
        TvTab.NowPlaying to stringResource(R.string.immersive_shell_now_playing),
    )
    Box(Modifier.fillMaxSize().background(TvBg)) {
        // The player runs under the bar to the top of the screen; the other pages start below it.
        Box(Modifier.fillMaxSize().padding(top = if (tab == TvTab.NowPlaying) 0.dp else 72.dp)) { page() }
        TvTopBar(tab, labels, onSelect, Modifier.align(Alignment.TopCenter))
    }
}

/** Home: Spotify's shelves as rows of covers, the first one big. Artists and shows stay on the phone. */
@Composable
private fun TvHome(onOpen: (TvPage) -> Unit) {
    val vm: HomeViewModel = viewModel()
    val home by vm.home.collectAsState()
    val feed = (home as? Response.Success)?.data
    when {
        feed == null && home is Response.Loading -> TvShelfSkeleton()
        feed == null -> TvRetry { vm.retry() }
        else -> TvHomeShelves(feed.greeting, feed.sections, onOpen)
    }
}

/** Home's shelves, the first one in big cards; an untitled one is named [greeting]. */
@Composable
internal fun TvHomeShelves(greeting: String, feedSections: List<HomeSection>, onOpen: (TvPage) -> Unit) {
    val likedName = stringResource(R.string.collection_liked_songs_title)
    val homeName = stringResource(R.string.navigationbar_musicappitems_home_title)
    val sections = remember(feedSections, likedName) {
        feedSections.withoutDj()
            .map { s -> s.copy(items = s.items.filter { it.page(likedName) != null }) }
            .filter { it.items.isNotEmpty() }
    }
    val first = remember { FocusRequester() }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 20.dp, bottom = 60.dp), verticalArrangement = Arrangement.spacedBy(30.dp)) {
        itemsIndexed(sections, key = { i, s -> "$i:${s.title}" }) { i, section ->
            val lead = i == 0
            Column(Modifier.fillMaxWidth()) {
                val title = section.title.ifBlank { greeting.ifBlank { homeName } }
                Text(title, fontSize = if (lead) 24.sp else 20.sp, fontWeight = FontWeight.Bold, color = Color.White,
                    modifier = Modifier.padding(start = 48.dp, bottom = 14.dp))
                LazyRow(contentPadding = PaddingValues(horizontal = 48.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(if (lead) 20.dp else 16.dp)) {
                    itemsIndexed(section.items, key = { j, it -> "$j:${it.name}:${it.imageUrl}" }) { j, item ->
                        val focus = if (lead && j == 0) Modifier.focusRequester(first) else Modifier
                        val page = item.page(likedName) ?: return@itemsIndexed
                        if (lead) TvGradientCard(item.name, item.subtitleText(), item.imageUrl, 240.dp, focus) { onOpen(page) }
                        else TvAlbumCard(item.name, item.subtitleText(), item.imageUrl, 160.dp, round = item is HomeItem.Artist, modifier = focus) { onOpen(page) }
                    }
                }
            }
        }
    }
    LaunchedEffect(sections.isNotEmpty()) { if (sections.isNotEmpty()) runCatching { delay(80); first.requestFocus() } }
}

/** The page a Home card opens, or null for one the TV cannot open (a card with no id, a single episode). */
private fun HomeItem.page(likedName: String): TvPage? = when (this) {
    is HomeItem.Album -> if (id.isBlank()) null else TvPage.Songs(LibraryEntry(id, name, artists.ifBlank { subtitle }, imageUrl, isPlaylist = false, uri = "spotify:album:$id"))
    is HomeItem.Playlist -> when {
        id == Api.HomeCache.LIKED_SONGS_ID -> TvPage.Songs(LibraryEntry("collection", likedName, "", imageUrl, isPlaylist = true, uri = LIKED))
        id.isNotBlank() -> TvPage.Songs(LibraryEntry(id, name, subtitle, imageUrl, isPlaylist = true, uri = "spotify:playlist:$id"))
        else -> null
    }
    is HomeItem.Artist -> if (id.isBlank()) null else TvPage.Artist(id, name)
    is HomeItem.Show -> if (id.isBlank() || isEpisode || !Features.PODCASTS) null else TvPage.Show(id, name)
}

private fun HomeItem.subtitleText(): String = when (this) {
    is HomeItem.Album -> artists.ifBlank { subtitle }
    is HomeItem.Playlist -> subtitle
    is HomeItem.Show -> subtitle
    is HomeItem.Artist -> ""
}

/** Liked Songs, then the playlists and albums of Your Library (a folder opens its own list of them). */
@Composable
private fun TvLibrary(onOpen: (TvPage) -> Unit) {
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
        else -> TvGrid(list) { e -> if (e.folderUri.isNotEmpty()) folder = e else onOpen(e.page()) }
    }
}

@Composable
private fun TvGrid(entries: List<LibraryEntry>, modifier: Modifier = Modifier, onClick: (LibraryEntry) -> Unit) {
    val first = remember { FocusRequester() }
    LazyVerticalGrid(GridCells.Adaptive(176.dp), modifier, contentPadding = PaddingValues(horizontal = 48.dp, vertical = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        itemsIndexed(entries, key = { _, it -> it.uri.ifEmpty { it.folderUri.ifEmpty { it.spotifyId } } }) { i, e ->
            TvAlbumCard(e.name, e.subtitle, e.coverUri, 160.dp, round = ":artist:" in e.uri, modifier = if (i == 0) Modifier.focusRequester(first) else Modifier) { onClick(e) }
        }
    }
    LaunchedEffect(entries) { runCatching { delay(80); first.requestFocus() } }
}

/**
 * Search: the field (the TV's own keyboard opens on it), then the songs in two columns and shelves
 * of artists, albums, playlists and (podcasts on) shows.
 */
@Composable
private fun TvSearch(onOpen: (TvPage) -> Unit, onPlayed: () -> Unit) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var asked by remember { mutableStateOf("") }
    var found by remember { mutableStateOf<SearchResults?>(null) }
    var failed by remember { mutableStateOf(false) }
    val field = remember { FocusRequester() }
    LaunchedEffect(asked) {
        if (asked.isBlank()) return@LaunchedEffect
        failed = false
        when (val r = Graph.api.searchEverything(asked).first { it !is Response.Loading }) {
            is Response.Success -> found = r.data
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
            modifier = Modifier.padding(horizontal = 64.dp).width(560.dp).focusRequester(field))
        Spacer(Modifier.height(20.dp))
        val f = found
        when {
            failed -> Text(stringResource(R.string.search_failed), color = TvSubtle, fontSize = 16.sp, modifier = Modifier.padding(start = 64.dp))
            f == null -> Unit
            f.isEmpty() -> Text(context.getString(R.string.assisted_curation_search_empty_view_no_result_title, asked), color = TvSubtle, fontSize = 16.sp,
                modifier = Modifier.padding(start = 64.dp))
            else -> TvSearchResults(f, onOpen) { s -> play(s, null, listOf(s)); onPlayed() }
        }
    }
}

private fun SearchResults.isEmpty() = songs.isEmpty() && albums.isEmpty() && artists.isEmpty() && playlists.isEmpty() && (!Features.PODCASTS || shows.isEmpty())

@Composable
internal fun TvSearchResults(f: SearchResults, onOpen: (TvPage) -> Unit, onSong: (SongsModel) -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 60.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        if (f.songs.isNotEmpty()) item("songs") {
            Column(Modifier.padding(horizontal = 64.dp)) {
                TvSectionTitle(stringResource(R.string.assisted_curation_search_filter_songs), Modifier)
                val songs = f.songs.take(8)
                Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                    listOf(songs.take(4), songs.drop(4)).forEach { part ->
                        Column(Modifier.weight(1f)) { part.forEach { s -> TvSongRow(s, null) { onSong(s) } } }
                    }
                }
            }
        }
        val artists = f.artists.filter { it.id.isNotBlank() }
        if (artists.isNotEmpty()) item("artists") {
            TvShelf(stringResource(R.string.assisted_curation_search_filter_artists)) {
                itemsIndexed(artists, key = { i, a -> "$i:${a.id}" }) { _, a ->
                    TvAlbumCard(a.name, "", a.coverUri, 150.dp, round = true) { onOpen(TvPage.Artist(a.id, a.name)) }
                }
            }
        }
        val albums = f.albums.filter { it.spotifyId.isNotBlank() }
        if (albums.isNotEmpty()) item("albums") {
            TvShelf(stringResource(R.string.assisted_curation_search_filter_albums)) {
                itemsIndexed(albums, key = { i, a -> "$i:${a.spotifyId}" }) { _, a ->
                    TvAlbumCard(a.name, a.artists, a.coverUri, 160.dp) {
                        onOpen(TvPage.Songs(LibraryEntry(a.spotifyId, a.name, a.artists, a.coverUri, isPlaylist = false, uri = "spotify:album:${a.spotifyId}")))
                    }
                }
            }
        }
        if (f.playlists.isNotEmpty()) item("playlists") {
            TvShelf(stringResource(R.string.assisted_curation_search_filter_playlists)) {
                itemsIndexed(f.playlists, key = { i, e -> "$i:${e.uri}" }) { _, e ->
                    TvAlbumCard(e.name, e.subtitle, e.coverUri, 160.dp) { onOpen(TvPage.Songs(e)) }
                }
            }
        }
        val shows = if (Features.PODCASTS) f.shows.filter { it.id.isNotBlank() } else emptyList()
        if (shows.isNotEmpty()) item("shows") {
            TvShelf(stringResource(R.string.assisted_curation_search_filter_podcasts)) {
                itemsIndexed(shows, key = { i, sh -> "$i:${sh.id}" }) { _, sh ->
                    TvAlbumCard(sh.name, sh.publisher, sh.coverUri, 160.dp) { onOpen(TvPage.Show(sh.id, sh.name)) }
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
        else -> TvSongsPage(entry, p,
            onPlay = { p.tracks.firstOrNull()?.let { vm.updateShuffleState(false); play(it, p.uri, p.tracks); onPlayed() } },
            onShuffle = { if (p.tracks.isNotEmpty()) { vm.updateShuffleState(true); play(p.tracks.random(), p.uri, p.tracks); onPlayed() } },
            onSong = { s -> play(s, p.uri, p.tracks); onPlayed() })
    }
}

@Composable
internal fun TvSongsPage(entry: LibraryEntry, p: ContextPageModel, onPlay: () -> Unit, onShuffle: () -> Unit, onSong: (SongsModel) -> Unit) {
    Row(Modifier.fillMaxSize().padding(start = 64.dp, end = 48.dp, top = 24.dp), horizontalArrangement = Arrangement.spacedBy(56.dp)) {
        val playFocus = remember { FocusRequester() }
        Column(Modifier.width(300.dp)) {
            TvCover(p.coverUri.ifBlank { entry.coverUri }, Modifier.size(300.dp), RoundedCornerShape(12.dp))
            Text(p.name.ifBlank { entry.name }, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold, maxLines = 2,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 16.dp))
            val owner = p.ownerName.ifBlank { entry.subtitle }
            if (owner.isNotBlank()) Text(owner, color = TvAccent, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(Modifier.padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TvPillButton(Icons.Default.PlayArrow, stringResource(R.string.immersive_player_play), filled = true, modifier = Modifier.focusRequester(playFocus), onClick = onPlay)
                TvPillButton(Icons.Default.Shuffle, stringResource(R.string.immersive_player_shuffle_on), filled = false, onClick = onShuffle)
            }
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 40.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            itemsIndexed(p.tracks, key = { i, s -> "$i:${s.url}" }) { i, s -> TvSongRow(s, i + 1) { onSong(s) } }
        }
        LaunchedEffect(p.tracks.isNotEmpty()) { if (p.tracks.isNotEmpty()) runCatching { delay(80); playFocus.requestFocus() } }
    }
}

/** Play or Shuffle under a list's cover: the filled one in the accent. */
@Composable
internal fun TvPillButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, filled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val fg = Color.White
    Row(
        modifier.tvPill(RoundedCornerShape(10.dp), base = if (filled) TvAccent else Color(0x26FFFFFF),
            focusedFill = if (filled) Color(0xFFFF5C97) else Color(0x4DFFFFFF), focusedScale = 1.06f, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, null, tint = fg, modifier = Modifier.size(20.dp))
        Text(label, color = fg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** One song: its number (or cover), title and artist, and its length. */
@Composable
internal fun TvSongRow(s: SongsModel, number: Int?, onClick: () -> Unit) {
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
internal fun TvRetry(onRetry: () -> Unit) {
    val focus = remember { FocusRequester() }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(stringResource(R.string.retry_button), color = Color.White, fontSize = 18.sp,
            modifier = Modifier.focusRequester(focus).tvPill(RoundedCornerShape(10.dp), base = Color(0xFF1C1C1E), onClick = onRetry)
                .padding(horizontal = 24.dp, vertical = 12.dp))
    }
    LaunchedEffect(Unit) { runCatching { delay(80); focus.requestFocus() } }
}

private const val LIKED = "spotify:collection:tracks"

/** A Library row the TV opens: a playlist, an album, an artist or (podcasts on) a show. */
private fun LibraryEntry.opens() = ":playlist:" in uri || ":album:" in uri || ":artist:" in uri || uri == LIKED ||
    (Features.PODCASTS && ":show:" in uri)

/** The page a Library row opens. */
private fun LibraryEntry.page(): TvPage = when {
    ":artist:" in uri -> TvPage.Artist(uri.substringAfterLast(':'), name)
    ":show:" in uri -> TvPage.Show(uri.substringAfterLast(':'), name)
    else -> TvPage.Songs(this)
}

internal fun formatMs(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}

private fun play(song: SongsModel, contextUri: String?, songs: List<SongsModel>) {
    SongPlayer.playSong(song.url, contextUri = contextUri, songs = songs)
    Graph.currentSongState.updateSongState(song.coverUri, song.title, song.singer, true, song.id, songs.indexOf(song).coerceAtLeast(0), song.album)
}
