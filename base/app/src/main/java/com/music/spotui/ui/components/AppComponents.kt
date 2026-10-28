package com.music.spotui.ui.components

import com.music.spotui.ui.theme.tone
import com.music.spotui.ui.theme.AppText

import androidx.compose.ui.res.stringResource

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.basicMarquee
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.BluetoothAudio
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderColors
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.CrossFade
import com.bumptech.glide.integration.compose.GlideImage
import com.bumptech.glide.integration.compose.placeholder
import app.myspoti.engine.R
import com.music.spotui.data.preferences.addLikedSongId
import com.music.spotui.data.preferences.isSongLiked
import com.music.spotui.data.preferences.removeLikedSongId
import com.music.spotui.di.Palette
import com.music.spotui.di.SongPlayer
import com.music.spotui.ui.navigation.Routes
import com.music.spotui.ui.theme.AppBackground
import com.music.spotui.ui.theme.GridBackground
import com.music.spotui.ui.viewmodel.PlayerViewModel
import kotlinx.coroutines.delay

/**
 * Loading placeholder, as in the official app: static grey shapes of the page to
 * come, no spinner. Shown only if loading lasts (cached pages appear without it),
 * and never animated (no frames spent while waiting).
 */
@Composable
fun Loader(kind: SkeletonKind = SkeletonKind.List) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(200); visible = true }
    Column(
        Modifier
            .fillMaxSize()
            .background(Color(AppBackground.toArgb()))
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        if (!visible) return@Column
        val tile = tone(0xFF242424)
        val line = tone(0xFF2A2A2A)
        when (kind) {
            SkeletonKind.Home -> {
                repeat(4) {
                    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        repeat(2) { Box(Modifier.weight(1f).height(56.dp).clip(RoundedCornerShape(4.dp)).background(tile)) }
                    }
                }
                repeat(2) {
                    Box(Modifier.padding(top = 24.dp, bottom = 12.dp).width(180.dp).height(18.dp).clip(RoundedCornerShape(4.dp)).background(line))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        repeat(3) { Box(Modifier.size(140.dp).clip(RoundedCornerShape(4.dp)).background(tile)) }
                    }
                }
            }
            SkeletonKind.Header -> {
                Box(Modifier.align(Alignment.CenterHorizontally).padding(top = 24.dp, bottom = 20.dp).size(220.dp).clip(RoundedCornerShape(4.dp)).background(tile))
                Box(Modifier.width(200.dp).height(22.dp).clip(RoundedCornerShape(4.dp)).background(line))
                Box(Modifier.padding(top = 10.dp, bottom = 20.dp).width(120.dp).height(14.dp).clip(RoundedCornerShape(4.dp)).background(line))
                SkeletonRows(tile, line, 6)
            }
            SkeletonKind.List -> SkeletonRows(tile, line, 9)
        }
    }
}

enum class SkeletonKind { List, Home, Header }

/**
 * Rows of grey shapes for a list that is loading, inside a page already drawn (a search, a
 * profile's lists): the same static placeholder as [Loader], without taking the whole screen.
 * [round] draws circles (people) instead of covers.
 */
@Composable
fun SkeletonList(count: Int = 6, round: Boolean = false, modifier: Modifier = Modifier) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(200); visible = true }
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        if (!visible) return@Column
        if (round) repeat(count) {
            Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(48.dp).clip(androidx.compose.foundation.shape.CircleShape).background(tone(0xFF242424)))
                Box(Modifier.padding(start = 12.dp).width(150.dp).height(14.dp).clip(RoundedCornerShape(4.dp)).background(tone(0xFF2A2A2A)))
            }
        } else SkeletonRows(tone(0xFF242424), tone(0xFF2A2A2A), count)
    }
}

/** Lyrics loading: lines of grey bars of varied widths, in the lyrics' own colour. */
@Composable
fun SkeletonLyrics(color: Color, modifier: Modifier = Modifier) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(200); visible = true }
    Column(modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp)) {
        if (!visible) return@Column
        for (w in floatArrayOf(0.85f, 0.6f, 0.75f, 0.5f, 0.9f, 0.65f, 0.7f, 0.45f)) {
            Box(Modifier.padding(vertical = 9.dp).fillMaxWidth(w).height(22.dp).clip(RoundedCornerShape(6.dp)).background(color.copy(alpha = 0.18f)))
        }
    }
}

