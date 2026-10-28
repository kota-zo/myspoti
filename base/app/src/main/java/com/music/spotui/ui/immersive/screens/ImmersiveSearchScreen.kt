/*
 * Adapted from BitChord (https://github.com/kushagrasinghx/BitChord), GPL-3.0-or-later,
 * sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/screens/SearchScreen.kt at edc1e6f.
 * Modified for Myspoti, 2026-10.
 */
package com.music.spotui.ui.immersive.screens

import com.music.spotui.ui.immersive.shell.immersiveHazeSource
import com.music.spotui.ui.immersive.shell.immersiveChromeScroll
import android.content.Context
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import app.myspoti.api.MediaCard
import app.myspoti.engine.R
import app.myspoti.host.android.MyspotiRuntime
import com.music.spotui.data.api.Features
import com.music.spotui.data.api.Response
import com.music.spotui.data.entity.AlbumsModel
import com.music.spotui.data.entity.ArtistsModel
import com.music.spotui.data.entity.LibraryEntry
import com.music.spotui.data.entity.PodcastModel
import com.music.spotui.data.entity.SearchResults
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.data.preferences.RecentItem
import com.music.spotui.data.preferences.addRecentItem
import com.music.spotui.data.preferences.clearRecentItems
import com.music.spotui.data.preferences.getRecentItems
import com.music.spotui.data.preferences.removeRecentItem
import com.music.spotui.di.Graph
import com.music.spotui.di.SongPlayer
import com.music.spotui.ui.immersive.sheets.ImmersiveSongOptionsSheet
import com.music.spotui.ui.components.SwipeToQueue
import com.music.spotui.ui.immersive.Immersive
import com.music.spotui.ui.immersive.components.ImmersiveButton
import com.music.spotui.ui.immersive.components.ImmersiveCover
import com.music.spotui.ui.immersive.components.ImmersiveListRow
import com.music.spotui.ui.immersive.components.ImmersiveMessageState
import com.music.spotui.ui.immersive.components.ImmersiveMoreButton
import com.music.spotui.ui.immersive.components.ImmersiveRowDividerInset
import com.music.spotui.ui.immersive.components.ImmersiveSearchField
import com.music.spotui.ui.immersive.components.ImmersiveSongRow
import com.music.spotui.ui.immersive.components.SectionHeader
import com.music.spotui.ui.immersive.components.immersiveClickable
import com.music.spotui.ui.immersive.components.immersiveSongListSkeleton
import com.music.spotui.ui.immersive.shell.ImmersiveTopBar
import com.music.spotui.ui.immersive.shell.immersiveLargeTitle
import com.music.spotui.ui.immersive.shell.immersiveTopContentPadding
import com.music.spotui.ui.navigation.Routes
import com.music.spotui.ui.navigation.TabReselect
import com.music.spotui.ui.navigation.albumRoute
import com.music.spotui.ui.navigation.artistRoute
import com.music.spotui.ui.navigation.categoryRoute
import com.music.spotui.ui.navigation.playlistRoute
import com.music.spotui.ui.navigation.profileRoute
import com.music.spotui.ui.navigation.showRoute
import com.music.spotui.ui.screens.SearchFilter
import com.music.spotui.ui.viewmodel.SearchViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Search in the Immersive look: the page's large title over a sticky search field and filter
 * pills; the category tiles and the recent searches while nothing is typed; with a query, the
 * top result then songs, artists, albums, playlists and podcasts as sections. The view model,
 * actions, recents, suggestions and sheets are those of the Classic Search screen.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ImmersiveSearchScreen(navController: NavController) {
    val vm: SearchViewModel = viewModel()
    val results by vm.results.collectAsState()
    val data = (results as? Response.Success)?.data ?: SearchResults()
    val loading = results is Response.Loading
    val failed = results is Response.Error
    val context = LocalContext.current
    val text = vm.query
    val filter = vm.filter

    // Recents are the items opened from results, not the typed queries, and show only once the
    // field is focused.
    var searchFocused by remember { mutableStateOf(false) }
    var recents by remember { mutableStateOf(loadRecents(context)) }
    var menuSong by remember { mutableStateOf<SongsModel?>(null) }
    menuSong?.let { sel ->
        ImmersiveSongOptionsSheet(song = sel, navController = navController, context = context, onDismiss = { menuSong = null })
    }
    val recordRecent: (RecentItem) -> Unit = { item ->
        addRecentItem(context, item)
        recents = loadRecents(context)
    }

    // Re-tapping the Search tab focuses the field and scrolls to the top; no query is re-run.
    val listState = rememberLazyListState()
    val fieldFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        TabReselect.search.collect {
            runCatching { fieldFocus.requestFocus() }
            if (listState.firstVisibleItemIndex != 0 || listState.firstVisibleItemScrollOffset != 0) {
                listState.animateScrollToItem(0)
            }
        }
    }

    val profiles by produceState(emptyList<MediaCard>(), text, filter) {
        value = emptyList()
        if (filter != SearchFilter.Profiles || text.isBlank()) return@produceState
        delay(250)
        value = runCatching { MyspotiRuntime.api.search.users(text.trim(), limit = 30).items }.getOrDefault(emptyList())
    }
    val suggestions by produceState(emptyList<String>(), text) {
        value = emptyList()
        if (text.isBlank()) return@produceState
        delay(300)
        value = runCatching { MyspotiRuntime.api.search.suggestions(text.trim(), 6) }.getOrDefault(emptyList())
            .filter { !it.equals(text.trim(), ignoreCase = true) }
    }
    val tiles = rememberBrowseTiles()

    val title = stringResource(R.string.navigationbar_musicappitems_search_title)
    val playSong: (SongsModel) -> Unit = { song ->
        // A radio from the tapped track (queue = this song + recommendations), not the list.
        vm.startRadioFromSong(song)
        SongPlayer.playSong(song.url, context, songs = listOf(song), alone = true)
        vm.updateSongState(song.coverUri, song.title, song.singer, true, song.id, 0, song.album)
    }

    // Large windows: more browse tiles per row (two on phones).
    val browseCols = com.music.spotui.ui.components.windowSize().columns(2, 220.dp)
    Box(Modifier.fillMaxSize().background(Immersive.Page)) {
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(top = immersiveTopContentPadding(), bottom = Immersive.BottomChrome),
            modifier = Modifier.fillMaxSize().immersiveHazeSource().immersiveChromeScroll(),
        ) {
            immersiveLargeTitle(title)
            stickyHeader(key = "search:field") {
                Column(Modifier.fillMaxWidth().background(Immersive.Page).padding(top = 4.dp, bottom = 6.dp)) {
                    ImmersiveSearchField(
                        query = text,
                        onQueryChange = { vm.search(it) },
                        onSubmit = { vm.search(text) },
                        focusRequester = fieldFocus,
                        placeholder = stringResource(R.string.search_header_field_hint),
                        modifier = Modifier
                            .padding(horizontal = Immersive.Gutter)
                            .onFocusChanged { searchFocused = it.hasFocus },
                    )
                    if (text.isNotBlank()) {
                        ImmersiveChipRow {
                            val filters = SearchFilter.entries.filter { it != SearchFilter.Podcasts || Features.PODCASTS }
                            filters.forEach { f ->
                                val on = f == filter
                                ImmersiveChip(stringResource(f.label), selected = on) {
                                    vm.filter = if (on && f != SearchFilter.Top) SearchFilter.Top else f
                                }
                            }
                        }
                    }
                }
            }

            if (text.isBlank()) {
                if (searchFocused && recents.isNotEmpty()) {
                    recentSearches(
                        recents = recents,
                        onOpen = { item -> openRecent(item, navController, vm, context) },
                        onRemove = {
                            removeRecentItem(context, it)
                            recents = loadRecents(context)
                        },
                        onClear = {
                            clearRecentItems(context)
                            recents = emptyList()
                            // The account's own list too, so other devices forget them as well.
                            val runtime = MyspotiRuntime
                            runtime.scope.launch { runCatching { runtime.api.search.clearRecent() } }
                        },
                    )
                } else {
                    item(key = "search:browse-header") { SectionHeader(stringResource(R.string.browse_all)) }
                    items(tiles.chunked(browseCols), key = { row -> "browse:" + row.first().key }) { row ->
                        BrowseTileRow(row = row, columns = browseCols) { tile ->
                            // A genre opens a catalogue (a page of curated playlists), not a keyword search.
                            if (tile.key == ImmersiveNewReleasesGenre) navController.navigate(Routes.NewReleases.route)
                            else navController.navigate(categoryRoute(tile.key, tile.title))
                        }
                    }
                }
            } else {
                // The completions for what is typed: one tap searches it.
                if (suggestions.isNotEmpty()) {
                    items(suggestions, key = { "suggest:$it" }) { term ->
                        SuggestionRow(term) { vm.search(term) }
                    }
                }
                searchResults(
                    data = data,
                    filter = filter,
                    profiles = profiles,
                    text = text,
                    loading = loading,
                    failed = failed,
                    onRetry = { vm.retry() },
                    currentSongId = vm.currentSongId.value,
                    playing = Graph.currentSongState.playingState.value,
                    onSong = { song -> recordRecent(song.toRecentItem()); playSong(song) },
                    onSongMore = { menuSong = it },
                    onArtist = { artist ->
                        recordRecent(RecentItem(type = "artist", key = artist.id.ifBlank { artist.name }, name = artist.name, image = artist.coverUri))
                        navController.navigate(artistRoute(artist.name, artist.id))
                    },
                    onAlbum = { album ->
                        recordRecent(RecentItem(type = "album", key = album.spotifyId.ifBlank { album.name }, name = album.name, singer = album.artists, image = album.coverUri))
                        navController.navigate(albumRoute(album.name, album.artists, album.spotifyId))
                    },
                    onPlaylist = { playlist ->
                        recordRecent(RecentItem(type = "playlist", key = playlist.spotifyId, name = playlist.name, singer = playlist.subtitle, image = playlist.coverUri))
                        navController.navigate(playlistRoute(playlist.spotifyId, playlist.name))
                    },
                    onShow = { show ->
                        recordRecent(RecentItem(type = "show", key = show.id, name = show.name, singer = show.publisher, image = show.coverUri))
                        navController.navigate(showRoute(show.id, show.name))
                    },
                    onProfile = { card ->
                        navController.navigate(profileRoute(card.uri.substringAfterLast(':'), card.title))
                    },
                )
            }
        }
        ImmersiveTopBar(title, listState)
    }
}

