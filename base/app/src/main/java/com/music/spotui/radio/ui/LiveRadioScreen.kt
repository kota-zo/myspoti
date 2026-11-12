package com.music.spotui.radio.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import app.myspoti.engine.R
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import com.bumptech.glide.integration.compose.placeholder
import com.music.spotui.radio.RadioFavoritesStore
import com.music.spotui.radio.RadioPlayer
import com.music.spotui.radio.RadioStation
import com.music.spotui.ui.components.FindInListField
import com.music.spotui.ui.components.pressable
import com.music.spotui.ui.theme.AppAccent
import com.music.spotui.ui.theme.AppBackground
import com.music.spotui.ui.theme.AppMuted
import com.music.spotui.ui.theme.AppText
import com.music.spotui.ui.theme.headerTint

/**
 * The Live Radio screen of the Classic look ([ImmersiveLiveRadioScreen] is the Immersive one):
 * search the radio-browser.info directory, browse a handful of popular genres, and keep
 * favorites (see [LiveRadioState]). Deliberately its own small screen rather than reusing Library's
 * list/grid machinery — stations have no album, no artist, no duration, so little of that
 * machinery would apply.
 */
@Composable
fun LiveRadioScreen(navController: NavController) {
    val state = rememberLiveRadioState()
    val favorites = RadioFavoritesStore.list()

    Box(Modifier.fillMaxSize().background(AppBackground)) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(
            bottom = com.music.spotui.ui.components.bottomBarsSpace())) {
            item {
                Column(
                    Modifier.fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(headerTint(Color(0xFF1C6E7A), 0f), AppBackground)))
                        .statusBarsPadding().padding(horizontal = 16.dp),
                ) {
                    Spacer(Modifier.height(56.dp))
                    Image(
                        painterResource(R.drawable.live_radio_cover), null,
                        modifier = Modifier.align(Alignment.CenterHorizontally).size(120.dp).clip(RoundedCornerShape(6.dp)),
                    )
                    Text(stringResource(R.string.live_radio_title), color = AppText, fontSize = 26.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 16.dp))
                    Text(stringResource(R.string.live_radio_subtitle), color = AppMuted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
                    Spacer(Modifier.height(14.dp))
                    FindInListField(state.query, { state.query = it }, stringResource(R.string.live_radio_search_hint), Modifier.fillMaxWidth())
                    Spacer(Modifier.height(10.dp))
                }
            }

            if (favorites.isNotEmpty() && state.browsing) {
                item { SectionTitle(stringResource(R.string.live_radio_favorites)) }
                itemsIndexed(favorites, key = { _, s -> "fav-${s.stationUuid}" }) { _, s ->
                    StationRow(s)
                }
                item { Spacer(Modifier.height(8.dp)) }
            }

            if (state.genres.isNotEmpty()) item {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
                ) {
                    for (g in state.genres) {
                        val on = g == state.genre
                        Text(
                            g, color = if (on) Color.Black else AppText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.clip(RoundedCornerShape(50))
                                .background(if (on) AppAccent else com.music.spotui.ui.theme.tone(0xFF2A2A2A))
                                .pressable(0.95f) { state.pick(g) }
                                .padding(horizontal = 14.dp, vertical = 7.dp),
                        )
                    }
                }
            }

            item {
                SectionTitle(
                    when {
                        state.query.isNotBlank() -> stringResource(R.string.live_radio_search_results)
                        state.genre.isNotBlank() -> state.genre.replaceFirstChar { it.uppercase() }
                        else -> stringResource(R.string.live_radio_popular)
                    },
                )
            }

            // Said even over the curated picks: what follows them is missing.
            if (state.error) item {
                Text(stringResource(R.string.live_radio_error), color = AppMuted, fontSize = 14.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 16.dp))
            }
            val results = state.results
            when {
                state.loading && results == null -> item {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = AppAccent)
                    }
                }
                results?.isEmpty() == true && !state.error && state.unplayable == 0 -> item {
                    Text(stringResource(R.string.live_radio_no_results), color = AppMuted, fontSize = 14.sp,
                        modifier = Modifier.fillMaxWidth().padding(32.dp), textAlign = TextAlign.Center)
                }
                else -> itemsIndexed(results.orEmpty(), key = { _, s -> s.stationUuid }) { _, s ->
                    StationRow(s)
                }
            }
            if (state.unplayable > 0) item {
                Text(pluralStringResource(R.plurals.lib_radio_unplayable, state.unplayable, state.unplayable), color = AppMuted,
                    fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 16.dp))
            }
        }
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.statusBarsPadding().padding(12.dp).size(40.dp).clip(CircleShape)
                .background(AppBackground.copy(alpha = 0.4f)).pressable { navController.navigateUp() },
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.accessibility_navigate_back), tint = AppText)
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, color = AppText, fontSize = 17.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
}

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun StationRow(station: RadioStation) {
    // The station currently loaded in LocalPlayer (playing or paused on), by its synthetic song
    // id — the same signal the mini player, full player and notification already show.
    val currentId by com.music.spotui.di.Graph.currentSongState.songId
    val current = currentId == RadioPlayer.songIdFor(station)
    // Bars only while it is actually playing (not merely loaded/paused), same as any other row.
    val playing = current && com.music.spotui.di.Graph.currentSongState.playingState.value
    val fav = RadioFavoritesStore.favorites.containsKey(station.stationUuid)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().pressable(0.97f) { RadioPlayer.play(station) }
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Box(Modifier.size(48.dp)) {
            GlideImage(
                model = station.favicon, contentDescription = null, contentScale = ContentScale.Crop,
                loading = placeholder(R.drawable.live_radio_cover), failure = placeholder(R.drawable.live_radio_cover),
                modifier = Modifier.matchParentSize().clip(RoundedCornerShape(4.dp)),
            )
            // The same three dancing bars a playing song shows over its cover elsewhere in the app.
            if (playing) com.music.spotui.ui.immersive.components.ImmersivePlayingBars(Modifier.align(Alignment.Center))
        }
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            Text(station.name, color = if (current) AppAccent else AppText, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val subtitle = stationSubtitle(station)
            if (subtitle.isNotBlank()) Text(subtitle, color = AppMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(
            painterResource(if (fav) R.drawable.ic_favorite_filled else R.drawable.ic_favorite),
            contentDescription = stringResource(if (fav) R.string.live_radio_unfavorite else R.string.live_radio_favorite),
            tint = if (fav) AppAccent else AppText,
            modifier = Modifier.size(24.dp).pressable(0.9f) { RadioFavoritesStore.toggle(station) },
        )
    }
}
