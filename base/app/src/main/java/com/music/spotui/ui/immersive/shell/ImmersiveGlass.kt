/*
 * Adapted from BitChord (https://github.com/kushagrasinghx/BitChord), GPL-3.0-or-later,
 * app/src/main/java/com/music/bitchord/ui/components/LiquidGlass.kt at edc1e6f, itself adapted
 * from EchoMusicApp/Echo-Music's GlassEffectConfig / Modifier.liquidGlass (GPL-3.0).
 * Modified for Myspoti, 2026-10: the switch is LiquidGlassPref, the fallback is Myspoti's Haze
 * frost, the page is recorded per page (each page's list) instead of around the whole app, and
 * a glass drawn inside the page it samples falls back to a plain fill, one in another window (a
 * sheet) samples the page by screen position. 2026-10 (later): the window-blur gate is gone, the
 * blur, lens and tint follow the surface size, and a surface can react to a press.
 *
 * The rendering itself is Kyant0/backdrop (Apache-2.0), vendored in ./backdrop.
 */
package com.music.spotui.ui.immersive.shell

import android.os.Build
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.music.spotui.data.preferences.LiquidGlassPref
import com.music.spotui.ui.immersive.Immersive
import com.music.spotui.ui.immersive.motion.ImmersiveMotion
import com.music.spotui.ui.immersive.shell.backdrop.Backdrop
import com.music.spotui.ui.immersive.shell.backdrop.backdrops.LayerBackdrop
import com.music.spotui.ui.immersive.shell.backdrop.backdrops.rememberLayerBackdrop
import com.music.spotui.ui.immersive.shell.backdrop.drawBackdrop
import com.music.spotui.ui.immersive.shell.backdrop.effects.blur
import com.music.spotui.ui.immersive.shell.backdrop.effects.colorControls
import com.music.spotui.ui.immersive.shell.backdrop.effects.lens
import com.music.spotui.ui.immersive.shell.backdrop.highlight.Highlight
import com.music.spotui.ui.immersive.shell.backdrop.shadow.Shadow
import com.music.spotui.ui.look.rememberReduceMotion

/** Whether Liquid Glass can run here, before the user's own switch is taken into account. */
enum class LiquidGlassAvailability {
    /** Android 12+ and no "remove animations": the switch decides. */
    AVAILABLE,

    /** The phone asks for no animation ("Remove animations"): frosted instead. */
    REDUCED,

    /** Before Android 12 there is no RenderEffect to blur the page live: frosted, and the switch is greyed. */
    UNSUPPORTED,
}

/** Which glass the Immersive surfaces actually draw. */
enum class GlassPath {
    /** Android 13+: live blur of the page behind, lens refraction, chromatic aberration (AGSL). */
    LENS,

    /** Android 12/12L: live RenderEffect blur of the page behind, no lens (no RuntimeShader). */
    LIVE_BLUR,

    /** Haze frost: below Android 12, "Remove animations" on, or the switch off. */
    FROST,
}

/**
 * See [LiquidGlassAvailability]. The system's "window blurs" switch is deliberately not read: it
 * only governs blur-behind of whole windows (a dialog's backdrop). The RenderEffect and
 * RuntimeShader blur of a recorded layer does not depend on it, and Samsung/Xiaomi/Battery saver
 * turn it off on phones that render the effect perfectly well.
 */
@Composable
fun liquidGlassAvailability(): LiquidGlassAvailability {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return LiquidGlassAvailability.UNSUPPORTED
    return if (rememberReduceMotion()) LiquidGlassAvailability.REDUCED else LiquidGlassAvailability.AVAILABLE
}

/** The path for these inputs; [rememberLiquidGlassActive] logs it. */
internal fun glassPathOf(switchOn: Boolean, availability: LiquidGlassAvailability, sdk: Int = Build.VERSION.SDK_INT): GlassPath = when {
    !switchOn || availability != LiquidGlassAvailability.AVAILABLE -> GlassPath.FROST
    sdk >= Build.VERSION_CODES.TIRAMISU -> GlassPath.LENS
    else -> GlassPath.LIVE_BLUR
}

