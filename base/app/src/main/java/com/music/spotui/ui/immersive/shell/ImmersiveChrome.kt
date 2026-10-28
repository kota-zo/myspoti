/*
 * The minimizing chrome follows BitChord (https://github.com/kushagrasinghx/BitChord),
 * GPL-3.0-or-later, app/src/main/java/com/music/bitchord/ui/components/floatingtabbar/FloatingTabBar.kt
 * (FloatingTabBarScrollConnection, the inline bar) and GlassNavBar.kt at edc1e6f.
 * Written anew for Myspoti, 2026-10: a scroll connection pages attach to, and one layout that
 * morphs the tab bar into a pill with the mini player beside it.
 */
package com.music.spotui.ui.immersive.shell

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.navigation.compose.currentBackStackEntryAsState
import app.myspoti.engine.R
import com.music.spotui.ui.immersive.Immersive
import com.music.spotui.ui.immersive.motion.ImmersiveMotion
import com.music.spotui.ui.look.rememberReduceMotion
import com.music.spotui.ui.navigation.Routes
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sign

/** How far a page has to scroll one way before the chrome folds or unfolds. */
private val FOLD_DISTANCE = 32.dp

/**
 * The floating chrome's fold, as iOS 26 does it: scrolling a page down minimizes the tab bar to a
 * pill (the mini player moving in beside it) and shrinks the top bar's controls; scrolling back up,
 * pulling past the top, tapping the pill or opening another page brings everything back.
 *
 * One instance for the app, provided by the host through [LocalImmersiveChrome]. Pages do not
 * talk to it: they attach its [scrollConnection] with [immersiveChromeScroll].
 */
@Stable
class ImmersiveChromeState internal constructor(private val foldDistancePx: Float) {
    /** True while the chrome is folded. */
    var minimized by mutableStateOf(false)
        private set

    private var travel = 0f

    /** Unfold now (the pill was tapped, another page opened). */
    fun expand() {
        travel = 0f
        minimized = false
    }

    /** Fold now. */
    fun minimize() {
        travel = 0f
        minimized = true
    }

    /**
     * Reads what the page actually scrolled (never consumes anything): [FOLD_DISTANCE] of it
     * downwards folds the chrome, as much upwards unfolds it, and a pull down at the very top
     * (nothing left to scroll) unfolds it at once. A page too short to scroll never folds it.
     */
    val scrollConnection: NestedScrollConnection = object : NestedScrollConnection {
        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            val dy = consumed.y
            if (dy != 0f) {
                if (travel != 0f && sign(travel) != sign(dy)) travel = 0f
                travel += dy
                if (travel <= -foldDistancePx) minimize()
                else if (travel >= foldDistancePx) expand()
            } else if (available.y > 0f && minimized) {
                expand()
            }
            return Offset.Zero
        }
    }
}

/**
 * The app's [ImmersiveChromeState], or null outside the Immersive look (where
 * [immersiveChromeScroll] is then a no-op).
 */
val LocalImmersiveChrome = staticCompositionLocalOf<ImmersiveChromeState?> { null }

/** The app's chrome state, kept for the lifetime of the calling composition. Provide it with [LocalImmersiveChrome]. */
@Composable
fun rememberImmersiveChromeState(): ImmersiveChromeState {
    val px = with(LocalDensity.current) { FOLD_DISTANCE.toPx() }
    return remember { ImmersiveChromeState(px) }
}

/**
 * Lets this page's scroll fold the floating chrome (tab bar to a pill with the mini player beside
 * it, the top bar's controls shrunk) the way iOS 26 does. Put it on the page's main scrolling
 * list, next to [immersiveHazeSource]:
 *
 * ```
 * LazyColumn(
 *     state = listState,
 *     modifier = Modifier.fillMaxSize().immersiveHazeSource().immersiveChromeScroll(),
 * ) { ... }
 * ```
 *
 * Works the same on a `Column(Modifier.verticalScroll(...))`, a `LazyVerticalGrid` or anything
 * scrollable inside it (nested horizontal shelves are ignored: only vertical scroll counts). It
 * consumes nothing, so the page's own scroll, overscroll and top bar collapse are unchanged.
 * Classic look (no [LocalImmersiveChrome]): does nothing. Attach it once per page.
 */
@Composable
fun Modifier.immersiveChromeScroll(state: ImmersiveChromeState? = LocalImmersiveChrome.current): Modifier =
    if (state == null) this else this.nestedScroll(state.scrollConnection)

/** The minimized tab bar's pill (and the mini player's height beside it). */
private val PILL_SIZE = 56.dp

/** The gap between the mini player and the tab bar, and between the pill and the mini player. */
private val CHROME_GAP = 8.dp

/** The mini player's margin to the screen edges; the tab bar sits [TAB_INSET] further in. */
private val MINI_MARGIN = 14.dp
/** Large windows: the mini player beside the tab bar, never wider than this. */
private val LargeMiniPlayerWidth = 560.dp
private val TAB_INSET = Immersive.Gutter - MINI_MARGIN

/**
 * The Immersive look's bottom chrome: the mini player over the floating tab bar, which folds into
 * a single glass pill (the current tab's glyph) with the mini player inline beside it while
 * [LocalImmersiveChrome] is minimized. Both hide on the player and queue routes.
 *
 * @param miniPlayer the mini player, given the modifier to lay out with; it may draw nothing.
 */
