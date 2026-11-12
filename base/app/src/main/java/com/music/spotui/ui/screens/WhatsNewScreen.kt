package com.music.spotui.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material.icons.automirrored.outlined.QueueMusic
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import app.myspoti.api.WhatsNewContent
import app.myspoti.api.WhatsNewItem
import app.myspoti.api.WhatsNewSections
import app.myspoti.api.WhatsNewState
import app.myspoti.api.best
import app.myspoti.api.whatsNewSections
import app.myspoti.engine.R
import app.myspoti.host.android.MyspotiRuntime
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import com.music.spotui.ui.components.wideClickable
import com.music.spotui.data.api.Response
import com.music.spotui.di.Graph
import com.music.spotui.di.SongPlayer
import com.music.spotui.ui.navigation.albumRoute
import com.music.spotui.ui.navigation.episodeRoute
import com.music.spotui.ui.theme.AppAccent
import com.music.spotui.ui.theme.AppMuted
import com.music.spotui.ui.theme.AppText
import com.music.spotui.ui.theme.tone
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Entries per request: what the Web Player's page asks for. */
private const val PAGE = 50

/** Where loading stops, should a feed ever run long. */
private const val MAX_ITEMS = 200

/** The feed of one pill, as both looks' pages show it; null [sections] while it loads. */
internal class WhatsNewFeed {
    var sections by mutableStateOf<WhatsNewSections?>(null)
    var failure by mutableStateOf<String?>(null)
}

/**
 * Loads the feed for [content], and marks its unseen entries seen once they are on screen, as the
 * Web Player's page does: what was new moves to "Earlier" on a later visit.
 * [attempt] loads it again (a retry).
 */
@Composable
internal fun rememberWhatsNewFeed(content: WhatsNewContent, attempt: Int): WhatsNewFeed {
    val feed = remember { WhatsNewFeed() }
    LaunchedEffect(content, attempt) {
        feed.failure = null
        feed.sections = null
        // The whole feed at once (a few months, 72 entries on a real account): "New" and
        // "Earlier" are split over its full order, and the Web Player stopped at the first 50.
        runCatching {
            val items = mutableListOf<WhatsNewItem>()
            var offset: Int? = 0
            while (offset != null && items.size < MAX_ITEMS) {
                val current: Int = offset
                val page = MyspotiRuntime.api.home.whatsNewFeed(current, PAGE, content)
                items += page.items
                offset = page.nextOffset?.takeIf { it > current }
            }
            items.distinctBy { it.id }
        }
            .onSuccess { items ->
                // Split before marking: what was unseen stays under "New" on this visit.
                feed.sections = whatsNewSections(items)
                val unseen = items.filter { it.state == WhatsNewState.NEW }.map { it.id }
                if (unseen.isNotEmpty()) runCatching { MyspotiRuntime.api.home.markWhatsNewSeen(unseen) }
            }
            .onFailure { feed.failure = it.message ?: it.toString() }
    }
    return feed
}

/** Where the player stands with an entry: on it (the release as the context, or the episode), and playing. */
internal data class WhatsNewPlayback(val current: Boolean, val playing: Boolean)

@Composable
internal fun whatsNewPlayback(item: WhatsNewItem): WhatsNewPlayback {
    val now = SongPlayer.nowPlaying.value
    val current = if (item.album != null) now.contextUri == item.uri else now.uri == item.uri
    return WhatsNewPlayback(current, current && Graph.currentSongState.playingState.value)
}

/**
 * The entry's play button: pauses or resumes it when it is on, else starts it. A release plays
 * from its first song with the release as the context, as its page's own button does.
 */
internal fun WhatsNewItem.togglePlay(state: WhatsNewPlayback) {
    val id = uri.substringAfterLast(':')
    when {
        state.playing -> SongPlayer.pause()
        state.current -> SongPlayer.play()
        album == null -> SongPlayer.playSong("episode:$id")
        else -> MyspotiRuntime.scope.launch {
            val page = withTimeoutOrNull(15_000) {
                Graph.api.getAlbumPage(id).first { it is Response.Error || (it is Response.Success && it.data.tracks.isNotEmpty()) }
            }
            val songs = (page as? Response.Success)?.data?.tracks.orEmpty()
            withContext(Dispatchers.Main) {
                if (songs.isEmpty()) Toast.makeText(Graph.app, R.string.page_load_failed, Toast.LENGTH_SHORT).show()
                else SongPlayer.playSong(songs.first().url, contextUri = uri, songs = songs)
            }
        }
    }
}

