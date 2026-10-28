package com.music.spotui.ui.navigation

import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.NamedNavArgument
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import com.music.spotui.ui.theme.AppBackground
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.music.spotui.ui.screens.AlbumByIdScreen
import com.music.spotui.ui.screens.ContextScreen
import com.music.spotui.ui.screens.ArtistReleasesScreen
import com.music.spotui.ui.screens.ArtistScreen
import com.music.spotui.ui.screens.CategoryScreen
import com.music.spotui.ui.screens.HomeScreen
import com.music.spotui.ui.screens.LibraryScreen
import com.music.spotui.ui.screens.PlayerScreen
import com.music.spotui.ui.screens.ShowScreen
import com.music.spotui.ui.screens.QueueScreen
import com.music.spotui.ui.screens.SearchScreen
import com.music.spotui.ui.viewmodel.PlayerViewModel
import com.music.spotui.ui.immersive.motion.ImmersiveMotion.Pages as IPages
import com.music.spotui.ui.immersive.motion.ImmersiveMotion.PageFade as IFade

@Composable
fun MyNavHost(
    navHostController: NavHostController,
    bottomBarState: MutableState<Boolean>,
    bottomBarPlayerState: MutableState<Boolean>,
    modifier: Modifier = Modifier,
) {

    val playerViewModel : PlayerViewModel = viewModel()
    val playerState by playerViewModel.currentSongTitle

//    val context = LocalContext.current
//    var player : ExoPlayer? = null
//    player = ExoPlayer.Builder(context).build()

    val context = LocalContext.current
    val startDestination = Routes.Home.route
    // The look (Settings > Display) is read through State objects: the graph builder and the
    // transitions keep the same lambdas, so a switch never rebuilds the graph (or the back stack).
    val immersiveState = androidx.compose.runtime.rememberUpdatedState(isImmersive())
    val reduceState = androidx.compose.runtime.rememberUpdatedState(com.music.spotui.ui.look.rememberReduceMotion())

    com.music.spotui.ui.components.ArtistChooserHost(navHostController)
    NavHost(
        navController = navHostController,
        startDestination = startDestination,
        modifier = modifier,
        // Pages: a short shift with a fade-through, tabs fade in, the player and queue rise
        // from the bottom (see navEnter). The fades put a page in an offscreen layer for
        // their 0.1-0.2 s: the owner's call, a page change read as a hard block without them.
        // The Immersive look pushes pages like a navigation stack (ImmersiveMotion.Pages).
        enterTransition = { if (immersiveState.value) IPages.enter(reduceState.value)(this) else if (reduceState.value) EnterTransition.None else navEnter(initialState, targetState) },
        exitTransition = { if (immersiveState.value) IPages.exit(reduceState.value)(this) else if (reduceState.value) ExitTransition.None else navExit(initialState, targetState) },
        popEnterTransition = { if (immersiveState.value) IPages.popEnter(reduceState.value)(this) else if (reduceState.value) EnterTransition.None else navPopEnter(initialState, targetState) },
        popExitTransition = { if (immersiveState.value) IPages.popExit(reduceState.value)(this) else if (reduceState.value) ExitTransition.None else navPopExit(initialState, targetState) },
    ){
        page(Routes.Home.route){
            LaunchedEffect(playerState) {
                bottomBarState.value = true
                bottomBarPlayerState.value = playerState != ""
            }
                if (isImmersive()) com.music.spotui.ui.immersive.screens.ImmersiveHomeScreen(navHostController) else HomeScreen(navHostController)
        }
        page(Routes.Search.route){
            LaunchedEffect(playerState) {
                bottomBarState.value = true
                bottomBarPlayerState.value = playerState != ""
            }
            if (isImmersive()) com.music.spotui.ui.immersive.screens.ImmersiveSearchScreen(navHostController) else SearchScreen(navHostController)
        }
        page(Routes.Library.route) {
            LaunchedEffect(playerState) {
                bottomBarState.value = true
                bottomBarPlayerState.value = playerState != ""
            }
            if (isImmersive()) com.music.spotui.ui.immersive.screens.ImmersiveLibraryScreen(navHostController) else LibraryScreen(navHostController)
        }
        // Older links to the player route open the player sheet over the page instead.
        page(Routes.Player.route){
            LaunchedEffect(Unit) {
                navHostController.popBackStack(Routes.Player.route, inclusive = true)
                PlayerSheet.show()
            }
        }

        page(Routes.Queue.route){
            LaunchedEffect(playerState) {
                bottomBarState.value = false
                bottomBarPlayerState.value = false
            }
            if (isImmersive()) com.music.spotui.ui.immersive.pages.ImmersiveQueueScreen(navHostController) else QueueScreen(navHostController)
        }

        page(Routes.LocalFiles.route){
            LaunchedEffect(playerState) {
                bottomBarState.value = true
                bottomBarPlayerState.value = playerState != ""
            }
            if (isImmersive()) com.music.spotui.ui.immersive.pages.ImmersiveLocalFilesScreen(navHostController) else com.music.spotui.ui.screens.LocalFilesScreen(navHostController)
        }

        page(Routes.Downloads.route){
            LaunchedEffect(playerState) {
                bottomBarState.value = true
                bottomBarPlayerState.value = playerState != ""
            }
            if (isImmersive()) com.music.spotui.ui.immersive.pages.ImmersiveDownloadsScreen(navHostController) else com.music.spotui.ui.screens.DownloadsScreen(navHostController)
        }

        page(Routes.NewReleases.route) {
            LaunchedEffect(playerState) {
                bottomBarState.value = true
                bottomBarPlayerState.value = playerState != ""
            }
            if (isImmersive()) com.music.spotui.ui.immersive.pages.ImmersiveNewReleasesScreen(navHostController) else com.music.spotui.ui.screens.NewReleasesScreen(navHostController)
        }

        page(Routes.Episodes.route) {
            LaunchedEffect(playerState) {
                bottomBarState.value = true
                bottomBarPlayerState.value = playerState != ""
            }
            if (isImmersive()) com.music.spotui.ui.immersive.pages.ImmersiveEpisodesScreen(navHostController) else com.music.spotui.ui.screens.EpisodesScreen(navHostController)
        }

        page(Routes.Recap.route) {
            LaunchedEffect(playerState) {
                bottomBarState.value = true
                bottomBarPlayerState.value = playerState != ""
            }
            if (isImmersive()) com.music.spotui.ui.immersive.pages.ImmersiveRecapScreen(navHostController) else com.music.spotui.ui.screens.RecapScreen(navHostController)
        }

        page(Routes.Friends.route) {
            LaunchedEffect(playerState) {
                bottomBarState.value = true
                bottomBarPlayerState.value = playerState != ""
            }
            if (isImmersive()) com.music.spotui.ui.immersive.pages.ImmersiveFriendsScreen(navHostController) else com.music.spotui.ui.screens.FriendActivityScreen(navHostController)
        }

        // Spotify's own list for the account (every device), not this phone's counter.
        page(Routes.Recent.route) {
            LaunchedEffect(playerState) {
                bottomBarState.value = true
                bottomBarPlayerState.value = playerState != ""
            }
            if (isImmersive()) com.music.spotui.ui.immersive.pages.ImmersiveRecentlyPlayedScreen(navHostController) else com.music.spotui.ui.screens.RecentlyPlayedScreen(navHostController)
        }

        page(Routes.Liked.route){
            LaunchedEffect(playerState) {
                bottomBarState.value = true
                bottomBarPlayerState.value = playerState != ""
            }
            if (isImmersive()) com.music.spotui.ui.immersive.screens.ImmersiveContextScreen(navHostController, type = "liked", id = "") else ContextScreen(navHostController, type = "liked", id = "")
        }

        // Settings: sound quality, downloads, data, display, language.
        page(Routes.Settings.route){
            LaunchedEffect(playerState) {
                bottomBarState.value = true
                bottomBarPlayerState.value = playerState != ""
            }
            if (isImmersive()) com.music.spotui.ui.immersive.pages.ImmersiveSettingsScreen(navHostController) else com.music.spotui.ui.screens.SettingsScreen(navHostController)
        }

        // Extensions: the Store and Installed tabs; one installed extension's page; a repository's extension before install.
        page(Routes.Extensions.route){
            LaunchedEffect(playerState) {
                bottomBarState.value = true
                bottomBarPlayerState.value = playerState != ""
            }
            if (isImmersive()) com.music.spotui.ui.immersive.pages.ImmersiveExtensionsScreen(navHostController) else com.music.spotui.ui.screens.ExtensionsScreen(navHostController)
        }
        page("${Routes.Extension.route}/{id}", arguments = listOf(navArgument("id") { defaultValue = "" })){ entry ->
            LaunchedEffect(playerState) {
                bottomBarState.value = true
                bottomBarPlayerState.value = playerState != ""
            }
            if (isImmersive()) com.music.spotui.ui.immersive.pages.ImmersiveExtensionScreen(navHostController, entry.arguments?.getString("id").orEmpty()) else com.music.spotui.ui.screens.ExtensionScreen(navHostController, entry.arguments?.getString("id").orEmpty())
        }
        page("${Routes.ExtensionEntry.route}/{id}", arguments = listOf(navArgument("id") { defaultValue = "" })){ entry ->
            LaunchedEffect(playerState) {
                bottomBarState.value = true
                bottomBarPlayerState.value = playerState != ""
            }
            if (isImmersive()) com.music.spotui.ui.immersive.pages.ImmersiveExtensionEntryScreen(navHostController, entry.arguments?.getString("id").orEmpty()) else com.music.spotui.ui.screens.ExtensionEntryScreen(navHostController, entry.arguments?.getString("id").orEmpty())
        }
        // An extension's own page (webview.page), sandboxed.
        page("extension-page/{id}", arguments = listOf(navArgument("id") { defaultValue = "" })){ entry ->
            LaunchedEffect(playerState) {
                bottomBarState.value = true
                bottomBarPlayerState.value = playerState != ""
            }
            com.music.spotui.ui.screens.ExtensionPageScreen(navHostController, entry.arguments?.getString("id").orEmpty())
        }






        page(
            "${Routes.Category.route}/{genre}?title={title}",
            arguments = listOf(navArgument("title") { defaultValue = "" }),
        ) { navBackStackEntry ->
            LaunchedEffect(playerState) {
                bottomBarState.value = true
                bottomBarPlayerState.value = playerState != ""
            }
            val genre = navBackStackEntry.arguments?.getString("genre").orEmpty()
            val title = navBackStackEntry.arguments?.getString("title").orEmpty()
            if (isImmersive()) com.music.spotui.ui.immersive.screens.ImmersiveCategoryScreen(navHostController, genre = genre, title = title.ifBlank { genre }) else CategoryScreen(navHostController, genre = genre, title = title.ifBlank { genre })
        }

        page(
            "${Routes.Album.route}/{uString}?artist={artist}&id={albumId}",
            arguments = listOf(
                navArgument("artist") { defaultValue = "" },
                navArgument("albumId") { defaultValue = "" },
            ),
        ) { navBackStackEntry ->
            LaunchedEffect(playerState) {
                bottomBarState.value = true
                bottomBarPlayerState.value = playerState != ""
            }

            /* Extracting the id from the route */
            val uId = navBackStackEntry.arguments?.getString("uString")
            val artist = navBackStackEntry.arguments?.getString("artist").orEmpty()
            /* We check if it's not null */
            val albumId = navBackStackEntry.arguments?.getString("albumId").orEmpty()
            uId?.let { name ->
                if (isImmersive()) com.music.spotui.ui.immersive.screens.ImmersiveAlbumByIdScreen(navHostController, name = name, artist = artist, id = albumId) else AlbumByIdScreen(navHostController, name = name, artist = artist, id = albumId)
            }
        }

        page(
            "${Routes.Playlist.route}/{pId}?name={name}",
            arguments = listOf(navArgument("name") { defaultValue = "" }),
        ) { navBackStackEntry ->
            LaunchedEffect(playerState) {
                bottomBarState.value = true
                bottomBarPlayerState.value = playerState != ""
            }
            val pId = navBackStackEntry.arguments?.getString("pId")
            val name = navBackStackEntry.arguments?.getString("name").orEmpty()
            pId?.let { if (isImmersive()) com.music.spotui.ui.immersive.screens.ImmersiveContextScreen(navHostController, type = "playlist", id = it) else ContextScreen(navHostController, type = "playlist", id = it) }
        }

        page(
            "${Routes.Profile.route}/{uId}?name={name}",
            arguments = listOf(navArgument("name") { defaultValue = "" }),
        ) { navBackStackEntry ->
            LaunchedEffect(playerState) {
                bottomBarState.value = true
                bottomBarPlayerState.value = playerState != ""
            }
            val uId = navBackStackEntry.arguments?.getString("uId")
            val name = navBackStackEntry.arguments?.getString("name").orEmpty()
            uId?.let { if (isImmersive()) com.music.spotui.ui.immersive.people.ImmersiveProfileScreen(navHostController, userId = it, userName = name) else com.music.spotui.ui.screens.ProfileScreen(navHostController, userId = it, userName = name) }
        }

        page("$LIKED_BY_ARTIST_ROUTE/{key}") { navBackStackEntry ->
            LaunchedEffect(playerState) {
                bottomBarState.value = true
                bottomBarPlayerState.value = playerState != ""
            }
            navBackStackEntry.arguments?.getString("key")?.let { if (isImmersive()) com.music.spotui.ui.immersive.screens.ImmersiveContextScreen(navHostController, type = "liked-artist", id = it) else ContextScreen(navHostController, type = "liked-artist", id = it) }
        }

        page(
            "${com.music.spotui.ui.screens.FOLLOW_LIST_ROUTE}/{uId}/{mode}",
        ) { navBackStackEntry ->
            LaunchedEffect(playerState) {
                bottomBarState.value = true
                bottomBarPlayerState.value = playerState != ""
            }
            val uId = navBackStackEntry.arguments?.getString("uId")
            val mode = navBackStackEntry.arguments?.getString("mode")
            uId?.let { if (isImmersive()) com.music.spotui.ui.immersive.people.ImmersiveFollowListScreen(navHostController, userId = it, followers = mode == "followers") else com.music.spotui.ui.screens.FollowListScreen(navHostController, userId = it, followers = mode == "followers") }
        }

        page(
            "${Routes.Show.route}/{sId}?name={name}",
            arguments = listOf(navArgument("name") { defaultValue = "" }),
        ) { navBackStackEntry ->
            LaunchedEffect(playerState) {
                bottomBarState.value = true
                bottomBarPlayerState.value = playerState != ""
            }
            val sId = navBackStackEntry.arguments?.getString("sId")
            val name = navBackStackEntry.arguments?.getString("name").orEmpty()
            sId?.let { if (isImmersive()) com.music.spotui.ui.immersive.podcast.ImmersiveShowScreen(navHostController, showId = it, showName = name) else ShowScreen(navHostController, showId = it, showName = name) }
        }

        page(
            "${Routes.Episode.route}/{eId}?name={name}",
            arguments = listOf(navArgument("name") { defaultValue = "" }),
        ) { navBackStackEntry ->
            LaunchedEffect(playerState) {
                bottomBarState.value = true
                bottomBarPlayerState.value = playerState != ""
            }
            val eId = navBackStackEntry.arguments?.getString("eId")
            val name = navBackStackEntry.arguments?.getString("name").orEmpty()
            eId?.let { if (isImmersive()) com.music.spotui.ui.immersive.pages.ImmersiveEpisodeScreen(navHostController, episodeId = it, fallbackName = name) else com.music.spotui.ui.screens.EpisodeScreen(navHostController, episodeId = it, fallbackName = name) }
        }

        page(
            "${Routes.Folder.route}/{uri}?name={name}",
            arguments = listOf(navArgument("name") { defaultValue = "" }),
        ) { navBackStackEntry ->
            LaunchedEffect(playerState) {
                bottomBarState.value = true
                bottomBarPlayerState.value = playerState != ""
            }
            val uri = navBackStackEntry.arguments?.getString("uri")
            val name = navBackStackEntry.arguments?.getString("name").orEmpty()
            uri?.let { if (isImmersive()) com.music.spotui.ui.immersive.pages.ImmersiveFolderScreen(navHostController, it, name) else com.music.spotui.ui.screens.FolderScreen(navHostController, it, name) }
        }

        page("${Routes.LocalPlaylist.route}/{id}") { navBackStackEntry ->
            LaunchedEffect(playerState) {
                bottomBarState.value = true
                bottomBarPlayerState.value = playerState != ""
            }
            navBackStackEntry.arguments?.getString("id")?.let { if (isImmersive()) com.music.spotui.ui.immersive.pages.ImmersiveLocalPlaylistScreen(navHostController, it) else com.music.spotui.ui.screens.LocalPlaylistScreen(navHostController, it) }
        }

        page(
            "${Routes.ArtistReleases.route}/{aString}?id={artistId}",
            arguments = listOf(navArgument("artistId") { defaultValue = "" }),
        ) { navBackStackEntry ->
            LaunchedEffect(playerState) {
                bottomBarState.value = true
                bottomBarPlayerState.value = playerState != ""
            }
            val aId = navBackStackEntry.arguments?.getString("aString")
            val artistId = navBackStackEntry.arguments?.getString("artistId").orEmpty()
            aId?.let { if (isImmersive()) com.music.spotui.ui.immersive.screens.ImmersiveArtistReleasesScreen(navHostController, it, artistId) else ArtistReleasesScreen(navHostController, it, artistId) }
        }

        page(
            "${Routes.Artist.route}/{aString}?id={artistId}",
            arguments = listOf(navArgument("artistId") { defaultValue = "" }),
        ) { navBackStackEntry ->
            LaunchedEffect(playerState) {
                bottomBarState.value = true
                bottomBarPlayerState.value = playerState != ""
            }

            /* Extracting the id from the route */
            val aId = navBackStackEntry.arguments?.getString("aString")
            val artistId = navBackStackEntry.arguments?.getString("artistId").orEmpty()
            /* We check if it's not null */
            aId?.let { aid->
                if (isImmersive()) com.music.spotui.ui.immersive.screens.ImmersiveArtistScreen(navHostController, aid, artistId) else ArtistScreen(navHostController, aid, artistId)
            }
        }
    }
}

