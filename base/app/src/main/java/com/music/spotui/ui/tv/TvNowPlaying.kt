/*
 * Adapted from Apple Music TV (https://github.com/JObersi10/apple-music-tv), GPL-3.0,
 * android/app/src/main/java/com/applemusicktv/ui/screens/NowPlayingScreen.kt at 7e5c594
 * (NowPlayingScreen, PlayerProgressBar, rememberSmoothProgressMs).
 * Modified for Myspoti, 2026-11.
 *
 * Changes: Myspoti's player state and actions (rememberImmersivePlayer), its mesh backdrop from
 * the cover instead of the beat-driven orbs, its lyrics panel; shuffle, repeat and like on the
 * transport row instead of an options menu; Myspoti's queue panel and Connect devices beside the
 * lyrics; the artist opens its page; no motion cover, sleep timer, live radio, screensaver or
 * full-screen lyrics.
 */
package com.music.spotui.ui.tv

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.compose.rememberNavController
import app.myspoti.engine.R
import com.music.spotui.ui.components.ArtistChooser
import com.music.spotui.ui.immersive.lyrics.ImmersiveLyricsPanel
import com.music.spotui.ui.immersive.lyrics.ImmersiveQueuePanel
import com.music.spotui.ui.immersive.player.ImmersivePlayerActions
import com.music.spotui.ui.immersive.player.ImmersivePlayerState
import com.music.spotui.ui.immersive.player.ImmersiveRepeat
import com.music.spotui.ui.immersive.player.rememberImmersivePlayer
import com.music.spotui.ui.look.MeshGradientBackground
import com.music.spotui.ui.look.rememberArtworkColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** The controls hide after this long without the remote; the cover, title and lyrics stay. */
private const val CHROME_HIDE_MS = 8_000L

/**
 * The full-screen player: the cover's colours as a slow mesh behind everything; on the left the
 * cover, title, artist, the transport and a seek bar the D-pad scrubs by 10 s; on the right the
 * synced lyrics. Idle, the controls fade and only the cover, its words and the lyrics remain.
 */
@Composable
internal fun TvNowPlaying(onArtist: (id: String, name: String) -> Unit) {
    // The TV opens its own artist page: the controller only satisfies the bridge.
    val bridge = rememberImmersivePlayer(rememberNavController())
    val state = bridge.state
    var devices by remember { mutableStateOf(false) }
    if (devices) TvDevicesDialog { devices = false }
    val scope = rememberCoroutineScope()
    TvPlayerScreen(
        state, bridge.actions, rememberArtworkColors(state.coverUrl.ifBlank { null }),
        onArtist = {
            scope.launch {
                val a = ArtistChooser.artistsOf(state.trackId, state.artist).firstOrNull { it.uri.isNotBlank() } ?: return@launch
                onArtist(a.uri.substringAfterLast(':'), a.name)
            }
        },
        onDevices = { devices = true },
    ) { queue, modifier ->
        if (queue) ImmersiveQueuePanel(modifier, PaddingValues(vertical = 8.dp)) else ImmersiveLyricsPanel(modifier, PaddingValues(vertical = 8.dp))
    }
}

