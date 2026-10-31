/*
 * Adapted from BitChord (https://github.com/kushagrasinghx/BitChord), GPL-3.0-or-later,
 * app/src/main/java/com/music/bitchord/ui/components/GlassNavBar.kt at edc1e6f
 * (and app/src/main/java/com/music/bitchord/ui/components/FloatingBottomBar.kt). Modified for Myspoti, 2026-10.
 */
package com.music.spotui.ui.immersive.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.currentBackStackEntryAsState
import com.music.spotui.ui.immersive.Immersive
import com.music.spotui.ui.look.ImmersiveSans
import com.music.spotui.ui.look.rememberReduceMotion
import com.music.spotui.ui.navigation.Routes
import com.music.spotui.ui.navigation.TabReselect
import com.music.spotui.ui.navigation.tabInStack

/**
 * The gap between the pill's glass edge and the tabs inside it. Shared with the mini player so
 * the two floating bars measure alike.
 */
internal val PILL_INSET = 6.dp

/**
 * How far the floating bars stand off the bottom edge: the system navigation bar's inset, but
 * never less than a gesture bar's. With the gesture hint switched off that inset is zero and the
 * bar would drop onto the very edge of the screen.
 */
internal val ImmersiveBarInsets: WindowInsets
    @Composable get() = WindowInsets.navigationBars.union(WindowInsets(bottom = GESTURE_BAR_FLOOR))

/** A gesture bar's inset. */
private val GESTURE_BAR_FLOOR = 15.dp

/**
 * Each tab's own vertical padding, the counterweight to [PILL_INSET]: the pill has no height of
 * its own, so the two numbers are a pair — change one and the bar's height moves unless the other
 * moves against it.
 */
private val TAB_VERTICAL_PADDING = 9.dp

/** The gap between a tab's glyph and its label. */
private val TAB_ICON_LABEL_GAP = 2.dp

/** How wide the floating bars are ever allowed to get: on a tablet they stop growing and centre. */
val ImmersiveBarMaxWidth = 440.dp

/**
 * The room [ImmersiveTabBar] takes at the bottom of the screen (its own height, the gap under it
 * and the navigation bar inset). Stack the mini player above this.
 */
@Composable
fun immersiveTabBarHeight(): androidx.compose.ui.unit.Dp =
    TabBarContentHeight + 2.dp + ImmersiveBarInsets.asPaddingValues().calculateBottomPadding()

/** The bar's own height: a 25 dp glyph, the gap, an 11 sp label, the tab padding and the pill inset. */
private val TabBarContentHeight = (PILL_INSET + TAB_VERTICAL_PADDING) * 2 + 25.dp + TAB_ICON_LABEL_GAP + 14.dp

private val TabLabel = TextStyle(
    fontFamily = ImmersiveSans,
    fontSize = 11.sp,
    lineHeight = 14.sp,
    fontWeight = FontWeight.Medium,
)

/**
 * The Immersive look's floating tab bar: a frosted pill with Home and Library, and Search in a
 * circle of its own beside it. The selected tab's pill lifts, travels to the next tab on a spring
 * and settles; a horizontal drag across the pill picks it up the same way.
 *
 * Navigation is exactly [com.music.spotui.ui.navigation.MainBottomNavigation]'s: re-tapping the
 * tab you are on emits [TabReselect] (scroll to top, focus search); from one of its sub-pages it
 * pops back to the tab's root; otherwise it navigates with `popUpTo(start){saveState}`,
 * `launchSingleTop` and `restoreState` (Home always reopens itself). The bar hides itself on the
 * player, queue and SpotiFLAC routes.
 *
 * Usage: put it at the bottom of the app's Box, above the NavHost, and the mini player above it
 * (see [immersiveTabBarHeight]). Provide [LocalImmersiveHaze] and mark the NavHost with
 * [immersiveHazeSource] for the glass to blur the page behind; without it the glass is a plain
 * translucent fill.
 *
 * ```
 * Box(Modifier.fillMaxSize()) {
 *     MyNavHost(nav, Modifier.immersiveHazeSource())
 *     ImmersiveTabBar(nav, Modifier.align(Alignment.BottomCenter))
 * }
 * ```
 */
@Composable
fun ImmersiveTabBar(navController: NavController, modifier: Modifier = Modifier) {
    val tabs = rememberTabNavigation(navController)
    // No motion of its own: the bar sits above the pages, so sliding it while the player rises
    // (or collapses behind it) made one gesture look like two.
    if (tabs.fullScreen) return
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
        TabBarContent(
            selectedTab = tabs.selectedTab,
            onTabClick = tabs.onTabClick,
            modifier = Modifier
                .windowInsetsPadding(ImmersiveBarInsets)
                .padding(horizontal = Immersive.Gutter)
                .padding(bottom = 2.dp)
                .widthIn(max = ImmersiveBarMaxWidth),
        )
    }
}

/** The tab the user is in, how to switch tabs, and whether the bar hides (player, queue). */
internal class TabNavigation(
    val selectedTab: String,
    val onTabClick: (Routes) -> Unit,
    val fullScreen: Boolean,
)

