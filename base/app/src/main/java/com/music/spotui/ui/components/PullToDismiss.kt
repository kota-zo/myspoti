package com.music.spotui.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitVerticalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.verticalDrag
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * A full-screen sheet pulled down from the top of its list closes, like the official
 * player: the sheet follows the finger (with some resistance) and closes past
 * [thresholdPx] or on a quick flick down; otherwise it springs back. Attach
 * [connection] to the list's `nestedScroll` (or, on a screen with no list, [pullToDismiss] to the
 * screen) and move the sheet by [offset].
 */
class PullToDismissState internal constructor(
    private val scope: CoroutineScope,
    private val thresholdPx: Float,
    private val flingPx: Float,
    private val dismiss: () -> Unit,
) {
    var enabled = true
    private val pull = Animatable(0f)
    private var raw = 0f
    private var dismissed = false

    /** How far the sheet is pulled down, in pixels. */
    val offset: Float get() = pull.value

    private fun follow() {
        scope.launch { pull.snapTo(raw * RESISTANCE) }
    }

    /**
     * The finger moved [delta] pixels (down is positive): down pulls the sheet, back up undoes the
     * pull first. Returns what was taken of it.
     */
    fun drag(delta: Float): Float {
        val used = when {
            delta < 0f -> maxOf(delta, -raw)
            enabled && !dismissed -> delta
            else -> 0f
        }
        if (used == 0f) return 0f
        raw += used
        follow()
        return used
    }

    /**
     * Back at rest, pullable again: the sheet opened once more. A pull that closed it left it
     * [dismissed] and drawn [offset] lower, and the sheet reopened before its close was over (the
     * mini player tapped during the slide) kept both: the whole player sat some 70 dp low, its
     * controls near the bottom, and could not be pulled again.
     */
    suspend fun reset() {
        raw = 0f
        dismissed = false
        pull.snapTo(0f)
    }

    /** The finger lifted at [velocity] pixels a second (down is positive): closes, or springs back. */
    fun release(velocity: Float) {
        scope.launch { settle(velocity) }
    }

    /** False when the sheet was not pulled (nothing to settle). */
    private suspend fun settle(velocity: Float): Boolean {
        if (raw <= 0f) return false
        if (raw > thresholdPx || velocity > flingPx) {
            dismissed = true
            dismiss()
        } else {
            raw = 0f
            pull.animateTo(0f)
        }
        return true
    }

    val connection = object : NestedScrollConnection {
        // Pushing back up first undoes the pull, before the list scrolls.
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (raw <= 0f || available.y >= 0f || source != NestedScrollSource.UserInput) return Offset.Zero
            return Offset(0f, drag(available.y))
        }

        // What the list could not scroll (it is at its top) pulls the sheet down.
        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            if (available.y <= 0f || source != NestedScrollSource.UserInput) return Offset.Zero
            return Offset(0f, drag(available.y))
        }

        override suspend fun onPreFling(available: Velocity): Velocity =
            if (settle(available.y)) available else Velocity.Zero
    }

    private companion object {
        const val RESISTANCE = 0.6f
    }
}

/**
 * A drag down anywhere on a screen with no list to scroll (the podcast player) pulls the sheet,
 * as a list's overscroll does through [PullToDismissState.connection]. Upward is not ours: nothing
 * is consumed until the finger has gone down past the touch slop, so taps, the progress bar and a
 * child's own gesture keep working. Put it ahead of the graphicsLayer that moves the sheet by
 * [PullToDismissState.offset]: the finger is then measured on the screen, not on the moving sheet.
 */
fun Modifier.pullToDismiss(state: PullToDismissState): Modifier = pointerInput(state) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        var pulled = 0f
        val drag = awaitVerticalTouchSlopOrCancellation(down.id) { change, overSlop ->
            if (overSlop > 0f) {
                pulled = overSlop
                change.consume()
            }
        }
        if (drag == null || pulled <= 0f) return@awaitEachGesture
        val velocity = VelocityTracker()
        velocity.addPointerInputChange(drag)
        state.drag(pulled)
        val finished = verticalDrag(drag.id) { change ->
            velocity.addPointerInputChange(change)
            state.drag(change.positionChange().y)
            change.consume()
        }
        state.release(if (finished) velocity.calculateVelocity().y else 0f)
    }
}

/** [open]: the sheet is (again) open, which puts the pull back at rest ([PullToDismissState.reset]). */
@Composable
fun rememberPullToDismiss(open: Boolean = true, onDismiss: () -> Unit): PullToDismissState {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val latest = rememberUpdatedState(onDismiss)
    val state = remember(density) {
        with(density) { PullToDismissState(scope, 120.dp.toPx(), 1200.dp.toPx()) { latest.value() } }
    }
    LaunchedEffect(state, open) { if (open) state.reset() }
    return state
}