private fun loadRecents(context: Context): List<RecentItem> =
    getRecentItems(context).filter { Features.PODCASTS || it.type != "show" }

/** Maps a tapped search-result song to a persisted recent item. */
private fun SongsModel.toRecentItem() = RecentItem(
    type = "song",
    key = spotifyTrackId.ifBlank { url },
    name = title,
    singer = singer,
    image = coverUri,
    songId = id,
    songAlbum = album,
    songUrl = url,
    spotifyTrackId = spotifyTrackId,
    explicit = explicit,
    durationMs = durationMs,
)

/** Replays a recent song or opens its album, artist, show or playlist, as the Classic screen does. */
private fun openRecent(item: RecentItem, navController: NavController, vm: SearchViewModel, context: Context) {
    when (item.type) {
        "song" -> {
            val songUrl = item.songUrl.ifBlank {
                SongPlayer.buildSpotifyPlayQuery(item.spotifyTrackId, item.name, item.singer)
            }.let { savedUrl ->
                if (item.spotifyTrackId.isNotBlank() && !savedUrl.startsWith("spotify:track:")) {
                    SongPlayer.buildSpotifyPlayQuery(item.spotifyTrackId, item.name, item.singer)
                } else {
                    savedUrl
                }
            }
            val song = SongsModel(
                item.songId, item.name, item.songAlbum, item.singer,
                item.image, songUrl, item.spotifyTrackId,
                explicit = item.explicit,
                durationMs = item.durationMs,
            )
            vm.startRadioFromSong(song)
            SongPlayer.playSong(song.url, context, songs = listOf(song), alone = true)
            vm.updateSongState(song.coverUri, song.title, song.singer, true, song.id, 0, song.album)
        }
        "artist" -> navController.navigate(artistRoute(item.name, item.key.takeIf { it != item.name }.orEmpty()))
        "album" -> navController.navigate(albumRoute(item.name, item.singer, item.key.takeIf { it != item.name }.orEmpty()))
        "show" -> navController.navigate(showRoute(item.key, item.name))
        "playlist" -> navController.navigate(playlistRoute(item.key, item.name))
    }
}

