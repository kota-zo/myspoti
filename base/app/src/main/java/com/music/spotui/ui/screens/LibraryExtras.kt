package com.music.spotui.ui.screens

import com.music.spotui.ui.theme.AppDestructive
import com.music.spotui.ui.theme.tone
import com.music.spotui.ui.theme.AppText
import com.music.spotui.ui.theme.AppMuted

import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.PushPin
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.outlined.PlaylistPlay
import androidx.compose.material.icons.automirrored.outlined.QueueMusic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import app.myspoti.engine.R
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.CrossFade
import com.bumptech.glide.integration.compose.GlideImage
import com.bumptech.glide.integration.compose.placeholder
import com.music.spotui.data.api.LibraryEdits
import com.music.spotui.data.api.LibraryFolders
import com.music.spotui.data.api.Response
import com.music.spotui.data.api.SpotifySync
import com.music.spotui.data.entity.LibraryEntry
import com.music.spotui.data.local.LocalItem
import com.music.spotui.data.local.LocalPlaylists
import com.music.spotui.data.local.LocalTrack
import com.music.spotui.di.Graph
import com.music.spotui.di.LocalPlayer
import com.music.spotui.di.SongPlayer
import com.music.spotui.ui.components.SwipeToRemove
import com.music.spotui.ui.components.Loader
import com.music.spotui.ui.components.SkeletonKind
import com.music.spotui.ui.components.pressable
import com.music.spotui.ui.navigation.localPlaylistRoute
import com.music.spotui.ui.navigation.playlistRoute
import com.music.spotui.ui.components.ProfileAvatar
import com.music.spotui.ui.theme.AppBackground
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Green: Color get() = com.music.spotui.ui.theme.AppAccent
private val SheetColor: androidx.compose.ui.graphics.Color get() = tone(0xFF282828)

/** A name prompt (new playlist / folder, rename). */
@Composable
fun NameDialog(title: String, initial: String, confirm: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SheetColor,
        title = { Text(title, color = AppText) },
        text = {
            TextField(
                value = text, onValueChange = { text = it }, singleLine = true,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = tone(0xFF3A3A3A), unfocusedContainerColor = tone(0xFF3A3A3A),
                    focusedTextColor = AppText, unfocusedTextColor = AppText, cursorColor = Green,
                    focusedIndicatorColor = Green, unfocusedIndicatorColor = Color.Transparent,
                ),
            )
        },
        confirmButton = {
            TextButton(enabled = text.isNotBlank(), onClick = { onConfirm(text.trim()) }) { Text(confirm, color = Green) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.add_to_playlist_button_cancel), color = AppText) }
        },
    )
}

@Composable
private fun SheetRow(icon: ImageVector, label: String, detail: String? = null, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp).clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        Icon(icon, null, tint = AppMuted, modifier = Modifier.size(26.dp))
        Spacer(Modifier.width(16.dp))
        Column {
            Text(label, color = AppText, fontSize = 16.sp)
            if (detail != null) Text(detail, color = AppMuted, fontSize = 12.sp)
        }
    }
}