@Composable
private fun SkeletonRows(tile: Color, line: Color, count: Int) {
    repeat(count) {
        Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).clip(RoundedCornerShape(4.dp)).background(tile))
            Column(Modifier.padding(start = 12.dp)) {
                Box(Modifier.width(170.dp).height(14.dp).clip(RoundedCornerShape(4.dp)).background(line))
                Box(Modifier.padding(top = 8.dp).width(110.dp).height(12.dp).clip(RoundedCornerShape(4.dp)).background(line))
            }
        }
    }
}

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
fun MiniPlayer(navController: NavHostController) {
    val vm : PlayerViewModel = viewModel()
    val songTitle = vm.currentSongTitle.value
    val songSinger = vm.currentSongSinger.value
    val songCoverUri = vm.currentSongCoverUri.value
    val songPlayingState = vm.currentSongPlayingState.value
    val songId = vm.currentSongId.value
    val liked = vm.likeState.value
    val np by SongPlayer.nowPlaying
    val context = LocalContext.current
    // A29: where the sound goes when it is not this phone's speaker. rememberAudioRoute
    // follows devices coming and going, so plugging a headset or getting in the car
    // updates the label without the screen reopening.
    val route = rememberAudioRoute()
    val routeLabel = when (route.kind) {
        AudioRoute.Kind.SPEAKER -> null
        AudioRoute.Kind.BLUETOOTH -> route.name.ifBlank { stringResource(R.string.label_bluetooth) }
        AudioRoute.Kind.WIRED -> stringResource(R.string.headphones)
    }

    // Card tint from the cover — extracted once per cover, not on every recomposition.
    var tint by remember { mutableStateOf(Palette.cachedFirst(songCoverUri) ?: Color(0xFF2A2A2A)) }
    LaunchedEffect(songCoverUri) {
        if (songCoverUri.isNotBlank()) {
            Palette().extractFirstColorFromImageUrl(context, songCoverUri) { tint = it }
            // Warm the full player's background colour: it opens already tinted.
            Palette().extractSecondColorFromCoverUrl(context, songCoverUri) { }
        }
    }

    // The big cover the player will open on, loaded now: it is then read from Glide's caches.
    LaunchedEffect(songCoverUri) {
        if (songCoverUri.isBlank()) return@LaunchedEffect
        com.bumptech.glide.Glide.with(context).load(songCoverUri).preload()
    }

    // A new song cross-fades the card's colour instead of snapping to it.
    val animatedTint by androidx.compose.animation.animateColorAsState(tint, androidx.compose.animation.core.tween(400), label = "miniTint")
    // A real crossfade (see SongBlend): cover and colour go from the outgoing song to this one with
    // the fade's progress; the words and the progress line switch once, half way.
    val blend = rememberSongBlend()
    val outgoing = blend?.takeIf { it.outgoingWords }?.song
    val outgoingTint = remember(blend) { animatedTint }

    // The Canvas of the song playing is looked up and loaded now, so the full player
    // opens on it directly instead of showing the cover first.
    val energy by app.myspoti.power.EnergySettings.state.collectAsStateWithLifecycle()
    LaunchedEffect(np.uri, energy.policy.allowCanvas, com.music.spotui.data.DataSaver.active) {
        val id = np.uri.removePrefix("spotify:track:").takeIf { np.uri.startsWith("spotify:track:") } ?: return@LaunchedEffect
        if (!energy.policy.allowCanvas || com.music.spotui.data.DataSaver.active) return@LaunchedEffect
        val url = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { com.music.spotui.di.Graph.api.getCanvasUrl(id) }.getOrNull()
        } ?: return@LaunchedEffect
        CanvasPlayer.preload(context, url)
    }

    // The next song, made ready while this one plays, so a skip shows it whole at once: its big
    // cover (on storage, not in memory), its colour, and its Canvas (the address always, the clip
    // itself on storage over Wi-Fi only). After the current song's own loads.
    LaunchedEffect(np.next?.spotifyTrackId, energy.policy.allowCanvas, com.music.spotui.data.DataSaver.active) {
        val next = np.next ?: return@LaunchedEffect
        kotlinx.coroutines.delay(2_500)
        if (next.coverUri.isNotBlank()) {
            com.bumptech.glide.Glide.with(context).downloadOnly().load(next.coverUri).submit()
            Palette().extractFirstColorFromImageUrl(context, next.coverUri) { }
            Palette().extractSecondColorFromCoverUrl(context, next.coverUri) { }
        }
        if (next.spotifyTrackId.isBlank() || !energy.policy.allowCanvas || com.music.spotui.data.DataSaver.active) return@LaunchedEffect
        val url = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { com.music.spotui.di.Graph.api.getCanvasUrl(next.spotifyTrackId) }.getOrNull()
        } ?: return@LaunchedEffect
        if (!com.music.spotui.data.DataSaver.metered) CanvasPlayer.warm(context, url)
    }

    // The thin bar moves ~2 px/s: 4 ticks a second look identical to 20.
    // Covered by the full player: no ticks (they redrew the whole window under it).
    val frame = rememberFrameClock(songPlayingState && !com.music.spotui.ui.navigation.PlayerSheet.open.value, fps = 4)

    var showDevices by remember { mutableStateOf(false) }
    if (showDevices) DevicesSheet { showDevices = false }

    // Swipe the track info left/right to change track: [previous, current, next].
    val pager = androidx.compose.foundation.pager.rememberPagerState(initialPage = 1, pageCount = { 3 })
    LaunchedEffect(songId) { if (pager.currentPage != 1) pager.scrollToPage(1) }
    LaunchedEffect(pager) {
        androidx.compose.runtime.snapshotFlow { pager.settledPage }.collect { page ->
            when (page) {
                2 -> SongPlayer.swipeNext()
                0 -> SongPlayer.previous()
            }
        }
    }

    Column(
        modifier = Modifier.pressable(0.96f) { com.music.spotui.ui.navigation.PlayerSheet.show() }
            // Where the full player rises from and sinks back to.
            .onGloballyPositioned { com.music.spotui.ui.navigation.PlayerSheet.miniTop = it.boundsInWindow().top }
            .padding(horizontal = 8.dp)
            .clip(RoundedCornerShape(8.dp))
            // Drawn: the colour follows a crossfade frame by frame without recomposing the card.
            .drawBehind { drawRect(blend?.let { androidx.compose.ui.graphics.lerp(outgoingTint, animatedTint, it.progress()) } ?: animatedTint) }
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp)
                .padding(start = 8.dp, end = 12.dp)
        ) {
            androidx.compose.foundation.pager.HorizontalPager(
                state = pager,
                modifier = Modifier.weight(1f),
            ) { page ->
                val s = when (page) { 0 -> np.prev; 2 -> np.next; else -> null }
                // T4: during an ad break the page publishes no track, so the song before it
                // would sit in the mini player; an explicit ad state is shown instead.
                val adPlaying = com.music.spotui.di.SongPlayer.adPlaying
                // A song change that is no swipe (the end of a song, a skip from the player or
                // the notification) crossfades instead of swapping in one frame. After a swipe the
                // new song is already on screen (the page swiped to): no fade then, or the page
                // jumped back to would show the old song fading out.
                // Through a crossfade the blend below is the transition: no fade of its own.
                val shown = MiniSong(s?.title ?: outgoing?.title ?: songTitle, s?.singer ?: outgoing?.artist ?: songSinger, s?.coverUri ?: songCoverUri)
                androidx.compose.animation.Crossfade(
                    targetState = shown,
                    animationSpec = androidx.compose.animation.core.tween(if (page == 1 && pager.currentPage == 1 && blend == null) 220 else 0),
                    label = "miniSong",
                ) { m ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Box(Modifier.size(40.dp).clip(RoundedCornerShape(4.dp))) {
                        GlideImage(
                            transition = CrossFade,
                            modifier = Modifier.size(40.dp),
                            model = m.cover,
                            contentScale = ContentScale.Crop,
                            failure = placeholder(R.drawable.placeholder),
                            loading = placeholder(R.drawable.placeholder),
                            contentDescription = ""
                        )
                        // The outgoing cover dissolving into this one, laid over it.
                        if (page == 1) SongBlendCover(blend, Modifier.size(40.dp))
                    }
                    val remote = SongPlayer.activeDevice.value
                    Column(modifier = Modifier.padding(start = 12.dp, end = 8.dp)
                        .graphicsLayer { alpha = if (page == 1) blend?.wordsAlpha() ?: 1f else 1f }) {
                        if (remote == null) {
                            Text(text = if (adPlaying) stringResource(R.string.ad_playing) else (m.title), color = Color.White, fontSize = 13.sp, lineHeight = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            if (!adPlaying) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                // The artist yields the width: the output's name stays whole.
                                Text(
                                    text = m.singer, color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp, lineHeight = 15.sp,
                                    maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false),
                                )
                                // The extension the sound comes from, discreet, as the output's name.
                                playingExtensionName()?.let { name ->
                                    Text(
                                        text = " · $name", color = Color.White.copy(alpha = 0.55f), fontSize = 11.sp,
                                        maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                        modifier = Modifier.widthIn(max = 110.dp),
                                    )
                                }
                                if (routeLabel != null) {
                                    Icon(
                                        imageVector = if (route.kind == AudioRoute.Kind.BLUETOOTH) Icons.Filled.BluetoothAudio else Icons.Filled.Headphones,
                                        contentDescription = stringResource(R.string.playing_on),
                                        tint = Color.White.copy(alpha = 0.55f),
                                        modifier = Modifier.padding(start = 6.dp).size(12.dp),
                                    )
                                    Text(
                                        text = routeLabel, color = Color.White.copy(alpha = 0.55f), fontSize = 11.sp,
                                        maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(start = 3.dp).widthIn(max = 110.dp),
                                    )
                                }
                            }
                        } else {
                            // Playing on another device: "Title • Artist", then the device in green.
                            Text(
                                text = androidx.compose.ui.text.buildAnnotatedString {
                                    pushStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold, color = Color.White))
                                    append(m.title)
                                    pop()
                                    pushStyle(androidx.compose.ui.text.SpanStyle(color = Color.White.copy(alpha = 0.7f)))
                                    append(" • " + (m.singer))
                                    pop()
                                },
                                fontSize = 13.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, softWrap = false,
                                // Scrolls once per track then rests: an endless marquee in the always-visible
                                // mini player made the whole app redraw at 120 Hz without pause.
                                modifier = Modifier.basicMarquee(iterations = 1, initialDelayMillis = 2_000),
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null, tint = com.music.spotui.ui.theme.AppAccent, modifier = Modifier.size(14.dp))
                                Text(remote.name, color = com.music.spotui.ui.theme.AppAccent, fontSize = 12.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.padding(start = 4.dp))
                            }
                        }
                    }
                }
                }
            }
            Icon(
                painter = painterResource(id = if (SongPlayer.activeDevice.value != null) R.drawable.ic_devices else R.drawable.ic_devices),
                tint = if (SongPlayer.activeDevice.value != null) com.music.spotui.ui.theme.AppAccent else Color.White.copy(alpha = 0.8f),
                modifier = Modifier.pressable(0.96f) { showDevices = true }
                    .size(22.dp)
                    ,
                contentDescription = stringResource(R.string.mr_user_route_category_name),
            )
            Spacer(modifier = Modifier.width(20.dp))
            if (SongPlayer.canLike) com.music.spotui.ui.screens.AddButton(song = { com.music.spotui.ui.screens.nowPlayingSong(vm) }, liked = liked, size = 24.dp) { vm.updateLikeState(true) }
            Spacer(modifier = Modifier.width(20.dp))
            com.music.spotui.ui.components.PlayPauseIcon(
                playing = songPlayingState,
                contentDescription = if (songPlayingState) stringResource(R.string.content_description_pause_button) else stringResource(R.string.content_description_play_button),
                tint = Color.White,
                modifier = Modifier.pressable(0.96f) {
                        if (songPlayingState) {
                            SongPlayer.pause()
                            vm.updateSongState(songCoverUri, songTitle, songSinger, false, songId, vm.currentSongIndex.value, vm.currentSongAlbum.value)
                        } else {
                            SongPlayer.play()
                            vm.updateSongState(songCoverUri, songTitle, songSinger, true, songId, vm.currentSongIndex.value, vm.currentSongAlbum.value)
                        }
                    }
                    .size(26.dp)
                    
            )
        }
        // Thin progress line at the bottom of the card.
        // Drawn every frame from the engine clock: smooth at the display refresh rate.
        androidx.compose.foundation.Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
                .height(2.dp)
                .clip(RoundedCornerShape(1.dp))
                // Its own layer: a tick re-records these two rectangles, not the whole card.
                .graphicsLayer()
        ) {
            frame.value
            val o = blend?.takeIf { it.outgoingWords }
            val dur = (o?.song?.durationMs ?: SongPlayer.getDuration()).toFloat()
            val p = if (dur > 0f) ((o?.positionMs() ?: SongPlayer.getCurrentPosition()) / dur).coerceIn(0f, 1f) else 0f
            drawRect(Color.White.copy(alpha = 0.25f))
            drawRect(Color.White, size = size.copy(width = size.width * p))
        }
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomSlider(
    modifier: Modifier = Modifier,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    colors: SliderColors = SliderDefaults.colors(),
) {
    Box(
        //contentAlignment = Alignment.BottomEnd,
        modifier = Modifier
            .fillMaxWidth()
            .height(0.dp)

    ) {

        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
            colors = colors,
        )
    }
}


@Composable
fun Snackbar(showMessage : String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(AppText),
        contentAlignment = Alignment.Center
    ){
        Text(
            fontWeight = FontWeight.W500,
            fontSize = 14.sp,
            color = com.music.spotui.ui.theme.AppBackground,
            text = showMessage
        )
    }
}


/** What the mini player shows of a song: the crossfade's key, so the old one fades with its own text. */
private data class MiniSong(val title: String, val singer: String, val cover: String)
