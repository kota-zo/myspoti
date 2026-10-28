package com.music.spotui.ui.components

import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.myspoti.api.MergePlan
import app.myspoti.api.PlaylistMerge
import app.myspoti.engine.R
import app.myspoti.host.android.MyspotiRuntime
import com.music.spotui.data.api.EditablePlaylist
import com.music.spotui.data.api.EditablePlaylists
import com.music.spotui.data.api.SpotifySync
import com.music.spotui.ui.theme.AppMuted
import com.music.spotui.ui.theme.AppText
import com.music.spotui.ui.theme.tone
import kotlinx.coroutines.launch

/**
 * "Add playlist to another playlist" / an album's "Add to playlist": every song of [sourceUri]
 * (a playlist or an album) goes to the bottom of the playlist the user picks. As in the Web
 * Player, the target is read first and a dialog asks when songs are already there: "Add all" or
 * "Add new ones" when some are, "Add anyway" or "Don't add" when all are.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddCollectionToPlaylistSheet(sourceUri: String, title: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var playlists by remember { mutableStateOf<List<EditablePlaylist>?>(null) }
    var query by remember { mutableStateOf("") }
    var reading by remember { mutableStateOf(false) }
    var asking by remember { mutableStateOf<Pair<EditablePlaylist, MergePlan>?>(null) }
    LaunchedEffect(Unit) { playlists = EditablePlaylists.load() }
    val shown = remember(playlists, query) {
        playlists.orEmpty().filter { "spotify:playlist:${it.id}" != sourceUri && matchesFind(query, it.name) }
    }

    fun say(text: String) = Handler(Looper.getMainLooper()).post { Toast.makeText(context, text, Toast.LENGTH_SHORT).show() }

    fun write(target: EditablePlaylist, uris: List<String>) {
        onDismiss()
        if (uris.isEmpty()) return
        SpotifySync.addUrisToPlaylist(context, target.id, uris) { ok ->
            say(if (ok) context.getString(R.string.add_collection_done, uris.size, target.name) else context.getString(R.string.playlist_change_refused))
        }
    }

    fun pick(target: EditablePlaylist) {
        if (reading) return
        reading = true
        scope.launch {
            val plan = runCatching { PlaylistMerge.plan(MyspotiRuntime.api, "spotify:playlist:${target.id}", sourceUri) }.getOrNull()
            reading = false
            when {
                plan == null -> { say(context.getString(R.string.playlist_change_refused)); onDismiss() }
                plan.all.isEmpty() -> { say(context.getString(R.string.add_collection_nothing)); onDismiss() }
                plan.hasDuplicates -> asking = target to plan
                else -> write(target, plan.all)
            }
        }
    }

    com.music.spotui.ui.components.LookSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = tone(0xFF1A1A1A)) {
        Column(modifier = Modifier.navigationBarsPadding().imePadding()) {
            Text(
                title, color = AppText, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(20.dp, 4.dp, 20.dp, 12.dp),
            )
            FindInListField(
                query = query, onQuery = { query = it },
                hint = stringResource(R.string.add_to_playlist_search_hint),
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            val list = playlists
            when {
                list == null || reading -> Text(
                    stringResource(if (reading) R.string.add_collection_reading else R.string.loading_playlists),
                    color = AppMuted, fontSize = 14.sp, modifier = Modifier.padding(20.dp, 12.dp),
                )
                shown.isEmpty() -> Text(
                    stringResource(if (list.isEmpty()) R.string.add_to_playlist_empty else R.string.add_to_playlist_no_match, query.trim()),
                    color = AppMuted, fontSize = 14.sp, modifier = Modifier.padding(20.dp, 12.dp),
                )
                else -> LazyColumn(modifier = Modifier.heightIn(max = 420.dp).padding(top = 4.dp)) {
                    items(shown.size) { i -> PickRow(shown[i]) { pick(shown[i]) } }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    asking?.let { (target, plan) ->
        val some = plan.someDuplicate
        AlertDialog(
            // Outside the dialog: nothing is added (the Web Player's onOutside).
            onDismissRequest = { asking = null; onDismiss() },
            containerColor = tone(0xFF282828),
            title = { Text(stringResource(if (some) R.string.duplicate_title_some else R.string.duplicate_title_all), color = AppText) },
            text = { Text(stringResource(if (some) R.string.duplicate_text_some else R.string.duplicate_text_all, target.name), color = AppMuted) },
            confirmButton = {
                TextButton(onClick = {
                    asking = null
                    if (some) write(target, plan.fresh) else onDismiss()
                }) { Text(stringResource(if (some) R.string.duplicate_add_new else R.string.duplicate_dont_add), color = com.music.spotui.ui.theme.AppAccent) }
            },
            dismissButton = {
                TextButton(onClick = { asking = null; write(target, plan.all) }) {
                    Text(stringResource(if (some) R.string.duplicate_add_all else R.string.duplicate_add_anyway), color = AppText)
                }
            },
        )
    }
}

/** The ⋯ menu of an album page (or Liked Songs): play it next, queue it, and the album's "Add to playlist". */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumOptionsSheet(albumUri: String, name: String, known: com.music.spotui.data.entity.ContextPageModel? = null, onDismiss: () -> Unit) {
    var adding by remember { mutableStateOf(false) }
    if (adding) { AddCollectionToPlaylistSheet(albumUri, name, onDismiss); return }
    com.music.spotui.ui.components.LookSheet(onDismissRequest = onDismiss, containerColor = tone(0xFF282828)) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
            Text(name, color = AppText, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(20.dp, 0.dp, 20.dp, 8.dp))
            QueueListRows(albumUri, known, onDismiss)
            // Liked Songs has no playlist to copy from: only the albums.
            if (albumUri.startsWith("spotify:album:")) OptionRow(Icons.Outlined.AddCircleOutline, stringResource(R.string.context_menu_add_to_playlist)) { adding = true }
        }
    }
}
