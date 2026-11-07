package com.music.spotui.ui.screens

import com.music.spotui.ui.components.wideClickable
import com.music.spotui.ui.theme.tone
import com.music.spotui.ui.theme.AppText
import com.music.spotui.ui.theme.AppMuted

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
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
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.CrossFade
import com.bumptech.glide.integration.compose.GlideImage
import com.music.spotui.di.SongPlayer
import com.music.spotui.ui.components.countLabel
import com.music.spotui.ui.components.dayLabel
import com.music.spotui.ui.components.groupSubtitle
import com.music.spotui.ui.components.openContext
import com.music.spotui.ui.components.playUri

private val Background: Color get() = com.music.spotui.ui.theme.AppBackground
private val Subtle: androidx.compose.ui.graphics.Color get() = AppMuted
/** The pill and chevron greys of Spotify's own page. */
private val ChipIdle: androidx.compose.ui.graphics.Color get() = tone(0xFF282828)
private val ChevronRing = Color(0xFF8A8A8A)

/** Rows per request: what the Web Player asks for its own list page. */
private const val PAGE = 50

/** The Liked Songs collection's uri, the row Spotify badges with a heart. */
private const val LIKED_URI = "spotify:collection:tracks"

/** The saved-episodes collection: a podcast context whose page is the app's own Episodes screen. */
private const val YOUR_EPISODES_URI = "spotify:collection:your-episodes"

/**
 * "Récents", from Spotify's own list for the account (see [app.myspoti.api.Recents]): the plays of
 * every device, grouped by context the way the official page groups them — one row per album,
 * playlist, artist or show, dated, with the plays behind an expand chevron.
 */
@Composable
fun RecentlyPlayedScreen(navController: NavController) {
    var entries by remember { mutableStateOf<List<RecentEntry>?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    // Spotify's list holds thousands of rows: the next pages load as the end comes into view.
    var nextOffset by remember { mutableStateOf<Int?>(null) }
    var loadingMore by remember { mutableStateOf(false) }
    var podcasts by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(emptySet<String>()) }
    val listState = rememberLazyListState()

    LaunchedEffect(Unit) {
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
            last >= (entries?.size ?: 0) - 5
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

    Column(Modifier.fillMaxSize().background(Background).statusBarsPadding()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 16.dp, top = 8.dp),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.accessibility_navigate_back),
                tint = AppText,
                modifier = Modifier.wideClickable(horizontal = 8.dp, vertical = 10.dp) { navController.popBackStack() }
                    .size(40.dp).padding(8.dp),
            )
            Text(
                stringResource(R.string.recents_title),
                fontWeight = FontWeight.Bold, color = AppText, fontSize = 24.sp, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 4.dp),
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Pill(stringResource(R.string.recents_filter_music), selected = !podcasts) { podcasts = false }
            Pill(stringResource(R.string.recents_filter_podcasts), selected = podcasts) { podcasts = true }
        }
        when {
            entries == null && failure != null -> Text(
                stringResource(R.string.recently_played_failed, failure.orEmpty()),
                color = Subtle, fontSize = 14.sp, modifier = Modifier.padding(20.dp),
            )
            entries == null -> com.music.spotui.ui.components.SkeletonList(count = 9)
            sections.isEmpty() -> Text(
                stringResource(R.string.recently_played_empty),
                color = Subtle, fontSize = 14.sp, modifier = Modifier.padding(20.dp),
            )
            else -> LazyColumn(
                state = listState,
                contentPadding = PaddingValues(bottom = com.music.spotui.ui.components.bottomBarsSpace()),
            ) {
                sections.forEach { section ->
                    item(key = "day:${section.dayMs}") { DayHeader(section.dayMs) }
                    section.groups.forEach { group ->
                        val key = group.context.uri
                        item(key = "row:$key") {
                            GroupRow(
                                modifier = Modifier.animateItem(),
                                group = group,
                                expanded = key in expanded,
                                onToggle = {
                                    expanded = if (key in expanded) expanded - key else expanded + key
                                },
                                onOpen = { openContext(group.context, navController) },
                            )
                        }
                        if (key in expanded) {
                            itemsIndexed(
                                group.plays,
                                key = { index, play -> "play:$key:$index:${play.uri}" },
                            ) { _, play ->
                                // Unfolded plays fade in and push the rows below down, as in the official app.
                                PlayRow(play, Modifier.animateItem()) {
                                    SongPlayer.playSong(playUri(play), contextUri = group.context.uri)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DayHeader(dayMs: Long?) {
    Text(
        dayLabel(dayMs),
        color = AppText, fontSize = 18.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 6.dp),
    )
}

@Composable
private fun Pill(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(50))
            .background(if (selected) AppText else ChipIdle)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    ) {
        Text(
            label,
            color = if (selected) Color.Black else AppText,
            fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1,
        )
    }
}

/**
 * One context of the list: its cover, its count, its type and creators, and the round chevron
 * that expands it in place. The row itself opens the context, as in the official app.
 */
@Composable
private fun GroupRow(group: RecentGroup, expanded: Boolean, onToggle: () -> Unit, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val artist = group.context.kind == MediaKind.ARTIST
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, label = "recentChevron")
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.fillMaxWidth().clickable(onClick = onOpen)
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        RecentCover(group.context, 56.dp)
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            // A context the server could not name (a withdrawn album) still reads: its count is
            // the only thing left to title the row with, and Spotify then drops the subtitle
            // line instead of repeating the count there.
            val count = countLabel(group.count, group.saved)
            Text(
                group.context.title.ifBlank { count },
                color = AppText, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (group.context.title.isNotBlank()) {
                Text(
                    groupSubtitle(group),
                    color = Subtle, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Box(
            Modifier.padding(start = 8.dp).size(28.dp).clip(CircleShape)
                .border(1.dp, ChevronRing, CircleShape)
                .clickable(onClick = onToggle),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.KeyboardArrowDown,
                contentDescription = stringResource(
                    if (expanded) R.string.recents_collapse else R.string.recents_expand,
                ),
                tint = AppText,
                modifier = Modifier.size(18.dp).rotate(rotation),
            )
        }
    }
}

/** A play of an expanded context: tapping it plays that track with the context as its queue. */
@OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun PlayRow(item: RecentItem, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
    ) {
        GlideImage(
            transition = CrossFade,
            model = item.images.best(300)?.url.orEmpty(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(40.dp).clip(RoundedCornerShape(3.dp)),
        )
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            Text(item.title, color = AppText, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            item.subtitle?.let {
                Text(it, color = Subtle, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/**
 * The context's artwork. Artists are round, everything else is a square with the page's corners;
 * Liked Songs carries the heart badge Spotify draws over its cover.
 */
@OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun RecentCover(context: RecentItem, size: Dp) {
    Box(Modifier.size(size)) {
        GlideImage(
            transition = CrossFade,
            model = context.images.best(300)?.url.orEmpty(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(size).clip(
                if (context.kind == MediaKind.ARTIST) CircleShape else RoundedCornerShape(4.dp),
            ),
        )
        if (context.uri == LIKED_URI || context.kind == MediaKind.COLLECTION) {
            Box(
                Modifier.align(Alignment.BottomStart).size(22.dp).clip(RoundedCornerShape(6.dp))
                    .background(Brush.linearGradient(listOf(Color(0xFF8E7CFF), Color(0xFFC9B8FF)))),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Favorite,
                    contentDescription = stringResource(R.string.collection_liked_songs_title),
                    tint = AppText,
                    modifier = Modifier.size(13.dp),
                )
            }
        }
    }
}
