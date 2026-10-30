/*
 * Adapted from BitChord (https://github.com/kushagrasinghx/BitChord), GPL-3.0-or-later,
 * sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/screens/LibraryScreen.kt and
 * app/src/main/java/com/music/bitchord/ui/screens/LibraryPhoneParts.kt at edc1e6f.
 * Modified for Myspoti, 2026-10.
 */
package com.music.spotui.ui.immersive.screens

import com.music.spotui.ui.immersive.shell.immersiveHazeSource
import com.music.spotui.ui.immersive.shell.immersiveChromeScroll
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import app.myspoti.engine.R
import com.music.spotui.data.api.Api
import com.music.spotui.data.api.Response
import com.music.spotui.data.entity.AccountModel
import com.music.spotui.data.entity.ArtistsModel
import com.music.spotui.data.entity.LibraryEntry
import com.music.spotui.data.local.LibraryOrder
import com.music.spotui.data.preferences.isLibraryGridView
import com.music.spotui.data.preferences.setLibraryGridView
import com.music.spotui.ui.components.FastScrollbar
import com.music.spotui.ui.components.pressHint
import com.music.spotui.ui.immersive.Immersive
import com.music.spotui.ui.immersive.components.ImmersiveCover
import com.music.spotui.ui.immersive.components.ImmersiveMessageState
import com.music.spotui.ui.immersive.components.ImmersiveMoreButton
import com.music.spotui.ui.immersive.components.ImmersiveSearchField
import com.music.spotui.ui.immersive.components.SectionHeader
import com.music.spotui.ui.immersive.components.immersiveClickable
import com.music.spotui.ui.immersive.components.immersiveLibrarySkeleton
import com.music.spotui.ui.immersive.components.immersiveThumbnailBorder
import com.music.spotui.ui.immersive.shell.ImmersiveBarAction
import com.music.spotui.ui.immersive.shell.ImmersiveLargeTitle
import com.music.spotui.ui.immersive.shell.ImmersiveTopBar
import com.music.spotui.ui.immersive.shell.immersiveTopContentPadding
import com.music.spotui.ui.immersive.shell.rememberTopBarCollapse
import com.music.spotui.ui.navigation.Routes
import com.music.spotui.ui.navigation.TabReselect
import com.music.spotui.ui.navigation.albumRoute
import com.music.spotui.ui.navigation.artistRoute
import com.music.spotui.ui.navigation.folderRoute
import com.music.spotui.ui.navigation.localPlaylistRoute
import com.music.spotui.ui.navigation.playlistRoute
import com.music.spotui.ui.screens.ContextViewModel
import com.music.spotui.ui.screens.LOCAL_PLAYLIST_PREFIX
import com.music.spotui.ui.screens.LibraryCreateSheet
import com.music.spotui.ui.screens.LibraryEntryCover
import com.music.spotui.ui.screens.LibraryItemSheet
import com.music.spotui.ui.viewmodel.LibraryViewModel
import com.music.spotui.data.local.LibraryChip
import com.music.spotui.data.local.LibraryChips
import androidx.compose.runtime.saveable.rememberSaveable

/** The chips under the title, in the Classic order; their logic is the shared [LibraryChips]. */
private val LibraryChipLabels = listOf(
    LibraryChip.PLAYLISTS to R.string.library_chip_playlists,
    LibraryChip.ARTISTS to R.string.library_chip_artists,
    LibraryChip.ALBUMS to R.string.library_chip_albums,
    LibraryChip.SONGS to R.string.library_chip_songs,
)

/**
 * Your Library in the Immersive look: the page's large title, the sort and list/grid controls,
 * kind pills and an optional search, then the saved playlists and albums (Liked Songs, Local
 * Files, Downloaded and the phone's own playlists pinned on top) and the followed artists. The
 * data, ordering, drag-to-reorder, sheets and re-tap-to-top are those of the Classic screen.
 */