/** True when the Immersive surfaces should be Liquid Glass: the switch is on and [liquidGlassAvailability] allows it. */
@Composable
fun rememberLiquidGlassActive(): Boolean {
    val availability = liquidGlassAvailability()
    val on = LiquidGlassPref.on
    val path = glassPathOf(on, availability)
    // One line per change, so a diagnostic report says which glass runs and why.
    LaunchedEffect(path, availability, on) {
        val why = when {
            !on -> "the Liquid Glass switch is off"
            availability == LiquidGlassAvailability.UNSUPPORTED -> "Android ${Build.VERSION.SDK_INT} is below 12, no RenderEffect"
            availability == LiquidGlassAvailability.REDUCED -> "Remove animations is on"
            path == GlassPath.LENS -> "Android ${Build.VERSION.SDK_INT}: RuntimeShader lens and live RenderEffect blur"
            else -> "Android ${Build.VERSION.SDK_INT}: live RenderEffect blur, no RuntimeShader for the lens"
        }
        app.myspoti.host.android.Logbook.log("glass: $path ($why)")
    }
    return path != GlassPath.FROST
}

/**
 * The recorded page the Liquid Glass surfaces refract. Provided by the host (App.kt) only while
 * [rememberLiquidGlassActive] is true; null means the bars fall back to the frost (which samples
 * the same recording through [LocalImmersiveFrostPage] on Android 12+, Haze below). Pages feed
 * it through [immersiveHazeSource], which records the page here as well when it is provided.
 */
val LocalImmersiveBackdrop = compositionLocalOf<LayerBackdrop?> { null }

/** A page recording kept for the lifetime of the calling composition. */
@Composable
fun rememberImmersiveBackdrop(): LayerBackdrop = rememberLayerBackdrop()

/**
 * Where a glass surface composed under it also records the glass it draws (without rim or
 * shadow), so the tab bar's selection lens can refract the bar rather than the page behind it.
 */
internal val LocalGlassExport = compositionLocalOf<LayerBackdrop?> { null }

/**
 * Resolution the glass records and processes its backdrop at: a third, as BitChord (nine times
 * fewer pixels through the colour matrix, the blur and the lens; the blur hides the upscale).
 */
private const val GLASS_RESOLUTION_SCALE = 0.33f

/** Surfaces up to this size (dp, smaller side) are "thin" glass: bars, pills, buttons. */
private const val THIN_DP = 56f

/** From this size on a surface is "large" glass: a sheet or a card, whose text must stay readable. */
private const val LARGE_DP = 200f

/** The glass's own body over the refracted page: dark, so white text holds on any artwork. */
internal val GlassTint = Color(0xFF1C1C22).copy(alpha = 0.42f)

/** Alpha of the body on large glass: more opaque, there is text on it. */
private const val LARGE_TINT_ALPHA = 0.60f

private val GlassShadow = Shadow(radius = 18.dp, color = Color.Black.copy(alpha = 0.22f))

/** A brighter, thicker specular rim than the library's default, like iOS 26's glass edge. */
private val GlassHighlight = Highlight(width = 0.75f.dp, alpha = 0.9f)

/** How much the lens bulges while the glass is pressed (fraction of its refraction). */
private const val PRESS_BULGE = 0.35f

private fun mix(a: Float, b: Float, t: Float) = a + (b - a) * t

/**
 * Liquid Glass over [page]: vibrancy, a live blur and (Android 13+, rounded shapes) lens
 * refraction with a little chromatic aberration, a dark [tint], a rim of light and a soft shadow.
 * The blur, the lens depth and the body's opacity follow the surface's size: a thin bar blurs
 * little and bends hard at its edge, a sheet blurs more and is more opaque. Use through
 * [immersiveGlassSurface]; this one is for surfaces that need a different tint or no lens (the
 * top bar's strip).
 *
 * @param press 0..1, how pressed the surface is (read in the draw phase): scales it a little and
 * bulges the lens. Null for a surface that is not touched.
 */
