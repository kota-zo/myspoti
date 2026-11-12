/* SPDX-License-Identifier: GPL-3.0-only */
package com.music.spotui.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.github.takahirom.roborazzi.captureRoboImage
import com.music.spotui.data.api.Response
import com.music.spotui.data.entity.AlbumsModel
import com.music.spotui.data.entity.ArtistOverviewModel
import com.music.spotui.data.entity.ArtistTrackUi
import com.music.spotui.data.entity.ArtistsModel
import com.music.spotui.data.entity.ContextPageModel
import com.music.spotui.data.entity.HomeFeedModel
import com.music.spotui.data.entity.HomeItem
import com.music.spotui.data.entity.HomeSection
import com.music.spotui.data.entity.LibraryEntry
import com.music.spotui.data.entity.SearchResults
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.data.preferences.Look
import com.music.spotui.di.Graph
import com.music.spotui.ui.components.ProvideWindowSize
import com.music.spotui.ui.components.WindowSize
import com.music.spotui.ui.components.windowSize
import com.music.spotui.ui.immersive.player.ImmersivePlayer
import com.music.spotui.ui.immersive.player.ImmersivePlayerActions
import com.music.spotui.ui.immersive.player.ImmersivePlayerState
import com.music.spotui.ui.immersive.player.ImmersiveRepeat
import com.music.spotui.ui.look.LocalLook
import com.music.spotui.ui.navigation.Routes
import com.music.spotui.ui.screens.ContextViewModel
import com.music.spotui.ui.theme.SpotuiTheme
import com.music.spotui.ui.viewmodel.ArtistViewModel
import com.music.spotui.ui.viewmodel.SearchViewModel
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The main screens of both looks drawn at phone, foldable, tablet and desktop sizes, with
 * made-up data and no network (covers stay placeholders), to look for layout defects. One
 * subclass per window size; the images land in app/build/outputs/roborazzi/adaptive-<size>-<screen>.png.
 * Run with `./gradlew :app:testDebugUnitTest --tests '*AdaptiveScreenshotTest*'`.
 * Pages that need a ViewModel get one seeded with the page's data, so they never load.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
abstract class AdaptiveScreenshotTest(private val size: String) {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Before fun setUp() {
        // The view models' defaults read Graph.api, which only needs the application.
        Graph::class.java.getDeclaredField("app").apply { isAccessible = true }.set(null, RuntimeEnvironment.getApplication())
        val st = Graph.currentSongState
        fun set(name: String, v: Any) {
            @Suppress("UNCHECKED_CAST")
            (st.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(st) as androidx.compose.runtime.MutableState<Any>).value = v
        }
        set("_title", "Breathe Deeper (Extended Version With A Long Title)")
        set("_singer", "Tame Impala, Lil Yachty")
        set("_album", "The Slow Rush")
        set("_playingState", true)
        offlineRuntime()
    }

    /**
     * No Spotify session: the API is a stand-in whose reads never answer (pages stay as seeded) and
     * whose flows are empty; the WebView host is a bare instance with only its account store.
     */
    private fun offlineRuntime() {
        val rt = app.myspoti.host.android.MyspotiRuntime
        rt.useApi(stub(app.myspoti.api.MyspotiApi::class.java))
    }

