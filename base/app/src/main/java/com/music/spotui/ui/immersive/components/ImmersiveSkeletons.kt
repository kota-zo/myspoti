/*
 * Adapted from BitChord (https://github.com/kushagrasinghx/BitChord), GPL-3.0-or-later,
 * sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/components/Skeletons.kt at edc1e6f. Modified for Myspoti, 2026-10.
 */
package com.music.spotui.ui.immersive.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import com.music.spotui.ui.components.leavingPlaceholder
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.music.spotui.ui.immersive.Immersive
import com.music.spotui.ui.look.rememberReduceMotion

/*
 * Grey stand-ins for content still on the wire, laid out to the same metrics as the real rows and
 * cards so nothing jumps when the data lands.
 */

/** How long one highlight sweep takes to cross a placeholder. */
private const val SHIMMER_PERIOD_MS = 1400

private val BlockShape = RoundedCornerShape(6.dp)
private val LineShape = RoundedCornerShape(4.dp)

// Ragged widths, so a run of rows reads as text rather than as a barcode.
private val TitleWidths = listOf(0.68f, 0.46f, 0.58f, 0.74f, 0.52f)
private val SubtitleWidths = listOf(0.34f, 0.44f, 0.27f, 0.38f, 0.31f)

/**
 * One placeholder block, with a highlight sweeping across it.
 *
 * The sweep is read inside the draw block rather than the composable body: a screenful of these
 * would otherwise recompose on every animation frame, and all any of them needs per frame is a
 * fresh gradient.
 */
@Composable
fun ImmersiveShimmerBox(modifier: Modifier = Modifier, shape: Shape = BlockShape) {
    val base = Immersive.Raised
    val highlight = Immersive.Text.copy(alpha = 0.10f).compositeOver(base)
    // Under "reduce motion" the band stays off the block: a flat placeholder, nothing endless running.
    val sweep = if (rememberReduceMotion()) null else rememberInfiniteTransition(label = "immersive skeleton").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(SHIMMER_PERIOD_MS, easing = LinearEasing)),
        label = "sweep",
    )
    Box(
        modifier
            .clip(shape)
            .drawWithCache {
                // The band travels from fully off one edge to fully off the other, which leaves a
                // beat of flat grey between passes rather than a highlight parked on the block.
                val band = size.width * 0.5f
                val startX = -band + (sweep?.value ?: 0f) * (size.width + band * 2)
                val brush = Brush.horizontalGradient(
                    colors = listOf(base, highlight, base),
                    startX = startX,
                    endX = startX + band,
                )
                onDrawBehind { drawRect(brush) }
            },
    )
}

/** A placeholder for one line of text, sized as a fraction of its parent. */
@Composable
private fun SkeletonLine(fraction: Float, height: Dp, modifier: Modifier = Modifier) {
    ImmersiveShimmerBox(
        modifier = modifier.fillMaxWidth(fraction).height(height),
        shape = LineShape,
    )
}

/** Stands in for a [SectionHeader]. Only the title line is drawn: most shelves have no subtitle. */
@Composable
fun ImmersiveSectionHeaderSkeleton(index: Int = 0) {
    Column(Modifier.padding(horizontal = Immersive.Gutter, vertical = 10.dp)) {
        SkeletonLine(fraction = TitleWidths[index % TitleWidths.size] * 0.7f, height = 20.dp)
    }
}

/**
 * Stands in for one [ImmersiveSongRow], down to the 52 dp cover and the gutter. [circular] matches
 * rows of artists, drawn as circles.
 */
@Composable
fun ImmersiveSongRowSkeleton(index: Int = 0, circular: Boolean = false, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Immersive.Gutter, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ImmersiveShimmerBox(Modifier.size(52.dp), if (circular) CircleShape else Immersive.CoverSmall)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            SkeletonLine(fraction = TitleWidths[index % TitleWidths.size], height = 14.dp)
            Spacer(Modifier.height(7.dp))
            SkeletonLine(fraction = SubtitleWidths[index % SubtitleWidths.size], height = 11.dp)
        }
    }
}

/** A run of track-row placeholders, for search hits and release track lists. */
fun LazyListScope.immersiveSongListSkeleton(
    count: Int = 8,
    keyPrefix: String = "skeleton:song",
    circular: Boolean = false,
    alpha: Float = 1f,
) {
    items(count, key = { "$keyPrefix:$it" }) { index ->
        Box(Modifier.graphicsLayer { this.alpha = alpha }) {
            ImmersiveSongRowSkeleton(index = index, circular = circular)
        }
    }
}

/**
 * The lead shelf on Home: near-page-width cards that page sideways. Built on a [LazyRow] with
 * scrolling off rather than a plain Row, so a card running past the right edge is measured and
 * clipped exactly as the real shelf's is — which is the whole point of a skeleton.
 */