/** Whether the entry is in the library (Your Episodes for an episode), asked of Spotify once shown. */
@Composable
internal fun whatsNewSaved(item: WhatsNewItem): Boolean {
    LaunchedEffect(item.uri) { SongPlayer.requestSaved(item.uri) }
    return SongPlayer.library[item.uri] == true
}

/**
 * The ⋯ menu of an entry: add it to (or remove it from) the library, add it to the queue, and
 * for a release "Add to playlist" (all its songs, as its page's menu does).
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun WhatsNewOptionsSheet(item: WhatsNewItem, saved: Boolean, onDismiss: () -> Unit) {
    var adding by remember { mutableStateOf(false) }
    if (adding) { com.music.spotui.ui.components.AddCollectionToPlaylistSheet(item.uri, item.title, onDismiss); return }
    val episode = item.album == null
    com.music.spotui.ui.components.LookSheet(onDismissRequest = onDismiss, containerColor = tone(0xFF282828)) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
            Text(item.title, color = AppText, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(20.dp, 0.dp, 20.dp, 8.dp))
            com.music.spotui.ui.components.OptionRow(
                if (saved) Icons.Filled.CheckCircle else Icons.Outlined.AddCircleOutline,
                stringResource(when {
                    episode && saved -> R.string.episode_unsave
                    episode -> R.string.episode_save
                    saved -> R.string.whats_new_remove_library
                    else -> R.string.whats_new_add_library
                }),
            ) {
                SongPlayer.setSaved(item.uri, !saved)
                onDismiss()
            }
            com.music.spotui.ui.components.OptionRow(Icons.AutoMirrored.Outlined.QueueMusic, stringResource(R.string.context_menu_add_to_queue)) {
                if (episode) SongPlayer.addEpisodeToQueue("episode:" + item.uri.substringAfterLast(':'))
                else com.music.spotui.di.CollectionQueue.queue(item.uri, next = false)
                onDismiss()
            }
            if (!episode) com.music.spotui.ui.components.OptionRow(Icons.AutoMirrored.Outlined.PlaylistAdd, stringResource(R.string.context_menu_add_to_playlist)) {
                adding = true
            }
        }
    }
}

/** The Web Player's pills: none selected shows both, a selected one again shows both. */
internal fun WhatsNewContent.toggled(pill: WhatsNewContent): WhatsNewContent =
    if (this == pill) WhatsNewContent.ALL else pill

/** An album opens its page, an episode its own page. */
internal fun WhatsNewItem.route(): String {
    val id = uri.substringAfterLast(':')
    album?.let { a -> return albumRoute(a.name, a.artists.joinToString(", ") { it.name }, id) }
    return episodeRoute(id, episode?.name.orEmpty())
}

internal val WhatsNewItem.title: String get() = album?.name ?: episode?.name.orEmpty()

/** The release's cover, or the episode's (its show's when it has none of its own). */
internal val WhatsNewItem.coverUrl: String?
    get() {
        val images = album?.images ?: episode?.let { e -> e.images.ifEmpty { e.show?.images.orEmpty() } }.orEmpty()
        return images.best(300)?.url
    }

/** "Single • Artist" for a release, the show's name for an episode. */
@Composable
internal fun whatsNewSubtitle(item: WhatsNewItem): String {
    val album = item.album ?: return item.episode?.show?.name.orEmpty()
    val type = when (album.type.uppercase()) {
        "SINGLE" -> stringResource(R.string.album_type_single)
        "EP" -> stringResource(R.string.album_type_ep)
        "COMPILATION" -> stringResource(R.string.album_type_compilation)
        else -> stringResource(R.string.album_type_album)
    }
    return listOf(type, album.artists.joinToString(", ") { it.name }).filter { it.isNotBlank() }.joinToString(" • ")
}

/** The day it came out: today, yesterday, or day and month (and year when not this one). */
@Composable
internal fun whatsNewDate(item: WhatsNewItem): String {
    val iso = item.releaseDate ?: return ""
    val date = runCatching { OffsetDateTime.parse(iso).atZoneSameInstant(ZoneId.systemDefault()).toLocalDate() }.getOrNull()
        ?: runCatching { Instant.parse(iso).atZone(ZoneId.systemDefault()).toLocalDate() }.getOrNull()
        ?: runCatching { LocalDate.parse(iso.take(10)) }.getOrNull()
        ?: return ""
    val today = LocalDate.now()
    return when (date) {
        today -> stringResource(R.string.recents_today)
        today.minusDays(1) -> stringResource(R.string.recents_yesterday)
        else -> date.format(DateTimeFormatter.ofPattern(if (date.year == today.year) "d MMM" else "d MMM yyyy", Locale.getDefault()))
    }
}