@Composable
fun ImmersiveLibraryScreen(navController: NavController) {
    val vm: LibraryViewModel = viewModel()
    val entries by vm.entries.collectAsState()
    val account by vm.account.collectAsState()
    val followedArtists by vm.followedArtists.collectAsState()
    val context = LocalContext.current

    // The first lists of the library load in the background, ready before a tap.
    LaunchedEffect((entries as? Response.Success)?.data?.take(8)?.map { it.spotifyId }) {
        val top = (entries as? Response.Success)?.data.orEmpty().take(8).mapNotNull { e ->
            when {
                e.isFolder || e.spotifyId.startsWith(LOCAL_PLAYLIST_PREFIX) -> null
                e.spotifyId in setOf(Api.HomeCache.LOCAL_FILES_ID, Api.HomeCache.DOWNLOADS_ID, Api.HomeCache.EPISODES_ID) -> null
                e.spotifyId == Api.HomeCache.LIKED_SONGS_ID -> "liked" to ""
                e.isPlaylist -> "playlist" to e.spotifyId
                else -> "album" to e.spotifyId
            }
        }
        if (top.isNotEmpty()) ContextViewModel.prefetchSoon(top)
    }

    var showAccount by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    var sorting by remember { mutableStateOf(false) }
    var itemMenu by remember { mutableStateOf<LibraryEntry?>(null) }
    // Rows or a grid of covers, persisted across runs.
    var gridView by remember { mutableStateOf(isLibraryGridView(context)) }
    var searchActive by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var kind by rememberSaveable { mutableStateOf(LibraryChip.ALL) }

    if (showAccount) {
        ImmersiveAccountSheet(
            account = (account as? Response.Success)?.data ?: AccountModel(),
            navController = navController,
            onDismiss = { showAccount = false },
        )
    }
    if (creating) LibraryCreateSheet(navController, onDismiss = { creating = false }, onLibraryChanged = { vm.load() })
    if (sorting) ImmersiveLibrarySortSheet(onDismiss = { sorting = false })
    itemMenu?.let { LibraryItemSheet(it, onDismiss = { itemMenu = null }, onChanged = { vm.load() }) }

    val loading = entries is Response.Loading
    val (allEntries, pinned) = libraryEntries(entries)
    // The kind and search filters only run again when their inputs change, not on every recomposition.
    val (shownEntries, shownArtists) = remember(kind, allEntries, followedArtists, searchQuery) {
        val (byKind, artists) = LibraryChips.apply(kind, allEntries, followedArtists, LIBRARY_SONG_IDS)
        if (searchQuery.isBlank()) byKind to artists
        else byKind.filter {
            it.name.contains(searchQuery, ignoreCase = true) || it.subtitle.contains(searchQuery, ignoreCase = true)
        } to artists.filter { it.name.contains(searchQuery, ignoreCase = true) }
    }
    // Only the whole library, unfiltered, can be put in a new order.
    val reorderLevel = if (kind == LibraryChip.ALL && searchQuery.isBlank()) LibraryOrder.ROOT else null

    val title = stringResource(R.string.navigationbar_musicappitems_yourlibrary_title)
    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()
    val header: @Composable () -> Unit = {
        LibraryHeader(
            title = title,
            gridView = gridView,
            onToggleGrid = {
                gridView = !gridView
                setLibraryGridView(context, gridView)
            },
            onSort = { sorting = true },
            searchActive = searchActive,
            query = searchQuery,
            onQuery = { searchQuery = it },
            kind = kind,
            onKind = { kind = LibraryChips.toggle(kind, it) },
        )
    }
    val avatar = (account as? Response.Success)?.data

    val refreshing by vm.refreshing.collectAsState()
    Box(Modifier.fillMaxSize().background(Immersive.Page)) {
      // Pull down to read the library again, as in the Classic look.
      androidx.compose.material3.pulltorefresh.PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = { vm.refresh() },
        modifier = Modifier.fillMaxSize(),
      ) {
        if (gridView && !loading) {
            LibraryGrid(
                gridState = gridState,
                header = header,
                entries = shownEntries,
                artists = shownArtists,
                loadFailed = entries !is Response.Success,
                onRetry = { vm.load() },
                navController = navController,
                reorderLevel = reorderLevel,
                pinned = pinned,
            )
        } else {
            LibraryList(
                listState = listState,
                header = header,
                loading = loading,
                entries = shownEntries,
                artists = shownArtists,
                loadFailed = !loading && entries !is Response.Success,
                onRetry = { vm.load() },
                navController = navController,
                reorderLevel = reorderLevel,
                pinned = pinned,
                onMenu = { itemMenu = it },
            )
        }
      }
        val collapse = if (gridView && !loading) gridCollapse(gridState) else rememberTopBarCollapse(listState)
        ImmersiveTopBar(title, collapse) {
            ImmersiveBarAction(
                Icons.Rounded.Add,
                stringResource(R.string.create_playlist_content_description),
                onClick = { creating = true },
            )
            ImmersiveBarAction(
                Icons.Rounded.Search,
                stringResource(R.string.library_search_text_hint),
                onClick = {
                    searchActive = !searchActive
                    if (!searchActive) searchQuery = ""
                },
                tint = if (searchActive) Immersive.Accent else Immersive.Text,
            )
            // The whole 44 dp slot takes the tap, not only the 36 dp disc in it.
            Box(
                Modifier.size(44.dp).immersiveClickable(onClick = { showAccount = true }),
                contentAlignment = Alignment.Center,
            ) {
                AvatarDisc(
                    url = avatar?.imageUrl,
                    initial = avatar?.name?.trim()?.firstOrNull()?.uppercase() ?: "•",
                    contentDescription = stringResource(R.string.account_title),
                    onClick = null,
                )
            }
        }
    }

    // Re-tap on the Library tab: an animated scroll back to the top of the rows already loaded.
    LaunchedEffect(gridView, loading) {
        TabReselect.library.collect {
            if (gridView && !loading) {
                if (gridState.firstVisibleItemIndex != 0 || gridState.firstVisibleItemScrollOffset != 0) gridState.animateScrollToItem(0)
            } else if (listState.firstVisibleItemIndex != 0 || listState.firstVisibleItemScrollOffset != 0) {
                listState.animateScrollToItem(0)
            }
        }
    }
}

