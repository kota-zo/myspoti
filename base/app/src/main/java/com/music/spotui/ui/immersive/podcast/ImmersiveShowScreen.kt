package com.music.spotui.ui.immersive.podcast

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Podcasts
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import app.myspoti.engine.R
import com.music.spotui.data.api.Response
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.di.Haptics
import com.music.spotui.di.SongPlayer
import com.music.spotui.data.preferences.EpisodeFilter
import com.music.spotui.ui.components.EpisodeOptionsSheet
import com.music.spotui.ui.components.episodeFilterLabel
import com.music.spotui.ui.immersive.Immersive
import com.music.spotui.ui.immersive.components.ImmersiveButton
import com.music.spotui.ui.immersive.components.ImmersiveCover
import com.music.spotui.ui.immersive.components.ImmersiveMessageState
import com.music.spotui.ui.immersive.components.immersiveClickable
import com.music.spotui.ui.immersive.components.immersiveSongListSkeleton
import com.music.spotui.ui.immersive.screens.ImmersiveAboutText
import com.music.spotui.ui.immersive.screens.ImmersiveChip
import com.music.spotui.ui.immersive.screens.ImmersiveChipRow
import com.music.spotui.ui.immersive.shell.ImmersiveTopBar
import com.music.spotui.ui.immersive.shell.immersiveHazeSource
import com.music.spotui.ui.immersive.shell.immersiveTopContentPadding
import com.music.spotui.ui.navigation.episodeRoute
import com.music.spotui.ui.viewmodel.ShowViewModel

private val ShowCover = 208.dp

/**
 * A show (or audiobook) page in the Immersive look: the cover on a wash of its own colour, the
 * name, who makes it, the rating and Follow; then Episodes (or Chapters) and About as pills, the
 * sort order, and the episodes with their own play, download and menu controls. Same
 * [ShowViewModel] and actions as the Classic show page.
 */
@Composable
fun ImmersiveShowScreen(navController: NavController, showId: String, showName: String = "") {
    val vm: ShowViewModel = viewModel()
    val context = LocalContext.current
    LaunchedEffect(showId) { vm.loadShow(showId) }

    val pageState by vm.page.collectAsState()
    val page = (pageState as? Response.Success)?.data
    val show = page?.show
    val audiobook = page?.audiobook == true
    val items = page?.items.orEmpty()
    val displayName = show?.name?.takeIf { it.isNotBlank() } ?: showName
    val cover = page?.coverUri.orEmpty()

    var tab by remember { mutableStateOf(0) }
    var oldestFirst by remember { mutableStateOf(false) }
    var menuSong by remember { mutableStateOf<SongsModel?>(null) }
    val np by SongPlayer.nowPlaying
    val listState = rememberLazyListState()

    fun playAt(index: Int) {
        val song = items.getOrNull(index) ?: return
        vm.updateQueue(items)
        SongPlayer.playSong(song.url, context, contextUri = "spotify:show:$showId")
        vm.updateSongState(song.coverUri, song.title, song.singer, true, song.id, index, song.album)
    }

    val filter = vm.filter.value
    val rows by remember(items, oldestFirst, filter) {
        derivedStateOf { filter.apply(if (oldestFirst) items.reversed() else items) { vm.isFinished(it) } }
    }

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

    ImmersiveOverscrollHost {
        Box(Modifier.fillMaxSize().background(Immersive.Page)) {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(bottom = Immersive.BottomChrome),
                modifier = Modifier.fillMaxSize().immersiveHazeSource(),
            ) {
                item(key = "header") {
                    ShowHeader(
                        show = show,
                        name = displayName,
                        cover = cover,
                        following = vm.following.value,
                        onFollow = { on ->
                            Haptics.toggle(on)
                            vm.setFollow(showId, on)
                        },
                    )
                }
                item(key = "tabs") {
                    ImmersiveChipRow {
                        ImmersiveChip(
                            label = stringResource(if (audiobook) R.string.show_tab_chapters else R.string.show_tab_episodes),
                            selected = tab == 0,
                            onClick = { tab = 0 },
                        )
                        ImmersiveChip(stringResource(R.string.show_tab_about), selected = tab == 1, onClick = { tab = 1 })
                    }
                }
                if (tab == 0 && items.isNotEmpty()) item(key = "sort") {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = Immersive.Gutter),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .weight(1f)
                                .immersiveClickable(onClick = { oldestFirst = !oldestFirst }, pressedScale = 0.96f)
                                .padding(vertical = 12.dp),
                        ) {
                            Icon(Icons.Rounded.SwapVert, contentDescription = null, tint = Immersive.Accent, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = stringResource(
                                    when {
                                        audiobook && oldestFirst -> R.string.show_sort_chapters_oldest
                                        audiobook -> R.string.show_sort_chapters_newest
                                        oldestFirst -> R.string.show_sort_oldest
                                        else -> R.string.show_sort_newest
                                    },
                                ),
                                style = Immersive.Subhead.copy(color = Immersive.Accent, fontWeight = FontWeight.Medium),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text(
                            text = stringResource(episodeFilterLabel(filter)),
                            style = Immersive.Subhead.copy(
                                color = if (filter == EpisodeFilter.ALL) Immersive.TextSecondary else Immersive.Accent,
                                fontWeight = FontWeight.Medium,
                            ),
                            maxLines = 1,
                            modifier = Modifier
                                .immersiveClickable(onClick = { vm.setFilter(showId, filter.next()) }, pressedScale = 0.96f)
                                .padding(start = 12.dp, top = 12.dp, bottom = 12.dp),
                        )
                    }
                }
                if (tab == 0 && items.isNotEmpty() && rows.isEmpty()) item(key = "all-played") {
                    ImmersiveMessageState(stringResource(R.string.show_filter_all_played))
                }
                if (pageState is Response.Loading && page == null) immersiveSongListSkeleton(count = 5, keyPrefix = "skeleton:show", alpha = 0.5f)
                if (pageState is Response.Error && items.isEmpty()) item(key = "error") {
                    ImmersiveMessageState((pageState as Response.Error).error)
                }
                if (tab == 1) item(key = "about") { About(show) }
                if (tab == 0 && page != null && items.isEmpty() && pageState !is Response.Loading) item(key = "empty") {
                    ImmersiveMessageState(stringResource(R.string.show_empty))
                }
                if (tab == 0) items(rows, key = { it.url }) { ep ->
                    ImmersiveEpisodeRow(
                        episode = ep,
                        playing = np.uri == "spotify:episode:${ep.url.removePrefix("episode:")}" && SongPlayer.isPlaying(),
                        finished = vm.isFinished(ep),
                        isNew = vm.isNew(ep),
                        onPlay = {
                            val index = items.indexOfFirst { it.url == ep.url }
                            if (index >= 0) playAt(index)
                        },
                        onOpen = { navController.navigate(episodeRoute(ep.url.removePrefix("episode:"), ep.title)) },
                        onMenu = { menuSong = ep },
                        modifier = rowPlacement(),
                    )
                }
            }
            ImmersiveTopBar(
                title = displayName,
                collapse = rememberHeaderCollapse(listState),
                onBack = { navController.navigateUp() },
            )
        }
    }
}

