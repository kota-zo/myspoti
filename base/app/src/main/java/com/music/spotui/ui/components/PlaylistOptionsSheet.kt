package com.music.spotui.ui.components

import com.music.spotui.ui.theme.AppDestructive
import com.music.spotui.ui.theme.tone
import com.music.spotui.ui.theme.AppText
import com.music.spotui.ui.theme.AppMuted

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.automirrored.outlined.PlaylistPlay
import androidx.compose.material.icons.automirrored.outlined.QueueMusic
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material.icons.outlined.GroupRemove
import androidx.compose.material.icons.outlined.PersonRemove
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.myspoti.api.PlaylistAccess
import app.myspoti.engine.R
import app.myspoti.host.android.MyspotiRuntime
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import com.bumptech.glide.integration.compose.placeholder
import com.music.spotui.data.entity.ContextPageModel
import com.music.spotui.ui.theme.AppAccent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A playlist's ⋯ menu. On your own playlists: edit the name, description and cover, choose
 * who can see it (public, private, collaborative) and delete it. On someone else's: their
 * profile. [onChanged] reloads the page after an edit; [onDeleted] leaves it. Changes show
 * at once and are written in the app's own scope, so closing the sheet does not cancel them.
 */
@OptIn(ExperimentalMaterial3Api::class, com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi::class)
@Composable
fun PlaylistOptionsSheet(
    page: ContextPageModel,
    onDismiss: () -> Unit,
    onChanged: () -> Unit,
    /** The name and description just saved, to show before Spotify has answered. */
    onEdited: (String, String) -> Unit,
    onDeleted: () -> Unit,
    onProfile: (String) -> Unit,
    /** "Add to this playlist" (search and suggestions), on your own playlists. */
    onAddSongs: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    fun isMine() = com.music.spotui.data.api.ProfileCache.id?.let { page.ownerUri == "spotify:user:$it" } == true
    // The profile id is fetched once per session and is no Compose state: read again until it
    // is there (opened before it was, the sheet never showed the owner's options at all).
    var mine by remember { mutableStateOf(isMine()) }
    LaunchedEffect(Unit) {
        com.music.spotui.data.api.ProfileCache.ensure(context)
        kotlinx.coroutines.withTimeoutOrNull(5_000) { while (com.music.spotui.data.api.ProfileCache.id == null) kotlinx.coroutines.delay(100) }
        mine = isMine()
    }
    var access by remember { mutableStateOf<PlaylistAccess?>(null) }
    var published by remember { mutableStateOf<Boolean?>(null) }
    var editing by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var renamingBlend by remember { mutableStateOf(false) }
    var leavingBlend by remember { mutableStateOf(false) }
    var leavingPlaylist by remember { mutableStateOf(false) }
    var stoppingCollab by remember { mutableStateOf(false) }
    var mergingInto by remember { mutableStateOf(false) }
    LaunchedEffect(page.uri, mine) {
        if (mine) {
            access = runCatching { MyspotiRuntime.api.playlists.access(page.uri) }.getOrNull()
            published = runCatching { MyspotiRuntime.api.playlists.published(page.uri) }.getOrNull()
        }
    }

    // Taps are sent one at a time, in order (SpotifySync); a refused one puts back what Spotify really has.
    fun restore() = scope.launch {
        access = runCatching { MyspotiRuntime.api.playlists.access(page.uri) }.getOrNull()
        published = runCatching { MyspotiRuntime.api.playlists.published(page.uri) }.getOrNull()
        android.widget.Toast.makeText(context, R.string.playlist_change_refused, android.widget.Toast.LENGTH_SHORT).show()
    }

    fun setAccess(target: PlaylistAccess) {
        if (access == target) return
        access = target
        // Made private, it leaves the profile too (as in the official apps).
        if (target == PlaylistAccess.PRIVATE) published = false
        com.music.spotui.data.api.SpotifySync.setPlaylistAccess(page.uri, target) { ok -> if (!ok) restore() }
    }

    fun setPublished(on: Boolean) {
        if (published == on) return
        published = on
        com.music.spotui.data.api.SpotifySync.setPlaylistPublished(page.uri, on) { ok -> if (!ok) restore() }
    }

    com.music.spotui.ui.components.LookSheet(onDismissRequest = onDismiss, containerColor = tone(0xFF282828)) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
            // As the official app: the playlist itself on top (cover, name, owner), then one action a row.
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(20.dp, 0.dp, 20.dp, 12.dp)) {
                com.bumptech.glide.integration.compose.GlideImage(
                    model = page.coverUri, contentDescription = null, contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = Modifier.size(52.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(4.dp)),
                )
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(page.name, color = AppText, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (page.ownerName.isNotBlank()) Text(stringResource(R.string.playlist_by, page.ownerName), color = AppMuted, fontSize = 13.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            androidx.compose.material3.HorizontalDivider(color = AppText.copy(alpha = 0.08f))
            Spacer(Modifier.height(6.dp))
            val share = { SpotifyCode.show(ShareTarget(page.uri, page.name, page.ownerName, page.coverUri)); onDismiss() }
            // The whole playlist into the queue, in its order (a long one is read to its end first).
            QueueListRows(page.uri, page, onDismiss)
            // Every song of this playlist into another one of yours (Web Player: "Add playlist to other playlist").
            if (page.uri.startsWith("spotify:playlist:")) OptionRow(Icons.Outlined.AddCircleOutline, stringResource(R.string.add_playlist_to_playlist)) { mergingInto = true }
            if (page.isBlend) {
                // A Blend is Spotify's: renamed, shared and left through its own service.
                OptionRow(Icons.Outlined.Edit, stringResource(R.string.blend_rename)) { renamingBlend = true }
                OptionRow(Icons.Outlined.Share, stringResource(R.string.blend_invite_more)) {
                    scope.launch {
                        val link = BlendInfo.recent[page.uri]?.group?.link
                            ?: runCatching { MyspotiRuntime.api.blends.group(page.uri).link }.getOrNull()
                        if (link == null) android.widget.Toast.makeText(context, R.string.blend_failed, android.widget.Toast.LENGTH_SHORT).show()
                        else { BlendInfo.share(context, link); onDismiss() }
                    }
                }
                OptionRow(Icons.AutoMirrored.Outlined.Logout, stringResource(R.string.blend_leave)) { leavingBlend = true }
            } else if (mine) {
                onAddSongs?.let { add -> OptionRow(Icons.Outlined.AddCircleOutline, stringResource(R.string.add_songs_title)) { onDismiss(); add() } }
                OptionRow(Icons.Outlined.Edit, stringResource(R.string.playlist_edit_details)) { editing = true }
                // Collaborative is not enough: only those who open its invitation link can add songs
                // (a plain link only shows it). Inviting makes it collaborative, as in the official app.
                OptionRow(Icons.Outlined.PersonAdd, stringResource(R.string.playlist_invite_collaborators)) {
                    if (access != PlaylistAccess.COLLABORATIVE) setAccess(PlaylistAccess.COLLABORATIVE)
                    scope.launch {
                        val link = runCatching { MyspotiRuntime.api.playlists.collaboratorInvite(page.uri) }.getOrNull()
                        if (link == null) { android.widget.Toast.makeText(context, R.string.playlist_invite_failed, android.widget.Toast.LENGTH_SHORT).show(); return@launch }
                        val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
                            .putExtra(android.content.Intent.EXTRA_TEXT, context.getString(R.string.playlist_invite_message, page.name, link))
                        context.startActivity(android.content.Intent.createChooser(send, null).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                        onDismiss()
                    }
                }
                OptionRow(Icons.Outlined.QrCode2, stringResource(R.string.context_menu_share), onClick = share)
                // Who sees it: one action naming what it does next (not three choices to read).
                val a = access
                fun change(target: PlaylistAccess, said: Int) {
                    setAccess(target)
                    android.widget.Toast.makeText(context, said, android.widget.Toast.LENGTH_SHORT).show()
                }
                when (a) {
                    null -> OptionRow(Icons.Outlined.Lock, stringResource(R.string.playlist_access_title), enabled = false) {}
                    PlaylistAccess.PRIVATE -> OptionRow(Icons.Outlined.Public, stringResource(R.string.playlist_make_public)) { change(PlaylistAccess.PUBLIC, R.string.playlist_now_public) }
                    PlaylistAccess.COLLABORATIVE -> OptionRow(Icons.Outlined.GroupRemove, stringResource(R.string.playlist_stop_collab)) { stoppingCollab = true }
                    else -> OptionRow(Icons.Outlined.Lock, stringResource(R.string.playlist_make_private)) { change(PlaylistAccess.PRIVATE, R.string.playlist_now_private) }
                }
                // Only a playlist others may see can be listed on the profile.
                if (a != null && a != PlaylistAccess.PRIVATE && published != null) {
                    val on = published == true
                    OptionRow(if (on) Icons.Outlined.PersonRemove else Icons.Outlined.Person,
                        stringResource(if (on) R.string.playlist_remove_profile else R.string.playlist_add_profile)) {
                        setPublished(!on)
                        android.widget.Toast.makeText(context, if (on) R.string.playlist_now_off_profile else R.string.playlist_now_on_profile, android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
                OptionRow(Icons.Outlined.Delete, stringResource(R.string.local_delete_playlist)) { deleting = true }
            } else {
                OptionRow(Icons.Outlined.QrCode2, stringResource(R.string.context_menu_share), onClick = share)
                // The Web Player offers it only where canCancelMembership is true (a collaborator, not the owner).
                if (page.canLeave) OptionRow(Icons.AutoMirrored.Outlined.Logout, stringResource(R.string.playlist_leave)) { leavingPlaylist = true }
            }
            if (!mine && !page.isBlend && page.ownerUri.startsWith("spotify:user:")) {
                OptionRow(Icons.Outlined.Person, stringResource(R.string.go_to_profile)) {
                    onDismiss(); onProfile(page.ownerUri.substringAfterLast(':'))
                }
            }
            // Entries from extensions (menu.items), after the app's own.
            com.music.spotui.extensions.ExtensionMenuEntries(
                if (page.type == "album") app.myspoti.extensions.Surface.ALBUM_MENU else app.myspoti.extensions.Surface.PLAYLIST_MENU,
                page.uri, page.name, page.ownerName, onDone = onDismiss,
            )
        }
    }

    if (mergingInto) AddCollectionToPlaylistSheet(page.uri, stringResource(R.string.add_playlist_to_playlist)) { mergingInto = false; onDismiss() }

    if (editing) EditDetailsDialog(page, onDismiss = { editing = false }) { name, description, cover ->
        editing = false
        // Name and description show at once, in the page and in the library; the cover needs the page again.
        if (name != page.name || description != page.description) onEdited(name, description)
        val renamed = if (name != page.name) com.music.spotui.data.api.LibraryEdits.Rename(page.uri, name) else null
        onDismiss()
        com.music.spotui.data.api.LibraryEdits.run(context, renamed, write = {
            if (name != page.name) MyspotiRuntime.api.playlists.rename(page.uri, name)
            if (description != page.description) MyspotiRuntime.api.playlists.setDescription(page.uri, description)
            when (cover) {
                is CoverChange.New -> MyspotiRuntime.api.playlists.setCover(page.uri, cover.jpeg)
                CoverChange.Remove -> MyspotiRuntime.api.playlists.setCover(page.uri, null)
                CoverChange.Keep -> {}
            }
        }, onDone = { ok ->
            if (ok) android.widget.Toast.makeText(context, R.string.playlist_saved, android.widget.Toast.LENGTH_SHORT).show()
            // Refused: the page goes back to what Spotify has; a new cover shows only once it is read again.
            if (!ok || cover != CoverChange.Keep) onChanged()
        })
    }

    if (renamingBlend) com.music.spotui.ui.screens.NameDialog(stringResource(R.string.blend_rename), page.name,
        stringResource(R.string.local_rename), onDismiss = { renamingBlend = false }) { name ->
        renamingBlend = false
        onDismiss()
        if (name == page.name) return@NameDialog
        onEdited(name, page.description)
        com.music.spotui.data.api.LibraryEdits.run(context, com.music.spotui.data.api.LibraryEdits.Rename(page.uri, name),
            write = { MyspotiRuntime.api.blends.rename(page.uri, name) },
            onDone = { ok -> if (!ok) onChanged() })
    }

    if (leavingBlend) AlertDialog(
        onDismissRequest = { leavingBlend = false },
        containerColor = tone(0xFF282828),
        title = { Text(stringResource(R.string.blend_leave), color = AppText) },
        text = { Text(stringResource(R.string.blend_leave_confirm, page.name), color = AppMuted) },
        confirmButton = {
            TextButton(onClick = {
                leavingBlend = false
                // The Blend leaves the library and the page closes now; a refusal brings it back.
                BlendInfo.recent.remove(page.uri)
                com.music.spotui.data.api.LibraryEdits.run(context, com.music.spotui.data.api.LibraryEdits.Remove(page.uri),
                    write = { MyspotiRuntime.api.blends.leave(page.uri) })
                onDismiss(); onDeleted()
            }) { Text(stringResource(R.string.blend_leave_button), color = AppDestructive) }
        },
        dismissButton = { TextButton(onClick = { leavingBlend = false }) { Text(stringResource(R.string.cancel), color = AppText) } },
    )

    if (stoppingCollab) {
        // Public (listed on the profile): collaborators are removed; private: they become listeners.
        val public = published == true
        AlertDialog(
            onDismissRequest = { stoppingCollab = false },
            containerColor = tone(0xFF282828),
            title = { Text(stringResource(R.string.playlist_stop_collab), color = AppText) },
            text = { Text(stringResource(if (public) R.string.playlist_stop_collab_public else R.string.playlist_stop_collab_private, page.name), color = AppMuted) },
            confirmButton = {
                TextButton(onClick = {
                    stoppingCollab = false
                    access = if (public) PlaylistAccess.PUBLIC else PlaylistAccess.PRIVATE
                    android.widget.Toast.makeText(context, R.string.playlist_now_solo, android.widget.Toast.LENGTH_SHORT).show()
                    // Written in order with the other visibility taps; a refusal puts back what Spotify has.
                    com.music.spotui.data.api.SpotifySync.stopPlaylistCollaboration(page.uri) { ok -> if (ok) onChanged() else restore() }
                }) { Text(stringResource(R.string.playlist_stop_collab), color = AppDestructive) }
            },
            dismissButton = { TextButton(onClick = { stoppingCollab = false }) { Text(stringResource(R.string.cancel), color = AppText) } },
        )
    }

    if (leavingPlaylist) AlertDialog(
        onDismissRequest = { leavingPlaylist = false },
        containerColor = tone(0xFF282828),
        title = { Text(stringResource(R.string.playlist_leave), color = AppText) },
        text = { Text(stringResource(R.string.playlist_leave_confirm, page.name), color = AppMuted) },
        confirmButton = {
            TextButton(onClick = {
                leavingPlaylist = false
                // The page closes now (as in the Web Player); a refusal says so, and the library is read again.
                com.music.spotui.data.api.LibraryEdits.run(context, null,
                    write = { MyspotiRuntime.api.playlists.leave(page.uri) },
                    onDone = { ok -> if (ok) com.music.spotui.data.api.LibraryEdits.reloadSoon() })
                onDismiss(); onDeleted()
            }) { Text(stringResource(R.string.playlist_leave_button), color = AppDestructive) }
        },
        dismissButton = { TextButton(onClick = { leavingPlaylist = false }) { Text(stringResource(R.string.cancel), color = AppText) } },
    )

    if (deleting) AlertDialog(
        onDismissRequest = { deleting = false },
        containerColor = tone(0xFF282828),
        text = { Text(stringResource(R.string.playlist_delete_confirm, page.name), color = AppText) },
        confirmButton = {
            TextButton(onClick = {
                deleting = false
                // The playlist leaves the library and the page closes now; a refusal brings it back.
                com.music.spotui.data.api.LibraryEdits.run(context, com.music.spotui.data.api.LibraryEdits.Remove(page.uri),
                    write = { MyspotiRuntime.api.playlists.delete(page.uri) })
                onDismiss(); onDeleted()
            }) { Text(stringResource(R.string.actions_picker_delete), color = AppDestructive) }
        },
        dismissButton = { TextButton(onClick = { deleting = false }) { Text(stringResource(R.string.cancel), color = AppText) } },
    )
}

private sealed interface CoverChange {
    data object Keep : CoverChange
    data object Remove : CoverChange
    class New(val jpeg: ByteArray, val preview: Bitmap) : CoverChange
}

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun EditDetailsDialog(page: ContextPageModel, onDismiss: () -> Unit, onSave: (String, String, CoverChange) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(page.name) }
    var description by remember { mutableStateOf(page.description) }
    var cover by remember { mutableStateOf<CoverChange>(CoverChange.Keep) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            val made = withContext(Dispatchers.Default) { runCatching { coverJpeg(context, uri) }.getOrNull() }
            if (made != null) cover = CoverChange.New(made.first, made.second)
        }
    }
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = AppText, unfocusedTextColor = AppText, cursorColor = AppAccent,
        focusedBorderColor = AppAccent, unfocusedBorderColor = Color(0xFF555555),
        focusedLabelColor = AppAccent, unfocusedLabelColor = AppMuted,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = tone(0xFF282828),
        title = { Text(stringResource(R.string.playlist_edit_details), color = AppText) },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val c = cover
                    Box(Modifier.size(96.dp).clip(RoundedCornerShape(4.dp)).background(tone(0xFF3A3A3A))
                        .clickable { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                        when (c) {
                            is CoverChange.New -> Image(c.preview.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.size(96.dp))
                            CoverChange.Remove -> {}
                            CoverChange.Keep -> GlideImage(page.coverUri, null, contentScale = ContentScale.Crop,
                                failure = placeholder(R.drawable.placeholder), modifier = Modifier.size(96.dp))
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        TextButton(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                            Text(stringResource(R.string.playlist_change_cover), color = AppText)
                        }
                        TextButton(onClick = { cover = CoverChange.Remove }) {
                            Text(stringResource(R.string.playlist_remove_cover), color = AppMuted)
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.playlist_name_label)) },
                    singleLine = true, colors = fieldColors, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(description, { description = it }, label = { Text(stringResource(R.string.playlist_description_label)) },
                    minLines = 2, maxLines = 4, colors = fieldColors, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = { onSave(name.trim(), description.trim(), cover) }) {
                Text(stringResource(R.string.playlist_save), color = AppAccent, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel), color = AppText) } },
    )
}

