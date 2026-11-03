package com.music.spotui.ui.immersive.pages

import com.music.spotui.ui.immersive.sheets.ImmersiveAlertDialog
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import app.myspoti.engine.R
import com.music.spotui.data.api.LibraryEdits
import com.music.spotui.data.api.LibraryFolders
import com.music.spotui.data.api.Response
import com.music.spotui.data.api.SpotifySync
import com.music.spotui.data.entity.LibraryEntry
import com.music.spotui.data.local.LibraryOrder
import com.music.spotui.data.local.LocalPlaylists
import com.music.spotui.di.Graph
import com.music.spotui.di.LocalPlayer
import com.music.spotui.di.SongPlayer
import com.music.spotui.ui.immersive.Immersive
import com.music.spotui.ui.immersive.components.ImmersiveListRow
import com.music.spotui.ui.immersive.components.ImmersiveMessageState
import com.music.spotui.ui.immersive.components.ImmersivePlayShuffleButtons
import com.music.spotui.ui.immersive.screens.LibraryList
import com.music.spotui.ui.immersive.shell.ImmersiveBarAction
import com.music.spotui.ui.immersive.shell.ImmersiveLargeTitle
import com.music.spotui.ui.immersive.shell.ImmersiveTopBar
import com.music.spotui.ui.immersive.shell.rememberTopBarCollapse
import com.music.spotui.ui.screens.LibraryItemSheet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A Spotify library folder in the Immersive look: the folder's name over its folders and
 * playlists, in the Library's rows (long press and drag to reorder, the ⋯ menu of every row), a
 * ⋯ in the bar for a new playlist, renaming and deleting the folder. The loading, the pending
 * edits and the actions are the Classic `FolderScreen`'s.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImmersiveFolderScreen(navController: NavController, uri: String, initialName: String) {
    val context = LocalContext.current
    var name by remember { mutableStateOf(initialName) }
    // The folder as Spotify gave it; [entries] is that with the changes Spotify has not caught up with.
    var server by remember { mutableStateOf<List<LibraryEntry>?>(null) }
    val edits by LibraryEdits.version.collectAsState()
    val entries = remember(server, edits) { server?.let { LibraryEdits.applyTo(uri, it) } }
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var newPlaylist by remember { mutableStateOf(false) }
    var deletingFolder by remember { mutableStateOf(false) }
    var itemMenu by remember { mutableStateOf<LibraryEntry?>(null) }
    var generation by remember { mutableStateOf(0) }
    var refreshing by remember { mutableStateOf(false) }
    LaunchedEffect(uri, generation) {
        withContext(Dispatchers.IO) {
            Graph.repository.provideLibraryFolder(uri).collect { r ->
                if (r is Response.Success) server = r.data else if (r is Response.Error && server == null) server = emptyList()
            }
        }
        refreshing = false
    }
    // A change just written: the folder is read again once Spotify's list has had time to catch up.
    LaunchedEffect(uri) { LibraryEdits.reloads.collect { generation++ } }

    if (menu) ImmersiveSheet(onDismiss = { menu = false }) {
        ImmersiveSheetRow(Icons.AutoMirrored.Rounded.PlaylistAdd, stringResource(R.string.add_to_playlist_new_playlist_button_text), onClick = { menu = false; newPlaylist = true })
        ImmersiveSheetRow(Icons.Rounded.Edit, stringResource(R.string.context_menu_rename_folder), onClick = { menu = false; renaming = true })
        ImmersiveSheetRow(Icons.Rounded.DeleteOutline, stringResource(R.string.folder_delete), destructive = true, onClick = { menu = false; deletingFolder = true })
    }
    itemMenu?.let { LibraryItemSheet(it, onDismiss = { itemMenu = null }, onChanged = { generation++ }) }
    if (deletingFolder) ImmersiveAlertDialog(
        onDismissRequest = { deletingFolder = false },
        text = { Text(stringResource(R.string.folder_delete_confirm, name), style = Immersive.Body) },
        confirmButton = {
            TextButton(onClick = {
                deletingFolder = false
                // The folder is gone from the library at once; a refusal brings it back.
                LibraryFolders.delete(context, uri)
                navController.navigateUp()
            }) { Text(stringResource(R.string.actions_picker_delete), style = Immersive.BodyStrong.copy(color = Immersive.Destructive)) }
        },
        dismissButton = {
            TextButton(onClick = { deletingFolder = false }) {
                Text(stringResource(R.string.cancel), style = Immersive.BodyStrong.copy(color = Immersive.Accent))
            }
        },
    )
    // A Spotify playlist created straight inside this folder.
    if (newPlaylist) ImmersiveNameDialog(
        stringResource(R.string.create_playlist_content_description), context.getString(R.string.my_playlist),
        stringResource(R.string.create_playlist_create_button), onDismiss = { newPlaylist = false },
    ) { playlistName ->
        newPlaylist = false
        SpotifySync.createPlaylistWithTrack(context, playlistName, "", folderUri = uri)
    }
    if (renaming) ImmersiveNameDialog(
        stringResource(R.string.context_menu_rename_folder), name, stringResource(R.string.local_rename),
        onDismiss = { renaming = false },
    ) { newName ->
        renaming = false
        val before = name
        name = newName
        LibraryFolders.rename(context, uri, newName) { ok -> if (!ok) name = before }
    }

    val listState = rememberLazyListState()
    val arranged = entries?.let { list ->
        LibraryOrder.version
        LibraryOrder.arrange(list, LibraryOrder.sort, LibraryOrder.custom(uri), 0)
    }.orEmpty()
    ImmersivePageFrame {
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = { refreshing = true; generation++ },
            modifier = Modifier.fillMaxSize(),
        ) {
            LibraryList(
                listState = listState,
                header = {
                    ImmersiveLargeTitle(
                        title = name,
                        subtitle = entries?.size?.let { pluralStringResource(R.plurals.add_to_playlist_folder_subtitle_playlists_count, it, it.toString()) },
                    )
                },
                loading = entries == null,
                entries = arranged,
                artists = emptyList(),
                loadFailed = false,
                onRetry = { generation++ },
                navController = navController,
                reorderLevel = uri,
                pinned = 0,
                onMenu = { itemMenu = it },
            )
        }
        ImmersiveTopBar(title = name, collapse = rememberTopBarCollapse(listState), onBack = { navController.navigateUp() }) {
            ImmersiveBarAction(Icons.Rounded.MoreHoriz, stringResource(R.string.content_description_context_menu), onClick = { menu = true })
        }
    }
}