/** The recent searches: a heading with "Clear", then a row per item opened. */
private fun LazyListScope.recentSearches(
    recents: List<RecentItem>,
    onOpen: (RecentItem) -> Unit,
    onRemove: (RecentItem) -> Unit,
    onClear: () -> Unit,
) {
    item(key = "recent:header") {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = Immersive.Gutter, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.assisted_curation_search_search_history_title),
                style = Immersive.Title,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(R.string.chat_content_picker_clear_recent_searches),
                style = Immersive.Subhead.copy(color = Immersive.Accent, fontWeight = FontWeight.Medium),
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable(role = Role.Button, onClick = onClear)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
    }
    items(recents.size, key = { "recent:${recents[it].type}:${recents[it].key}" }) { i ->
        val item = recents[i]
        val subtitle = when (item.type) {
            "song" -> stringResource(R.string.assisted_curation_search_song) + " • ${item.singer}"
            "artist" -> stringResource(R.string.artist_subtitle)
            "album" -> stringResource(R.string.album) + " • ${item.singer}"
            "show" -> stringResource(R.string.album_type_podcast) + (if (item.singer.isNotBlank()) " • ${item.singer}" else "")
            "playlist" -> stringResource(R.string.assisted_curation_search_playlist) + (if (item.singer.isNotBlank()) " • ${item.singer}" else "")
            else -> ""
        }
        ImmersiveListRow(
            title = item.name,
            subtitle = subtitle,
            imageUrl = item.image,
            round = item.type == "artist",
            onClick = { onOpen(item) },
            trailing = {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = stringResource(R.string.aligned_curation_first_save_default_row_remove_action_description),
                    tint = Immersive.TextSecondary,
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .clickable(role = Role.Button) { onRemove(item) }
                        .padding(6.dp),
                )
            },
        )
    }
}

