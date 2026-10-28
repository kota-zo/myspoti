/*
 * Adapted from BitChord (https://github.com/kushagrasinghx/BitChord), GPL-3.0-or-later,
 * app/src/main/java/com/music/bitchord/ui/screens/DetailScreen.kt at edc1e6f. Modified for Myspoti, 2026-10.
 */
package com.music.spotui.ui.immersive.screens

import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.foundation.lazy.itemsIndexed
import com.music.spotui.ui.immersive.motion.immersiveItemPlacement
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import app.myspoti.engine.R
import app.myspoti.host.android.MyspotiRuntime
import com.music.spotui.data.api.Response
import com.music.spotui.data.entity.ContextPageModel
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.data.local.LocalPlaylists
import com.music.spotui.data.offline.OfflineCollections
import com.music.spotui.data.offline.OfflineStatus
import com.music.spotui.data.offline.OfflineStore
import com.music.spotui.di.Graph
import com.music.spotui.di.LocalPlayer
import com.music.spotui.di.Palette
import com.music.spotui.di.SongPlayer
import com.music.spotui.ui.components.DownloadAsk
import com.music.spotui.ui.components.FastScrollbar
import com.music.spotui.ui.components.matchesFind
import com.music.spotui.ui.immersive.Immersive
import com.music.spotui.ui.immersive.components.ImmersiveButton
import com.music.spotui.ui.immersive.components.ImmersiveCircleButton
import com.music.spotui.ui.immersive.components.ImmersiveCover
import com.music.spotui.ui.immersive.components.ImmersiveExplicitBadge
import com.music.spotui.ui.immersive.components.ImmersiveListRow
import com.music.spotui.ui.immersive.components.ImmersiveMessageState
import com.music.spotui.ui.immersive.components.ImmersiveRowDividerInset
import com.music.spotui.ui.immersive.components.ImmersiveSearchField
import com.music.spotui.ui.immersive.components.ImmersiveShelf
import com.music.spotui.ui.immersive.components.ImmersiveShelfCard
import com.music.spotui.ui.immersive.components.ImmersiveShimmerBox
import com.music.spotui.ui.immersive.components.ImmersiveSongRow
import com.music.spotui.ui.immersive.components.SectionHeader
import com.music.spotui.ui.immersive.components.immersiveClickable
import com.music.spotui.ui.immersive.components.immersiveDetailSkeleton
import com.music.spotui.ui.immersive.shell.ImmersiveBarAction
import com.music.spotui.ui.immersive.shell.ImmersiveTopBar
import com.music.spotui.ui.immersive.shell.immersiveHazeSource
import com.music.spotui.ui.immersive.shell.immersiveChromeScroll
import com.music.spotui.ui.immersive.shell.immersiveTopContentPadding
import com.music.spotui.ui.navigation.albumRoute
import com.music.spotui.ui.navigation.artistRoute
import com.music.spotui.ui.navigation.profileRoute
import com.music.spotui.ui.screens.ContextViewModel
import com.music.spotui.ui.screens.SmartShuffle
import com.music.spotui.ui.screens.TrackOrder
import com.music.spotui.ui.screens.TrackSort
import com.music.spotui.ui.viewmodel.PlayerViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The framed sleeve on a release page, as a fraction of the page width. */
private const val SLEEVE_FRACTION = 0.76f

/** ...and of its height, so a landscape window still shows the title under it. */
private const val SLEEVE_MAX_HEIGHT = 0.5f

private val SleeveShape = RoundedCornerShape(12.dp)
private val SleeveShadow = 18.dp

/** The inset the header text shares. */
private val HeaderGutter = Immersive.Gutter + 8.dp

/** Where the divider of a numbered row starts: clear of the track number. */
private val NumberedDividerInset = Immersive.Gutter + 24.dp + Immersive.Gutter

/** Where the find field sits once open: right under the header, which is item zero. */
private const val FIND_ITEM_INDEX = 1

/**
 * Album / playlist / Liked Songs page of the Immersive look: a large centred sleeve over a wash of
 * its own colour, the title, the owner in the accent, the meta line, Play and Shuffle, a row of
 * round buttons (save, download, smart shuffle) and the track list. The data and every action are
 * the Classic page's (same [ContextViewModel], same sheets).
 *
 * @param type "album", "playlist", "liked" or "liked-artist", as for the Classic page.
 */
@Composable
fun ImmersiveContextScreen(navController: NavController, type: String, id: String) {
    val vm: ContextViewModel = viewModel(key = "$type:$id")
    // During composition, so a cached page is there for the very first frame.
    remember(type, id) { vm.load(type, id) }
    val resp by vm.page.collectAsState()
    BoxWithConstraints(Modifier.fillMaxSize().background(Immersive.Page)) {
        val sleeve = albumSleeve(maxWidth, maxHeight)
        when (val r = resp) {
            is Response.Loading -> ContextLoading(navController, sleeve)
            is Response.Error -> {
                Box(Modifier.fillMaxSize()) {
                    ImmersiveMessageState(
                        message = r.error,
                        actionLabel = stringResource(R.string.retry_button),
                        onAction = { vm.reload() },
                        modifier = Modifier.padding(top = immersiveTopContentPadding()),
                    )
                    ImmersiveTopBar(title = "", collapse = { 0f }, onBack = { navController.navigateUp() })
                }
            }
            is Response.Success -> ImmersiveContextContent(navController, vm, r.data, sleeve)
        }
    }
}

