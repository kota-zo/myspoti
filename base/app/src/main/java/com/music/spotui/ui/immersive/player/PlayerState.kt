/*
 * Adapted from BitChord (https://github.com/kushagrasinghx/BitChord), GPL-3.0-or-later,
 * sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/PlayerState.kt at edc1e6f.
 * Modified for Myspoti, 2026-10.
 *
 * Changes: the scrubber's hold-after-release and the volume bar's state, with the position from
 * Myspoti's playback and the volume from Android's AudioManager (STREAM_MUSIC).
 */
package com.music.spotui.ui.immersive.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * How close the player's reported position has to get to a released scrub handle before the
 * handle stops being drawn where it was dropped.
 */
internal const val SEEK_SETTLE_TOLERANCE_MS = 1_500L

/** How long that handle is held at the drop point regardless: a backstop for a seek that never settles. */
internal const val SEEK_SETTLE_TIMEOUT_MS = 4_000L

/** The scrubber's finger state: where the handle is drawn while it is held, and right after. */
@Stable
internal class PlayerScrub {
    var scrubbing by mutableStateOf(false)
        private set

    var value by mutableFloatStateOf(0f)
        private set

    /**
     * After releasing the scrubber the player needs to buffer before it reports the new position.
     * Kept showing where the user dropped it so the handle doesn't snap back and then jump forward.
     */
    var pendingSeek by mutableStateOf<Float?>(null)

    /** Where the handle is drawn. */
    fun shown(positionMs: Long, durationMs: Long): Float {
        val fraction = if (durationMs > 0) positionMs.toFloat() / durationMs else 0f
        return when {
            scrubbing -> value
            pendingSeek != null -> pendingSeek!!
            else -> fraction.coerceIn(0f, 1f)
        }
    }

    fun drag(to: Float) {
        scrubbing = true
        value = to
    }

    fun release(seekTo: (Float) -> Unit) {
        pendingSeek = value
        seekTo(value)
        scrubbing = false
    }
}

/**
 * Released as soon as the player's own position agrees with where the handle was dropped, and
 * unconditionally a few seconds later whether it agrees or not (a clamped or rejected seek would
 * otherwise freeze the handle for the rest of the track).
 */
@Composable
internal fun rememberPlayerScrub(trackKey: Any, position: () -> Long, durationMs: Long): PlayerScrub {
    val scrub = remember { PlayerScrub() }
    val currentPosition by androidx.compose.runtime.rememberUpdatedState(position)
    LaunchedEffect(durationMs, scrub.pendingSeek) {
        val target = scrub.pendingSeek ?: return@LaunchedEffect
        if (durationMs <= 0) return@LaunchedEffect
        val goal = (target * durationMs).toLong()
        // The playhead is not Compose state: sampled a few times a second until it agrees.
        while (abs(currentPosition() - goal) >= SEEK_SETTLE_TOLERANCE_MS) delay(150)
        scrub.pendingSeek = null
    }
    LaunchedEffect(scrub.pendingSeek) {
        if (scrub.pendingSeek == null) return@LaunchedEffect
        delay(SEEK_SETTLE_TIMEOUT_MS)
        scrub.pendingSeek = null
    }
    LaunchedEffect(trackKey) { scrub.pendingSeek = null }
    return scrub
}

/**
 * The volume bar's state: the system music level, tweened to what the system reports, followed
 * under a finger. While a Spotify Connect device plays, the bar is that device's volume, as the
 * volume keys are (reported: the bar only changed the phone's volume).
 */
@Stable
internal class PlayerVolume(
    private val audio: AudioManager,
    private val scope: CoroutineScope,
) {
    private val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)

    /** A hardware step is a jump of 1/15th of the bar, which reads as a stutter unless tweened. */
    val level = Animatable(read())
    var dragging by mutableStateOf(false)
        private set

    private val remote: Boolean get() = com.music.spotui.di.SongPlayer.activeDevice.value != null

    fun read(): Float =
        if (remote) com.music.spotui.di.SongPlayer.remoteVolume / 100f
        else audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max

    fun drag(to: Float) {
        dragging = true
        // Follow the finger exactly; only external changes tween.
        scope.launch { level.snapTo(to) }
        if (remote) {
            val v = (to * 100).roundToInt().coerceIn(0, 100)
            if (v != com.music.spotui.di.SongPlayer.remoteVolume) com.music.spotui.di.SongPlayer.setRemoteVolume(v)
        } else {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, (to * max).roundToInt().coerceIn(0, max), 0)
        }
    }

    fun release() {
        dragging = false
    }
}

/** The system music volume as a bar value, following the hardware keys and the system panel. */
@Composable
internal fun rememberPlayerVolume(): PlayerVolume {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val volume = remember(context) {
        PlayerVolume(context.getSystemService(Context.AUDIO_SERVICE) as AudioManager, scope)
    }
    var system by remember { mutableFloatStateOf(volume.read()) }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                if (intent?.getIntExtra(EXTRA_STREAM_TYPE, -1) == AudioManager.STREAM_MUSIC) system = volume.read()
            }
        }
        androidx.core.content.ContextCompat.registerReceiver(context, receiver, IntentFilter(VOLUME_CHANGED_ACTION), androidx.core.content.ContextCompat.RECEIVER_EXPORTED)
        onDispose { context.unregisterReceiver(receiver) }
    }
    // Read here so the bar follows a Connect device appearing and its reported volume.
    val remote = com.music.spotui.di.SongPlayer.activeDevice.value != null
    val remoteLevel = com.music.spotui.di.SongPlayer.remoteVolume
    // Glide to the level the system (or the device) reports, but never fight the finger.
    LaunchedEffect(system, remote, remoteLevel) {
        if (!volume.dragging) volume.level.animateTo(volume.read(), tween(durationMillis = 220, easing = FastOutSlowInEasing))
    }
    return volume
}

// The system's volume broadcast has no public constant.
private const val VOLUME_CHANGED_ACTION = "android.media.VOLUME_CHANGED_ACTION"
private const val EXTRA_STREAM_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE"
