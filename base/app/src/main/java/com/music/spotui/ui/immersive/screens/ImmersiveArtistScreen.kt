/*
 * Adapted from BitChord (https://github.com/kushagrasinghx/BitChord), GPL-3.0-or-later,
 * app/src/main/java/com/music/bitchord/ui/screens/DetailScreen.kt at edc1e6f. Modified for Myspoti, 2026-10.
 */
package com.music.spotui.ui.immersive.screens

import android.annotation.SuppressLint
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import app.myspoti.engine.R
import com.music.spotui.data.api.Response
import com.music.spotui.data.entity.AlbumsModel
import com.music.spotui.data.entity.ArtistOverviewModel
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.di.Graph
import com.music.spotui.di.SongPlayer
import com.music.spotui.ui.immersive.Immersive
import com.music.spotui.ui.immersive.components.ImmersiveCircleButton
import com.music.spotui.ui.immersive.components.ImmersiveCover
import com.music.spotui.ui.immersive.components.ImmersiveMessageState
import com.music.spotui.ui.immersive.components.ImmersiveShelf
import com.music.spotui.ui.immersive.components.ImmersiveShelfCard
import com.music.spotui.ui.immersive.components.SectionHeader
import com.music.spotui.ui.immersive.components.immersiveDetailSkeleton
import com.music.spotui.ui.immersive.shell.ImmersiveBarAction
import com.music.spotui.ui.immersive.shell.ImmersiveTopBar
import com.music.spotui.ui.immersive.shell.immersiveHazeSource
import com.music.spotui.ui.immersive.shell.immersiveChromeScroll
import com.music.spotui.ui.immersive.shell.immersiveTopBarHeight
import com.music.spotui.ui.navigation.Routes
import com.music.spotui.ui.navigation.albumRoute
import com.music.spotui.ui.navigation.artistRoute
import com.music.spotui.ui.navigation.likedByArtistRoute
import com.music.spotui.ui.viewmodel.ArtistViewModel

private const val MAX_ARTIST_SONGS = 10
private const val SONGS_PER_COLUMN = 4
private const val ARTIST_ROW_MAX_ITEMS = 5

/** The artist photo, very slightly taller than it is wide. */
private const val ARTIST_PHOTO_RATIO = 0.95f

/**
 * How far past the foot of the artist photo the name is allowed to hang: dropped clear of the
 * picture it sits on the faded page instead, which has nothing in it to compete.
 */
private val HeaderDrop = 44.dp

/** How far the artist name and everything under it sit above the usual [HeaderDrop]. */
private val ArtistHeaderLift = 64.dp

/** The artist action row: a large Play flanked by two smaller circles. */
private val PlayButton = 70.dp
private const val PLAY_ICON_SCALE = 0.56f
private val SideButton = 52.dp
private val ActionGap = 24.dp

/** A column of top songs is this share of the row, so the next one shows beside it. */
private const val TRACK_COLUMN_FRACTION = 0.88f
private val TrackColumnMaxWidth = 400.dp

private fun grouped(n: Long): String = "%,d".format(n)

/** "2024" or "2024-05-17" -> "2024". */
private fun releaseYear(album: AlbumsModel): String = album.year

/** "2024 · Single": what a release card says under its name. */
@Composable
internal fun releaseDetail(album: AlbumsModel): String {
    val kind = when (album.type.uppercase()) {
        "SINGLE" -> stringResource(R.string.album_type_single)
        "EP" -> stringResource(R.string.album_type_ep)
        "COMPILATION" -> stringResource(R.string.album_type_compilation)
        "" -> ""
        else -> stringResource(R.string.album_type_album)
    }
    return listOf(releaseYear(album), kind).filter { it.isNotBlank() }.joinToString(" · ")
}

/**
 * Artist page of the Immersive look: the photo full bleed up under the bar, dissolving into the
 * page with the name over it, then Shuffle, Play and Follow, the latest release, the top songs
 * paged four at a time, the discography shelves, who the artist appears with, similar artists and
 * the biography. Same [ArtistViewModel] and actions as the Classic page.
 */
