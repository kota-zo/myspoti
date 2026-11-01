/*
 * Adapted from BitChord (https://github.com/kushagrasinghx/BitChord), GPL-3.0-or-later,
 * app/src/main/java/com/music/bitchord/ui/screens/HistoryScreen.kt at edc1e6f.
 * Modified for Myspoti, 2026-10.
 */
package com.music.spotui.ui.immersive.pages

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import app.myspoti.api.MediaKind
import app.myspoti.api.RecentEntry
import app.myspoti.api.RecentGroup
import app.myspoti.api.RecentItem
import app.myspoti.api.RecentSection
import app.myspoti.api.best
import app.myspoti.api.groupRecents
import app.myspoti.engine.R
import app.myspoti.host.android.MyspotiRuntime
import com.music.spotui.di.Haptics
import com.music.spotui.di.SongPlayer
import com.music.spotui.ui.components.FastScrollbar
import com.music.spotui.ui.immersive.Immersive
import com.music.spotui.ui.immersive.components.ImmersiveCover
import com.music.spotui.ui.immersive.components.ImmersiveMessageState
import com.music.spotui.ui.immersive.components.ImmersiveRowDividerInset
import com.music.spotui.ui.immersive.components.ImmersiveSongRowSkeleton
import com.music.spotui.ui.immersive.components.SectionHeader
import com.music.spotui.ui.immersive.components.immersiveClickable
import com.music.spotui.ui.immersive.components.immersiveSongListSkeleton
import com.music.spotui.ui.immersive.screens.ImmersiveChip
import com.music.spotui.ui.immersive.screens.ImmersiveChipRow
import com.music.spotui.ui.immersive.screens.ImmersiveRowDivider
import com.music.spotui.ui.immersive.shell.ImmersiveTopBar
import com.music.spotui.ui.immersive.shell.immersiveHazeSource
import com.music.spotui.ui.immersive.shell.immersiveLargeTitle
import com.music.spotui.ui.immersive.shell.immersiveTopContentPadding
import com.music.spotui.ui.look.rememberReduceMotion
import com.music.spotui.ui.navigation.Routes
import com.music.spotui.ui.navigation.albumRoute
import com.music.spotui.ui.navigation.artistRoute
import com.music.spotui.ui.navigation.playlistRoute
import com.music.spotui.ui.navigation.showRoute
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Rows per request: what the Web Player asks for its own list page. */
private const val PAGE = 50

/** The Liked Songs collection's uri, the row badged with a heart. */
private const val LIKED_URI = "spotify:collection:tracks"

/** The saved-episodes collection: a podcast context whose page is the app's own Episodes screen. */
private const val YOUR_EPISODES_URI = "spotify:collection:your-episodes"

private val ChevronRing = Color.White.copy(alpha = 0.35f)

/**
 * "Recents" of the Immersive look, replacing the Classic `RecentlyPlayedScreen`: a plain list
 * with a large title (no single cover stands for a history), a Music / Podcasts pill pair, then
 * the plays of every device grouped by context under their day. A row opens its album, playlist,
 * artist or show; its chevron unfolds the plays behind it, each of which plays with the context
 * as its queue. The next pages load as the end comes into view.
 */
