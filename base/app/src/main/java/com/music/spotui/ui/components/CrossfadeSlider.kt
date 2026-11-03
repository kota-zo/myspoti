package com.music.spotui.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.myspoti.engine.R
import com.music.spotui.data.preferences.CrossfadePref
import kotlin.math.roundToInt

/**
 * The crossfade's length on a slider, 1 to [CrossfadePref.MAX] seconds a second at a time (it was
 * four fixed choices: 3, 6, 9, 12 s). The value shows while it moves and is saved when it is let go,
 * so the player is not handed every step of a drag. Shared by both looks: [accent] fills the track,
 * [text] and [muted] colour the label and the bounds.
 */
@Composable
fun CrossfadeSlider(accent: Color, text: Color, muted: Color, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    var value by remember(CrossfadePref.seconds) { mutableFloatStateOf(CrossfadePref.seconds.coerceIn(1, CrossfadePref.MAX).toFloat()) }
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.settings_crossfade_length), color = text, fontSize = 16.sp, modifier = Modifier.weight(1f))
            Text(stringResource(R.string.settings_crossfade_seconds, value.roundToInt()), color = accent, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
        Slider(
            value = value,
            onValueChange = { value = it },
            onValueChangeFinished = { CrossfadePref.set(ctx, value.roundToInt()) },
            valueRange = 1f..CrossfadePref.MAX.toFloat(),
            steps = CrossfadePref.MAX - 2,
            colors = SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent, inactiveTrackColor = muted.copy(alpha = 0.3f),
                activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent),
            modifier = Modifier.padding(top = 2.dp),
        )
        Row(Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.settings_crossfade_seconds, 1), color = muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Text(stringResource(R.string.settings_crossfade_seconds, CrossfadePref.MAX), color = muted, fontSize = 12.sp)
        }
    }
}
