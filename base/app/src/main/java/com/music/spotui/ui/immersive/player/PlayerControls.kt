/*
 * Adapted from BitChord (https://github.com/kushagrasinghx/BitChord), GPL-3.0-or-later,
 * sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/PlayerControls.kt and
 * sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/theme/ArtworkPalette.kt at edc1e6f.
 * Modified for Myspoti, 2026-10.
 *
 * Changes: the scrubber, transport, volume row, round glyph discs and the bottom row. Quality
 * badges, Automix, lossless, the output/party capsule and the autoplay toggle are left out;
 * the glyphs are Material Icons Rounded; the pale player tint is read from the cover colours
 * the mesh backdrop already extracted, and the part of the palette code made of colours
 * published by a streaming service is dropped.
 */
package com.music.spotui.ui.immersive.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.automirrored.rounded.VolumeDown
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils
import app.myspoti.engine.R
import com.music.spotui.di.Haptics
import com.music.spotui.ui.immersive.Immersive
import com.music.spotui.ui.look.MeshPalette
import com.music.spotui.ui.look.ThinSlider
import com.music.spotui.ui.components.rememberFrameClock
import kotlin.math.roundToInt

/**
 * The colour every secondary line and glyph in the player is set in: the cover's own colour
 * lifted pale rather than a translucent white. White wherever nothing provides it. The title,
 * transport, seek and volume bars stay white on purpose.
 */
internal val LocalPlayerTint = compositionLocalOf { Color.White }

private const val PLAYER_TINT_LIGHTNESS = 0.88f
private const val PLAYER_TINT_MAX_SATURATION = 0.40f

/** Below this a swatch is black, white or grey to the eye, whatever hue HSL assigns it. */
private const val TINT_MIN_CHROMA = 0.08f

/**
 * The pale tint of [palette]'s first colour that is actually visible (real chroma), or white for
 * a cover with no colour worth echoing: a near-black with a trace of red is "pink" at full
 * saturation in HSL, and lifting it pale would put a pink on the player that is nowhere on the cover.
 */
internal fun playerTintOf(palette: MeshPalette): Color {
    val source = palette.colors.firstOrNull { maxOf(it.red, it.green, it.blue) - minOf(it.red, it.green, it.blue) >= TINT_MIN_CHROMA }
        ?: return Color.White
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(source.toArgb(), hsl)
    hsl[1] = hsl[1].coerceIn(0.18f, PLAYER_TINT_MAX_SATURATION)
    hsl[2] = PLAYER_TINT_LIGHTNESS
    return Color(ColorUtils.HSLToColor(hsl))
}

/** m:ss. */
internal fun formatTime(ms: Long): String {
    val total = (ms.coerceAtLeast(0L) / 1000L).toInt()
    return "%d:%02d".format(total / 60, total % 60)
}

/**
 * The seek bar, with elapsed and remaining either side under it. The playhead is read per tick,
 * in the bar's draw and in the two times, so a tick redraws the bar and recomposes only the times,
 * not the player the bar sits in.
 */
@Composable
internal fun PlayerScrubber(
    state: ImmersivePlayerState,
    onSeek: (Long) -> Unit,
) {
    val scrub = rememberPlayerScrub(state.title, state.positionMs, state.durationMs)
    // As many ticks as the bar has pixels to move, as the Classic player's: ~5 a second on a long song.
    val frame = rememberFrameClock(state.playing && !state.buffering, fps = 15)
    // A crossfade: the times go from the outgoing song to the new one half way, with the
    // credits, dimmed through the switch rather than jumping at full strength.
    Column(Modifier.fillMaxWidth().graphicsLayer { alpha = state.blend?.let { 0.3f + 0.7f * it.wordsAlpha() } ?: 1f }) {
        ThinSlider(
            value = { frame.value; scrub.shown(state.positionMs(), state.durationMs) },
            onValueChange = scrub::drag,
            onValueChangeFinished = { scrub.release { onSeek((it * state.durationMs).toLong()) } },
            loading = state.buffering && !scrub.scrubbing,
        )
        ScrubTimes(frame, scrub, state)
    }
}