/**
 * A playlist kept on this phone, in the Immersive look: Play and Shuffle over the phone's files,
 * each row swiping away to leave the playlist, a ⋯ in the bar to rename or delete it. The data
 * and the player are the Classic `LocalPlaylistScreen`'s ([LocalPlaylists], [LocalPlayer]).
 */
@Composable
fun ImmersiveLocalPlaylistScreen(navController: NavController, id: String) {
    val playlist = LocalPlaylists.playlists[id]
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    if (playlist == null) {
        LaunchedEffect(Unit) { navController.navigateUp() }
        return
    }
    if (menu) ImmersiveSheet(onDismiss = { menu = false }) {
        ImmersiveSheetRow(Icons.Rounded.Edit, stringResource(R.string.local_rename), onClick = { menu = false; renaming = true })
        ImmersiveSheetRow(Icons.Rounded.DeleteOutline, stringResource(R.string.local_delete_playlist), destructive = true, onClick = {
            menu = false
            navController.navigateUp()
            LocalPlaylists.delete(id)
        })
    }
    if (renaming) ImmersiveNameDialog(
        stringResource(R.string.local_rename), playlist.name, stringResource(R.string.local_rename),
        onDismiss = { renaming = false },
    ) { renaming = false; LocalPlaylists.rename(id, it) }

    val np by SongPlayer.nowPlaying
    val playingHere = LocalPlayer.active.value && LocalPlayer.contextName == playlist.name
    val tracks = playlist.items.map { it.toTrack() }
    ImmersivePage(
        title = playlist.name,
        subtitle = stringResource(R.string.local_playlist_subtitle) + " • " +
            pluralStringResource(R.plurals.add_to_playlist_subtitle_songs_count, playlist.items.size, playlist.items.size.toString()),
        onBack = { navController.navigateUp() },
        actions = {
            ImmersiveBarAction(Icons.Rounded.MoreHoriz, stringResource(R.string.content_description_context_menu), onClick = { menu = true })
        },
    ) {
        if (playlist.items.isEmpty()) {
            item(key = "empty") { ImmersiveMessageState(stringResource(R.string.local_playlist_empty)) }
            return@ImmersivePage
        }
        item(key = "buttons") {
            ImmersivePlayShuffleButtons(
                onPlay = {
                    when {
                        playingHere && SongPlayer.isPlaying() -> SongPlayer.pause()
                        playingHere -> SongPlayer.play()
                        else -> LocalPlayer.play(tracks, 0, context = playlist.name)
                    }
                },
                onShuffle = { LocalPlayer.play(tracks, tracks.indices.random(), shuffle = true, context = playlist.name) },
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        itemsIndexed(playlist.items, key = { _, item -> item.key }) { index, item ->
            val current = LocalPlayer.active.value && np.uri == "local:${tracks[index].id}"
            Column(Modifier.animateItem()) {
                ImmersiveSwipeToRemove(item.key, onRemove = { LocalPlaylists.remove(id, item.key) }) {
                    ImmersiveListRow(
                        title = item.title,
                        subtitle = item.artist.ifBlank { null },
                        imageUrl = item.artUri,
                        round = false,
                        playing = current && SongPlayer.isPlaying(),
                        onClick = { LocalPlayer.play(tracks, index, context = playlist.name) },
                    )
                }
            }
        }
    }
}
