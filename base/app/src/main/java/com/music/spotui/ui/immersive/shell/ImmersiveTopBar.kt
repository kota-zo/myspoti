/*
 * Adapted from BitChord (https://github.com/kushagrasinghx/BitChord), GPL-3.0-or-later,
 * app/src/main/java/com/music/bitchord/ui/components/FrostedTopBar.kt at edc1e6f
 * (the large page heading is from sharedUi/.../ui/screens/HomeScreen.kt). Modified for Myspoti, 2026-10.
 */
package com.music.spotui.ui.immersive.shell

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.RectangleShape
import com.music.spotui.ui.immersive.motion.ImmersiveMotion
import com.music.spotui.ui.look.rememberReduceMotion
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import com.music.spotui.ui.components.wideClickable
import androidx.compose.ui.layout.layout
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.myspoti.engine.R
import com.music.spotui.ui.immersive.Immersive
import dev.chrisbanes.haze.HazeState

/**
 * The bar's own height, above whatever inset it is sitting under. The single source of truth for
 * it: the bar lays itself out to this, and everything that has to clear the bar measures from
 * [immersiveTopBarHeight] rather than from a copy of the number.
 */
val ImmersiveTopBarContentHeight = 52.dp

/** The breathing room between the bar's bottom edge and the first thing under it. */
val ImmersiveTopBarContentGap = 12.dp

/** How much of each end of the bar is spoken for, so a long title truncates instead of running under it. */
private val TitleInset = 64.dp

/** The back and action targets: a 44 dp circle, as the floating controls of the official bars. */
private val BarControlSize = 44.dp

/** The same controls while the chrome is minimized ([LocalImmersiveChrome]): small glass pills. */
private val BarControlSizeMinimized = 38.dp

/**
 * The size of a top bar control right now: 44 dp, shrinking to small pills on a spring while the
 * chrome is minimized. Read by [ImmersiveBarAction]; a custom action in the bar can read it too.
 */
val LocalBarControlSize = compositionLocalOf { BarControlSize }

@Composable
private fun rememberBarControlSize(): Dp {
    val minimized = LocalImmersiveChrome.current?.minimized == true
    val size by animateDpAsState(
        targetValue = if (minimized) BarControlSizeMinimized else BarControlSize,
        animationSpec = ImmersiveMotion.chromeSpring(rememberReduceMotion()),
        label = "immersiveBarControlSize",
    )
    return size
}

/**
 * How far down the window the bar actually ends: the status bar inset it is pinned under, plus
 * its own height. Read at composition because the inset is a property of the device, not the app.
 */
@Composable
fun immersiveTopBarHeight(): Dp =
    WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + ImmersiveTopBarContentHeight

/**
 * Where page content should start: clear of the bar, plus [ImmersiveTopBarContentGap]. Use it as
 * the top `contentPadding` of the page's list so its large title rests just under the bar.
 */
@Composable
fun immersiveTopContentPadding(): Dp = immersiveTopBarHeight() + ImmersiveTopBarContentGap

/**
 * The top bar of an Immersive page: a back chevron and actions on glass circles, and a small
 * centred [title] over a frosted strip — both fade in as [collapse] goes from 0 to 1. At 0 the bar
 * is transparent and the page's own [ImmersiveLargeTitle] is the heading; once the list has
 * scrolled the large title under the bar, the small one and the frost take over.
 *
 * Prefer the overloads taking the page's [LazyListState] or [ScrollState], which work [collapse]
 * out from the scroll offset. Draw it after (above) the scrolling content in a Box, and give the
 * content `immersiveTopContentPadding()` at the top:
 *
 * ```
 * val list = rememberLazyListState()
 * Box(Modifier.fillMaxSize()) {
 *     LazyColumn(state = list, contentPadding = PaddingValues(top = immersiveTopContentPadding())) {
 *         immersiveLargeTitle("Library")
 *         ...
 *     }
 *     ImmersiveTopBar("Library", list, onBack = { nav.popBackStack() }) {
 *         ImmersiveBarAction(Icons.Rounded.Add, "Add", onClick = ::add)
 *     }
 * }
 * ```
 *
 * @param collapse 0 = large title showing (bar transparent), 1 = collapsed (small title + frost).
 * Read inside draw layers only, so a scroll does not recompose anything.
 * @param onBack shows the back chevron when set.
 * @param hazeState the page to blur, [LocalImmersiveHaze] by default; null falls back to a solid fill.
 * @param actions trailing controls, laid out in one glass pill; see [ImmersiveBarAction].
 */
