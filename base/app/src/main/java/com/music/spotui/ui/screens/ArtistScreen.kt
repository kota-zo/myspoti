package com.music.spotui.ui.screens

import com.music.spotui.ui.components.widePressable
import com.music.spotui.ui.components.wideClickable
import com.music.spotui.ui.theme.tone
import com.music.spotui.ui.theme.AppText
import com.music.spotui.ui.theme.AppAccent
import com.music.spotui.ui.theme.AppMuted

import com.music.spotui.ui.components.SkeletonKind
import com.music.spotui.ui.components.pressable
import androidx.compose.ui.res.stringResource

import androidx.compose.material.icons.filled.Check
import kotlinx.coroutines.flow.first
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MoreVert
import android.annotation.SuppressLint
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.music.spotui.ui.components.SongOptionsSheet
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.CrossFade
import com.bumptech.glide.integration.compose.GlideImage
import com.bumptech.glide.integration.compose.placeholder
import app.myspoti.engine.R
import com.music.spotui.data.api.Response
import com.music.spotui.data.entity.AlbumsModel
import com.music.spotui.data.entity.ArtistOverviewModel
import com.music.spotui.data.entity.ArtistTrackUi
import com.music.spotui.data.entity.ArtistsModel
import com.music.spotui.data.preferences.addLikedSongId
import com.music.spotui.data.preferences.isSongLiked
import com.music.spotui.data.preferences.removeLikedSongId
import com.music.spotui.di.SongPlayer
import com.music.spotui.ui.components.Loader
import com.music.spotui.ui.navigation.Routes
import com.music.spotui.ui.navigation.albumRoute
import com.music.spotui.ui.navigation.artistRoute
import com.music.spotui.ui.theme.AppBackground
import com.music.spotui.ui.theme.AppPalette
import com.music.spotui.ui.viewmodel.ArtistViewModel

private val SpotifyGreen: Color get() = com.music.spotui.ui.theme.AppAccent

private fun grouped(n: Long): String =
    "%,d".format(n)

@Composable
fun ArtistScreen(navController: NavController, artistName: String, artistId: String = "") {
    val artistViewModel: ArtistViewModel = viewModel()
    val overview by artistViewModel.overview.collectAsState()

    LaunchedEffect(artistName, artistId) { artistViewModel.loadArtistOverview(artistName, artistId) }

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(AppBackground.toArgb()))
    ) {
        when (val state = overview) {
            is Response.Loading -> Loader(SkeletonKind.Header)
            is Response.Error -> androidx.compose.foundation.layout.Column(Modifier.fillMaxSize().statusBarsPadding()) {
                androidx.compose.material3.Icon(
                    androidx.compose.material.icons.Icons.AutoMirrored.Filled.ArrowBack,
                    stringResource(R.string.accessibility_navigate_back), tint = AppText,
                    modifier = Modifier.pressable(0.96f) { navController.navigateUp() }.padding(16.dp).size(26.dp),
                )
                com.music.spotui.ui.components.ErrorRetry(stringResource(R.string.page_load_failed)) {
                    artistViewModel.loadArtistOverview(artistName, artistId)
                }
            }
            is Response.Success -> ArtistOverviewContent(
                navController, artistViewModel, state.data, artistName,
            )
        }
    }
}

