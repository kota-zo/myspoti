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
// Parts adapted from Android's Jetcaster sample (https://github.com/android/compose-samples), Copyright 2024 The Android Open Source Project, Apache License 2.0.
// Parts adapted from Android's JetStream sample (https://github.com/android/tv-samples), Copyright 2023 Google LLC, Apache License 2.0.
package com.music.spotui.ui.tv

import android.os.Build
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import kotlinx.coroutines.delay

internal val TvBg = Color(0xFF000000)
internal val TvAccent = Color(0xFFFF2D78)
internal val TvTitle = Color(0xFFF2F2F5)
internal val TvSubtle = Color(0xFF8A8A8E)

/** The tabs of the floating bar, in their order. */
internal enum class TvTab { Home, Library, Search, Extensions, NowPlaying }

/**
 * How a focused card looks, as tv-material's Card and JetStream's MovieCard draw it: a soft scale, a
 * white glow around it and JetStream's 3 dp light border. [focused] is read by the caller, so the
 * focus can sit on a larger element (a card and its title) while only the picture moves.
 */
@Composable
internal fun Modifier.tvFocusLook(focused: Boolean, shape: RoundedCornerShape, corner: Dp, focusedScale: Float = 1.06f): Modifier {
    val f by animateFloatAsState(if (focused) 1f else 0f, tween(150), label = "cardFocus")
    return this
        .graphicsLayer { val s = 1f + (focusedScale - 1f) * f; scaleX = s; scaleY = s }
        .drawBehind {
            if (f <= 0f) return@drawBehind
            // A glow of 20 dp: four widening rounded rects, fading out.
            val glow = 20.dp.toPx()
            for (i in 1..4) {
                val g = glow * i / 4f
                drawRoundRect(
                    Color.White.copy(alpha = 0.06f * f * (5 - i) / 4f),
                    topLeft = Offset(-g, -g), size = Size(size.width + 2 * g, size.height + 2 * g),
                    cornerRadius = CornerRadius(corner.toPx() + g),
                )
            }
        }
        .border(TvFocusBorder, TvOnSurface.copy(alpha = f), shape)
}

/** A focusable card: [tvFocusLook] on the element that takes the focus. [onFocus] hears the focus too. */
@Composable
internal fun Modifier.tvCard(
    shape: RoundedCornerShape,
    corner: Dp,
    focusedScale: Float = 1.06f,
    onFocus: (Boolean) -> Unit = {},
    onClick: () -> Unit,
): Modifier {
    var focused by remember { mutableStateOf(false) }
    return this
        .tvFocusLook(focused, shape, corner, focusedScale)
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

private data class TabBounds(val left: Dp, val width: Dp, val height: Dp)

/**
 * The floating pill of tabs at the top. As JetStream's DashboardTopBar, one pill slides under the
 * tabs: bright under the focused tab while the remote is on the bar, dim under the open tab
 * otherwise (DashboardTopBarItemIndicator).
 */
@Composable
internal fun TvTopBar(selected: TvTab, labels: Map<TvTab, String>, onSelect: (TvTab) -> Unit, modifier: Modifier = Modifier) {
    var barFocused by remember { mutableStateOf(false) }
    var focusedTab by remember { mutableStateOf<TvTab?>(null) }
    val bounds = remember { mutableStateMapOf<TvTab, TabBounds>() }
    val density = LocalDensity.current
    // On Now Playing the bar fades away until the remote reaches it.
    val alpha by animateFloatAsState(if (selected == TvTab.NowPlaying && !barFocused) 0f else 1f, tween(500), label = "barAlpha")
    Box(
        modifier.fillMaxWidth().onFocusChanged { barFocused = it.hasFocus }.graphicsLayer { this.alpha = alpha }.padding(vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.clip(RoundedCornerShape(50)).background(Color(0xE61C1C1E))
                .background(Brush.verticalGradient(0f to Color(0x14FFFFFF), 0.5f to Color.Transparent))
                .padding(horizontal = 6.dp, vertical = 5.dp),
        ) {
            val target = (if (barFocused) focusedTab else null) ?: selected
            bounds[target]?.let { b ->
                val left by animateDpAsState(b.left, label = "tabLeft")
                val width by animateDpAsState(b.width, label = "tabWidth")
                val pill by animateColorAsState(if (barFocused) TvOnSurface else Color(0x40FFFFFF), label = "tabPill")
                Box(Modifier.offset(x = left).width(width).height(b.height).background(pill, RoundedCornerShape(50)))
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                TvTab.entries.forEach { tab ->
                    val onPill = tab == target
                    val fg by animateColorAsState(
                        when {
                            onPill && barFocused -> TvInverseOnSurface
                            onPill || tab == selected -> Color.White
                            else -> Color(0xFF9A9A9A)
                        }, tween(180), label = "tabFg")
                    Box(
                        Modifier
                            .onGloballyPositioned { c ->
                                with(density) { bounds[tab] = TabBounds(c.positionInParent().x.toDp(), c.size.width.toDp(), c.size.height.toDp()) }
                            }
                            .onFocusChanged { if (it.isFocused) focusedTab = tab else if (focusedTab == tab) focusedTab = null }
                            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onSelect(tab) }
                            .padding(horizontal = 18.dp, vertical = 8.dp),
                    ) {
                        Text(labels[tab].orEmpty(), fontSize = 15.sp, color = fg,
                            fontWeight = if (tab == selected || onPill) FontWeight.SemiBold else FontWeight.Medium)
                    }
                }
            }
        }
    }
}