/** The elapsed and remaining times under the seek bar, read per tick of [frame]. */
@Composable
private fun ScrubTimes(frame: State<Long>, scrub: PlayerScrub, state: ImmersivePlayerState) {
    frame.value
    val shown = scrub.shown(state.positionMs(), state.durationMs)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // The slider's touch target extends well past the drawn bar: pull the labels back up under it.
            .offset(y = (-9).dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        val duration = state.durationMs.coerceAtLeast(0L)
        val elapsed = (shown * duration).toLong()
        Text(
            text = formatTime(elapsed),
            style = Immersive.Caption,
            color = LocalPlayerTint.current.copy(alpha = 0.55f),
        )
        Text(
            text = "-" + formatTime(duration - elapsed),
            style = Immersive.Caption,
            color = LocalPlayerTint.current.copy(alpha = 0.55f),
        )
    }
}

/**
 * Translucent circular button used for the track menu and the like control. [active] brightens
 * the disc rather than only the glyph: it sits on artwork of any colour, and a white icon on a
 * white-ish sleeve has no tint change left to make.
 */
@Composable
internal fun CircleGlyph(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    active: Boolean = false,
) {
    val discAlpha by animateFloatAsState(
        targetValue = if (active) 0.34f else 0.18f,
        label = "glyphDisc",
    )
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(LocalPlayerTint.current.copy(alpha = discAlpha))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {
                Haptics.click()
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Crossfade(
            targetState = icon,
            animationSpec = tween(durationMillis = 180),
            label = "playerMenuGlyph",
        ) { glyph ->
            Icon(
                imageVector = glyph,
                contentDescription = contentDescription,
                tint = LocalPlayerTint.current,
                modifier = Modifier.size(19.dp),
            )
        }
    }
}

/** The skip glyphs' touch box, beside the larger play button. */
private val PLAYER_SKIP_TOUCH_SIZE = 64.dp

/**
 * The skip glyphs are drawn a little flatter than they are wide, so the pair reads lower and
 * longer next to the play button without losing any width.
 */
private const val PLAYER_SKIP_HEIGHT_SCALE = 0.85f

/** Previous, play/pause, next: bare glyphs, no backgrounds. */
@Composable
internal fun TransportRow(
    isPlaying: Boolean,
    /** While the stream resolves and buffers, the play glyph would be a lie. */
    isLoading: Boolean,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
) {
    val playSize = 88.dp
    val playTouch = 92.dp
    Row(
        modifier = Modifier.fillMaxWidth(),
        // SpaceAround, not SpaceEvenly: the outer margins take half a gap.
        horizontalArrangement = Arrangement.SpaceAround,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TransportGlyph(
            icon = Icons.Rounded.SkipPrevious,
            contentDescription = stringResource(R.string.immersive_player_previous),
            size = 64.dp,
            touchSize = PLAYER_SKIP_TOUCH_SIZE,
            heightScale = PLAYER_SKIP_HEIGHT_SCALE,
            onClick = onPrevious,
        )
        if (isLoading) {
            // Same footprint as the play/pause target: a smaller box would shunt everything below it.
            Box(Modifier.size(playTouch), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color.White, strokeWidth = 3.dp, modifier = Modifier.size(38.dp))
            }
        } else {
            TransportGlyph(
                icon = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                contentDescription = stringResource(if (isPlaying) R.string.immersive_player_pause else R.string.immersive_player_play),
                size = playSize,
                touchSize = playTouch,
                onClick = onPlayPause,
            )
        }
        TransportGlyph(
            icon = Icons.Rounded.SkipNext,
            contentDescription = stringResource(R.string.immersive_player_next),
            size = 64.dp,
            touchSize = PLAYER_SKIP_TOUCH_SIZE,
            heightScale = PLAYER_SKIP_HEIGHT_SCALE,
            onClick = onNext,
        )
    }
}

/**
 * Transport glyph. The circular clip belongs on the touch target, never on the [Icon]: clipping
 * the icon itself shaves the corners off wide glyphs.
 */
@Composable
private fun TransportGlyph(
    icon: ImageVector,
    contentDescription: String,
    size: Dp,
    touchSize: Dp = size,
    onClick: () -> Unit,
    heightScale: Float = 1f,
) {
    Box(
        modifier = Modifier
            .size(touchSize)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {
                Haptics.click()
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = Color.White,
            modifier = Modifier
                .size(size)
                .then(if (heightScale != 1f) Modifier.graphicsLayer { scaleY = heightScale } else Modifier),
        )
    }
}

