package com.music.spotui.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.myspoti.engine.R
import com.music.spotui.di.LocalPlayer
import com.music.spotui.di.SongPlayer
import com.music.spotui.extensions.ExtensionAudio
import com.music.spotui.extensions.Extensions
import com.music.spotui.extensions.SPOTIFY_SOURCE
import com.music.spotui.ui.immersive.Immersive
import com.music.spotui.ui.immersive.pages.ImmersiveChoiceRow
import com.music.spotui.ui.immersive.pages.ImmersiveGroupRow
import com.music.spotui.ui.theme.AppAccent
import com.music.spotui.ui.theme.AppMuted
import com.music.spotui.ui.theme.AppText

/**
 * What the quality sheet's Source section shows, the same in both looks: the audio sources the
 * user can pick (Spotify and the extensions on, in the order they are asked now), their names, the
 * one the song plays from (null: being asked, a download), the one first for this session (null:
 * the order of Settings) and the one being asked for the song after a tap.
 */
private class SourceChoices(val ids: List<String>, val names: Map<String, String>, val playing: String?, val lead: String?, val asking: String?)

/**
 * The quality sheet's Source section: another audio source for this song and the next ones, until
 * the app restarts (SongPlayer.switchSource, Extensions.setSessionSource); "Back to my order" while
 * one is. Nothing without an audio-source extension on, or for an episode (Spotify's own).
 * Drawn in each look's own way ([ImmersiveSourcePicker], [ClassicSourcePicker]).
 */
@Composable
fun SourcePicker(modifier: Modifier = Modifier) {
    val registry by Extensions.registry.collectAsState()
    val lead by Extensions.sessionSource.collectAsState()
    val answers = ExtensionAudio.answers.intValue
    val uri = SongPlayer.nowPlaying.value.uri
    val local = LocalPlayer.active.value
    val ids = remember(registry, lead) { Extensions.audioChoices() }
    if (ids.none { it != SPOTIFY_SOURCE } || uri.startsWith("spotify:episode:")) return
    val spotify = stringResource(R.string.sources_spotify)
    val names = remember(ids, registry, spotify) { ids.associateWith { if (it == SPOTIFY_SOURCE) spotify else registry.find(it)?.name ?: it } }
    val playing = remember(uri, local, answers, SongPlayer.routing) {
        when {
            SongPlayer.routing -> null
            !local -> SPOTIFY_SOURCE
            // A song played from its download plays from no source.
            else -> LocalPlayer.current()?.uri?.takeIf { it.scheme == ExtensionAudio.SCHEME }?.lastPathSegment?.let(ExtensionAudio::providerOf)
        }
    }
    var asking by remember { mutableStateOf<String?>(null) }
    val ctx = LocalContext.current
    val pick = { id: String ->
        // One ask at a time: a second tap waits for the first source's answer.
        if (asking == null) {
            asking = id
            SongPlayer.switchSource(id) { r ->
                asking = null
                val name = names[id] ?: id
                when (r) {
                    SongPlayer.SourceSwitch.MISSING -> ctx.getString(R.string.source_pick_missing, name)
                    SongPlayer.SourceSwitch.NEXT_SONG -> ctx.getString(R.string.source_pick_next, name)
                    SongPlayer.SourceSwitch.PLAYING -> null
                }?.let { android.widget.Toast.makeText(ctx, it, android.widget.Toast.LENGTH_LONG).show() }
            }
        }
    }
    val reset = { Extensions.setSessionSource(null) }
    val c = SourceChoices(ids, names, playing, lead, asking)
    if (com.music.spotui.ui.navigation.isImmersive()) ImmersiveSourcePicker(c, pick, reset, modifier)
    else ClassicSourcePicker(c, pick, reset, modifier)
}

/** What a source's row says under its name: being asked, first for this session, playing now. */
@Composable
private fun detailOf(c: SourceChoices, id: String): String? = when (id) {
    c.asking -> "…"
    c.lead -> stringResource(R.string.source_pick_temporary)
    c.playing -> stringResource(R.string.source_pick_playing)
    else -> null
}

/** Immersive: as its Settings pages and the streaming levels under it, a grouped card with a check on the source playing. */
@Composable
private fun ImmersiveSourcePicker(c: SourceChoices, pick: (String) -> Unit, reset: () -> Unit, modifier: Modifier) {
    Column(modifier.fillMaxWidth().padding(horizontal = 20.dp).selectableGroup()) {
        Text(stringResource(R.string.source_pick_title), style = Immersive.Headline, modifier = Modifier.padding(top = 4.dp))
        Text(stringResource(R.string.source_pick_detail), style = Immersive.Caption.copy(color = Color.White.copy(alpha = 0.72f)),
            modifier = Modifier.padding(top = 2.dp, bottom = 10.dp))
        Column(Modifier.fillMaxWidth().clip(Immersive.Card).background(Immersive.Raised.copy(alpha = 0.92f))) {
            c.ids.forEachIndexed { i, id ->
                ImmersiveChoiceRow(c.names[id] ?: id, selected = id == c.playing, detail = detailOf(c, id), onClick = { pick(id) })
                if (i < c.ids.lastIndex || c.lead != null) Box(Modifier.padding(start = 16.dp).fillMaxWidth().height(0.5.dp).background(Immersive.Separator))
            }
            if (c.lead != null) ImmersiveGroupRow(stringResource(R.string.source_pick_reset), detail = stringResource(R.string.source_pick_reset_detail),
                icon = Icons.Rounded.Restore, onClick = reset)
        }
    }
}

/** Classic: as the streaming levels under it, radio rows, the source playing selected. */
@Composable
private fun ClassicSourcePicker(c: SourceChoices, pick: (String) -> Unit, reset: () -> Unit, modifier: Modifier) {
    Column(modifier.fillMaxWidth().selectableGroup()) {
        Text(stringResource(R.string.source_pick_title), color = AppText, fontSize = 17.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
        Text(stringResource(R.string.source_pick_detail), color = AppMuted, fontSize = 13.sp,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 4.dp))
        for (id in c.ids) {
            val on = id == c.playing
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
                    .selectable(selected = on, role = Role.RadioButton, onClick = { pick(id) })
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                RadioButton(selected = on, onClick = null, modifier = Modifier.padding(horizontal = 8.dp),
                    colors = RadioButtonDefaults.colors(selectedColor = AppAccent, unselectedColor = AppMuted))
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text(c.names[id] ?: id, color = if (on) AppAccent else AppText, fontSize = 16.sp)
                    detailOf(c, id)?.let { Text(it, color = if (id == c.lead) AppAccent else AppMuted, fontSize = 12.sp) }
                }
            }
        }
        if (c.lead != null) TextButton(onClick = reset, modifier = Modifier.padding(horizontal = 8.dp)) {
            Text(stringResource(R.string.source_pick_reset), color = AppAccent, fontSize = 14.sp)
        }
    }
}