@Composable
fun ImmersiveArtistScreen(navController: NavController, artistName: String, artistId: String = "") {
    val artistViewModel: ArtistViewModel = viewModel()
    val overview by artistViewModel.overview.collectAsState()

    LaunchedEffect(artistName, artistId) { artistViewModel.loadArtistOverview(artistName, artistId) }

    BoxWithConstraints(Modifier.fillMaxSize().background(Immersive.Page)) {
        // Measured rather than read off the window, and capped against the window's own height so
        // a landscape window still shows the track list under the photo.
        val artHeight = (maxWidth / ARTIST_PHOTO_RATIO).coerceAtMost(maxHeight * 0.6f)
        when (val state = overview) {
            is Response.Loading -> ArtistLoading(navController, artistName, artHeight)
            is Response.Error -> Box(Modifier.fillMaxSize()) {
                ImmersiveMessageState(
                    message = stringResource(R.string.page_load_failed),
                    actionLabel = stringResource(R.string.retry_button),
                    onAction = { artistViewModel.loadArtistOverview(artistName, artistId) },
                    modifier = Modifier.padding(top = immersiveTopBarHeight()),
                )
                ImmersiveTopBar(title = "", collapse = { 0f }, onBack = { navController.navigateUp() })
            }
            is Response.Success -> ArtistOverviewContent(navController, artistViewModel, state.data, artistName, artHeight)
        }
    }
}

/** The name is all the row that was tapped knew; the rest stands as shimmer. */
@Composable
private fun ArtistLoading(navController: NavController, artistName: String, artHeight: Dp) {
    val listState = rememberLazyListState()
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            userScrollEnabled = false,
            contentPadding = PaddingValues(bottom = Immersive.BottomChrome),
            modifier = Modifier.fillMaxSize().immersiveHazeSource(),
        ) {
            item(key = "header") { ArtistNameHeader(name = artistName, artHeight = artHeight) }
            immersiveDetailSkeleton(isArtist = true)
        }
        ImmersiveTopBar(title = "", collapse = { 0f }, onBack = { navController.navigateUp() })
    }
}

/** The name, hung below where the photo ends, with [extra] (verified, listeners) under it. */
@Composable
private fun ArtistNameHeader(
    name: String,
    artHeight: Dp,
    extra: (@Composable () -> Unit)? = null,
) {
    Box(Modifier.fillMaxWidth()) {
        Spacer(Modifier.fillMaxWidth().height(artHeight + HeaderDrop - ArtistHeaderLift))
        Column(
            modifier = Modifier.align(Alignment.BottomCenter),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = name,
                style = Immersive.LargeTitle.copy(fontSize = 44.sp, lineHeight = 48.sp, fontWeight = FontWeight.ExtraBold),
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = Immersive.Gutter + 8.dp, end = Immersive.Gutter + 8.dp, top = 14.dp, bottom = 7.dp),
            )
            extra?.invoke()
        }
    }
}

