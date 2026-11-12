/*
 * Adapted from BitChord (https://github.com/kushagrasinghx/BitChord), GPL-3.0-or-later,
 * app/src/main/java/com/music/bitchord/ui/screens/DetailScreen.kt (PageBackground, PlayCircle,
 * CompactSongRow, TopReleaseCard) at edc1e6f. Modified for Myspoti, 2026-10.
 */
package com.music.spotui.ui.immersive.screens

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.myspoti.engine.R
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import com.music.spotui.data.entity.AlbumsModel
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.ui.components.spotifyImageUrl
import com.music.spotui.ui.immersive.Immersive
import com.music.spotui.ui.immersive.components.ImmersiveCover
import com.music.spotui.ui.immersive.components.ImmersiveMoreButton
import com.music.spotui.ui.immersive.components.ImmersivePlayingBars
import com.music.spotui.ui.immersive.components.ImmersiveSongTitle
import com.music.spotui.ui.immersive.components.immersiveClickable
import com.music.spotui.ui.immersive.components.immersiveThumbnailBorder
import kotlin.math.roundToInt

/** How far the artist photo is blurred over its foot, where it dissolves into the page. */
private val SoftFootBlur = 48.dp

/** Where the blurred copy starts to show and where it has fully taken over. */
private val SoftFootMask = Brush.verticalGradient(
    0.35f to Color.Transparent,
    0.75f to Color.Black,
)

/**
 * Where the top of the artwork currently is.
 *
 * The list is the one being scrolled; the background only has to agree with it. While the header
 * is item zero and on screen, how far it has been scrolled off the top is exactly the offset the
 * picture behind it needs. Once it isn't, the picture parks two artwork-heights up — far enough
 * that no part of it comes back down.
 */
private fun LazyListState.headerTop(artHeightPx: Float): Float =
    if (firstVisibleItemIndex == 0) -firstVisibleItemScrollOffset.toFloat() else -artHeightPx * 2f

/**
 * Everything on an artist page that is colour rather than words: the photo, full bleed up under
 * the bar, with its foot dissolved into the page. A layer of its own so the list can be scrolled
 * over it rather than carry it: it follows the scroll (read in the offset block, so a scroll moves
 * it without recomposing anything) instead of being scrolled.
 *
 * A blurred copy of the photo is faded in over the lower half, so the picture loses its detail
 * before it loses its colour — a merge rather than a fade to flat black. Drawn once: nothing
 * about it changes as the page moves, so it is rasterised on the first frame and composited after.
 */
@OptIn(ExperimentalGlideComposeApi::class)
@Composable
internal fun ArtistBackdrop(
    imageUrl: String?,
    artHeight: Dp,
    listState: LazyListState,
    modifier: Modifier = Modifier,
) {
    val model = spotifyImageUrl(imageUrl)
    val artHeightPx = with(androidx.compose.ui.platform.LocalDensity.current) { artHeight.toPx() }
    val softenFoot = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    Box(modifier.clipToBounds()) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(artHeight)
                .offset { IntOffset(0, listState.headerTop(artHeightPx).roundToInt()) },
        ) {
            Box(Modifier.matchParentSize().background(Immersive.Raised))
            if (model != null) {
                GlideImage(
                    model = model,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize(),
                )
                if (softenFoot) {
                    GlideImage(
                        model = model,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .matchParentSize()
                            // Offscreen so the mask cuts the blurred result rather than each draw beneath it.
                            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                            .drawWithContent {
                                drawContent()
                                drawRect(SoftFootMask, blendMode = BlendMode.DstIn)
                            }
                            // Rectangle keeps the edges clamped to the picture's own colour
                            // rather than fading to transparent at the foot.
                            .blur(SoftFootBlur, BlurredEdgeTreatment.Rectangle),
                    )
                }
            }
            // Ends on the page colour at full strength, so there is no edge left where the artwork
            // stops. Eased rather than run straight: a gradient that changes slope at a stop shows
            // a line at that stop, however close the colours either side of it are. The darkening
            // at the top keeps the bar's controls legible over a bright photo.
            Box(
                Modifier
                    .matchParentSize()
                    .background(
                        Brush.verticalGradient(
                            0.00f to Color.Black.copy(alpha = 0.35f),
                            0.22f to Color.Transparent,
                            0.45f to Color.Transparent,
                            0.65f to Immersive.Page.copy(alpha = 0.30f),
                            0.82f to Immersive.Page.copy(alpha = 0.72f),
                            0.94f to Immersive.Page.copy(alpha = 0.95f),
                            1.00f to Immersive.Page,
                        ),
                    ),
            )
        }
    }
}