@SuppressLint("DefaultLocale")
@OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun ArtistOverviewContent(
    navController: NavController,
    artistViewModel: ArtistViewModel,
    overview: ArtistOverviewModel,
    artistName: String,
) {
    val context = LocalContext.current
    val tracks = overview.topTracks
    val displayName = overview.name.ifBlank { artistName }

    var menuSong by remember { mutableStateOf<com.music.spotui.data.entity.SongsModel?>(null) }
    menuSong?.let { sel ->
        SongOptionsSheet(
            song = sel,
            navController = navController,
            context = context,
            onDismiss = { menuSong = null },
        )
    }

    // B27: the ⋮ of the action row below used to do nothing (no clickable at all); it now
    // opens the artist's own menu.
    var showArtistMenu by remember { mutableStateOf(false) }
    if (showArtistMenu) com.music.spotui.ui.components.ArtistOptionsSheet(
        artistId = overview.id,
        artistName = displayName,
        artistImage = overview.avatarImage.ifBlank { overview.headerImage },
        onDismiss = { showArtistMenu = false },
    )

    // Follow + liked tracks straight from the user's Spotify library.
    var following by remember(overview.id) { mutableStateOf(false) }
    var saved by remember(overview.id) { mutableStateOf<Set<String>>(emptySet()) }
    LaunchedEffect(overview.id) {
        val uris = tracks.map { "spotify:track:${it.song.spotifyTrackId}" } + "spotify:artist:${overview.id}"
        val res = SongPlayer.libraryContains(uris)
        saved = res
        following = "spotify:artist:${overview.id}" in res
    }
    var showAllPopular by remember { mutableStateOf(false) }
    // The user's liked songs by this artist: from the session cache, else loaded once in the background.
    var likedByArtist by remember(overview.id) { mutableStateOf<List<com.music.spotui.data.entity.SongsModel>>(emptyList()) }
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
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()

    fun playTrackAt(index: Int) {
        if (index !in tracks.indices) return
        val songs = tracks.map { it.song }
        artistViewModel.updateQueue(songs)
        val s = songs[index]
        SongPlayer.playSong(s.url, context, songs = songs)
        artistViewModel.updateSongState(s.coverUri, s.title, s.singer, true, s.id, index)
    }

    Box(modifier = Modifier.fillMaxSize()) {
    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(bottom = com.music.spotui.ui.components.bottomBarsSpace()),
        modifier = Modifier
            .fillMaxSize()
            .background(Color(AppBackground.toArgb()))
    ) {
        // ── Header: big artist image with scrim + name ──
        item {
            Box(modifier = Modifier
                .fillMaxWidth()
                .height(340.dp)
            ) {
                GlideImage(
                    transition = CrossFade,
                    modifier = Modifier.fillMaxSize(),
                    model = overview.headerImage.ifBlank { overview.avatarImage },
                    contentScale = ContentScale.Crop,
                    failure = placeholder(R.drawable.placeholder),
                    loading = placeholder(R.drawable.placeholder),
                    contentDescription = "",
                )
                Box(modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Black.copy(alpha = 0.35f),
                                Color.Transparent,
                                Color(AppBackground.toArgb()),
                            ),
                        )
                    )
                )
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .statusBarsPadding()
                        .padding(12.dp)
                        .wideClickable { navController.navigateUp() }
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.35f)),
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.accessibility_navigate_back), tint = Color.White)
                }
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(16.dp, 0.dp, 16.dp, 12.dp),
                ) {
                    com.music.spotui.ui.components.FitText(
                        text = displayName,
                        fontSize = 44.sp,
                        minSize = 24.sp,
                        fontWeight = FontWeight.ExtraBold,
                        maxLines = 2,
                    )
                    if (overview.verified) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.size(18.dp).clip(CircleShape).background(SpotifyGreen),
                        ) {
                            Icon(Icons.Default.Check, contentDescription = null, tint = Color.Black, modifier = Modifier.size(13.dp))
                        }
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.verified_artist), color = AppText, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // ── Monthly listeners (compact, like "4,3 M auditeurs et auditrices par mois") ──
        item {
            overview.monthlyListeners?.let {
                val compact = android.icu.text.CompactDecimalFormat
                    .getInstance(java.util.Locale.getDefault(), android.icu.text.CompactDecimalFormat.CompactStyle.SHORT)
                    .format(it)
                Text(
                    text = stringResource(R.string.monthly_listeners_text, compact),
                    color = AppText.copy(alpha = 0.7f),
                    fontSize = 14.sp,
                    modifier = Modifier.padding(16.dp, 12.dp, 16.dp, 0.dp),
                )
            }
        }

        // ── "Your #3 artist of the past 4 weeks", from the account's Spotify top artists ──
        item { ArtistTopBadge(overview.id) }

        // ── Action row: Follow + shuffle + play ──
        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp, 16.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    overview.popularReleases.firstOrNull()?.let { latest ->
                        GlideImage(
                            transition = CrossFade,
                            model = latest.coverUri,
                            contentDescription = latest.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(width = 36.dp, height = 48.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .clickable { navController.navigate(albumRoute(latest.name, displayName, latest.spotifyId)) },
                        )
                        Spacer(Modifier.width(16.dp))
                    }
                    Box(modifier = Modifier.pressable(0.96f) {
                            val on = !(SongPlayer.library["spotify:artist:${overview.id}"] ?: following)
                            following = on
                            com.music.spotui.di.Haptics.toggle(on)
                            SongPlayer.setSaved("spotify:artist:${overview.id}", on)
                        }
                        .clip(RoundedCornerShape(20.dp))
                        .border(1.dp, Color.Gray, RoundedCornerShape(20.dp))
                        
                        .padding(16.dp, 7.dp),
                    ) {
                        Text(
                            text = stringResource(if (SongPlayer.library["spotify:artist:${overview.id}"] ?: following) R.string.curation_button_following_state_text else R.string.curation_button_follow_state_text),
                            color = AppText,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Spacer(Modifier.width(16.dp))
                    // B27: this ⋮ had neither `clickable` nor `onClick` — the artist menu is
                    // opened with the same pressable feedback as the row's other buttons.
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = stringResource(R.string.content_description_context_menu),
                        tint = AppText.copy(alpha = 0.7f),
                        modifier = Modifier
                            .pressable(0.96f) { showArtistMenu = true }
                            .size(24.dp),
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_player_shuffle),
                        contentDescription = stringResource(R.string.play_mode_item_shuffle_title),
                        tint = if (isThisArtist && com.music.spotui.di.Graph.currentSongState.shuffle.value) SpotifyGreen else AppText,
                        modifier = Modifier.pressable(0.96f) {
                                if (tracks.isNotEmpty()) {
                                    com.music.spotui.di.Graph.currentSongState.startShuffled(tracks.map { it.song })
                                }
                            }
                            .size(26.dp)
                            ,
                    )
                    Spacer(Modifier.width(20.dp))
                    Box(modifier = Modifier.pressable(0.96f) {
                            when {
                                isThisArtist && playingNow -> SongPlayer.pause()
                                isThisArtist -> SongPlayer.play()
                                else -> playTrackAt(0)
                            }
                        }
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(SpotifyGreen)
                        ,
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painter = painterResource(id = if (isThisArtist && playingNow) R.drawable.ic_playing else R.drawable.play_svgrepo_com),
                            contentDescription = if (isThisArtist && playingNow) stringResource(R.string.content_description_pause_button) else stringResource(R.string.content_description_play_button),
                            tint = Color.Black,
                            modifier = Modifier.size(26.dp),
                        )
                    }
                }
            }
        }

        // ── Liked songs by this artist ──
        if (likedByArtist.isNotEmpty()) {
            item(key = "liked-by-artist") {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                        .clickable { navController.navigate(com.music.spotui.ui.navigation.likedByArtistRoute(overview.id, displayName)) }
                        .padding(16.dp, 8.dp),
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.size(52.dp).clip(RoundedCornerShape(4.dp))
                            .background(Brush.linearGradient(listOf(Color(0xFF450AF5), Color(0xFF8E8EE5), Color(0xFFC4EFD9)))),
                    ) {
                        androidx.compose.material3.Icon(Icons.Default.Favorite, contentDescription = null, tint = AppText, modifier = Modifier.size(22.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.artist_liked_songs), color = AppText, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Text(
                            androidx.compose.ui.res.pluralStringResource(R.plurals.artist_liked_songs_count, likedByArtist.size, likedByArtist.size, displayName),
                            color = AppText.copy(alpha = 0.7f), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }

        // ── Popular tracks ──
        if (tracks.isNotEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.artist_popular_tracks),
                    color = AppText,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(16.dp, 8.dp, 16.dp, 4.dp),
                )
            }
            val popular = tracks.take(if (showAllPopular) 10 else 5)
            val popularKeys = rowKeys("pop", popular) { it.song.spotifyTrackId.ifBlank { it.song.title } }
            itemsIndexed(popular, key = { i, _ -> popularKeys[i] }) { index, item ->
                com.music.spotui.ui.components.SwipeToQueue(item.song) {
                PopularTrackRow(
                    item, index, artistViewModel,
                    saved = SongPlayer.library["spotify:track:${item.song.spotifyTrackId}"] ?: ("spotify:track:${item.song.spotifyTrackId}" in saved),
                    onToggleSaved = { on ->
                        SongPlayer.setTrackSaved(item.song.spotifyTrackId, on)
                    },
                    onMore = { menuSong = item.song },
                    onPlay = { playTrackAt(index) },
                )
                }
            }
            if (tracks.size > 5) item {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .border(1.dp, AppText.copy(alpha = 0.5f), RoundedCornerShape(50))
                            .clickable { showAllPopular = !showAllPopular }
                            .padding(horizontal = 20.dp, vertical = 8.dp),
                    ) {
                        Text(if (showAllPopular) stringResource(R.string.profile_bio_show_less) else stringResource(R.string.audiobook_series_see_more_button), color = AppText, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // ── Popular releases (discography) — vertical list, first few + "Show all" ──
        if (overview.popularReleases.isNotEmpty()) {
            item { SectionHeader(stringResource(R.string.popular_releases_section_title)) }
            val popularReleases = overview.popularReleases.take(4)
            val popularReleaseKeys = rowKeys("release", popularReleases) { it.spotifyId.ifBlank { it.name } }
            itemsIndexed(popularReleases, key = { i, _ -> popularReleaseKeys[i] }) { _, album ->
                ReleaseRow(album) {
                    navController.navigate(albumRoute(album.name, album.artists.ifBlank { displayName }, album.spotifyId))
                }
            }
            if (overview.popularReleases.size > 4) {
                item {
                    ShowAllButton {
                        navController.navigate("${Routes.ArtistReleases.route}/${android.net.Uri.encode(artistName)}?id=${android.net.Uri.encode(overview.id)}")
                    }
                }
            }
        }

        // ── About ──
        if (!overview.biography.isNullOrBlank()) {
            item { SectionHeader(stringResource(R.string.creator_biography_section_title)) }
            item {
                Box(modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp, 4.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(AppText.copy(alpha = 0.06f))
                ) {
                    Column {
                        if (overview.avatarImage.isNotBlank()) {
                            GlideImage(
                                transition = CrossFade,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(200.dp),
                                model = overview.avatarImage,
                                contentScale = ContentScale.Crop,
                                failure = placeholder(R.drawable.placeholder),
                                contentDescription = "",
                            )
                        }
                        overview.monthlyListeners?.let {
                            Text(
                                text = stringResource(R.string.monthly_listeners_text, grouped(it)),
                                color = AppText,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(14.dp, 12.dp, 14.dp, 4.dp),
                            )
                        }
                        Text(
                            text = com.music.spotui.ui.components.rememberLinkedText(overview.biography.orEmpty(), AppAccent),
                            color = AppMuted,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(14.dp, 4.dp, 14.dp, 16.dp),
                        )
                    }
                }
            }
        }

        // ── Fans also like ──
        if (overview.relatedArtists.isNotEmpty()) {
            item { SectionHeader(stringResource(R.string.fans_also_like_section_title)) }
            item {
                LazyRow(
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp, 0.dp),
                ) {
                    val relatedKeys = rowKeys("related", overview.relatedArtists) { it.id.ifBlank { it.name } }
                    items(overview.relatedArtists.size, key = { relatedKeys[it] }) { i ->
                        RelatedArtistCard(overview.relatedArtists[i]) {
                            navController.navigate(artistRoute(overview.relatedArtists[i].name, overview.relatedArtists[i].id))
                        }
                    }
                }
            }
        }

        // ── Appears on ──
        if (overview.appearsOn.isNotEmpty()) {
            item { SectionHeader(stringResource(R.string.appears_on_section_title)) }
            item {
                LazyRow(
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp, 0.dp),
                ) {
                    val appearsOnKeys = rowKeys("appears", overview.appearsOn) { it.spotifyId.ifBlank { it.name } }
                    items(overview.appearsOn.size, key = { appearsOnKeys[it] }) { i ->
                        ReleaseCard(overview.appearsOn[i]) {
                            navController.navigate(albumRoute(overview.appearsOn[i].name, overview.appearsOn[i].artists, overview.appearsOn[i].spotifyId))
                        }
                    }
                }
            }
        }
    }
    // Title bar that fades in once the header has scrolled away (official app).
    val collapsed by remember { androidx.compose.runtime.derivedStateOf { listState.firstVisibleItemIndex > 0 } }
    androidx.compose.animation.AnimatedVisibility(
        visible = collapsed,
        enter = androidx.compose.animation.fadeIn(),
        exit = androidx.compose.animation.fadeOut(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .background(tone(0xFF2A2A2A))
                .statusBarsPadding()
                .height(56.dp)
                .padding(horizontal = 12.dp),
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.accessibility_navigate_back), tint = AppText,
                modifier = Modifier.wideClickable(horizontal = 12.dp, vertical = 15.dp) { navController.navigateUp() }.size(26.dp),
            )
            Text(
                displayName, color = AppText, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.weight(1f).padding(end = 26.dp),
            )
        }
    }
    }
}

