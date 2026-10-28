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

import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.node.requireGraphicsContext
import androidx.compose.ui.platform.InspectorInfo
import com.music.spotui.ui.immersive.shell.backdrop.internal.recordLayer
import kotlinx.coroutines.launch

fun Modifier.layerBackdrop(backdrop: LayerBackdrop): Modifier =
    this then LayerBackdropElement(backdrop)

private class LayerBackdropElement(
    val backdrop: LayerBackdrop
) : ModifierNodeElement<LayerBackdropNode>() {

    override fun create(): LayerBackdropNode {
        return LayerBackdropNode(backdrop)
    }

    override fun update(node: LayerBackdropNode) {
        if (node.backdrop != backdrop) {
            node.backdrop.layerCoordinates = null
            node.backdrop = backdrop
        }
        node.invalidateDraw()
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "layerBackdrop"
        properties["backdrop"] = backdrop
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is LayerBackdropElement) return false

        if (backdrop != other.backdrop) return false

        return true
    }

    override fun hashCode(): Int {
        return backdrop.hashCode()
    }
}

private class LayerBackdropNode(
    var backdrop: LayerBackdrop
) : DrawModifierNode, GlobalPositionAwareModifierNode, androidx.compose.ui.node.CompositionLocalConsumerModifierNode, Modifier.Node() {

    override fun ContentDrawScope.draw() {
        // Vendored change: record once and composite, rather than upstream's
        // draw-then-record.
        //
        // Upstream draws the content to the screen and then draws all of it a
        // second time into the layer, which puts the entire app through the draw
        // pass twice on every frame the glass is switched on — and this node
        // wraps the whole page, so "the entire app" is what that is. Recording
        // first and then blitting the finished layer to the screen produces the
        // same pixels off one pass.
        //
        // The layer is the node's own size, so this does clip anything a child
        // paints outside its bounds. Nothing here does — the pages are lists
        // inside a full-screen container — but a child that grew a shadow past
        // the edge would lose it, which upstream would not.
        // A degenerate size would record an empty layer, and unlike upstream there
        // is no direct draw left to fall back on — the page would simply vanish.
        if (size.width < 1f || size.height < 1f) {
            drawContent()
            return
        }
        // One recording per source, read in the draw phase so every frame of a transition redraws it.
        val layer = source.layer
        if (layer == null || layer.isReleased) {
            if (layer != null) app.myspoti.host.android.Logbook.log("glass: backdrop source drawn after release, drawing the page directly")
            return drawContent()
        }
        recordLayer(this@LayerBackdropNode, layer) { backdrop.onDraw(this@draw) }
        drawLayer(layer)
    }

    private val source = LayerBackdrop.Source()
    private var registeredIn: LayerBackdrop? = null

    override fun onAttach() {
        registeredIn = backdrop
        source.layer = requireGraphicsContext().createGraphicsLayer()
        source.alpha = currentValueOf(LocalBackdropSourceAlpha)
        backdrop.sources.add(source)
        backdrop.moves++
        // Reported: after a tab change the glass kept what it showed until the page was scrolled.
        // The glass draws this page's recording inside its own blurred layer, which the renderer
        // does not redraw when only the page (or a row of it) changes: the recording is drawn in
        // two places and its damage counts in one. A page that just arrived fills in over its first
        // moments (its data, its covers), so the glass is redrawn on every frame while it settles.
        // Only then: a page animating on its own (a playing row) does not keep the blur running.
        coroutineScope.launch {
            val end = android.os.SystemClock.uptimeMillis() + SETTLE_MS
            while (android.os.SystemClock.uptimeMillis() < end) {
                androidx.compose.runtime.withFrameNanos { }
                (registeredIn ?: backdrop).moves++
            }
        }
    }

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        if (coordinates.isAttached) {
            source.coordinates = coordinates
            // The backdrop may have been swapped by update(): follow it.
            if (registeredIn !== backdrop) {
                registeredIn?.retire(source)
                registeredIn = backdrop
                backdrop.sources.add(source)
            }
            backdrop.publish(source)
        }
    }

    override fun onDetach() {
        registeredIn?.retire(source)
        registeredIn = null
        source.coordinates = null
        source.layer?.let { requireGraphicsContext().releaseGraphicsLayer(it) }
        source.layer = null
    }
}

/** How long a page that just arrived keeps redrawing the glass as it fills in. */
private const val SETTLE_MS = 3_000L