/** What the engine would complete the half-typed query to: tap to search it. */
@Composable
private fun SuggestionRow(term: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = Immersive.Gutter, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Search, contentDescription = null, tint = Immersive.TextSecondary, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(16.dp))
        Text(text = term, style = Immersive.Body, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A hairline between rows of a section, starting clear of the cover. */
@Composable
private fun RowDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = ImmersiveRowDividerInset)
            .height(0.5.dp)
            .background(Immersive.Separator),
    )
}

/**
 * The results for the typed query: with Top, the top result then a section per kind; with one
 * kind picked, that kind's rows alone. Loading, nothing found and a failure each say what they
 * are (never an empty page).
 */
private fun LazyListScope.searchResults(
    data: SearchResults,
    filter: SearchFilter,
    profiles: List<MediaCard>,
    text: String,
    loading: Boolean,
    failed: Boolean,
    onRetry: () -> Unit,
    currentSongId: Int,
    playing: Boolean,
    onSong: (SongsModel) -> Unit,
    onSongMore: (SongsModel) -> Unit,
    onArtist: (ArtistsModel) -> Unit,
    onAlbum: (AlbumsModel) -> Unit,
    onPlaylist: (LibraryEntry) -> Unit,
    onShow: (PodcastModel) -> Unit,
    onProfile: (MediaCard) -> Unit,
) {
    val top = filter == SearchFilter.Top
    val songs = if (top || filter == SearchFilter.Songs) data.songs else emptyList()
    val artists = if (top || filter == SearchFilter.Artists) data.artists else emptyList()
    val albums = if (top || filter == SearchFilter.Albums) data.albums else emptyList()
    val playlists = if (top || filter == SearchFilter.Playlists) data.playlists else emptyList()
    val podcasts = Features.PODCASTS && (top || filter == SearchFilter.Podcasts)
    val shows = if (podcasts) data.shows else emptyList()
    val episodes = if (podcasts) data.episodes else emptyList()
    val sectioned = top

    if (filter != SearchFilter.Profiles &&
        songs.isEmpty() && artists.isEmpty() && albums.isEmpty() && playlists.isEmpty() && shows.isEmpty() && episodes.isEmpty()
    ) {
        when {
            loading -> immersiveSongListSkeleton(keyPrefix = "skeleton:search")
            failed -> item(key = "search:failed") {
                ImmersiveMessageState(
                    message = stringResource(R.string.search_failed),
                    actionLabel = stringResource(R.string.home_retry),
                    onAction = onRetry,
                )
            }
            else -> item(key = "search:empty") {
                ImmersiveMessageState(stringResource(R.string.assisted_curation_search_empty_view_no_result_title, text.trim()))
            }
        }
        return
    }

    if (filter == SearchFilter.Profiles) {
        items(profiles.size, key = { "profile:${profiles[it].uri}" }) { i ->
            val card = profiles[i]
            ImmersiveListRow(
                title = card.title,
                subtitle = stringResource(R.string.recently_played_user_profile),
                imageUrl = card.images.firstOrNull()?.url,
                round = true,
                onClick = { onProfile(card) },
            )
        }
        return
    }

    // The All response carries its best song as a promoted card.
    if (top && songs.isNotEmpty()) {
        item(key = "search:top-result") {
            TopResultCard(
                song = songs.first(),
                onPlay = { onSong(songs.first()) },
                onMore = { onSongMore(songs.first()) },
            )
        }
    }
    fun title(res: Int): @Composable () -> String = { stringResource(res) }
    section("songs", sectioned, title(R.string.assisted_curation_search_filter_songs), songs.size) { range ->
        items(range.count(), key = { "song:${songs[range.first + it].id}:${range.first + it}" }) { n ->
            val song = songs[range.first + n]
            SongResultRow(song, currentSongId, playing, onClick = { onSong(song) }, onMore = { onSongMore(song) })
            if (n < range.count() - 1) RowDivider()
        }
    }
    section("artists", sectioned, title(R.string.assisted_curation_search_filter_artists), artists.size) { range ->
        items(range.count(), key = { "artist:${artists[range.first + it].id}:${range.first + it}" }) { n ->
            val artist = artists[range.first + n]
            ImmersiveListRow(
                title = artist.name,
                subtitle = stringResource(R.string.artist_subtitle),
                imageUrl = artist.coverUri,
                round = true,
                onClick = { onArtist(artist) },
            )
            if (n < range.count() - 1) RowDivider()
        }
    }
    section("albums", sectioned, title(R.string.assisted_curation_search_filter_albums), albums.size) { range ->
        items(range.count(), key = { "album:${albums[range.first + it].spotifyId}:${range.first + it}" }) { n ->
            val album = albums[range.first + n]
            ImmersiveListRow(
                title = album.name,
                subtitle = stringResource(R.string.album) + " • ${album.artists}",
                imageUrl = album.coverUri,
                round = false,
                onClick = { onAlbum(album) },
            )
            if (n < range.count() - 1) RowDivider()
        }
    }
    section("playlists", sectioned, title(R.string.assisted_curation_search_filter_playlists), playlists.size) { range ->
        items(range.count(), key = { "playlist:${playlists[range.first + it].spotifyId}:${range.first + it}" }) { n ->
            val playlist = playlists[range.first + n]
            ImmersiveListRow(
                title = playlist.name,
                subtitle = stringResource(R.string.assisted_curation_search_playlist) +
                    (if (playlist.subtitle.isNotBlank()) " • ${playlist.subtitle}" else ""),
                imageUrl = playlist.coverUri,
                round = false,
                onClick = { onPlaylist(playlist) },
            )
            if (n < range.count() - 1) RowDivider()
        }
    }
    // Podcasts: shows (open their page) then individual episodes (play).
    section("shows", sectioned, title(R.string.assisted_curation_search_filter_podcasts), shows.size) { range ->
        items(range.count(), key = { "show:${shows[range.first + it].id}:${range.first + it}" }) { n ->
            val show = shows[range.first + n]
            ImmersiveListRow(
                title = show.name,
                subtitle = stringResource(R.string.album_type_podcast) + (if (show.publisher.isNotBlank()) " • ${show.publisher}" else ""),
                imageUrl = show.coverUri,
                round = false,
                onClick = { onShow(show) },
            )
            if (n < range.count() - 1) RowDivider()
        }
    }
    section("episodes", sectioned, title(R.string.assisted_curation_search_filter_episodes), episodes.size) { range ->
        items(range.count(), key = { "episode:${episodes[range.first + it].id}:${range.first + it}" }) { n ->
            val ep = episodes[range.first + n]
            SongResultRow(ep, currentSongId, playing, onClick = { onSong(ep) }, onMore = { onSongMore(ep) })
            if (n < range.count() - 1) RowDivider()
        }
    }
    // What extensions add for this search (search.provide), under Spotify's results.
    if (filter != SearchFilter.Profiles && text.isNotBlank()) item(key = "search:extensions") {
        com.music.spotui.extensions.ExtensionBlocks(
            app.myspoti.extensions.Hook.SEARCH, app.myspoti.extensions.Surface.SEARCH,
            kotlinx.serialization.json.buildJsonObject { put("query", kotlinx.serialization.json.JsonPrimitive(text.trim())) }.toString(),
            delayMs = 400,
        )
    }
}