/** The Library's "+": a Spotify playlist, a Spotify folder, or a playlist on this phone. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryCreateSheet(navController: NavController, onDismiss: () -> Unit, onLibraryChanged: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var asking by remember { mutableStateOf<String?>(null) } // "playlist" | "folder" | "local"
    when (asking) {
        "playlist" -> NameDialog(stringResource(R.string.create_playlist_content_description), context.getString(R.string.my_playlist),
            stringResource(R.string.create_playlist_create_button), onDismiss = onDismiss) { name ->
            // The row shows in the library at once; SpotifySync takes it back if Spotify refuses.
            SpotifySync.createPlaylistWithTrack(context, name, "")
            onDismiss()
        }
        "folder" -> NameDialog(stringResource(R.string.create_menu_item_folder_title), context.getString(R.string.create_folder_name_suggested_folder_name),
            stringResource(R.string.create_playlist_create_button), onDismiss = onDismiss) { name ->
            LibraryFolders.create(context, name)
            onDismiss()
        }
        "local" -> NameDialog(stringResource(R.string.local_playlist_new), context.getString(R.string.my_playlist),
            stringResource(R.string.create_playlist_create_button), onDismiss = onDismiss) { name ->
            val p = LocalPlaylists.create(name)
            onDismiss()
            navController.navigate(localPlaylistRoute(p.id))
        }
        "blend" -> BlendSheet(navController, onDismiss)
        else -> com.music.spotui.ui.components.LookSheet(onDismissRequest = onDismiss, containerColor = SheetColor) {
            Column(Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
                SheetRow(Icons.AutoMirrored.Filled.PlaylistAdd, stringResource(R.string.assisted_curation_search_playlist)) { asking = "playlist" }
                SheetRow(Icons.Outlined.Group, stringResource(R.string.blend_title), stringResource(R.string.blend_detail)) { asking = "blend" }
                SheetRow(Icons.Outlined.CreateNewFolder, stringResource(R.string.create_menu_item_folder_title)) { asking = "folder" }
                SheetRow(Icons.Outlined.PhoneAndroid, stringResource(R.string.local_playlist_new), stringResource(R.string.local_add_note)) { asking = "local" }
            }
        }
    }
}

/**
 * "Mix à plusieurs" (Spotify Blend): an invitation link to send, or one received to join with.
 * The Blend playlist only exists on Spotify once the friend has joined; then it is in the
 * library like any playlist.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BlendSheet(navController: NavController, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val blends = app.myspoti.host.android.MyspotiRuntime.api.blends
    var joining by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    fun say(res: Int) = Toast.makeText(context, context.getString(res), Toast.LENGTH_SHORT).show()
    if (joining) NameDialog(stringResource(R.string.blend_join_title), "", stringResource(R.string.blend_join_button),
        onDismiss = { joining = false }) { link ->
        joining = false
        busy = true
        scope.launch {
            val opened = runCatching {
                val invitation = blends.invitation(link)
                when (invitation.state) {
                    app.myspoti.api.BlendInvitationState.JOINABLE, app.myspoti.api.BlendInvitationState.UNKNOWN -> blends.join(link)
                    app.myspoti.api.BlendInvitationState.JOINED -> invitation.playlist
                    app.myspoti.api.BlendInvitationState.WAITING -> { say(R.string.blend_own_invite); null }
                    app.myspoti.api.BlendInvitationState.FULL -> { say(R.string.blend_full); null }
                    app.myspoti.api.BlendInvitationState.DELETED -> { say(R.string.blend_deleted); null }
                }
            }.onFailure {
                say(if (it is IllegalArgumentException) R.string.blend_bad_link else R.string.blend_failed)
            }.getOrNull()
            busy = false
            if (opened != null) { onDismiss(); navController.navigate(playlistRoute(opened.substringAfterLast(':'))) }
        }
    }
    com.music.spotui.ui.components.LookSheet(onDismissRequest = onDismiss, containerColor = SheetColor) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
            Text(stringResource(R.string.blend_title), color = AppText, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(20.dp, 0.dp, 20.dp, 8.dp))
            if (busy) Text(stringResource(R.string.blend_working), color = AppMuted, fontSize = 14.sp, modifier = Modifier.padding(20.dp, 12.dp))
            else {
                SheetRow(Icons.Outlined.Share, stringResource(R.string.blend_invite)) {
                    busy = true
                    scope.launch {
                        val link = runCatching { blends.invite().link }.getOrNull()
                        busy = false
                        if (link == null) return@launch say(R.string.blend_failed)
                        val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
                            .putExtra(android.content.Intent.EXTRA_TEXT, context.getString(R.string.blend_invite_message, link))
                        context.startActivity(android.content.Intent.createChooser(send, null).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                        onDismiss()
                    }
                }
                SheetRow(Icons.Outlined.Link, stringResource(R.string.blend_join)) { joining = true }
            }
        }
    }
}

/**
 * A Blend invitation link opened from outside (see InviteLinks.blend): who invites, and "Join".
 * Spotify's own words for the invitation's state are shown as they come (already translated).
 * An invitation already joined opens its playlist straight away; one of the account's own is
 * offered to be sent again.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BlendInviteSheet(link: com.music.spotui.data.api.InviteLinks.BlendLink, navController: NavController, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val api = app.myspoti.host.android.MyspotiRuntime.api
    var invitation by remember(link) { mutableStateOf<app.myspoti.api.BlendInvitation?>(null) }
    var failed by remember(link) { mutableStateOf(false) }
    var busy by remember(link) { mutableStateOf(false) }
    fun open(uri: String) { onDismiss(); navController.navigate(playlistRoute(uri.substringAfterLast(':'))) }
    LaunchedEffect(link) {
        api.ready.first { it }
        runCatching { api.blends.invitation(link.id) }
            .onSuccess { v ->
                if (v.state == app.myspoti.api.BlendInvitationState.JOINED && v.playlist != null) open(v.playlist!!)
                else invitation = v
            }
            .onFailure { failed = true }
    }
    com.music.spotui.ui.components.LookSheet(onDismissRequest = onDismiss, containerColor = SheetColor) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(24.dp, 0.dp, 24.dp, 20.dp),
        ) {
            val v = invitation
            val inviter = v?.inviter
            val own = v?.state == app.myspoti.api.BlendInvitationState.WAITING
            val joinable = v?.state == app.myspoti.api.BlendInvitationState.JOINABLE || v?.state == app.myspoti.api.BlendInvitationState.UNKNOWN
            ProfileAvatar(inviter?.imageUrl, inviter?.name, 88.dp)
            Spacer(Modifier.height(16.dp))
            val title = when {
                failed -> stringResource(R.string.blend_failed)
                v == null -> stringResource(R.string.blend_title)
                joinable && inviter != null -> stringResource(R.string.blend_invited_by, inviter.name)
                else -> v.title ?: stringResource(R.string.blend_title)
            }
            Text(title, color = AppText, fontSize = 20.sp, fontWeight = FontWeight.Bold, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            val detail = when {
                v == null && !failed -> stringResource(R.string.blend_working)
                v == null -> null
                joinable -> stringResource(R.string.blend_detail)
                else -> v.subtitle
            }
            if (detail != null) Text(detail, color = AppMuted, fontSize = 14.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.padding(top = 6.dp))
            Spacer(Modifier.height(24.dp))
            if (joinable) androidx.compose.material3.Button(
                enabled = !busy,
                onClick = {
                    busy = true
                    scope.launch {
                        val uri = runCatching { api.blends.join(link.id) }.getOrNull()
                        busy = false
                        if (uri == null) Toast.makeText(context, context.getString(R.string.blend_failed), Toast.LENGTH_SHORT).show()
                        else {
                            com.music.spotui.data.api.Api.HomeCache.libraryRoot = null
                            LibraryEdits.reloadSoon()
                            open(uri)
                        }
                    }
                },
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = com.music.spotui.ui.theme.AppAccent, contentColor = Color.Black),
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) { Text(stringResource(if (busy) R.string.blend_working else R.string.blend_join_button), fontWeight = FontWeight.Bold, fontSize = 16.sp) }
            if (own) androidx.compose.material3.Button(
                onClick = {
                    val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(android.content.Intent.EXTRA_TEXT, context.getString(R.string.blend_invite_message, "https://open.spotify.com/blend/taste-match/${link.id}"))
                    context.startActivity(android.content.Intent.createChooser(send, null).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                    onDismiss()
                },
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = AppText, contentColor = com.music.spotui.ui.theme.AppBackground),
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) { Text(stringResource(R.string.blend_invite), fontWeight = FontWeight.Bold, fontSize = 16.sp) }
            TextButton(onClick = onDismiss, modifier = Modifier.padding(top = 4.dp)) {
                Text(stringResource(R.string.blend_later), color = AppText)
            }
        }
    }
}

@Composable
private fun PageHeader(title: String, subtitle: String?, navController: NavController, onMenu: (() -> Unit)?) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(8.dp, 8.dp, 8.dp, 4.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(44.dp).clip(CircleShape).pressable { navController.navigateUp() },
        ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.accessibility_navigate_back), tint = AppText) }
        Column(Modifier.weight(1f).padding(start = 4.dp)) {
            Text(title, color = AppText, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, color = AppMuted, fontSize = 13.sp)
        }
        if (onMenu != null) Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(44.dp).clip(CircleShape).pressable { onMenu() },
        ) { Icon(Icons.Default.MoreVert, stringResource(R.string.content_description_context_menu), tint = AppText) }
    }
}

/** A Spotify library folder: its folders and playlists, opened level by level. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderScreen(navController: NavController, uri: String, initialName: String) {
    val context = LocalContext.current
    var name by remember { mutableStateOf(initialName) }
    // The folder as Spotify gave it; [entries] is that with the changes Spotify has not caught up with.
    var server by remember { mutableStateOf<List<LibraryEntry>?>(null) }
    val edits by com.music.spotui.data.api.LibraryEdits.version.collectAsState()
    val entries = remember(server, edits) { server?.let { com.music.spotui.data.api.LibraryEdits.applyTo(uri, it) } }
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var newPlaylist by remember { mutableStateOf(false) }
    var deletingFolder by remember { mutableStateOf(false) }
    var generation by remember { mutableStateOf(0) }
    var refreshing by remember { mutableStateOf(false) }
    LaunchedEffect(uri, generation) {
        withContext(Dispatchers.IO) {
            Graph.repository.provideLibraryFolder(uri).collect { r -> if (r is Response.Success) server = r.data else if (r is Response.Error && server == null) server = emptyList() }
        }
        refreshing = false
    }
    // A change just written: the folder is read again once Spotify's list has had time to catch up.
    LaunchedEffect(uri) { com.music.spotui.data.api.LibraryEdits.reloads.collect { generation++ } }
    if (menu) com.music.spotui.ui.components.LookSheet(onDismissRequest = { menu = false }, containerColor = SheetColor) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
            SheetRow(Icons.AutoMirrored.Filled.PlaylistAdd, stringResource(R.string.add_to_playlist_new_playlist_button_text)) { menu = false; newPlaylist = true }
            SheetRow(Icons.Outlined.Edit, stringResource(R.string.context_menu_rename_folder)) { menu = false; renaming = true }
            SheetRow(Icons.Outlined.Delete, stringResource(R.string.folder_delete)) { menu = false; deletingFolder = true }
        }
    }
    if (deletingFolder) androidx.compose.material3.AlertDialog(
        onDismissRequest = { deletingFolder = false },
        containerColor = SheetColor,
        text = { Text(stringResource(R.string.folder_delete_confirm, name), color = AppText) },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = {
                deletingFolder = false
                // The folder is gone from the library at once; a refusal brings it back.
                LibraryFolders.delete(context, uri)
                navController.navigateUp()
            }) { Text(stringResource(R.string.actions_picker_delete), color = AppDestructive) }
        },
        dismissButton = { androidx.compose.material3.TextButton(onClick = { deletingFolder = false }) { Text(stringResource(R.string.cancel), color = AppText) } },
    )
    // A Spotify playlist created straight inside this folder.
    if (newPlaylist) NameDialog(stringResource(R.string.create_playlist_content_description), context.getString(R.string.my_playlist),
        stringResource(R.string.create_playlist_create_button), onDismiss = { newPlaylist = false }) { playlistName ->
        newPlaylist = false
        SpotifySync.createPlaylistWithTrack(context, playlistName, "", folderUri = uri)
    }
    if (renaming) NameDialog(stringResource(R.string.context_menu_rename_folder), name, stringResource(R.string.local_rename),
        onDismiss = { renaming = false }) { newName ->
        renaming = false
        val before = name
        name = newName
        LibraryFolders.rename(context, uri, newName) { ok -> if (!ok) name = before }
    }
    Column(Modifier.fillMaxSize().background(AppBackground)) {
        val n = entries?.size
        PageHeader(name, n?.let { pluralStringResource(R.plurals.add_to_playlist_folder_subtitle_playlists_count, it, it.toString()) }, navController) { menu = true }
        when (val list = entries) {
            null -> Loader(SkeletonKind.Header)
            else -> {
                com.music.spotui.data.local.LibraryOrder.version
                val arranged = com.music.spotui.data.local.LibraryOrder.run { arrange(list, sort, custom(uri), 0) }
                androidx.compose.material3.pulltorefresh.PullToRefreshBox(
                    isRefreshing = refreshing, onRefresh = { refreshing = true; generation++ }, modifier = Modifier.fillMaxSize(),
                ) {
                    SumUpLibraryScreen(PaddingValues(0.dp), arranged, emptyList(), navController, reorderLevel = uri, onChanged = { generation++ })
                }
            }
        }
    }
}

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun LocalItemRow(item: LocalItem, current: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().pressable(0.97f, onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        GlideImage(
            transition = CrossFade,
            model = item.artUri, contentDescription = null, contentScale = ContentScale.Crop,
            loading = placeholder(R.drawable.placeholder), failure = placeholder(R.drawable.local_files_cover),
            modifier = Modifier.size(48.dp).clip(RoundedCornerShape(4.dp)),
        )
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            Text(item.title, color = if (current) Green else AppText, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (item.artist.isNotBlank()) Text(item.artist, color = AppMuted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Phone files in a list ([items]), played by the local player; swipe right to left to remove. */
fun androidx.compose.foundation.lazy.LazyListScope.localItems(items: List<LocalItem>, context: String, onRemove: (LocalItem) -> Unit) {
    itemsIndexed(items, key = { _, i -> i.key }) { index, item ->
        val np = SongPlayer.nowPlaying.value
        val track = item.toTrack()
        SwipeToRemove(item.key, onRemove = { onRemove(item) }) {
            LocalItemRow(item, LocalPlayer.active.value && np.uri == "local:${track.id}") {
                LocalPlayer.play(items.map { it.toTrack() }, index, context = context)
            }
        }
    }
}

