package com.music.spotui.ui.immersive.podcast

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
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
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.myspoti.engine.R
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.di.Haptics
import com.music.spotui.ui.components.EpisodeDownloadButton
import com.music.spotui.ui.components.EpisodeProgressBar
import com.music.spotui.ui.components.episodeClock
import com.music.spotui.ui.components.episodeDateLabel
import com.music.spotui.ui.components.episodeMinutes
import com.music.spotui.ui.immersive.Immersive
import com.music.spotui.ui.immersive.components.ImmersiveCover
import com.music.spotui.ui.immersive.components.immersiveClickable
import com.music.spotui.ui.immersive.screens.ImmersiveRowDivider

/**
 * One episode (or book chapter) of a show: cover, title, a snippet, "date · length" and what is
 * left of it, then its own controls (menu, download, play). A tap opens the episode, a long press
 * opens its menu; the row shrinks a little under the finger.
 */
@Composable
internal fun ImmersiveEpisodeRow(
    episode: SongsModel,
    playing: Boolean,
    finished: Boolean,
    isNew: Boolean,
    onPlay: () -> Unit,
    onOpen: () -> Unit,
    onMenu: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val date = episodeDateLabel(episode.releaseDate)
    val minutes = episodeMinutes(episode.durationMs)
    val titleColor by animateColorAsState(if (playing) Immersive.Accent else Immersive.Text, label = "episode row title")
    Column(modifier.fillMaxWidth()) {
        Column(
            Modifier
                .fillMaxWidth()
                .immersiveClickable(onClick = onOpen, onLongClick = onMenu, pressedScale = 0.98f)
                .padding(horizontal = Immersive.Gutter, vertical = 12.dp),
        ) {
            Row {
                ImmersiveCover(episode.coverUri, Modifier.size(64.dp), Immersive.CoverSmall)
                Column(Modifier.weight(1f).padding(start = 14.dp)) {
                    Text(
                        text = episode.title,
                        style = Immersive.BodyStrong.copy(color = titleColor),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (episode.description.isNotBlank()) {
                        Spacer(Modifier.height(3.dp))
                        Text(text = episode.description, style = Immersive.Subhead, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.height(4.dp))
                    val started = episode.playedPositionMs > 0 && !finished
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (isNew) {
                            Text(
                                text = stringResource(R.string.episode_new),
                                style = Immersive.Caption.copy(color = Immersive.Accent, fontWeight = FontWeight.Bold),
                                maxLines = 1,
                                modifier = Modifier.padding(end = 6.dp),
                            )
                        }
                        if (finished) {
                            Icon(Icons.Rounded.Check, contentDescription = null, tint = Immersive.Accent, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                        }
                        val meta = buildString {
                            if (finished) append(stringResource(R.string.episode_marked_played)).append(" · ")
                            append(if (date.isBlank()) minutes else "$date · $minutes")
                        }
                        Text(text = meta, style = Immersive.Caption, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        if (started) {
                            Text(
                                text = stringResource(R.string.episode_remaining, episodeClock(episode.durationMs - episode.playedPositionMs)),
                                style = Immersive.Caption.copy(color = Immersive.Accent),
                                maxLines = 1,
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                    }
                    if (started) {
                        Spacer(Modifier.height(6.dp))
                        EpisodeProgressBar(episode.playedPositionMs, episode.durationMs, Immersive.Accent, Immersive.TextSecondary.copy(alpha = 0.2f))
                    }
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) {
                Box(
                    Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .immersiveClickable(onClick = onMenu, pressedScale = 0.9f),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Rounded.MoreHoriz,
                        contentDescription = stringResource(R.string.episode_options),
                        tint = Immersive.TextSecondary,
                        modifier = Modifier.size(22.dp),
                    )
                }
                Spacer(Modifier.weight(1f))
                EpisodeDownloadButton(episode, size = 24.dp)
                Spacer(Modifier.width(8.dp))
                PlayDisc(playing = playing, onClick = onPlay)
            }
        }
        ImmersiveRowDivider(inset = Immersive.Gutter)
    }
}

/** The episode's own play control: an accent disc with the triangle (two bars while it plays). */
@Composable
internal fun PlayDisc(playing: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(40.dp)
            .immersiveClickable(onClick = { Haptics.click(); onClick() }, pressedScale = 0.9f)
            .clip(CircleShape)
            .background(Immersive.Accent),
        contentAlignment = Alignment.Center,
    ) {
        Crossfade(targetState = playing, animationSpec = tween(150), label = "episode play glyph") { on ->
            Icon(
                imageVector = if (on) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                contentDescription = stringResource(if (on) R.string.notif_action_pause else R.string.episode_play),
                tint = Color.White,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}