@Composable
internal fun Modifier.immersiveLiquidGlass(
    shape: Shape,
    page: LayerBackdrop,
    tint: Color = GlassTint,
    lens: Boolean = true,
    rim: Boolean = true,
    press: (() -> Float)? = null,
): Modifier {
    val export = LocalGlassExport.current
    val backdrop = remember(page) { PageBackdrop(page) }
    // The lens only knows rounded rectangles (it throws on anything else).
    val bend = lens && shape is CornerBasedShape && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    val adaptive = tint == GlassTint
    // Stable across recompositions: a new lambda per call would rebuild the effect chain.
    val effects = remember(bend, press) {
        val block: com.music.spotui.ui.immersive.shell.backdrop.BackdropEffectScope.() -> Unit = {
            val k = GLASS_RESOLUTION_SCALE
            val minDp = size.minDimension / k / density
            val depth = ((minDp - THIN_DP) / (LARGE_DP - THIN_DP)).coerceIn(0f, 1f)
            // Vibrancy, and a touch darker so a bright cover behind never washes the text out.
            colorControls(brightness = -0.03f, saturation = 1.6f)
            blur(mix(3.5f, 14f, depth) * density * k)
            if (bend) {
                val bulge = 1f + PRESS_BULGE * (press?.invoke() ?: 0f)
                val height = mix((minDp * 0.42f).coerceAtMost(22f), 30f, depth)
                val amount = height * mix(1.7f, 1.2f, depth) * bulge
                lens(
                    refractionHeight = height * density * k,
                    refractionAmount = amount * density * k,
                    depthEffect = true,
                    chromaticAberration = true,
                )
            }
        }
        block
    }
    val surface = remember(tint, adaptive) {
        val block: DrawScope.() -> Unit = {
            val depth = ((size.minDimension / density - THIN_DP) / (LARGE_DP - THIN_DP)).coerceIn(0f, 1f)
            drawRect(color = if (adaptive) tint.copy(alpha = mix(tint.alpha, LARGE_TINT_ALPHA, depth)) else tint, size = size)
        }
        block
    }
    val layerBlock = remember(press) {
        press?.let { p ->
            val block: GraphicsLayerScope.() -> Unit = {
                val s = 1f - 0.04f * p()
                scaleX = s
                scaleY = s
            }
            block
        }
    }
    return drawBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = effects,
        highlight = if (rim) ({ GlassHighlight }) else null,
        shadow = if (rim) ({ GlassShadow }) else null,
        layerBlock = layerBlock,
        onDrawSurface = surface,
        exportedBackdrop = export,
        backdropScale = GLASS_RESOLUTION_SCALE,
    )
}

/**
 * Haze's frost drawn from the page recording ([LocalImmersiveFrostPage]) instead of Haze: a
 * [blur] (dp) under [tint], nothing else. It follows a page or tab transition frame by frame like
 * the Liquid Glass does. Clip it to its shape before this.
 */
@Composable
internal fun Modifier.immersivePageFrost(page: LayerBackdrop, tint: Color, blur: Float): Modifier {
    val backdrop = remember(page) { PageBackdrop(page) }
    val effects = remember(blur) {
        val block: com.music.spotui.ui.immersive.shell.backdrop.BackdropEffectScope.() -> Unit = {
            blur(blur * density * GLASS_RESOLUTION_SCALE)
        }
        block
    }
    val surface = remember(tint) {
        val block: DrawScope.() -> Unit = { drawRect(color = tint, size = size) }
        block
    }
    return drawBackdrop(
        backdrop = backdrop,
        shape = { androidx.compose.ui.graphics.RectangleShape },
        effects = effects,
        highlight = null,
        shadow = null,
        onDrawSurface = surface,
        backdropScale = GLASS_RESOLUTION_SCALE,
    )
}

/**
 * The 0..1 press of a glass surface for [source], on the Immersive press spring (a touch of
 * overshoot on release). Constant 0 under "reduce motion". Read it in the draw phase.
 */
