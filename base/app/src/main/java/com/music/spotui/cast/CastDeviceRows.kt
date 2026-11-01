package com.music.spotui.cast

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.myspoti.engine.R
import app.myspoti.host.android.MyspotiRuntime
import com.music.spotui.di.LocalPlayer
import com.music.spotui.ui.theme.AppAccent
import com.music.spotui.ui.theme.AppMuted
import com.music.spotui.ui.theme.AppText

/**
 * The Cast devices of the network in the device picker, under the Spotify Connect devices: Myspoti's
 * own songs (extensions, downloads, the phone's files) are cast to them. While a Spotify song
 * plays, a tap wakes the device's Spotify receiver ([CastSpotify]) and the song moves to it as a
 * Spotify Connect device; one already listed above under the same name is not repeated. While
 * casting: back to this phone, and the device's volume.
 */
@Composable
fun CastDeviceRows(onDismiss: () -> Unit) {
    val context = LocalContext.current
    DisposableEffect(Unit) {
        CastDiscovery.start(context)
        onDispose { CastDiscovery.stop() }
    }
    val casting = CastPlayback.device.value
    val ownSong = LocalPlayer.active.value
    if (casting != null) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)) {
            Icon(Icons.Default.VolumeUp, contentDescription = null, tint = AppMuted, modifier = Modifier.size(22.dp))
            Slider(
                value = CastPlayback.volume.floatValue,
                onValueChange = { CastPlayback.setVolume(it) },
                colors = SliderDefaults.colors(thumbColor = AppAccent, activeTrackColor = AppAccent),
                modifier = Modifier.padding(start = 12.dp).weight(1f),
            )
        }
        CastRow(Icons.Default.Smartphone, stringResource(R.string.connect_phone_is_self), null) {
            CastPlayback.stop(back = true); onDismiss()
        }
    }
    val waking = CastSpotify.waking.value
    val connectNames = MyspotiRuntime.spotify.devices.devices.collectAsState().value.filter { !it.local }.map { it.name }.toSet()
    CastDiscovery.devices.filter { it.id != casting?.id && (ownSong || it.name !in connectNames) }.forEach { d ->
        val detail = when {
            waking?.id == d.id -> stringResource(R.string.cast_connecting)
            !ownSong -> stringResource(R.string.cast_spotify_detail)
            else -> d.model.ifBlank { stringResource(R.string.cast_device_detail) }
        }
        CastRow(Icons.Default.Cast, d.name, detail, enabled = waking == null) {
            if (ownSong) CastPlayback.start(d) else CastSpotify.start(d)
            onDismiss()
        }
    }
    if (casting != null && CastPlayback.connecting.value)
        CastRow(Icons.Default.CastConnected, casting.name, stringResource(R.string.cast_connecting), current = true) {}
}

@Composable
private fun CastRow(icon: ImageVector, name: String, detail: String?, current: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else 0.5f).padding(horizontal = 20.dp, vertical = 14.dp),
    ) {
        Icon(icon, contentDescription = null, tint = if (current) AppAccent else AppText, modifier = Modifier.size(28.dp))
        Spacer(Modifier.width(16.dp))
        Column {
            Text(name, color = if (current) AppAccent else AppText, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            if (detail != null) Text(detail, color = AppMuted, fontSize = 13.sp)
        }
    }
}
