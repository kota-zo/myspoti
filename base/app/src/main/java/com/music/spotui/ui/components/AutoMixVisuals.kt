package com.music.spotui.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.myspoti.engine.R
import com.music.spotui.di.LocalPlayer
import com.music.spotui.ui.look.rememberReduceMotion

/**
 * The mix AutoMix has planned for the song playing, or is playing: the players colour its stretch
 * of the seek bar from the moment it is planned and pulse with the music around it. Null with no
 * mix ahead (AutoMix off, a Spotify song, the last song).
 */
@Composable
fun rememberMixCue(): LocalPlayer.MixCue? =
    LocalPlayer.mixCue.value?.takeIf { LocalPlayer.active.value && com.music.spotui.automix.AutoMix.on && com.music.spotui.automix.AutoMix.showInPlayer }

/**
 * Where the mix sits on the bar of the song shown, as fractions of its length: the song going out
 * until half way through the mix (the words then switch, see [SongBlend]), the one coming in after.
 */
fun LocalPlayer.MixCue.zoneOn(durationMs: Long, incomingShown: Boolean): ClosedFloatingPointRange<Float>? {
    if (durationMs <= 0) return null
    val (from, to) = if (mixing && incomingShown) entryMs to resumeMs else exitMs to endMs
    val a = (from.toFloat() / durationMs).coerceIn(0f, 1f)
    val b = (to.toFloat() / durationMs).coerceIn(0f, 1f)
    return if (b - a < 0.002f) null else a..b
}

/**
 * The mix's pulse, read every frame while [cue] shows and the music plays: [level] follows the
 * beats (see [LocalPlayer.mixPulse]); each strong beat sends a ring out of the cover ([rings]).
 * Still (0) with reduce motion.
 */
@Stable
class MixPulse internal constructor() {
    internal val value = mutableFloatStateOf(0f)
    internal val clock = mutableLongStateOf(0L)
    /** Rings out: start time (ns) and strength, oldest first. */
    internal val rings = ArrayDeque<Pair<Long, Float>>()
    private var last = 0f
    private var lastRing = 0L

    /** 0 (still) to 1 (a loud downbeat): read it in a draw or layer block. */
    fun level(): Float = value.floatValue

    internal fun step(now: Long, v: Float) {
        // A beat starting (the level jumps from its decay): a ring, at most one per 180 ms.
        if (v > 0.45f && v > last + 0.2f && now - lastRing > 180_000_000L) {
            rings.addLast(now to v); lastRing = now
            while (rings.size > 4) rings.removeFirst()
        }
        while (rings.isNotEmpty() && now - rings.first().first > RING_NS) rings.removeFirst()
        last = v
        value.floatValue = v
        clock.longValue = now
    }
}

private const val RING_NS = 900_000_000L

@Composable
fun rememberMixPulse(cue: LocalPlayer.MixCue?): MixPulse? {
    val reduceMotion = rememberReduceMotion()
    val on = cue != null && !reduceMotion && com.music.spotui.automix.AutoMix.pulseLevel > 0
    val pulse = remember(on) { if (on) MixPulse() else null }
    LaunchedEffect(pulse) {
        val p = pulse ?: return@LaunchedEffect
        while (true) {
            if (!LocalPlayer.isPlaying()) { p.step(System.nanoTime(), 0f); kotlinx.coroutines.delay(250); continue }
            // Still until the 8 s before the mix (see LocalPlayer.mixPulse): a check a second, no frames.
            val c = LocalPlayer.mixCue.value
            if (c != null && !c.mixing && LocalPlayer.position() < c.exitMs - 9_000) { p.step(System.nanoTime(), 0f); kotlinx.coroutines.delay(1_000); continue }
            val k = if (com.music.spotui.automix.AutoMix.pulseLevel == 1) 0.55f else 1f
            withFrameNanos { p.step(it, LocalPlayer.mixPulse() * k) }
        }
    }
    return pulse
}

/**
 * Around a cover: a glow in [color] that swells with the beat and rings that leave the cover's
 * edge on the strong ones, drawn behind it. [corner] is the cover's corner radius; [strength]
 * (read in the draw) dims it all, e.g. while the cover collapses into a header.
 */
