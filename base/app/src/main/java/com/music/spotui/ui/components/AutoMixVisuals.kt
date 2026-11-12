package com.music.spotui.ui.components

import androidx.lifecycle.repeatOnLifecycle
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
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
 * The mix AutoMix has planned for the song playing, or is playing: the players move with the music
 * while it is heard ([rememberMixPulse]). Null with no
 * mix ahead (AutoMix off, a Spotify song, the last song).
 */
@Composable
fun rememberMixCue(): LocalPlayer.MixCue? =
    LocalPlayer.mixCue.value?.takeIf { LocalPlayer.active.value && com.music.spotui.automix.AutoMix.on && com.music.spotui.automix.AutoMix.showInPlayer }

/**
 * How the player moves with an AutoMix mix: it draws in (the content compresses a little) while
 * the mix is heard, the cover beating on the music's beats meanwhile; when the mix is
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
        val t = ((now - releaseAt) / 1e9f).coerceAtLeast(0f)
        // A damped spring from where it was: past its size once, settled within a second.
        return releaseFrom * kotlin.math.exp(-t / 0.22f) * kotlin.math.cos(t * 2f * Math.PI.toFloat() / 0.62f)
    }

    /** The release wave, 0 (leaving the cover) to 1 (gone past the screen's edges); null when none. */
    fun wave(): Float? {
        val now = clock.longValue
        if (releaseAt == 0L) return null
        return ((now - releaseAt) / WAVE_NS.toFloat()).coerceAtLeast(0f).takeIf { it < 1f }
    }

    internal val releasing: Boolean get() = releaseAt != 0L && clock.longValue - releaseAt < RELEASE_NS

    /**
     * Something still moves and needs frames after a mix: the release, a draw-in easing back or a
     * beat fading out (a mix held still by a pause asks for none). The loop slept a second as soon as the mix was over, the release just started: it stood
     * still, then went on from halfway (the spring jumped, the wave came from mid-screen).
     */
    internal val moving: Boolean get() = releasing || (!held && (squeeze != 0f || beat > 0f))

    internal fun step(at: Long, beat: Float, target: Float?) {
        // One clock: the loop's checks read System.nanoTime(), the frames the frame's time, a little
        // earlier; a release started on one and read on the other began before its start.
        val now = maxOf(at, clock.longValue)
        // The same ease whatever the screen's rate (a share per frame was twice as quick at 120 Hz).
        val dt = if (clock.longValue == 0L) 0f else ((now - clock.longValue) / 1e9f).coerceIn(0f, 0.1f)
        val ease = { tau: Float -> 1f - kotlin.math.exp(-dt / tau) }
        if (target != null) {
            this.beat = beat
            // Eased towards the mix's own progress, never jumping on a skip or a seek.
            squeeze += (target - squeeze) * ease(0.13f)
            held = true; releaseAt = 0L
        } else {
            // After the mix the cover's beat fades out (it fell back to its size at once).
            this.beat = if (beat >= this.beat) beat else (this.beat + (beat - this.beat) * ease(0.12f)).let { if (it < 0.002f) 0f else it }
            if (held) {
                // The mix is over (or was left): let go from where it is.
                held = false
                if (squeeze > 0.15f) { releaseAt = now; releaseFrom = squeeze; squeeze = 0f }
            }
            // A slight draw-in (a short mix) eases back instead of snapping to its size.
            if (squeeze != 0f) squeeze = (squeeze * (1f - ease(0.13f))).let { if (kotlin.math.abs(it) < 0.002f) 0f else it }
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
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    // On screen only: the loop's checks went on with the app in the background.
    LaunchedEffect(pulse, lifecycle) {
        val p = pulse ?: return@LaunchedEffect
        lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) { while (true) {
            val c = LocalPlayer.mixCue.value
            // Only while the mix is heard: the content drawing in for the 8 s before it moved the
            // player with nothing to hear yet, and a seek or a skip out of that stretch then played
            // the release (the spring back and the wave) for a mix that never happened.
            val mixing = c != null && c.mixing
            // No mix heard and nothing moving: a check a second, no frames. The step that sees the mix
            // over starts the release, which runs on frames from there.
            if (!mixing && !p.moving) {
                p.step(System.nanoTime(), 0f, null)
                if (p.moving) continue
                kotlinx.coroutines.delay(if (c == null) 400 else 1_000); continue
            }
            if (!LocalPlayer.isPlaying() && !p.moving) { kotlinx.coroutines.delay(250); continue }
            val k = if (com.music.spotui.automix.AutoMix.pulseLevel == 1) 0.55f else 1f
            withFrameNanos { now ->
                // Drawn in along the mix. A quick one (a cut, a skip) moves as far as its length
                // allows: the whole draw-in and the spring back squeezed into a second or two read as a jolt.
                val target = LocalPlayer.mixCue.value?.takeIf { it.mixing }?.let {
                    ((it.endMs - it.exitMs) / 8_000f).coerceIn(0.3f, 1f) * smooth(LocalPlayer.fadeProgress())
                }
                p.step(now, if (target != null) LocalPlayer.mixPulse() * k else 0f, target?.times(k))
            }
        } }
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