    /** The settings read the WebView host's account list: a bare host with only that (taken away after the test). */
    private fun bareHost() {
        val rt = app.myspoti.host.android.MyspotiRuntime
        run {
            val unsafe = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
            val host = unsafe.javaClass.getMethod("allocateInstance", Class::class.java)
                .invoke(unsafe, app.myspoti.host.android.WebPlayerHost::class.java) as app.myspoti.host.android.WebPlayerHost
            val ctx = RuntimeEnvironment.getApplication()
            app.myspoti.host.android.WebPlayerHost::class.java.getDeclaredField("accounts").apply { isAccessible = true }
                .set(host, app.myspoti.host.android.AccountStore(java.io.File(ctx.filesDir, "accounts.json")))
            rt.host = host
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> stub(type: Class<T>): T = java.lang.reflect.Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { proxy, m, args ->
        val r = m.returnType
        val g = m.genericReturnType.typeName
        when {
            m.name == "toString" -> "stub(${type.simpleName})"
            m.name == "hashCode" -> System.identityHashCode(proxy)
            m.name == "equals" -> proxy === args?.get(0)
            args?.lastOrNull() is kotlin.coroutines.Continuation<*> -> kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
            kotlinx.coroutines.flow.SharedFlow::class.java.isAssignableFrom(r) && !kotlinx.coroutines.flow.StateFlow::class.java.isAssignableFrom(r) -> kotlinx.coroutines.flow.MutableSharedFlow<Any?>()
            kotlinx.coroutines.flow.StateFlow::class.java.isAssignableFrom(r) -> kotlinx.coroutines.flow.MutableStateFlow<Any?>(
                when { g.contains("Boolean") -> false; g.contains("List<") -> emptyList<Any>(); else -> null })
            kotlinx.coroutines.flow.Flow::class.java.isAssignableFrom(r) -> kotlinx.coroutines.flow.emptyFlow<Any?>()
            r == java.lang.Boolean.TYPE -> false
            r == Integer.TYPE -> 0
            r == java.lang.Long.TYPE -> 0L
            r == String::class.java -> ""
            List::class.java.isAssignableFrom(r) -> emptyList<Any>()
            Map::class.java.isAssignableFrom(r) -> emptyMap<Any, Any>()
            r.isInterface -> stub(r)
            else -> null
        }
    } as T

    private fun shoot(name: String, look: Look = Look.CLASSIC, content: @Composable () -> Unit) {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            SpotuiTheme {
                CompositionLocalProvider(LocalLook provides look) {
                    ProvideWindowSize {
                        Box(Modifier.fillMaxSize().background(if (look == Look.IMMERSIVE) Color.Black else Color(0xFF121212))) { content() }
                    }
                }
            }
        }
        rule.mainClock.advanceTimeBy(1_500)
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/adaptive-$size-$name.png")
        // The covers draw nothing without network: their boxes (described images), and every
        // clickable's box (touch targets), in dp, in a text file beside the image.
        val d = rule.activity.resources.displayMetrics.density
        val sp = androidx.compose.ui.semantics.SemanticsProperties
        fun label(c: androidx.compose.ui.semantics.SemanticsConfiguration) =
            (c.getOrNull(sp.ContentDescription) ?: c.getOrNull(sp.Text)?.map { it.text })?.joinToString()?.take(40) ?: ""
        java.io.File("build/outputs/roborazzi/adaptive-$size-$name.txt").writeText(
            rule.onAllNodes(androidx.compose.ui.test.SemanticsMatcher("described or clickable") {
                sp.ContentDescription in it.config || androidx.compose.ui.semantics.SemanticsActions.OnClick in it.config
            }, useUnmergedTree = true).fetchSemanticsNodes().joinToString("\n") { n ->
                val b = n.boundsInRoot
                val kind = if (androidx.compose.ui.semantics.SemanticsActions.OnClick in n.config) "click" else "image"
                "$kind\t${label(n.config).ifEmpty { n.children.firstNotNullOfOrNull { c -> label(c.config).ifEmpty { null } } ?: "" }}\t" +
                    "x=${(b.left / d).toInt()} y=${(b.top / d).toInt()} w=${(b.width / d).toInt()} h=${(b.height / d).toInt()}"
            })
    }

    private inline fun <reified T : ViewModel> seed(key: String? = null): T {
        val p = ViewModelProvider(rule.activity)
        return if (key == null) p[T::class.java] else p[key, T::class.java]
    }

    private fun Any.poke(field: String, value: Any?) {
        var c: Class<*>? = javaClass
        while (c != null) {
            runCatching { c!!.getDeclaredField(field) }.getOrNull()?.let { f ->
                f.isAccessible = true
                val cur = f.get(this)
                if (cur is kotlinx.coroutines.flow.MutableStateFlow<*>) {
                    @Suppress("UNCHECKED_CAST") (cur as kotlinx.coroutines.flow.MutableStateFlow<Any?>).value = value
                } else f.set(this, value)
                return
            }
            c = c.superclass
        }
        error("no field $field")
    }

    // ---- made-up data ----

    private fun song(i: Int, title: String, artist: String) =
        SongsModel(i, title, "Album", artist, "", "spotify:track:$i", durationMs = 180_000 + i * 7_000)

    private val songs = listOf(
        song(1, "Midnight City", "M83"), song(2, "Breathe Deeper", "Tame Impala"), song(3, "Nightcall", "Kavinsky"),
        song(4, "Instant Crush (feat. Julian Casablancas) - Remastered 2023 Edition", "Daft Punk, Julian Casablancas"),
        song(5, "Electric Feel", "MGMT"), song(6, "Redbone", "Childish Gambino"),
        song(7, "The Less I Know the Better", "Tame Impala"), song(8, "Kids", "MGMT"), song(9, "Genesis", "Justice"),
        song(10, "Odd Look", "Kavinsky, The Weeknd"), song(11, "Feels Like We Only Go Backwards", "Tame Impala"), song(12, "D.A.N.C.E.", "Justice"),
    )

    private fun playlists(n: Int, prefix: String) = List(n) { HomeItem.Playlist("$prefix ${it + 1}", "", "Mix of the week with M83, MGMT and more", id = "pl$prefix$it") }

    private val feed = HomeFeedModel("Good evening", listOf(
        HomeSection("", playlists(8, "Daily Mix")),
        HomeSection("Made for you", playlists(9, "Discover")),
        HomeSection("Recently played", playlists(9, "Radio")),
        HomeSection("Your top mixes", playlists(9, "Top")),
    ))

    private val entries = List(14) {
        LibraryEntry("pl$it", if (it == 3) "A very long playlist name that does not fit on one line at all" else "Playlist ${it + 1}",
            "Playlist • Myspoti", "", isPlaylist = true, uri = "spotify:playlist:pl$it")
    }
    private val artists = listOf("M83", "Tame Impala", "Kavinsky").mapIndexed { i, n -> ArtistsModel(n, "", id = "a$i") }

    private val results = SearchResults(
        songs = songs.take(6),
        artists = listOf("M83", "Tame Impala", "Kavinsky", "MGMT", "Justice").mapIndexed { i, n -> ArtistsModel(n, "", id = "a$i") },
        albums = List(6) { AlbumsModel(it, "M83", "", "Album ${it + 1}", "2011", spotifyId = "al$it") },
    )

    private val overview = ArtistOverviewModel(
        id = "a1", name = "Tame Impala", monthlyListeners = 31_245_871,
        topTracks = songs.take(10).map { ArtistTrackUi(it) },
        popularReleases = List(8) { AlbumsModel(it, "Tame Impala", "", "Release ${it + 1}", "2020", spotifyId = "r$it") },
        relatedArtists = listOf("MGMT", "Pond", "Unknown Mortal Orchestra", "Mac DeMarco").mapIndexed { i, n -> ArtistsModel(n, "", id = "r$i") },
    )

    private val album = ContextPageModel("spotify:album:al1", "album", "Currents (Deluxe Edition)", "",
        description = "", ownerName = "Tame Impala", metaLine = "Album • 2015", tracks = songs)

    private fun seedArtist() = seed<ArtistViewModel>().apply { poke("loadedOverview", "a1"); poke("_overview", Response.Success(this@AdaptiveScreenshotTest.overview)) }
    private fun seedAlbum() = seed<ContextViewModel>("album:al1").apply { poke("key", "album:al1"); poke("_page", Response.Success(album)) }
    private fun seedSearch() = seed<SearchViewModel>().apply { query = "tame"; poke("_results", Response.Success(this@AdaptiveScreenshotTest.results)) }

    // ---- shells ----

    /** Classic: the rail on large windows and phones on their side, the bottom bar (mini player + tabs), the page capped as MyNavHost does. */
    @Composable
    private fun ClassicShell(route: String, capped: Boolean, page: @Composable (NavHostController) -> Unit) {
        val nav = rememberNavController()
        val large = windowSize().large
        Row(Modifier.fillMaxSize()) {
            if (windowSize().navigationRail) com.music.spotui.ui.navigation.MainNavigationRail(nav)
            Scaffold(modifier = Modifier.weight(1f), bottomBar = { com.music.spotui.ui.navigation.MainBottomNavigation(nav) }) { _ ->
                NavHost(nav, startDestination = route) {
                    listOf(Routes.Home.route, Routes.Search.route, Routes.Library.route, "detail").forEach { r ->
                        composable(r) { CompositionLocalProvider(androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner provides rule.activity) {
                            Box(Modifier.fillMaxSize().background(Color(0xFF121212))) {
                                Box(Modifier.fillMaxHeight().then(
                                    if (large && capped) Modifier.fillMaxWidth().wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = WindowSize.MaxPageWidth)
                                    else Modifier.fillMaxWidth())) { page(nav) }
                            }
                        } }
                    }
                }
            }
        }
    }

