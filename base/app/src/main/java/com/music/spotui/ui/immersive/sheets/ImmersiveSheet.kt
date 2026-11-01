/*
 * Adapted from BitChord (https://github.com/kushagrasinghx/BitChord), GPL-3.0-or-later,
 * app/src/main/java/com/music/bitchord/ui/components/SongActionsSheet.kt (TintedSheet, ActionRow,
 * SheetTrackHeader, SheetHeading, GroupSeparator) and ArtworkBackdrop.kt at edc1e6f.
 * Modified for Myspoti, 2026-10.
 */
package com.music.spotui.ui.immersive.sheets

import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import com.music.spotui.ui.components.spotifyImageUrl
import com.music.spotui.ui.immersive.Immersive
import com.music.spotui.ui.immersive.components.ImmersiveCover
import com.music.spotui.ui.immersive.components.ImmersiveSongTitle
import com.music.spotui.ui.immersive.components.immersiveClickable
import com.music.spotui.ui.immersive.components.rememberImmersiveOverscrollFactory
import com.music.spotui.ui.immersive.motion.ImmersiveMotion
import com.music.spotui.ui.look.rememberArtworkColors
import com.music.spotui.ui.look.rememberReduceMotion

/** The sheets' top corners (BitChord's `SHEET_SHAPE`). */
val ImmersiveSheetShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)

/** The ground of a sheet that is not about one cover (BitChord's player drawer, without its blur). */
private val FlatSheetColor = Color(0xFF121212)

/** What a sheet is painted in: [background] under everything, [accent] for values, ticks and highlights. */
@Immutable
data class ImmersiveSheetTint(val background: Color, val accent: Color)

/** The tint of the sheet being drawn, read by the rows so they take the cover's accent. */
val LocalImmersiveSheetTint = compositionLocalOf { ImmersiveSheetTint(FlatSheetColor, Immersive.Accent) }

/** How a row is drawn: the sheet's full-width list, or the compact rows of a held item's popup. */
enum class ImmersiveRowStyle { Sheet, Menu }

/** Set by whichever surface the rows are drawn on; see [ImmersiveHeldContextMenu]. */
val LocalImmersiveRowStyle = staticCompositionLocalOf { ImmersiveRowStyle.Sheet }

/**
 * A cover's colours made into a sheet tint: a deep wash of its dominant colour for the ground
 * and a brightened version for the accent. Without a cover ([imageUrl] null) the flat ground and
 * Myspoti's own accent. The first sight of a cover warms in; a known one is right on the first
 * frame, and "reduce motion" turns the fade into a cut.
 */
@Composable
fun rememberImmersiveSheetTint(imageUrl: String?): ImmersiveSheetTint {
    val hasCover = !imageUrl.isNullOrBlank()
    val colors = rememberArtworkColors(if (hasCover) spotifyImageUrl(imageUrl) ?: imageUrl else null).colors
    val seed = colors.firstOrNull() ?: Immersive.Accent
    val target = remember(seed, hasCover) {
        if (!hasCover) return@remember ImmersiveSheetTint(FlatSheetColor, Immersive.Accent)
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(seed.toArgb(), hsl)
        val ground = floatArrayOf(hsl[0], hsl[1].coerceIn(0.25f, 0.6f), 0.14f)
        val accent = floatArrayOf(hsl[0], hsl[1].coerceIn(0.55f, 1f), 0.68f)
        ImmersiveSheetTint(
            Color(ColorUtils.HSLToColor(ground)),
            Color(ColorUtils.HSLToColor(accent)),
        )
    }
    val spec = if (rememberReduceMotion()) snap<Color>() else tween(ImmersiveMotion.SHEET_TINT_MS)
    val background by animateColorAsState(target.background, spec, label = "sheetTintBackground")
    val accent by animateColorAsState(target.accent, spec, label = "sheetTintAccent")
    return ImmersiveSheetTint(background, accent)
}

