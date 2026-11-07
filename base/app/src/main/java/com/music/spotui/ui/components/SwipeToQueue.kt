package com.music.spotui.ui.components

import app.myspoti.engine.R

import androidx.compose.ui.res.stringResource

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.music.spotui.data.entity.SongsModel
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Spotify's gesture: swipe a song row sideways to add it to the queue. The row
 * follows the finger over a green strip, ticks once with a vibration when it
 * passes the threshold, adds the song ONCE on release, then springs back.
 */
@Composable
fun SwipeToQueue(song: SongsModel, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val threshold = with(LocalDensity.current) { 96.dp.toPx() }
    val maxDrag = threshold * 1.4f
    val offset = remember { Animatable(0f) }
    var armed by remember { mutableStateOf(false) }
    // The offset moves every drag and spring frame: read it through derived state so only the
    // strip's two visual thresholds, not the whole row, recompose.
    val revealed by remember(offset) { derivedStateOf { offset.value != 0f } }
    val fromStart by remember(offset) { derivedStateOf { offset.value > 0f } }

    Box(modifier = modifier.fillMaxWidth()) {
        if (revealed) {
            Box(
                contentAlignment = if (fromStart) Alignment.CenterStart else Alignment.CenterEnd,
                modifier = Modifier
                    .matchParentSize()
                    .background(if (armed) com.music.spotui.ui.theme.AppAccent else com.music.spotui.ui.theme.AppAccent.copy(alpha = 0.6f)),
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.PlaylistAdd,
                    contentDescription = stringResource(R.string.add_to_queue_action_content_description),
                    tint = Color.Black,
                    modifier = Modifier.padding(horizontal = 24.dp).size(26.dp),
                )
            }
        }
        Box(
            modifier = Modifier
                .offset { IntOffset(offset.value.roundToInt(), 0) }
                .background(com.music.spotui.ui.theme.AppBackground)
                .pointerInput(song.id) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            if (armed && song.spotifyTrackId.isNotBlank()) {
                                com.music.spotui.di.SongPlayer.addToQueue(song.spotifyTrackId)
                                android.widget.Toast.makeText(context, context.getString(R.string.snackbar_added_to_queue), android.widget.Toast.LENGTH_SHORT).show()
                            }
                            armed = false
                            scope.launch { offset.animateTo(0f, spring()) }
                        },
                        onDragCancel = {
                            armed = false
                            scope.launch { offset.animateTo(0f, spring()) }
                        },
                    ) { change, dx ->
                        change.consume()
                        val next = (offset.value + dx).coerceIn(-maxDrag, maxDrag)
                        scope.launch { offset.snapTo(next) }
                        val nowArmed = abs(next) >= threshold
                        if (nowArmed && !armed) haptic.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                        armed = nowArmed
                    }
                },
        ) { content() }
    }
}
