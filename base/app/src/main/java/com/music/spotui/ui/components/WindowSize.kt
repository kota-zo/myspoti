package com.music.spotui.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Material's window width classes: under 600 dp, 600 to 839 dp, 840 dp and wider. */
enum class WidthClass { COMPACT, MEDIUM, EXPANDED }

/** Material's window height classes: under 480 dp, 480 to 899 dp, 900 dp and taller. */
enum class HeightClass { COMPACT, MEDIUM, EXPANDED }

/**
 * The app window's size (tablets, foldables, split screen, Samsung DeX windows of any shape).
 * Read from the configuration, which the activity keeps live across resizes (no restart, see
 * configChanges in the manifest), so every layout below follows a window being dragged.
 */
@Immutable
data class WindowSize(val widthDp: Int, val heightDp: Int) {
    val width: WidthClass = when {
        widthDp < 600 -> WidthClass.COMPACT
        widthDp < 840 -> WidthClass.MEDIUM
        else -> WidthClass.EXPANDED
    }
    val height: HeightClass = when {
        heightDp < 480 -> HeightClass.COMPACT
        heightDp < 900 -> HeightClass.MEDIUM
        else -> HeightClass.EXPANDED
    }

    /**
     * A large window: tablets, unfolded foldables, desktop windows. Phones are never large, in
     * either orientation (a phone on its side is wide but compact in height), so they keep
     * their own layouts exactly.
     */
    val large: Boolean get() = width != WidthClass.COMPACT && height != HeightClass.COMPACT

    /** Large and 840 dp or wider: room for columns side by side. */
    val expanded: Boolean get() = large && width == WidthClass.EXPANDED

    /** Large and wider than tall, or a large portrait tablet: the players lay out in two panes. */
    val twoPane: Boolean get() = large && (widthDp > heightDp || width == WidthClass.EXPANDED)

    /** Grid columns for a phone layout that had [phone] columns, spread to cells of about [cell]. */
    fun columns(phone: Int, cell: Dp = 180.dp): Int =
        if (!large) phone else (widthDp / cell.value).toInt().coerceIn(phone, phone * 4)

    companion object {
        /** The widest a content column (rows, track lists, settings) grows on a large window. */
        val MaxContentWidth = 960.dp
        /** The widest a detail page (album, playlist, artist, settings) grows, centred. */
        val MaxPageWidth = 1040.dp
        /** The widest a sheet grows on a large window, centred. */
        val MaxSheetWidth = 640.dp
        /** The widest the mini player grows on a large window. */
        val MaxMiniPlayerWidth = 720.dp
    }
}

private val LocalWindowSize = staticCompositionLocalOf<WindowSize?> { null }

/** The window's size classes; computed once by [ProvideWindowSize], or here when outside it. */
@Composable
fun windowSize(): WindowSize = LocalWindowSize.current ?: configurationWindowSize()

@Composable
private fun configurationWindowSize(): WindowSize {
    val c = LocalConfiguration.current
    return remember(c.screenWidthDp, c.screenHeightDp) { WindowSize(c.screenWidthDp, c.screenHeightDp) }
}

/** Computes the window's size classes once for everything inside (the activity's content). */
@Composable
fun ProvideWindowSize(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalWindowSize provides configurationWindowSize(), content = content)
}

/**
 * Large windows: caps the content at [max] and centres it, so rows are not stretched across a
 * desktop window. Phones: unchanged.
 */
fun Modifier.maxContentWidth(max: Dp = WindowSize.MaxContentWidth): Modifier = composed {
    if (!windowSize().large) this
    else this.fillMaxWidth().wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = max)
}

/**
 * A mouse's right button (Samsung DeX, a tablet with a mouse) does what a long press does:
 * opens the same menu. Consumes the press so the row's own click does not fire too.
 */
fun Modifier.secondaryClick(action: (() -> Unit)?): Modifier = if (action == null) this else composed {
    val latest by rememberUpdatedState(action)
    pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val e = awaitPointerEvent(PointerEventPass.Initial)
                if (e.type == PointerEventType.Press && e.buttons.isSecondaryPressed) {
                    e.changes.forEach { it.consume() }
                    latest()
                }
            }
        }
    }
}
