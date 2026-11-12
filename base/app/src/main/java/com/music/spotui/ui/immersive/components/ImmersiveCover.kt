/*
 * Adapted from BitChord (https://github.com/kushagrasinghx/BitChord), GPL-3.0-or-later,
 * sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/components/Common.kt
 * (and app/src/main/java/com/music/bitchord/ui/components/CoverPlaceholder.kt) at edc1e6f. Modified for Myspoti, 2026-10.
 */
package com.music.spotui.ui.immersive.components

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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
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
 * The plate is a fixed square so only the bars move. They step [PLAYING_BARS_FPS] times a second
 * ([com.music.spotui.ui.components.rememberFrameClock], on screen only): three endless animations
 * redrew the window at the screen's top rate (120 Hz) for a 24 dp mark whenever its row was shown.
 */
@Composable
fun ImmersivePlayingBars(modifier: Modifier = Modifier) {
    val reduceMotion = rememberReduceMotion()
    val clock = com.music.spotui.ui.components.rememberFrameClock(running = !reduceMotion, fps = PLAYING_BARS_FPS)
    val barHeights = listOf(0.38f, 0.78f, 0.52f).mapIndexed { index, minimum ->
        // Under "reduce motion" the bars rest at their low mark instead of dancing.
        if (reduceMotion) return@mapIndexed ({ minimum })
        ({ playingBarHeight(index, minimum, clock.value / 1_000_000L) })
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
                        // Drawn at its height from the bottom: the dance does not relayout (and
                        // recompose) the bars sixty times a second, and the corners stay round.
                        .drawBehind {
                            val barHeight = size.height * height()
                            drawRoundRect(
                                color = Color.White,
                                topLeft = Offset(0f, size.height - barHeight),
                                size = Size(size.width, barHeight),
                                cornerRadius = CornerRadius(2.dp.toPx()),
                            )
                        },
                )
            }
        }
    }
}

/** The bars' steps a second: the eye sees no difference on a mark this small. */
private const val PLAYING_BARS_FPS = 12

/**
 * Bar [index]'s height at [nowMs]: up from [minimum] to full and back, each bar at its own pace
 * (the keyframes the infinite animation had, played forth and back).
 */
internal fun playingBarHeight(index: Int, minimum: Float, nowMs: Long): Float {
    val length = 520 + index * 130
    val peakAt = 260 + index * 50
    val cycle = (nowMs % (2L * length)).toInt()
    val t = if (cycle < length) cycle else 2 * length - cycle
    val rise = if (t <= peakAt) t.toFloat() / peakAt else (length - t).toFloat() / (length - peakAt)
    return minimum + (1f - minimum) * rise.coerceIn(0f, 1f)
}
