package com.music.spotui.ui.components

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.myspoti.engine.R
import com.music.spotui.data.api.LyricsApi
import com.music.spotui.di.SongPlayer
import com.music.spotui.ui.theme.AppMuted
import com.music.spotui.ui.theme.AppText
import com.music.spotui.ui.theme.tone
import com.music.spotui.ui.viewmodel.LyricsViewModel

/**
 * "Try another source": the providers that can give this song's lyrics, the one shown marked,
 * the word-timed ones named, the ones set aside for now dimmed. A tap asks that one (the lyrics
 * shown stay until it answers); the sheet closes on lyrics, and stays with a toast when the
 * provider had none. Shared by both looks, like the other pickers behind the song options.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun LyricsSourceSheet(onDismiss: () -> Unit, vm: LyricsViewModel = viewModel()) {
    val context = LocalContext.current
    val track = remember { SongPlayer.currentLyricsTrack() }
    if (track == null) { LaunchedEffect(Unit) { onDismiss() }; return }
    val state by vm.state.collectAsState()
    val trying by vm.trying.collectAsState()
    val current = (state as? LyricsViewModel.State.Loaded)?.lyrics?.let { LyricsApi.sourceOf(it) }
    val sources = remember(track) { vm.sources(track) }

    // The answer to a try: new lyrics close the sheet, a provider with nothing says so.
    var asked by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(trying) {
        val id = trying
        if (id != null) { asked = id; return@LaunchedEffect }
        val was = asked ?: return@LaunchedEffect
        asked = null
        if (vm.triedNothing.value == was) {
            val name = sources.firstOrNull { it.id == was }?.name ?: was
            Toast.makeText(context, context.getString(R.string.lyrics_source_nothing, name), Toast.LENGTH_SHORT).show()
        } else onDismiss()
    }

    LookSheet(onDismissRequest = onDismiss, containerColor = tone(0xFF282828)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            Text(
                stringResource(R.string.lyrics_other_source), color = AppText, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(20.dp, 8.dp),
            )
            sources.forEach { s ->
                val here = s.id == current
                val detail = listOfNotNull(
                    if (here) stringResource(R.string.lyrics_source_shown) else null,
                    if (s.words) stringResource(R.string.lyrics_source_words) else null,
                    if (s.setAside != null) stringResource(R.string.lyrics_source_aside) else null,
                ).joinToString(" · ")
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        // Set aside: dimmed, still tappable (asking by hand ignores the break).
                        .alpha(if (s.setAside != null) 0.45f else 1f)
                        .clickable(enabled = trying == null) { vm.loadFrom(track, s.id) }
                        .padding(20.dp, 12.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(s.name, color = AppText, fontSize = 16.sp)
                        if (detail.isNotEmpty()) Text(detail, color = AppMuted, fontSize = 12.sp)
                    }
                    if (trying == s.id) CircularProgressIndicator(color = AppText, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                    else if (here) Icon(Icons.Default.Check, contentDescription = null, tint = AppText)
                }
            }
        }
    }
}