@OptIn(ExperimentalGlideComposeApi::class, ExperimentalFoundationApi::class)
@Composable
private fun PopularTrackRow(
    item: ArtistTrackUi,
    index: Int,
    artistViewModel: ArtistViewModel,
    saved: Boolean,
    onToggleSaved: (Boolean) -> Unit,
    onMore: () -> Unit,
    onPlay: () -> Unit,
) {
    val song = item.song
    val titleColor =
        if (song.id == artistViewModel.currentSongId.value) SpotifyGreen else AppText

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onLongClick = onMore,
                onClick = onPlay,
            )
            .padding(16.dp, 6.dp),
    ) {
        Text(
            text = "${index + 1}",
            color = AppText.copy(alpha = 0.7f),
            fontSize = 15.sp,
            modifier = Modifier.width(24.dp),
        )
        GlideImage(
            transition = CrossFade,
            modifier = Modifier
                .padding(8.dp, 0.dp, 12.dp, 0.dp)
                .size(48.dp)
                .clip(RoundedCornerShape(4.dp)),
            model = song.coverUri,
            contentScale = ContentScale.Crop,
            failure = placeholder(R.drawable.placeholder),
            loading = placeholder(R.drawable.placeholder),
            contentDescription = "",
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(text = song.title, color = titleColor, fontSize = 16.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (song.explicit) { ExplicitBadge(); Spacer(Modifier.width(6.dp)) }
                Text(text = item.playcount?.let { grouped(it) } ?: song.singer, color = AppText.copy(alpha = 0.7f), fontSize = 13.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
        }
        if (saved) {
            LikeButton(liked = true, size = 22.dp) { onToggleSaved(false) }
            Spacer(Modifier.width(16.dp))
        }
        Icon(
            Icons.Default.MoreVert,
            contentDescription = stringResource(R.string.content_description_context_menu),
            tint = AppText.copy(alpha = 0.7f),
            modifier = Modifier.pressable(0.96f) { onMore() }
                .size(24.dp)
                ,
        )
    }
}

/**
 * A key per row of a lazy list, so a row keeps its place (and its state) when the list around it
 * changes: [prefix] and the entry's [id], with a counter on repeats. The prefix keeps the lists
 * of one page apart.
 */
private fun <T> rowKeys(prefix: String, list: List<T>, id: (T) -> String): List<String> {
    val seen = HashMap<String, Int>()
    return list.map { e ->
        val k = "$prefix:${id(e)}"
        val n = (seen[k] ?: 0) + 1
        seen[k] = n
        if (n == 1) k else "$k#$n"
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        color = AppText,
        fontSize = 20.sp,
        fontWeight = FontWeight.Bold,
        maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        modifier = Modifier.padding(16.dp, 20.dp, 16.dp, 8.dp),
    )
}

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun ReleaseCard(album: AlbumsModel, onClick: () -> Unit) {
    Column(
        modifier = Modifier.pressable(0.96f) { onClick() }
            .width(140.dp)
            .padding(4.dp)
            ,
    ) {
        GlideImage(
            transition = CrossFade,
            modifier = Modifier
                .size(132.dp)
                .clip(RoundedCornerShape(6.dp)),
            model = album.coverUri,
            contentScale = ContentScale.Crop,
            failure = placeholder(R.drawable.placeholder),
            loading = placeholder(R.drawable.placeholder),
            contentDescription = "",
        )
        Spacer(Modifier.height(6.dp))
        Text(text = album.name, color = AppText, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        Text(
            text = album.year.ifBlank { album.artists }.let { if (album.year.isNotBlank()) "${album.year} • Album" else it },
            color = AppMuted,
            fontSize = 11.sp,
            maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
    }
}

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun ReleaseRow(album: AlbumsModel, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.pressable(0.96f) { onClick() }
            .fillMaxWidth()
            .padding(16.dp, 6.dp)
            ,
    ) {
        GlideImage(
            transition = CrossFade,
            modifier = Modifier
                .size(88.dp)
                .clip(RoundedCornerShape(4.dp)),
            model = album.coverUri,
            contentScale = ContentScale.Crop,
            failure = placeholder(R.drawable.placeholder),
            loading = placeholder(R.drawable.placeholder),
            contentDescription = "",
        )
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = album.name, color = AppText, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            val kind = when (album.type.uppercase()) {
                "SINGLE" -> stringResource(R.string.album_type_single); "EP" -> stringResource(R.string.album_type_ep)
                "COMPILATION" -> stringResource(R.string.album_type_compilation); "" -> ""
                else -> stringResource(R.string.album_type_album)
            }
            val year = releaseYear(album)
            Text(
                text = listOf(year, kind).filter { it.isNotBlank() }.joinToString(" • "),
                color = AppMuted,
                fontSize = 13.sp,
                maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@Composable
private fun ShowAllButton(onClick: () -> Unit) {
    Box(modifier = Modifier.pressable(0.96f) { onClick() }
        .padding(16.dp, 10.dp, 16.dp, 4.dp)
        .clip(RoundedCornerShape(20.dp))
        .border(1.dp, Color.Gray, RoundedCornerShape(20.dp))
        
        .padding(20.dp, 8.dp),
    ) {
        Text(text = stringResource(R.string.composer_section_button_show_all), color = AppText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** Release-year helper: Spotify release dates come as "2024" or "2024-05-17". */
private fun releaseYear(album: AlbumsModel): String = album.year

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
fun ArtistReleasesScreen(navController: NavController, artistName: String, artistId: String = "") {
    val artistViewModel: ArtistViewModel = viewModel()
    val overview by artistViewModel.overview.collectAsState()

    LaunchedEffect(artistName, artistId) { artistViewModel.loadArtistOverview(artistName, artistId) }

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(AppBackground.toArgb()))
    ) {
        val data = (overview as? Response.Success)?.data
        val releases = data?.popularReleases ?: emptyList()
        val featuredOn = data?.appearsOn ?: emptyList()
        // Spotify's Releases view: filter chips + a "Latest release" spotlight,
        // then releases grouped by kind, newest first.
        // Spotify names the kinds in capitals ("ALBUM", "SINGLE"…): compared without case.
        val albums = releases.filter { it.type.lowercase() == "album" || it.type.lowercase() == "compilation" }
        val singles = releases.filter { it.type.lowercase() == "single" || it.type.lowercase() == "ep" }
        val untyped = releases.filter { it.type.lowercase() !in setOf("album", "compilation", "single", "ep") }
        val latest = releases.maxByOrNull { it.time }
        var filter by remember { mutableStateOf("") } // "" | "albums" | "singles" | "featured"

        fun openAlbum(album: AlbumsModel) {
            navController.navigate(albumRoute(album.name, album.artists.ifBlank { artistName }, album.spotifyId))
        }

        LazyColumn(
            contentPadding = PaddingValues(bottom = com.music.spotui.ui.components.bottomBarsSpace()),
            modifier = Modifier
                .fillMaxSize()
                .background(Color(AppBackground.toArgb()))
        ) {
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(16.dp, 12.dp, 16.dp, 12.dp),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "",
                        tint = AppText,
                        modifier = Modifier.widePressable(horizontal = 16.dp, vertical = 12.dp, pressedScale = 0.96f) { navController.navigateUp() }
                            .size(26.dp),
                    )
                    Spacer(Modifier.width(16.dp))
                    Text(
                        text = stringResource(R.string.releases_title),
                        color = AppText,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            item {
                Row(modifier = Modifier.padding(12.dp, 0.dp, 12.dp, 8.dp)) {
                    ReleaseFilterChip(stringResource(R.string.artist_releases_albums), filter == "albums") {
                        filter = if (filter == "albums") "" else "albums"
                    }
                    Spacer(Modifier.width(8.dp))
                    ReleaseFilterChip(stringResource(R.string.artist_releases_singles), filter == "singles") {
                        filter = if (filter == "singles") "" else "singles"
                    }
                    if (featuredOn.isNotEmpty()) {
                        Spacer(Modifier.width(8.dp))
                        ReleaseFilterChip("Featured on", filter == "featured") {
                            filter = if (filter == "featured") "" else "featured"
                        }
                    }
                }
            }
            if (overview is Response.Loading) {
                item { Loader() }
            }
            // One key per row across the whole list (the latest release is also among the albums).
            val albumKeys = rowKeys("album", albums) { it.spotifyId.ifBlank { it.name } }
            val singleKeys = rowKeys("single", singles) { it.spotifyId.ifBlank { it.name } }
            val featuredKeys = rowKeys("featured", featuredOn) { it.spotifyId.ifBlank { it.name } }
            val untypedKeys = rowKeys("untyped", untyped) { it.spotifyId.ifBlank { it.name } }
            when (filter) {
                "albums" -> items(albums.size, key = { albumKeys[it] }) { i -> ReleaseRow(albums[i]) { openAlbum(albums[i]) } }
                "singles" -> items(singles.size, key = { singleKeys[it] }) { i -> ReleaseRow(singles[i]) { openAlbum(singles[i]) } }
                "featured" -> items(featuredOn.size, key = { featuredKeys[it] }) { i -> ReleaseRow(featuredOn[i]) { openAlbum(featuredOn[i]) } }
                else -> {
                    latest?.let {
                        item { ReleaseSectionHeader(stringResource(R.string.latest_release_title)) }
                        item(key = "latest") { ReleaseRow(it) { openAlbum(it) } }
                    }
                    if (albums.isNotEmpty()) {
                        item { ReleaseSectionHeader(stringResource(R.string.artist_releases_albums)) }
                        items(albums.size, key = { albumKeys[it] }) { i -> ReleaseRow(albums[i]) { openAlbum(albums[i]) } }
                    }
                    if (singles.isNotEmpty()) {
                        item { ReleaseSectionHeader(stringResource(R.string.artist_releases_singles)) }
                        items(singles.size, key = { singleKeys[it] }) { i -> ReleaseRow(singles[i]) { openAlbum(singles[i]) } }
                    }
                    if (untyped.isNotEmpty()) {
                        if (albums.isEmpty() && singles.isEmpty()) {
                            item { ReleaseSectionHeader(stringResource(R.string.popular_releases_section_title)) }
                        }
                        items(untyped.size, key = { untypedKeys[it] }) { i -> ReleaseRow(untyped[i]) { openAlbum(untyped[i]) } }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReleaseSectionHeader(title: String) {
    Text(
        text = title,
        color = AppText,
        fontSize = 22.sp,
        fontWeight = FontWeight.Bold,
        maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        modifier = Modifier.padding(16.dp, 18.dp, 16.dp, 8.dp),
    )
}

@Composable
private fun ReleaseFilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    // Same pill as the Home chips.
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.pressable(0.96f) { onClick() }
            .clip(RoundedCornerShape(50))
            .background(if (selected) com.music.spotui.ui.theme.AppAccent else tone(0xFF2A2A2A))
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(
            text = label,
            color = if (selected) Color.Black else AppText,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
    }
}

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun RelatedArtistCard(artist: ArtistsModel, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.pressable(0.96f) { onClick() }
            .width(130.dp)
            .padding(4.dp)
            ,
    ) {
        GlideImage(
            transition = CrossFade,
            modifier = Modifier
                .size(122.dp)
                .clip(CircleShape),
            model = artist.coverUri,
            contentScale = ContentScale.Crop,
            failure = placeholder(R.drawable.placeholder),
            loading = placeholder(R.drawable.placeholder),
            contentDescription = "",
        )
        Spacer(Modifier.height(6.dp))
        Text(text = artist.name, color = AppText, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        Text(text = stringResource(R.string.artist_subtitle), color = AppMuted, fontSize = 11.sp)
    }
}
