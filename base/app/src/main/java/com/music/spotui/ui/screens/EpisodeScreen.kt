package com.music.spotui.ui.screens

import com.music.spotui.ui.components.wideClickable
import com.music.spotui.ui.theme.headerTint
import com.music.spotui.ui.theme.AppText

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import app.myspoti.engine.R
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.CrossFade
import com.bumptech.glide.integration.compose.GlideImage
import com.bumptech.glide.integration.compose.placeholder
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.di.SongPlayer
import com.music.spotui.ui.components.EpisodeDownloadButton
import com.music.spotui.ui.components.EpisodeOptionsSheet
import com.music.spotui.ui.components.Loader
import com.music.spotui.ui.components.episodeClock
import com.music.spotui.ui.components.episodeDateLabel
import com.music.spotui.ui.components.episodeMinutes
import com.music.spotui.ui.components.pressable
import com.music.spotui.ui.components.shareEpisode
import com.music.spotui.ui.components.spotifyUri
import com.music.spotui.ui.navigation.showRoute
import com.music.spotui.ui.theme.AppAccent
import com.music.spotui.ui.theme.AppBackground
import com.music.spotui.ui.viewmodel.EpisodeViewModel

/**
 * One podcast episode (or book chapter), as the official page shows it: cover, title, the show
 * it belongs to, date and length, the action row, then the description.
 */
