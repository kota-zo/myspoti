package com.music.spotui.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.navigation.NavController
import com.music.spotui.ui.screens.PlayerScreen
import kotlinx.coroutines.flow.drop

/**
 * The full player, shown over the pages instead of being one of them, like the official
 * app: pulled down, it uncovers the page it was opened from, with the mini player and the
 * tabs already in place, and closing it leaves them there.
 */
object PlayerSheet {
    val open = mutableStateOf(false)

    fun show() { open.value = true }
    fun close() { open.value = false }

    /** The mini player's top edge in the window (px), where the player rises from and goes back to. */
    var miniTop by mutableFloatStateOf(0f)
}

/**
 * How far the player's content has come in while it opens (0 .. 1), read in draw lambdas: the
 * cover, the titles and the controls fade in once the sheet is under way, after its background.
 */
val LocalPlayerReveal = androidx.compose.runtime.staticCompositionLocalOf<() -> Float> { { 1f } }

/*
 * Opening, as the official app: the player grows out of the mini player. Its background rises
 * from the mini player's top edge and fades in over the first part of the way, then the cover,
 * titles and controls fade in and settle a few dp up. Closing plays it backwards: the content
 * fades first, then the sheet sinks into the mini player and fades into it. No dimming of the
 * page behind: darker than the player's own background, it made the screen blink dark then light
 * on the first and last frames (measured on a recording, reported).
 *
 * It replaces a cover flown between the two players (a copy that ran out of step with the real
 * one: a ghost square during the slide, a lone cover crossing the page on the way down) and a
 * slide whose clock started on the frame that built the whole player (its first frames lost
 * to that work: the "jump" at the start).
 */
private const val OPEN_MS = 380
private const val CLOSE_MS = 300
/** The sheet's background is fully there by this share of the way. */
private const val SHEET_FADE = 0.3f
private val CloseEasing = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)

@Composable
fun PlayerOverlay(navController: NavController) {
    // Any navigation from the player (album, artist, playlist…) lands on that page.
    LaunchedEffect(navController) {
        navController.currentBackStackEntryFlow.drop(1).collect { PlayerSheet.close() }
    }
    val open = PlayerSheet.open.value
    val progress = remember { Animatable(0f) }
    var shown by remember { mutableStateOf(false) }
    val settled = remember { mutableStateOf(false) }
    // "Remove animations": the player is there, or gone, at once.
    val reduce = com.music.spotui.ui.look.rememberReduceMotion()
    // The page under the player gives up the keyboard's focus: a search field kept it, and its
    // cursor handle, drawn by the system in a window of its own, stayed over the player.
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    LaunchedEffect(open) {
        if (open) {
            focusManager.clearFocus(force = true)
            shown = true
            settled.value = false
            // Two frames for the player's first composition and layout before the clock runs.
            if (progress.value == 0f) { withFrameNanos { }; withFrameNanos { } }
            if (reduce) progress.snapTo(1f) else progress.animateTo(1f, tween(OPEN_MS, easing = Decelerate))
            settled.value = true
        } else if (shown) {
            settled.value = false
            if (reduce) progress.snapTo(0f) else progress.animateTo(0f, tween(CLOSE_MS, easing = CloseEasing))
            shown = false
        }
    }
    if (!shown) return
    BackHandler(enabled = open) { PlayerSheet.close() }
    var heightPx by remember { mutableIntStateOf(0) }
    val reveal = remember { { ((progress.value - 0.25f) / 0.6f).coerceIn(0f, 1f) } }
    Box(
        Modifier.fillMaxSize().onSizeChanged { heightPx = it.height }.graphicsLayer {
            val p = progress.value
            val from = PlayerSheet.miniTop.takeIf { it > 0f } ?: heightPx.toFloat()
            translationY = (1f - p) * from
            // A layer alpha only over the first part of the way: the open player draws plain.
            alpha = (p / SHEET_FADE).coerceIn(0f, 1f)
        },
    ) {
        CompositionLocalProvider(LocalPageSettled provides settled, LocalPlayerReveal provides reveal) {
            // A podcast episode gets the podcast player (±15 s, speed, sleep timer, no shuffle);
            // everything else keeps the music player.
            // The player's bare texts use the body style as it is (no app-wide letter spacing).
            androidx.compose.material3.ProvideTextStyle(com.music.spotui.ui.theme.Typography.bodyLarge) {
                if (com.music.spotui.di.SongPlayer.isEpisode) {
                    com.music.spotui.ui.screens.PodcastPlayerScreen(navController)
                } else {
                    PlayerScreen(navController)
                }
            }
        }
    }
}