@Composable
fun ImmersiveHeroShelfSkeleton() {
    Column(Modifier.padding(bottom = 26.dp)) {
        ImmersiveSectionHeaderSkeleton()
        BoxWithConstraints {
            val cardWidth = immersiveHeroCardWidth(maxWidth)
            LazyRow(
                contentPadding = PaddingValues(horizontal = Immersive.Gutter),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                userScrollEnabled = false,
            ) {
                items(2) {
                    ImmersiveShimmerBox(
                        modifier = Modifier.width(cardWidth).aspectRatio(ImmersiveHeroCardRatio),
                        shape = Immersive.Card,
                    )
                }
            }
        }
    }
}

/** The compact carousel of square cards used by every shelf below the first. */
@Composable
fun ImmersiveShelfSkeleton(
    index: Int = 0,
    cardWidth: Dp = ImmersiveShelfCardWidth,
    cardShape: Shape = Immersive.CoverMedium,
) {
    Column(Modifier.padding(bottom = 26.dp)) {
        ImmersiveSectionHeaderSkeleton(index = index)
        LazyRow(
            contentPadding = PaddingValues(horizontal = Immersive.Gutter),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            userScrollEnabled = false,
        ) {
            items(3) { card ->
                Column(Modifier.width(cardWidth)) {
                    ImmersiveShimmerBox(
                        modifier = Modifier.width(cardWidth).aspectRatio(1f),
                        shape = cardShape,
                    )
                    Spacer(Modifier.height(12.dp))
                    SkeletonLine(fraction = TitleWidths[card % TitleWidths.size], height = 13.dp)
                    Spacer(Modifier.height(6.dp))
                    SkeletonLine(fraction = SubtitleWidths[card % SubtitleWidths.size], height = 11.dp)
                }
            }
        }
    }
}

/** Home while the first page of shelves is still loading; it fades out as the shelves fade in. */
fun LazyListScope.immersiveFeedSkeleton(shelves: Int = 3, firstIsHero: Boolean = true) {
    if (firstIsHero) {
        item(key = "skeleton:hero") { Box(leavingPlaceholder()) { ImmersiveHeroShelfSkeleton() } }
        items(shelves - 1, key = { "skeleton:shelf:$it" }) { index ->
            Box(leavingPlaceholder()) { ImmersiveShelfSkeleton(index = index + 1) }
        }
    } else {
        items(shelves, key = { "skeleton:shelf:$it" }) { index ->
            Box(leavingPlaceholder()) { ImmersiveShelfSkeleton(index = index) }
        }
    }
}

/** The signed-in library: saved collections, then a run of rows. */
fun LazyListScope.immersiveLibrarySkeleton() {
    item(key = "skeleton:library:shelf") { ImmersiveShelfSkeleton() }
    item(key = "skeleton:library:header") { ImmersiveSectionHeaderSkeleton(index = 1) }
    immersiveSongListSkeleton(count = 7, keyPrefix = "skeleton:library:song")
}

/** The Play / Shuffle pair, which only appears once there is something to play. */
@Composable
fun ImmersivePlayShuffleSkeleton(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            // Matches the real pair's inset, height and corner, so the header above them does not
            // shift when the track list lands.
            .padding(horizontal = Immersive.Gutter),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ImmersiveShimmerBox(Modifier.weight(1f).height(ImmersiveActionButtonHeight), Immersive.Button)
        ImmersiveShimmerBox(Modifier.weight(1f).height(ImmersiveActionButtonHeight), Immersive.Button)
    }
}

/**
 * An album, playlist or artist page below its header — the header itself is drawn from what the
 * row that was tapped already knew, so it never waits.
 */
fun LazyListScope.immersiveDetailSkeleton(isArtist: Boolean) {
    item(key = "skeleton:detail:actions") {
        Column(Modifier.graphicsLayer { alpha = 0.4f }) {
            ImmersivePlayShuffleSkeleton()
            Spacer(Modifier.height(if (isArtist) 22.dp else 20.dp))
        }
    }
    if (isArtist) {
        item(key = "skeleton:detail:top") {
            Column(Modifier.graphicsLayer { alpha = 0.4f }) {
                ImmersiveSectionHeaderSkeleton()
                repeat(4) { index -> ImmersiveSongRowSkeleton(index) }
            }
        }
        item(key = "skeleton:detail:sections") {
            Box(Modifier.graphicsLayer { alpha = 0.4f }) {
                ImmersiveShelfSkeleton(index = 2)
            }
        }
    } else {
        immersiveSongListSkeleton(count = 8, keyPrefix = "skeleton:detail:song", alpha = 0.4f)
    }
}