/** ThinSlider's fixed touch target at the volume bar's size: 10dp + 22dp. */
internal val VOLUME_ROW_HEIGHT = 32.dp

/** The volume bar between its two speaker glyphs. */
@Composable
internal fun VolumeRow(volume: PlayerVolume) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.AutoMirrored.Rounded.VolumeDown,
            contentDescription = stringResource(R.string.immersive_player_volume_down),
            tint = LocalPlayerTint.current.copy(alpha = 0.5f),
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(10.dp))
        ThinSlider(
            value = volume.level.value,
            onValueChange = volume::drag,
            onValueChangeFinished = volume::release,
            idleHeight = 6.dp,
            activeHeight = 10.dp,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(10.dp))
        Icon(
            Icons.AutoMirrored.Rounded.VolumeUp,
            contentDescription = stringResource(R.string.immersive_player_volume_up),
            tint = LocalPlayerTint.current.copy(alpha = 0.5f),
            modifier = Modifier.size(20.dp),
        )
    }
}

private val BOTTOM_ACTION_SIZE = 44.dp
// Three segments (shuffle, repeat, devices) have to fit between the glyphs on a 320dp screen.
private val PILL_SEGMENT_WIDTH = 56.dp
private val PILL_ICON_SIZE = 24.dp

/** How long the capsule takes to cross from the middle of the row to the lyrics glyph's place as the queue opens, and back. */
private const val PILL_SLIDE_MS = com.music.spotui.ui.immersive.motion.ImmersiveMotion.MEDIUM_MS

/** How wide a capsule of [segments] comes out, dividers included. */
private fun pillWidth(segments: Int): Dp = PILL_SEGMENT_WIDTH * segments + 1.dp * (segments - 1)

/**
 * A row of controls joined into one capsule. The join is a hairline rather than a gap, which is
 * what makes several controls read as a single object.
 */
@Composable
private fun Pill(content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(
        modifier = Modifier
            .height(BOTTOM_ACTION_SIZE)
            .clip(CircleShape)
            .background(LocalPlayerTint.current.copy(alpha = 0.12f))
            .animateContentSize(
                animationSpec = com.music.spotui.ui.immersive.motion.ImmersiveMotion.chromeSpring(),
            ),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
private fun PillDivider() {
    Box(
        Modifier
            .width(1.dp)
            .fillMaxHeight()
            .background(LocalPlayerTint.current.copy(alpha = 0.20f)),
    )
}

@Composable
private fun PillSegment(
    contentDescription: String,
    onClick: () -> Unit,
    icon: ImageVector,
    highlighted: Boolean = false,
    width: Dp = PILL_SEGMENT_WIDTH,
) {
    Box(
        modifier = Modifier
            .width(width)
            .height(BOTTOM_ACTION_SIZE)
            .background(if (highlighted) LocalPlayerTint.current.copy(alpha = 0.14f) else Color.Transparent)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {
                Haptics.click()
                onClick()
            }
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        val tint = LocalPlayerTint.current.copy(alpha = if (highlighted) 1f else 0.75f)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Crossfade(
                targetState = icon,
                animationSpec = tween(durationMillis = 200),
                label = "pillSegmentIcon",
            ) { current ->
                Icon(imageVector = current, contentDescription = null, tint = tint, modifier = Modifier.size(PILL_ICON_SIZE))
            }
        }
    }
}

/** One of the bottom row's round glyphs (lyrics, queue); lit while its panel is up. */
@Composable
private fun BottomGlyph(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    highlighted: Boolean = false,
) {
    Box(
        modifier = Modifier
            .size(BOTTOM_ACTION_SIZE)
            .clip(CircleShape)
            .background(if (highlighted) LocalPlayerTint.current.copy(alpha = 0.20f) else Color.Transparent)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {
                Haptics.click()
                onClick()
            }
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = LocalPlayerTint.current.copy(alpha = if (highlighted) 1f else 0.75f),
            modifier = Modifier.size(26.dp),
        )
    }
}