@SuppressLint("DefaultLocale")
@Composable
private fun ArtistOverviewContent(
    navController: NavController,
    artistViewModel: ArtistViewModel,
    overview: ArtistOverviewModel,
    artistName: String,
    artHeight: Dp,
) {
    val context = LocalContext.current
    val tracks = overview.topTracks
    val displayName = overview.name.ifBlank { artistName }

    var menuSong by remember { mutableStateOf<SongsModel?>(null) }
    menuSong?.let { sel ->
        com.music.spotui.ui.immersive.sheets.ImmersiveSongOptionsSheet(
            song = sel,
            navController = navController,
            context = context,
            onDismiss = { menuSong = null },
        )
    }
    var showArtistMenu by remember { mutableStateOf(false) }
    if (showArtistMenu) com.music.spotui.ui.components.ArtistOptionsSheet(
        artistId = overview.id,
        artistName = displayName,
        onDismiss = { showArtistMenu = false },
    )

    // Follow state straight from the user's library.
    var following by remember(overview.id) { mutableStateOf(false) }
    LaunchedEffect(overview.id) {
        val uris = tracks.map { "spotify:track:${it.song.spotifyTrackId}" } + "spotify:artist:${overview.id}"
        val res = SongPlayer.libraryContains(uris)
        following = "spotify:artist:${overview.id}" in res
    }
    // The user's liked songs by this artist: from the session cache, else loaded once in the background.
    var likedByArtist by remember(overview.id) { mutableStateOf<List<SongsModel>>(emptyList()) }
    LaunchedEffect(overview.id) {
        // Loaded once per session, shared by every artist page, after this page is shown.
        val all = com.music.spotui.data.api.Api.LikedCache.tracks
            ?: run { kotlinx.coroutines.delay(800); com.music.spotui.data.api.Api.LikedCache.loadOnce().await() }
            ?: return@LaunchedEffect
        likedByArtist = all.filter { s ->
            if (s.artistIds.isNotEmpty()) overview.id in s.artistIds
            else displayName.isNotBlank() && s.singer.split(", ").any { it.equals(displayName, ignoreCase = true) }
        }
    }
    val np by SongPlayer.nowPlaying
    val isThisArtist = np.contextUri == "spotify:artist:${overview.id}"
    val playingNow = artistViewModel.currentSongPlayingState.value
    val currentSongId = artistViewModel.currentSongId.value
    val isFollowing = SongPlayer.library["spotify:artist:${overview.id}"] ?: following
    val listState = rememberLazyListState()

    fun playTrackAt(index: Int) {
        if (index !in tracks.indices) return
        val songs = tracks.map { it.song }
        artistViewModel.updateQueue(songs)
        val s = songs[index]
        SongPlayer.playSong(s.url, context)
        artistViewModel.updateSongState(s.coverUri, s.title, s.singer, true, s.id, index)
    }

    // The bar's small title takes over once the name has scrolled up under it.
    val barPx = with(LocalDensity.current) { immersiveTopBarHeight().toPx() }
    val fadePx = with(LocalDensity.current) { 40.dp.toPx() }
    val collapse = remember(listState, barPx, fadePx) {
        val fraction: () -> Float = {
            if (listState.firstVisibleItemIndex > 0) 1f
            else {
                val header = listState.layoutInfo.visibleItemsInfo.firstOrNull()?.size?.toFloat() ?: 0f
                if (header <= 0f) 0f
                else ((listState.firstVisibleItemScrollOffset - (header - barPx - fadePx)) / fadePx).coerceIn(0f, 1f)
            }
        }
        fraction
    }

    Box(Modifier.fillMaxSize()) {
        // The photo is drawn behind the list rather than in it, so both agree on its height
        // (artHeight) without being able to ask each other.
        ArtistBackdrop(
            imageUrl = overview.headerImage.ifBlank { overview.avatarImage },
            artHeight = artHeight,
            listState = listState,
            modifier = Modifier.matchParentSize(),
        )
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(bottom = Immersive.BottomChrome),
            modifier = Modifier.fillMaxSize().immersiveHazeSource().immersiveChromeScroll(),
        ) {
            item(key = "header") {
                ArtistNameHeader(name = displayName, artHeight = artHeight) {
                    if (overview.verified) Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Verified, contentDescription = null, tint = Immersive.Accent, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.verified_artist), style = Immersive.Subhead.copy(color = Immersive.Text, fontWeight = FontWeight.SemiBold))
                    }
                    overview.monthlyListeners?.let {
                        val compact = android.icu.text.CompactDecimalFormat
                            .getInstance(java.util.Locale.getDefault(), android.icu.text.CompactDecimalFormat.CompactStyle.SHORT)
                            .format(it)
                        Text(
                            text = stringResource(R.string.monthly_listeners_text, compact),
                            style = Immersive.Subhead,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }

            // "Your #3 artist of the past 4 weeks", from the account's Spotify top artists.
            item(key = "top-badge") { com.music.spotui.ui.screens.ArtistTopBadge(overview.id) }

            // Shuffle, Play, Follow: a large Play flanked by two smaller circles.
            item(key = "actions") {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = Immersive.Gutter + 8.dp).padding(top = 14.dp, bottom = 22.dp),
                    horizontalArrangement = Arrangement.spacedBy(ActionGap, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ImmersiveCircleButton(
                        icon = Icons.Rounded.Shuffle,
                        contentDescription = stringResource(R.string.immersive_shell_shuffle),
                        onClick = {
                            if (tracks.isNotEmpty()) Graph.currentSongState.startShuffled(tracks.map { it.song })
                        },
                        tint = if (isThisArtist && Graph.currentSongState.shuffle.value) Immersive.Accent else Immersive.Text,
                        size = SideButton,
                    )
                    ArtistPlayCircle(
                        playing = isThisArtist && playingNow,
                        onClick = {
                            when {
                                isThisArtist && playingNow -> SongPlayer.pause()
                                isThisArtist -> SongPlayer.play()
                                else -> playTrackAt(0)
                            }
                        },
                        size = PlayButton,
                        iconScale = PLAY_ICON_SCALE,
                        containerColor = Color.White,
                    )
                    ImmersiveCircleButton(
                        icon = if (isFollowing) Icons.Rounded.Check else Icons.Rounded.PersonAdd,
                        contentDescription = stringResource(if (isFollowing) R.string.immersive_detail_unfollow else R.string.immersive_detail_follow),
                        onClick = {
                            val on = !(SongPlayer.library["spotify:artist:${overview.id}"] ?: following)
                            following = on
                            com.music.spotui.di.Haptics.toggle(on)
                            SongPlayer.setSaved("spotify:artist:${overview.id}", on)
                        },
                        tint = if (isFollowing) Immersive.Accent else Immersive.Text,
                        size = SideButton,
                    )
                }
            }

            // Liked songs by this artist.
            if (likedByArtist.isNotEmpty()) item(key = "liked-by-artist") {
                ArtistLikedRow(
                    title = stringResource(R.string.artist_liked_songs),
                    subtitle = pluralStringResource(R.plurals.artist_liked_songs_count, likedByArtist.size, likedByArtist.size, displayName),
                    onClick = { navController.navigate(likedByArtistRoute(overview.id, displayName)) },
                )
            }

            // The latest release, pulled out as a card of its own.
            overview.popularReleases.maxByOrNull { it.time }?.let { latest ->
                item(key = "latest-release") {
                    ArtistTopReleaseCard(
                        album = latest,
                        label = stringResource(R.string.latest_release_title),
                        detail = releaseDetail(latest),
                        onClick = { navController.navigate(albumRoute(latest.name, latest.artists.ifBlank { displayName }, latest.spotifyId)) },
                    )
                }
            }

            // Popular tracks: they page sideways four at a time rather than bury the shelves.
            if (tracks.isNotEmpty()) item(key = "top-songs") {
                SectionHeader(stringResource(R.string.artist_popular_tracks))
                BoxWithConstraints {
                    val columnWidth = minOf(maxWidth * TRACK_COLUMN_FRACTION, TrackColumnMaxWidth)
                    val topSongsState = rememberLazyListState()
                    LazyRow(
                        state = topSongsState,
                        contentPadding = PaddingValues(horizontal = Immersive.Gutter),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        flingBehavior = rememberSnapFlingBehavior(lazyListState = topSongsState),
                    ) {
                        items(tracks.take(MAX_ARTIST_SONGS).withIndex().chunked(SONGS_PER_COLUMN)) { column ->
                            Column(Modifier.width(columnWidth)) {
                                column.forEach { (index, item) ->
                                    ArtistCompactSongRow(
                                        song = item.song,
                                        subtitle = item.playcount?.let { grouped(it) } ?: item.song.singer,
                                        current = item.song.id == currentSongId,
                                        playing = item.song.id == currentSongId && playingNow,
                                        onClick = { playTrackAt(index) },
                                        onMore = { menuSong = item.song },
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Discography: albums, then singles and EPs; a release with no kind goes under "Popular releases".
            val releases = overview.popularReleases
            val albums = releases.filter { it.type.lowercase() == "album" || it.type.lowercase() == "compilation" }
            val singles = releases.filter { it.type.lowercase() == "single" || it.type.lowercase() == "ep" }
            val untyped = releases.filter { it.type.lowercase() !in setOf("album", "compilation", "single", "ep") }
            val openReleases = {
                navController.navigate("${Routes.ArtistReleases.route}/${android.net.Uri.encode(artistName)}?id=${android.net.Uri.encode(overview.id)}")
            }
            fun openAlbum(album: AlbumsModel) {
                navController.navigate(albumRoute(album.name, album.artists.ifBlank { displayName }, album.spotifyId))
            }
            releaseShelf(R.string.artist_releases_albums, albums, "albums", ::openAlbum, openReleases)
            releaseShelf(R.string.artist_releases_singles, singles, "singles", ::openAlbum, openReleases)
            releaseShelf(R.string.popular_releases_section_title, untyped, "untyped", ::openAlbum, openReleases)

            if (overview.appearsOn.isNotEmpty()) item(key = "appears-on") {
                ImmersiveShelf(
                    title = stringResource(R.string.appears_on_section_title),
                    onSeeAll = if (overview.appearsOn.size > ARTIST_ROW_MAX_ITEMS) openReleases else null,
                ) {
                    items(overview.appearsOn.take(ARTIST_ROW_MAX_ITEMS)) { album ->
                        ImmersiveShelfCard(
                            title = album.name,
                            subtitle = album.artists,
                            imageUrl = album.coverUri,
                            onClick = { navController.navigate(albumRoute(album.name, album.artists, album.spotifyId)) },
                        )
                    }
                }
            }

            if (overview.relatedArtists.isNotEmpty()) item(key = "related") {
                ImmersiveShelf(title = stringResource(R.string.fans_also_like_section_title)) {
                    items(overview.relatedArtists) { artist ->
                        ImmersiveShelfCard(
                            title = artist.name,
                            subtitle = stringResource(R.string.artist_subtitle),
                            imageUrl = artist.coverUri,
                            round = true,
                            onClick = { navController.navigate(artistRoute(artist.name, artist.id)) },
                        )
                    }
                }
            }

            // About closes the page, after the shelves.
            if (!overview.biography.isNullOrBlank()) item(key = "about") {
                SectionHeader(stringResource(R.string.creator_biography_section_title))
                Column(Modifier.padding(horizontal = Immersive.Gutter)) {
                    if (overview.avatarImage.isNotBlank()) {
                        ImmersiveCover(
                            url = overview.avatarImage,
                            modifier = Modifier.fillMaxWidth().height(200.dp),
                            shape = Immersive.Card,
                        )
                        Spacer(Modifier.height(12.dp))
                    }
                    ImmersiveAboutText(overview.biography.orEmpty())
                }
            }
        }

        ImmersiveTopBar(
            title = displayName,
            collapse = collapse,
            onBack = { navController.navigateUp() },
            actions = {
                ImmersiveBarAction(
                    icon = Icons.Rounded.MoreHoriz,
                    contentDescription = stringResource(R.string.content_description_context_menu),
                    onClick = { showArtistMenu = true },
                )
            },
        )
    }
}

/** A shelf of [releases] (the first few; "See all" opens the releases page), if there are any. */
private fun androidx.compose.foundation.lazy.LazyListScope.releaseShelf(
    titleRes: Int,
    releases: List<AlbumsModel>,
    key: String,
    onOpen: (AlbumsModel) -> Unit,
    onSeeAll: () -> Unit,
) {
    if (releases.isEmpty()) return
    item(key = "releases:$key") {
        ImmersiveShelf(
            title = stringResource(titleRes),
            onSeeAll = if (releases.size > ARTIST_ROW_MAX_ITEMS) onSeeAll else null,
        ) {
            items(releases.take(ARTIST_ROW_MAX_ITEMS)) { album ->
                ImmersiveShelfCard(
                    title = album.name,
                    subtitle = releaseDetail(album),
                    imageUrl = album.coverUri,
                    onClick = { onOpen(album) },
                )
            }
        }
    }
}