@Composable
fun ImmersiveRecentlyPlayedScreen(navController: NavController) {
    var entries by remember { mutableStateOf<List<RecentEntry>?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    // The list holds thousands of rows: the next pages load as the end comes into view.
    var nextOffset by remember { mutableStateOf<Int?>(null) }
    var loadingMore by remember { mutableStateOf(false) }
    // The pill and the unfolded contexts survive a recreation; the loaded entries load again.
    var podcasts by rememberSaveable { mutableStateOf(false) }
    var expanded by rememberSaveable(
        stateSaver = listSaver(save = { it.toList() }, restore = { it.toSet() }),
    ) { mutableStateOf(emptySet<String>()) }
    var generation by remember { mutableStateOf(0) }
    val listState = rememberLazyListState()
    val reduceMotion = rememberReduceMotion()

    LaunchedEffect(generation) {
        failure = null
        runCatching { MyspotiRuntime.api.recents.recentEntries(0, PAGE) }
            .onSuccess { page ->
                entries = page.items
                nextOffset = page.nextOffset
            }
            .onFailure { failure = it.message ?: it.toString() }
    }

    val atEnd by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: return@derivedStateOf false
            last >= listState.layoutInfo.totalItemsCount - 5
        }
    }
    LaunchedEffect(atEnd, nextOffset) {
        val offset = nextOffset ?: return@LaunchedEffect
        if (!atEnd || loadingMore) return@LaunchedEffect
        loadingMore = true
        runCatching { MyspotiRuntime.api.recents.recentEntries(offset, PAGE) }
            .onSuccess { page ->
                entries = entries.orEmpty() + page.items
                nextOffset = page.nextOffset
            }
        loadingMore = false
    }

    // Grouping runs on the raw rows, then the pill drops what the tab does not show: a heading
    // whose rows are all gone disappears with them.
    val sections = remember(entries, podcasts) {
        groupRecents(entries.orEmpty()).mapNotNull { section ->
            val groups = section.groups.filter { it.music != podcasts }
            if (groups.isEmpty()) null else RecentSection(section.dayMs, groups)
        }
    }

    val title = stringResource(R.string.recents_title)
    ImmersivePageFrame {
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(top = immersiveTopContentPadding(), bottom = Immersive.BottomChrome),
            modifier = Modifier.fillMaxSize().immersiveHazeSource(),
        ) {
            immersiveLargeTitle(title)
            item(key = "recents:pills", contentType = "pills") {
                ImmersiveChipRow {
                    ImmersiveChip(stringResource(R.string.recents_filter_music), selected = !podcasts) { podcasts = false }
                    ImmersiveChip(stringResource(R.string.recents_filter_podcasts), selected = podcasts) { podcasts = true }
                }
            }
            when {
                entries == null && failure != null -> item(key = "recents:failed") {
                    ImmersiveMessageState(
                        message = stringResource(R.string.recently_played_failed, failure.orEmpty()),
                        actionLabel = stringResource(R.string.home_retry),
                        onAction = { generation++ },
                    )
                }
                entries == null -> immersiveSongListSkeleton(count = 10, keyPrefix = "skeleton:recents")
                sections.isEmpty() -> item(key = "recents:empty") {
                    ImmersiveMessageState(stringResource(R.string.recently_played_empty))
                }
                else -> {
                    val place = if (reduceMotion) null else spring<IntOffset>(
                        stiffness = Spring.StiffnessMediumLow,
                        visibilityThreshold = IntOffset.VisibilityThreshold,
                    )
                    sections.forEach { section ->
                        item(key = "day:${section.dayMs}") {
                            SectionHeader(
                                title = dayLabel(section.dayMs),
                                modifier = Modifier.animateItem(placementSpec = place).padding(top = 8.dp),
                            )
                        }
                        section.groups.forEachIndexed { groupIndex, group ->
                            val key = group.context.uri
                            item(key = "row:$key") {
                                Column(Modifier.animateItem(placementSpec = place)) {
                                    GroupRow(
                                        group = group,
                                        expanded = key in expanded,
                                        onToggle = {
                                            Haptics.click()
                                            expanded = if (key in expanded) expanded - key else expanded + key
                                        },
                                        onOpen = { openContext(group.context, navController) },
                                    )
                                    if (groupIndex < section.groups.lastIndex && key !in expanded) {
                                        ImmersiveRowDivider(ImmersiveRowDividerInset)
                                    }
                                }
                            }
                            if (key in expanded) {
                                itemsIndexed(
                                    group.plays,
                                    key = { index, play -> "play:$key:$index:${play.uri}" },
                                ) { _, play ->
                                    // Unfolded plays fade in and push the rows below down.
                                    PlayRow(play, Modifier.animateItem(placementSpec = place)) {
                                        SongPlayer.playSong(playUri(play), contextUri = group.context.uri)
                                    }
                                }
                            }
                        }
                    }
                    if (nextOffset != null) item(key = "recents:more") { ImmersiveSongRowSkeleton(index = 1) }
                }
            }
        }
        FastScrollbar(listState, Modifier.align(Alignment.CenterEnd))
        ImmersiveTopBar(title = title, listState = listState, onBack = { navController.navigateUp() })
    }
}

/**
 * One context of the list: its cover, its count, its type and creators, and the round chevron
 * that expands it in place. The row itself opens the context.
 */