private val tabRoutes = setOf(Routes.Home.route, Routes.Search.route, Routes.Library.route)
// Full-height sheets over the current page (the official player / queue).
private val sheetRoutes = setOf(Routes.Player.route, Routes.Queue.route)

private fun isTabSwitch(from: NavBackStackEntry, to: NavBackStackEntry) =
    from.destination.route in tabRoutes && to.destination.route in tabRoutes
private fun NavBackStackEntry.isSheet() = destination.route in sheetRoutes

// Material 3 emphasized curves: quick start, long soft landing (and the reverse to leave).
internal val Decelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
internal val Accelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
private const val PAGE_MS = 300
private const val TAB_MS = 150
internal const val SHEET_IN_MS = 340
internal const val SHEET_OUT_MS = 240

// Pages: Material's shared axis with a fade-through. The page leaving fades out over the first
// 90 ms while it moves a step aside; the page coming in moves the same short step and fades in
// over the rest. Two opaque pages sliding across the whole screen (as before) read as a heavy
// block with a hard edge running over the content; a short shift and a fade at the hand-over
// read as the official apps' quick page change. Tabs: the new one fades in, nothing moves.
private const val SHIFT = 0.08f
private const val FADE_OUT_MS = 90
private val Standard = CubicBezierEasing(0.2f, 0f, 0f, 1f)