@Composable
internal fun rememberGlassPress(source: androidx.compose.foundation.interaction.InteractionSource?): (() -> Float)? {
    if (source == null || rememberReduceMotion()) return null
    val pressed by source.collectIsPressedAsState()
    val progress = animateFloatAsState(
        targetValue = if (pressed) 1f else 0f,
        animationSpec = if (pressed) ImmersiveMotion.pressSpring<Float>() else ImmersiveMotion.releaseSpring<Float>(),
        label = "glassPress",
    )
    return remember(progress) { { progress.value } }
}

/**
 * The page under the glass, laid on the page colour so the blur never runs into transparency
 * (where it does, the sharp page shows through the glass). Draws nothing but [Immersive.GlassFill]
 * when it cannot sample the page: the glass is inside the recorded page itself (it would draw the
 * page into itself). A glass in another window (a sheet) samples the page by screen position.
 */
internal class PageBackdrop(private val page: LayerBackdrop) : Backdrop {
    override val isCoordinatesDependent: Boolean = true

    override fun DrawScope.drawBackdrop(
        density: Density,
        coordinates: LayoutCoordinates?,
        layerBlock: (GraphicsLayerScope.() -> Unit)?,
    ) {
        val source = page.layerCoordinates
        page.moves // read: a page sliding or arriving redraws this glass on every frame
        val attached = coordinates != null && source != null && source.isAttached && coordinates.isAttached &&
            !page.isInsideSource(coordinates)
        val sameWindow = attached && source!!.findRoot() === coordinates!!.findRoot()
        // Another window (the sheet's dialog): each recorded layer is drawn at the surface's
        // position on the screen, which is what both windows share.
        val usable = attached && (sameWindow || Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        report(usable, source, coordinates, attached, sameWindow, page)
        drawRect(if (usable) Immersive.Page else Immersive.GlassFill, topLeft = FAR_TOP_LEFT, size = FAR_SIZE)
        if (!usable) return
        if (sameWindow) {
            with(page) { drawBackdrop(density, coordinates, layerBlock) }
        } else {
            val surface = coordinates!!.positionOnScreen()
            with(page) { drawSources { src -> surface - src.positionOnScreen() } }
        }
    }

    /**
     * One Logbook line per change of state (at most a handful per run): whether the shared page
     * recording is attached and non-empty, which a diagnostic report then proves or disproves.
     */
    private fun report(
        usable: Boolean,
        source: LayoutCoordinates?,
        coordinates: LayoutCoordinates?,
        attached: Boolean,
        sameWindow: Boolean,
        page: LayerBackdrop,
    ) {
        val recorded = page.recordedSize()
        val line = buildString {
            append("glass: ")
            append(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) "LENS" else "LIVE_BLUR")
            if (recorded.width > 0 && recorded.height > 0) append(" backdrop recorded ${recorded.width}x${recorded.height}")
            else append(" backdrop empty (0x0)")
            append(if (usable) ", sampled" else ", NOT sampled")
            if (coordinates != null && coordinates.isAttached) append(" for a ${coordinates.size.width}x${coordinates.size.height} surface")
            if (!usable) {
                append(" [page coordinates ").append(if (source == null) "null" else if (source.isAttached) "attached" else "detached")
                append(", surface ").append(if (coordinates == null) "null" else if (coordinates.isAttached) "attached" else "detached")
                append(", same window ").append(sameWindow)
                append(", surface inside page ").append(attached.not() && coordinates != null && page.isInsideSource(coordinates))
                append("]")
            }
        }
        if (line == lastReport || reports >= 14) return
        lastReport = line
        reports++
        app.myspoti.host.android.Logbook.log(line)
    }

    private companion object {
        @Volatile var lastReport: String? = null
        @Volatile var reports = 0

        // Past every edge, whatever the scale and padding of the recording.
        val FAR_TOP_LEFT = Offset(-20_000f, -20_000f)
        val FAR_SIZE = Size(40_000f, 40_000f)
    }
}

private fun LayoutCoordinates.findRoot(): LayoutCoordinates {
    var c = this
    while (true) c = c.parentCoordinates ?: return c
}

private fun LayoutCoordinates.isAncestorOf(other: LayoutCoordinates): Boolean {
    var c: LayoutCoordinates? = other
    while (c != null) {
        if (c === this) return true
        c = c.parentCoordinates
    }
    return false
}
