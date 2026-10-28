/*
 * Adapted from BitChord (https://github.com/kushagrasinghx/BitChord), GPL-3.0-or-later,
 * sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/screens/HomeScreen.kt at edc1e6f.
 * Modified for Myspoti, 2026-10.
 */
package com.music.spotui.ui.immersive.screens

import com.music.spotui.ui.immersive.shell.immersiveHazeSource
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import app.myspoti.engine.R
import com.music.spotui.data.api.Features
import com.music.spotui.data.api.ProfileCache
import com.music.spotui.data.api.Response
import com.music.spotui.data.entity.HomeFeedModel
import com.music.spotui.data.entity.HomeItem
import com.music.spotui.data.entity.HomeSection
import com.music.spotui.di.Graph
import com.music.spotui.di.SongPlayer
import com.music.spotui.ui.components.pressHint
import com.music.spotui.ui.immersive.Immersive
import com.music.spotui.ui.immersive.components.ImmersiveCover
import com.music.spotui.ui.immersive.components.ImmersiveLargeAlbumCard
import com.music.spotui.ui.immersive.components.ImmersiveMessageState
import com.music.spotui.ui.immersive.components.ImmersiveShelf
import com.music.spotui.ui.immersive.components.ImmersiveShelfCard
import com.music.spotui.ui.immersive.components.ImmersiveShelfRow
import com.music.spotui.ui.immersive.components.SectionHeader
import com.music.spotui.ui.immersive.components.immersiveClickable
import com.music.spotui.ui.immersive.components.immersiveFeedSkeleton
import com.music.spotui.ui.immersive.components.immersiveHeroCardWidth
import com.music.spotui.ui.immersive.shell.ImmersiveFloatingHeader
import com.music.spotui.ui.immersive.shell.ImmersiveHeaderContentHeight
import com.music.spotui.ui.immersive.shell.LocalBarControlSize
import com.music.spotui.ui.immersive.shell.immersiveChromeScroll
import com.music.spotui.ui.immersive.shell.immersiveHeaderContentPadding
import com.music.spotui.ui.immersive.shell.rememberTopBarCollapse
import com.music.spotui.ui.navigation.TabReselect
import com.music.spotui.ui.navigation.albumRoute
import com.music.spotui.ui.navigation.artistRoute
import com.music.spotui.ui.navigation.playlistRoute
import com.music.spotui.ui.navigation.showRoute
import com.music.spotui.ui.screens.ContextViewModel
import com.music.spotui.ui.viewmodel.HomeViewModel

/**
 * Home in the Immersive look: the greeting as the page's large title with the profile disc at its
 * end, the facet pills, then the feed — the first carousel as big cards, the rest as shelves. The
 * data, clicks, prefetch and profile actions are those of the Classic Home screen.
 */
