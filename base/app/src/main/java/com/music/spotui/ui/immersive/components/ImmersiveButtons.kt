/*
 * Adapted from BitChord (https://github.com/kushagrasinghx/BitChord), GPL-3.0-or-later,
 * sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/components/Common.kt (MessageState)
 * and sharedUi/.../ui/components/Skeletons.kt (the Play / Shuffle pair's metrics) at edc1e6f. Modified for Myspoti, 2026-10.
 */
package com.music.spotui.ui.immersive.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.myspoti.engine.R
import com.music.spotui.ui.components.GlyphSwap
import com.music.spotui.ui.immersive.Immersive
import com.music.spotui.ui.immersive.motion.ImmersiveMotion
import com.music.spotui.ui.look.rememberReduceMotion

/** The height of the page action buttons (Play / Shuffle), and what their skeleton reserves. */
val ImmersiveActionButtonHeight = 50.dp

/**
 * A button of the Immersive look: an optional [icon] and a [text] on a [Immersive.Raised] fill in
 * the accent colour; [filled] swaps it for an accent fill with white content (the primary action).
 * It shrinks a little under the finger.
 */
@Composable
fun ImmersiveButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    filled: Boolean = false,
    shape: Shape = Immersive.Button,
    enabled: Boolean = true,
) {
    // Lighting up (Shuffle on) fades the fill and the content instead of swapping them in one frame.
    val reduce = rememberReduceMotion()
    val content by animateColorAsState(if (filled) Color.White else Immersive.Accent, stateFade(reduce), label = "buttonContent")
    val fill by animateColorAsState(if (filled) Immersive.Accent else Immersive.Raised, stateFade(reduce), label = "buttonFill")
    Row(
        modifier = modifier
            .immersiveClickable(onClick = onClick, enabled = enabled)
            .height(ImmersiveActionButtonHeight)
            .clip(shape)
            .background(fill)
            .padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            // Play turning into Pause: the glyphs swap as the player's do.
            GlyphSwap(icon) { Icon(it, contentDescription = null, tint = content, modifier = Modifier.size(22.dp)) }
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text = text,
            style = Immersive.BodyStrong.copy(color = content, fontWeight = FontWeight.SemiBold),
            maxLines = 1,
        )
    }
}

/** A round 44 dp icon button on [Immersive.Raised] (a download toggle, a follow, a share). */
@Composable
fun ImmersiveCircleButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = Immersive.Accent,
    size: androidx.compose.ui.unit.Dp = 44.dp,
) {
    // A toggle (save, follow, download) changes glyph and colour in a short spring and fade
    // instead of in one frame; nothing runs on first display.
    val shownTint by animateColorAsState(tint, stateFade(rememberReduceMotion()), label = "circleTint")
    Box(
        modifier = modifier
            .immersiveClickable(onClick = onClick)
            .size(size)
            .clip(CircleShape)
            .background(Immersive.Raised),
        contentAlignment = Alignment.Center,
    ) {
        GlyphSwap(icon) { Icon(it, contentDescription = contentDescription, tint = shownTint, modifier = Modifier.size(22.dp)) }
    }
}

/** A colour changing with a control's state: a short fade, a cut under "reduce motion". */
private fun stateFade(reduce: Boolean): androidx.compose.animation.core.AnimationSpec<Color> =
    if (reduce) snap() else tween(ImmersiveMotion.FADE_MS)

/**
 * The Play / Shuffle pair under an album's or a playlist's header: two equal-width buttons, side
 * by side, inset by the page gutter. Hide it until there is something to play.
 *
 * `ImmersivePlayShuffleButtons(onPlay = { play(tracks) }, onShuffle = { play(tracks.shuffled()) })`
 */
@Composable
fun ImmersivePlayShuffleButtons(
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Immersive.Gutter),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ImmersiveButton(
            text = stringResource(R.string.immersive_shell_play),
            icon = Icons.Rounded.PlayArrow,
            onClick = onPlay,
            modifier = Modifier.weight(1f),
        )
        ImmersiveButton(
            text = stringResource(R.string.immersive_shell_shuffle),
            icon = Icons.Rounded.Shuffle,
            onClick = onShuffle,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * An empty or error state: a centred [message] and, with [actionLabel] and [onAction], a button
 * under it ("Try again").
 */
@Composable
fun ImmersiveMessageState(
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Immersive.Gutter + 6.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = message,
            style = Immersive.Body.copy(color = Immersive.TextSecondary),
            textAlign = TextAlign.Center,
        )
        if (actionLabel != null && onAction != null) {
            ImmersiveButton(text = actionLabel, onClick = onAction, filled = true)
        }
    }
}