/**
 * The bottom sheet of the Immersive look. A Material modal sheet, so it drags with the finger,
 * flings away with velocity and settles on the usual dismissal thresholds, but painted here:
 * with an [imageUrl] it wears that cover's colours (the cover blurred across the top, dissolving
 * into the tint, as BitChord's song sheet does), without one a flat dark ground.
 *
 * The rows scroll inside, capped at [maxHeightFraction] of the screen (or exactly that when
 * [expand] is set, for lists that bring their own scrolling), with the iOS rubber band.
 * [content] is a column; use [ImmersiveSheetRow] and friends for what goes in it.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalGlideComposeApi::class)
@Composable
fun ImmersiveSheet(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    imageUrl: String? = null,
    maxHeightFraction: Float = 0.85f,
    expand: Boolean = false,
    scrollable: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val tint = rememberImmersiveSheetTint(imageUrl)
    val maxHeight = LocalConfiguration.current.screenHeightDp.dp * maxHeightFraction
    val canBlur = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        // The sheet paints its own ground, corners and handle: a transparent container has
        // nothing to clip or to hang a handle on, which is why they are drawn below.
        containerColor = Color.Transparent,
        tonalElevation = 0.dp,
        dragHandle = null,
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
    ) {
        CompositionLocalProvider(
            LocalImmersiveSheetTint provides tint,
            LocalOverscrollFactory provides rememberImmersiveOverscrollFactory(),
        ) {
            Box(modifier.fillMaxWidth().clip(ImmersiveSheetShape)) {
                Box(Modifier.matchParentSize().background(tint.background)) {
                    if (canBlur && !imageUrl.isNullOrBlank()) {
                        GlideImage(
                            model = spotifyImageUrl(imageUrl) ?: imageUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxWidth()
                                .fillMaxHeight(WASH_FRACTION)
                                // Overscaled so the blur's clamped edges never reach a visible one.
                                .graphicsLayer { scaleX = 1.4f; scaleY = 1.4f }
                                .blur(72.dp, BlurredEdgeTreatment.Unbounded),
                        )
                    }
                    Box(
                        Modifier.fillMaxSize().background(
                            Brush.verticalGradient(
                                0f to tint.background.copy(alpha = 0.40f),
                                0.20f to tint.background.copy(alpha = 0.55f),
                                0.55f to tint.background.copy(alpha = 0.85f),
                                WASH_FRACTION to tint.background,
                            ),
                        ),
                    )
                }
                Column(
                    Modifier
                        .fillMaxWidth()
                        .then(if (expand) Modifier.height(maxHeight) else Modifier.heightIn(max = maxHeight)),
                ) {
                    // Drawn rather than taken from BottomSheetDefaults, whose handle carries 22 dp of
                    // padding each side. Outside the scrolling rows so it stays put as a grab target.
                    Box(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp), contentAlignment = Alignment.Center) {
                        Box(
                            Modifier
                                .size(width = 34.dp, height = 4.dp)
                                .clip(CircleShape)
                                .background(Immersive.Text.copy(alpha = 0.35f)),
                        )
                    }
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = expand)
                            .then(if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                            .navigationBarsPadding(),
                        content = content,
                    )
                }
            }
        }
    }
}

/** How far down a tinted sheet the blurred cover reaches. */
private const val WASH_FRACTION = 0.75f

/** The sheet's heading: [title] in the page-title face, an optional quiet [subtitle] under it. */
@Composable
fun ImmersiveSheetTitle(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    subtitleColor: Color = Immersive.TextSecondary,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = Immersive.Title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrBlank()) {
                Text(subtitle, style = Immersive.Subhead.copy(color = subtitleColor), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        trailing?.invoke()
    }
}

/**
 * What a sheet is about, at its head: a 52 dp cover, the [title] (with the explicit badge) and a
 * [subtitle]. BitChord's `SheetTrackHeader`; the cover is a rounded square, or a circle for artists.
 */
