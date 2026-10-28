package com.music.spotui.ui.navigation

import com.music.spotui.ui.theme.AppText
import com.music.spotui.ui.theme.AppMuted
import com.music.spotui.ui.theme.AppShade

import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.compose.ui.res.stringResource
import app.myspoti.engine.R

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.Interaction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.music.spotui.ui.components.MiniPlayer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.statusBarsPadding

/**
 * What re-tapping the tab you are already on does in the official app: Home and Library
 * scroll their list back to the top without reloading anything, Search focuses its field.
 * The bar only sees the tap; the list state and the text field live inside the screens,
 * so the tap is published here and each screen collects its own event.
 */
object TabReselect {
    // replay = 0 + a one-slot buffer: a tap is never held back, and a tap that lands while
    // the screen is not composed is simply dropped rather than replayed on the next visit.
    private val _home = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(
        extraBufferCapacity = 1, onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    private val _library = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(
        extraBufferCapacity = 1, onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    private val _search = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(
        extraBufferCapacity = 1, onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )

    val home: kotlinx.coroutines.flow.SharedFlow<Unit> = _home
    val library: kotlinx.coroutines.flow.SharedFlow<Unit> = _library
    val search: kotlinx.coroutines.flow.SharedFlow<Unit> = _search

    fun emit(route: String) {
        when (route) {
            Routes.Home.route -> _home.tryEmit(Unit)
            Routes.Library.route -> _library.tryEmit(Unit)
            Routes.Search.route -> _search.tryEmit(Unit)
        }
    }
}

class NoRippleInteractionSource : MutableInteractionSource {

    override val interactions: Flow<Interaction> = emptyFlow()

    override suspend fun emit(interaction: Interaction) {}

    override fun tryEmit(interaction: Interaction) = true
}

@Composable
fun MainBottomNavigation(navController: NavHostController, bottomBarState: MutableState<Boolean>, bottomBarPlayerState : MutableState<Boolean>) {

    // Visibility follows the screen actually shown (not flags each screen sets,
    // which raced during transitions and left the mini player over the full one).
    val shownRoute = navController.currentBackStackEntryAsState().value?.destination?.route
    val fullScreen = shownRoute == Routes.Player.route || shownRoute == Routes.Queue.route
    val hasTrack = com.music.spotui.di.Graph.currentSongState.title.value.isNotBlank()
    // No motion of its own: the bar sits above the pages, so sliding it while the
    // player rises (or collapses behind it) made one gesture look like two.
    AnimatedVisibility(
        visible = !fullScreen,
        enter = androidx.compose.animation.EnterTransition.None,
        exit = androidx.compose.animation.ExitTransition.None,
        content = {
            Box(
                contentAlignment = Alignment.BottomCenter,
                modifier = Modifier
                    .fillMaxWidth()
                    // Settings, Display: the fade over the list (the default), or solid black (from #15).
                    .then(
                        if (com.music.spotui.data.preferences.SolidNavBar.on) Modifier.background(AppShade)
                        // Like the official bar: clear above the mini player, near-opaque by the tab icons,
                        // so covers scrolling underneath never compete with the labels.
                        else Modifier.background(Brush.verticalGradient(
                            0f to Color.Transparent,
                            0.3f to AppShade.copy(alpha = 0.7f),
                            0.6f to AppShade.copy(alpha = 0.92f),
                            1f to AppShade,
                        ))
                    )
            ) {

                Column(modifier = Modifier.navigationBarsPadding()) {

                    AnimatedVisibility(
                        visible = hasTrack,
                        enter = slideInVertically(initialOffsetY = { it }),
                        exit = slideOutVertically(targetOffsetY = { it }),
                        content = {
                            // Large windows: a capped, centred capsule, not a bar across a desktop window.
                            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                Box(if (com.music.spotui.ui.components.windowSize().large) Modifier.widthIn(max = com.music.spotui.ui.components.WindowSize.MaxMiniPlayerWidth) else Modifier) {
                                    MiniPlayer(navController)
                                }
                            }
                        }
                    )



                    Spacer(modifier = Modifier.height(4.dp))
                    val tabs = rememberTabNavigation(navController)
                    // Large windows: the tabs are in the rail at the side (MainNavigationRail).
                    if (!com.music.spotui.ui.components.windowSize().large) {
                    // Official bar proportions: a 58 dp strip (the gesture inset is added by
                    // navigationBarsPadding above), three equal thirds edge to edge, so the
                    // tabs spread over the full width instead of clustering in the middle.
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(NavBarHeight),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TabItems.forEach { item ->
                            NavBarTab(
                                iconRes = item.icon,
                                label = if (item.labelRes != 0) stringResource(item.labelRes) else item.label,
                                selected = tabs.selected == item.route,
                                onClick = { tabs.open(item) },
                            )
                        }
                    }
                    }



                }



            }
        }
    )
}

