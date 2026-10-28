package com.music.spotui.ui.components

import com.music.spotui.ui.theme.tone
import com.music.spotui.ui.theme.AppText
import com.music.spotui.ui.theme.AppMuted

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.myspoti.engine.R
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.CrossFade
import com.bumptech.glide.integration.compose.GlideImage
import com.bumptech.glide.integration.compose.placeholder
import com.music.spotui.data.api.EditablePlaylist
import com.music.spotui.data.api.EditablePlaylists
import com.music.spotui.data.api.SpotifySync
import com.music.spotui.data.entity.SongsModel

/**
 * "Add to another playlist": one tap copies the track into a playlist the user picks.
 *
 * Add-only on purpose — the "Saved in" sheet stays the place where the track is toggled in
 * and out of several lists; this one is the short path asked for by the testers, with a
 * search field because a library folds into dozens of rows. The playlist the track is
 * already shown in is filtered out: adding it there would only make a duplicate entry.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalGlideComposeApi::class)
@Composable
fun AddToPlaylistSheet(
    song: SongsModel,
    context: Context,
    onDismiss: () -> Unit,
    excludePlaylistId: String? = null,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var playlists by remember { mutableStateOf<List<EditablePlaylist>?>(null) }
    var query by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        // Folders flattened: adding to a playlist does not need the folder tree.
        playlists = EditablePlaylists.loadWithCovers()
    }
    // Filtered in one pass; accent-insensitive like the other "find" fields of the app.
    val shown = remember(playlists, query) {
        playlists.orEmpty().filter { it.id != excludePlaylistId && matchesFind(query, it.name) }
    }

    fun addTo(playlist: EditablePlaylist) {
        onDismiss()
        SpotifySync.addTrackToPlaylist(context, playlist.id, song.spotifyTrackId) { ok ->
            // The write runs on the main dispatcher already; posted anyway so the toast
            // survives the sheet's teardown.
            Handler(Looper.getMainLooper()).post {
                val text = if (ok) context.getString(R.string.local_added_to, playlist.name)
                else context.getString(R.string.playlist_change_refused)
                Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
            }
        }
    }

    com.music.spotui.ui.components.LookSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = tone(0xFF1A1A1A),
    ) {
        Column(modifier = Modifier.navigationBarsPadding().imePadding()) {
            Text(
                stringResource(R.string.context_menu_add_to_another_playlist),
                color = AppText,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(20.dp, 4.dp, 20.dp, 12.dp),
            )
            FindInListField(
                query = query,
                onQuery = { query = it },
                hint = stringResource(R.string.add_to_playlist_search_hint),
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            val list = playlists
            when {
                list == null -> Text(
                    stringResource(R.string.loading_playlists),
                    color = androidx.compose.ui.graphics.Color.Gray, fontSize = 14.sp,
                    modifier = Modifier.padding(20.dp, 12.dp),
                )
                shown.isEmpty() -> Text(
                    stringResource(if (list.isEmpty()) R.string.add_to_playlist_empty else R.string.add_to_playlist_no_match, query.trim()),
                    color = androidx.compose.ui.graphics.Color.Gray, fontSize = 14.sp,
                    modifier = Modifier.padding(20.dp, 12.dp),
                )
                else -> LazyColumn(modifier = Modifier.heightIn(max = 420.dp).padding(top = 4.dp)) {
                    items(shown.size) { i ->
                        val pl = shown[i]
                        PickRow(pl) { addTo(pl) }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
internal fun PickRow(playlist: EditablePlaylist, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        GlideImage(
            transition = CrossFade,
            modifier = Modifier.size(48.dp).clip(RoundedCornerShape(4.dp)),
            model = playlist.imageUrl,
            contentScale = ContentScale.Crop,
            failure = placeholder(R.drawable.placeholder),
            loading = placeholder(R.drawable.placeholder),
            contentDescription = "",
        )
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Text(playlist.name, color = AppText, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                pluralStringResource(R.plurals.liked_songs_header_metadata_text, playlist.trackCount, playlist.trackCount),
                color = androidx.compose.ui.graphics.Color.Gray, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Icon(
            Icons.Default.Add,
            contentDescription = null,
            tint = AppMuted,
            modifier = Modifier.size(22.dp),
        )
    }
}
