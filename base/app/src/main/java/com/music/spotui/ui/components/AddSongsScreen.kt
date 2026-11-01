package com.music.spotui.ui.components

import com.music.spotui.ui.theme.tone
import com.music.spotui.ui.theme.AppText
import com.music.spotui.ui.theme.AppMuted
import com.music.spotui.ui.theme.AppBackground
import com.music.spotui.ui.theme.AppAccent

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.myspoti.engine.R
import app.myspoti.host.android.MyspotiRuntime
import com.bumptech.glide.integration.compose.CrossFade
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import com.music.spotui.data.api.Response
import com.music.spotui.data.entity.ContextPageModel
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.di.Graph
import com.music.spotui.di.SongPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

private val Green get() = AppAccent
private const val SUGGESTIONS = 20
private const val TYPE_PAUSE_MS = 300L

/**
 * "Add to this playlist", as in the official Android app: a Spotify search with a + on every
 * song, and Spotify's suggestions for the playlist while nothing is typed. Songs are added one
 * tap each without leaving the screen; [onClose] says whether any was added (the page reloads).
 */
@OptIn(ExperimentalGlideComposeApi::class)
@Composable
fun AddSongsScreen(page: ContextPageModel, onClose: (added: Boolean) -> Unit) {
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val playlistId = page.uri.substringAfterLast(':')
    val inList = remember(page.uri) { page.tracks.mapTo(HashSet()) { it.spotifyTrackId } }
    val added = remember { mutableStateListOf<String>() }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<SongsModel>?>(null) }
    var suggestions by remember { mutableStateOf<List<SongsModel>?>(null) }
    var suggestionsRound by remember { mutableStateOf(0) }
    val seen = remember { HashSet<String>() }
    val focus = remember { FocusRequester() }

    LaunchedEffect(suggestionsRound) {
        suggestions = null
        val skip = (inList + added + seen).map { "spotify:track:$it" }
        val got = withContext(Dispatchers.IO) {
            runCatching { MyspotiRuntime.api.playlists.recommended(page.uri, limit = SUGGESTIONS, skip = skip) }
                .getOrDefault(emptyList()).map(Graph.api::songOf)
        }.filter { it.spotifyTrackId !in inList }
        got.forEach { seen += it.spotifyTrackId }
        suggestions = got
    }
    LaunchedEffect(query) {
        val q = query.trim()
        if (q.isEmpty()) { results = null; return@LaunchedEffect }
        delay(TYPE_PAUSE_MS)
        val r = withContext(Dispatchers.IO) { Graph.api.searchTracks(q).first { it !is Response.Loading } }
        results = (r as? Response.Success)?.data.orEmpty()
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    val close = { onClose(added.isNotEmpty()) }
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(Modifier.fillMaxSize().background(AppBackground).statusBarsPadding().navigationBarsPadding().imePadding()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp)) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.size(48.dp).clip(CircleShape).clickable(onClick = close)) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.add_songs_close), tint = AppText)
                }
                Text(stringResource(R.string.add_songs_title), color = AppText, fontSize = 17.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f).padding(start = 4.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(44.dp)
                    .clip(RoundedCornerShape(8.dp)).background(tone(0xFF2A2A2A)).padding(horizontal = 12.dp),
            ) {
                Icon(Icons.Default.Search, contentDescription = null, tint = AppMuted, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) Text(stringResource(R.string.add_songs_search_hint), color = AppMuted, fontSize = 15.sp)
                    BasicTextField(
                        value = query, onValueChange = { query = it }, singleLine = true,
                        textStyle = TextStyle(color = AppText, fontSize = 15.sp), cursorBrush = SolidColor(Green),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    )
                }
                if (query.isNotEmpty()) SearchClearButton(focus, AppText) { query = "" }
            }
            val searching = query.isNotBlank()
            val list = if (searching) results else suggestions
            LazyColumn(Modifier.weight(1f).padding(top = 8.dp)) {
                if (!searching) item(key = "suggestions-title") {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp)) {
                        Text(stringResource(R.string.recommended_songs), color = AppText, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        TextButton(onClick = { suggestionsRound++ }) {
                            Text(stringResource(R.string.recommended_songs_refresh), color = AppText, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                when {
                    list == null -> item(key = "loading") { SkeletonList(count = 8) }
                    list.isEmpty() && searching -> item(key = "none") {
                        Text(stringResource(R.string.add_songs_nothing, query.trim()), color = AppMuted, fontSize = 14.sp,
                            modifier = Modifier.padding(24.dp))
                    }
                    else -> items(list, key = { (if (searching) "r-" else "s-") + it.spotifyTrackId }) { song ->
                        val there = song.spotifyTrackId in inList || song.spotifyTrackId in added
                        AddSongRow(song, there, onPlay = { SongPlayer.playSong(song.url, context, songs = listOf(song)) }) {
                            if (there) return@AddSongRow
                            added += song.spotifyTrackId
                            com.music.spotui.di.Haptics.confirm()
                            com.music.spotui.data.api.SpotifySync.addTrackToPlaylist(context, playlistId, song.spotifyTrackId) { ok ->
                                if (!ok) android.os.Handler(android.os.Looper.getMainLooper()).post {
                                    added -= song.spotifyTrackId
                                    android.widget.Toast.makeText(context, context.getString(R.string.playlist_change_refused), android.widget.Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun AddSongRow(song: SongsModel, added: Boolean, onPlay: () -> Unit, onAdd: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onPlay).padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
    ) {
        GlideImage(model = song.coverUri, contentDescription = null, contentScale = ContentScale.Crop, transition = CrossFade,
            modifier = Modifier.size(48.dp).clip(RoundedCornerShape(4.dp)))
        Column(Modifier.weight(1f).padding(start = 12.dp, end = 8.dp)) {
            Text(song.title, color = AppText, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (song.explicit) { com.music.spotui.ui.screens.ExplicitBadge(); Spacer(Modifier.width(6.dp)) }
                Text(song.singer, color = AppMuted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(48.dp).clip(CircleShape).clickable(enabled = !added, onClick = onAdd)) {
            if (added) Icon(Icons.Default.CheckCircle, contentDescription = stringResource(R.string.add_songs_added), tint = Green, modifier = Modifier.size(26.dp))
            else Icon(Icons.Outlined.AddCircleOutline, contentDescription = stringResource(R.string.recommended_songs_add), tint = AppText, modifier = Modifier.size(26.dp))
        }
    }
}
