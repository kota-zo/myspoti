package com.music.spotui.ui.immersive.podcast

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyItemScope
import com.music.spotui.ui.immersive.motion.immersiveItemPlacement
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.music.spotui.ui.immersive.components.rememberImmersiveOverscrollFactory
import com.music.spotui.ui.immersive.shell.immersiveTopBarHeight
import com.music.spotui.ui.look.rememberArtworkColors

/**
 * The bar's small title takes over once a page's header (the first list item) has scrolled up
 * under it: 0 while the header's large title shows, 1 after. Read inside draw layers only.
 */
@Composable
fun rememberHeaderCollapse(listState: LazyListState): () -> Float {
    val barPx = with(LocalDensity.current) { immersiveTopBarHeight().toPx() }
    val fadePx = with(LocalDensity.current) { 40.dp.toPx() }
    return remember(listState, barPx, fadePx) {
        val fraction: () -> Float = {
            if (listState.firstVisibleItemIndex > 0) 1f
            else {
                val header = listState.layoutInfo.visibleItemsInfo.firstOrNull()?.size?.toFloat() ?: 0f
                if (header <= 0f) 0f
                else ((listState.firstVisibleItemScrollOffset - (header - barPx - fadePx)) / fadePx).coerceIn(0f, 1f)
            }
        }
        fraction
    }
}

/**
 * A wash of the cover's own colour that fades into the black page: the backdrop of a header whose
 * artwork is not full bleed. It cross-fades when the cover (and so its colour) changes.
 */
@Composable
fun CoverGlow(coverUrl: String?, height: Dp, modifier: Modifier = Modifier) {
    val palette = rememberArtworkColors(coverUrl?.takeIf { it.isNotBlank() })
    val base = palette.colors.firstOrNull() ?: Color.DarkGray
    val tint by animateColorAsState(lerp(base, Color.Black, 0.35f), tween(com.music.spotui.ui.immersive.motion.ImmersiveMotion.TINT_MS), label = "coverGlow")
    Box(
        modifier
            .height(height)
            .background(Brush.verticalGradient(listOf(tint.copy(alpha = 0.62f), Color.Transparent))),
    )
}

/** Gives every list and page inside [content] the Immersive look's iOS-style rubber-band overscroll. */
@Composable
fun ImmersiveOverscrollHost(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalOverscrollFactory provides rememberImmersiveOverscrollFactory(), content = content)
}

/**
 * A list row's placement animation: rows slide to their new place on one soft spring when the
 * list changes (a sort, a removal) and do not fade. Nothing moves when the phone asks for no animation.
 */
@Composable
fun LazyItemScope.rowPlacement(): Modifier = immersiveItemPlacement()