/** The sleeve's side on a page of this size. */
internal fun albumSleeve(maxWidth: Dp, maxHeight: Dp): Dp = (maxWidth * SLEEVE_FRACTION).coerceAtMost(maxHeight * SLEEVE_MAX_HEIGHT)

/** Nothing is known of the page yet: the sleeve and the track list stand as shimmer. */
@Composable
internal fun ContextLoading(navController: NavController, sleeve: Dp) {
    val listState = rememberLazyListState()
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            userScrollEnabled = false,
            contentPadding = PaddingValues(top = immersiveTopContentPadding(), bottom = Immersive.BottomChrome),
            modifier = Modifier.fillMaxSize().immersiveHazeSource(),
        ) {
            item(key = "skeleton:context:header") {
                Column(Modifier.fillMaxWidth().padding(bottom = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    ImmersiveShimmerBox(Modifier.size(sleeve), SleeveShape)
                    Spacer(Modifier.height(20.dp))
                    ImmersiveShimmerBox(Modifier.fillMaxWidth(0.55f).height(26.dp), RoundedCornerShape(6.dp))
                    Spacer(Modifier.height(10.dp))
                    ImmersiveShimmerBox(Modifier.fillMaxWidth(0.35f).height(16.dp), RoundedCornerShape(6.dp))
                }
            }
            immersiveDetailSkeleton(isArtist = false)
        }
        ImmersiveTopBar(title = "", listState = listState, onBack = { navController.navigateUp() })
    }
}