@Composable
fun ImmersiveBottomChrome(
    navController: NavController,
    miniPlayer: @Composable (Modifier) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tabs = rememberTabNavigation(navController)
    if (tabs.fullScreen) return
    // Large windows (tablets, foldables, DeX): the tab bar and the mini player side by side in one
    // floating row, centred, each at its own capped width; there is room, so it never folds.
    if (com.music.spotui.ui.components.windowSize().large) {
        Box(
            modifier.fillMaxWidth().windowInsetsPadding(ImmersiveBarInsets)
                .padding(horizontal = MINI_MARGIN).padding(bottom = 2.dp),
            contentAlignment = Alignment.BottomCenter,
        ) {
            androidx.compose.foundation.layout.Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(CHROME_GAP),
            ) {
                Box(Modifier.widthIn(max = ImmersiveBarMaxWidth)) {
                    TabBarContent(selectedTab = tabs.selectedTab, onTabClick = tabs.onTabClick, modifier = Modifier)
                }
                Box(Modifier.weight(1f, fill = false).widthIn(max = LargeMiniPlayerWidth)) { miniPlayer(Modifier) }
            }
        }
        return
    }
    val chrome = LocalImmersiveChrome.current
    val reduceMotion = rememberReduceMotion()
    val minimized = chrome?.minimized == true

    // A new page starts at its top, with everything showing.
    val route = navController.currentBackStackEntryAsState().value?.destination?.route
    LaunchedEffect(route) { chrome?.expand() }

    val progress = remember { Animatable(if (minimized) 1f else 0f) }
    LaunchedEffect(minimized) {
        progress.animateTo(if (minimized) 1f else 0f, ImmersiveMotion.chromeSpring(reduceMotion))
    }
    val showTabs by remember { derivedStateOf { progress.value < 0.999f } }
    val showPill by remember { derivedStateOf { progress.value > 0.001f } }

    val density = LocalDensity.current
    val pillPx = with(density) { PILL_SIZE.roundToPx() }
    val gapPx = with(density) { CHROME_GAP.roundToPx() }
    val insetPx = with(density) { TAB_INSET.roundToPx() }

    Box(modifier.fillMaxWidth().windowInsetsPadding(ImmersiveBarInsets), contentAlignment = Alignment.BottomCenter) {
        Layout(
            contents = listOf<@Composable () -> Unit>(
                {
                    if (showTabs) TabBarContent(selectedTab = tabs.selectedTab, onTabClick = tabs.onTabClick, modifier = Modifier)
                },
                {
                    if (showPill) MinimizedTabPill(tabs.selectedTab, onClick = { chrome?.expand() })
                },
                { miniPlayer(Modifier) },
            ),
            modifier = Modifier
                .widthIn(max = ImmersiveBarMaxWidth + TAB_INSET * 2)
                .fillMaxWidth()
                .padding(horizontal = MINI_MARGIN)
                .padding(bottom = 2.dp),
        ) { (tabSlot, pillSlot, miniSlot), constraints ->
            val p = progress.value
            val width = constraints.maxWidth
            val tab = tabSlot.firstOrNull()?.measure(Constraints(maxWidth = (width - insetPx * 2).coerceAtLeast(0)))
            val pill = pillSlot.firstOrNull()?.measure(Constraints.fixed(pillPx, pillPx))
            val inlineX = insetPx + pillPx + gapPx
            val miniWidth = lerp(width.toFloat(), (width - inlineX - insetPx).toFloat(), p).roundToInt().coerceIn(0, width)
            val mini = miniSlot.firstOrNull()?.measure(Constraints(minWidth = miniWidth, maxWidth = miniWidth))

            // The tab bar's height whether or not it is still composed, so nothing jumps when it goes.
            val tabHeight = tab?.height ?: pillPx
            val miniHeight = mini?.height ?: 0
            val height = max(max(tabHeight, pillPx), if (miniHeight > 0) tabHeight + gapPx + miniHeight else 0)
            layout(width, height) {
                // At rest the bar is placed plainly, like the mini player: a glass inside a placement
                // layer (even an identity one) is not sampled correctly. Only while it folds does it
                // get the layer for its alpha and scale.
                if (tab != null && p == 0f) {
                    tab.place(insetPx, height - tab.height)
                } else {
                    tab?.placeWithLayer(insetPx, height - tab.height) {
                    val q = progress.value.coerceIn(0f, 1f)
                    alpha = 1f - q
                    // Shrinks into the pill's corner.
                    transformOrigin = TransformOrigin(0f, 1f)
                    scaleX = lerp(1f, pillPx / tab.width.coerceAtLeast(1).toFloat(), q)
                    scaleY = lerp(1f, pillPx / tab.height.coerceAtLeast(1).toFloat(), q)
                }
                }
                pill?.placeWithLayer(insetPx, height - pill.height) {
                    val q = progress.value.coerceIn(0f, 1f)
                    alpha = q
                    scaleX = lerp(0.7f, 1f, q)
                    scaleY = scaleX
                }
                mini?.place(
                    x = lerp(0f, inlineX.toFloat(), p).roundToInt(),
                    y = lerp((height - tabHeight - gapPx - miniHeight).toFloat(), (height - miniHeight).toFloat(), p).roundToInt(),
                )
            }
        }
    }
}

/** The folded tab bar: the current tab's glyph on a glass disc; a tap unfolds it. */
@Composable
private fun MinimizedTabPill(selectedTab: String, onClick: () -> Unit) {
    val item = TabItems.firstOrNull { it.route == selectedTab } ?: Routes.Home
    val source = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .size(PILL_SIZE)
            .immersiveGlassSurface(CircleShape, interactionSource = source)
            .clickable(
                interactionSource = source,
                indication = null,
                role = Role.Button,
                onClickLabel = stringResource(R.string.immersive_expand_tab_bar),
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(item.icon),
            contentDescription = stringResource(R.string.immersive_expand_tab_bar),
            tint = Immersive.Accent,
            modifier = Modifier.size(25.dp),
        )
    }
}

private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t