/** A section's heading (when the page is sectioned) and its rows; nothing when it has none. */
private fun LazyListScope.section(
    id: String,
    titled: Boolean,
    title: @Composable () -> String,
    count: Int,
    rows: LazyListScope.(IntRange) -> Unit,
) {
    if (count == 0) return
    if (titled) item(key = "section:$id") { SectionHeader(title()) }
    rows(0 until count)
}

/** A song hit: the shared song row, swipe-to-queue as in the Classic list. */
@Composable
private fun SongResultRow(
    song: SongsModel,
    currentSongId: Int,
    playing: Boolean,
    onClick: () -> Unit,
    onMore: () -> Unit,
) {
    val current = song.id == currentSongId
    SwipeToQueue(song) {
        ImmersiveSongRow(
            song = song,
            playing = current && playing,
            current = current,
            onClick = onClick,
            onMore = onMore,
            modifier = Modifier.background(Immersive.Page),
            showAlbum = true,
        )
    }
}

/** The best song for the query, promoted: a big cover, the title, and Play / more. */
@Composable
private fun TopResultCard(song: SongsModel, onPlay: () -> Unit, onMore: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Immersive.Gutter)
            .padding(top = 18.dp, bottom = 8.dp),
    ) {
        Text(text = stringResource(R.string.immersive_screens_top_result), style = Immersive.Title)
        Spacer(Modifier.height(10.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.immersiveClickable(onClick = onPlay, onLongClick = onMore),
        ) {
            ImmersiveCover(song.coverUri, Modifier.size(72.dp), Immersive.CoverMedium)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(text = song.title, style = Immersive.Headline, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                Text(text = song.singer, style = Immersive.Subhead, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            ImmersiveMoreButton(onClick = onMore, tint = Immersive.Text, iconSize = 24.dp)
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ImmersiveButton(
                text = stringResource(R.string.immersive_shell_play),
                onClick = onPlay,
                icon = Icons.Rounded.PlayArrow,
            )
        }
    }
}
