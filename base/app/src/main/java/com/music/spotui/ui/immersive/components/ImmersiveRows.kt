/*
 * Adapted from BitChord (https://github.com/kushagrasinghx/BitChord), GPL-3.0-or-later,
 * sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/components/Common.kt at edc1e6f. Modified for Myspoti, 2026-10.
 */
package com.music.spotui.ui.immersive.components

import com.music.spotui.ui.components.secondaryClick
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.myspoti.engine.R
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.ui.immersive.Immersive

/** The cover of a row, and the height that lines the rows up (4 dp above and below it make [Immersive.RowHeight]). */
private val RowCover = 52.dp

/** A numbered row's track-number column, the number centred in it: room for three digits. */
private val TrackNumberWidth = 24.dp

/** How far a row's [ImmersiveMoreButton] reaches past the content edge into the gutter. */
private val RowMoreOutset = 14.dp

/** Where a divider under a track row starts: clear of the cover. */
val ImmersiveRowDividerInset = Immersive.Gutter + RowCover + 14.dp

private val RowTitle = Immersive.Body.copy(fontWeight = FontWeight.Medium)

/**
 * One track row for search results, album and playlist pages, the library: a 52 dp cover (or
 * [trackNumber] in its place), the title with an explicit badge, the artist, the duration and a
 * "more" button. Tap plays, long-press (or the button) calls [onMore].
 *
 * @param song the track to show (cover, title, artist, explicit, duration).
 * @param playing this song is the one playing right now: bars over its cover, title in the accent.
 * @param onMore opens the song's menu; null hides the button (and the long press).
 * @param current this song is the loaded one, playing or paused: accent title. Defaults to [playing].
 * @param trackNumber shown in place of the cover, for an album's own listing.
 * @param showDuration the length at the end of the row.
 * @param showAlbum "artist · album" as the second line instead of just the artist.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ImmersiveSongRow(
    song: SongsModel,
    playing: Boolean,
    onClick: () -> Unit,
    onMore: (() -> Unit)?,
    modifier: Modifier = Modifier,
    current: Boolean = playing,
    trackNumber: Int? = null,
    showDuration: Boolean = false,
    showAlbum: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
) {
    val titleColor by animateColorAsState(
        targetValue = if (current) Immersive.Accent else Immersive.Text,
        label = "immersive song row title",
    )
    val subtitle = if (showAlbum && song.album.isNotBlank() && song.singer.isNotBlank()) {
        "${song.singer} · ${song.album}"
    } else {
        song.singer.ifBlank { song.album }
    }
    RowScaffold(
        title = song.title,
        subtitle = subtitle,
        onClick = onClick,
        onLongClick = onMore,
        modifier = modifier.alpha(if (song.playable) 1f else 0.45f),
        titleColor = titleColor,
        explicit = song.explicit,
        leading = {
            if (trackNumber != null) {
                Box(Modifier.width(TrackNumberWidth).height(RowCover), contentAlignment = Alignment.Center) {
                    if (current) {
                        Icon(
                            imageVector = if (playing) Icons.Rounded.GraphicEq else Icons.Rounded.PlayArrow,
                            contentDescription = stringResource(R.string.immersive_shell_now_playing),
                            tint = Immersive.Accent,
                            modifier = Modifier.size(22.dp),
                        )
                    } else {
                        Text(text = "$trackNumber", style = Immersive.Body.copy(color = Immersive.TextSecondary), maxLines = 1)
                    }
                }
            } else {
                Box(Modifier.size(RowCover)) {
                    ImmersiveCover(song.coverUri, Modifier.matchParentSize(), Immersive.CoverSmall)
                    if (playing) ImmersivePlayingBars(Modifier.align(Alignment.Center))
                }
            }
        },
        gapAfterLeading = if (trackNumber == null) 14.dp else Immersive.Gutter,
        trailingText = if (showDuration && song.durationMs > 0) formatDuration(song.durationMs) else null,
        onMore = onMore,
        trailing = trailing,
    )
}

/**
 * A generic row: a 52 dp cover (a circle when [round], for artists), a title, a subtitle and an
 * optional "more" button. For playlists, albums, artists, shows and library entries.
 *
 * @param imageUrl the cover; null/blank draws the placeholder glyph.
 * @param playing bars over the cover and the title in the accent (the playing context).
 * @param onMore opens the item's menu; null hides the button (and the long press).
 * @param trailing extra content before the more button (a pin, a download badge, a chevron).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ImmersiveListRow(
    title: String,
    subtitle: String?,
    imageUrl: String?,
    round: Boolean,
    onClick: () -> Unit,
    onMore: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    playing: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
) {
    val titleColor by animateColorAsState(
        targetValue = if (playing) Immersive.Accent else Immersive.Text,
        label = "immersive list row title",
    )
    RowScaffold(
        title = title,
        subtitle = subtitle.orEmpty(),
        onClick = onClick,
        onLongClick = onMore,
        modifier = modifier,
        titleColor = titleColor,
        explicit = false,
        leading = {
            Box(Modifier.size(RowCover)) {
                ImmersiveCover(
                    imageUrl,
                    Modifier.matchParentSize(),
                    if (round) CircleShape else Immersive.CoverSmall,
                )
                if (playing) ImmersivePlayingBars(Modifier.align(Alignment.Center))
            }
        },
        gapAfterLeading = 14.dp,
        trailingText = null,
        trailing = trailing,
        onMore = onMore,
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RowScaffold(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    modifier: Modifier,
    titleColor: Color,
    explicit: Boolean,
    leading: @Composable () -> Unit,
    gapAfterLeading: Dp,
    trailingText: String?,
    onMore: (() -> Unit)?,
    trailing: (@Composable () -> Unit)? = null,
) {
    val haptics = LocalHapticFeedback.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            // A mouse's right click opens the same menu as the long press (DeX, tablets).
            .secondaryClick(onLongClick)
            .combinedClickable(
                role = Role.Button,
                onClick = onClick,
                onLongClick = onLongClick?.let { long ->
                    {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        long()
                    }
                },
            )
            .padding(horizontal = Immersive.Gutter, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading()
        Spacer(Modifier.width(gapAfterLeading))
        Column(Modifier.weight(1f)) {
            ImmersiveSongTitle(title = title, explicit = explicit, style = RowTitle, color = titleColor)
            if (subtitle.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = Immersive.Subhead,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        trailing?.invoke()
        if (trailingText != null) {
            Spacer(Modifier.width(8.dp))
            Text(text = trailingText, style = Immersive.Caption)
        }
        if (onMore != null) ImmersiveMoreButton(onClick = onMore)
    }
}

/**
 * The ⋮ at the end of a row. Its circle is a touch target nobody sees, so it hangs [RowMoreOutset]
 * into the page gutter: the dots end at the row's content edge, mirroring the cover on the left,
 * rather than a whole button's width short of it — and the row's text gets that width back.
 */
@Composable
fun ImmersiveMoreButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = Immersive.TextSecondary,
    iconSize: Dp = 20.dp,
) {
    Box(
        modifier = modifier
            .hangIntoGutter(RowMoreOutset)
            .size(36.dp)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Rounded.MoreVert,
            contentDescription = stringResource(R.string.immersive_shell_more),
            tint = tint,
            modifier = Modifier.size(iconSize),
        )
    }
}

/**
 * Lays a trailing control out [outset] narrower than it draws, so it overhangs its row's end edge
 * by that much: for buttons whose glyph is far smaller than their touch target.
 */
fun Modifier.hangIntoGutter(outset: Dp): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val width = (placeable.width - outset.roundToPx()).coerceAtLeast(0)
    layout(width, placeable.height) { placeable.place(0, 0) }
}

/** 213000 ms -> "3:33". */
internal fun formatDuration(ms: Int): String {
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
