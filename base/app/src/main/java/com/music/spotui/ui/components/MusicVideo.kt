package com.music.spotui.ui.components

import androidx.compose.ui.res.stringResource

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.OndemandVideo
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.music.spotui.di.Graph
import com.music.spotui.di.SongPlayer
import com.music.spotui.video.MusicVideoController
import com.music.spotui.ui.screens.SpotifySeekBar
import app.myspoti.engine.R
import kotlinx.coroutines.delay

/**
 * The music video (YouTube, synced to Spotify by [MusicVideoController]) rendered by
 * ExoPlayer into a TextureView: a normal view, so it scrolls, clips and animates with
 * the rest of the UI. Only the [active] surface receives the frames.
 */
@Composable
fun VideoSurface(modifier: Modifier = Modifier, active: Boolean = true, fitHeight: Boolean = false, pip: Boolean = false, onTap: (() -> Unit)? = null) {
    val ratio = MusicVideoController.aspect.floatValue.coerceIn(0.4f, 3f)
    var view by remember { mutableStateOf<android.view.TextureView?>(null) }
    val player = MusicVideoController.player
    // In the picture-in-picture window only its own surface shows the clip; outside it, never that one.
    val mine = pip == MusicVideoController.inPip.value
    DisposableEffect(player, view, active, mine) {
        val v = view
        val attached = player != null && v != null && active && mine
        if (attached) { player!!.setVideoTextureView(v); MusicVideoController.surfaces++ }
        onDispose {
            if (attached) MusicVideoController.surfaces--
            if (player != null && v != null) player.clearVideoTextureView(v)
        }
    }
    androidx.compose.ui.viewinterop.AndroidView(
        factory = { ctx -> android.view.TextureView(ctx).also { view = it } },
        modifier = modifier
            .then(if (fitHeight) Modifier.aspectRatio(ratio, matchHeightConstraintsFirst = true) else Modifier.fillMaxWidth().aspectRatio(ratio))
            .background(Color.Black)
            .then(
                if (onTap != null) Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onTap,
                ) else Modifier
            ),
    )
}

// The official AV button (layout animated_av_button): a black pill drawn over the
// media (overMediaBackgroundBase), 16 dp video icon, BodySmallBold white label.
private val OverMedia = Color.Black.copy(alpha = 0.6f)

