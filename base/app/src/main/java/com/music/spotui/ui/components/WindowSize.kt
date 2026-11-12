package com.music.spotui.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
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

    /**
     * A phone on its side: wide, but under 480 dp tall. Bars stacked at the bottom took a third of
     * its height there, so the tabs move to the side (Classic's rail) or share one row with the
     * mini player (Immersive), as on large windows.
     */
    val shortAndWide: Boolean get() = height == HeightClass.COMPACT && width != WidthClass.COMPACT

    /**
     * A phone held upright (a compact width, taller than wide): the players keep their one-column
     * layout, the controls under the cover, whatever their own box measures for a moment. The
     * side-by-side layouts were also chosen from the box's measured width and height, and a
     * passing measure (the sheet's first frame, coming back from picture-in-picture or another
     * app, a window being resized) put a phone's controls elsewhere until the next one (reported:
     * the controls sometimes at the bottom instead of their place).
     */
    val portraitPhone: Boolean get() = width == WidthClass.COMPACT && heightDp >= widthDp

    /**
     * The players' cover beside the controls, for a box of [boxWidth] x [boxHeight]: never on a
     * [portraitPhone]; otherwise on [twoPane] windows and boxes wider than tall (wider than
     * [minWidth] too, when given).
     */
    fun playerSideBySide(boxWidth: Dp, boxHeight: Dp, minWidth: Dp = 0.dp): Boolean =
        !portraitPhone && (twoPane || (boxWidth > minWidth && boxWidth > boxHeight))

    /** Classic's tabs in the rail at the side instead of the bottom bar: large windows and [shortAndWide]. */
    val navigationRail: Boolean get() = large || shortAndWide

    /**
     * Immersive's tab bar and mini player side by side in one row: only where the row is wide
     * enough for both (840 dp and wider) or the window too short for them stacked. Below that
     * (a foldable, a portrait tablet) the mini player beside the 440 dp tab bar was squeezed to
     * 200-320 dp, so they stack as on phones.
     */
    val chromeInOneRow: Boolean get() = expanded || shortAndWide

    /**
     * Grid columns for a phone layout that had [phone] columns, spread to cells of about [cell].
     * Follows the width alone, not [large]: a phone on its side is short but wide, and its grids
     * kept the portrait count of huge cells (covers of 280 dp whose titles fell under the mini
     * player). A portrait phone is narrower than [phone] cells, so it keeps its own count.
     */
    fun columns(phone: Int, cell: Dp = 180.dp): Int = columnsIn(widthDp.dp, phone, cell)

    companion object {
        /** The widest a detail page (album, playlist, artist, settings) grows, centred. */
        val MaxPageWidth = 1040.dp
        /** The widest the mini player grows on a large window. */
        val MaxMiniPlayerWidth = 720.dp

        /** [columns] for a box of [width] (a list capped at [MaxPageWidth] has fewer than the window). */
        fun columnsIn(width: Dp, phone: Int, cell: Dp = 180.dp): Int =
            (width.value / cell.value).toInt().coerceIn(phone, phone * 4)
    }
}

/**
 * Caps a page's list at [WindowSize.MaxPageWidth], centred, on wider windows: rows (a library, a
 * search) drawn across 1800 dp are hard to read. Narrower windows keep their whole width.
 */
fun Modifier.cappedPageWidth(): Modifier = this
    .fillMaxWidth()
    .wrapContentWidth(Alignment.CenterHorizontally)
    .widthIn(max = WindowSize.MaxPageWidth)
    .fillMaxWidth()

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