/**
 * The artist page's Play: a filled circle with the triangle punched out of it, so the page shows
 * through (two bars while it plays).
 */
@Composable
internal fun ArtistPlayCircle(
    playing: Boolean,
    onClick: () -> Unit,
    size: Dp,
    iconScale: Float,
    containerColor: Color,
) {
    val description = stringResource(if (playing) R.string.immersive_detail_pause else R.string.immersive_shell_play)
    Box(
        modifier = Modifier
            .immersiveClickable(onClick = onClick, pressedScale = 0.94f)
            .size(size)
            .clip(CircleShape)
            // Offscreen so the clear below cuts the circle drawn under it, not whatever the page has behind.
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawCircle(containerColor)
                val box = this.size.minDimension * iconScale
                val unit = box / 24f
                val origin = Offset((this.size.width - box) / 2f, (this.size.height - box) / 2f)
                if (playing) {
                    for (x in listOf(6.5f, 14f)) {
                        drawRoundRect(
                            color = Color.Black,
                            topLeft = Offset(origin.x + x * unit, origin.y + 4.5f * unit),
                            size = Size(3.5f * unit, 15f * unit),
                            cornerRadius = CornerRadius(1.2f * unit),
                            blendMode = BlendMode.Clear,
                        )
                    }
                } else {
                    val triangle = Path().apply {
                        moveTo(origin.x + 6.8f * unit, origin.y + 4.8f * unit)
                        lineTo(origin.x + 19.2f * unit, origin.y + 12f * unit)
                        lineTo(origin.x + 6.8f * unit, origin.y + 19.2f * unit)
                        close()
                    }
                    drawPath(triangle, Color.Black, blendMode = BlendMode.Clear)
                    // The same round-joined pen the icon is drawn with.
                    drawPath(
                        triangle,
                        Color.Black,
                        style = Stroke(width = 2f * unit, cap = StrokeCap.Round, join = StrokeJoin.Round),
                        blendMode = BlendMode.Clear,
                    )
                }
            }
            .semantics { contentDescription = description },
    )
}

/**
 * A row of the artist's top songs: compact, no swipe, so the horizontal paging gestures stay
 * unambiguous. [subtitle] is the play count, or the artist when there is none.
 */
@Composable
internal fun ArtistCompactSongRow(
    song: SongsModel,
    subtitle: String,
    current: Boolean,
    playing: Boolean,
    onClick: () -> Unit,
    onMore: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .immersiveClickable(onClick = onClick, onLongClick = onMore, pressedScale = 0.98f)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(48.dp)) {
            ImmersiveCover(song.coverUri, Modifier.matchParentSize(), Immersive.CoverSmall)
            if (current && playing) ImmersivePlayingBars(Modifier.align(Alignment.Center))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            ImmersiveSongTitle(
                title = song.title,
                explicit = song.explicit,
                style = Immersive.Body.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium),
                color = if (current) Immersive.Accent else Immersive.Text,
            )
            Text(text = subtitle, style = Immersive.Subhead, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        ImmersiveMoreButton(onClick = onMore)
    }
}

/**
 * The artist page's top-release card: sleeve, what it is and when, and its title, in a rounded
 * veil the width of the page.
 */
@Composable
internal fun ArtistTopReleaseCard(
    album: AlbumsModel,
    label: String,
    detail: String,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(28.dp)
    val coverShape = RoundedCornerShape(12.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Immersive.Gutter)
            .padding(bottom = 22.dp)
            .immersiveClickable(onClick = onClick, pressedScale = 0.98f)
            // A light veil rather than a dark panel, which reads as a hole in a black page.
            .clip(shape)
            .background(Color.White.copy(alpha = 0.07f), shape)
            .border(1.dp, Color.White.copy(alpha = 0.10f), shape)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ImmersiveCover(
            url = album.coverUri,
            modifier = Modifier.size(88.dp).immersiveThumbnailBorder(coverShape),
            shape = coverShape,
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(text = label, style = Immersive.Subhead, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(text = album.name, style = Immersive.Headline, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (detail.isNotBlank()) {
                Text(text = detail, style = Immersive.Subhead, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** The "Liked songs" row: a heart on the Liked Songs gradient, the count under it. */
@Composable
internal fun ArtistLikedRow(title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = Immersive.Gutter, vertical = 8.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(52.dp)
                .clip(Immersive.CoverSmall)
                .background(Brush.linearGradient(listOf(Color(0xFF450AF5), Color(0xFF8E8EE5), Color(0xFFC4EFD9)))),
        ) {
            Icon(Icons.Rounded.Favorite, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(text = title, style = Immersive.Body.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium), maxLines = 1)
            Text(text = subtitle, style = Immersive.Subhead, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
