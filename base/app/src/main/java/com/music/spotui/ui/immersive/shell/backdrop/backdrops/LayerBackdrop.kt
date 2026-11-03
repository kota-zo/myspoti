/*
 * Vendored from Kyant0/backdrop v2.0.0 (io.github.kyant0:backdrop)
 * https://github.com/Kyant0/backdrop — Copyright 2025 Kyant0, Apache License 2.0
 *
 * Vendored so the library ships as source with this app (binary AARs compiled
 * against older Compose broke at runtime) and to add a backdrop resolution
 * scale for cheaper effect rendering. KMP expect/actual declarations were
 * merged into this single Android source set. Package renamed accordingly.
 * Myspoti copy taken from BitChord's vendored tree (github.com/kushagrasinghx/BitChord,
 * app/src/main/java/com/music/bitchord/ui/components/backdrop at edc1e6f), which adds
 * backdropScale and exportedBackdrop to drawBackdrop. Package renamed again for Myspoti.
 */
package com.music.spotui.ui.immersive.shell.backdrop.backdrops

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.Density
import com.music.spotui.ui.immersive.shell.backdrop.Backdrop
import com.music.spotui.ui.immersive.shell.backdrop.internal.InverseLayerScope

private val DefaultOnDraw: ContentDrawScope.() -> Unit = { drawContent() }

/**
 * The opacity a page recording into a backdrop has on screen right now (its fade during a page
 * transition), provided by the navigation around each page: the glass draws the page with it.
 */
val LocalBackdropSourceAlpha = androidx.compose.runtime.staticCompositionLocalOf<() -> Float> { { 1f } }

@Composable
fun rememberLayerBackdrop(
    graphicsLayer: GraphicsLayer = rememberGraphicsLayer(),
    onDraw: ContentDrawScope.() -> Unit = DefaultOnDraw
): LayerBackdrop {
    return remember(graphicsLayer, onDraw) {
        LayerBackdrop(graphicsLayer, onDraw)
    }
}

@Stable
class LayerBackdrop internal constructor(
    val graphicsLayer: GraphicsLayer,
    internal val onDraw: ContentDrawScope.() -> Unit
) : Backdrop {

    override val isCoordinatesDependent: Boolean = true

    internal var layerCoordinates: LayoutCoordinates? by mutableStateOf(null)

    /**
     * The nodes recording into this backdrop, oldest first. During a page transition two pages
     * record at once; the newest one (the page arriving) owns [layerCoordinates], so the one
     * leaving can neither overwrite it with its own position nor blank it when it detaches.
     */
    internal class Source {
        var coordinates: LayoutCoordinates? = null
        /** This source's own recording: every page keeps its own, so two pages in a transition are both sampled. */
        var layer: GraphicsLayer? = null
        /** Its opacity on screen now ([LocalBackdropSourceAlpha]); read while drawing, so a fade redraws the glass. */
        var alpha: () -> Float = { 1f }
    }
    internal val sources = ArrayList<Source>()

    /**
     * Bumped whenever a page records in, leaves or moves. The glass reads it while drawing: a page
     * sliding in does not redraw its content, and the same LayoutCoordinates object would not
     * invalidate [layerCoordinates], so without it the glass kept the page's old place until the
     * transition ended, then jumped.
     */
    internal var moves by androidx.compose.runtime.mutableIntStateOf(0)

    internal fun publish(source: Source) {
        if (sources.lastOrNull() === source) layerCoordinates = source.coordinates
        moves++
    }

    internal fun retire(source: Source) {
        sources.remove(source)
        layerCoordinates = sources.lastOrNull()?.coordinates
        moves++
    }

    private var inverseLayerScope: InverseLayerScope? = null

    /** Size of the largest recording (0x0 when no page has recorded yet). */
    internal fun recordedSize(): androidx.compose.ui.unit.IntSize =
        sources.mapNotNull { it.layer?.size }.maxByOrNull { it.width.toLong() * it.height } ?: androidx.compose.ui.unit.IntSize.Zero

    /** Whether [c] sits inside one of the recorded pages (it would then draw itself into itself). */
    internal fun isInsideSource(c: LayoutCoordinates): Boolean = sources.any { s ->
        var n: LayoutCoordinates? = c
        var inside = false
        while (n != null) { if (n === s.coordinates) { inside = true; break }; n = n.parentCoordinates }
        inside
    }

    /**
     * Draws every attached page's recording, oldest first, each at its page's current position
     * ([offsetOf] maps the page's coordinates to the offset of the surface inside it). During a
     * transition both pages are drawn where they are this frame, so the glass sees what is on screen.
     */
    internal fun DrawScope.drawSources(offsetOf: (LayoutCoordinates) -> Offset) {
        for (s in sources.toList()) {
            val c = s.coordinates ?: continue
            val layer = s.layer ?: continue
            if (layer.isReleased) {
                app.myspoti.host.android.Logbook.log("glass: backdrop source sampled after release, skipped")
                continue
            }
            if (!c.isAttached || layer.size.width <= 0) continue
            val a = s.alpha().coerceIn(0f, 1f)
            if (a <= 0f) continue
            val o = offsetOf(c)
            withTransform({ translate(-o.x, -o.y) }) {
                if (a >= 1f) drawLayer(layer)
                else drawContext.canvas.let { canvas ->
                    // The page's fade, as on screen (the recording itself is always opaque).
                    canvas.saveLayer(androidx.compose.ui.geometry.Rect(Offset.Zero, layer.size.let { androidx.compose.ui.geometry.Size(it.width.toFloat(), it.height.toFloat()) }),
                        androidx.compose.ui.graphics.Paint().apply { alpha = a })
                    drawLayer(layer)
                    canvas.restore()
                }
            }
        }
    }

    override fun DrawScope.drawBackdrop(
        density: Density,
        coordinates: LayoutCoordinates?,
        layerBlock: (GraphicsLayerScope.() -> Unit)?
    ) {
        val coordinates = coordinates ?: return
        moves // read: a page moving or arriving redraws the glass
        withTransform({
            if (layerBlock != null) {
                with(obtainInverseLayerScope()) { inverseTransform(density, layerBlock) }
            }
        }) {
            val offsetOf: (LayoutCoordinates) -> Offset = { src ->
                try {
                    src.localPositionOf(coordinates)
                } catch (_: Exception) {
                    coordinates.positionInWindow() - src.positionInWindow()
                }
            }
            if (sources.isNotEmpty()) drawSources(offsetOf) else drawExported(offsetOf)
        }
    }

    /**
     * A glass exported here (drawBackdrop's exportedBackdrop: the tab bar's glass, for its
     * selection lens) records into [graphicsLayer] and sets [layerCoordinates], with no page source.
     * Reported: a tap or a drag on the Liquid Glass tab bar left the selection pill fully see-through
     * (the bare page, no glass) until it landed and the flat pill took over with a jump: only the
     * page sources were drawn here, so the lens refracted the page, the fill and the tabs, never
     * the bar's glass under them.
     */
    private fun DrawScope.drawExported(offsetOf: (LayoutCoordinates) -> Offset) {
        val c = layerCoordinates ?: return
        if (!c.isAttached || graphicsLayer.isReleased || graphicsLayer.size.width <= 0) return
        val o = offsetOf(c)
        withTransform({ translate(-o.x, -o.y) }) { drawLayer(graphicsLayer) }
    }

    private fun obtainInverseLayerScope(): InverseLayerScope {
        return inverseLayerScope?.apply { reset() }
            ?: InverseLayerScope().also { inverseLayerScope = it }
    }
}
