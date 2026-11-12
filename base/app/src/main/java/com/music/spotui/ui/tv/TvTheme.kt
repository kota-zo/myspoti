/* SPDX-License-Identifier: GPL-3.0-only */
// Parts adapted from Android's Jetcaster sample (https://github.com/android/compose-samples), Copyright 2024 The Android Open Source Project, Apache License 2.0.
// Parts adapted from Android's JetStream sample (https://github.com/android/tv-samples), Copyright 2023 Google LLC, Apache License 2.0.
package com.music.spotui.ui.tv

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import com.music.spotui.ui.look.rememberArtworkColors

/**
 * The spacing of the TV screens, after Jetcaster TV's JetcasterAppDefaults: overscan margins (the
 * edges a TV may crop), the gaps between items and sections, and the card and thumbnail sizes.
 */
internal object TvSpace {
    /** The default overscan margin, for shelves. */
    val start = 48.dp
    /** The margin of a detail page (a list, a podcast, the player). */
    val detailStart = 80.dp
    val tiny = 4.dp
    val small = tiny * 2
    val default = small * 2
    val medium = default + tiny
    val large = medium * 2
    /** Between two shelves. */
    val section = large
    /** Between two cards of a shelf. */
    val item = medium
    /** Between the two columns of a detail page. */
    val twoColumn = large + default
    val cardLarge = 268.dp
    val cardMedium = 196.dp
    val cardSmall = 124.dp
    /** The cover of a list or a podcast, and of the player. */
    val thumbnail = 266.dp
}

/** Text styles after Jetcaster TV's typography (sizes and line heights), in the system font. */
internal object TvType {
    private val base = TextStyle(color = TvTitle)
    val displayMedium = base.copy(fontSize = 42.sp, lineHeight = 52.sp, fontWeight = FontWeight.Bold)
    val headlineLarge = base.copy(fontSize = 32.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold)
    val headlineSmall = base.copy(fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold)
    val titleLarge = base.copy(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold)
    val titleMedium = base.copy(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium)
    val titleSmall = base.copy(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium)
    val bodyLarge = base.copy(fontSize = 16.sp, lineHeight = 24.sp)
    val bodyMedium = base.copy(fontSize = 14.sp, lineHeight = 20.sp)
    val bodySmall = base.copy(fontSize = 12.sp, lineHeight = 16.sp, color = TvSubtle)
    /** JetStream's shadow under a title laid over a picture. */
    val overImage = Shadow(Color.Black.copy(alpha = 0.5f), Offset(2f, 4f), 2f)
}

/** The focus look of JetStream: a 3 dp border in the light surface colour. */
internal val TvFocusBorder = 3.dp
/** JetStream's light surface: a focused tab, button or row turns this colour... */
internal val TvOnSurface = Color(0xFFE5E1E6)
/** ...with its text in this one. */
internal val TvInverseOnSurface = Color(0xFF1B1B1F)

/** JetStream's pairs of Material 300 colours, for the radial gradient behind a missing cover. */
private val GradientPairs = listOf(
    Color(0xFFF3A397) to Color(0xFFF8EE94), // Coral, Light yellow
    Color(0xFFE57373) to Color(0xFF90A4AE), // Red, Blue grey
    Color(0xFFF06292) to Color(0xFFE0E0E0), // Pink, Grey
    Color(0xFFBA68C8) to Color(0xFFA1887F), // Purple, Brown
    Color(0xFF9575CD) to Color(0xFFFF8A65), // Deep purple, Deep orange
    Color(0xFF7986CB) to Color(0xFFFFB74D), // Indigo, Orange
    Color(0xFF64B5F6) to Color(0xFFFFD54F), // Blue, Amber
    Color(0xFF4FC3F7) to Color(0xFFFFF176), // Light blue, Yellow
    Color(0xFF4DD0E1) to Color(0xFFDCE775), // Cyan, Lime
    Color(0xFF4DB6AC) to Color(0xFFAED581), // Teal, Light green
    Color(0xFF81C784) to Color(0xFFF3A397), // Green, Coral
)

/**
 * The two colours of a missing cover, the same for the same [seed] (JetStream picks them at random),
 * darkened for a black screen.
 */
internal fun tvSeedColors(seed: String): Pair<Color, Color> {
    val (a, b) = GradientPairs[(seed.hashCode() and 0x7fffffff) % GradientPairs.size]
    return lerp(a, Color.Black, 0.35f) to lerp(b, Color.Black, 0.62f)
}

/** The placeholder of a missing cover: JetStream's gradient of a pair chosen by [seed], diagonal (a radial one bands). */
internal fun tvSeedBrush(seed: String): Brush {
    val (a, b) = tvSeedColors(seed)
    return Brush.linearGradient(listOf(a, b))
}

/** One colour of the cover at [url] (its palette), or the seeded one while there is no cover. */
@Composable
internal fun rememberTvTint(url: String, seed: String): Color {
    val palette = rememberArtworkColors(url.ifBlank { null })
    return if (url.isBlank()) tvSeedColors(seed).first else palette.colors.firstOrNull() ?: TvAccent
}

/**
 * A page over its cover, after Jetcaster TV's BackgroundContainer: the cover's colour washes in from
 * the top right; the cover itself, blurred where the TV can (Android 12+) and faint, lies under a
 * black scrim from the bottom left (after ImageBackgroundRadialGradientScrim), so text stays legible.
 */
@OptIn(ExperimentalGlideComposeApi::class)
@Composable
internal fun TvBackdrop(imageUrl: String, seed: String, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val tint = rememberTvTint(imageUrl, seed)
    Box(modifier) {
        // The colours run up under the floating bar of tabs, to the top of the screen.
        Box(Modifier.fillMaxSize().bleedUp(TvBarHeight).background(TvBg)) {
            if (imageUrl.isNotBlank()) {
                GlideImage(
                    model = imageUrl, contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().alpha(0.32f).then(if (Build.VERSION.SDK_INT >= 31) Modifier.blur(60.dp) else Modifier),
                )
            }
            Box(
                Modifier.fillMaxSize().drawWithCache {
                    val wash = Brush.radialGradient(
                        listOf(tint.copy(alpha = 0.5f), tint.copy(alpha = 0.16f), Color.Transparent),
                        center = Offset(size.width, 0f), radius = size.width * 0.9f,
                    )
                    // Jetcaster's scrim, linear here: a wide radial one bands on an 8-bit TV panel.
                    val scrim = Brush.linearGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f)),
                        start = Offset(size.width * 0.6f, 0f), end = Offset(0f, size.height))
                    onDrawBehind {
                        drawRect(wash)
                        drawRect(scrim)
                    }
                },
            )
        }
        content()
    }
}

/** The height the floating bar of tabs keeps above a page. */
internal val TvBarHeight = 72.dp

/** Laid out [extra] taller, reaching up by [extra] above its place. */
private fun Modifier.bleedUp(extra: Dp): Modifier = layout { measurable, constraints ->
    val px = extra.roundToPx()
    val tall = if (constraints.hasBoundedHeight) constraints.copy(minHeight = constraints.minHeight + px, maxHeight = constraints.maxHeight + px) else constraints
    val placeable = measurable.measure(tall)
    layout(placeable.width, (placeable.height - px).coerceAtLeast(0)) { placeable.place(0, -px) }
}