    /** Immersive: the page on black, the floating tab bar and mini player over it. */
    @Composable
    private fun ImmersiveShell(route: String, capped: Boolean, page: @Composable (NavHostController) -> Unit) {
        val nav = rememberNavController()
        val large = windowSize().large
        val chrome = com.music.spotui.ui.immersive.shell.rememberImmersiveChromeState()
        CompositionLocalProvider(com.music.spotui.ui.immersive.shell.LocalImmersiveChrome provides chrome) {
            Box(Modifier.fillMaxSize()) {
                NavHost(nav, startDestination = route) {
                    listOf(Routes.Home.route, Routes.Search.route, Routes.Library.route, "detail").forEach { r ->
                        composable(r) { CompositionLocalProvider(androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner provides rule.activity) {
                            Box(Modifier.fillMaxSize().background(Color.Black)) {
                                Box(Modifier.fillMaxHeight().then(
                                    if (large && capped) Modifier.fillMaxWidth().wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = WindowSize.MaxPageWidth)
                                    else Modifier.fillMaxWidth())) { page(nav) }
                            }
                        } }
                    }
                }
                com.music.spotui.ui.immersive.shell.ImmersiveBottomChrome(
                    nav, miniPlayer = { m -> com.music.spotui.ui.immersive.player.ImmersiveMiniPlayer(m) },
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }
    }

    // ---- classic ----

    @Test fun classicHome() = shoot("classic-home") {
        ClassicShell(Routes.Home.route, capped = false) { nav -> com.music.spotui.ui.screens.HomeFeedContent(nav, feed) }
    }

    @Test fun classicLibrary() = shoot("classic-library") {
        ClassicShell(Routes.Library.route, capped = false) { nav -> com.music.spotui.ui.screens.SumUpLibraryScreen(PaddingValues(0.dp), entries, artists, nav) }
    }

    @Test fun classicLibraryGrid() = shoot("classic-librarygrid") {
        ClassicShell(Routes.Library.route, capped = false) { nav -> com.music.spotui.ui.screens.LibraryGridScreen(PaddingValues(0.dp), entries, artists, nav) }
    }

    /**
     * A search field keeping the focus while the player opens over its page left its cursor handle,
     * drawn by the system in a window of its own, over the player: opening the player takes it away.
     */
    private fun playerTakesTheFocus(look: Look, overlay: @Composable () -> Unit) {
        val fr = androidx.compose.ui.focus.FocusRequester()
        var focused = false
        rule.mainClock.autoAdvance = false
        rule.setContent {
            SpotuiTheme { CompositionLocalProvider(LocalLook provides look) { ProvideWindowSize {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    androidx.compose.foundation.text.BasicTextField("a query", {},
                        Modifier.focusRequester(fr).onFocusChanged { focused = it.isFocused })
                    overlay()
                }
            } } }
        }
        rule.runOnIdle { fr.requestFocus() }
        rule.mainClock.advanceTimeBy(100)
        org.junit.Assert.assertTrue("the field did not take the focus", focused)
        try {
            com.music.spotui.ui.navigation.PlayerSheet.open.value = true
            rule.mainClock.advanceTimeBy(1_000)
            org.junit.Assert.assertFalse("$look: the search field kept the focus under the open player", focused)
        } finally {
            com.music.spotui.ui.navigation.PlayerSheet.open.value = false
        }
    }