/** A playlist kept on this phone. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalPlaylistScreen(navController: NavController, id: String) {
    val playlist = LocalPlaylists.playlists[id]
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    if (playlist == null) {
        LaunchedEffect(Unit) { navController.navigateUp() }
        return
    }
    if (menu) com.music.spotui.ui.components.LookSheet(onDismissRequest = { menu = false }, containerColor = SheetColor) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
            SheetRow(Icons.Outlined.Edit, stringResource(R.string.local_rename)) { menu = false; renaming = true }
            SheetRow(Icons.Outlined.Delete, stringResource(R.string.local_delete_playlist)) {
                menu = false
                navController.navigateUp()
                LocalPlaylists.delete(id)
            }
        }
    }
    if (renaming) NameDialog(stringResource(R.string.local_rename), playlist.name, stringResource(R.string.local_rename),
        onDismiss = { renaming = false }) { renaming = false; LocalPlaylists.rename(id, it) }
    Column(Modifier.fillMaxSize().background(AppBackground)) {
        PageHeader(playlist.name, stringResource(R.string.local_playlist_subtitle) + " • " +
            pluralStringResource(R.plurals.add_to_playlist_subtitle_songs_count, playlist.items.size, playlist.items.size.toString()),
            navController) { menu = true }
        if (playlist.items.isEmpty()) Text(stringResource(R.string.local_playlist_empty), color = AppMuted, fontSize = 14.sp,
            modifier = Modifier.padding(20.dp))
        else LazyColumn(contentPadding = PaddingValues(bottom = com.music.spotui.ui.components.bottomBarsSpace()), modifier = Modifier.fillMaxSize()) {
            item {
                Row(Modifier.fillMaxWidth().padding(16.dp, 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.weight(1f))
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.size(52.dp).pressable(0.92f) {
                            LocalPlayer.play(playlist.items.map { it.toTrack() }, 0, context = playlist.name)
                        }.clip(CircleShape).background(Green),
                    ) {
                        Icon(painterResource(R.drawable.play_svgrepo_com), stringResource(R.string.content_description_play_button), tint = Color.Black, modifier = Modifier.size(24.dp))
                    }
                }
            }
            localItems(playlist.items, playlist.name) { LocalPlaylists.remove(id, it.key) }
        }
    }
}

/**
 * "Add to playlist" for a phone file: a playlist on this phone (or a new one), or one of
 * the user's Spotify playlists, where it is shown under "Added on this device" (Spotify
 * cannot hold phone files, so it stays on this phone).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalGlideComposeApi::class)
@Composable
fun AddLocalToPlaylistSheet(track: LocalTrack, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var spotifyLists by remember { mutableStateOf<List<com.music.spotui.data.api.EditablePlaylist>?>(null) }
    var creating by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        spotifyLists = com.music.spotui.data.api.EditablePlaylists.loadWithCovers()
    }
    fun added(name: String) {
        Toast.makeText(context, context.getString(R.string.local_added_to, name), Toast.LENGTH_SHORT).show()
        onDismiss()
    }
    if (creating) {
        NameDialog(stringResource(R.string.local_playlist_new), context.getString(R.string.my_playlist),
            stringResource(R.string.create_playlist_create_button), onDismiss = { creating = false }) { name ->
            val p = LocalPlaylists.create(name)
            LocalPlaylists.add(p.id, track)
            added(p.name)
        }
        return
    }
    com.music.spotui.ui.components.LookSheet(onDismissRequest = onDismiss, containerColor = SheetColor) {
        LazyColumn(Modifier.navigationBarsPadding().heightIn(max = 560.dp)) {
            item {
                Text(stringResource(R.string.context_menu_add_to_playlist), color = AppText, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(20.dp, 4.dp, 20.dp, 4.dp))
                Text(stringResource(R.string.local_add_note), color = AppMuted, fontSize = 12.sp,
                    modifier = Modifier.padding(20.dp, 0.dp, 20.dp, 8.dp))
                SheetRow(Icons.AutoMirrored.Filled.PlaylistAdd, stringResource(R.string.add_to_playlist_new_playlist_button_text),
                    stringResource(R.string.local_playlist_subtitle)) { creating = true }
            }
            items(LocalPlaylists.list(), key = { "l:" + it.id }) { p ->
                PickRow(name = p.name, detail = stringResource(R.string.local_playlist_subtitle), cover = null) {
                    LocalPlaylists.add(p.id, track); added(p.name)
                }
            }
            when (val lists = spotifyLists) {
                null -> item { Text(stringResource(R.string.loading_playlists), color = Color.Gray, fontSize = 14.sp, modifier = Modifier.padding(20.dp, 12.dp)) }
                else -> items(lists, key = { "s:" + it.id }) { p ->
                    PickRow(name = p.name, detail = stringResource(R.string.local_added_section), cover = p.imageUrl) {
                        LocalPlaylists.addToSpotify(p.id, track); added(p.name)
                    }
                }
            }
            item { Spacer(Modifier.height(12.dp)) }
        }
    }
}

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun PickRow(name: String, detail: String, cover: String?, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        if (cover == null) Image(painterResource(R.drawable.local_files_cover), null, modifier = Modifier.size(48.dp).clip(RoundedCornerShape(4.dp)))
        else GlideImage(transition = CrossFade, model = cover, contentDescription = null, contentScale = ContentScale.Crop,
            loading = placeholder(R.drawable.placeholder), failure = placeholder(R.drawable.placeholder),
            modifier = Modifier.size(48.dp).clip(RoundedCornerShape(4.dp)))
        Column(Modifier.padding(start = 12.dp)) {
            Text(name, color = AppText, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(detail, color = AppMuted, fontSize = 12.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
    }
}


/**
 * A library row's ⋯ menu: pin it at the top, move a playlist or folder into another folder
 * (or back to the top level), delete a folder. Each shows in the list at once ([LibraryEdits]),
 * which reads itself again when Spotify has had time to catch up; [onChanged] is kept for callers.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryItemSheet(entry: LibraryEntry, onDismiss: () -> Unit, onChanged: () -> Unit) {
    val context = LocalContext.current
    var moving by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var merging by remember { mutableStateOf(false) }
    // The change shows in the list at once and is written in the app's own scope (the sheet is
    // closed by then); a refusal puts the list back and says so.
    fun act(edit: LibraryEdits.Edit, block: suspend () -> Unit) {
        LibraryEdits.run(context, edit, write = { block(); LibraryFolders.changed() })
        onDismiss()
    }
    val api = app.myspoti.host.android.MyspotiRuntime.api
    com.music.spotui.ui.components.LookSheet(onDismissRequest = onDismiss, containerColor = SheetColor) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
            Text(entry.name, color = AppText, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.padding(20.dp, 0.dp, 20.dp, 8.dp))
            if (!moving) {
                // A saved playlist or album: all its songs into the queue (read to the end first).
                if (entry.uri.startsWith("spotify:playlist:") || entry.uri.startsWith("spotify:album:")) {
                    SheetRow(Icons.AutoMirrored.Outlined.PlaylistPlay, stringResource(R.string.context_menu_play_next)) {
                        com.music.spotui.di.CollectionQueue.queue(entry.uri, next = true); onDismiss()
                    }
                    SheetRow(Icons.AutoMirrored.Outlined.QueueMusic, stringResource(R.string.context_menu_add_to_queue)) {
                        com.music.spotui.di.CollectionQueue.queue(entry.uri, next = false); onDismiss()
                    }
                }
                SheetRow(Icons.Outlined.PushPin, stringResource(if (entry.pinned) R.string.library_unpin else R.string.library_pin)) {
                    act(LibraryEdits.Pin(entry.uri, !entry.pinned)) { if (entry.pinned) api.library.unpin(entry.uri) else api.library.pin(entry.uri) }
                }
                if (entry.isPlaylist || entry.isFolder) SheetRow(Icons.Outlined.DriveFileMove, stringResource(R.string.library_move_to_folder)) { moving = true }
                // A saved playlist or album: all its songs into another playlist of yours.
                if (entry.uri.startsWith("spotify:playlist:") || entry.uri.startsWith("spotify:album:"))
                    SheetRow(Icons.AutoMirrored.Filled.PlaylistAdd, stringResource(R.string.context_menu_add_to_playlist)) { merging = true }
                if (entry.isFolder) SheetRow(Icons.Outlined.Delete, stringResource(R.string.folder_delete)) { deleting = true }
            } else {
                val folders by androidx.compose.runtime.produceState<List<app.myspoti.api.LibraryItem>?>(null) {
                    value = runCatching { api.library.items(folderUri = null, limit = 200).items.filter { it.kind == app.myspoti.api.MediaKind.FOLDER && it.uri != entry.uri } }
                        .getOrDefault(emptyList())
                }
                SheetRow(Icons.Outlined.Home, stringResource(R.string.library_top_level)) { act(LibraryEdits.Move(entry, null)) { api.library.moveToFolder(entry.uri, null) } }
                folders?.forEach { f ->
                    SheetRow(Icons.Outlined.Folder, f.name) { act(LibraryEdits.Move(entry, f.uri)) { api.library.moveToFolder(entry.uri, f.uri) } }
                }
            }
        }
    }
    if (merging) com.music.spotui.ui.components.AddCollectionToPlaylistSheet(entry.uri, entry.name) { merging = false; onDismiss() }
    if (deleting) androidx.compose.material3.AlertDialog(
        onDismissRequest = { deleting = false },
        containerColor = SheetColor,
        text = { Text(stringResource(R.string.folder_delete_confirm, entry.name), color = AppText) },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = { deleting = false; act(LibraryEdits.Remove(entry.uri)) { api.library.deleteFolder(entry.uri) } }) {
                Text(stringResource(R.string.actions_picker_delete), color = AppDestructive)
            }
        },
        dismissButton = { androidx.compose.material3.TextButton(onClick = { deleting = false }) { Text(stringResource(R.string.cancel), color = AppText) } },
    )
}
