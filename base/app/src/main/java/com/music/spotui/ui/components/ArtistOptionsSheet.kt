package com.music.spotui.ui.components

import com.music.spotui.ui.theme.tone
import com.music.spotui.ui.theme.AppText
import com.music.spotui.ui.theme.AppMuted

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.myspoti.engine.R
import kotlinx.coroutines.launch

/**
 * B27: the ⋮ of an artist page's action row had no `clickable` and did nothing. It now opens
 * the artist's own menu, with the actions the app can really do for an artist: share its link,
 * and keep it out of (or back in) radios and mixes — the same taste-profile call the track
 * menu's "Don't play this artist" uses.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArtistOptionsSheet(artistId: String, artistName: String, artistImage: String = "", onDismiss: () -> Unit) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val uri = "spotify:artist:$artistId"
    // null = not known yet (or the call failed): the ban row stays disabled rather than lying.
    val banned by produceState<Boolean?>(null, uri) {
        if (artistId.isBlank()) return@produceState
        value = runCatching {
            app.myspoti.host.android.MyspotiRuntime.api.extras.isArtistBanned(listOf(uri)).single()
        }.getOrNull()
    }
    LookSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = tone(0xFF282828)) {
        Column(Modifier.navigationBarsPadding().verticalScroll(rememberScrollState())) {
            Text(
                artistName, color = AppText, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            )
            HorizontalDivider(color = tone(0xFF3E3E3E), thickness = 0.5.dp)
            Spacer(Modifier.height(8.dp))
            ArtistMenuRow(Icons.Default.Share, stringResource(R.string.share_link), enabled = artistId.isNotBlank()) {
                SpotifyCode.show(ShareTarget("spotify:artist:$artistId", artistName, coverUri = artistImage))
                onDismiss()
            }
            ArtistMenuRow(
                Icons.Default.Block,
                stringResource(if (banned == true) R.string.artist_unban else R.string.artist_ban),
                enabled = banned != null,
            ) {
                // The button shows the action it will do, so the flip is derived from the state.
                val keepOut = banned != true
                app.myspoti.host.android.MyspotiRuntime.scope.launch {
                    runCatching { app.myspoti.host.android.MyspotiRuntime.api.extras.setArtistBanned(uri, keepOut) }
                }
                Toast.makeText(
                    context,
                    context.getString(if (keepOut) R.string.artist_banned else R.string.artist_unbanned, artistName),
                    Toast.LENGTH_SHORT,
                ).show()
                com.music.spotui.di.Haptics.confirm()
                onDismiss()
            }
            com.music.spotui.extensions.ExtensionMenuEntries(app.myspoti.extensions.Surface.ARTIST_MENU, if (artistId.isBlank()) "" else uri, artistName, onDone = onDismiss)
            Spacer(Modifier.height(12.dp))
        }
    }
}

/** One row of the artist menu, laid out like the track menu's rows. */
@Composable
private fun ArtistMenuRow(icon: ImageVector, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    val tint = if (enabled) AppText else Color(0xFF6A6A6A)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 20.dp, vertical = 14.dp),
    ) {
        Icon(icon, contentDescription = null, tint = if (enabled) AppMuted else Color(0xFF6A6A6A), modifier = Modifier.size(22.dp))
        Text(label, color = tint, fontSize = 15.sp, modifier = Modifier.padding(start = 16.dp))
    }
}