/** The library rows that hold songs rather than a playlist, for [LibraryChips.apply]. */
private val LIBRARY_SONG_IDS = setOf(
    Api.HomeCache.LIKED_SONGS_ID, Api.HomeCache.LOCAL_FILES_ID, Api.HomeCache.DOWNLOADS_ID,
    com.music.spotui.radio.RadioFeaturePref.LIBRARY_ENTRY_ID,
)

/** The collapse fraction (0..1) of a grid page, as [rememberTopBarCollapse] gives a list's. */
@Composable
internal fun gridCollapse(state: LazyGridState): () -> Float {
    val distancePx = with(LocalDensity.current) { 40.dp.toPx() }
    return remember(state, distancePx) {
        val fraction: () -> Float = {
            if (state.firstVisibleItemIndex > 0) 1f
            else (state.firstVisibleItemScrollOffset / distancePx).coerceIn(0f, 1f)
        }
        fraction
    }
}

/**
 * The library's entries with the app's own folders in: Liked Songs, Local Files and Downloaded
 * stay on top, then the phone's own playlists, the rest following the chosen sort. The second
 * value is how many entries at the top never move.
 */
@Composable
private fun libraryEntries(entries: Response<List<LibraryEntry>>): Pair<List<LibraryEntry>, Int> {
    val localName = stringResource(R.string.local_files_title)
    val downloadsName = stringResource(R.string.collection_downloaded)
    val localSubtitle = stringResource(R.string.assisted_curation_search_playlist) + " • " + stringResource(R.string.local_playlist_subtitle)
    val likedName = stringResource(R.string.collection_liked_songs_title)
    val radioName = stringResource(R.string.live_radio_title)
    val packageName = LocalContext.current.packageName
    val hasDownloads = com.music.spotui.data.offline.OfflineStore.tracks.isNotEmpty() ||
        com.music.spotui.data.offline.OfflineDownloader.requests.isNotEmpty()
    val radioOn = com.music.spotui.radio.RadioFeaturePref.on
    val base = (entries as? Response.Success)?.data ?: emptyList()
    val rootPinned = base.indexOfFirst { it.spotifyId == Api.HomeCache.LIKED_SONGS_ID } + 2 +
        (if (radioOn) 1 else 0) + (if (hasDownloads) 1 else 0)
    val all = base.map {
        if (it.spotifyId == Api.HomeCache.LIKED_SONGS_ID) it.copy(name = likedName, subtitle = "") else it
    }.let { list ->
        val local = LibraryEntry(
            spotifyId = Api.HomeCache.LOCAL_FILES_ID, name = localName, subtitle = "",
            coverUri = "android.resource://$packageName/drawable/local_files_cover", isPlaylist = true,
        )
        // "Live Radio" (radio/ module), right under Local Files — Settings > Playback toggle.
        val radio = LibraryEntry(
            spotifyId = com.music.spotui.radio.RadioFeaturePref.LIBRARY_ENTRY_ID, name = radioName, subtitle = "",
            coverUri = "android.resource://$packageName/drawable/live_radio_cover", isPlaylist = true,
        )
        val downloads = LibraryEntry(
            spotifyId = Api.HomeCache.DOWNLOADS_ID, name = downloadsName, subtitle = "", coverUri = "", isPlaylist = true,
        )
        val at = list.indexOfFirst { it.spotifyId == Api.HomeCache.LIKED_SONGS_ID } + 1
        // The phone's own playlists: files of this phone, not on the account.
        val localLists = com.music.spotui.data.local.LocalPlaylists.list().map { p ->
            LibraryEntry(
                spotifyId = LOCAL_PLAYLIST_PREFIX + p.id, name = p.name,
                subtitle = localSubtitle, coverUri = "", isPlaylist = true,
            )
        }
        list.take(at) + local + (if (radioOn) listOf(radio) else emptyList()) +
            (if (hasDownloads) listOf(downloads) else emptyList()) + localLists + list.drop(at)
    }.let { list ->
        LibraryOrder.version
        LibraryOrder.arrange(list, LibraryOrder.sort, LibraryOrder.custom(LibraryOrder.ROOT), rootPinned)
    }
    return all to rootPinned
}