@Composable
fun ImmersiveHomeScreen(navController: NavController) {
    val homeViewModel: HomeViewModel = viewModel()
    val home by homeViewModel.home.collectAsState()
    val feed = (home as? Response.Success)?.data
    val facet = homeViewModel.facet.value
    val context = LocalContext.current
    LaunchedEffect(Unit) { ProfileCache.ensure(context) }

    // Re-tapping the Home tab while on Home scrolls back to the top, as in the Classic screen.
    val listState = rememberLazyListState()
    LaunchedEffect(Unit) {
        TabReselect.home.collect {
            if (listState.firstVisibleItemIndex != 0 || listState.firstVisibleItemScrollOffset != 0) {
                listState.animateScrollToItem(0)
            }
        }
    }

    val homeTitle = stringResource(R.string.navigationbar_musicappitems_home_title)
    val title = feed?.greeting?.takeIf { it.isNotBlank() } ?: homeTitle
    var showProfile by remember { mutableStateOf(false) }
    if (showProfile) {
        ImmersiveProfileSheet(
            onOpen = { route -> showProfile = false; navController.navigate(route) },
            onDismiss = { showProfile = false },
        )
    }

    // The context playing right now (empty while paused), derived once for every card: a card
    // only recomposes when its own answer flips, not on every change of the player's state.
    val playingContext = remember {
        derivedStateOf { if (Graph.currentSongState.playingState.value) SongPlayer.nowPlaying.value.contextUri else "" }
    }

    // Podcasts can be off: their shows and episodes then leave the feed.
    val sections = remember(feed) { feed?.let { visibleSections(it) }.orEmpty() }
    // Only the untitled section the web home starts with is a shortcut grid; a titled lead
    // section is a carousel like any other.
    val gridSection = sections.firstOrNull()?.takeIf { it.title.isBlank() }
    val carousels = if (gridSection != null) sections.drop(1) else sections
    // The shortcuts at the top load in the background, ready before a tap.
    LaunchedEffect(gridSection?.items?.map { it.name }) {
        val top = gridSection?.items.orEmpty().take(8).mapNotNull { item ->
            when (item) {
                is HomeItem.Album -> item.id.takeIf { it.isNotBlank() }?.let { "album" to it }
                is HomeItem.Playlist -> item.id.takeIf { it.isNotBlank() }?.let { "playlist" to it }
                else -> null
            }
        }
        if (top.isNotEmpty()) ContextViewModel.prefetchSoon(top)
    }

    Box(Modifier.fillMaxSize().background(Immersive.Page)) {
        LazyColumn(
            state = listState,
            // The greeting is the floating header's own title, so the feed starts right under it
            // (it used to be a large title under an empty bar row: the gap at the top of Home).
            contentPadding = PaddingValues(top = immersiveHeaderContentPadding(), bottom = Immersive.BottomChrome),
            modifier = Modifier.fillMaxSize().immersiveHazeSource().immersiveChromeScroll(),
        ) {
            item(key = "home:facets") {
                ImmersiveChipRow {
                    ImmersiveChip(stringResource(R.string.filter_chip_all_label), selected = facet.isEmpty()) {
                        if (facet.isNotEmpty()) homeViewModel.selectFacet(facet)
                    }
                    ImmersiveChip(stringResource(R.string.recents_content_filter_music), selected = facet == "music-chip") {
                        homeViewModel.selectFacet("music-chip")
                    }
                    if (Features.PODCASTS) {
                        ImmersiveChip(stringResource(R.string.ad_category_title_podcasts), selected = facet == "podcasts-chip") {
                            homeViewModel.selectFacet("podcasts-chip")
                        }
                        ImmersiveChip(stringResource(R.string.audiobooks_title), selected = facet == "audiobooks-chip") {
                            homeViewModel.selectFacet("audiobooks-chip")
                        }
                    }
                }
            }
            when {
                feed != null && feed.sections.isNotEmpty() -> {
                    gridSection?.let { section ->
                        item(key = "home:shortcuts") {
                            ShortcutGrid(navController, section.items.take(8), playingContext)
                        }
                    }
                    carousels.forEachIndexed { index, section ->
                        item(key = "home:section:$index:${section.title}") {
                            // The lead carousel gets the big cards, unless it is a row of artists.
                            val hero = index == 0 && section.items.none { it is HomeItem.Artist }
                            if (hero) HeroSection(navController, section, playingContext) else ShelfSection(navController, section, playingContext)
                        }
                    }
                    // Sections from extensions (home.sections), after Spotify's own.
                    item(key = "home:extensions") {
                        com.music.spotui.extensions.ExtensionBlocks(app.myspoti.extensions.Hook.HOME, app.myspoti.extensions.Surface.HOME, "{}")
                    }
                }
                home is Response.Loading -> immersiveFeedSkeleton(firstIsHero = true)
                else -> item(key = "home:message") {
                    ImmersiveMessageState(
                        message = stringResource(if (home is Response.Error) R.string.home_error else R.string.home_empty),
                        actionLabel = if (home is Response.Error) stringResource(R.string.home_retry) else null,
                        onAction = if (home is Response.Error) ({ homeViewModel.retry() }) else null,
                    )
                }
            }
        }
        // The greeting and the profile disc on floating glass; the feed scrolls under it.
        ImmersiveFloatingHeader(title, collapse = rememberTopBarCollapse(listState, 24.dp)) {
            val size = LocalBarControlSize.current
            // The tap area is the header's full height and reaches past the disc toward the
            // title, so the small disc of the minimized bar is still easy to hit.
            Box(
                Modifier
                    .height(ImmersiveHeaderContentHeight)
                    .width(size + 20.dp)
                    .immersiveClickable(onClick = { showProfile = true }),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Box(
                    Modifier.size(size).graphicsLayer { scaleX = size / 44.dp; scaleY = scaleX },
                    contentAlignment = Alignment.Center,
                ) {
                    ProfileDisc(onClick = null)
                }
            }
        }
    }
}

