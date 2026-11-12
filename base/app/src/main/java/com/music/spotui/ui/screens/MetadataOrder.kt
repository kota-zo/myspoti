package com.music.spotui.ui.screens

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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.myspoti.engine.R
import com.music.spotui.data.catalog.CatalogRuntime
import com.music.spotui.data.catalog.MetadataOrder
import com.music.spotui.data.catalog.MetadataPref

/**
 * Settings → Sources → Metadata: the services searches, albums and artists come from, in the order they are asked,
 * each with a switch (one stays on) and arrows to reorder, as the audio sources' list. Spotify is asked only while an
 * account is signed in, which its row says. Drawn in the classic or the Immersive [look].
 */
@Composable
internal fun MetadataOrderList(look: SourceLook, rowPadding: Modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
    val ctx = LocalContext.current
    val order = MetadataPref.order
    val off = MetadataPref.off
    val signedIn = CatalogRuntime.signedIn()
    Column(Modifier.fillMaxWidth()) {
        order.forEachIndexed { i, id ->
            val on = id !in off
            val asked = on && (id != MetadataOrder.SPOTIFY || signedIn)
            val name = MetadataOrder.name(id)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().then(rowPadding)) {
                Text("${i + 1}", color = look.muted, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.widthIn(min = 22.dp))
                Column(Modifier.weight(1f)) {
                    Text(name, style = look.name.copy(color = if (asked) look.text else look.muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val line = when {
                        !on -> stringResource(R.string.metadata_off)
                        id == MetadataOrder.SPOTIFY && !signedIn -> stringResource(R.string.metadata_signed_out)
                        id == MetadataOrder.SPOTIFY -> stringResource(R.string.metadata_spotify_detail)
                        else -> stringResource(R.string.metadata_asked)
                    }
                    Text(line, style = look.line.copy(color = if (asked) look.accent else look.muted), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.width(8.dp))
                val label = stringResource(R.string.metadata_switch, name)
                Switch(
                    checked = on,
                    onCheckedChange = { MetadataPref.setOn(ctx, id, it) },
                    enabled = MetadataOrder.canTurnOff(order, off, id),
                    colors = SwitchDefaults.colors(checkedTrackColor = look.accent),
                    modifier = Modifier.semantics { contentDescription = label },
                )
                IconButton(onClick = { MetadataPref.move(ctx, id, -1) }, enabled = i > 0, modifier = Modifier.size(34.dp)) {
                    Icon(Icons.Default.KeyboardArrowUp, stringResource(R.string.extensions_move_up), tint = if (i > 0) look.text else look.muted.copy(alpha = 0.3f))
                }
                IconButton(onClick = { MetadataPref.move(ctx, id, 1) }, enabled = i < order.lastIndex, modifier = Modifier.size(34.dp)) {
                    Icon(Icons.Default.KeyboardArrowDown, stringResource(R.string.extensions_move_down), tint = if (i < order.lastIndex) look.text else look.muted.copy(alpha = 0.3f))
                }
            }
        }
        if (order != MetadataOrder.ALL || off.isNotEmpty()) {
            TextButton(onClick = { MetadataPref.reset(ctx) }, modifier = rowPadding, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                Text(stringResource(R.string.metadata_reset), color = look.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