/**
 * Lyrics, the capsule, and the queue: the row the player ends on.
 *
 * The capsule always holds shuffle, repeat and devices, so the playback modes are in reach
 * whatever is up. With the queue up the lyrics glyph steps aside and the capsule slides over
 * into its place, leaving the queue glyph, lit, as the way back.
 */
@Composable
internal fun PlayerActionRow(
    lyricsOpen: Boolean,
    queueOpen: Boolean,
    shuffleEnabled: Boolean,
    smartEnabled: Boolean,
    repeatMode: Int,
    deviceActive: Boolean,
    onToggleLyrics: () -> Unit,
    onToggleQueue: () -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    onOpenDevices: () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val widestRow = BOTTOM_ACTION_SIZE * 2 + pillWidth(3)
        val edgeInset = ((maxWidth - widestRow) / 4).coerceAtLeast(0.dp)
        // 0 with the capsule centred between lyrics and queue, 1 with it moved over to where the
        // lyrics glyph sits. Read only while placing, so the slide relays out the row and recomposes nothing.
        val toStart = animateFloatAsState(
            targetValue = if (queueOpen) 1f else 0f,
            animationSpec = tween(PILL_SLIDE_MS, easing = FastOutSlowInEasing),
            label = "playerBottomPillSlide",
        )
        Box(
            modifier = Modifier.fillMaxWidth().padding(horizontal = edgeInset),
            contentAlignment = Alignment.CenterStart,
        ) {
            // Gone while the queue is up: the capsule slides over into its place.
            AnimatedVisibility(
                visible = !queueOpen,
                enter = fadeIn(tween(180, delayMillis = 120)),
                exit = fadeOut(tween(120)),
                modifier = Modifier.align(Alignment.CenterStart),
            ) {
                BottomGlyph(
                    icon = Icons.Rounded.Lyrics,
                    contentDescription = stringResource(if (lyricsOpen) R.string.immersive_player_lyrics_close else R.string.immersive_player_lyrics),
                    // Lyrics and the queue are two things to show in one place, so opening either
                    // closes the other; lit to say which is up.
                    onClick = onToggleLyrics,
                    highlighted = lyricsOpen,
                )
            }
            // Laid out across the whole row and placed by hand: centred at 0, flush with the
            // lyrics glyph's left edge at 1.
            Box(
                Modifier.layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints.copy(minWidth = 0))
                    layout(constraints.maxWidth, placeable.height) {
                        val centred = (constraints.maxWidth - placeable.width) / 2f
                        placeable.place((centred * (1f - toStart.value)).roundToInt(), 0)
                    }
                },
            ) {
                Pill {
                    PillSegment(
                        icon = if (smartEnabled) Icons.Rounded.AutoAwesome else Icons.Rounded.Shuffle,
                        contentDescription = stringResource(
                            when {
                                smartEnabled -> R.string.smart_shuffle_title
                                shuffleEnabled -> R.string.immersive_player_shuffle_on
                                else -> R.string.immersive_player_shuffle_off
                            },
                        ),
                        onClick = onToggleShuffle,
                        highlighted = shuffleEnabled,
                    )
                    PillDivider()
                    PillSegment(
                        icon = if (repeatMode == ImmersiveRepeat.ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                        contentDescription = when (repeatMode) {
                            ImmersiveRepeat.ONE -> stringResource(R.string.immersive_player_repeat_one)
                            ImmersiveRepeat.ALL -> stringResource(R.string.immersive_player_repeat_all)
                            else -> stringResource(R.string.immersive_player_repeat_off)
                        },
                        onClick = onCycleRepeat,
                        highlighted = repeatMode != ImmersiveRepeat.OFF,
                    )
                    PillDivider()
                    PillSegment(
                        icon = Icons.Rounded.Devices,
                        contentDescription = stringResource(R.string.immersive_player_devices),
                        onClick = onOpenDevices,
                        highlighted = deviceActive,
                    )
                }
            }
            Box(Modifier.align(Alignment.CenterEnd)) {
                BottomGlyph(
                    icon = Icons.AutoMirrored.Rounded.QueueMusic,
                    contentDescription = stringResource(if (queueOpen) R.string.immersive_player_queue_close else R.string.immersive_player_queue),
                    onClick = onToggleQueue,
                    highlighted = queueOpen,
                )
            }
        }
    }
}