    @Test fun classicPlayerTakesTheFocus() = playerTakesTheFocus(Look.CLASSIC) {
        com.music.spotui.ui.navigation.PlayerOverlay(rememberNavController())
    }

    @Test fun immersivePlayerTakesTheFocus() = playerTakesTheFocus(Look.IMMERSIVE) {
        com.music.spotui.ui.immersive.player.ImmersivePlayerOverlay(rememberNavController())
    }

    /**
     * The Classic search field's hint in French, the longest: on one line, at the field's start after
     * the magnifier, where the typed text goes (it wrapped onto two lines centred in the bar).
     */
    @Test fun classicSearchHintAtTheStart() {
        org.robolectric.RuntimeEnvironment.setQualifiers("+fr")
        rule.setContent {
            SpotuiTheme { ProvideWindowSize { Box(Modifier.fillMaxSize().background(Color(0xFF121212))) {
                com.music.spotui.ui.screens.SearchStickyBar(text = "", onTextChange = {})
            } } }
        }
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/adaptive-$size-classic-search-hint-fr.png")
        val d = rule.activity.resources.displayMetrics.density
        val text = rule.activity.getString(app.myspoti.engine.R.string.search_header_field_hint)
        org.junit.Assert.assertTrue("not the French hint: $text", text.startsWith("Que souhaitez"))
        val hint = rule.onNodeWithText(text, useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        org.junit.Assert.assertTrue("hint on more than one line: $hint", hint.height / d < 30f)
        org.junit.Assert.assertTrue("hint not at the field's start: $hint", hint.left / d < 90f)
    }

    @Test fun classicSearch() {
        val vm = seedSearch()
        shoot("classic-search") {
            ClassicShell(Routes.Search.route, capped = false) { nav -> com.music.spotui.ui.screens.SumUpSearchScreen(nav, results, vm) }
        }
    }

    @Test fun classicAlbum() {
        seedAlbum()
        shoot("classic-album") {
            ClassicShell("detail", capped = true) { nav -> com.music.spotui.ui.screens.ContextScreen(nav, "album", "al1") }
        }
    }

    @Test fun classicArtist() {
        seedArtist()
        shoot("classic-artist") {
            ClassicShell("detail", capped = true) { nav -> com.music.spotui.ui.screens.ArtistScreen(nav, "Tame Impala", "a1") }
        }
    }

    @After fun dropHost() {
        app.myspoti.host.android.MyspotiRuntime::class.java.getDeclaredField("host").apply { isAccessible = true }
            .set(app.myspoti.host.android.MyspotiRuntime, null)
    }

    @Test fun classicSettings() = bareHost().let { shoot("classic-settings") {
        ClassicShell("detail", capped = true) { nav -> com.music.spotui.ui.screens.SettingsScreen(nav) }
    } }

    @Test fun classicPlayer() = shoot("classic-player") {
        com.music.spotui.ui.screens.PlayerScreen(rememberNavController())
    }

    // The whole tab pages, their view models reading the made-up feed and library from the
    // process caches (the stand-in API is never ready, so nothing replaces them).
    @Test fun classicHomeScreen() {
        com.music.spotui.data.api.Api.HomeCache.home = feed
        shoot("classic-homescreen") { ClassicShell(Routes.Home.route, capped = false) { nav -> com.music.spotui.ui.screens.HomeScreen(nav) } }
    }

    @Test fun classicLibraryScreen() {
        com.music.spotui.data.api.Api.HomeCache.libraryRoot = entries
        shoot("classic-libraryscreen") { ClassicShell(Routes.Library.route, capped = false) { nav -> com.music.spotui.ui.screens.LibraryScreen(nav) } }
    }

    // ---- immersive ----

    @Test fun immersivePlayer() = shoot("immersive-player", Look.IMMERSIVE) {
        ImmersivePlayer(
            ImmersivePlayerState(title = "Breathe Deeper (Extended Version With A Long Title)", artist = "Tame Impala, Lil Yachty",
                album = "The Slow Rush", playing = true, positionMs = { 74_000L }, durationMs = 372_000L,
                shuffle = true, repeatMode = ImmersiveRepeat.ALL, liked = true, contextName = "Night Drive"),
            ImmersivePlayerActions(),
            navController = rememberNavController(),
        )
    }

    @Test fun immersiveLibrary() = shoot("immersive-library", Look.IMMERSIVE) {
        ImmersiveShell(Routes.Library.route, capped = false) { nav ->
            com.music.spotui.ui.immersive.screens.LibraryList(
                rememberLazyListState(), header = { Text("Library", color = Color.White, fontSize = 34.sp, modifier = Modifier.padding(16.dp)) },
                loading = false, entries = entries, artists = artists, loadFailed = false, onRetry = {}, navController = nav,
                reorderLevel = null, pinned = 0, onMenu = {},
            )
        }
    }

    @Test fun immersiveHomeScreen() {
        com.music.spotui.data.api.Api.HomeCache.home = feed
        shoot("immersive-homescreen", Look.IMMERSIVE) {
            ImmersiveShell(Routes.Home.route, capped = false) { nav -> com.music.spotui.ui.immersive.screens.ImmersiveHomeScreen(nav) }
        }
    }

    @Test fun immersiveLibraryScreen() {
        com.music.spotui.data.api.Api.HomeCache.libraryRoot = entries
        shoot("immersive-libraryscreen", Look.IMMERSIVE) {
            ImmersiveShell(Routes.Library.route, capped = false) { nav -> com.music.spotui.ui.immersive.screens.ImmersiveLibraryScreen(nav) }
        }
    }

    @Test fun immersiveSearch() {
        seedSearch()
        shoot("immersive-search", Look.IMMERSIVE) {
            ImmersiveShell(Routes.Search.route, capped = false) { nav -> com.music.spotui.ui.immersive.screens.ImmersiveSearchScreen(nav) }
        }
    }

    @Test fun immersiveAlbum() {
        seedAlbum()
        shoot("immersive-album", Look.IMMERSIVE) {
            ImmersiveShell("detail", capped = true) { nav -> com.music.spotui.ui.immersive.screens.ImmersiveContextScreen(nav, "album", "al1") }
        }
    }

    @Test fun immersiveArtist() {
        seedArtist()
        shoot("immersive-artist", Look.IMMERSIVE) {
            ImmersiveShell("detail", capped = true) { nav -> com.music.spotui.ui.immersive.screens.ImmersiveArtistScreen(nav, "Tame Impala", "a1") }
        }
    }

    // ---- a passing height ----

    /**
     * The player drawn in a box that is, for a moment, shorter than wide (as a passing measure
     * gives it: the sheet's first frame, back from picture-in-picture or another app), then whole
     * again. The play / pause button is back exactly where it was; on a phone held upright it
     * also stayed under the cover meanwhile (centred), the one-column layout never left for the
     * side-by-side one. The short frame lands in adaptive-<size>-<name>-short.png.
     */
    private fun keepsItsControls(name: String, look: Look, content: @Composable () -> Unit) {
        var short by androidx.compose.runtime.mutableStateOf(false)
        rule.mainClock.autoAdvance = false
        rule.setContent {
            SpotuiTheme {
                CompositionLocalProvider(LocalLook provides look) {
                    ProvideWindowSize {
                        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
                            val h = if (short) minOf(maxHeight, maxWidth * 0.8f) else maxHeight
                            Box(Modifier.fillMaxWidth().height(h).background(if (look == Look.IMMERSIVE) Color.Black else Color(0xFF121212))) { content() }
                        }
                    }
                }
            }
        }
        val d = rule.activity.resources.displayMetrics.density
        fun pause() = rule.onAllNodes(androidx.compose.ui.test.hasContentDescription("Pause"), useUnmergedTree = true)
            .fetchSemanticsNodes().first().boundsInRoot
        rule.mainClock.advanceTimeBy(1_500)
        val before = pause()
        short = true
        rule.mainClock.advanceTimeBy(500)
        val during = pause()
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/adaptive-$size-$name-short.png")
        short = false
        rule.mainClock.advanceTimeBy(1_500)
        val after = pause()
        fun near(a: Float, b: Float) = kotlin.math.abs(a - b) / d < 1f
        org.junit.Assert.assertTrue("$name: play / pause moved from $before to $after",
            near(before.left, after.left) && near(before.top, after.top) && near(before.width, after.width))
        val c = rule.activity.resources.configuration
        if (WindowSize(c.screenWidthDp, c.screenHeightDp).portraitPhone) {
            val centre = rule.onRoot().fetchSemanticsNode().boundsInRoot.center.x
            org.junit.Assert.assertTrue("$name: play / pause left the column under the cover ($during)",
                kotlin.math.abs(during.center.x - centre) / d < 4f)
        }
    }

