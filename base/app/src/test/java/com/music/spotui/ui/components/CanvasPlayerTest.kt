package com.music.spotui.ui.components

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The Canvas player gives its decoder back when the app leaves the screen (a paused but prepared
 * player kept a hardware video decoder, counted and powered as video playing in the background)
 * and loads the same clip again when the app comes back.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class CanvasPlayerTest {
    private val clip = "file:///canvas/clip.mp4"
    private var player: ExoPlayer? = null

    private fun prepared(): ExoPlayer = ExoPlayer.Builder(RuntimeEnvironment.getApplication()).build().also { p ->
        player = p
        p.setMediaItem(MediaItem.fromUri(clip))
        p.prepare()
        p.playWhenReady = true
        CanvasPlayer.attach(p, clip)
    }

    @After fun release() { player?.release() }

    @Test fun inTheBackgroundThePlayerHoldsNoDecoder() {
        val p = prepared()
        assertNotEquals(Player.STATE_IDLE, p.playbackState)
        CanvasPlayer.onBackground()
        // Stopped: its renderers (and so their codecs) are released, the clip stays its item.
        assertEquals(Player.STATE_IDLE, p.playbackState)
        assertFalse(p.playWhenReady)
        assertEquals(1, p.mediaItemCount)
        assertFalse(CanvasPlayer.isReady(clip))
    }

    @Test fun backOnScreenTheSameClipIsLoadedAgain() {
        val p = prepared()
        CanvasPlayer.onBackground()
        CanvasPlayer.onForeground()
        assertNotEquals(Player.STATE_IDLE, p.playbackState)
        assertEquals(clip, p.currentMediaItem?.localConfiguration?.uri.toString())
    }

    @Test fun backgroundTwiceAndForegroundWithoutBackgroundChangeNothing() {
        val p = prepared()
        CanvasPlayer.onForeground()
        assertNotEquals(Player.STATE_IDLE, p.playbackState)
        CanvasPlayer.onBackground()
        CanvasPlayer.onBackground()
        assertEquals(Player.STATE_IDLE, p.playbackState)
    }
}
