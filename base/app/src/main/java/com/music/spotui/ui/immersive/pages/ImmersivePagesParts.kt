/*
 * Adapted from BitChord (https://github.com/kushagrasinghx/BitChord), GPL-3.0-or-later,
 * app/src/main/java/com/music/bitchord/ui/screens/SettingsSheet.kt (SegmentedControl) at edc1e6f.
 * Modified for Myspoti, 2026-10.
 */
package com.music.spotui.ui.immersive.pages

import com.music.spotui.ui.immersive.sheets.ImmersiveAlertDialog
import com.music.spotui.ui.immersive.sheets.ImmersiveGlassSheet
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.myspoti.engine.R
import com.music.spotui.di.Haptics
import com.music.spotui.ui.immersive.Immersive
import com.music.spotui.ui.immersive.components.immersiveClickable
import com.music.spotui.ui.immersive.components.rememberImmersiveOverscrollFactory
import com.music.spotui.ui.immersive.motion.ImmersiveMotion
import com.music.spotui.ui.look.rememberReduceMotion
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Sliding pill selector for the two or three views of a page (Songs, Artists, Albums). A
 * [Immersive.Raised] track with the selected option inverted on the accent.
 */
@Composable
internal fun ImmersiveSegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val reduceMotion = rememberReduceMotion()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Immersive.Raised)
            // Enough of a margin for the track to read as a track: at 2 dp the selected pill sat
            // flush against the container's own edge and the two shapes merged into one.
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEachIndexed { index, label ->
            val chosen = index == selectedIndex
            val pill by animateColorAsState(
                targetValue = if (chosen) Immersive.Accent else Color.Transparent,
                animationSpec = if (reduceMotion) snap() else tween(ImmersiveMotion.FADE_MS),
                label = "segment pill",
            )
            val labelColor by animateColorAsState(
                targetValue = if (chosen) Color.White else Immersive.TextSecondary,
                animationSpec = if (reduceMotion) snap() else tween(ImmersiveMotion.FADE_MS),
                label = "segment label",
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(pill)
                    .clickable(role = Role.Tab) {
                        if (!chosen) {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onSelect(index)
                        }
                    }
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    style = Immersive.Subhead.copy(color = labelColor, fontWeight = FontWeight.Medium),
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * Swipe a row from right to left to remove it: the row follows the finger over a red strip with
 * a bin, ticks past the threshold, and slides out before [onRemove]. The Classic pages' gesture
 * on the Immersive page colour.
 */
@Composable
internal fun ImmersiveSwipeToRemove(key: Any, onRemove: () -> Unit, content: @Composable () -> Unit) {
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val reduceMotion = rememberReduceMotion()
    val threshold = with(LocalDensity.current) { 120.dp.toPx() }
    val offset = remember(key) { Animatable(0f) }
    var armed by remember(key) { mutableStateOf(false) }
    var width by remember { mutableFloatStateOf(0f) }
    // The offset moves every drag and spring frame; only the strip appearing is a composition change.
    val revealed by remember(offset) { derivedStateOf { offset.value < 0f } }

    Box(modifier = Modifier.fillMaxWidth().onSizeChanged { width = it.width.toFloat() }) {
        if (revealed) {
            Box(
                contentAlignment = Alignment.CenterEnd,
                modifier = Modifier
                    .matchParentSize()
                    .background(if (armed) Immersive.Destructive else Immersive.Destructive.copy(alpha = 0.6f)),
            ) {
                Icon(
                    Icons.Rounded.DeleteOutline,
                    contentDescription = stringResource(R.string.context_menu_undownload),
                    tint = Color.White,
                    modifier = Modifier.padding(horizontal = 24.dp).size(26.dp),
                )
            }
        }
        Box(
            modifier = Modifier
                .offset { IntOffset(offset.value.roundToInt(), 0) }
                .background(Immersive.Page)
                .pointerInput(key) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            if (armed) scope.launch {
                                offset.animateTo(-width.coerceAtLeast(threshold), if (reduceMotion) snap() else tween(180))
                                onRemove()
                            } else scope.launch { offset.animateTo(0f, if (reduceMotion) snap() else spring()) }
                            armed = false
                        },
                        onDragCancel = {
                            armed = false
                            scope.launch { offset.animateTo(0f, if (reduceMotion) snap() else spring()) }
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

/** A name prompt (new playlist, rename a folder or a playlist) in the Immersive look. */
@Composable
internal fun ImmersiveNameDialog(
    title: String,
    initial: String,
    confirm: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    ImmersiveAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = Immersive.Headline) },
        text = {
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(Color.White.copy(alpha = 0.08f), Immersive.Button)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            ) {
                if (text.isEmpty()) {
                    Text(
                        stringResource(R.string.immersive_pages_name_hint),
                        style = Immersive.Body.copy(color = Immersive.TextSecondary),
                    )
                }
                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    textStyle = Immersive.Body,
                    cursorBrush = SolidColor(Immersive.Accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (text.isNotBlank()) onConfirm(text.trim()) }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = text.isNotBlank(), onClick = { onConfirm(text.trim()) }) {
                Text(confirm, style = Immersive.BodyStrong.copy(color = Immersive.Accent))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.add_to_playlist_button_cancel), style = Immersive.BodyStrong)
            }
        },
    )
}

/** A bottom sheet on glass, the frame of every menu of these pages. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ImmersiveSheet(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    ImmersiveGlassSheet(onDismiss) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 12.dp), content = content)
    }
}

/** One entry of a sheet: a glyph, a label and, under it, an optional [detail] line. */
@Composable
internal fun ImmersiveSheetRow(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    detail: String? = null,
    destructive: Boolean = false,
) {
    val tint = if (destructive) Immersive.Destructive else Immersive.Text
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .immersiveClickable(onClick = { Haptics.click(); onClick() }, pressedScale = 0.98f)
            .padding(horizontal = Immersive.Gutter, vertical = 8.dp),
    ) {
        Icon(icon, contentDescription = null, tint = if (destructive) tint else Immersive.TextSecondary, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(16.dp))
        Column {
            Text(label, style = Immersive.Body.copy(color = tint), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (detail != null) Text(detail, style = Immersive.Caption)
        }
    }
}

/**
 * The frame of every page here: the black page with iOS-style rubber-band overscroll on all the
 * lists inside it.
 */
@Composable
internal fun ImmersivePageFrame(content: @Composable BoxScope.() -> Unit) {
    CompositionLocalProvider(LocalOverscrollFactory provides rememberImmersiveOverscrollFactory()) {
        Box(Modifier.fillMaxSize().background(Immersive.Page), content = content)
    }
}