private fun visibleSections(feed: HomeFeedModel): List<HomeSection> =
    if (Features.PODCASTS) feed.sections
    else feed.sections.map { s -> s.copy(items = s.items.filterNot { it is HomeItem.Show }) }.filter { it.items.isNotEmpty() }

/** Albums and playlists start loading under the finger. */
private fun prefetchHomeItem(item: HomeItem) {
    when (item) {
        is HomeItem.Album -> if (item.id.isNotBlank()) ContextViewModel.prefetch("album", item.id)
        is HomeItem.Playlist -> if (item.id.isNotBlank()) ContextViewModel.prefetch("playlist", item.id)
        else -> {}
    }
}

private fun onHomeItemClick(navController: NavController, item: HomeItem) {
    when (item) {
        is HomeItem.Album -> navController.navigate(albumRoute(item.name, item.artists.ifBlank { item.subtitle }, item.id))
        is HomeItem.Artist -> navController.navigate(artistRoute(item.name, item.id))
        is HomeItem.Show ->
            if (item.isEpisode) SongPlayer.playSong("episode:${item.id}")
            else navController.navigate(showRoute(item.id, item.name))
        // The real playlist content by its id (daily mixes, etc).
        is HomeItem.Playlist ->
            if (item.id.isNotBlank()) navController.navigate(playlistRoute(item.id, item.name))
            else navController.navigate(albumRoute(item.name))
    }
}

/** The uri of the thing this card opens, to tell whether it is what is playing. */
private fun HomeItem.contextUri(): String = when (this) {
    is HomeItem.Album -> if (id.isNotBlank()) "spotify:album:$id" else ""
    is HomeItem.Playlist -> if (id.isNotBlank()) "spotify:playlist:$id" else ""
    is HomeItem.Artist -> if (id.isNotBlank()) "spotify:artist:$id" else ""
    is HomeItem.Show -> if (id.isNotBlank() && !isEpisode) "spotify:show:$id" else ""
}

/** True while this card's album, playlist or artist is the playing context, [playingContext]. */
private fun HomeItem.isPlayingIn(playingContext: String): Boolean {
    val uri = contextUri()
    return uri.isNotEmpty() && playingContext == uri
}

/**
 * Keys for the cards of a shelf: the uri they open, or their kind and name for those with none
 * (an episode). A repeat within one shelf gets a suffix, as a lazy list refuses two equal keys.
 */
private fun homeItemKeys(items: List<HomeItem>): List<String> {
    val seen = HashMap<String, Int>()
    return items.map { item ->
        val base = item.contextUri().ifEmpty { "${item::class.simpleName}:${item.name}:${item.imageUrl}" }
        val count = seen.merge(base, 1, Int::plus) ?: 1
        if (count == 1) base else "$base#$count"
    }
}

@Composable
private fun HomeItem.cardSubtitle(): String = when (this) {
    is HomeItem.Album -> subtitle
    is HomeItem.Playlist -> subtitle
    is HomeItem.Artist -> stringResource(R.string.artist_subtitle)
    is HomeItem.Show -> subtitle
}