@Composable
fun ImmersiveTopBar(
    title: String,
    collapse: () -> Float,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    hazeState: HazeState? = LocalImmersiveHaze.current,
    actions: (@Composable RowScope.() -> Unit)? = null,
) {
    val page = LocalImmersiveBackdrop.current
    val controlSize = rememberBarControlSize()
    // The actions pill grows with each action, so the title keeps clear of whatever width it has.
    var actionsWidthPx by remember { mutableIntStateOf(0) }
    val titleInset = maxOf(TitleInset, with(LocalDensity.current) { actionsWidthPx.toDp() } + Immersive.Gutter + 8.dp)
    Box(modifier = modifier.fillMaxWidth().holdsTouches()) {
        // The glass panel hanging from the top (rounded bottom corners), fading in with the collapse.
        ImmersiveTopGlass(collapse, Modifier.matchParentSize(), hazeState)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .height(ImmersiveTopBarContentHeight),
        ) {
            Text(
                text = title,
                style = Immersive.Headline.copy(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.Center)
                    // The same room on both sides: the title is centred within this padded box,
                    // so an inset that differs end to end would move it off the bar's centre.
                    .padding(horizontal = titleInset)
                    .fillMaxWidth()
                    .graphicsLayer { alpha = collapse() },
            )
            if (onBack != null) {
                val backSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = Immersive.Gutter)
                        // Taps around the disc count too, out to the screen edge and the bar's height.
                        .wideClickable(horizontal = Immersive.Gutter, vertical = 12.dp, interactionSource = backSource, onClick = onBack)
                        .size(controlSize)
                        .immersiveGlassSurface(CircleShape, hazeState, interactionSource = backSource),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.AutoMirrored.Rounded.KeyboardArrowLeft,
                        contentDescription = stringResource(R.string.immersive_shell_back),
                        tint = Immersive.Text,
                        modifier = Modifier.size(controlSize * 0.64f),
                    )
                }
            }
            if (actions != null) {
                Row(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = Immersive.Gutter)
                        .immersiveGlassSurface(CircleShape, hazeState)
                        // Keep the right edge fixed while a new action opens room to its left.
                        .animateContentSize(
                            animationSpec = ImmersiveMotion.sizeSpring(),
                        )
                        .onSizeChanged { actionsWidthPx = it.width },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CompositionLocalProvider(LocalBarControlSize provides controlSize) { actions() }
                }
            }
        }
    }
}

/** [ImmersiveTopBar] collapsing as a [LazyListState] scrolls: [collapseDistance] of scroll takes it from 0 to 1. */
@Composable
fun ImmersiveTopBar(
    title: String,
    listState: LazyListState,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    hazeState: HazeState? = LocalImmersiveHaze.current,
    collapseDistance: Dp = 40.dp,
    actions: (@Composable RowScope.() -> Unit)? = null,
) = ImmersiveTopBar(
    title = title,
    collapse = rememberTopBarCollapse(listState, collapseDistance),
    modifier = modifier,
    onBack = onBack,
    hazeState = hazeState,
    actions = actions,
)

/** [ImmersiveTopBar] collapsing as a [ScrollState] scrolls: [collapseDistance] of scroll takes it from 0 to 1. */
@Composable
fun ImmersiveTopBar(
    title: String,
    scrollState: ScrollState,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    hazeState: HazeState? = LocalImmersiveHaze.current,
    collapseDistance: Dp = 40.dp,
    actions: (@Composable RowScope.() -> Unit)? = null,
) = ImmersiveTopBar(
    title = title,
    collapse = rememberTopBarCollapse(scrollState, collapseDistance),
    modifier = modifier,
    onBack = onBack,
    hazeState = hazeState,
    actions = actions,
)

/** The collapse fraction (0..1) of a [LazyListState] page: 1 once past the first item. */
@Composable
fun rememberTopBarCollapse(state: LazyListState, distance: Dp = 40.dp): () -> Float {
    val distancePx = with(LocalDensity.current) { distance.toPx() }
    return remember(state, distancePx) {
        val fraction: () -> Float = {
            if (state.firstVisibleItemIndex > 0) 1f
            else (state.firstVisibleItemScrollOffset / distancePx).coerceIn(0f, 1f)
        }
        fraction
    }
}

/** The collapse fraction (0..1) of a [ScrollState] page. */
@Composable
fun rememberTopBarCollapse(state: ScrollState, distance: Dp = 40.dp): () -> Float {
    val distancePx = with(LocalDensity.current) { distance.toPx() }
    return remember(state, distancePx) {
        val fraction: () -> Float = { (state.value / distancePx).coerceIn(0f, 1f) }
        fraction
    }
}

/**
 * One 44 dp icon button for [ImmersiveTopBar]'s `actions` (several share one glass pill).
 *
 * @param tint the glyph colour; [Immersive.Accent] for an active toggle.
 */
@Composable
fun ImmersiveBarAction(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = Immersive.Text,
) {
    val size = LocalBarControlSize.current
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(size * 0.5f))
    }
}

/**
 * A page's large heading (the first thing in its list), over the bar's small title that takes
 * over when it has scrolled away. [trailing] sits at the end of the line (a count, a sort button).
 *
 * In a `LazyColumn` use [immersiveLargeTitle]; in a `Column(verticalScroll(...))` put this first.
 */
@Composable
fun ImmersiveLargeTitle(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Immersive.Gutter, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = Immersive.LargeTitle,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = Immersive.Subhead,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailing != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End, content = trailing)
        }
    }
}