@OptIn(ExperimentalGlideComposeApi::class)
@Composable
fun EpisodeScreen(navController: NavController, episodeId: String, fallbackName: String = "") {
    val vm: EpisodeViewModel = viewModel()
    val context = LocalContext.current
    LaunchedEffect(episodeId) { vm.load(episodeId) }

    val episode by vm.episode.collectAsState()
    val finished = vm.finished.value
    var menuOpen by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    val np by SongPlayer.nowPlaying
    val uri = "spotify:episode:$episodeId"
    val playing = np.uri == uri && SongPlayer.isPlaying()
    val saved = SongPlayer.library[uri] == true

    fun play() {
        val ep = episode ?: return
        SongPlayer.playSong(ep.url, context)
        // The mini player and the notification show it before Spotify reports it.
        com.music.spotui.di.Graph.currentSongState.updateSongState(ep.coverUri, ep.title, ep.singer, true, ep.id, 0, ep.album)
    }

    episode?.let { ep ->
        if (menuOpen) EpisodeOptionsSheet(
            song = ep,
            finished = finished,
            onPlay = ::play,
            onMarkFinished = { vm.setFinished(ep, it) },
            onDismiss = { menuOpen = false },
        )
    }

    Box(Modifier.fillMaxSize().background(AppBackground)) {
        LazyColumn(
            contentPadding = PaddingValues(bottom = com.music.spotui.ui.components.bottomBarsSpace()),
            modifier = Modifier.fillMaxSize(),
        ) {
            item {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(headerTint(Color(0xFF34302B), 0f), AppBackground)))
                        .statusBarsPadding(),
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .padding(12.dp)
                            .wideClickable { navController.navigateUp() }
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.35f)),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.accessibility_navigate_back), tint = Color.White)
                    }
                    GlideImage(
                        transition = CrossFade,
                        model = episode?.coverUri,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        failure = placeholder(R.drawable.placeholder),
                        modifier = Modifier
                            .fillMaxWidth(0.62f)
                            .aspectRatio(1f)
                            .align(Alignment.CenterHorizontally)
                            .clip(RoundedCornerShape(6.dp)),
                    )
                    Spacer(Modifier.height(18.dp))
                    Text(
                        text = episode?.title?.takeIf { it.isNotBlank() } ?: fallbackName,
                        color = AppText,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                    // The show, as the page links it.
                    val showId = episode?.showId.orEmpty()
                    if (showId.isNotBlank()) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                                .clickable { navController.navigate(showRoute(showId, episode?.singer.orEmpty())) },
                        ) {
                            GlideImage(
                                transition = CrossFade,
                                model = episode?.coverUri,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.size(22.dp).clip(RoundedCornerShape(3.dp)),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = episode?.singer.orEmpty(),
                                color = AppText,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    val date = episodeDateLabel(episode?.releaseDate.orEmpty())
                    val minutes = episodeMinutes(episode?.durationMs ?: 0)
                    Text(
                        text = if (date.isBlank()) minutes else "$date • $minutes",
                        color = AppText.copy(alpha = 0.65f),
                        fontSize = 13.sp,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                    // Action row: save, download, share, "…", then Play.
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                    ) {
                        SaveEpisodeButton(saved) {
                            val ep = episode ?: return@SaveEpisodeButton
                            SongPlayer.setSaved(ep.spotifyUri, !saved)
                        }
                        episode?.let { EpisodeDownloadButton(it, size = 26.dp) }
                        Icon(
                            Icons.Default.Share,
                            contentDescription = stringResource(R.string.a11y_share),
                            tint = AppText.copy(alpha = 0.85f),
                            modifier = Modifier.size(26.dp).pressable(0.9f) { episode?.let { shareEpisode(context, it) } },
                        )
                        Spacer(Modifier.width(20.dp))
                        Icon(
                            Icons.Default.MoreVert,
                            contentDescription = stringResource(R.string.episode_options),
                            tint = AppText.copy(alpha = 0.85f),
                            modifier = Modifier.size(26.dp).pressable(0.9f) { menuOpen = true },
                        )
                        Spacer(Modifier.weight(1f))
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .height(36.dp)
                                .clip(RoundedCornerShape(18.dp))
                                .background(AppAccent)
                                .pressable(0.95f) { if (playing) SongPlayer.pause() else play() }
                                .padding(horizontal = 20.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                com.music.spotui.ui.components.PlayPauseIcon(
                                    playing = playing,
                                    contentDescription = null,
                                    tint = Color.Black,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(if (playing) R.string.notif_action_pause else R.string.episode_play), color = Color.Black, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
            if (vm.loading.value) item { Loader() }
            if (vm.failed.value) item {
                Text(
                    stringResource(R.string.episode_not_found),
                    color = AppText.copy(alpha = 0.7f),
                    fontSize = 14.sp,
                    modifier = Modifier.padding(16.dp),
                )
            }
            episode?.let { ep ->
                if (ep.playedPositionMs > 0 && !finished) item {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    ) {
                        Text(
                            stringResource(R.string.episode_remaining, episodeClock(ep.durationMs - ep.playedPositionMs)),
                            color = AppAccent,
                            fontSize = 12.sp,
                        )
                    }
                }
                if (ep.description.isNotBlank()) item {
                    Column(Modifier.fillMaxWidth().animateContentSize(androidx.compose.animation.core.tween(250)).padding(16.dp)) {
                        Text(
                            text = com.music.spotui.ui.components.rememberLinkedText(ep.description, AppAccent),
                            color = AppText.copy(alpha = 0.78f),
                            fontSize = 14.sp,
                            maxLines = if (expanded) Int.MAX_VALUE else 4,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = stringResource(if (expanded) R.string.episode_show_less else R.string.episode_show_more),
                            color = AppText,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.clickable { expanded = !expanded },
                        )
                    }
                }
            }
        }
    }
}

/** ✓ disc when the episode is saved, outlined + otherwise (same shape as the player's). */
@Composable
private fun SaveEpisodeButton(saved: Boolean, onClick: () -> Unit) {
    if (saved) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(26.dp)
                .clip(CircleShape)
                .background(AppAccent)
                .pressable(0.9f, onClick = onClick),
        ) {
            Icon(Icons.Default.Check, contentDescription = stringResource(R.string.episode_unsave), tint = Color.Black, modifier = Modifier.size(17.dp))
        }
    } else {
        Icon(
            Icons.Outlined.Add,
            contentDescription = stringResource(R.string.episode_save),
            tint = AppText.copy(alpha = 0.85f),
            modifier = Modifier.size(28.dp).pressable(0.9f, onClick = onClick),
        )
    }
}
