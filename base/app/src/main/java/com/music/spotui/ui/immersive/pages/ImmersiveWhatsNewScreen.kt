package com.music.spotui.ui.immersive.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.clip
import com.music.spotui.di.SongPlayer
import com.music.spotui.ui.screens.WhatsNewOptionsSheet
import com.music.spotui.ui.screens.togglePlay
import com.music.spotui.ui.screens.whatsNewPlayback
import com.music.spotui.ui.screens.whatsNewSaved
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import app.myspoti.api.WhatsNewContent
import app.myspoti.api.WhatsNewItem
import app.myspoti.engine.R
import com.music.spotui.ui.components.FastScrollbar
import com.music.spotui.ui.immersive.Immersive
import com.music.spotui.ui.immersive.components.ImmersiveCover
import com.music.spotui.ui.immersive.components.ImmersiveMessageState
import com.music.spotui.ui.immersive.components.ImmersiveRowDividerInset
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
import com.music.spotui.ui.screens.coverUrl
import com.music.spotui.ui.screens.rememberWhatsNewFeed
import com.music.spotui.ui.screens.route
import com.music.spotui.ui.screens.title
import com.music.spotui.ui.screens.toggled
import com.music.spotui.ui.screens.whatsNewDate
import com.music.spotui.ui.screens.whatsNewSubtitle

/**
 * "What's New" of the Immersive look, replacing the Classic `WhatsNewScreen`: the large title and
 * its subtitle, the Music / Podcasts pills, then the releases and episodes of what the account
 * follows under "New" and "Earlier", newest first. A row opens its album or episode.
 */
@Composable
fun ImmersiveWhatsNewScreen(navController: NavController) {
    var content by rememberSaveable { mutableStateOf(WhatsNewContent.ALL) }
    var attempt by rememberSaveable { mutableStateOf(0) }
    val feed = rememberWhatsNewFeed(content, attempt)
    val listState = rememberLazyListState()
    val title = stringResource(R.string.whats_new_title)
    val subtitle = stringResource(R.string.whats_new_subtitle)

    ImmersivePageFrame {
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(top = immersiveTopContentPadding(), bottom = Immersive.BottomChrome),
            modifier = Modifier.fillMaxSize().immersiveHazeSource(),
        ) {
            immersiveLargeTitle(title, subtitle = subtitle)
            item(key = "whatsnew:pills", contentType = "pills") {
                ImmersiveChipRow {
                    ImmersiveChip(stringResource(R.string.recents_filter_music), selected = content == WhatsNewContent.MUSIC) {
                        content = content.toggled(WhatsNewContent.MUSIC)
                    }
                    ImmersiveChip(stringResource(R.string.recents_filter_podcasts), selected = content == WhatsNewContent.PODCASTS) {
                        content = content.toggled(WhatsNewContent.PODCASTS)
                    }
                }
            }
            val sections = feed.sections
            when {
                sections == null && feed.failure != null -> item(key = "whatsnew:failed") {
                    ImmersiveMessageState(
                        message = stringResource(R.string.whats_new_failed, feed.failure.orEmpty()),
                        actionLabel = stringResource(R.string.home_retry),
                        onAction = { attempt++ },
                    )
                }
                sections == null -> immersiveSongListSkeleton(count = 10, keyPrefix = "skeleton:whatsnew")
                sections.fresh.isEmpty() && sections.earlier.isEmpty() -> item(key = "whatsnew:empty") {
                    ImmersiveMessageState(stringResource(R.string.whats_new_empty))
                }
                else -> {
                    section(R.string.whats_new_section_new, sections.fresh, navController)
                    section(R.string.whats_new_section_earlier, sections.earlier, navController)
                }
            }
        }
        FastScrollbar(listState, Modifier.align(Alignment.CenterEnd))
        ImmersiveTopBar(title = title, listState = listState, onBack = { navController.navigateUp() })
    }
}

private fun LazyListScope.section(title: Int, list: List<WhatsNewItem>, navController: NavController) {
    if (list.isEmpty()) return
    item(key = "title:$title") { SectionHeader(title = stringResource(title), modifier = Modifier.padding(top = 8.dp)) }
    itemsIndexed(list, key = { _, item -> "item:${item.id}" }) { index, item ->
        Column {
            WhatsNewRow(item) { navController.navigate(item.route()) }
            if (index < list.lastIndex) ImmersiveRowDivider(ImmersiveRowDividerInset)
        }
    }
}

/**
 * Cover, title, "Single • Artist" (or the show) and the day it came out, then the Web Player's
 * actions: the heart for the library, the ⋯ menu, and play at the end.
 */
@Composable
private fun WhatsNewRow(item: WhatsNewItem, onClick: () -> Unit) {
    val saved = whatsNewSaved(item)
    val playback = whatsNewPlayback(item)
    var menu by remember { mutableStateOf(false) }
    if (menu) WhatsNewOptionsSheet(item, saved) { menu = false }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .immersiveClickable(onClick = onClick, pressedScale = 0.98f)
            .padding(start = Immersive.Gutter, end = Immersive.Gutter, top = 8.dp, bottom = 4.dp),
    ) {
        ImmersiveCover(item.coverUrl, Modifier.size(52.dp), Immersive.CoverSmall)
        Column(Modifier.padding(start = 14.dp).weight(1f)) {
            Text(
                item.title, style = Immersive.Body.copy(fontWeight = FontWeight.Medium, color = if (playback.current) Immersive.Accent else Immersive.Text),
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            Text(whatsNewSubtitle(item), style = Immersive.Subhead, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val date = whatsNewDate(item)
            if (date.isNotEmpty()) Text(date, style = Immersive.Caption, maxLines = 1)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 2.dp)) {
                Icon(
                    if (saved) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                    contentDescription = stringResource(if (saved) R.string.immersive_detail_unsave else R.string.immersive_detail_save),
                    tint = if (saved) Immersive.Accent else Immersive.TextSecondary,
                    modifier = Modifier.clip(CircleShape).immersiveClickable(onClick = { SongPlayer.setSaved(item.uri, !saved) }, pressedScale = 0.9f)
                        .padding(6.dp).size(22.dp),
                )
                Icon(
                    Icons.Rounded.MoreHoriz,
                    contentDescription = stringResource(R.string.immersive_shell_more),
                    tint = Immersive.TextSecondary,
                    modifier = Modifier.padding(start = 8.dp).clip(CircleShape).immersiveClickable(onClick = { menu = true }, pressedScale = 0.9f)
                        .padding(6.dp).size(22.dp),
                )
                Spacer(Modifier.weight(1f))
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(38.dp).clip(CircleShape).background(Immersive.Text)
                        .immersiveClickable(onClick = { item.togglePlay(playback) }, pressedScale = 0.9f),
                ) {
                    Icon(
                        if (playback.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = stringResource(if (playback.playing) R.string.immersive_player_pause else R.string.immersive_player_play),
                        tint = Immersive.Page,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
        }
    }
}
