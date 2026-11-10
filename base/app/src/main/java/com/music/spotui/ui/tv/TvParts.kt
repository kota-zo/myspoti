/*
 * Adapted from Apple Music TV (https://github.com/JObersi10/apple-music-tv), GPL-3.0,
 * android/app/src/main/java/com/applemusicktv/ui/components/TopNavBar.kt, AlbumCard.kt,
 * SpotlightCards.kt (GradientCard) and Skeleton.kt at 7e5c594.
 * Modified for Myspoti, 2026-11.
 *
 * Changes: built on Compose foundation instead of androidx.tv (no new library on weak TVs): the
 * focus scale, white halo and hairline border of tv-material's Card are drawn by hand. Myspoti's
 * accent, Material icons (no SF Symbols), Glide for the covers, no motion artwork, no beat-reactive
 * bars, no settings tab.
 */
package com.music.spotui.ui.tv

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage

internal val TvBg = Color(0xFF000000)
internal val TvAccent = Color(0xFFFF2D78)
internal val TvTitle = Color(0xFFF2F2F5)
internal val TvSubtle = Color(0xFF8A8A8E)

/** The tabs of the floating bar, in their order. */
internal enum class TvTab { Home, Library, Search, NowPlaying }

/**
 * The focus look of a card: a soft scale, a white halo around it and a hairline white border,
 * as tv-material's Card draws them. [onFocus] hears the focus too.
 */
@Composable
internal fun Modifier.tvCard(
    shape: RoundedCornerShape,
    corner: Dp,
    focusedScale: Float = 1.08f,
    borderAlpha: Float = 0.55f,
    onFocus: (Boolean) -> Unit = {},
    onClick: () -> Unit,
): Modifier {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) focusedScale else 1f, tween(150), label = "cardScale")
    val halo by animateFloatAsState(if (focused) 1f else 0f, tween(150), label = "cardHalo")
    return this
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .drawBehind {
            if (halo <= 0f) return@drawBehind
            // A glow of 18 dp: three widening rounded rects, fading out.
            val glow = 18.dp.toPx()
            for (i in 1..3) {
                val g = glow * i / 3f
                drawRoundRect(
                    Color.White.copy(alpha = 0.07f * halo * (4 - i) / 3f),
                    topLeft = Offset(-g, -g), size = Size(size.width + 2 * g, size.height + 2 * g),
                    cornerRadius = CornerRadius(corner.toPx() + g),
                )
            }
        }
        .border(1.5.dp, Color.White.copy(alpha = borderAlpha * halo), shape)
        .clip(shape)
        .onFocusChanged { focused = it.isFocused || it.hasFocus; onFocus(focused) }
        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
}

/** A focusable round or pill control with a light fill when focused. */
@Composable
internal fun Modifier.tvPill(
    shape: RoundedCornerShape = RoundedCornerShape(50),
    base: Color = Color.Transparent,
    focusedFill: Color = Color(0x33FFFFFF),
    focusedScale: Float = 1.1f,
    onClick: () -> Unit,
): Modifier {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) focusedScale else 1f, tween(150), label = "pillScale")
    val fill by animateColorAsState(if (focused) focusedFill else base, tween(150), label = "pillFill")
    return this
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .clip(shape)
        .background(fill)
        .onFocusChanged { focused = it.isFocused }
        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
}

/** The floating pill of tabs at the top; the white pill marks the open tab. */
@Composable
internal fun TvTopBar(selected: TvTab, labels: Map<TvTab, String>, onSelect: (TvTab) -> Unit, modifier: Modifier = Modifier) {
    var barFocused by remember { mutableStateOf(false) }
    // On Now Playing the bar fades away until the remote reaches it.
    val alpha by animateFloatAsState(if (selected == TvTab.NowPlaying && !barFocused) 0f else 1f, tween(500), label = "barAlpha")
    Box(
        modifier.fillMaxWidth().onFocusChanged { barFocused = it.hasFocus }.graphicsLayer { this.alpha = alpha }.padding(vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier.clip(RoundedCornerShape(50)).background(Color(0xE61C1C1E))
                .background(Brush.verticalGradient(0f to Color(0x14FFFFFF), 0.5f to Color.Transparent))
                .padding(horizontal = 6.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            TvTab.entries.forEach { tab ->
                val isSelected = tab == selected
                var focused by remember { mutableStateOf(false) }
                val bg by animateColorAsState(
                    when { isSelected -> Color.White; focused -> Color(0xFF2C2C2E); else -> Color.Transparent }, tween(180), label = "tabBg")
                val fg by animateColorAsState(if (isSelected) Color.Black else if (focused) Color.White else Color(0xFF9A9A9A), tween(180), label = "tabFg")
                Box(
                    Modifier.clip(RoundedCornerShape(50)).background(bg)
                        .onFocusChanged { focused = it.isFocused }
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onSelect(tab) }
                        .padding(horizontal = 18.dp, vertical = 8.dp),
                ) {
                    Text(labels[tab].orEmpty(), fontSize = 15.sp, color = fg,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium)
                }
            }
        }
    }
}