/**
 * A square cover with its title and subtitle under it, for shelves and grids: Jetcaster's
 * PodcastCard in tv-material's StandardCardContainer. The card and its title take the focus as
 * one; the cover scales and gets the border, the title brightens.
 */
@Composable
internal fun TvAlbumCard(title: String, subtitle: String, coverUrl: String, size: Dp, round: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val corner = if (round) size / 2 else 12.dp
    val shape = RoundedCornerShape(corner)
    var focused by remember { mutableStateOf(false) }
    Column(
        modifier.width(size).onFocusChanged { focused = it.hasFocus }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
        horizontalAlignment = if (round) Alignment.CenterHorizontally else Alignment.Start,
    ) {
        TvCover(coverUrl, Modifier.fillMaxWidth().aspectRatio(1f).tvFocusLook(focused, shape, corner), shape, seed = title)
        Column(Modifier.padding(start = 2.dp, end = 2.dp, top = 12.dp), horizontalAlignment = if (round) Alignment.CenterHorizontally else Alignment.Start) {
            Text(title, style = TvType.titleSmall, fontWeight = if (focused) FontWeight.SemiBold else FontWeight.Medium,
                color = if (focused) Color.White else TvTitle.copy(alpha = 0.82f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotBlank()) {
                Text(subtitle, style = TvType.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}

/** A big square card whose title sits on a dark scrim over the cover (the lead shelf of Home). */
@Composable
internal fun TvGradientCard(title: String, subtitle: String, coverUrl: String, width: Dp, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Box(modifier.width(width).aspectRatio(1f).tvCard(shape, 16.dp, focusedScale = 1.05f, onClick = onClick)) {
        TvCover(coverUrl, Modifier.fillMaxSize(), shape, seed = title)
        // A dark scrim, not the cover's own colour: a palette read for every card is too much for a weak TV.
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.4f to Color.Transparent, 1f to Color(0xEB101012))))
        Column(Modifier.align(Alignment.BottomStart).padding(16.dp)) {
            Text(title, style = TvType.titleLarge.copy(shadow = TvType.overImage), color = Color.White, fontWeight = FontWeight.Bold,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotBlank()) {
                Text(subtitle, style = TvType.bodyMedium, color = Color.White.copy(alpha = 0.75f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}

/** A cover; without one, JetStream's radial gradient in colours picked by [seed] (a title). */
@OptIn(ExperimentalGlideComposeApi::class)
@Composable
internal fun TvCover(url: String, modifier: Modifier, shape: RoundedCornerShape = RoundedCornerShape(8.dp), seed: String = "") {
    val placeholder = url.isBlank() && seed.isNotEmpty()
    Box(modifier.clip(shape).background(if (placeholder) tvSeedBrush(seed) else SolidColor(Color(0xFF1C1C1E))), contentAlignment = Alignment.Center) {
        if (url.isNotBlank()) GlideImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else Icon(Icons.Default.MusicNote, null, tint = Color.White.copy(alpha = if (placeholder) 0.4f else 0.2f), modifier = Modifier.size(40.dp))
    }
}

/**
 * A Material icon in a focusable round button, as JetStream's VideoPlayerControlsIcon on
 * tv-material's ClickableSurface: a faint disc, light with a dark icon when focused.
 */
@Composable
internal fun TvIconButton(icon: ImageVector, label: String, size: Dp = 48.dp, tint: Color = Color.White, base: Color = Color(0x1FFFFFFF),
                          modifier: Modifier = Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val f by animateFloatAsState(if (focused) 1f else 0f, tween(150), label = "iconFocus")
    val focusedFill = if (base == Color.White) Color.White else TvOnSurface
    val iconTint = when {
        !focused -> tint
        tint == TvAccent -> TvAccent
        else -> TvInverseOnSurface
    }
    Box(
        modifier.size(size)
            .graphicsLayer { val s = 1f + 0.1f * f; scaleX = s; scaleY = s }
            .drawBehind {
                if (f > 0f) drawCircle(Color.White.copy(alpha = 0.16f * f), radius = this.size.minDimension / 2 + 6.dp.toPx())
            }
            .clip(CircleShape)
            .background(lerp(base, focusedFill, f))
            .onFocusChanged { focused = it.isFocused }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, tint = iconTint, modifier = Modifier.size(size * 0.55f))
    }
}

/** A card of Home's featured carousel. */
internal data class TvHeroItem(val title: String, val subtitle: String, val kind: String, val imageUrl: String)

/**
 * Home's featured banner, after JetStream's FeaturedMoviesCarousel: a wide card over a blurred,
 * veiled copy of the cover, the title large on the left, the cover sharp on the right and the
 * position dots at the bottom (CarouselIndicator). Focused it gets the light border; ← and → move
 * through the items, OK opens the one shown. Unfocused it moves on by itself.
 */
@Composable
internal fun TvHero(items: List<TvHeroItem>, modifier: Modifier = Modifier, onOpen: (Int) -> Unit) {
    if (items.isEmpty()) return
    var index by remember(items) { mutableIntStateOf(0) }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(focused, index, items.size) {
        if (!focused && items.size > 1) { delay(8_000); index = (index + 1) % items.size }
    }
    val shape = RoundedCornerShape(14.dp)
    val border by animateFloatAsState(if (focused) 1f else 0f, tween(150), label = "heroBorder")
    Box(
        modifier.fillMaxWidth().height(300.dp)
            .border(TvFocusBorder, TvOnSurface.copy(alpha = border), shape)
            .clip(shape)
            .onFocusChanged { focused = it.isFocused }
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                when {
                    e.key == Key.DirectionLeft && index > 0 -> { index--; true }
                    e.key == Key.DirectionRight && index < items.lastIndex -> { index++; true }
                    else -> false
                }
            }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onOpen(index) },
    ) {
        Crossfade(index.coerceIn(0, items.lastIndex), animationSpec = tween(1000), label = "hero") { i -> TvHeroSlide(items[i]) }
        if (items.size > 1) {
            Row(
                Modifier.align(Alignment.BottomEnd).padding(24.dp).clip(RoundedCornerShape(4.dp)).background(Color.White.copy(alpha = 0.1f)).padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items.indices.forEach { i ->
                    Box(Modifier.size(8.dp).clip(CircleShape).background(if (i == index) TvOnSurface else TvOnSurface.copy(alpha = 0.3f)))
                }
            }
        }
    }
}

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun TvHeroSlide(item: TvHeroItem) {
    val tint = rememberTvTint(item.imageUrl, item.title)
    Box(Modifier.fillMaxSize().background(lerp(tint, Color.Black, 0.45f))) {
        if (item.imageUrl.isNotBlank()) {
            GlideImage(model = item.imageUrl, contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().alpha(0.6f).then(if (Build.VERSION.SDK_INT >= 31) Modifier.blur(40.dp) else Modifier))
        } else {
            val (a, b) = tvSeedColors(item.title)
            Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(b, a))))
        }
        // JetStream's veils: dark from the left where the words are, and towards the bottom.
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Color.Black.copy(alpha = 0.85f), 0.55f to Color.Black.copy(alpha = 0.3f), 1f to Color.Transparent)))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.5f)))))
        val coverShape = RoundedCornerShape(10.dp)
        TvCover(item.imageUrl, Modifier.align(Alignment.CenterEnd).padding(end = 56.dp).size(212.dp).shadow(24.dp, coverShape), coverShape, seed = item.title)
        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth(0.58f).padding(start = 36.dp, bottom = 32.dp, end = 16.dp)) {
            if (item.kind.isNotBlank()) {
                Text(item.kind.uppercase(), color = TvAccent, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp)
            }
            Text(item.title, style = TvType.displayMedium.copy(shadow = TvType.overImage), color = Color.White, maxLines = 2,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
            if (item.subtitle.isNotBlank()) {
                Text(item.subtitle, style = TvType.titleMedium.copy(shadow = TvType.overImage), color = Color.White.copy(alpha = 0.65f),
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}

/** A shimmering placeholder while a page loads: Home's banner or a big shelf first, then the standard ones. */
@Composable
internal fun TvShelfSkeleton(rows: Int = 3, hero: Boolean = false) {
    val t = rememberInfiniteTransition(label = "sk")
    val a by t.animateFloat(0.05f, 0.16f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse), label = "a")
    val bar = Color.White.copy(alpha = a)
    Column(Modifier.fillMaxSize().padding(top = 20.dp), verticalArrangement = Arrangement.spacedBy(TvSpace.section)) {
        if (hero) Box(Modifier.padding(horizontal = TvSpace.start).fillMaxWidth().height(300.dp).clip(RoundedCornerShape(14.dp)).background(bar))
        else SkeletonRow(bar, 196.dp, 5)
        repeat(rows) { SkeletonRow(bar, 156.dp, 7) }
    }
}

@Composable
private fun SkeletonRow(bar: Color, cardSize: Dp, cards: Int) {
    Column {
        Box(Modifier.padding(start = TvSpace.start, bottom = 16.dp).width(200.dp).height(22.dp).clip(RoundedCornerShape(5.dp)).background(bar))
        Row(Modifier.padding(start = TvSpace.start), horizontalArrangement = Arrangement.spacedBy(TvSpace.item)) {
            repeat(cards) {
                Column {
                    Box(Modifier.size(cardSize).clip(RoundedCornerShape(12.dp)).background(bar))
                    Box(Modifier.padding(top = 12.dp).width(cardSize * 0.7f).height(12.dp).clip(RoundedCornerShape(4.dp)).background(bar))
                }
            }
        }
    }
}