@Composable
private fun ImmersiveContextContent(navController: NavController, vm: ContextViewModel, p: ContextPageModel, sleeve: Dp) {
    val context = LocalContext.current
    val playerVm: PlayerViewModel = viewModel()
    val currentSongId = playerVm.currentSongId.value
    val playing = playerVm.currentSongPlayingState.value
    val shuffle = playerVm.shuffleState.value
    val np by SongPlayer.nowPlaying
    val isLiked = p.type == "liked" || p.type == "liked-artist"
    val isThisContext = np.contextUri == p.uri || (isLiked && np.contextUri.contains(":collection"))
    val contextSaved by vm.contextSaved
    val smart = SmartShuffle.on.value == p.uri
    val tapper = rememberTrackTapper()

    // The wash behind the header: the cover's second colour, the Classic page's own extraction.
    var tintTarget by remember { mutableStateOf(Palette.cachedSecond(p.coverUri) ?: Color(0xFF3A3A3A)) }
    LaunchedEffect(p.coverUri) {
        if (p.coverUri.isNotBlank()) Palette().extractSecondColorFromCoverUrl(context, p.coverUri) { tintTarget = it }
    }
    val tint by animateColorAsState(if (isLiked) LikedTint else tintTarget, tween(350), label = "context tint")

    var menuSong by remember { mutableStateOf<SongsModel?>(null) }
    // "Find in Liked Songs / in playlist", opened from the top bar.
    var find by rememberSaveable { mutableStateOf("") }
    var finding by rememberSaveable { mutableStateOf(false) }
    // "Sort by" for the list, kept per list (also after leaving it or restarting the app).
    var sort by remember(p.uri) { mutableStateOf(TrackOrder.load(context, p.uri)) }
    var sortMenu by remember { mutableStateOf(false) }
    var askRemoveDownloads by remember { mutableStateOf(false) }
    var playlistMenu by remember { mutableStateOf(false) }
    var addingSongs by remember { mutableStateOf(false) }
    var albumMenu by remember { mutableStateOf(false) }
    // Collaborative playlists, as on the Classic page: the people, leaving, who added what.
    var showMembers by remember { mutableStateOf(false) }
    var leaveAsk by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { com.music.spotui.data.api.ProfileCache.ensure(context) }
    val ownPlaylist = com.music.spotui.data.api.ProfileCache.let { cache ->
        cache.name
        cache.id?.let { me -> p.ownerUri == "spotify:user:$me" } == true
    }
    val crew = vm.members.value
    if (showMembers && crew.size > 1) com.music.spotui.ui.screens.MembersSheet(
        members = crew, navController = navController,
        ownPlaylist = ownPlaylist, me = com.music.spotui.data.api.ProfileCache.id, canLeave = p.canLeave,
        onChange = { m, collaborator ->
            vm.changeMember(p, m, collaborator) { ok ->
                if (!ok) android.widget.Toast.makeText(context, R.string.playlist_change_refused, android.widget.Toast.LENGTH_SHORT).show()
            }
        },
        onLeave = { showMembers = false; leaveAsk = true },
    ) { showMembers = false }
    if (leaveAsk) androidx.compose.material3.AlertDialog(
        onDismissRequest = { leaveAsk = false },
        containerColor = Immersive.Raised,
        title = { Text(stringResource(R.string.playlist_leave), color = Immersive.Text) },
        text = { Text(stringResource(R.string.playlist_leave_confirm, p.name), color = Immersive.TextSecondary) },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = {
                leaveAsk = false
                com.music.spotui.data.api.LibraryEdits.run(context, null,
                    write = { app.myspoti.host.android.MyspotiRuntime.api.playlists.leave(p.uri) },
                    onDone = { ok -> if (ok) com.music.spotui.data.api.LibraryEdits.reloadSoon() })
                navController.navigateUp()
            }) { Text(stringResource(R.string.playlist_leave_button), color = Immersive.Destructive) }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = { leaveAsk = false }) { Text(stringResource(R.string.cancel), color = Immersive.Text) }
        },
    )
    // Confirmations (and their Undo) float above the list, not inside a menu that closes.
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // The row goes at once, then the removal is written. The snackbar keeps the mistake
    // reversible: Undo adds the track back and the page is read again.
    fun removeFromThisPlaylist(song: SongsModel) {
        val playlistId = p.uri.substringAfterLast(':')
        vm.dropTrack(song)
        com.music.spotui.data.api.SpotifySync.removeTrackFromPlaylist(context, playlistId, song.spotifyTrackId) { ok ->
            if (!ok) {
                vm.reload()
                Toast.makeText(context, context.getString(R.string.playlist_change_refused), Toast.LENGTH_SHORT).show()
            } else scope.launch {
                val chosen = snackbar.showSnackbar(
                    message = context.getString(R.string.removed_from, p.name),
                    actionLabel = context.getString(R.string.undo),
                    withDismissAction = false,
                    // Long: the write has just gone through, the mistake deserves time to be undone.
                    duration = SnackbarDuration.Long,
                )
                if (chosen == SnackbarResult.ActionPerformed) {
                    com.music.spotui.data.api.SpotifySync.addTrackToPlaylist(context, playlistId, song.spotifyTrackId) { back ->
                        if (back) vm.reload()
                        else Toast.makeText(context, context.getString(R.string.playlist_change_refused), Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    if (addingSongs) com.music.spotui.ui.components.AddSongsScreen(p) { added -> addingSongs = false; if (added) vm.reload() }
    if (albumMenu) com.music.spotui.ui.components.AlbumOptionsSheet(p.uri, p.name, known = p) { albumMenu = false }
    if (playlistMenu) com.music.spotui.ui.components.PlaylistOptionsSheet(
        page = p,
        onAddSongs = if (p.type == "playlist" && vm.editableContext.value && !p.isBlend) ({ addingSongs = true }) else null,
        onDismiss = { playlistMenu = false },
        onChanged = { vm.reload() },
        onEdited = { name, description -> vm.showDetails(name, description) },
        onDeleted = { navController.navigateUp() },
        onProfile = { navController.navigate(profileRoute(it, p.ownerName)) },
    )
    if (askRemoveDownloads) AlertDialog(
        onDismissRequest = { askRemoveDownloads = false },
        containerColor = Immersive.Raised,
        title = { Text(stringResource(R.string.playlist_entity_remove_download_dialog_title), style = Immersive.Headline) },
        text = { Text(stringResource(R.string.playlist_entity_remove_download_dialog_description), style = Immersive.Subhead) },
        confirmButton = {
            TextButton(onClick = {
                askRemoveDownloads = false
                OfflineCollections.remove(p.uri)
                Toast.makeText(context, context.getString(R.string.toast_undownload), Toast.LENGTH_SHORT).show()
            }) { Text(stringResource(R.string.playlist_entity_remove_download_dialog_positive_button), style = Immersive.BodyStrong.copy(color = Immersive.Accent)) }
        },
        dismissButton = {
            TextButton(onClick = { askRemoveDownloads = false }) {
                Text(stringResource(R.string.add_to_playlist_button_cancel), style = Immersive.BodyStrong)
            }
        },
    )
    menuSong?.let { sel ->
        val inPlaylist = p.type == "playlist"
        val canRemove = inPlaylist && vm.editableContext.value
        com.music.spotui.ui.immersive.sheets.ImmersiveSongOptionsSheet(
            song = sel,
            navController = navController,
            context = context,
            onDismiss = { menuSong = null },
            // The removal entry only exists on a playlist page the user may change: nowhere else
            // is there a list to take the track out of.
            onRemoveFromPlaylist = if (canRemove) ({ removeFromThisPlaylist(sel) }) else null,
            currentPlaylistId = if (inPlaylist) p.uri.substringAfterLast(':') else null,
        )
    }

    // Liked Songs: a track unliked anywhere leaves the list right away. Derived state:
    // recomputed when the page, the search, the sort or a like changes, while a recomposition
    // with nothing new in them re-sorts nothing (a 900-track list is real work).
    val findable = p.type == "liked" || p.type == "playlist"
    val all by remember(p) {
        derivedStateOf {
            if (isLiked) p.tracks.filter { SongPlayer.library["spotify:track:${it.spotifyTrackId}"] != false } else p.tracks
        }
    }
    val rows by remember(p, find, sort) {
        derivedStateOf {
            val found = if (findable) all.filter { matchesFind(find, it.title, it.singer, it.album) } else all
            if (findable) sort.apply(found) else found
        }
    }
    // Unique and stable for a given list: a playlist can hold the same song twice (the id alone
    // is not unique) and the index changes at every sort (which would lose every item).
    val keys = remember(rows) {
        val seen = HashMap<String, Int>()
        rows.map { s ->
            val id = s.id.toString()
            val n = (seen[id] ?: 0) + 1
            seen[id] = n
            if (n == 1) id else "$id#$n"
        }
    }

    // What the Play pill does: pause / resume this list, else start it (smart shuffle if it is on).
    fun onPlay() = when {
        isThisContext && playing -> SongPlayer.pause()
        isThisContext -> SongPlayer.play()
        p.tracks.isNotEmpty() && SmartShuffle.on.value == p.uri -> vm.playSmart(p, null)
        p.tracks.isNotEmpty() -> vm.play(p, if (shuffle) p.tracks.random() else sort.apply(p.tracks).first(), sort)
        else -> Unit
    }
    fun toggleSmart() {
        if (smart) {
            SmartShuffle.on.value = null
            playerVm.updateShuffleState(false)
        } else {
            SmartShuffle.on.value = p.uri
            Toast.makeText(context, context.getString(R.string.immersive_detail_smart_shuffle_on), Toast.LENGTH_SHORT).show()
            if (isThisContext) vm.playSmart(p, p.tracks.firstOrNull { it.id == currentSongId })
        }
    }
    // What the Shuffle pill does: start the list shuffled, or, while it plays, step through
    // shuffle → smart shuffle (playlists, Liked Songs) → off, as the Classic shuffle icon does.
    fun onShuffle() {
        if (p.tracks.isEmpty()) return
        when {
            !isThisContext && smart -> vm.playSmart(p, null)
            !isThisContext -> {
                playerVm.updateShuffleState(true)
                vm.play(p, p.tracks.random(), sort)
            }
            smart -> { SmartShuffle.on.value = null; playerVm.updateShuffleState(false) }
            shuffle && SmartShuffle.canPlay(p.uri) -> toggleSmart()
            else -> playerVm.updateShuffleState(!shuffle)
        }
    }

    val listState = rememberLazyListState()
    val findFocus = remember { FocusRequester() }
    val findStop = with(LocalDensity.current) { immersiveTopContentPadding().roundToPx() }
    // Opening the search carries the page up to it, so the field lands just clear of the bar with
    // the tracks under it. Done as an effect so the row it scrolls to is already in the list.
    LaunchedEffect(finding) {
        if (finding) {
            listState.animateScrollToItem(FIND_ITEM_INDEX, -findStop)
            runCatching { findFocus.requestFocus() }
        } else find = ""
    }

    val refreshing by vm.refreshing
    Box(Modifier.fillMaxSize()) {
      // Pull down to read the page again, as in the Classic look.
      androidx.compose.material3.pulltorefresh.PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = { vm.refresh() },
        modifier = Modifier.fillMaxSize(),
      ) {
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(bottom = Immersive.BottomChrome),
            modifier = Modifier.fillMaxSize().immersiveHazeSource().immersiveChromeScroll(),
        ) {
            item(key = "header") {
                ContextHeader(
                    navController = navController,
                    p = p,
                    tint = tint,
                    sleeve = sleeve,
                    isLiked = isLiked,
                    isThisContext = isThisContext,
                    playing = playing,
                    saved = SongPlayer.library[p.uri] ?: contextSaved,
                    smart = smart,
                    shuffled = isThisContext && (shuffle || smart),
                    onPlay = ::onPlay,
                    onShuffle = ::onShuffle,
                    onToggleSaved = { vm.toggleContextSaved(p) },
                    onToggleSmart = ::toggleSmart,
                    onAskRemoveDownloads = { askRemoveDownloads = true },
                    members = crew,
                    onMembers = { showMembers = true },
                )
            }
            // Someone else's collaborative playlist this account was never invited to.
            if (vm.collabLocked.value && !vm.editableContext.value) item(key = "collab-locked") {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = Immersive.Gutter, vertical = 6.dp).fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp)).background(Immersive.Raised).padding(12.dp),
                ) {
                    Icon(androidx.compose.material.icons.Icons.Outlined.Group, contentDescription = null, tint = Immersive.TextSecondary, modifier = Modifier.size(20.dp))
                    Text(
                        stringResource(R.string.playlist_collab_locked, p.ownerName.ifBlank { stringResource(R.string.playlist_collab_owner) }),
                        style = Immersive.Caption.copy(color = Immersive.TextSecondary),
                        modifier = Modifier.padding(start = 10.dp),
                    )
                }
            }
            if (finding && findable) item(key = "find") {
                ImmersiveSearchField(
                    query = find,
                    onQueryChange = { find = it },
                    onSubmit = {},
                    focusRequester = findFocus,
                    placeholder = stringResource(
                        if (p.type == "liked") R.string.find_in_context_edit_text_liked_songs_hint else R.string.find_in_context_edit_text_playlist_hint,
                    ),
                    modifier = Modifier.padding(horizontal = Immersive.Gutter, vertical = 8.dp),
                )
            }
            if (rows.isEmpty() && find.isNotBlank()) item(key = "find-empty") {
                ImmersiveMessageState(stringResource(R.string.assisted_curation_search_empty_view_no_result_title, find.trim()))
            }
            val numbered = p.type == "album"
            itemsIndexed(rows, key = { i, _ -> keys[i] }) { index, song ->
                Column {
                    ImmersiveSwipeToQueue(song) {
                        ImmersiveSongRow(
                            song = song,
                            playing = song.id == currentSongId && playing,
                            current = song.id == currentSongId,
                            onClick = {
                                tapper.tap(song) { if (SmartShuffle.on.value == p.uri) vm.playSmart(p, song) else vm.play(p, song, sort) }
                            },
                            onMore = { menuSong = song },
                            trackNumber = if (numbered) index + 1 else null,
                            showDuration = numbered,
                            showAlbum = !numbered,
                            // Collaborative: the small face of who added the song.
                            trailing = vm.adders.value.let { a -> a["row:" + song.rowUid] ?: a["song:" + song.spotifyTrackId] }?.let { m ->
                                { com.music.spotui.ui.components.ProfileAvatar(m.imageUrl, m.name, 22.dp) }
                            },
                        )
                    }
                    if (index < rows.lastIndex) ImmersiveRowDivider(if (numbered) NumberedDividerInset else ImmersiveRowDividerInset)
                }
            }
            // Phone files added to this playlist on this phone (the catalogue can't hold them).
            if (p.type == "playlist") {
                val playlistId = p.uri.substringAfterLast(":")
                val additions = LocalPlaylists.additions[playlistId].orEmpty()
                if (additions.isNotEmpty()) {
                    item(key = "local-header") { SectionHeader(stringResource(R.string.local_added_section), modifier = Modifier.padding(top = 14.dp)) }
                    itemsIndexed(additions, key = { _, item -> "local:${item.key}" }) { index, item ->
                        val track = item.toTrack()
                        ImmersiveListRow(
                            title = item.title,
                            subtitle = item.artist,
                            imageUrl = item.artUri,
                            round = false,
                            playing = LocalPlayer.active.value && np.uri == "local:${track.id}",
                            onClick = { LocalPlayer.play(additions.map { it.toTrack() }, index, context = p.name) },
                            trailing = {
                                Icon(
                                    Icons.Rounded.Close,
                                    contentDescription = stringResource(R.string.immersive_detail_remove_local),
                                    tint = Immersive.TextSecondary,
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .immersiveClickable(onClick = { LocalPlaylists.removeFromSpotify(playlistId, item.key) })
                                        .padding(8.dp),
                                )
                            },
                        )
                    }
                }
            }
            // "Recommended songs", as under an own playlist in the official apps (an empty one too).
            if (p.type == "playlist" && vm.editableContext.value && find.isBlank() && !p.isBlend) immersiveRecommended(vm, p, context, currentSongId)
            // An album: "More like this", albums close to its first song.
            if (p.type == "album") item(key = "similar") {
                SimilarAlbums(navController, p.tracks.firstOrNull()?.spotifyTrackId.orEmpty(), p.uri)
            }
        }
      }
        FastScrollbar(listState, Modifier.align(Alignment.CenterEnd))

        // Search, sort and the playlist's menu live in the bar, as the header is the sleeve's.
        val hasActions = findable || p.type == "playlist"
        ImmersiveTopBar(
            title = p.name,
            listState = listState,
            onBack = { navController.navigateUp() },
            actions = if (hasActions) {
                {
                    if (findable && all.isNotEmpty()) ImmersiveBarAction(
                        icon = Icons.Rounded.Search,
                        contentDescription = stringResource(R.string.immersive_detail_find),
                        onClick = { finding = !finding },
                        tint = if (finding) Immersive.Accent else Immersive.Text,
                    )
                    if (findable) Box {
                        ImmersiveBarAction(
                            icon = Icons.Rounded.SwapVert,
                            contentDescription = stringResource(R.string.filter_and_sort_sort_section_title),
                            onClick = { sortMenu = true },
                            tint = if (sort.isCustom) Immersive.Text else Immersive.Accent,
                        )
                        SortMenu(
                            open = sortMenu,
                            sort = sort,
                            onDismiss = { sortMenu = false },
                            onSort = { sort = it; TrackOrder.save(context, p.uri, it) },
                        )
                    }
                    if (p.type == "playlist" || p.type == "album" || p.type == "liked" || p.type == "liked-artist") ImmersiveBarAction(
                        icon = Icons.Rounded.MoreHoriz,
                        contentDescription = stringResource(R.string.content_description_context_menu),
                        onClick = { if (p.type == "playlist") playlistMenu = true else albumMenu = true },
                    )
                }
            } else null,
        )
        // Opened from a collaborative-playlist invitation (…?pt=): the playlist is joined from here.
        InviteJoinBar(vm, p, Modifier.align(Alignment.BottomCenter).padding(start = Immersive.Gutter, end = Immersive.Gutter, bottom = Immersive.BottomChrome))
        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = Immersive.BottomChrome),
        ) { data ->
            Snackbar(
                snackbarData = data,
                containerColor = Immersive.Raised,
                contentColor = Immersive.Text,
                actionContentColor = Immersive.Accent,
            )
        }
    }
}