    /**
     * The player open while the song's lyrics come, are not found, load again for the next song and
     * come late: the play / pause button never moves (reported: the controls at the bottom on songs
     * without lyrics). The frame without lyrics lands in adaptive-<size>-<name>-nolyrics.png.
     */
    private fun keepsItsControlsWithoutLyrics(name: String, look: Look, content: @Composable () -> Unit) {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            SpotuiTheme {
                CompositionLocalProvider(LocalLook provides look) {
                    ProvideWindowSize {
                        Box(Modifier.fillMaxSize().background(if (look == Look.IMMERSIVE) Color.Black else Color(0xFF121212))) { content() }
                    }
                }
            }
        }
        val vm = seed<com.music.spotui.ui.viewmodel.LyricsViewModel>()
        val loaded = com.music.spotui.ui.viewmodel.LyricsViewModel.State.Loaded(com.music.spotui.data.entity.Lyrics(
            (0 until 12).map { com.music.spotui.data.entity.LyricLine(it * 4_000L, "A line of the song number $it") }, synced = true))
        val d = rule.activity.resources.displayMetrics.density
        fun pause() = rule.onAllNodes(androidx.compose.ui.test.hasContentDescription("Pause"), useUnmergedTree = true)
            .fetchSemanticsNodes().first().boundsInRoot
        fun at(state: com.music.spotui.ui.viewmodel.LyricsViewModel.State): androidx.compose.ui.geometry.Rect {
            vm.poke("_state", state)
            rule.mainClock.advanceTimeBy(1_000)
            return pause()
        }
        val withLyrics = at(loaded)
        val none = at(com.music.spotui.ui.viewmodel.LyricsViewModel.State.NotFound)
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/adaptive-$size-$name-nolyrics.png")
        val loading = at(com.music.spotui.ui.viewmodel.LyricsViewModel.State.Loading)
        val late = at(loaded)
        for ((what, r) in listOf("without lyrics" to none, "while they load" to loading, "when they come late" to late))
            org.junit.Assert.assertTrue("$name: play / pause moved $what, from $withLyrics to $r",
                kotlin.math.abs(r.top - withLyrics.top) / d < 1f && kotlin.math.abs(r.left - withLyrics.left) / d < 1f)
    }

    @Test fun classicPlayerWithoutLyrics() = keepsItsControlsWithoutLyrics("classic-player", Look.CLASSIC) {
        com.music.spotui.ui.screens.PlayerScreen(rememberNavController())
    }

    @Test fun immersivePlayerWithoutLyrics() = keepsItsControlsWithoutLyrics("immersive-player", Look.IMMERSIVE) {
        ImmersivePlayer(
            ImmersivePlayerState(title = "A song", artist = "An artist", album = "An album", playing = true,
                positionMs = { 74_000L }, durationMs = 372_000L),
            ImmersivePlayerActions(),
            navController = rememberNavController(),
        )
    }

    @Test fun classicPlayerPassingHeight() = keepsItsControls("classic-player", Look.CLASSIC) {
        com.music.spotui.ui.screens.PlayerScreen(rememberNavController())
    }

    @Test fun immersivePlayerPassingHeight() = keepsItsControls("immersive-player", Look.IMMERSIVE) {
        ImmersivePlayer(
            ImmersivePlayerState(title = "Breathe Deeper", artist = "Tame Impala", album = "The Slow Rush", playing = true,
                positionMs = { 74_000L }, durationMs = 372_000L),
            ImmersivePlayerActions(),
            navController = rememberNavController(),
        )
    }

    // ---- the queue's drag ----

    /**
     * The player's queue as QueueProbeActivity lays it out (the player's header above it, its
     * controls under it), 30 songs: a row taken by its handle and carried above the top of the list,
     * over the header, and held there. The list runs up under it and the row goes along to the head
     * of the queue (past the context's heading, into "Next in queue"); the drag was undone instead
     * (reported). Phone size only.
     */
    @Test fun queueRowCarriedAboveTheList() {
        org.junit.Assume.assumeTrue(size == "phone")
        val items = (1..30).map { i ->
            com.music.spotui.di.SongPlayer.QueueItem(SongsModel(i, "Song %02d".format(i), "Album", "Artist $i", "", "", spotifyTrackId = ""),
                "local:-$i", (i - 1).toString(), com.music.spotui.di.SongPlayer.LOCAL)
        }
        com.music.spotui.di.SongPlayer.showProbeQueue(com.music.spotui.di.SongPlayer.EngineQueue(
            current = SongsModel(0, "Now playing", "Album", "Artist", "", "", spotifyTrackId = ""), nextUp = items))
        rule.mainClock.autoAdvance = false
        rule.setContent {
            SpotuiTheme {
                CompositionLocalProvider(LocalLook provides Look.IMMERSIVE) {
                    ProvideWindowSize {
                        androidx.compose.foundation.layout.Column(Modifier.fillMaxSize().background(Color(0xFF3B2A2E))) {
                            Box(Modifier.fillMaxWidth().height(110.dp).background(Color(0xFF2A1E21)))
                            Box(Modifier.fillMaxWidth().weight(1f)) {
                                com.music.spotui.ui.immersive.lyrics.ImmersiveQueuePanel(
                                    Modifier.fillMaxSize().testTag("queue"),
                                    PaddingValues(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 12.dp))
                            }
                            Box(Modifier.fillMaxWidth().height(330.dp).background(Color(0xFF2A1E21)))
                        }
                    }
                }
            }
        }
        rule.mainClock.advanceTimeBy(1_000)
        // Some rows above the ones seen, for the list to run up to: header, now playing and its
        // heading, the context's heading, then the songs (Song 09 first).
        rule.onNodeWithTag("queue").performScrollToIndex(12)
        rule.mainClock.advanceTimeBy(500)
        val d = rule.activity.resources.displayMetrics.density
        val handles = rule.onAllNodes(androidx.compose.ui.test.hasContentDescription("Move in queue"), useUnmergedTree = true)
            .fetchSemanticsNodes().map { it.boundsInRoot }.filter { it.top > 110 * d }.sortedBy { it.top }
        // The third row seen: the finger then goes over two rows and out of the list.
        val from = handles[2].center
        val title = rule.onAllNodes(androidx.compose.ui.test.hasText("Song 11"), useUnmergedTree = true)
            .fetchSemanticsNodes().first().boundsInRoot
        org.junit.Assert.assertTrue("Song 11 is the third row seen", kotlin.math.abs(title.center.y - from.y) < 24 * d)
        rule.onRoot().performTouchInput { down(from) }
        rule.mainClock.advanceTimeByFrame()
        // Up past the top of the list (110 dp from the window's top), into the header, by small moves.
        val to = androidx.compose.ui.geometry.Offset(from.x, 50 * d)
        val steps = 30
        for (i in 1..steps) {
            rule.onRoot().performTouchInput { moveTo(from + (to - from) * (i / steps.toFloat())) }
            rule.mainClock.advanceTimeByFrame()
        }
        // Held there for three seconds: the list runs to its top (eight rows or so a second).
        repeat(180) {
            rule.onRoot().performTouchInput { moveTo(to) }
            rule.mainClock.advanceTimeByFrame()
        }
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/adaptive-$size-queue-held-above.png")
        rule.onRoot().performTouchInput { up() }
        rule.mainClock.advanceTimeBy(1_000)
        org.robolectric.shadows.ShadowLooper.idleMainLooper(2, java.util.concurrent.TimeUnit.SECONDS)
        rule.mainClock.advanceTimeBy(1_000)
        // Past the context's heading at the very top: a song of the device's own goes into the queue, first.
        val q = com.music.spotui.di.SongPlayer.engineQueue.value
        val order = (q.queued + q.nextUp).map { it.song.title }
        org.junit.Assert.assertEquals("the row carried above the list goes to the head: $order", "Song 11", order.firstOrNull())
        org.junit.Assert.assertEquals(30, order.size)
    }

    @Test fun immersiveSettings() = bareHost().let { shoot("immersive-settings", Look.IMMERSIVE) {
        ImmersiveShell("detail", capped = true) { nav -> com.music.spotui.ui.immersive.pages.ImmersiveSettingsScreen(nav) }
    } }
}