private val TabItems = listOf(Routes.Home, Routes.Search, Routes.Library)

/** The tab shown (also on its sub-pages) and what a tap on a tab does; shared by the bar and the rail. */
private class TabNavigation(private val navController: NavHostController, private val state: androidx.compose.runtime.MutableState<String>, private val currentRoute: String?) {
    val selected: String get() = state.value

    fun open(item: Routes) {
        // Standard bottom-tab navigation: each tab keeps its
        // state (no reload, scroll kept) when you come back.
        if (item.route == state.value) {
            if (currentRoute == item.route) {
                // Already on the tab's own page: nothing to reload, only
                // the re-tap shortcut (scroll to top, or focus search).
                TabReselect.emit(item.route)
            } else if (!navController.popBackStack(item.route, inclusive = false)) {
                // On one of its sub-pages: back to its root page first.
                navController.navigate(item.route) { launchSingleTop = true }
            }
        } else {
            state.value = item.route
            navController.navigate(item.route) {
                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                launchSingleTop = true
                // Home always opens Home itself (its scroll kept), not the
                // page last left open from it (a playlist, an artist…).
                restoreState = item.route != Routes.Home.route
            }
        }
    }
}

@Composable
private fun rememberTabNavigation(navController: NavHostController): TabNavigation {
    val navStack by navController.currentBackStackEntryAsState()
    val currentRoute = navStack?.destination?.route
    // The tab the user is in, even on one of its sub-pages (album, artist…).
    val selectedTab = androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(Routes.Home.route) }
    if (currentRoute in TabItems.map { it.route }) selectedTab.value = currentRoute!!
    return TabNavigation(navController, selectedTab, currentRoute)
}

/**
 * Large windows (tablets, foldables, DeX): the three tabs in a rail down the left side instead
 * of a phone bar stretched across the window. Hidden with the bar on the full-screen routes.
 */
@Composable
fun MainNavigationRail(navController: NavHostController) {
    val shownRoute = navController.currentBackStackEntryAsState().value?.destination?.route
    if (shownRoute == Routes.Player.route || shownRoute == Routes.Queue.route) return
    val tabs = rememberTabNavigation(navController)
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .width(88.dp)
            .background(AppShade)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(top = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TabItems.forEach { item ->
            Row(Modifier.fillMaxWidth().height(72.dp)) {
                NavBarTab(
                    iconRes = item.icon,
                    label = if (item.labelRes != 0) stringResource(item.labelRes) else item.label,
                    selected = tabs.selected == item.route,
                    onClick = { tabs.open(item) },
                )
            }
        }
    }
}

// Official app metrics. Material3's NavigationBarItem measures its icon slot at a
// fixed 24 dp, so a 28 dp Icon there was clipped back down: the bar is drawn by hand.
private val NavBarHeight = 58.dp
private val NavIconSize = 28.dp

/**
 * One tab of the bottom bar: a 28 dp glyph above an 11 sp medium label, the two
 * centred as a single block inside an equal third of the bar's width.
 */
@Composable
private fun RowScope.NavBarTab(
    iconRes: Int,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    // Spotify's idle grey, lighter than Color.Gray which read as "disabled".
    val tint = if (selected) AppText else AppMuted
    Box(
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight()
            // No ripple: the bar is a plain strip of tabs, and the tint is the feedback.
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClickLabel = label,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                painter = painterResource(id = iconRes),
                // The label right below already names the tab for TalkBack.
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(NavIconSize),
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = label,
                color = tint,
                fontSize = 11.sp,
                lineHeight = 12.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.2.sp,
                maxLines = 1,
            )
        }
    }
}