/** The wash behind Liked Songs' header, as the Classic page's purple-to-blue sleeve. */
private val LikedTint = Color(0xFF4A3FA8)

/**
 * Everything above the track list: the sleeve, the title, the owner, the meta line, the Play and
 * Shuffle pair and the round buttons. The wash behind it runs up under the (transparent) top bar.
 */
@Composable
private fun ContextHeader(
    navController: NavController,
    p: ContextPageModel,
    tint: Color,
    sleeve: Dp,
    isLiked: Boolean,
    isThisContext: Boolean,
    playing: Boolean,
    saved: Boolean,
    smart: Boolean,
    shuffled: Boolean,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onToggleSaved: () -> Unit,
    onToggleSmart: () -> Unit,
    onAskRemoveDownloads: () -> Unit,
    members: List<app.myspoti.api.PlaylistMember> = emptyList(),
    onMembers: () -> Unit = {},
) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(lerp(tint, Color.Black, 0.3f), Immersive.Page)))
            .padding(top = immersiveTopContentPadding(), bottom = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(sleeve).shadow(SleeveShadow, SleeveShape)) {
            if (isLiked) LikedSleeve(Modifier.matchParentSize())
            else ImmersiveCover(
                url = p.coverUri,
                modifier = Modifier.matchParentSize(),
                shape = SleeveShape,
                placeholderIcon = if (p.type == "album") Icons.Rounded.Album else Icons.Rounded.QueueMusic,
            )
        }
        Spacer(Modifier.height(20.dp))
        Text(
            text = p.name,
            style = Immersive.LargeTitle.copy(fontSize = 28.sp, lineHeight = 34.sp),
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = HeaderGutter),
        )
        if (p.description.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = p.description,
                style = Immersive.Subhead,
                textAlign = TextAlign.Center,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = HeaderGutter),
            )
        }
        if (members.size > 1) {
            // Collaborative: everyone who adds to it, their faces overlapping; a tap lists them.
            Spacer(Modifier.height(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = HeaderGutter).immersiveClickable(onClick = onMembers),
            ) {
                Box(Modifier.width((24 + 16 * (members.size.coerceAtMost(4) - 1)).dp).height(24.dp)) {
                    members.take(4).forEachIndexed { n, m ->
                        Box(Modifier.padding(start = (16 * n).dp).size(24.dp).clip(CircleShape).background(Immersive.Page).padding(1.dp)) {
                            com.music.spotui.ui.components.ProfileAvatar(m.imageUrl, m.name, 22.dp)
                        }
                    }
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (members.size <= 2) members.joinToString(", ") { it.name }
                    else stringResource(R.string.playlist_members_more, members.first().name, members.size - 1),
                    style = Immersive.Headline.copy(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.Normal, color = Immersive.Accent),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        } else if (p.ownerName.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .padding(horizontal = HeaderGutter)
                    .immersiveClickable(onClick = {
                        if (p.ownerUri.startsWith("spotify:artist:")) {
                            navController.navigate(artistRoute(p.ownerName.substringBefore(",").trim(), p.ownerUri.substringAfterLast(":")))
                        } else if (p.ownerUri.startsWith("spotify:user:")) {
                            navController.navigate(profileRoute(p.ownerUri.substringAfterLast(":"), p.ownerName))
                        }
                    }),
            ) {
                if (p.ownerAvatar.isNotBlank()) {
                    ImmersiveCover(p.ownerAvatar, Modifier.size(24.dp), CircleShape, border = false)
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    text = p.ownerName,
                    style = Immersive.Headline.copy(fontSize = 19.sp, lineHeight = 24.sp, fontWeight = FontWeight.Normal, color = Immersive.Accent),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (p.metaLine.isNotBlank() || p.explicit) {
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = HeaderGutter)) {
                if (p.explicit && p.type == "album") {
                    ImmersiveExplicitBadge(Immersive.TextSecondary)
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    text = p.metaLine,
                    style = Immersive.Caption.copy(fontWeight = FontWeight.Medium),
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        if (p.tracks.isNotEmpty()) {
            val thisPlaying = isThisContext && playing
            Spacer(Modifier.height(18.dp))
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = Immersive.Gutter),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ImmersiveButton(
                    text = stringResource(if (thisPlaying) R.string.immersive_detail_pause else R.string.immersive_shell_play),
                    icon = if (thisPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    onClick = onPlay,
                    modifier = Modifier.weight(1f),
                )
                ImmersiveButton(
                    text = stringResource(R.string.immersive_shell_shuffle),
                    icon = if (shuffled && smart) Icons.Rounded.AutoAwesome else Icons.Rounded.Shuffle,
                    onClick = onShuffle,
                    // Lit while this list plays shuffled: a tap then steps to smart shuffle or off.
                    filled = shuffled,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                if (p.type != "liked") ImmersiveCircleButton(
                    icon = if (saved) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                    contentDescription = stringResource(if (saved) R.string.immersive_detail_unsave else R.string.immersive_detail_save),
                    onClick = onToggleSaved,
                    tint = if (saved) Immersive.Accent else Immersive.Text,
                )
                // The page's own songs, downloaded as a whole stay in step with the catalogue.
                val ids = p.tracks.map { it.spotifyTrackId }.filter { it.isNotBlank() }.distinct()
                // Songs with no matching recording count as settled: the list can still be removed.
                val downloadedCount = ids.count {
                    OfflineStore.isDownloaded(it) || OfflineStore.status[it] == OfflineStatus.Unavailable
                }
                val busy = ids.any {
                    val st = OfflineStore.status[it]
                    st == OfflineStatus.Queued || st is OfflineStatus.Downloading
                }
                val listKept = OfflineCollections.isDownloaded(p.uri)
                LaunchedEffect(listKept, p.complete, p.tracks.size) { if (listKept && p.complete) OfflineCollections.sync(p) }
                DownloadCircle(downloadedCount, ids.size, busy, listKept) {
                    if (listKept) onAskRemoveDownloads()
                    else DownloadAsk.start(context) { q ->
                        com.music.spotui.di.Haptics.confirm()
                        OfflineCollections.download(p, q)
                        Toast.makeText(context, context.getString(R.string.download_notification_downloading), Toast.LENGTH_SHORT).show()
                    }
                }
                if (SmartShuffle.canPlay(p.uri)) ImmersiveCircleButton(
                    icon = Icons.Rounded.AutoAwesome,
                    contentDescription = stringResource(R.string.smart_shuffle_title),
                    onClick = onToggleSmart,
                    tint = if (smart) Immersive.Accent else Immersive.Text,
                )
            }
        }
    }
}

/** The Liked Songs artwork: purple-to-blue gradient with a white heart. */
@Composable
private fun LikedSleeve(modifier: Modifier) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .clip(SleeveShape)
            .background(Brush.linearGradient(listOf(Color(0xFF450AF5), Color(0xFF8E8EE5), Color(0xFFC4EFD9)))),
    ) {
        Icon(Icons.Rounded.Favorite, contentDescription = null, tint = Color.White, modifier = Modifier.fillMaxSize(0.4f))
    }
}

/**
 * The download button of an album / playlist: an arrow, a progress ring while songs download, then
 * a bin once all of them are on the phone (tap = remove the downloads). The Classic button's
 * states in a round 44 dp button.
 */
@Composable
private fun DownloadCircle(downloaded: Int, total: Int, busy: Boolean, kept: Boolean, onClick: () -> Unit) {
    val all = kept && !busy
    Box(
        modifier = Modifier
            .immersiveClickable(onClick = onClick)
            .size(44.dp)
            .clip(CircleShape)
            .background(Immersive.Raised),
        contentAlignment = Alignment.Center,
    ) {
        when {
            all -> Icon(
                Icons.Rounded.DeleteOutline, stringResource(R.string.context_menu_undownload),
                tint = Immersive.Accent, modifier = Modifier.size(22.dp),
            )
            busy -> if (total > 0) CircularProgressIndicator(
                progress = { downloaded.toFloat() / total },
                color = Immersive.Accent, trackColor = Immersive.Separator,
                strokeWidth = 2.dp, modifier = Modifier.size(22.dp),
            ) else CircularProgressIndicator(color = Immersive.Accent, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
            else -> Icon(
                Icons.Rounded.Download, stringResource(R.string.context_menu_download),
                tint = Immersive.Text, modifier = Modifier.size(22.dp),
            )
        }
    }
}

/** The sort menu: picking the sort already chosen flips its direction (the arrow shows it). */
@Composable
private fun SortMenu(open: Boolean, sort: TrackOrder, onDismiss: () -> Unit, onSort: (TrackOrder) -> Unit) {
    DropdownMenu(expanded = open, onDismissRequest = onDismiss, containerColor = Immersive.Raised) {
        Text(
            stringResource(R.string.filter_and_sort_sort_section_title),
            style = Immersive.Caption,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )
        TrackSort.entries.forEach { option ->
            DropdownMenuItem(
                text = {
                    Text(
                        stringResource(option.label),
                        style = Immersive.Body.copy(color = if (option == sort.sort) Immersive.Accent else Immersive.Text),
                    )
                },
                trailingIcon = if (option == sort.sort) {
                    {
                        Icon(
                            if (sort.reversed) Icons.Rounded.ArrowUpward else Icons.Rounded.ArrowDownward,
                            contentDescription = null, tint = Immersive.Accent, modifier = Modifier.size(18.dp),
                        )
                    }
                } else null,
                onClick = {
                    onDismiss()
                    onSort(if (option == sort.sort) sort.copy(reversed = !sort.reversed) else TrackOrder(option))
                },
            )
        }
    }
}

/** "More like this" under an album: the catalogue's similar albums, from its first song. */
@Composable
private fun SimilarAlbums(navController: NavController, firstTrackId: String, albumUri: String) {
    val albums by produceState(emptyList<app.myspoti.api.Album>(), firstTrackId) {
        if (firstTrackId.isBlank()) return@produceState
        value = runCatching { MyspotiRuntime.api.catalog.similarAlbums("spotify:track:$firstTrackId", 12) }
            .getOrDefault(emptyList()).filter { it.uri != albumUri }
    }
    if (albums.isEmpty()) return
    ImmersiveShelf(title = stringResource(R.string.more_like_this), modifier = Modifier.padding(top = 24.dp)) {
        items(albums.size, key = { albums[it].uri }) { i ->
            val a = albums[i]
            val artists = a.artists.joinToString(", ") { it.name }
            ImmersiveShelfCard(
                title = a.name,
                subtitle = artists,
                imageUrl = a.images.maxByOrNull { it.width ?: 0 }?.url,
                onClick = { navController.navigate(albumRoute(a.name, artists, a.uri.substringAfterLast(':'))) },
            )
        }
    }
}

private const val RECS_COUNT_SHOWN = 10

/** The suggestions under an own playlist: tap to listen, "+" to add, "Refresh" for others. */
private fun androidx.compose.foundation.lazy.LazyListScope.immersiveRecommended(
    vm: ContextViewModel, p: ContextPageModel, context: android.content.Context, currentSongId: Int,
) {
    item(key = "recs-title") {
        LaunchedEffect(p.uri) { vm.loadRecs(p) }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(start = Immersive.Gutter, end = 8.dp, top = 28.dp, bottom = 4.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.recommended_songs), style = Immersive.Headline)
                Text(
                    stringResource(if (p.tracks.isEmpty()) R.string.recommended_songs_from_name else R.string.recommended_songs_from_songs),
                    style = Immersive.Caption.copy(color = Immersive.TextSecondary),
                )
            }
            androidx.compose.material3.TextButton(onClick = { vm.loadRecs(p, refresh = true) }) {
                Text(stringResource(R.string.recommended_songs_refresh), color = Immersive.Accent, fontWeight = FontWeight.SemiBold)
            }
        }
    }
    val recs = vm.recs.value
    if (recs == null) item(key = "recs-loading") {
        Column(Modifier.padding(horizontal = Immersive.Gutter)) {
            repeat(3) { ImmersiveShimmerBox(Modifier.fillMaxWidth().height(52.dp).padding(vertical = 4.dp), RoundedCornerShape(8.dp)) }
        }
    }
    else items(recs.take(RECS_COUNT_SHOWN), key = { "rec-" + it.spotifyTrackId }) { song ->
        ImmersiveSongRow(
            song = song,
            playing = false,
            current = song.id == currentSongId,
            onClick = { SongPlayer.playSong(song.url, context, songs = listOf(song)) },
            onMore = null,
            modifier = immersiveItemPlacement(),
            trailing = {
                Icon(
                    androidx.compose.material.icons.Icons.Outlined.AddCircleOutline,
                    contentDescription = stringResource(R.string.recommended_songs_add),
                    tint = Immersive.Text,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .immersiveClickable(onClick = {
                            vm.takeRec(p, song)
                            com.music.spotui.data.api.SpotifySync.addTrackToPlaylist(context, p.uri.substringAfterLast(':'), song.spotifyTrackId) { ok ->
                                if (ok) vm.reload()
                                else android.widget.Toast.makeText(context, context.getString(R.string.playlist_change_refused), android.widget.Toast.LENGTH_SHORT).show()
                            }
                        })
                        .padding(8.dp),
                )
            },
        )
    }
}

