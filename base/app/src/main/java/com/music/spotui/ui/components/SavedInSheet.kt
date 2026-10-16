package com.music.spotui.ui.components

import androidx.compose.ui.res.stringResource

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import com.bumptech.glide.integration.compose.placeholder
import app.myspoti.engine.R
import androidx.compose.ui.res.pluralStringResource
import com.music.spotui.data.api.SpotifySync
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.data.preferences.addLikedSongId
import com.music.spotui.data.preferences.isSongLiked
import com.music.spotui.data.preferences.removeLikedSongId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val SpotifyGreen: Color get() = com.music.spotui.ui.theme.AppAccent

/**
 * Spotify's "Saved in" sheet: Liked Songs plus every user playlist, each row
 * toggling the track in/out of it (mirrored to the real Spotify account), and a
 * stringResource(R.string.add_to_playlist_new_playlist_button_text) action that creates one on Spotify with the track in it.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalGlideComposeApi::class)
@Composable
fun SavedInSheet(
    song: SongsModel,
    context: Context,
    onDismiss: () -> Unit,
    onLikedChanged: (Boolean) -> Unit = {},
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val liked = com.music.spotui.di.rememberTrackSaved(song.spotifyTrackId)
    var playlists by remember { mutableStateOf<List<com.music.spotui.data.api.EditablePlaylist>?>(null) }
    // playlistId → does it contain this track (filled lazily per row).
    val membership = remember(song.spotifyTrackId) { mutableStateMapOf<String, Boolean>() }
    // Rows with a change on its way: a second tap would race it (an add and a remove at once).
    val busy = remember(song.spotifyTrackId) { mutableStateMapOf<String, Boolean>() }
    var creating by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    // Folders opened so far (uri to name); the last one is shown.
    val folders = remember { androidx.compose.runtime.mutableStateListOf<Pair<String, String>>() }
    val folder = folders.lastOrNull()

    LaunchedEffect(folder?.first) {
        // Only playlists the user can change (theirs and collaborative ones), level by level.
        playlists = null
        playlists = com.music.spotui.data.api.EditablePlaylists.level(folder?.first)
    }
    androidx.activity.compose.BackHandler(enabled = folders.isNotEmpty()) { folders.removeAt(folders.lastIndex) }

    fun createNow() {
        val name = newName.trim().ifBlank { context.getString(R.string.my_playlist) }
        creating = false
        newName = ""
        SpotifySync.createPlaylistWithTrack(context, name, song.spotifyTrackId, folderUri = folder?.first)
        onDismiss()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF1A1A1A),
    ) {
        Column(modifier = Modifier.navigationBarsPadding().imePadding()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp, 4.dp, 20.dp, 12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f, fill = false)) {
                    if (folder != null) Icon(
                        Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, tint = Color.White,
                        modifier = Modifier.padding(end = 12.dp).size(24.dp).clickable { folders.removeAt(folders.lastIndex) },
                    )
                    Text(folder?.second ?: stringResource(R.string.add_to_playlist_saved_in_section_header), color = Color.White,
                        fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(
                    stringResource(R.string.add_to_playlist_new_playlist_button_text),
                    color = SpotifyGreen,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { creating = true },
                )
            }

            if (creating) {
                TextField(
                    value = newName,
                    onValueChange = { newName = it },
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.playlist_name), color = Color.Gray) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { createNow() }),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color(0xFF2A2A2A),
                        unfocusedContainerColor = Color(0xFF2A2A2A),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        cursorColor = SpotifyGreen,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp, 0.dp, 20.dp, 8.dp)
                        .clip(RoundedCornerShape(8.dp)),
                )
                Row(modifier = Modifier.padding(20.dp, 0.dp, 20.dp, 8.dp)) {
                    Text(
                        stringResource(R.string.create_playlist_create_button),
                        color = SpotifyGreen,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { createNow() },
                    )
                    Spacer(Modifier.width(24.dp))
                    Text(
                        stringResource(R.string.add_to_playlist_button_cancel),
                        color = Color.Gray,
                        fontSize = 14.sp,
                        modifier = Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { creating = false; newName = "" },
                    )
                }
            }

            // ── Liked Songs ──
            if (folder == null) SavedInRow(
                name = stringResource(R.string.collection_liked_songs_title),
                subtitle = "",
                saved = liked,
                cover = {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(
                                Brush.linearGradient(listOf(Color(0xFF4A39EA), Color(0xFF868AE1)))
                            ),
                    ) {
                        Icon(Icons.Default.Favorite, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                    }
                },
            ) {
                com.music.spotui.di.SongPlayer.setTrackSaved(song.spotifyTrackId, !liked)
                onLikedChanged(!liked)
            }

            when (val list = playlists) {
                null -> Text(stringResource(R.string.loading_playlists), color = Color.Gray, fontSize = 14.sp, modifier = Modifier.padding(20.dp, 12.dp))
                else -> LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                    items(list.size) { i ->
                        val pl = list[i]
                        if (pl.isFolder) {
                            FolderRow(pl.name, pluralStringResource(R.plurals.add_to_playlist_folder_subtitle_playlists_count, pl.trackCount, pl.trackCount.toString())) {
                                folders.add(pl.folderUri!! to pl.name)
                            }
                            return@items
                        }
                        // Resolve membership lazily (cached per session in SpotifySync).
                        LaunchedEffect(pl.id, song.spotifyTrackId) {
                            if (membership[pl.id] == null && song.spotifyTrackId.isNotBlank()) {
                                membership[pl.id] = withContext(Dispatchers.IO) {
                                    SpotifySync.playlistTrackIds(context, pl.id).contains(song.spotifyTrackId)
                                }
                            }
                        }
                        val saved = membership[pl.id] == true
                        SavedInRow(
                            name = pl.name,
                            subtitle = pluralStringResource(R.plurals.liked_songs_header_metadata_text, pl.trackCount, pl.trackCount),
                            saved = saved,
                            busy = busy[pl.id] == true || (membership[pl.id] == null && song.spotifyTrackId.isNotBlank()),
                            cover = {
                                GlideImage(
                                    modifier = Modifier
                                        .size(48.dp)
                                        .clip(RoundedCornerShape(4.dp)),
                                    model = pl.imageUrl,
                                    contentScale = ContentScale.Crop,
                                    failure = placeholder(R.drawable.placeholder),
                                    loading = placeholder(R.drawable.placeholder),
                                    contentDescription = "",
                                )
                            },
                        ) {
                            if (song.spotifyTrackId.isBlank() || busy[pl.id] == true || membership[pl.id] == null) return@SavedInRow
                            membership[pl.id] = !saved
                            busy[pl.id] = true
                            // Done: say where the song went. Refused by Spotify: back to the real state, and say so.
                            val done: (Boolean) -> Unit = { ok ->
                                android.os.Handler(android.os.Looper.getMainLooper()).post {
                                    busy.remove(pl.id)
                                    if (!ok) membership[pl.id] = saved
                                    val text = when {
                                        !ok -> context.getString(R.string.playlist_change_refused)
                                        saved -> context.getString(R.string.playlist_removed_from, pl.name)
                                        else -> context.getString(R.string.local_added_to, pl.name)
                                    }
                                    android.widget.Toast.makeText(context, text, android.widget.Toast.LENGTH_SHORT).show()
                                }
                            }
                            if (saved) SpotifySync.removeTrackFromPlaylist(context, pl.id, song.spotifyTrackId, done)
                            else SpotifySync.addTrackToPlaylist(context, pl.id, song.spotifyTrackId, done)
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.padding(10.dp))
        }
    }
}

@Composable
private fun FolderRow(name: String, subtitle: String, onOpen: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(48.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xFF333333)),
        ) {
            Icon(Icons.Outlined.Folder, contentDescription = null, tint = Color(0xFFB3B3B3), modifier = Modifier.size(24.dp))
        }
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            Text(name, color = Color.White, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, color = Color(0xFFB3B3B3), fontSize = 13.sp, maxLines = 1)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = Color(0xFFB3B3B3))
    }
}

@Composable
private fun SavedInRow(
    name: String,
    subtitle: String,
    saved: Boolean,
    cover: @Composable () -> Unit,
    busy: Boolean = false,
    onToggle: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onToggle() }
            .padding(20.dp, 8.dp),
    ) {
        cover()
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 14.dp, end = 8.dp),
        ) {
            Text(name, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotBlank()) {
                Text(subtitle, color = Color.Gray, fontSize = 13.sp, maxLines = 1)
            }
        }
        if (busy) {
            androidx.compose.material3.CircularProgressIndicator(color = SpotifyGreen, strokeWidth = 2.dp, modifier = Modifier.padding(3.dp).size(20.dp))
        } else if (saved) {
            Icon(Icons.Default.CheckCircle, contentDescription = stringResource(R.string.curation_button_saved_text), tint = SpotifyGreen, modifier = Modifier.size(26.dp))
        } else {
            Icon(Icons.Default.Add, contentDescription = stringResource(R.string.add_to_playlist_new_playlist_button_text), tint = Color(0xFFB3B3B3), modifier = Modifier.size(26.dp))
        }
    }
}