/** Title, sort and view controls, the optional search field and the kind pills: one list item. */
@Composable
private fun LibraryHeader(
    title: String,
    gridView: Boolean,
    onToggleGrid: () -> Unit,
    onSort: () -> Unit,
    searchActive: Boolean,
    query: String,
    onQuery: (String) -> Unit,
    kind: LibraryChip,
    onKind: (LibraryChip) -> Unit,
) {
    Column {
        // The title itself scrolls the list to its top.
        Box(
            Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                TabReselect.emit(Routes.Library.route)
            },
        ) { ImmersiveLargeTitle(title) }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = Immersive.Gutter).padding(bottom = 2.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.immersiveClickable(onClick = onSort).padding(vertical = 6.dp),
            ) {
                Icon(Icons.Rounded.SwapVert, contentDescription = null, tint = Immersive.Text, modifier = Modifier.size(18.dp))
                Text(
                    text = stringResource(libraryOrderLabel(LibraryOrder.sort)),
                    style = Immersive.Subhead.copy(color = Immersive.Text, fontWeight = FontWeight.SemiBold),
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            Icon(
                painter = painterResource(if (gridView) R.drawable.ic_view_list else R.drawable.ic_view_grid),
                contentDescription = stringResource(if (gridView) R.string.list_view else R.string.grid_view),
                tint = Immersive.Text,
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onToggleGrid)
                    .padding(7.dp),
            )
        }
        AnimatedVisibility(
            visible = searchActive,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            ImmersiveSearchField(
                query = query,
                onQueryChange = onQuery,
                onSubmit = {},
                placeholder = stringResource(R.string.library_search_text_hint),
                modifier = Modifier.padding(horizontal = Immersive.Gutter).padding(top = 4.dp, bottom = 4.dp),
            )
        }
        ImmersiveChipRow {
            LibraryChipLabels.forEach { (k, label) ->
                ImmersiveChip(stringResource(label), selected = k == kind) { onKind(k) }
            }
        }
    }
}

/** Lays the content out [inset] wider on each side, for a full-width row inside a padded grid. */
internal fun Modifier.immersiveBleed(inset: Dp): Modifier = layout { measurable, constraints ->
    val px = inset.roundToPx()
    val width = constraints.maxWidth + 2 * px
    val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
    layout(constraints.maxWidth, placeable.height) { placeable.place(-px, 0) }
}