/** A collaborative-playlist invitation for this page: "Join" over the list, as on the Classic page. */
@Composable
private fun InviteJoinBar(vm: ContextViewModel, p: ContextPageModel, modifier: Modifier) {
    val context = LocalContext.current
    val invite by com.music.spotui.data.api.InviteLinks.pending.collectAsState()
    var joining by remember { mutableStateOf(false) }
    val offered = invite?.takeIf { p.type == "playlist" && it.uri == p.uri && !vm.editableContext.value } ?: return
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Immersive.Raised)
            .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
    ) {
        Text(stringResource(R.string.playlist_invite_banner), style = Immersive.Body, modifier = Modifier.weight(1f).padding(end = 12.dp))
        androidx.compose.material3.Button(
            enabled = !joining,
            colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = Immersive.Accent, contentColor = Color.White),
            onClick = {
                joining = true
                com.music.spotui.data.api.InviteLinks.join(context, offered, p) { ok ->
                    joining = false
                    if (ok) {
                        vm.editableContext.value = true
                        vm.contextSaved.value = true
                        SongPlayer.library[p.uri] = true
                        vm.reload()
                        android.widget.Toast.makeText(context, context.getString(R.string.playlist_invite_joined), android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            },
        ) { Text(stringResource(R.string.playlist_invite_join), fontWeight = FontWeight.Bold) }
    }
}
