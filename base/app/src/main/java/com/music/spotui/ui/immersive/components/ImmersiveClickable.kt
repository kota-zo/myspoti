/*
 * Adapted from BitChord (https://github.com/kushagrasinghx/BitChord), GPL-3.0-or-later,
 * sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/components/Common.kt (SignInBanner's press spring) at edc1e6f.
 * Modified for Myspoti, 2026-10.
 */
package com.music.spotui.ui.immersive.components

import com.music.spotui.ui.components.secondaryClick
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role

/**
 * Tap and long-press for cards, buttons and rows of the Immersive look: no ripple, the element
 * shrinks to [pressedScale] under the finger and springs back. The long press gives a haptic tick.
 */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.immersiveClickable(
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    pressedScale: Float = 0.97f,
    enabled: Boolean = true,
    role: Role? = Role.Button,
): Modifier = composed {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = com.music.spotui.ui.immersive.motion.ImmersiveMotion.pressSpring(),
        label = "immersiveClickableScale",
    )
    val haptics = LocalHapticFeedback.current
    this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        // A mouse's right click opens the same menu as the long press (DeX, tablets).
        .secondaryClick(if (enabled) onLongClick else null)
        .combinedClickable(
            interactionSource = interaction,
            indication = null,
            enabled = enabled,
            role = role,
            onClick = onClick,
            onLongClick = onLongClick?.let { long ->
                {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    long()
                }
            },
        )
}
