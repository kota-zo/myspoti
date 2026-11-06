package com.music.spotui.ui.components

import com.music.spotui.ui.theme.AppText

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * A thin draggable thumb on the right edge of a long [LazyListState] list: shown while the
 * list scrolls or the thumb is dragged, hidden otherwise. Place it in a Box over the list.
 */
@Composable
fun FastScrollbar(state: LazyListState, modifier: Modifier = Modifier, minItems: Int = 30) {
    // The layout info changes on every scroll step: only the count, which rarely changes, is
    // read in composition.
    val total by remember { derivedStateOf { state.layoutInfo.totalItemsCount } }
    if (total <= minItems) return
    var dragging by remember { mutableStateOf(false) }
    var visible by remember { mutableStateOf(false) }
    val scrolling = state.isScrollInProgress
    LaunchedEffect(scrolling, dragging) {
        if (scrolling || dragging) visible = true else { delay(1200); visible = false }
    }
    val alpha by androidx.compose.animation.core.animateFloatAsState(if (visible) 1f else 0f, label = "scrollbar")
    if (alpha == 0f) return
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val thumbPx = with(density) { 56.dp.toPx() }
    Box(modifier.fillMaxHeight().width(28.dp).alpha(alpha)) {
        Box(
            Modifier
                .align(androidx.compose.ui.Alignment.TopEnd)
                .offset {
                    // Read in layout: the thumb follows the scroll without recomposing the bar.
                    val travel = (state.layoutInfo.viewportSize.height - thumbPx).coerceAtLeast(0f)
                    val first = state.firstVisibleItemIndex.toFloat()
                    val fraction = if (total <= 1) 0f else (first / (total - 1)).coerceIn(0f, 1f)
                    IntOffset(0, (fraction * travel).roundToInt())
                }
                .padding(end = 3.dp)
                .size(width = 5.dp, height = 56.dp)
                .background(AppText.copy(alpha = if (dragging) 0.8f else 0.5f), RoundedCornerShape(50))
        )
        // A wider invisible strip so the thin thumb is easy to grab.
        Box(
            Modifier
                .fillMaxHeight()
                .width(28.dp)
                .pointerInput(total) {
                    detectVerticalDragGestures(
                        onDragStart = { dragging = true },
                        onDragEnd = { dragging = false },
                        onDragCancel = { dragging = false },
                    ) { change, _ ->
                        change.consume()
                        val h = (size.height - thumbPx).coerceAtLeast(1f)
                        val f = ((change.position.y - thumbPx / 2) / h).coerceIn(0f, 1f)
                        scope.launch { state.scrollToItem((f * (total - 1)).roundToInt()) }
                    }
                }
        )
    }
}