fun Modifier.mixHalo(pulse: MixPulse?, color: Color, corner: Dp, strength: () -> Float = { 1f }): Modifier =
    if (pulse == null) this else drawBehind {
        val now = pulse.clock.longValue
        val k0 = strength().coerceIn(0f, 1f)
        if (k0 <= 0f) return@drawBehind
        val r = corner.toPx()
        val v = pulse.level() * k0
        // The glow: three soft layers, wider and fainter outwards.
        for (k in 1..3) {
            val grow = (6 + 10 * k).dp.toPx() * (0.4f + v)
            glowRect(color.copy(alpha = 0.16f * v / k), grow, r + grow)
        }
        // The rings: out to 28 dp past the edge, fading as they go.
        for ((start, strength) in pulse.rings) {
            val t = ((now - start) / RING_NS.toFloat()).coerceIn(0f, 1f)
            val out = 28.dp.toPx() * easeOut(t)
            val w = 2.dp.toPx() * (1f - t) + 0.5f
            drawRoundRect(
                color.copy(alpha = 0.7f * strength * k0 * (1f - t)),
                topLeft = Offset(-out, -out), size = Size(size.width + 2 * out, size.height + 2 * out),
                cornerRadius = CornerRadius(r + out), style = Stroke(w),
            )
        }
    }

private fun DrawScope.glowRect(color: Color, grow: Float, radius: Float) = drawRoundRect(
    color, topLeft = Offset(-grow, -grow), size = Size(size.width + 2 * grow, size.height + 2 * grow), cornerRadius = CornerRadius(radius),
)

private fun easeOut(t: Float) = 1f - (1f - t) * (1f - t) * (1f - t)

/** The cover beating with the mix: a few percent bigger on each beat. */
fun Modifier.mixBeat(pulse: MixPulse?, amount: Float = 0.035f): Modifier =
    if (pulse == null) this else graphicsLayer {
        val s = 1f + amount * pulse.level()
        scaleX = s; scaleY = s
    }

/**
 * Draws the mix's stretch on a seek bar's track (the canvas holding the bar, [y] its middle): the
 * part still ahead in [color] at half strength, the part played in full, thicker on the beat.
 */
fun DrawScope.drawMixZone(zone: ClosedFloatingPointRange<Float>, played: Float, y: Float, thickness: Float, color: Color, pulse: Float) {
    val x0 = size.width * zone.start; val x1 = size.width * zone.endInclusive
    val h = thickness * (1f + 0.8f * pulse)
    val radius = CornerRadius(h / 2)
    if (pulse > 0f) {
        val g = h * 2.6f
        drawRoundRect(color.copy(alpha = 0.22f * pulse), Offset(x0 - g / 4, y - g / 2), Size(x1 - x0 + g / 2, g), CornerRadius(g / 2))
    }
    drawRoundRect(color.copy(alpha = 0.45f), Offset(x0, y - h / 2), Size(x1 - x0, h), radius)
    val px = (size.width * played).coerceIn(x0, x1)
    if (px > x0) drawRoundRect(color, Offset(x0, y - h / 2), Size(px - x0, h), radius)
}

/**
 * The line under the seek bar that says a mix comes: "Mix into …" from 30 s before it,
 * "Mixing into …" while it plays. [positionMs] is the song playing's position.
 */
@Composable
fun MixHint(cue: LocalPlayer.MixCue?, positionMs: () -> Long, tint: Color, accent: Color, pulse: MixPulse?, modifier: Modifier = Modifier) {
    // Checked twice a second rather than per frame: the line comes 30 s ahead, give or take.
    var near by remember { androidx.compose.runtime.mutableStateOf(false) }
    LaunchedEffect(cue) {
        while (true) {
            near = cue != null && (cue.mixing || cue.skip || cue.exitMs - positionMs() < 30_000)
            if (cue == null) break
            kotlinx.coroutines.delay(500)
        }
    }
    AnimatedVisibility(
        visible = near && cue!!.nextTitle.isNotBlank(),
        enter = fadeIn() + expandVertically(expandFrom = Alignment.Top),
        exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Top),
        modifier = modifier,
    ) {
        val c = remember { mutableLastCue() }
        cue?.let { c.value = it }
        val shown = c.value ?: return@AnimatedVisibility
        Row(
            Modifier
                .background(accent.copy(alpha = 0.16f), RoundedCornerShape(50))
                .padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(Icons.Rounded.GraphicEq, contentDescription = null, tint = accent,
                modifier = Modifier.size(14.dp).mixBeat(pulse, amount = 0.25f))
            Spacer(Modifier.width(6.dp))
            Text(
                stringResource(if (shown.mixing) R.string.automix_hint_mixing else R.string.automix_hint_next, shown.nextTitle),
                color = tint, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private class LastCue { var value: LocalPlayer.MixCue? = null }
private fun mutableLastCue() = LastCue()
