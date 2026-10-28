package com.music.spotui.ui.screens

import com.music.spotui.ui.components.wideClickable
import com.music.spotui.ui.theme.headerTint
import com.music.spotui.ui.theme.AppText

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Tune
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import app.myspoti.engine.R
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.CrossFade
import com.bumptech.glide.integration.compose.GlideImage
import com.bumptech.glide.integration.compose.placeholder
import com.music.spotui.data.api.Response
import com.music.spotui.data.entity.PodcastModel
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.di.SongPlayer
import com.music.spotui.ui.components.EpisodeDownloadButton
import com.music.spotui.ui.components.EpisodeOptionsSheet
import com.music.spotui.ui.components.Loader
import com.music.spotui.ui.components.episodeClock
import com.music.spotui.ui.components.episodeDateLabel
import com.music.spotui.ui.components.episodeMinutes
import com.music.spotui.ui.components.pressable
import com.music.spotui.ui.components.spotifyUri
import com.music.spotui.ui.navigation.episodeRoute
import com.music.spotui.ui.theme.AppAccent
import com.music.spotui.ui.theme.AppBackground
import com.music.spotui.ui.viewmodel.ShowViewModel

/**
 * A show page, laid out like the official app: cover and name, follow, the episodes (or the
 * chapters of a book) with their own play / download / "…" controls, and an About tab.
 */
