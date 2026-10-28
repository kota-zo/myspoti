package com.music.spotui.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.myspoti.engine.R
import com.music.spotui.data.preferences.EpisodeFilter
import com.music.spotui.data.preferences.PodcastPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The words for [filter] in the show and "Your Episodes" lists. */
fun episodeFilterLabel(filter: EpisodeFilter): Int = when (filter) {
    EpisodeFilter.ALL -> R.string.show_filter_all
    EpisodeFilter.UNPLAYED_FIRST -> R.string.show_filter_unplayed_first
    EpisodeFilter.HIDE_PLAYED -> R.string.show_filter_hide_played
}

/**
 * A dot on a followed show's cover when it has an episode released since the user last opened it.
 * It asks Spotify for the newest episode of the show at most every few hours (see [PodcastPrefs]),
 * and only for shows the user has opened before. Draws nothing otherwise.
 */
@Composable
fun NewEpisodeDot(showId: String, color: Color, modifier: Modifier = Modifier) {
    if (showId.isBlank()) return
    LaunchedEffect(showId) { withContext(Dispatchers.IO) { PodcastPrefs.refresh(showId) } }
    if (!PodcastPrefs.hasNew(showId)) return
    Box(modifier.size(12.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.5f)).padding(1.5.dp).clip(CircleShape).background(color))
}

/** A thin bar with how far an episode was listened to; nothing when it was not started. */
@Composable
fun EpisodeProgressBar(positionMs: Long, durationMs: Int, color: Color, track: Color, modifier: Modifier = Modifier) {
    if (positionMs <= 0 || durationMs <= 0) return
    val fraction = (positionMs.toFloat() / durationMs).coerceIn(0.02f, 1f)
    Box(modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(track)) {
        Box(Modifier.fillMaxWidth(fraction).height(3.dp).background(color))
    }
}