/** The player drawn from plain state; [panel] draws the lyrics, or the queue when its flag is set. */
@Composable
internal fun TvPlayerScreen(
    state: ImmersivePlayerState,
    actions: ImmersivePlayerActions,
    palette: com.music.spotui.ui.look.MeshPalette,
    onArtist: () -> Unit,
    onDevices: () -> Unit,
    panel: @Composable (queue: Boolean, Modifier) -> Unit,
) {

    var chromeVisible by remember { mutableStateOf(true) }
    var lastNavMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(chromeVisible, lastNavMs, state.playing) {
        if (!chromeVisible || !state.playing) return@LaunchedEffect
        while (isActive) {
            delay(500)
            if (System.currentTimeMillis() - lastNavMs > CHROME_HIDE_MS) { chromeVisible = false; break }
        }
    }
    // Into idle slowly, back at once: the controls are there the moment the remote is touched.
    val idle by animateFloatAsState(
        if (chromeVisible) 0f else 1f,
        if (chromeVisible) tween(240, easing = FastOutSlowInEasing) else tween(1600, easing = FastOutSlowInEasing),
        label = "idle",
    )
    val chromeAlpha = 1f - idle
    var queue by remember { mutableStateOf(false) }

    Box(
        Modifier.fillMaxSize().background(TvBg).onPreviewKeyEvent { e ->
            if (e.type == KeyEventType.KeyDown) {
                lastNavMs = System.currentTimeMillis()
                // The first press only brings the controls back: focus does not also jump.
                if (!chromeVisible) { chromeVisible = true; return@onPreviewKeyEvent true }
            }
            false
        },
    ) {
        MeshGradientBackground(palette, Modifier.fillMaxSize(), trackKey = state.title)
        // Darker towards the right, where the lyrics are read, and at the bottom.
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Color(0x33000000), 0.45f to Color(0x55000000), 1f to Color(0xAA000000))))

        if (state.title.isBlank()) {
            Text(stringResource(R.string.immersive_shell_now_playing), color = Color(0xFF888888), fontSize = 20.sp, modifier = Modifier.align(Alignment.Center))
            return@Box
        }

        TvClock(Modifier.align(Alignment.TopEnd).padding(end = 32.dp, top = 18.dp).graphicsLayer { alpha = chromeAlpha })

        Row(Modifier.fillMaxSize().padding(horizontal = 72.dp, vertical = 48.dp), horizontalArrangement = Arrangement.spacedBy(64.dp)) {
            Column(
                Modifier.width(380.dp).fillMaxHeight().graphicsLayer {
                    // Idle, the controls still hold their space: the group eases down a little so the
                    // cover and its words sit in the middle.
                    val s = 1f + 0.03f * idle
                    scaleX = s; scaleY = s
                    translationY = idle * 64f
                },
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Crossfade(state.coverUrl, animationSpec = tween(800), label = "cover") { url ->
                    TvCover(url, Modifier.size(320.dp), RoundedCornerShape(18.dp))
                }
                Spacer(Modifier.height(18.dp))
                TvMarquee(state.title, 26.sp, FontWeight.Bold, Color.White)
                // The artist opens its page (the first one of the credit on a TV, no chooser).
                Box(Modifier.fillMaxWidth().tvPill(RoundedCornerShape(8.dp), focusedFill = Color(0x1FFFFFFF), focusedScale = 1f, onClick = onArtist)) { TvMarquee(state.artist, 18.sp, FontWeight.Medium, TvAccent) }
                // Where the sound goes when it is not this TV (Spotify Connect).
                state.deviceName?.let { Text(it, color = TvSubtle, fontSize = 14.sp, maxLines = 1, modifier = Modifier.padding(top = 4.dp)) }
                Spacer(Modifier.height(18.dp))
                TvTransport(state, actions, Modifier.graphicsLayer { alpha = chromeAlpha })
                Spacer(Modifier.height(16.dp))
                TvSeekBar(state, actions, Modifier.graphicsLayer { alpha = chromeAlpha })
            }
            Column(Modifier.weight(1f).fillMaxHeight()) {
                // Lyrics or queue, and where the music plays: chrome, they fade with the controls.
                Row(Modifier.align(Alignment.End).graphicsLayer { alpha = chromeAlpha }, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TvIconButton(Icons.Default.Lyrics, stringResource(R.string.immersive_player_lyrics), size = 44.dp,
                        tint = if (!queue) TvAccent else Color.White.copy(alpha = 0.6f)) { queue = false }
                    TvIconButton(Icons.AutoMirrored.Filled.QueueMusic, stringResource(R.string.immersive_player_queue), size = 44.dp,
                        tint = if (queue) TvAccent else Color.White.copy(alpha = 0.6f)) { queue = true }
                    TvIconButton(Icons.Default.Devices, stringResource(R.string.listen_on), size = 44.dp,
                        tint = if (state.deviceName != null) TvAccent else Color.White.copy(alpha = 0.6f), onClick = onDevices)
                }
                // Lyrics are content, not chrome: they stay when the controls hide; the queue fades.
                panel(queue, Modifier.weight(1f).fillMaxWidth().graphicsLayer { alpha = if (queue) chromeAlpha else 1f })
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TvMarquee(text: String, size: androidx.compose.ui.unit.TextUnit, weight: FontWeight, color: Color) {
    Text(text, color = color, fontSize = size, fontWeight = weight, maxLines = 1, modifier = Modifier.fillMaxWidth().basicMarquee(),
        style = TextStyle(textAlign = androidx.compose.ui.text.style.TextAlign.Center))
}

/** Shuffle, previous, play/pause, next, repeat and like; play/pause has the focus at first. */
@Composable
private fun TvTransport(state: ImmersivePlayerState, actions: ImmersivePlayerActions, modifier: Modifier) {
    val playFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { delay(80); playFocus.requestFocus() } }
    val dim = Color.White.copy(alpha = 0.6f)
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        TvIconButton(Icons.Default.Shuffle,
            stringResource(if (state.shuffle) R.string.immersive_player_shuffle_on else R.string.immersive_player_shuffle_off),
            size = 44.dp, tint = if (state.shuffle) TvAccent else dim, onClick = actions.toggleShuffle)
        TvIconButton(Icons.Default.SkipPrevious, stringResource(R.string.immersive_player_previous), size = 52.dp, onClick = actions.previous)
        TvIconButton(
            if (state.playing) Icons.Default.Pause else Icons.Default.PlayArrow,
            stringResource(if (state.playing) R.string.immersive_player_pause else R.string.immersive_player_play),
            size = 68.dp, tint = Color.Black, base = Color.White, modifier = Modifier.focusRequester(playFocus), onClick = actions.playPause,
        )
        TvIconButton(Icons.Default.SkipNext, stringResource(R.string.immersive_player_next), size = 52.dp, onClick = actions.next)
        val repeatOn = state.repeatMode != ImmersiveRepeat.OFF
        TvIconButton(
            if (state.repeatMode == ImmersiveRepeat.ONE || state.repeatMode == ImmersiveRepeat.ONCE) Icons.Default.RepeatOne else Icons.Default.Repeat,
            stringResource(when (state.repeatMode) {
                ImmersiveRepeat.ALL -> R.string.immersive_player_repeat_all
                ImmersiveRepeat.ONE -> R.string.immersive_player_repeat_one
                ImmersiveRepeat.ONCE -> R.string.immersive_player_repeat_once
                else -> R.string.immersive_player_repeat_off
            }),
            size = 44.dp, tint = if (repeatOn) TvAccent else dim, onClick = actions.cycleRepeat,
        )
        if (!state.isEpisode) TvIconButton(
            if (state.liked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
            stringResource(if (state.liked) R.string.immersive_player_unlike else R.string.immersive_player_like),
            size = 44.dp, tint = if (state.liked) TvAccent else dim, onClick = actions.toggleLike,
        )
    }
}

/**
 * The seek bar and its times. Focused, ← and → move a cursor by 10 s and OK seeks there; the
 * playhead is read every frame here only, not by the whole screen.
 */
@Composable
private fun TvSeekBar(state: ImmersivePlayerState, actions: ImmersivePlayerActions, modifier: Modifier) {
    var positionMs by remember { mutableLongStateOf(state.positionMs()) }
    LaunchedEffect(state.positionMs) { while (isActive) withFrameMillis { positionMs = state.positionMs() } }
    val duration = state.durationMs.coerceAtLeast(1L)
    var focused by remember { mutableStateOf(false) }
    // The cursor starts at the playhead each time the bar takes the focus.
    var scrubMs by remember(state.title) { mutableLongStateOf(positionMs) }
    val progress = (positionMs.toFloat() / duration).coerceIn(0f, 1f)
    val scrub = (scrubMs.toFloat() / duration).coerceIn(0f, 1f)
    val shadow = Shadow(Color.Black.copy(alpha = 0.8f), blurRadius = 8f)
    Column(modifier.fillMaxWidth()) {
        Box(
            Modifier.fillMaxWidth().padding(vertical = 6.dp)
                .onFocusChanged { if (it.isFocused && !focused) scrubMs = positionMs; focused = it.isFocused }
                .onKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                    when (e.key) {
                        Key.DirectionLeft -> { scrubMs = (scrubMs - 10_000).coerceAtLeast(0); true }
                        Key.DirectionRight -> { scrubMs = (scrubMs + 10_000).coerceAtMost(duration); true }
                        else -> false
                    }
                }
                .focusable()
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { actions.seekTo(scrubMs); positionMs = scrubMs },
        ) {
            Box(Modifier.fillMaxWidth().height(if (focused) 8.dp else 4.dp).clip(RoundedCornerShape(4.dp)).background(Color(0x44FFFFFF))) {
                Box(Modifier.fillMaxWidth(progress).fillMaxHeight().background(Color.White))
                if (focused) Box(Modifier.fillMaxWidth(scrub).fillMaxHeight().background(TvAccent.copy(alpha = 0.6f)))
            }
        }
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
            Text(formatMs(if (focused) scrubMs else positionMs), style = TextStyle(fontSize = 13.sp, color = if (focused) Color.White else Color(0xFFAAAAAA), shadow = shadow))
            Text(formatMs(state.durationMs), style = TextStyle(fontSize = 13.sp, color = Color(0xFFAAAAAA), shadow = shadow))
        }
    }
}

/** The time of day, top right, in the TV's own format. */
@Composable
private fun TvClock(modifier: Modifier) {
    var text by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        val fmt = DateFormat.getTimeInstance(DateFormat.SHORT)
        while (isActive) { text = fmt.format(Date()); delay(1_000) }
    }
    Text(text, modifier, style = TextStyle(fontSize = 17.sp, color = Color(0xCCFFFFFF), shadow = Shadow(Color.Black.copy(alpha = 0.8f), blurRadius = 8f)))
}