/** A square cover with its title and subtitle under it, for shelves and grids. */
@Composable
internal fun TvAlbumCard(title: String, subtitle: String, coverUrl: String, size: Dp, round: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val corner = if (round) size / 2 else 12.dp
    val shape = RoundedCornerShape(corner)
    Column(modifier.width(size)) {
        TvCover(coverUrl, Modifier.fillMaxWidth().aspectRatio(1f).tvCard(shape, corner, onClick = onClick), shape)
        Column(Modifier.padding(horizontal = 4.dp, vertical = 8.dp)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = TvTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotBlank()) {
                Text(subtitle, fontSize = 12.sp, color = TvSubtle, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}

/** A big square card whose title sits on a tinted scrim over the cover (the lead shelf of Home). */
@Composable
internal fun TvGradientCard(title: String, subtitle: String, coverUrl: String, width: Dp, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    // A dark scrim, not the cover's own colour: a palette read for every card is too much for a weak TV.
    val tint = Color(0xFF101012)
    Box(modifier.width(width).aspectRatio(1f).tvCard(shape, 16.dp, focusedScale = 1.06f, borderAlpha = 0.7f, onClick = onClick)) {
        TvCover(coverUrl, Modifier.fillMaxSize(), shape)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.4f to Color.Transparent, 1f to tint.copy(alpha = 0.92f))))
        Column(Modifier.align(Alignment.BottomStart).padding(14.dp)) {
            Text(title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotBlank()) {
                Text(subtitle, color = Color.White.copy(alpha = 0.78f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
internal fun TvCover(url: String, modifier: Modifier, shape: RoundedCornerShape = RoundedCornerShape(8.dp)) {
    // The same URL the phone screens hand to Glide: the profile and library endpoints answer a
    // `spotify:image:` (or `spotify:mosaic:`) identifier, which Glide cannot fetch — given one, it
    // kept its grey box on every card of the TV's Home (reported: no cover loads there).
    val src = com.music.spotui.ui.components.spotifyImageUrl(url)
    Box(modifier.clip(shape).background(Color(0xFF1C1C1E)), contentAlignment = Alignment.Center) {
        if (!src.isNullOrBlank()) GlideImage(model = src, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else Icon(Icons.Default.MusicNote, null, tint = Color.White.copy(alpha = 0.2f), modifier = Modifier.size(40.dp))
    }
}

/** A Material icon in a focusable round button. */
@Composable
internal fun TvIconButton(icon: ImageVector, label: String, size: Dp = 48.dp, tint: Color = Color.White, base: Color = Color.Transparent,
                          modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(modifier.size(size).tvPill(base = base, onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, label, tint = tint, modifier = Modifier.size(size * 0.55f))
    }
}

/** A shimmering placeholder while a page loads: a big shelf first, then the standard ones. */
@Composable
internal fun TvShelfSkeleton(rows: Int = 3) {
    val t = rememberInfiniteTransition(label = "sk")
    val a by t.animateFloat(0.05f, 0.16f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse), label = "a")
    val bar = Color.White.copy(alpha = a)
    Column(Modifier.fillMaxSize().padding(top = 20.dp), verticalArrangement = Arrangement.spacedBy(28.dp)) {
        SkeletonRow(bar, 200.dp, 5)
        repeat(rows) { SkeletonRow(bar, 140.dp, 7) }
    }
}

@Composable
private fun SkeletonRow(bar: Color, cardSize: Dp, cards: Int) {
    Column {
        Box(Modifier.padding(start = 48.dp, bottom = 14.dp).width(180.dp).height(18.dp).clip(RoundedCornerShape(5.dp)).background(bar))
        Row(Modifier.padding(start = 48.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            repeat(cards) {
                Column {
                    Box(Modifier.size(cardSize).clip(RoundedCornerShape(12.dp)).background(bar))
                    Box(Modifier.padding(top = 8.dp).width(cardSize * 0.7f).height(11.dp).clip(RoundedCornerShape(4.dp)).background(bar))
                }
            }
        }
    }
}
