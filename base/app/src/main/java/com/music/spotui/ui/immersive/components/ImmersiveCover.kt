/*
 * Adapted from BitChord (https://github.com/kushagrasinghx/BitChord), GPL-3.0-or-later,
 * sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/components/Common.kt
 * (and app/src/main/java/com/music/bitchord/ui/components/CoverPlaceholder.kt) at edc1e6f. Modified for Myspoti, 2026-10.
 */
package com.music.spotui.ui.immersive.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.bumptech.glide.integration.compose.CrossFade
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import com.music.spotui.ui.components.spotifyImageUrl
import com.music.spotui.ui.immersive.Immersive
import com.music.spotui.ui.look.rememberReduceMotion

/** The hairline every cover carries, so a cover with light edges does not dissolve into the page. */
fun Modifier.immersiveThumbnailBorder(shape: Shape): Modifier =
    this.border(width = 1.dp, color = Color.White.copy(alpha = 0.12f), shape = shape)

/**
 * A cover (album, playlist, artist, show) from [url], clipped to [shape] with the thumbnail
 * hairline and a [Immersive.Raised] fill that shows while it loads. [url] may be a
 * `spotify:image:` uri (see `spotifyImageUrl`); null or blank draws [placeholderIcon] instead.
 *
 * The size comes from [modifier]: `ImmersiveCover(url, Modifier.size(52.dp))`,
 * `Modifier.fillMaxWidth().aspectRatio(1f)`. Use `CircleShape` for artists.
 */
@OptIn(ExperimentalGlideComposeApi::class)
@Composable
fun ImmersiveCover(
    url: String?,
    modifier: Modifier = Modifier,
    shape: Shape = Immersive.CoverMedium,
    contentDescription: String? = null,
    border: Boolean = true,
    placeholderIcon: ImageVector = Icons.Rounded.MusicNote,
) {
    val model = spotifyImageUrl(url)
    Box(
        modifier = modifier
            .clip(shape)
            .background(Immersive.Raised)
            .then(if (border) Modifier.immersiveThumbnailBorder(shape) else Modifier),
    ) {
        if (model == null) {
            ImmersiveCoverPlaceholder(icon = placeholderIcon, modifier = Modifier.fillMaxSize())
        } else {
            GlideImage(
                model = model,
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                transition = CrossFade,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * What stands in for a cover that is not there: a playlist with nothing on it yet, an album whose
 * files carry no picture. A soft diagonal gradient off [base] with a glyph for what the thing is.
 * Callers draw it behind the image, so a cover that loads late simply covers it.
 */
@Composable
fun ImmersiveCoverPlaceholder(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    base: Color = Immersive.Raised,
    tint: Color = Immersive.Text,
    /** The glyph's share of the tile. */
    iconFraction: Float = 0.36f,
) {
    Box(
        modifier = modifier.background(Brush.linearGradient(listOf(lerp(base, tint, 0.16f), base))),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint.copy(alpha = 0.6f),
            modifier = Modifier.fillMaxSize(iconFraction),
        )
    }
}

/**
 * Three bars dancing on a dark plate, laid over a cover to mark the song that is playing.
 * The plate is a fixed square so only the bars move.
 */
@Composable
fun ImmersivePlayingBars(modifier: Modifier = Modifier) {
    val reduceMotion = rememberReduceMotion()
    val barHeights = listOf(0.38f, 0.78f, 0.52f).mapIndexed { index, minimum ->
        // Under "reduce motion" the bars rest at their low mark instead of dancing.
        if (reduceMotion) return@mapIndexed ({ minimum })
        val transition = rememberInfiniteTransition(label = "immersive playing bar $index")
        val height = transition.animateFloat(
            initialValue = minimum,
            targetValue = 1f - (index * 0.12f),
            animationSpec = infiniteRepeatable(
                animation = keyframes {
                    durationMillis = 520 + index * 130
                    minimum at 0
                    1f at (260 + index * 50)
                    minimum at durationMillis
                },
                repeatMode = RepeatMode.Reverse,
            ),
            label = "immersive playing bar height $index",
        )
        ({ height.value })
    }
    Box(
        modifier = modifier
            .size(24.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(Color.Black.copy(alpha = 0.52f)),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier.height(14.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            barHeights.forEach { height ->
                Box(
                    Modifier
                        .width(3.dp)
                        .height(14.dp)
                        // Scaled in the layer, from the bottom: the dance does not relayout
                        // (and recompose) the bars sixty times a second.
                        .graphicsLayer {
                            scaleY = height()
                            transformOrigin = TransformOrigin(0.5f, 1f)
                        }
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color.White),
                )
            }
        }
    }
}