private fun pageIn(fromRight: Boolean): EnterTransition =
    slideInHorizontally(tween(PAGE_MS, easing = Standard)) { (it * SHIFT).toInt() * if (fromRight) 1 else -1 } +
        fadeIn(tween(PAGE_MS - FADE_OUT_MS, delayMillis = FADE_OUT_MS, easing = Decelerate))

private fun pageOut(toLeft: Boolean): ExitTransition =
    slideOutHorizontally(tween(PAGE_MS, easing = Standard)) { (it * SHIFT).toInt() * if (toLeft) -1 else 1 } +
        fadeOut(tween(FADE_OUT_MS, easing = Accelerate))

private fun navEnter(from: NavBackStackEntry, to: NavBackStackEntry): EnterTransition = when {
    isTabSwitch(from, to) -> fadeIn(tween(TAB_MS, easing = Decelerate))
    to.isSheet() -> slideInVertically(tween(SHEET_IN_MS, easing = Decelerate)) { it }
    else -> pageIn(fromRight = true)
}

private fun navExit(from: NavBackStackEntry, to: NavBackStackEntry): ExitTransition = when {
    isTabSwitch(from, to) -> ExitTransition.None
    // The page stays still under the rising sheet until it covers it.
    to.isSheet() -> fadeOut(tween(durationMillis = 1, delayMillis = SHEET_IN_MS))
    else -> pageOut(toLeft = true)
}