/** Albums, playlists and folders start loading under the finger. */
private fun prefetchLibraryEntry(entry: LibraryEntry) = when {
    entry.isFolder || entry.spotifyId.startsWith(LOCAL_PLAYLIST_PREFIX) -> Unit
    entry.spotifyId == Api.HomeCache.LOCAL_FILES_ID || entry.spotifyId == Api.HomeCache.DOWNLOADS_ID || entry.spotifyId == Api.HomeCache.EPISODES_ID -> Unit
    entry.spotifyId == com.music.spotui.radio.RadioFeaturePref.LIBRARY_ENTRY_ID -> Unit
    entry.spotifyId == Api.HomeCache.LIKED_SONGS_ID -> ContextViewModel.prefetch("liked", "")
    entry.isPlaylist -> ContextViewModel.prefetch("playlist", entry.spotifyId)
    else -> ContextViewModel.prefetch("album", entry.spotifyId)
}

private fun openLibraryEntry(entry: LibraryEntry, navController: NavController) {
    if (entry.isFolder) navController.navigate(folderRoute(entry.folderUri, entry.name))
    else if (entry.spotifyId.startsWith(LOCAL_PLAYLIST_PREFIX)) navController.navigate(localPlaylistRoute(entry.spotifyId.removePrefix(LOCAL_PLAYLIST_PREFIX)))
    else if (entry.spotifyId == Api.HomeCache.DOWNLOADS_ID) navController.navigate(Routes.Downloads.route)
    else if (entry.spotifyId == Api.HomeCache.LOCAL_FILES_ID) navController.navigate(Routes.LocalFiles.route)
    else if (entry.spotifyId == com.music.spotui.radio.RadioFeaturePref.LIBRARY_ENTRY_ID) navController.navigate(Routes.LiveRadio.route)
    else if (entry.spotifyId == Api.HomeCache.LIKED_SONGS_ID) navController.navigate(Routes.Liked.route)
    else if (entry.spotifyId == Api.HomeCache.EPISODES_ID) navController.navigate(Routes.Episodes.route)
    else if (entry.isPlaylist) navController.navigate(playlistRoute(entry.spotifyId, entry.name))
    else navController.navigate(albumRoute(entry.name, entry.artists, entry.spotifyId))
}