/**
 * "What's New", behind the bell on Home: the releases of the artists the account follows and the
 * episodes of its shows, newest first, under "New" and "Earlier" as on the Web Player's page.
 */
@Composable
fun WhatsNewScreen(navController: NavController) {
    var content by remember { mutableStateOf(WhatsNewContent.ALL) }
    var attempt by remember { mutableStateOf(0) }
    val feed = rememberWhatsNewFeed(content, attempt)

    Column(Modifier.fillMaxSize().background(com.music.spotui.ui.theme.AppBackground).statusBarsPadding()) {
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
                stringResource(R.string.whats_new_title),
                fontWeight = FontWeight.Bold, color = AppText, fontSize = 24.sp, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 4.dp),
            )
        }
        Text(
            stringResource(R.string.whats_new_subtitle),
            color = AppMuted, fontSize = 13.sp, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 2.dp),
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            FilterPill(stringResource(R.string.recents_filter_music), content == WhatsNewContent.MUSIC) {
                content = content.toggled(WhatsNewContent.MUSIC)
            }
            FilterPill(stringResource(R.string.recents_filter_podcasts), content == WhatsNewContent.PODCASTS) {
                content = content.toggled(WhatsNewContent.PODCASTS)
            }
        }
        val sections = feed.sections
        when {
            sections == null && feed.failure != null -> Column(Modifier.padding(20.dp)) {
                Text(stringResource(R.string.whats_new_failed, feed.failure.orEmpty()), color = AppMuted, fontSize = 14.sp)
                Text(
                    stringResource(R.string.home_retry), color = AppText, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 12.dp).clickable { attempt++ },
                )
            }
            sections == null -> com.music.spotui.ui.components.SkeletonList(count = 9)
            sections.fresh.isEmpty() && sections.earlier.isEmpty() -> Text(
                stringResource(R.string.whats_new_empty), color = AppMuted, fontSize = 14.sp, modifier = Modifier.padding(20.dp),
            )
            else -> LazyColumn(contentPadding = PaddingValues(bottom = com.music.spotui.ui.components.bottomBarsSpace())) {
                section(R.string.whats_new_section_new, sections.fresh, navController)
                section(R.string.whats_new_section_earlier, sections.earlier, navController)
            }
        }
    }
}

private fun LazyListScope.section(title: Int, list: List<WhatsNewItem>, navController: NavController) {
    if (list.isEmpty()) return
    item(key = "title:$title") {
        Text(
            stringResource(title), color = AppText, fontSize = 18.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 6.dp),
        )
    }
    items(list, key = { "item:${it.id}" }) { item -> WhatsNewRow(item) { navController.navigate(item.route()) } }
}

/**
 * Cover, title, "Single • Artist" (or the show) and the day it came out, then the Web Player's
 * actions: + / ✓ for the library, the ⋯ menu, and play at the end.
 */
@OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun WhatsNewRow(item: WhatsNewItem, onClick: () -> Unit) {
    val saved = whatsNewSaved(item)
    val playback = whatsNewPlayback(item)
    var menu by remember { mutableStateOf(false) }
    if (menu) WhatsNewOptionsSheet(item, saved) { menu = false }
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
    ) {
        GlideImage(
            model = item.coverUrl.orEmpty(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(64.dp).clip(RoundedCornerShape(4.dp)).background(tone(0xFF282828)),
        )
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            Text(item.title, color = if (playback.current) AppAccent else AppText, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(whatsNewSubtitle(item), color = AppMuted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val date = whatsNewDate(item)
            if (date.isNotEmpty()) Text(date, color = AppMuted, fontSize = 12.sp, maxLines = 1)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 2.dp)) {
                LikeButton(liked = saved, size = 24.dp) { SongPlayer.setSaved(item.uri, !saved) }
                Icon(
                    Icons.Filled.MoreHoriz,
                    contentDescription = stringResource(R.string.content_description_context_menu),
                    tint = AppMuted,
                    modifier = Modifier.padding(start = 12.dp).clip(CircleShape).clickable { menu = true }.padding(8.dp).size(24.dp),
                )
                Spacer(Modifier.weight(1f))
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(40.dp).clip(CircleShape).background(AppText).clickable { item.togglePlay(playback) },
                ) {
                    Icon(
                        if (playback.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = stringResource(if (playback.playing) R.string.content_description_pause_button else R.string.content_description_play_button),
                        tint = Color.Black,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun FilterPill(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(50))
            .background(if (selected) AppText else tone(0xFF282828))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    ) {
        Text(label, color = if (selected) Color.Black else AppText, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}