/** The lead carousel: big artwork cards with the caption over a scrim, the next one peeking in. */
@Composable
private fun HeroSection(navController: NavController, section: HomeSection, playingContext: State<String>) {
    Column(Modifier.padding(bottom = 26.dp)) {
        SectionHeader(section.title)
        BoxWithConstraints {
            // Measured: the card has a ceiling as well as a fraction, and the skeleton agrees.
            val cardWidth = immersiveHeroCardWidth(maxWidth)
            val keys = remember(section.items) { homeItemKeys(section.items) }
            ImmersiveShelfRow {
                items(section.items.size, key = { keys[it] }) { i ->
                    val item = section.items[i]
                    // One lambda per card, so the press hint's pointer input is not restarted by a recomposition.
                    val hint = remember(item) { { prefetchHomeItem(item) } }
                    ImmersiveLargeAlbumCard(
                        title = item.name,
                        subtitle = item.cardSubtitle(),
                        imageUrl = item.imageUrl,
                        onClick = { onHomeItemClick(navController, item) },
                        modifier = Modifier.width(cardWidth).pressHint(hint),
                        playing = item.isPlayingIn(playingContext.value),
                    )
                }
            }
        }
    }
}

/** A titled row of square cards (round for artists). */
@Composable
private fun ShelfSection(navController: NavController, section: HomeSection, playingContext: State<String>) {
    val keys = remember(section.items) { homeItemKeys(section.items) }
    ImmersiveShelf(title = section.title) {
        items(section.items.size, key = { keys[it] }) { i ->
            val item = section.items[i]
            val hint = remember(item) { { prefetchHomeItem(item) } }
            ImmersiveShelfCard(
                title = item.name,
                subtitle = item.cardSubtitle(),
                imageUrl = item.imageUrl,
                onClick = { onHomeItemClick(navController, item) },
                modifier = Modifier.width(com.music.spotui.ui.immersive.components.ImmersiveShelfCardWidth)
                    .pressHint(hint),
                round = item is HomeItem.Artist,
                playing = item.isPlayingIn(playingContext.value),
                badge = if (item is HomeItem.Show && !item.isEpisode) ({
                    com.music.spotui.ui.components.NewEpisodeDot(item.id, Immersive.Accent, Modifier.align(Alignment.TopEnd).padding(8.dp))
                }) else null,
            )
        }
    }
}

/** The two-column shortcuts the web home starts with: a small cover and a name on a raised tile. */
@Composable
private fun ShortcutGrid(navController: NavController, items: List<HomeItem>, playingContext: State<String>) {
    Column(
        modifier = Modifier.padding(horizontal = Immersive.Gutter).padding(bottom = 22.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Large windows: more tiles per row (two on phones).
        val cols = com.music.spotui.ui.components.windowSize().columns(2, 280.dp)
        items.chunked(cols).forEach { rowItems ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                rowItems.forEach { item ->
                    val playing = item.isPlayingIn(playingContext.value)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .weight(1f)
                            .height(56.dp)
                            .pressHint { prefetchHomeItem(item) }
                            .immersiveClickable(onClick = { onHomeItemClick(navController, item) })
                            .clip(Immersive.CoverMedium)
                            .background(Immersive.Raised),
                    ) {
                        ImmersiveCover(
                            url = item.imageUrl,
                            modifier = Modifier.size(56.dp),
                            shape = Immersive.CoverMedium,
                            border = false,
                        )
                        Text(
                            text = item.name,
                            style = Immersive.Subhead.copy(
                                color = if (playing) Immersive.Accent else Immersive.Text,
                                fontWeight = FontWeight.SemiBold,
                                lineHeight = 17.sp,
                            ),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 10.dp).weight(1f),
                        )
                    }
                }
                repeat(cols - rowItems.size) { Box(Modifier.weight(1f)) }
            }
        }
    }
}