/** Spotify's "Passer à la vidéo" / "Passer à l'audio" pill. */
@Composable
fun VideoSwitchPill(on: Boolean, modifier: Modifier = Modifier) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        SwitchPill(on)
        // With the video on: the clip's own sound instead of the song's (the song still plays, unheard).
        if (false) {
            val clip = MusicVideoController.clipSound.value
            val context = androidx.compose.ui.platform.LocalContext.current
            Spacer(Modifier.width(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .height(32.dp)
                    .clip(RoundedCornerShape(50))
                    .background(OverMedia)
                    .clickable {
                        MusicVideoController.setClipSound(!clip)
                        // What changed, said once per tap: the label alone did not tell (reported).
                        android.widget.Toast.makeText(context, if (clip) R.string.clip_sound_off_hint else R.string.clip_sound_on_hint, android.widget.Toast.LENGTH_LONG).show()
                    }
                    .padding(start = 10.dp, end = 12.dp),
            ) {
                Icon(
                    Icons.Default.VolumeUp,
                    contentDescription = null,
                    tint = if (clip) com.music.spotui.ui.theme.AppAccent else Color.White,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
                // The sound heard now: the song's, or the clip's own.
                Text(stringResource(if (clip) R.string.clip_sound_clip else R.string.clip_sound_song), color = if (clip) com.music.spotui.ui.theme.AppAccent else Color.White,
                    fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            }
        }
    }
}

@Composable
private fun SwitchPill(on: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .height(32.dp)
            .clip(RoundedCornerShape(50))
            .background(OverMedia)
            .clickable { MusicVideoController.setOn(!on) }
            .padding(start = 10.dp, end = 12.dp),
    ) {
        Icon(
            if (on) Icons.Default.MusicNote else Icons.Default.OndemandVideo,
            contentDescription = stringResource(R.string.av_content_desc),
            tint = Color.White,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            stringResource(if (on) R.string.av_switch_to_audio else R.string.av_switch_to_video),
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
    }
}

/** Full-screen button shown under the video. */
@Composable
fun VideoFullscreenButton(modifier: Modifier = Modifier) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(48.dp)
            .clickable { MusicVideoController.fullscreen.value = true },
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(32.dp).clip(CircleShape).background(OverMedia),
        ) {
            Icon(
                Icons.Default.Fullscreen,
                contentDescription = stringResource(R.string.video_fullscreen_enter),
                tint = Color.White,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** Controls over the full-screen video; a tap shows / hides them. */
@Composable
fun VideoFullscreenOverlay() {
    BackHandler { MusicVideoController.fullscreen.value = false }
    var controls by remember { mutableStateOf(true) }
    var touch by remember { mutableIntStateOf(0) }
    LaunchedEffect(controls, touch) {
        if (controls) { delay(3500); controls = false }
    }
    val state = Graph.currentSongState
    val playing = state.playingState.value
    val frame = rememberFrameClock(playing && controls, fps = 30)
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) { detectTapGestures { controls = !controls } },
    ) {
        AnimatedVisibility(visible = controls, enter = fadeIn(), exit = fadeOut()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.6f), Color.Transparent, Color.Black.copy(alpha = 0.7f)))),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(state.title.value, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(state.singer.value, color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    VideoSwitchPill(on = true)
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(40.dp),
                    modifier = Modifier.align(Alignment.Center),
                ) {
                    RoundIcon(R.drawable.ic_player_skip, stringResource(R.string.np_content_desc_prev), 30, flip = true) { touch++; SongPlayer.previous() }
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .background(Color.White)
                            .clickable { touch++; if (playing) SongPlayer.pause() else SongPlayer.play() },
                    ) {
                        PlayPauseIcon(
                            playing = playing,
                            contentDescription = if (playing) stringResource(R.string.content_description_pause_button) else stringResource(R.string.content_description_play_button),
                            tint = Color.Black,
                            modifier = Modifier.size(30.dp),
                        )
                    }
                    RoundIcon(R.drawable.ic_player_skip, stringResource(R.string.np_content_desc_next), 30) { touch++; SongPlayer.next() }
                }
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp)) {
                    SpotifySeekBar(
                        fraction = {
                            frame.value
                            if (dragging) dragValue else {
                                val d = MusicVideoController.duration()
                                if (d > 0) (MusicVideoController.position().toFloat() / d).coerceIn(0f, 1f) else 0f
                            }
                        },
                        onDrag = { touch++; dragging = true; dragValue = it },
                        onRelease = { MusicVideoController.seekTo((dragValue * MusicVideoController.duration()).toLong()); dragging = false },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                        Spacer(Modifier.weight(1f))
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.size(48.dp).clickable(
                                interactionSource = remember { MutableInteractionSource() }, indication = null,
                            ) { MusicVideoController.fullscreen.value = false },
                        ) {
                            Icon(
                                Icons.Default.FullscreenExit,
                                contentDescription = stringResource(R.string.video_fullscreen_exit),
                                tint = Color.White,
                                modifier = Modifier.size(26.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RoundIcon(icon: Int, description: String, sizeDp: Int, flip: Boolean = false, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size((sizeDp + 18).dp).clickable(
            interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick,
        ),
    ) {
        Icon(
            painterResource(icon), contentDescription = description, tint = Color.White,
            modifier = Modifier.size(sizeDp.dp).graphicsLayer { if (flip) scaleX = -1f },
        )
    }
}

/** What the picture-in-picture window shows: the clip alone, centred on black. */
@Composable
fun PipVideo() {
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        VideoSurface(fitHeight = true, pip = true)
    }
}