/** The rows layout. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun LibraryList(
    listState: LazyListState,
    header: @Composable () -> Unit,
    loading: Boolean,
    entries: List<LibraryEntry>,
    artists: List<ArtistsModel>,
    loadFailed: Boolean,
    onRetry: () -> Unit,
    navController: NavController,
    reorderLevel: String?,
    pinned: Int,
    onMenu: (LibraryEntry) -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    // The rows as shown, moved live under the finger; saved when the finger lifts. One state
    // for the screen's life: the drag gestures hold on to it.
    var rows by remember { mutableStateOf(entries.distinctBy { LibraryOrder.key(it) }) }
    var dragKey by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableStateOf(0f) }
    val level by rememberUpdatedState(reorderLevel)
    val fixed by rememberUpdatedState(pinned)
    LaunchedEffect(entries) {
        if (dragKey == null) rows = entries.distinctBy { LibraryOrder.key(it) }
    }

    // Moves the dragged row to the row its centre is over (pinned rows excluded).
    fun follow() {
        val key = dragKey ?: return
        val info = listState.layoutInfo.visibleItemsInfo
        val dragged = info.firstOrNull { it.key == key } ?: return
        val centre = dragged.offset + dragOffset + dragged.size / 2f
        val target = info.firstOrNull { it.key != key && it.key is String && centre.toInt() in it.offset until it.offset + it.size } ?: return
        val from = rows.indexOfFirst { LibraryOrder.key(it) == key }
        val to = rows.indexOfFirst { LibraryOrder.key(it) == target.key }
        if (from < fixed || to < fixed || from < 0 || to < 0) return
        // Keeps the list still when the first visible row is involved.
        if (listState.firstVisibleItemIndex == from || listState.firstVisibleItemIndex == to) {
            listState.requestScrollToItem(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
        }
        rows = rows.toMutableList().apply { add(to, removeAt(from)) }
        dragOffset += dragged.offset - target.offset
    }

    fun finish() {
        val at = level
        if (dragKey != null && at != null) LibraryOrder.saveCustom(at, rows.drop(fixed).map { LibraryOrder.key(it) })
        dragKey = null
        dragOffset = 0f
    }

    // Near the top or bottom edge, the list scrolls by itself while a row is dragged.
    LaunchedEffect(dragKey) {
        if (dragKey == null) return@LaunchedEffect
        val edge = with(density) { 72.dp.toPx() }
        while (dragKey != null) {
            val info = listState.layoutInfo
            val dragged = info.visibleItemsInfo.firstOrNull { it.key == dragKey }
            if (dragged != null) {
                val top = dragged.offset + dragOffset
                val bottom = top + dragged.size
                val speed = when {
                    bottom > info.viewportEndOffset - edge -> 18f
                    top < info.viewportStartOffset + edge -> -18f
                    else -> 0f
                }
                if (speed != 0f) {
                    val moved = listState.scrollBy(speed)
                    dragOffset += moved
                    follow()
                }
            }
            withFrameNanos { }
        }
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(top = immersiveTopContentPadding(), bottom = Immersive.BottomChrome),
            modifier = Modifier.fillMaxSize().immersiveHazeSource().immersiveChromeScroll(),
        ) {
            item(key = "library:header") { header() }
            if (loading) {
                immersiveLibrarySkeleton()
                return@LazyColumn
            }
            if (entries.isEmpty() && artists.isEmpty() && !loadFailed) {
                item(key = "library:empty") { ImmersiveMessageState(stringResource(R.string.library_empty)) }
            }
            itemsIndexed(rows, key = { _, e -> LibraryOrder.key(e) }) { index, entry ->
                val key = LibraryOrder.key(entry)
                val dragged = dragKey == key
                val movable = reorderLevel != null && index >= pinned
                LibraryEntryRow(
                    entry = entry,
                    onMenu = { onMenu(entry) },
                    modifier = Modifier
                        // Same modifier chain while dragging: changing its shape would rebuild the
                        // gesture detector below and cancel the drag as soon as it starts.
                        .animateItem(
                            fadeInSpec = null, fadeOutSpec = null,
                            placementSpec = if (dragged) null else spring(
                                stiffness = Spring.StiffnessMediumLow,
                                visibilityThreshold = IntOffset.VisibilityThreshold,
                            ),
                        )
                        .zIndex(if (dragged) 1f else 0f)
                        .graphicsLayer {
                            translationY = if (dragged) dragOffset else 0f
                            if (dragged) { scaleX = 1.03f; scaleY = 1.03f }
                        }
                        .background(if (dragged) Immersive.Raised else androidx.compose.ui.graphics.Color.Transparent, Immersive.CoverMedium)
                        .pressHint { prefetchLibraryEntry(entry) }
                        .immersiveClickable(onClick = { openLibraryEntry(entry, navController) }, pressedScale = 0.98f)
                        // Inside the clickable, so a long press that ends without moving opens nothing.
                        .then(
                            if (!movable) Modifier else Modifier.pointerInput(key) {
                                try {
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            dragKey = key
                                            dragOffset = 0f
                                        },
                                        onDrag = { change, amount -> change.consume(); dragOffset += amount.y; follow() },
                                        onDragEnd = { finish() },
                                        onDragCancel = { finish() },
                                    )
                                } finally {
                                    if (dragKey == key) finish()
                                }
                            },
                        ),
                )
            }
            // Artists followed on the account.
            if (artists.isNotEmpty()) {
                item(key = "library:artists-header") { SectionHeader(stringResource(R.string.song_dna_search_chip_followed_artists)) }
                itemsIndexed(artists, key = { _, a -> "artist:${a.id.ifBlank { a.name }}" }) { _, artist ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .immersiveClickable(onClick = { navController.navigate(artistRoute(artist.name, artist.id)) }, pressedScale = 0.98f)
                            .padding(horizontal = Immersive.Gutter, vertical = 4.dp),
                    ) {
                        ImmersiveCover(artist.coverUri, Modifier.size(52.dp), CircleShape)
                        Column(Modifier.padding(start = 14.dp)) {
                            Text(
                                text = artist.name,
                                style = Immersive.Body.copy(fontWeight = FontWeight.Medium),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(text = stringResource(R.string.artist_subtitle), style = Immersive.Subhead)
                        }
                    }
                }
            }
            if (loadFailed) {
                item(key = "library:error") {
                    ImmersiveMessageState(
                        message = stringResource(R.string.library_error),
                        actionLabel = stringResource(R.string.home_retry),
                        onAction = onRetry,
                    )
                }
            }
        }
        FastScrollbar(listState, Modifier.align(Alignment.CenterEnd))
    }
}

/** One row of the library: a cover, the name, the creator or kind, a pin mark and the menu. */
@Composable
private fun LibraryEntryRow(entry: LibraryEntry, onMenu: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Immersive.Gutter, vertical = 4.dp),
    ) {
        val shape = Immersive.CoverSmall
        LibraryEntryCover(
            entry,
            Modifier.size(52.dp).clip(shape).background(Immersive.Raised).immersiveThumbnailBorder(shape),
        )
        Column(modifier = Modifier.padding(start = 14.dp).weight(1f)) {
            Text(
                text = entry.name,
                style = Immersive.Body.copy(fontWeight = FontWeight.Medium),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (entry.pinned) {
                    Icon(
                        Icons.Rounded.PushPin, contentDescription = null, tint = Immersive.Accent,
                        modifier = Modifier.padding(end = 4.dp).size(12.dp),
                    )
                }
                Text(text = entry.subtitle, style = Immersive.Subhead, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (entry.uri.isNotBlank()) ImmersiveMoreButton(onClick = onMenu)
    }
}

/** The grid layout: square covers with the name and creator underneath, artists as circles. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LibraryGrid(
    gridState: LazyGridState,
    header: @Composable () -> Unit,
    entries: List<LibraryEntry>,
    artists: List<ArtistsModel>,
    loadFailed: Boolean,
    onRetry: () -> Unit,
    navController: NavController,
    reorderLevel: String?,
    pinned: Int,
) {
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    // Same as the list view, in two dimensions: the tile follows the finger and takes the place
    // of the tile its centre is over.
    var rows by remember { mutableStateOf(entries.distinctBy { LibraryOrder.key(it) }) }
    var dragKey by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    val level by rememberUpdatedState(reorderLevel)
    val fixed by rememberUpdatedState(pinned)
    LaunchedEffect(entries) {
        if (dragKey == null) rows = entries.distinctBy { LibraryOrder.key(it) }
    }

    fun follow() {
        val key = dragKey ?: return
        val info = gridState.layoutInfo.visibleItemsInfo
        val dragged = info.firstOrNull { it.key == key } ?: return
        val cx = dragged.offset.x + dragOffset.x + dragged.size.width / 2f
        val cy = dragged.offset.y + dragOffset.y + dragged.size.height / 2f
        val target = info.firstOrNull {
            it.key != key && it.key is String &&
                cx >= it.offset.x && cx < it.offset.x + it.size.width && cy >= it.offset.y && cy < it.offset.y + it.size.height
        } ?: return
        val from = rows.indexOfFirst { LibraryOrder.key(it) == key }
        val to = rows.indexOfFirst { LibraryOrder.key(it) == target.key }
        if (from < fixed || to < fixed || from < 0 || to < 0) return
        if (gridState.firstVisibleItemIndex == from || gridState.firstVisibleItemIndex == to) {
            gridState.requestScrollToItem(gridState.firstVisibleItemIndex, gridState.firstVisibleItemScrollOffset)
        }
        rows = rows.toMutableList().apply { add(to, removeAt(from)) }
        dragOffset += Offset((dragged.offset.x - target.offset.x).toFloat(), (dragged.offset.y - target.offset.y).toFloat())
    }

    fun finish() {
        val at = level
        if (dragKey != null && at != null) LibraryOrder.saveCustom(at, rows.drop(fixed).map { LibraryOrder.key(it) })
        dragKey = null
        dragOffset = Offset.Zero
    }

    LaunchedEffect(dragKey) {
        if (dragKey == null) return@LaunchedEffect
        val edge = with(density) { 72.dp.toPx() }
        while (dragKey != null) {
            val info = gridState.layoutInfo
            val dragged = info.visibleItemsInfo.firstOrNull { it.key == dragKey }
            if (dragged != null) {
                val top = dragged.offset.y + dragOffset.y
                val bottom = top + dragged.size.height
                val speed = when {
                    bottom > info.viewportEndOffset - edge -> 18f
                    top < info.viewportStartOffset + edge -> -18f
                    else -> 0f
                }
                if (speed != 0f) {
                    val moved = gridState.scrollBy(speed)
                    dragOffset += Offset(0f, moved)
                    follow()
                }
            }
            withFrameNanos { }
        }
    }

    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Fixed(com.music.spotui.ui.components.windowSize().columns(3, 170.dp)),
        modifier = Modifier.fillMaxSize().immersiveHazeSource().immersiveChromeScroll(),
        contentPadding = PaddingValues(
            start = Immersive.Gutter, end = Immersive.Gutter,
            top = immersiveTopContentPadding(), bottom = Immersive.BottomChrome,
        ),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item(key = "library:header", span = { GridItemSpan(maxLineSpan) }) {
            Box(Modifier.immersiveBleed(Immersive.Gutter)) { header() }
        }
        if (entries.isEmpty() && artists.isEmpty() && !loadFailed) {
            item(key = "library:empty", span = { GridItemSpan(maxLineSpan) }) {
                ImmersiveMessageState(stringResource(R.string.library_empty))
            }
        }
        itemsIndexed(rows, key = { _, e -> LibraryOrder.key(e) }) { index, entry ->
            val key = LibraryOrder.key(entry)
            val dragged = dragKey == key
            val movable = reorderLevel != null && index >= pinned
            val shape = Immersive.CoverMedium
            Column(
                modifier = Modifier
                    // Same modifier chain while dragging (see the list view).
                    .animateItem(
                        fadeInSpec = null, fadeOutSpec = null,
                        placementSpec = if (dragged) null else spring(
                            stiffness = Spring.StiffnessMediumLow,
                            visibilityThreshold = IntOffset.VisibilityThreshold,
                        ),
                    )
                    .zIndex(if (dragged) 1f else 0f)
                    .graphicsLayer {
                        translationX = if (dragged) dragOffset.x else 0f
                        translationY = if (dragged) dragOffset.y else 0f
                        if (dragged) { scaleX = 1.06f; scaleY = 1.06f }
                    }
                    .pressHint { prefetchLibraryEntry(entry) }
                    .immersiveClickable(onClick = { openLibraryEntry(entry, navController) })
                    .then(
                        if (!movable) Modifier else Modifier.pointerInput(key) {
                            try {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        dragKey = key
                                        dragOffset = Offset.Zero
                                    },
                                    onDrag = { change, amount -> change.consume(); dragOffset += amount; follow() },
                                    onDragEnd = { finish() },
                                    onDragCancel = { finish() },
                                )
                            } finally {
                                if (dragKey == key) finish()
                            }
                        },
                    ),
            ) {
                LibraryEntryCover(
                    entry,
                    Modifier.fillMaxWidth().aspectRatio(1f).clip(shape).background(Immersive.Raised).immersiveThumbnailBorder(shape),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = entry.name,
                    style = Immersive.Subhead.copy(color = Immersive.Text, fontWeight = FontWeight.Medium),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (entry.subtitle.isNotBlank()) {
                    Text(text = entry.subtitle, style = Immersive.Caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (artists.isNotEmpty()) {
            item(key = "library:artists-header", span = { GridItemSpan(maxLineSpan) }) {
                Box(Modifier.immersiveBleed(Immersive.Gutter)) { SectionHeader(stringResource(R.string.song_dna_search_chip_followed_artists)) }
            }
            itemsIndexed(artists, key = { _, a -> "artist:${a.id.ifBlank { a.name }}" }) { _, artist ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.immersiveClickable(onClick = { navController.navigate(artistRoute(artist.name, artist.id)) }),
                ) {
                    ImmersiveCover(artist.coverUri, Modifier.fillMaxWidth().aspectRatio(1f), CircleShape)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = artist.name,
                        style = Immersive.Subhead.copy(color = Immersive.Text, fontWeight = FontWeight.Medium),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                    )
                    Text(text = stringResource(R.string.artist_subtitle), style = Immersive.Caption, textAlign = TextAlign.Center)
                }
            }
        }
        if (loadFailed) {
            item(key = "library:error", span = { GridItemSpan(maxLineSpan) }) {
                ImmersiveMessageState(
                    message = stringResource(R.string.library_error),
                    actionLabel = stringResource(R.string.home_retry),
                    onAction = onRetry,
                )
            }
        }
    }
}