@Composable
fun ImmersiveSheetHeader(
    title: String,
    subtitle: String?,
    coverUrl: String?,
    modifier: Modifier = Modifier,
    explicit: Boolean = false,
    round: Boolean = false,
) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ImmersiveCover(coverUrl, Modifier.size(52.dp), if (round) CircleShape else RoundedCornerShape(8.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            ImmersiveSongTitle(title, explicit, Immersive.BodyStrong, Immersive.Text)
            if (!subtitle.isNullOrBlank()) {
                Text(subtitle, style = Immersive.Subhead, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** The hairline under a header. */
@Composable
fun ImmersiveSheetDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(modifier, thickness = 0.5.dp, color = Immersive.Separator)
}

/**
 * One line of a sheet's menu (BitChord's `ActionRow`): glyph, [label] and an optional [value] on
 * the right in the sheet's accent. [tint] is for rows whose glyph carries state (a filled heart);
 * [leading] replaces the glyph (a download badge). In the popup of a held item the label leads and
 * the glyph sits at the far edge, as in context menus. Press shrinks it, no ripple.
 */
@Composable
fun ImmersiveSheetRow(
    icon: ImageVector?,
    label: String,
    modifier: Modifier = Modifier,
    value: String? = null,
    tint: Color? = null,
    accent: Color = LocalImmersiveSheetTint.current.accent,
    enabled: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    val menu = LocalImmersiveRowStyle.current == ImmersiveRowStyle.Menu
    val alpha = if (enabled) 1f else 0.4f
    val glyph: @Composable () -> Unit = {
        Box(Modifier.size(if (menu) 20.dp else 24.dp), contentAlignment = Alignment.Center) {
            when {
                leading != null -> leading()
                icon != null -> Icon(
                    icon, contentDescription = null,
                    tint = (tint ?: Immersive.Text).copy(alpha = alpha),
                    modifier = Modifier.size(if (menu) 20.dp else 22.dp),
                )
            }
        }
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .immersiveClickable(onClick = onClick, enabled = enabled, pressedScale = 0.98f)
            .then(
                if (menu) Modifier.heightIn(min = 46.dp).padding(horizontal = 18.dp, vertical = 11.dp)
                else Modifier.padding(horizontal = 22.dp, vertical = 15.dp),
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!menu) {
            glyph()
            Spacer(Modifier.width(18.dp))
        }
        Text(
            text = label,
            style = Immersive.Body.copy(color = Immersive.Text.copy(alpha = alpha)),
            maxLines = if (menu) 2 else Int.MAX_VALUE,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (value != null) {
            Spacer(Modifier.width(12.dp))
            Text(value, style = Immersive.Body.copy(color = accent.copy(alpha = alpha)), maxLines = 1)
        }
        if (menu) {
            Spacer(Modifier.width(14.dp))
            glyph()
        }
    }
}

/**
 * A choice in a list: [label] (and an optional [detail]) with a tick in the sheet's accent when
 * [selected]. For the sleep timer, sort orders, quality pickers.
 */
@Composable
fun ImmersiveSheetOption(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    detail: String? = null,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    val accent = LocalImmersiveSheetTint.current.accent
    Row(
        modifier = modifier
            .fillMaxWidth()
            .immersiveClickable(onClick = onClick, pressedScale = 0.98f)
            .padding(horizontal = 22.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = Immersive.Body.copy(color = if (danger) Immersive.Accent else Immersive.Text))
            if (!detail.isNullOrBlank()) Text(detail, style = Immersive.Subhead)
        }
        if (selected) {
            Spacer(Modifier.width(12.dp))
            Icon(Icons.Rounded.Check, contentDescription = null, tint = accent, modifier = Modifier.size(22.dp))
        }
    }
}

/** A grey line of explanation or an empty state inside a sheet. */
@Composable
fun ImmersiveSheetNote(text: String, modifier: Modifier = Modifier, style: TextStyle = Immersive.Subhead) {
    Text(text, style = style, modifier = modifier.padding(horizontal = 22.dp, vertical = 14.dp))
}