/** The tab bar's navigation, shared by [ImmersiveTabBar] and the minimizing [ImmersiveBottomChrome]. */
@Composable
internal fun rememberTabNavigation(navController: NavController): TabNavigation {
    // Visibility follows the screen actually shown, not flags each screen sets.
    val navStack by navController.currentBackStackEntryAsState()
    val currentRoute = navStack?.destination?.route
    val fullScreen = currentRoute == Routes.Player.route ||
        currentRoute == Routes.Queue.route || currentRoute in com.music.spotui.ui.navigation.CHAT_FULL_SCREEN_ROUTES

    // The tab the user is in, even on one of its sub-pages (album, artist...). Kept whether or
    // not the bar shows, so it survives the bar being hidden on the player.
    var selectedTab by rememberSaveable { mutableStateOf(Routes.Home.route) }
    navController.tabInStack(currentRoute, TabRoutes)?.let { selectedTab = it }

    val onTabClick: (Routes) -> Unit = { item ->
        // Standard bottom-tab navigation: each tab keeps its state (no reload, scroll kept).
        if (item.route == selectedTab) {
            if (currentRoute == item.route) {
                // Already on the tab's own page: nothing to reload, only the re-tap shortcut.
                TabReselect.emit(item.route)
            } else if (!navController.popBackStack(item.route, inclusive = false)) {
                // On one of its sub-pages: back to its root page first.
                navController.navigate(item.route) { launchSingleTop = true }
            }
        } else {
            selectedTab = item.route
            navController.navigate(item.route) {
                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                launchSingleTop = true
                // Home always opens Home itself (its scroll kept), not the page last left
                // open from it (a playlist, an artist...).
                restoreState = item.route != Routes.Home.route
            }
        }
    }
    return TabNavigation(selectedTab, onTabClick, fullScreen)
}

private val TabRoutes = listOf(Routes.Home.route, Routes.Search.route, Routes.Library.route, Routes.Friends.route)

/** The tabs the bar shows. */
internal val TabItems = listOf(Routes.Home, Routes.Library, Routes.Friends, Routes.Search)

/** The tab bar itself (pill and Search circle), filling the width [modifier] leaves it. */
@Composable
internal fun TabBarContent(
    selectedTab: String,
    onTabClick: (Routes) -> Unit,
    modifier: Modifier,
) {
    // Held for the same reason the content lambda below is keyed: it is captured once per key.
    val currentOnTabClick by androidx.compose.runtime.rememberUpdatedState(onTabClick)
    val pillShape = remember { RoundedCornerShape(percent = 50) }
    val reduceMotion = rememberReduceMotion()

    // A factory, not a value: each of the two surfaces gets its own glass modifier.
    val glassSurface: @Composable () -> Modifier = { Modifier.immersiveGlassSurface(pillShape) }

    Box(modifier = modifier, contentAlignment = Alignment.BottomCenter) {
        FloatingTabBar(
            selectedTabKey = selectedTab,
            modifier = Modifier.fillMaxWidth(),
            tabBarContentModifier = glassSurface,
            colors = FloatingTabBarDefaults.colors(
                backgroundColor = Color.Transparent,
                indicatorColor = Color.White.copy(alpha = 0.12f),
            ),
            sizes = FloatingTabBarDefaults.sizes(
                tabBarContentPadding = PaddingValues(PILL_INSET),
                tabExpandedContentPadding = PaddingValues(vertical = TAB_VERTICAL_PADDING),
            ),
            reduceMotion = reduceMotion,
            contentKey = selectedTab,
        ) {
            // Search is the odd one out: a circle of its own beside the pill.
            listOf(Routes.Home, Routes.Library, Routes.Friends).forEach { item ->
                val tint = if (selectedTab == item.route) Immersive.Accent else Immersive.Text.copy(alpha = 0.65f)
                tab(
                    key = item.route,
                    icon = { TabIcon(item, tint) },
                    title = {
                        Text(
                            text = tabLabel(item),
                            style = TabLabel,
                            color = tint,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            // Three tabs in the pill: a long label (Bibliothèque, with large text) shrinks a little
                            // before it is cut, and keeps clear of the tab's rounded ends, which clip its first and last letters.
                            autoSize = androidx.compose.foundation.text.TextAutoSize.StepBased(minFontSize = 8.sp, maxFontSize = 11.sp, stepSize = 0.5.sp),
                            modifier = Modifier.padding(top = TAB_ICON_LABEL_GAP).padding(horizontal = 6.dp),
                        )
                    },
                    onClick = { currentOnTabClick(item) },
                )
            }
            standaloneTab(
                key = Routes.Search.route,
                icon = {
                    TabIcon(
                        Routes.Search,
                        if (selectedTab == Routes.Search.route) Immersive.Accent else Immersive.Text.copy(alpha = 0.65f),
                    )
                },
                onClick = { currentOnTabClick(Routes.Search) },
            )
        }
    }
}

@Composable
private fun tabLabel(item: Routes): String =
    if (item.labelRes != 0) stringResource(item.labelRes) else item.label

@Composable
private fun TabIcon(item: Routes, tint: Color) {
    Icon(
        painter = painterResource(item.icon),
        // The label right below names the tab for TalkBack; the standalone one has none.
        contentDescription = if (item == Routes.Search) tabLabel(item) else null,
        tint = tint,
        modifier = Modifier.size(25.dp),
    )
}
