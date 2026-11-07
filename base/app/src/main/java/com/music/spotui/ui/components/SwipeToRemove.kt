package com.music.spotui.ui.components

import com.music.spotui.ui.theme.AppText

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.myspoti.engine.R
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Swipe a row from right to left to remove it: the row follows the finger over a red
 * strip with a bin, ticks past the threshold, and slides out before [onRemove].
 */
@Composable
fun SwipeToRemove(key: Any, onRemove: () -> Unit, content: @Composable () -> Unit) {
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val threshold = with(LocalDensity.current) { 120.dp.toPx() }
    val offset = remember(key) { Animatable(0f) }
    var armed by remember(key) { mutableStateOf(false) }
    var width by remember { mutableFloatStateOf(0f) }
    // The offset moves every drag and spring frame; only the strip appearing is a composition change.
    val revealed by remember(offset) { derivedStateOf { offset.value < 0f } }
    // "Remove animations": the row goes back, or out, at once.
    val reduce = com.music.spotui.ui.look.rememberReduceMotion()
    val slideOut = if (reduce) snap<Float>() else tween(180)
    val springBack = if (reduce) snap<Float>() else spring<Float>()

    Box(modifier = Modifier.fillMaxWidth().onSizeChanged { width = it.width.toFloat() }) {
        if (revealed) {
            Box(
                contentAlignment = Alignment.CenterEnd,
                modifier = Modifier.matchParentSize()
                    .background(if (armed) Color(0xFFE22134) else Color(0xFFE22134).copy(alpha = 0.6f)),
            ) {
                Icon(Icons.Outlined.DeleteOutline, stringResource(R.string.context_menu_undownload), tint = AppText,
                    modifier = Modifier.padding(horizontal = 24.dp).size(26.dp))
            }
        }
        Box(
            modifier = Modifier
                .offset { IntOffset(offset.value.roundToInt(), 0) }
                .background(com.music.spotui.ui.theme.AppBackground)
                .pointerInput(key) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            if (armed) scope.launch {
                                offset.animateTo(-width.coerceAtLeast(threshold), slideOut)
                                onRemove()
                            } else scope.launch { offset.animateTo(0f, springBack) }
                            armed = false
                        },
                        onDragCancel = {
                            armed = false
                            scope.launch { offset.animateTo(0f, springBack) }
                        },
                    ) { change, dx ->
                        change.consume()
                        // Leftwards only (right to left).
                        val next = (offset.value + dx).coerceIn(-(width.coerceAtLeast(threshold)), 0f)
                        scope.launch { offset.snapTo(next) }
                        val nowArmed = -next >= threshold
                        if (nowArmed && !armed) haptic.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                        armed = nowArmed
                    }
                },
        ) { content() }
    }
}
