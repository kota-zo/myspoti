package com.music.spotui.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.drawWithContent
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
 * How the player moves with an AutoMix mix: it draws in (the content compresses a little) from the
 * 8 s before the mix to its end, the cover beating on the music's beats meanwhile; when the mix is
 * over it lets go: the content springs back past its size and settles, and a soft wave leaves the
 * cover and crosses the screen. Read every frame while a mix is near or the release plays, else
 * still (no frames). Nothing moves with reduce motion or the animation off.
 */
@Stable
class MixPulse internal constructor() {
    internal val clock = mutableLongStateOf(0L)
    private var beat = 0f
    private var squeeze = 0f
    private var held = false
    /** When the release started (ns), 0 = none, and how drawn in the content was then. */
    private var releaseAt = 0L
    private var releaseFrom = 0f

    /** 0 (still) to 1 (a loud downbeat): read it in a draw or layer block. */
    fun level(): Float { clock.longValue; return beat }

    /** How drawn in the content is, 0 to 1 (below 0 while it springs back past its size). */
    fun squeeze(): Float {
        val now = clock.longValue
        if (releaseAt == 0L) return squeeze
        val t = (now - releaseAt) / 1e9f
        // A damped spring from where it was: past its size once, settled within a second.
        return releaseFrom * kotlin.math.exp(-t / 0.22f) * kotlin.math.cos(t * 2f * Math.PI.toFloat() / 0.62f)
    }

    /** The release wave, 0 (leaving the cover) to 1 (gone past the screen's edges); null when none. */
    fun wave(): Float? {
        val now = clock.longValue
        if (releaseAt == 0L) return null
        return ((now - releaseAt) / WAVE_NS.toFloat()).takeIf { it < 1f }
    }

    internal val releasing: Boolean get() = releaseAt != 0L && clock.longValue - releaseAt < RELEASE_NS

    internal fun step(now: Long, beat: Float, target: Float?) {
        this.beat = beat
        if (target != null) {
            // Eased towards the mix's own progress, never jumping on a skip or a seek.
            squeeze += (target - squeeze) * 0.12f
            held = true; releaseAt = 0L
        } else if (held) {
            // The mix is over (or was left): let go from where it is.
            held = false
            if (squeeze > 0.15f) { releaseAt = now; releaseFrom = squeeze }
            squeeze = 0f
        }
        if (releaseAt != 0L && now - releaseAt >= RELEASE_NS) releaseAt = 0L
        clock.longValue = now
    }
}

private const val WAVE_NS = 1_300_000_000L
private const val RELEASE_NS = 1_400_000_000L

@Composable
fun rememberMixPulse(cue: LocalPlayer.MixCue?): MixPulse? {
    val reduceMotion = rememberReduceMotion()
    // Kept across mixes (not only while one is near): the release plays after its cue is gone.
    val on = !reduceMotion && com.music.spotui.automix.AutoMix.on && com.music.spotui.automix.AutoMix.showInPlayer &&
        com.music.spotui.automix.AutoMix.pulseLevel > 0
    val pulse = remember(on) { if (on) MixPulse() else null }
    LaunchedEffect(pulse) {
        val p = pulse ?: return@LaunchedEffect
        while (true) {
            val c = LocalPlayer.mixCue.value
            val near = c != null && (c.mixing || LocalPlayer.position() >= c.exitMs - 9_000)
            // Nothing near and nothing letting go: a check a second, no frames.
            if (!near && !p.releasing) { p.step(System.nanoTime(), 0f, null); kotlinx.coroutines.delay(if (c == null) 400 else 1_000); continue }
            if (!LocalPlayer.isPlaying() && !p.releasing) { kotlinx.coroutines.delay(250); continue }
            val k = if (com.music.spotui.automix.AutoMix.pulseLevel == 1) 0.55f else 1f
            withFrameNanos { now ->
                val cue = LocalPlayer.mixCue.value
                // Drawn in a quarter over the 8 s before the mix, the rest along it.
                val target = cue?.let {
                    if (it.mixing) 0.25f + 0.75f * smooth(LocalPlayer.fadeProgress())
                    else 0.25f * smooth(((LocalPlayer.position() - (it.exitMs - 8_000)) / 8_000f).coerceIn(0f, 1f))
                }
                p.step(now, LocalPlayer.mixPulse() * k, target?.times(k))
            }
        }
    }
    return pulse
}

private fun smooth(t: Float) = t * t * (3 - 2 * t)

/**
 * The player's content drawing in with the mix and springing back after it ([amount]: the scale
 * lost when fully drawn in). Put it on the content above the backdrop, which stays full screen.
 */
fun Modifier.mixSqueeze(pulse: MixPulse?, amount: Float = 0.05f): Modifier =
    if (pulse == null) this else graphicsLayer {
        val s = 1f - amount * pulse.squeeze()
        scaleX = s; scaleY = s
    }

/**
 * The release wave over the content: a wide soft band of [color] leaving [center] (fractions of
 * the box, where the cover is) and fading as it crosses the screen.
 */
fun Modifier.mixWave(pulse: MixPulse?, color: Color, center: Offset = Offset(0.5f, 0.32f)): Modifier =
    if (pulse == null) this else drawWithContent {
        drawContent()
        val t = pulse.wave() ?: return@drawWithContent
        val c = Offset(size.width * center.x, size.height * center.y)
        val reach = kotlin.math.hypot(size.width.toDouble(), size.height.toDouble()).toFloat()
        val r = reach * easeOut(t)
        val band = 140.dp.toPx() * (0.6f + 0.4f * t)
        val alpha = 0.22f * (1f - t) * (1f - t)
        if (r <= 1f || alpha <= 0.005f) return@drawWithContent
        val outer = r + band / 2
        val stop = { d: Float -> (d / outer).coerceIn(0f, 1f) }
        drawCircle(
            Brush.radialGradient(
                0f to Color.Transparent,
                stop(r - band / 2) to Color.Transparent,
                stop(r) to color.copy(alpha = alpha),
                1f to Color.Transparent,
                center = c, radius = outer,
            ),
            radius = outer, center = c,
        )
    }

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

/**
 * The local player's speed when it is not 1× ("1.5×"), under the seek bar: a speed left at 2× in
 * the equalizer page made downloads sound broken with nothing on screen to say why (#137). A tap
 * puts it back to 1×.
 */
@Composable
fun PlaybackSpeedNote(tint: Color, accent: Color, modifier: Modifier = Modifier) {
    val speed = com.music.spotui.data.preferences.EqualizerPref.speed
    if (!LocalPlayer.active.value || kotlin.math.abs(speed - 1f) < 0.01f) return
    val ctx = androidx.compose.ui.platform.LocalContext.current
    Text(
        stringResource(R.string.playback_speed_note, if (speed % 1f == 0f) "%.0f".format(speed) else "%.2f".format(speed).trimEnd('0')),
        color = tint, fontSize = 12.sp,
        modifier = modifier
            .background(accent.copy(alpha = 0.16f), RoundedCornerShape(50))
            .clickable { com.music.spotui.data.preferences.EqualizerPref.setSpeed(ctx, 1f) }
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}