/** The picked image as Spotify takes a cover: square, 640 px, JPEG under 256 KB. */
private fun coverJpeg(context: Context, uri: Uri): Pair<ByteArray, Bitmap> {
    val source = context.contentResolver.openInputStream(uri).use { android.graphics.BitmapFactory.decodeStream(it) }
        ?: error("not an image")
    val side = minOf(source.width, source.height)
    val square = Bitmap.createBitmap(source, (source.width - side) / 2, (source.height - side) / 2, side, side)
    val scaled = Bitmap.createScaledBitmap(square, 640, 640, true)
    var quality = 90
    var bytes: ByteArray
    do {
        bytes = java.io.ByteArrayOutputStream().also { scaled.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()
        quality -= 10
    } while (bytes.size > 256 * 1024 && quality > 30)
    return bytes to scaled
}

@Composable
internal fun OptionRow(icon: ImageVector, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp)
            .then(if (enabled) Modifier else Modifier.alpha(0.4f)),
    ) {
        Icon(icon, null, tint = AppText, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(16.dp))
        Text(label, color = AppText, fontSize = 16.sp)
    }
}

/** "Play next" and "Add to queue" for the whole list [uri]; [known] is its page when the screen has it. */
@Composable
internal fun QueueListRows(uri: String, known: ContextPageModel?, onDismiss: () -> Unit) {
    OptionRow(Icons.AutoMirrored.Outlined.PlaylistPlay, stringResource(R.string.context_menu_play_next)) {
        com.music.spotui.di.CollectionQueue.queue(uri, next = true, known = known)
        onDismiss()
    }
    OptionRow(Icons.AutoMirrored.Outlined.QueueMusic, stringResource(R.string.context_menu_add_to_queue)) {
        com.music.spotui.di.CollectionQueue.queue(uri, next = false, known = known)
        onDismiss()
    }
}