@Composable
private fun GroupRow(group: RecentGroup, expanded: Boolean, onToggle: () -> Unit, onOpen: () -> Unit) {
    val reduceMotion = rememberReduceMotion()
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = if (reduceMotion) snap() else com.music.spotui.ui.immersive.motion.ImmersiveMotion.placementSpring(),
        label = "recents chevron",
    )
    val artist = group.context.kind == MediaKind.ARTIST
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .immersiveClickable(onClick = onOpen, pressedScale = 0.98f)
            .padding(horizontal = Immersive.Gutter, vertical = 4.dp),
    ) {
        RecentCover(group.context, round = artist)
        Column(Modifier.padding(start = 14.dp).weight(1f)) {
            // A context the server could not name (a withdrawn album) still reads: its count is
            // the only thing left to title the row with, and the subtitle line is then dropped
            // instead of repeating the count there.
            val count = countLabel(group.count, group.saved)
            Text(
                text = group.context.title.ifBlank { count },
                style = Immersive.Body.copy(fontWeight = FontWeight.Medium),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (group.context.title.isNotBlank()) {
                Text(groupSubtitle(group), style = Immersive.Subhead, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Box(
            Modifier
                .padding(start = 8.dp)
                .size(30.dp)
                .clip(CircleShape)
                .border(1.dp, ChevronRing, CircleShape)
                .immersiveClickable(onClick = onToggle, pressedScale = 0.9f),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Rounded.KeyboardArrowDown,
                contentDescription = stringResource(if (expanded) R.string.recents_collapse else R.string.recents_expand),
                tint = Immersive.Text,
                modifier = Modifier.size(20.dp).rotate(rotation),
            )
        }
    }
}

/** A play of an expanded context: tapping it plays that track with the context as its queue. */
@Composable
private fun PlayRow(item: RecentItem, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .immersiveClickable(onClick = onClick, pressedScale = 0.98f)
            .padding(start = Immersive.Gutter + 12.dp, end = Immersive.Gutter, top = 4.dp, bottom = 4.dp),
    ) {
        ImmersiveCover(item.images.best(300)?.url, Modifier.size(40.dp), Immersive.CoverSmall)
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            Text(item.title, style = Immersive.Body.copy(fontSize = 14.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            item.subtitle?.let { Text(it, style = Immersive.Caption, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

/**
 * The context's artwork. Artists are round, everything else a square with the page's corners;
 * Liked Songs carries a heart badge over its cover.
 */
@Composable
private fun RecentCover(context: RecentItem, round: Boolean) {
    Box(Modifier.size(52.dp)) {
        ImmersiveCover(
            url = context.images.best(300)?.url,
            modifier = Modifier.size(52.dp),
            shape = if (round) CircleShape else Immersive.CoverSmall,
        )
        if (context.uri == LIKED_URI || context.kind == MediaKind.COLLECTION) {
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .size(22.dp)
                    .clip(Immersive.CoverSmall)
                    .background(Brush.linearGradient(listOf(Color(0xFF8E7CFF), Color(0xFFC9B8FF)))),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.Favorite,
                    contentDescription = stringResource(R.string.collection_liked_songs_title),
                    tint = Color.White,
                    modifier = Modifier.size(13.dp),
                )
            }
        }
    }
}

/** "[N] tracks played • [Type] • [creators]", with its own wording for saved rows. */
@Composable
private fun groupSubtitle(group: RecentGroup): String {
    val parts = buildList {
        add(countLabel(group.count, group.saved))
        add(group.context.typeLabel?.takeIf { it.isNotBlank() } ?: kindLabel(group.context.kind))
        // Liked Songs and artists show no creator line.
        if (!group.saved && group.context.kind != MediaKind.ARTIST) group.context.subtitle?.let { add(it) }
    }
    return parts.filter { it.isNotBlank() }.joinToString(" • ")
}

@Composable
private fun countLabel(count: Int, saved: Boolean): String = when {
    saved && count == 1 -> stringResource(R.string.recents_saved_one)
    saved -> stringResource(R.string.recents_saved_many, count)
    count == 1 -> stringResource(R.string.recents_played_one)
    else -> stringResource(R.string.recents_played_many, count)
}

@Composable
private fun dayLabel(dayMs: Long?): String {
    if (dayMs == null) return ""
    val date = Instant.ofEpochMilli(dayMs).atZone(ZoneId.systemDefault()).toLocalDate()
    val today = LocalDate.now()
    return when (date) {
        today -> stringResource(R.string.recents_today)
        today.minusDays(1) -> stringResource(R.string.recents_yesterday)
        // Older days print as day and short month, in the phone's language.
        else -> date.format(DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()))
    }
}

@Composable
private fun kindLabel(kind: MediaKind): String = when (kind) {
    MediaKind.PLAYLIST -> stringResource(R.string.assisted_curation_search_playlist)
    MediaKind.ALBUM -> stringResource(R.string.album_type_album)
    MediaKind.ARTIST -> stringResource(R.string.artist_subtitle)
    MediaKind.SHOW -> stringResource(R.string.ad_category_title_podcasts)
    MediaKind.EPISODE -> stringResource(R.string.your_episodes_title)
    else -> ""
}

/** The uri a play row hands the player: episodes are queued by id, tracks by their own uri. */
private fun playUri(item: RecentItem): String =
    if (item.kind == MediaKind.EPISODE) "episode:" + item.uri.substringAfterLast(':') else item.uri

/**
 * Opens the context the row stands for. Liked Songs and Your Episodes are collections with their
 * own pages, the rest go to their playlist, album, artist or show page; a lone track (a row with
 * no context) plays.
 */
private fun openContext(context: RecentItem, navController: NavController) {
    val id = context.uri.substringAfterLast(':')
    when {
        context.uri == LIKED_URI || context.kind == MediaKind.COLLECTION -> navController.navigate(Routes.Liked.route)
        context.uri == YOUR_EPISODES_URI -> navController.navigate(Routes.Episodes.route)
        context.kind == MediaKind.PLAYLIST -> navController.navigate(playlistRoute(id, context.title))
        context.kind == MediaKind.ALBUM -> navController.navigate(albumRoute(context.title, context.subtitle.orEmpty(), id))
        context.kind == MediaKind.ARTIST -> navController.navigate(artistRoute(context.title, id))
        context.kind == MediaKind.SHOW -> navController.navigate(showRoute(id, context.title))
        context.kind == MediaKind.TRACK || context.kind == MediaKind.EPISODE ->
            SongPlayer.playSong(playUri(context), contextUri = null)
        else -> Unit
    }
}
