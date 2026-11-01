package com.music.spotui.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.myspoti.engine.R
import app.myspoti.extensions.Action
import app.myspoti.extensions.Area
import com.music.spotui.extensions.Extensions
import com.music.spotui.extensions.InstalledExtension
import com.music.spotui.extensions.SPOTIFY_SOURCE
import com.music.spotui.ui.theme.AppAccent
import com.music.spotui.ui.theme.AppMuted
import com.music.spotui.ui.theme.AppText

/** The colours and type a source list is drawn with: the classic look's or the Immersive one's. */
internal class SourceLook(
    val text: Color, val muted: Color, val accent: Color,
    val name: TextStyle, val line: TextStyle,
) {
    companion object {
        val Classic = SourceLook(AppText, AppMuted, AppAccent,
            TextStyle(color = AppText, fontSize = 15.sp, fontWeight = FontWeight.SemiBold), TextStyle(color = AppMuted, fontSize = 12.sp))
    }
}

/**
 * The order the source [action] (audio or downloads) asks in, for Settings: one row per
 * extension with only what matters (its place, name and whether it answers) and arrows to
 * reorder; its version, author, reason and last use open under it on demand. [spotifyLast]: the
 * audio order, where Spotify has a row of its own that moves too (the sources under it are not
 * asked).
 */
@Composable
internal fun SourceOrderList(action: Action, spotifyLast: Boolean, look: SourceLook, onOpen: (String) -> Unit, rowPadding: Modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
    val registry by Extensions.registry.collectAsState()
    val errors by Extensions.errors.collectAsState()
    val statuses by Extensions.statuses.collectAsState()
    val activity by Extensions.activity.state.collectAsState()
    // The order the user set: a source picked in the player for this session is only said above it.
    val line = remember(registry, action) { Extensions.savedSourceLine(action).filter { spotifyLast || it != SPOTIFY_SOURCE } }
    val lead by Extensions.sessionSource.collectAsState()
    val spotifyAt = line.indexOf(SPOTIFY_SOURCE)
    Column(Modifier.fillMaxWidth()) {
        lead?.takeIf { action == Action.AUDIO_SOURCE }?.let { id ->
            val name = if (id == SPOTIFY_SOURCE) stringResource(R.string.sources_spotify) else registry.find(id)?.name ?: id
            Column(Modifier.fillMaxWidth().then(rowPadding)) {
                Text(stringResource(R.string.source_pick_active, name), style = look.line.copy(color = look.accent))
                TextButton(onClick = { Extensions.setSessionSource(null) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                    Text(stringResource(R.string.source_pick_reset), color = look.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        line.forEachIndexed { i, id ->
            val arrows: @Composable () -> Unit = { MoveArrows(action, id, i, line.size, look) }
            val e = registry.find(id)
            if (id == SPOTIFY_SOURCE) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().then(rowPadding)) {
                Rank(i + 1, look)
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.sources_spotify), style = look.name, maxLines = 1)
                    Text(stringResource(R.string.sources_spotify_detail), style = look.line, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                arrows()
            } else if (e != null) {
                SourceRow(e, i, action, errors[e.id], statuses[e.id], activity.lastOrNull { it.extensionId == e.id && it.area == Area.AUDIO },
                    below = spotifyAt in 0 until i, look, rowPadding, arrows) { onOpen(e.id) }
            }
        }
        if (line.isEmpty()) Text(stringResource(R.string.downloads_source_row_off), style = look.line, modifier = rowPadding)
    }
}

@Composable
private fun MoveArrows(action: Action, id: String, i: Int, count: Int, look: SourceLook) {
    if (count < 2) return
    IconButton(onClick = { Extensions.moveSource(action, id, -1) }, enabled = i > 0, modifier = Modifier.size(34.dp)) {
        Icon(Icons.Default.KeyboardArrowUp, stringResource(R.string.extensions_move_up), tint = if (i > 0) look.text else look.muted.copy(alpha = 0.3f))
    }
    IconButton(onClick = { Extensions.moveSource(action, id, 1) }, enabled = i < count - 1, modifier = Modifier.size(34.dp)) {
        Icon(Icons.Default.KeyboardArrowDown, stringResource(R.string.extensions_move_down), tint = if (i < count - 1) look.text else look.muted.copy(alpha = 0.3f))
    }
}

@Composable
private fun Rank(n: Int, look: SourceLook) =
    Text("$n", color = look.muted, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.widthIn(min = 22.dp))

@Composable
private fun SourceRow(
    e: InstalledExtension, i: Int, action: Action, error: String?, status: app.myspoti.extensions.ExtensionStatus?,
    last: app.myspoti.extensions.ActivityLog.Entry?, below: Boolean, look: SourceLook, rowPadding: Modifier, arrows: @Composable () -> Unit, onOpen: () -> Unit,
) {
    var open by rememberSaveable(e.id, action.key) { mutableStateOf(false) }
    val pkg = remember(e.id, e.sha256) { Extensions.pkg(e.id) }
    val problem = stateLine(e, error, status)
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { open = !open }.then(rowPadding)) {
            Rank(i + 1, look)
            ExtIcon(e.id, e.name, null, pkg?.icon, size = 34.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(e.name, style = look.name.copy(color = if (e.enabled && !below) look.text else look.muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
                when {
                    problem != null -> Text(problem.first, style = look.line.copy(color = problem.second), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    !e.enabled -> Text(stringResource(R.string.extension_off), style = look.line, maxLines = 1)
                    below -> Text(stringResource(R.string.sources_below_spotify), style = look.line, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    else -> Text(stringResource(R.string.sources_ready), style = look.line.copy(color = look.accent), maxLines = 1)
                }
            }
            Icon(if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                stringResource(if (open) R.string.sources_details_hide else R.string.sources_details_show), tint = look.muted, modifier = Modifier.size(20.dp))
            arrows()
        }
        // What is only useful now and then, behind the row's chevron.
        AnimatedVisibility(open) {
            Column(Modifier.fillMaxWidth().padding(start = 86.dp, end = 20.dp, bottom = 6.dp)) {
                Text(stringResource(R.string.sources_detail_version, e.version, e.author.ifBlank { "—" }), style = look.line)
                pkg?.manifest?.declared()?.firstOrNull { it.name == action.key }?.why?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = look.line, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
                last?.let {
                    val ago = android.text.format.DateUtils.getRelativeTimeSpanString(it.at, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS).toString()
                    Text(stringResource(R.string.sources_last_used, it.text, ago), style = look.line, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                TextButton(onClick = onOpen, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                    Text(stringResource(R.string.sources_open), color = look.accent, fontSize = 13.sp)
                }
            }
        }
    }
}