private fun navPopEnter(from: NavBackStackEntry, to: NavBackStackEntry): EnterTransition = when {
    isTabSwitch(from, to) -> fadeIn(tween(TAB_MS, easing = Decelerate))
    from.isSheet() -> EnterTransition.None
    else -> pageIn(fromRight = false)
}

private fun navPopExit(from: NavBackStackEntry, to: NavBackStackEntry): ExitTransition = when {
    isTabSwitch(from, to) -> ExitTransition.None
    from.isSheet() -> slideOutVertically(tween(SHEET_OUT_MS, easing = Accelerate)) { it }
    else -> pageOut(toLeft = false)
}

/** A destination on an opaque background, so sliding pages never show through each other. */
private fun NavGraphBuilder.page(
    route: String,
    arguments: List<NamedNavArgument> = emptyList(),
    content: @Composable AnimatedContentScope.(NavBackStackEntry) -> Unit,
) = composable(route, arguments = arguments) { entry ->
    val scope = this
    val settled = androidx.compose.runtime.remember {
        androidx.compose.runtime.derivedStateOf {
            scope.transition.currentState == scope.transition.targetState
        }
    }
    val immersive = isImmersive()
    // Immersive: the page runs its own fade (ImmersiveMotion.PageFade) and hands it to the Liquid
    // Glass recording it, which then fades with it on every frame of the transition.
    val fade = if (!immersive) null else scope.transition.animateFloat(
        transitionSpec = {
            val ms = if (targetState == androidx.compose.animation.EnterExitState.Visible) IFade.enterMs else IFade.exitMs
            tween(ms)
        },
        label = "pageFade",
    ) { if (it == androidx.compose.animation.EnterExitState.Visible) 1f else 0f }
    val alpha = androidx.compose.runtime.remember(fade) { { fade?.value ?: 1f } }
    androidx.compose.runtime.CompositionLocalProvider(
        LocalPageSettled provides settled,
        com.music.spotui.ui.immersive.shell.backdrop.backdrops.LocalBackdropSourceAlpha provides alpha,
    ) {
        // Large windows: detail pages (album, playlist, artist, settings…) keep a readable width,
        // centred, instead of rows 2000 px wide; the tab pages and grids use the whole width
        // (more columns). The inner box is always there, so a resize never rebuilds the page.
        val capped = com.music.spotui.ui.components.windowSize().large &&
            entry.destination.route?.substringBefore('/')?.substringBefore('?') !in FullWidthRoutes
        // Immersive look: every page (Classic fallbacks too) sits on its black page colour.
        Box(Modifier.fillMaxSize()
            .then(if (fade == null) Modifier else Modifier.graphicsLayer { this.alpha = fade.value })
            .background(if (immersive) com.music.spotui.ui.immersive.Immersive.Page else AppBackground)) {
            Box(
                Modifier.fillMaxHeight().then(
                    if (capped) Modifier.fillMaxWidth().wrapContentWidth(Alignment.CenterHorizontally)
                        .widthIn(max = com.music.spotui.ui.components.WindowSize.MaxPageWidth)
                    else Modifier.fillMaxWidth()
                )
            ) { scope.content(entry) }
        }
    }
}

/** Pages that use a large window's whole width (grids, the tabs' own pages, the player). */
private val FullWidthRoutes = setOf(
    Routes.Home.route, Routes.Search.route, Routes.Library.route, Routes.Player.route, Routes.Queue.route,
    Routes.Category.route, Routes.NewReleases.route,
)

/**
 * false while this page is sliding in or out. Heavy views (video players, surfaces)
 * wait for it: built in the first frame, they stalled it and the motion jumped.
 */
val LocalPageSettled = androidx.compose.runtime.staticCompositionLocalOf<androidx.compose.runtime.State<Boolean>> {
    androidx.compose.runtime.mutableStateOf(true)
}

/** The Immersive look is on (Settings > Display, or the onboarding's look step). */
@Composable
internal fun isImmersive(): Boolean =
    com.music.spotui.ui.look.LocalLook.current == com.music.spotui.data.preferences.Look.IMMERSIVE
