package com.music.spotui.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.RotateLeft
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import app.myspoti.engine.R
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.CrossFade
import com.bumptech.glide.integration.compose.GlideImage
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.di.Graph
import com.music.spotui.di.SongPlayer
import com.music.spotui.ui.components.DevicesSheet
import com.music.spotui.ui.components.EpisodeOptionsSheet
import com.music.spotui.ui.components.PodcastSpeedSheet
import com.music.spotui.ui.components.QueueSheet
import com.music.spotui.ui.components.SleepTimerSheet
import com.music.spotui.ui.components.episodeClock
import com.music.spotui.ui.components.pressable
import com.music.spotui.ui.components.pullToDismiss
import com.music.spotui.ui.components.speedLabel
import com.music.spotui.ui.theme.AppAccent
import com.music.spotui.ui.theme.AppBackground
import kotlinx.coroutines.launch

/**
 * The seek bar and the two clocks, with their own ticker: the position moves every half second,
 * and only this part of the player follows it (as [ElapsedLabel] does for the music player).
 */
@Composable
private fun PodcastProgress(playing: Boolean, uri: String, duration: Long) {
    // Tapping the time flips it, as in the official player (it opens on what is left).
    var showRemaining by remember { mutableStateOf(true) }
    var drag by remember { mutableStateOf<Float?>(null) }
    var position by remember { mutableStateOf(SongPlayer.getCurrentPosition()) }
    LaunchedEffect(playing, uri) {
        while (true) {
            position = SongPlayer.getCurrentPosition()
            if (!playing) break
            kotlinx.coroutines.delay(500)
        }
    }
    val fraction = when {
        drag != null -> drag!!
        duration > 0 -> (position.toFloat() / duration).coerceIn(0f, 1f)
        else -> 0f
    }
    val shown = drag?.let { (it * duration).toLong() } ?: position
    SpotifySeekBar(
        fraction = { fraction },
        onDrag = { f -> drag = f },
        onRelease = {
            val f = drag ?: return@SpotifySeekBar
            drag = null
            if (duration > 0) {
                position = (f * duration).toLong()
                SongPlayer.seekTo(position)
            }
        },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
    ) {
        Text(episodeClock(shown), color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
        // The stock player's right-hand clock is a button: elapsed <-> remaining.
        Text(
            text = if (showRemaining) episodeClock((duration - shown).coerceAtLeast(0)) else episodeClock(shown),
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 12.sp,
            modifier = Modifier.clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { showRemaining = !showRemaining },
        )
    }
}

/**
 * The player, when a podcast episode plays: Spotify gives it its own controls — ±15 s instead
 * of previous / next, a speed selector, a sleep timer and no shuffle or repeat. Music keeps
 * the music player ([PlayerScreen]).
 */
@OptIn(ExperimentalGlideComposeApi::class)
@Composable
fun PodcastPlayerScreen(navController: NavController) {
    val context = LocalContext.current
    val state = Graph.currentSongState
    val np by SongPlayer.nowPlaying
    val title = state.title.value
    val showName = state.singer.value.ifBlank { np.contextName }
    val cover = state.coverUri.value
    val playing = state.playingState.value
    val episodeId = np.uri.removePrefix("spotify:episode:")
    val uri = "spotify:episode:$episodeId"
    val saved = SongPlayer.library[uri] == true
    androidx.compose.runtime.LaunchedEffect(uri) { SongPlayer.requestSaved(uri) }

    var showSpeed by remember { mutableStateOf(false) }
    var showSleep by remember { mutableStateOf(false) }
    var showDevices by remember { mutableStateOf(false) }
    var showQueue by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    val duration = SongPlayer.getDuration()
    val song = SongsModel(
        id = ("uri:$uri").hashCode() and 0x7fffffff,
        title = title,
        album = showName,
        singer = showName,
        coverUri = cover,
        url = "episode:$episodeId",
        durationMs = duration.toInt(),
    )

    if (showSpeed) PodcastSpeedSheet { showSpeed = false }
    if (showSleep) SleepTimerSheet { showSleep = false }
    if (showDevices) DevicesSheet { showDevices = false }
    if (showQueue) QueueSheet(contextName = showName) { showQueue = false }
    if (showMenu) EpisodeOptionsSheet(
        song = song,
        finished = false,
        onPlay = { SongPlayer.play() },
        onMarkFinished = { played ->
            scope.launch {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching { Graph.api.setEpisodePlayed(uri, played) }
                }
            }
        },
        onOpen = { navController.navigate(com.music.spotui.ui.navigation.episodeRoute(episodeId, title)) },
        onDismiss = { showMenu = false },
    )

    // The background takes the cover's colour like the music player, cross-faded on change.
    var coverColor by remember { mutableStateOf(com.music.spotui.di.Palette.cachedSecond(cover) ?: Color(0xFF3A3630)) }
    LaunchedEffect(cover) {
        if (cover.isNotBlank()) com.music.spotui.di.Palette().extractSecondColorFromCoverUrl(context, cover) { coverColor = it }
    }
    val tint by androidx.compose.animation.animateColorAsState(coverColor, androidx.compose.animation.core.tween(450), label = "cover")

    // Pulled down anywhere, the player closes, as the music player does from the top of its list.
    val pullToClose = com.music.spotui.ui.components.rememberPullToDismiss { com.music.spotui.ui.navigation.PlayerSheet.close() }
    Box(
        Modifier
            .fillMaxSize()
            .pullToDismiss(pullToClose)
            .graphicsLayer { translationY = pullToClose.offset }
            .background(Brush.verticalGradient(listOf(tint, AppBackground))),
    ) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            // Top bar: down chevron, "playing from the podcast", the episode's own menu.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_down),
                    contentDescription = stringResource(R.string.a11y_close_player),
                    tint = Color.White,
                    modifier = Modifier.size(26.dp).clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { com.music.spotui.ui.navigation.PlayerSheet.close() },
                )
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 12.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) {
                            val showId = episodeShowId(np.contextUri)
                            if (showId.isNotBlank()) navController.navigate(com.music.spotui.ui.navigation.showRoute(showId, showName))
                        },
                ) {
                    Text(
                        stringResource(R.string.podcast_playing_from),
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        showName,
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Icon(
                    Icons.Default.MoreVert,
                    contentDescription = stringResource(R.string.episode_options),
                    tint = Color.White,
                    modifier = Modifier.size(24.dp).pressable(0.9f) { showMenu = true },
                )
            }

            Spacer(Modifier.weight(1f))
            GlideImage(
                transition = CrossFade,
                model = cover,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth(0.82f)
                    .aspectRatio(1f)
                    .align(Alignment.CenterHorizontally)
                    .clip(RoundedCornerShape(8.dp)),
            )
            Spacer(Modifier.weight(1f))

            // Episode title and the show it comes from.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = title,
                        color = Color.White,
                        fontSize = 21.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = showName,
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) {
                            val showId = episodeShowId(np.contextUri)
                            if (showId.isNotBlank()) navController.navigate(com.music.spotui.ui.navigation.showRoute(showId, showName))
                        },
                    )
                }
                LikeButton(liked = saved, size = 28.dp) { SongPlayer.setSaved(uri, !saved) }
            }

            PodcastProgress(playing = playing, uri = np.uri, duration = duration)

            // Podcast transport: speed, −15 s, play, +15 s, sleep timer. No shuffle, no repeat:
            // the official app does not put them on an episode.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp).height(64.dp),
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(width = 44.dp, height = 28.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .border(1.dp, Color.White.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
                        .pressable(0.92f) { showSpeed = true },
                ) {
                    Text(
                        stringResource(R.string.player_speed_chip, speedLabel(SongPlayer.speed.value)),
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
                SkipIcon(back = true)
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(Color.White)
                        .pressable(0.92f) {
                            if (playing) SongPlayer.pause() else SongPlayer.play()
                        },
                ) {
                    com.music.spotui.ui.components.PlayPauseIcon(
                        playing = playing,
                        contentDescription = stringResource(if (playing) R.string.notif_action_pause else R.string.notif_action_play),
                        tint = Color.Black,
                        modifier = Modifier.size(30.dp),
                    )
                }
                SkipIcon(back = false)
                Icon(
                    Icons.Outlined.Timer,
                    contentDescription = stringResource(R.string.player_sleep_timer),
                    tint = if (SongPlayer.sleepEndsAt.value != null) AppAccent else Color.White,
                    modifier = Modifier.size(26.dp).pressable(0.9f) { showSleep = true },
                )
            }

            PlayerConnectRow(
                context = context,
                title = title,
                artist = showName,
                coverUri = cover,
                onDevices = { showDevices = true },
                onQueue = { showQueue = true },
            )
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** ±15 s: the circular arrow with the number inside, as the official player draws it. */
@Composable
private fun SkipIcon(back: Boolean) {
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(40.dp).pressable(0.9f) {
        SongPlayer.skipBy(if (back) -15_000L else 15_000L)
    }) {
        Icon(
            imageVector = if (back) Icons.Default.RotateLeft else Icons.Default.RotateRight,
            contentDescription = stringResource(if (back) R.string.player_back_15 else R.string.player_forward_15),
            tint = Color.White,
            modifier = Modifier.size(34.dp),
        )
        Text(
            text = "15",
            color = Color.White,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
    }
}

/** The show id carried by the played context ("spotify:show:<id>"), "" when there is none. */
internal fun episodeShowId(contextUri: String): String =
    contextUri.takeIf { it.startsWith("spotify:show:") }?.removePrefix("spotify:show:").orEmpty()