@Composable
private fun ShowHeader(
    show: com.music.spotui.data.entity.PodcastModel?,
    name: String,
    cover: String,
    following: Boolean,
    onFollow: (Boolean) -> Unit,
) {
    Box(Modifier.fillMaxWidth()) {
        CoverGlow(cover, height = 420.dp)
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth().padding(top = immersiveTopContentPadding() + 8.dp, bottom = 8.dp),
        ) {
            ImmersiveCover(cover, Modifier.size(ShowCover), Immersive.CoverLarge, placeholderIcon = Icons.Rounded.Podcasts)
            Spacer(Modifier.height(20.dp))
            Text(
                text = name,
                style = Immersive.LargeTitle,
                textAlign = TextAlign.Center,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = Immersive.Gutter),
            )
            val authors = show?.authors.orEmpty().joinToString(", ")
            val narrators = show?.narrators.orEmpty().joinToString(", ")
            val byline = when {
                authors.isNotBlank() -> stringResource(R.string.show_authors, authors)
                show != null && show.publisher.isNotBlank() -> show.publisher
                else -> ""
            }
            if (byline.isNotBlank()) {
                Text(
                    text = byline,
                    style = Immersive.Subhead.copy(color = Immersive.Accent),
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = Immersive.Gutter).padding(top = 4.dp),
                )
            }
            if (narrators.isNotBlank()) {
                Text(
                    text = stringResource(R.string.show_narrators, narrators),
                    style = Immersive.Caption,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = Immersive.Gutter).padding(top = 2.dp),
                )
            }
            val rating = show?.rating ?: 0.0
            if (rating > 0.0) {
                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Star, contentDescription = null, tint = Immersive.TextSecondary, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(String.format(java.util.Locale.getDefault(), "%.1f", rating), style = Immersive.Subhead)
                }
            }
            Spacer(Modifier.height(16.dp))
            ImmersiveButton(
                text = stringResource(if (following) R.string.show_following else R.string.show_follow),
                icon = if (following) Icons.Rounded.Check else Icons.Rounded.Add,
                filled = !following,
                modifier = Modifier.widthIn(min = 170.dp),
                onClick = { onFollow(!following) },
            )
        }
    }
}

@Composable
private fun About(show: com.music.spotui.data.entity.PodcastModel?) {
    val authors = show?.authors.orEmpty().joinToString(", ")
    val narrators = show?.narrators.orEmpty().joinToString(", ")
    Column(Modifier.fillMaxWidth().padding(horizontal = Immersive.Gutter, vertical = 8.dp)) {
        if (!show?.description.isNullOrBlank()) ImmersiveAboutText(show?.description.orEmpty(), maxLines = 8)
        if (authors.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.show_authors, authors), style = Immersive.Subhead)
        }
        if (narrators.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.show_narrators, narrators), style = Immersive.Subhead)
        }
    }
}
