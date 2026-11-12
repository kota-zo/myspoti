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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
private const val PAUSED_POLL_MS = 250L

/** The word sweep's top rate (word-synced lyrics); the line-synced ones need far fewer (see [LYRICS_LINE_FPS]). */
internal const val LYRICS_WORD_FPS = 60
/** Line-synced lyrics: only a line change moves them (its own animation runs at the screen's rate). */
internal const val LYRICS_LINE_FPS = 20

/** A frame's own time within the period (see rememberFrameClock). */
private const val FRAME_SLACK_MS = 4L

private fun playerPosition(): Long = SongPlayer.getCurrentPosition().coerceAtLeast(0L)

/** Where the song whose words are shown is: during the first half of a mix, the song going out. */
internal var lyricsPosition: () -> Long = ::playerPosition

/**
 * The song position, ticking while [playing]: [fps] frames a second at most (one frame, then a
 * sleep, as [com.music.spotui.ui.components.rememberFrameClock] does), so the screen is not held at
 * its top rate (120 Hz) for the lyrics; the energy saver's lower cap applies too.
 *
 * Gated on the app being on screen: the loop asks for a frame, writes a value that
 * invalidates a drawing and is handed the next one, which is a request to render continuously
 * for as long as it runs. Every start is a fresh run from the latest reading, so neither a
 * pause nor a spell in the background is mistaken for one very long frame.
 */
@Composable
internal fun rememberLyricsPlayhead(trackKey: Any, playing: Boolean, fps: Int = LYRICS_WORD_FPS): LyricsPlayhead {
    val head = remember(trackKey) { LyricsPlayhead(lyricsPosition()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val energy by app.myspoti.power.EnergySettings.state.collectAsStateWithLifecycle()
    val periodMs = 1000L / minOf(fps, energy.policy.lyricsFps).coerceIn(1, 120)
    LaunchedEffect(head, playing, lifecycle, periodMs) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            if (!playing) {
                // Not moving: each reading is the truth, including a seek made while paused.
                while (isActive) {
                    val position = lyricsPosition()
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
                        val position = lyricsPosition()
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
                    // Nothing asks for a frame until the next tick: the LTPO screen can step down.
                    delay((periodMs - FRAME_SLACK_MS).coerceAtLeast(0))
                }
            }
        }
    }
    return head
}