/** [ImmersiveLargeTitle] as the next item of a lazy list. */
fun LazyListScope.immersiveLargeTitle(
    title: String,
    subtitle: String? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    item(key = "immersive:large-title", contentType = "immersive:large-title") {
        ImmersiveLargeTitle(title = title, subtitle = subtitle, trailing = trailing)
    }
}

/** The row of [ImmersiveFloatingHeader] under the status bar. */
val ImmersiveHeaderContentHeight = 56.dp

/** The rounded bottom corners of the top glass panel (the Immersive sheet radius). */
private val HeaderCorner = 28.dp

/** How far the glass panel extends above the screen, so only its bottom curve and rim are seen. */
private val HeaderOverscan = 32.dp

private val HeaderShape = RoundedCornerShape(bottomStart = HeaderCorner, bottomEnd = HeaderCorner)

/**
 * The glass panel of the top bar and the floating header: Liquid Glass (lens, live blur of the page
 * scrolling under it, rim, tint) or the frost on Android < 13 / with the switch off, in a shape
 * with rounded bottom corners and a square top. The panel is laid out [HeaderOverscan] taller than
 * its slot and shifted above the screen, so its top edge, rim and top corners are never visible
 * and the lens bends along the bottom curve only. It is a sibling of the page (never inside the
 * recorded node), so it cannot sample itself. [visible] (0..1, read in the draw phase) fades it in
 * as content scrolls under; with "reduce motion" it snaps instead.
 */
@Composable
internal fun ImmersiveTopGlass(visible: () -> Float, modifier: Modifier, hazeState: HazeState?) {
    val page = LocalImmersiveBackdrop.current
    val reduce = rememberReduceMotion()
    val overscan = with(LocalDensity.current) { HeaderOverscan.roundToPx() }
    val shape = remember(overscan) { HeaderShape }
    val glass = when {
        page != null && hazeState != null && hazeState === LocalImmersiveHaze.current ->
            Modifier.immersiveLiquidGlass(shape, page)
        hazeState != null -> Modifier.clip(shape).immersiveFrost(hazeState, tint = Immersive.Page.copy(alpha = 0.55f))
        else -> Modifier.background(Immersive.Page.copy(alpha = 0.94f), shape)
    }
    Box(
        modifier
            .layout { measurable, c ->
                val p = measurable.measure(androidx.compose.ui.unit.Constraints.fixed(c.maxWidth, c.maxHeight + overscan))
                layout(c.maxWidth, c.maxHeight) { p.place(0, -overscan) }
            }
            .graphicsLayer {
                val v = visible()
                alpha = if (reduce) (if (v > 0.02f) 1f else 0f) else v
            }
            .then(glass),
    )
}

/**
 * Where a page under [ImmersiveFloatingHeader] should start: just under the glass, with the gap
 * the content needs to read as below it rather than cut by it.
 */
@Composable
fun immersiveHeaderContentPadding(): Dp =
    WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + ImmersiveHeaderContentHeight + 10.dp

/**
 * A tab's heading on floating glass, as Home in Apple Music: the title at the start and the
 * [trailing] controls at the end of one row, on a glass panel pinned to the top of the screen
 * (behind the status bar) with rounded bottom corners. The page scrolls under it, so give the
 * list `immersiveHeaderContentPadding()` at the top and no large title of its own:
 *
 * ```
 * Box(Modifier.fillMaxSize()) {
 *     LazyColumn(contentPadding = PaddingValues(top = immersiveHeaderContentPadding()), ...) { ... }
 *     ImmersiveFloatingHeader("Home") { ProfileButton() }
 * }
 * ```
 *
 * The glass is [immersiveGlassSurface] (Liquid Glass when it is on, frost otherwise). Controls in
 * [trailing] shrink with the chrome; read [LocalBarControlSize] for their size.
 */
@Composable
fun ImmersiveFloatingHeader(
    title: String,
    modifier: Modifier = Modifier,
    hazeState: HazeState? = LocalImmersiveHaze.current,
    collapse: () -> Float = { 1f },
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val controlSize = rememberBarControlSize()
    Box(modifier.fillMaxWidth().holdsTouches()) {
        ImmersiveTopGlass(collapse, Modifier.matchParentSize(), hazeState)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .height(ImmersiveHeaderContentHeight)
                .padding(start = Immersive.Gutter, end = Immersive.Gutter - 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = Immersive.Title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (trailing != null) {
                CompositionLocalProvider(LocalBarControlSize provides controlSize) { trailing() }
            }
        }
    }
}

/**
 * Keeps a tap on the bar's glass from reaching the page under it: being hit at all is what keeps
 * the page from being hit. Nothing is consumed: a button in the bar drops its tap when it sees the
 * finger's moves consumed, so consuming here made the bar's buttons miss most taps.
 */
private fun Modifier.holdsTouches(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) awaitPointerEvent()
    }
}