@OptIn(ExperimentalGlideComposeApi::class)
@Composable
fun ShowScreen(navController: NavController, showId: String, showName: String = "") {
    val vm: ShowViewModel = viewModel()
    val context = LocalContext.current
    LaunchedEffect(showId) { vm.loadShow(showId) }

    val pageState by vm.page.collectAsState()
    val page = (pageState as? Response.Success)?.data
    val show = page?.show
    val audiobook = page?.audiobook == true
    val items = page?.items.orEmpty()

    var tab by remember { mutableStateOf(0) }
    var oldestFirst by remember { mutableStateOf(false) }
    var menuSong by remember { mutableStateOf<SongsModel?>(null) }
    val np by SongPlayer.nowPlaying

    fun playAt(index: Int) {
        val song = items.getOrNull(index) ?: return
        vm.updateQueue(items)
        SongPlayer.playSong(song.url, context, contextUri = "spotify:show:$showId")
        vm.updateSongState(song.coverUri, song.title, song.singer, true, song.id, index, song.album)
    }

    val rows = remember(items, oldestFirst) { if (oldestFirst) items.reversed() else items }

    menuSong?.let { sel ->
        EpisodeOptionsSheet(
            song = sel,
            finished = vm.isFinished(sel),
            onPlay = {
                val index = items.indexOfFirst { it.url == sel.url }
                if (index >= 0) playAt(index)
            },
            onMarkFinished = { vm.setFinished(sel, it) },
            onOpen = { navController.navigate(episodeRoute(sel.url.removePrefix("episode:"), sel.title)) },
            onDismiss = { menuSong = null },
        )
    }

    Box(Modifier.fillMaxSize().background(AppBackground)) {
        LazyColumn(
            contentPadding = PaddingValues(bottom = com.music.spotui.ui.components.bottomBarsSpace()),
            modifier = Modifier.fillMaxSize(),
        ) {
            item {
                Header(
                    navController = navController,
                    show = show,
                    fallbackName = showName,
                    cover = page?.coverUri.orEmpty(),
                    audiobook = audiobook,
                    following = vm.following.value,
                    onFollow = { vm.setFollow(showId, it) },
                )
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TabLabel(
                        text = stringResource(if (audiobook) R.string.show_tab_chapters else R.string.show_tab_episodes),
                        selected = tab == 0,
                        modifier = Modifier.weight(1f),
                    ) { tab = 0 }
                    TabLabel(stringResource(R.string.show_tab_about), selected = tab == 1, modifier = Modifier.weight(1f)) { tab = 1 }
                }
            }
            if (tab == 0 && rows.isNotEmpty()) {
                item {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { oldestFirst = !oldestFirst }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    ) {
                        Icon(Icons.Default.Tune, contentDescription = null, tint = AppText.copy(alpha = 0.8f), modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = stringResource(
                                when {
                                    audiobook && oldestFirst -> R.string.show_sort_chapters_oldest
                                    audiobook -> R.string.show_sort_chapters_newest
                                    oldestFirst -> R.string.show_sort_oldest
                                    else -> R.string.show_sort_newest
                                },
                            ),
                            color = AppText.copy(alpha = 0.8f),
                            fontSize = 13.sp,
                        )
                    }
                }
            }
            if (pageState is Response.Loading && page == null) item { Loader() }
            if (pageState is Response.Error && items.isEmpty()) item {
                com.music.spotui.ui.components.ErrorRetry(stringResource(R.string.page_load_failed)) { vm.loadShow(showId) }
            }
            if (tab == 1) item { About(show) }
            if (tab == 0 && page != null && items.isEmpty() && pageState !is Response.Loading) item {
                Text(
                    stringResource(R.string.show_empty),
                    color = AppText.copy(alpha = 0.6f),
                    fontSize = 14.sp,
                    modifier = Modifier.padding(16.dp),
                )
            }
            if (tab == 0) items(rows, key = { it.url }) { ep ->
                EpisodeRow(
                    episode = ep,
                    playing = np.uri == "spotify:episode:${ep.url.removePrefix("episode:")}" && SongPlayer.isPlaying(),
                    finished = vm.isFinished(ep),
                    onPlay = {
                        val index = items.indexOfFirst { it.url == ep.url }
                        if (index >= 0) playAt(index)
                    },
                    onOpen = { navController.navigate(episodeRoute(ep.url.removePrefix("episode:"), ep.title)) },
                    onMenu = { menuSong = ep },
                )
            }
        }
    }
}

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun Header(
    navController: NavController,
    show: PodcastModel?,
    fallbackName: String,
    cover: String,
    audiobook: Boolean,
    following: Boolean,
    onFollow: (Boolean) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(headerTint(Color(0xFF33322F), 0f), AppBackground)))
            .statusBarsPadding(),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .padding(12.dp)
                .wideClickable { navController.navigateUp() }
                .size(40.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.35f)),
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.accessibility_navigate_back), tint = Color.White)
        }
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            GlideImage(
                transition = CrossFade,
                model = cover,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                failure = placeholder(R.drawable.placeholder),
                modifier = Modifier
                    .size(128.dp)
                    .clip(RoundedCornerShape(6.dp)),
            )
            Column(Modifier.weight(1f).padding(start = 16.dp)) {
                Text(
                    text = show?.name?.takeIf { it.isNotBlank() } ?: fallbackName,
                    color = AppText,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                val authors = show?.authors.orEmpty().joinToString(", ")
                val narrators = show?.narrators.orEmpty().joinToString(", ")
                if (authors.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(stringResource(R.string.show_authors, authors), color = AppText.copy(alpha = 0.8f), fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                if (narrators.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(stringResource(R.string.show_narrators, narrators), color = AppText.copy(alpha = 0.65f), fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                if (authors.isBlank() && show != null && show.publisher.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(show.publisher, color = AppText.copy(alpha = 0.8f), fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        // Rating, as the official header shows it under the cover.
        val rating = show?.rating ?: 0.0
        if (rating > 0.0) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 16.dp, top = 12.dp),
            ) {
                Icon(Icons.Default.Star, contentDescription = null, tint = AppText.copy(alpha = 0.8f), modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    text = String.format(java.util.Locale.getDefault(), "%.1f", rating),
                    color = AppText.copy(alpha = 0.8f),
                    fontSize = 13.sp,
                )
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            FollowPill(following) { onFollow(!following) }
        }
    }
}

@Composable
private fun FollowPill(following: Boolean, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .height(32.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (following) AppText.copy(alpha = 0.12f) else Color.Transparent)
            .pressable(0.96f, onClick = onClick)
            .padding(horizontal = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (following) {
                Icon(Icons.Default.Check, contentDescription = null, tint = AppText, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
            }
            Text(
                text = stringResource(if (following) R.string.show_following else R.string.show_follow),
                color = AppText,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun TabLabel(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.clickable(onClick = onClick).padding(vertical = 10.dp),
    ) {
        Text(
            text = text,
            color = if (selected) AppText else AppText.copy(alpha = 0.6f),
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        )
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier
                .height(2.dp)
                .width(if (selected) 40.dp else 0.dp)
                .background(AppAccent),
        )
    }
}

@Composable
private fun About(show: PodcastModel?) {
    val authors = show?.authors.orEmpty().joinToString(", ")
    val narrators = show?.narrators.orEmpty().joinToString(", ")
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Text(
            text = com.music.spotui.ui.components.rememberLinkedText(show?.description.orEmpty(), AppAccent),
            color = AppText.copy(alpha = 0.75f),
            fontSize = 14.sp,
        )
        if (authors.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.show_authors, authors), color = AppText.copy(alpha = 0.6f), fontSize = 13.sp)
        }
        if (narrators.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.show_narrators, narrators), color = AppText.copy(alpha = 0.6f), fontSize = 13.sp)
        }
    }
}

/** One episode (or chapter): cover, title, snippet, date • length, then its own controls. */
@OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun EpisodeRow(
    episode: SongsModel,
    playing: Boolean,
    finished: Boolean,
    onPlay: () -> Unit,
    onOpen: () -> Unit,
    onMenu: () -> Unit,
) {
    val date = episodeDateLabel(episode.releaseDate)
    val minutes = episodeMinutes(episode.durationMs)
    Column(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row {
            GlideImage(
                transition = CrossFade,
                model = episode.coverUri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                failure = placeholder(R.drawable.placeholder),
                modifier = Modifier.size(56.dp).clip(RoundedCornerShape(4.dp)),
            )
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(
                    text = episode.title,
                    color = if (playing) AppAccent else AppText,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (episode.description.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = episode.description,
                        color = AppText.copy(alpha = 0.65f),
                        fontSize = 12.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (finished) {
                        Text(stringResource(R.string.episode_marked_played), color = AppText.copy(alpha = 0.6f), fontSize = 12.sp)
                        Spacer(Modifier.width(6.dp))
                        Text("•", color = AppText.copy(alpha = 0.4f), fontSize = 12.sp)
                        Spacer(Modifier.width(6.dp))
                    }
                    val started = episode.playedPositionMs > 0 && !finished
                    Text(
                        text = when {
                            date.isBlank() -> minutes
                            else -> "$date • $minutes"
                        },
                        color = AppText.copy(alpha = 0.6f),
                        fontSize = 12.sp,
                    )
                    if (started) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.episode_remaining, episodeClock(episode.durationMs - episode.playedPositionMs)),
                            color = AppAccent,
                            fontSize = 12.sp,
                        )
                    }
                }
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End,
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        ) {
            Icon(
                Icons.Default.MoreVert,
                contentDescription = stringResource(R.string.episode_options),
                tint = AppText.copy(alpha = 0.8f),
                modifier = Modifier.size(22.dp).pressable(0.9f, onClick = onMenu),
            )
            Spacer(Modifier.weight(1f))
            EpisodeDownloadButton(episode, size = 24.dp)
            Spacer(Modifier.width(6.dp))
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(40.dp).clip(CircleShape).background(AppText).pressable(0.92f, onClick = onPlay),
            ) {
                com.music.spotui.ui.components.PlayPauseIcon(
                    playing = playing,
                    contentDescription = stringResource(R.string.episode_play),
                    tint = com.music.spotui.ui.theme.AppBackground,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}