@Config(sdk = [35], qualifiers = "w320dp-h568dp-port-xhdpi", application = Application::class)
class AdaptiveScreenshotTestSmallPhone : AdaptiveScreenshotTest("phone-small")

@Config(sdk = [35], qualifiers = "w320dp-h568dp-port-xhdpi", fontScale = 1.3f, application = Application::class)
class AdaptiveScreenshotTestSmallPhoneFont : AdaptiveScreenshotTest("phone-small-font130")

@Config(sdk = [35], qualifiers = "w411dp-h891dp-port-xxhdpi", application = Application::class)
class AdaptiveScreenshotTestPhone : AdaptiveScreenshotTest("phone")

@Config(sdk = [35], qualifiers = "w891dp-h411dp-land-xxhdpi", application = Application::class)
class AdaptiveScreenshotTestPhoneLand : AdaptiveScreenshotTest("phone-land")

@Config(sdk = [35], qualifiers = "w673dp-h841dp-port-xhdpi", application = Application::class)
class AdaptiveScreenshotTestFold : AdaptiveScreenshotTest("fold")

@Config(sdk = [35], qualifiers = "w800dp-h1280dp-port-xhdpi", application = Application::class)
class AdaptiveScreenshotTestTabletPort : AdaptiveScreenshotTest("tablet-port")

@Config(sdk = [35], qualifiers = "w1280dp-h800dp-land-xhdpi", application = Application::class)
class AdaptiveScreenshotTestTabletLand : AdaptiveScreenshotTest("tablet-land")

@Config(sdk = [35], qualifiers = "w1920dp-h1080dp-land-mdpi", application = Application::class)
class AdaptiveScreenshotTestDesktop : AdaptiveScreenshotTest("desktop")
