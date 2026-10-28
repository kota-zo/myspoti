/*
 * Adapted from BitChord (https://github.com/kushagrasinghx/BitChord), GPL-3.0-or-later,
 * sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/PlayerLyrics.kt at edc1e6f.
 * Modified for Myspoti, 2026-10.
 *
 * Changes: BitChord's LyricPlayhead / rememberLyricClock, fed by SongPlayer.getCurrentPosition()
 * (already extrapolated, read once per frame) instead of a twice-a-second stamped reading. A
 * seek is recognised from the position jumping, or announced by a tap on a line.
 */
package com.music.spotui.ui.immersive.lyrics

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableLongState
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.music.spotui.di.SongPlayer
import com.music.spotui.ui.look.LyricClock
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.abs

/**
 * The lyric playhead of one track: [clock] is the song position in milliseconds, ticking on
 * every frame while playing (read it in draw lambdas, never in composition), steered by the
 * player's own position through a [LyricClock].
 */
internal class LyricsPlayhead(startMs: Long) {
    val clock: MutableLongState = mutableLongStateOf(startMs)
    internal val engine = LyricClock(startMs)

    /** Changes whenever the lyrics are meant to jump: a seek, or a tap on a line. */
    var discontinuity: Long = 0L
        private set

    /** The panel is about to seek: the next reading is where the song landed, not a bad one. */
    fun announceJump() {
        discontinuity++
    }

    internal var lastReadMs = startMs
    internal var lastReadAtMs = Double.NaN
}

private fun nanosToMs(nanos: Long): Double = nanos / 1_000_000.0

/** A frame time this close to [System.nanoTime] is on the same clock. */
private const val SAME_CLOCK_NANOS = 250_000_000L

/** A reading this far from where the last one carried on to is a seek, not playback. */
private const val SEEK_JUMP_MS = 800L

/** How often the position is looked at while paused, for a seek made from elsewhere. */
private const val PAUSED_POLL_MS = 120L

private fun playerPosition(): Long = SongPlayer.getCurrentPosition().coerceAtLeast(0L)

/**
 * The song position, ticking every frame while [playing].
 *
 * Gated on the app being on screen: the loop asks for a frame, writes a value that
 * invalidates a drawing and is handed the next one, which is a request to render continuously
 * for as long as it runs. Every start is a fresh run from the latest reading, so neither a
 * pause nor a spell in the background is mistaken for one very long frame.
 */
@Composable
internal fun rememberLyricsPlayhead(trackKey: Any, playing: Boolean): LyricsPlayhead {
    val head = remember(trackKey) { LyricsPlayhead(playerPosition()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(head, playing, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            if (!playing) {
                // Not moving: each reading is the truth, including a seek made while paused.
                while (isActive) {
                    val position = playerPosition()
                    head.engine.hold(position, nanosToMs(System.nanoTime()))
                    head.clock.longValue = position
                    head.lastReadMs = position
                    head.lastReadAtMs = Double.NaN
                    delay(PAUSED_POLL_MS)
                }
            } else {
                head.engine.restart()
                head.lastReadAtMs = Double.NaN
                while (isActive) {
                    withFrameNanos { frameNanos ->
                        // Frame time is preferred for being evenly spaced where it is on the
                        // System.nanoTime clock; anywhere it is not, the real clock is used.
                        val system = System.nanoTime()
                        val now = nanosToMs(if (abs(system - frameNanos) < SAME_CLOCK_NANOS) frameNanos else system)
                        val position = playerPosition()
                        // To play but loading (a seek into a stream, its start): the position stands
                        // still, and so do the words. Carried on at the learned rate, they ran ahead
                        // of the song by the whole wait (seconds with an extension's stream) and then
                        // crept back at half speed: a tapped line seemed not to jump, then the words
                        // were out of step. The next frame that moves starts a fresh run from there.
                        if (!SongPlayer.positionMoving()) {
                            head.engine.hold(position, now)
                            head.clock.longValue = position
                            head.lastReadMs = position
                            head.lastReadAtMs = Double.NaN
                            return@withFrameNanos
                        }
                        if (!head.lastReadAtMs.isNaN() &&
                            abs(position - (head.lastReadMs + (now - head.lastReadAtMs))) > SEEK_JUMP_MS
                        ) {
                            head.announceJump()
                        }
                        head.lastReadMs = position
                        head.lastReadAtMs = now
                        head.clock.longValue = head.engine.frame(
                            nowMs = now,
                            reportedMs = position,
                            sampledAtMs = Double.NaN,
                            discontinuity = head.discontinuity,
                        )
                    }
                }
            }
        }
    }
    return head
}
