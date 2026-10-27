/*
 * Adapted from BitChord (https://github.com/kushagrasinghx/BitChord), GPL-3.0-or-later,
 * app/src/main/java/com/music/bitchord/ui/player/PlayerSheetMotion.kt and
 * sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/PlayerDock.kt at edc1e6f.
 * Modified for Myspoti, 2026-10.
 *
 * Changes: only the sheet's motion is kept: it opens and closes on one critically damped spring
 * (stiffness 195) and a pull down carries the finger's speed into it. The Material sheet window,
 * the window animations and the cover docking into the mini player are left out.
 */
package com.music.spotui.ui.immersive.player

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.music.spotui.di.SongPlayer
import com.music.spotui.ui.immersive.shell.LocalImmersiveBackdrop
import com.music.spotui.ui.immersive.shell.LocalImmersiveFrostPage
import com.music.spotui.ui.immersive.shell.backdrop.backdrops.LocalBackdropSourceAlpha
import com.music.spotui.ui.immersive.shell.backdrop.backdrops.layerBackdrop
import com.music.spotui.ui.immersive.shell.backdrop.backdrops.rememberLayerBackdrop
import com.music.spotui.ui.navigation.LocalPageSettled
import com.music.spotui.ui.navigation.PlayerSheet
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** The slide's spring: no bounce, a long soft landing. */
private const val SLIDE_STIFFNESS = 195f

/** The slide counts as settled within this many pixels of its target. */
private const val SLIDE_SETTLED_PX = 0.5f

/** A release faster than this (px/s) decides the sheet on its own, either way. */
private const val FLING_VELOCITY = 1_000f

private val PlayerCardShape = androidx.compose.foundation.shape.RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)

private fun slideSpec(reduce: Boolean) = com.music.spotui.ui.immersive.motion.ImmersiveMotion.slideSpring(reduce)

/**
 * The Immersive look's player over the pages, a drop-in for [com.music.spotui.ui.navigation.PlayerOverlay]:
 * driven by [PlayerSheet] (`open`, `show()`, `close()`), it rises on a spring when opened, follows
 * a pull down and closes with the finger's speed, closes on the back button and on any navigation.
 * A podcast episode gets [ImmersivePodcastPlayer].
 */
@Composable
fun ImmersivePlayerOverlay(navController: NavController) {
    // Any navigation from the player (album, artist, playlist…) lands on that page.
    LaunchedEffect(navController) {
        navController.currentBackStackEntryFlow.drop(1).collect { PlayerSheet.close() }
    }
    val open = PlayerSheet.open.value
    val scope = rememberCoroutineScope()
    val reduce = com.music.spotui.ui.look.rememberReduceMotion()
    var heightPx by remember { mutableIntStateOf(0) }
    // How far down the sheet is (px): 0 open, the window's height away.
    val offset = remember { Animatable(0f) }
    var shown by remember { mutableStateOf(false) }
    // Invisible until it has been put below the window: no frame of it at rest before the rise.
    var primed by remember { mutableStateOf(false) }
    // The finger's hold on the sheet, or NaN when it has none.
    var held by remember { mutableFloatStateOf(Float.NaN) }
    var closeVelocity by remember { mutableFloatStateOf(0f) }
    val settled = remember { mutableStateOf(false) }

    LaunchedEffect(open) {
        if (open) {
            val wasShown = shown
            shown = true
            if (!wasShown) {
                settled.value = false
                snapshotFlow { heightPx }.first { it > 0 }
                offset.snapTo(heightPx.toFloat())
                primed = true
            }
            offset.animateTo(0f, slideSpec(reduce))
            settled.value = true
        } else if (shown) {
            settled.value = false
            val velocity = closeVelocity.coerceAtLeast(0f)
            closeVelocity = 0f
            offset.animateTo(heightPx.toFloat(), slideSpec(reduce), initialVelocity = velocity)
            shown = false
            primed = false
        }
    }
    if (!shown) return
    BackHandler(enabled = open) { PlayerSheet.close() }
    // Compose's clickable asks the view for the system "click" sound on every tap; the Classic player
    // is silent, so the window's sound effects are off while this player is up (haptics are separate).
    val view = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.DisposableEffect(view) {
        val was = view.isSoundEffectsEnabled
        view.isSoundEffectsEnabled = false
        onDispose { view.isSoundEffectsEnabled = was }
    }

    val sheetDrag = remember {
        object : PlayerSheetDrag {
            override fun drag(delta: Float) {
                if (!PlayerSheet.open.value) return
                if (held.isNaN()) held = offset.value
                held = (held + delta).coerceIn(0f, heightPx.toFloat())
            }

            override fun release(velocity: Float) {
                if (held.isNaN()) return
                val from = held
                val close = when {
                    velocity >= FLING_VELOCITY -> true
                    velocity <= -FLING_VELOCITY -> false
                    else -> from > heightPx / 2f
                }
                scope.launch {
                    offset.snapTo(from)
                    held = Float.NaN
                    if (close) {
                        // The finger's own speed goes into the spring: the sheet carries on as it was moving.
                        closeVelocity = velocity
                        PlayerSheet.close()
                    } else {
                        offset.animateTo(0f, slideSpec(reduce), initialVelocity = velocity)
                    }
                }
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { heightPx = it.height }
            .graphicsLayer {
                val y = if (held.isNaN()) offset.value else held
                translationY = y
                // The player rises as a card with rounded top corners, square once it is home.
                if (y > 0.5f) {
                    clip = true
                    shape = PlayerCardShape
                }
                alpha = if (primed) 1f else 0f
            }
            // Nothing under the player may take a touch the player leaves alone.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
    ) {
        // The glass sheets opened from the player (sound quality, devices…) sample the player, not
        // the page under it: the player records itself and stands in for the page recording.
        // Nothing recorded while neither Liquid Glass nor the recorded frost reads it.
        val outerGlass = LocalImmersiveBackdrop.current
        val outerFrost = LocalImmersiveFrostPage.current
        val playerPage = if (outerGlass != null || outerFrost != null) rememberLayerBackdrop() else null
        CompositionLocalProvider(
            LocalPageSettled provides settled,
            LocalPlayerSheetDrag provides sheetDrag,
            LocalImmersiveBackdrop provides outerGlass?.let { playerPage },
            LocalImmersiveFrostPage provides outerFrost?.let { playerPage },
            // Hidden until primed, like the player itself: no frame of it at rest in the glass.
            LocalBackdropSourceAlpha provides { if (primed) 1f else 0f },
        ) {
            Box(Modifier.fillMaxSize().then(if (playerPage != null) Modifier.layerBackdrop(playerPage) else Modifier)) {
                // A podcast episode gets the podcast player (15 s skips, speed); everything else the music one.
                if (SongPlayer.isEpisode) {
                    ImmersivePodcastPlayer(navController)
                } else {
                    val bridge = rememberImmersivePlayer(navController)
                    ImmersivePlayer(bridge.state, bridge.actions, navController = navController)
                }
            }
        }
    }
}
