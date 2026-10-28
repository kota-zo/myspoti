package com.music.spotui.ui.screens

import com.music.spotui.ui.components.wideClickable
import com.music.spotui.ui.theme.tone
import com.music.spotui.ui.theme.AppText
import com.music.spotui.ui.theme.AppMuted

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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import app.myspoti.api.best
import app.myspoti.engine.R
import app.myspoti.host.android.MyspotiRuntime
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.CrossFade
import com.bumptech.glide.integration.compose.GlideImage
import com.music.spotui.data.local.ListenHistory
import com.music.spotui.di.Graph
import com.music.spotui.di.SongPlayer
import com.music.spotui.ui.navigation.albumRoute
import com.music.spotui.ui.navigation.episodeRoute
import com.music.spotui.ui.navigation.showRoute
import com.music.spotui.ui.theme.AppAccent

private val Background: Color get() = com.music.spotui.ui.theme.AppBackground
private val Subtle: androidx.compose.ui.graphics.Color get() = AppMuted

@Composable
private fun ScreenHeader(title: String, navController: NavController) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(8.dp, 12.dp, 16.dp, 8.dp),
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = stringResource(R.string.accessibility_navigate_back),
            tint = AppText,
            modifier = Modifier.wideClickable(horizontal = 8.dp, vertical = 10.dp) { navController.popBackStack() }.size(40.dp).padding(8.dp),
        )
        Text(title, fontWeight = FontWeight.Bold, color = AppText, fontSize = 22.sp, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 6.dp))
    }
}

@Composable
private fun Loading() = com.music.spotui.ui.components.SkeletonList(count = 7)

@Composable
private fun SectionTitle(text: String) =
    Text(text, color = AppText, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(16.dp, 18.dp, 16.dp, 8.dp))

/** [kind] is the Spotify uri type ("album", "show" or "episode"): the what's-new feed also carries followed podcasts. */
internal data class ReleaseTile(val id: String, val name: String, val artists: String, val cover: String, val detail: String, val kind: String = "album")

/** The page a tile opens: a show and an episode have their own pages, an album tile opens the album. */
internal fun ReleaseTile.route(): String = when (kind) {
    "show" -> showRoute(id, name)
    "episode" -> episodeRoute(id, name)
    else -> albumRoute(name, artists, id)
}

/** "New releases": what the followed artists just released, then the latest albums Spotify features. */
@OptIn(ExperimentalGlideComposeApi::class)
@Composable
fun NewReleasesScreen(navController: NavController) {
    val followed by produceState<List<ReleaseTile>?>(null) {
        value = runCatching {
            MyspotiRuntime.api.home.whatsNew(0, 50).items.map { a ->
                ReleaseTile(a.uri.substringAfterLast(':'), a.name, a.artists.joinToString(", ") { it.name },
                    a.images.best(300)?.url.orEmpty(), listOfNotNull(a.type.lowercase().replaceFirstChar { it.uppercase() }, a.releaseDate?.take(10)).joinToString(" • "),
                    a.uri.split(':').getOrElse(1) { "album" })
            }
        }.getOrDefault(emptyList())
    }
    val latest by produceState<List<ReleaseTile>?>(null) {
        value = runCatching {
            Graph.api.newReleases().map { ReleaseTile(it.spotifyId, it.name, it.artists, it.coverUri, it.artists) }
        }.getOrDefault(emptyList())
    }
    Column(Modifier.fillMaxSize().background(Background).statusBarsPadding()) {
        ScreenHeader(stringResource(R.string.browse_new_releases), navController)
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            contentPadding = PaddingValues(12.dp, 0.dp, 12.dp, com.music.spotui.ui.components.bottomBarsSpace()),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            fun section(title: Int, list: List<ReleaseTile>?) {
                item(span = { GridItemSpan(2) }) { SectionTitle(stringResource(title)) }
                when {
                    list == null -> item(span = { GridItemSpan(2) }) { Loading() }
                    list.isEmpty() -> item(span = { GridItemSpan(2) }) {
                        Text(stringResource(R.string.nothing_here), color = Subtle, fontSize = 14.sp, modifier = Modifier.padding(16.dp, 4.dp))
                    }
                    else -> items(list, key = { "$title:${it.id}" }) { t ->
                        Column(Modifier.clickable { navController.navigate(t.route()) }) {
                            GlideImage(transition = CrossFade, model = t.cover, contentDescription = null, contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(6.dp)))
                            Text(t.name, color = AppText, fontSize = 13.sp, fontWeight = FontWeight.Bold, lineHeight = 16.sp, minLines = 2, maxLines = 2,
                                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                            Text(t.detail.ifBlank { t.artists }, color = AppMuted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            section(R.string.new_releases_followed, followed)
            section(R.string.new_releases_latest, latest)
        }
    }
}

/** "Your Episodes": saved podcast episodes, newest first, playable in place. */
@OptIn(ExperimentalGlideComposeApi::class)
@Composable
fun EpisodesScreen(navController: NavController) {
    val episodes by produceState<List<com.music.spotui.data.entity.SongsModel>?>(null) { value = Graph.api.getSavedEpisodes() }
    var filter by remember { mutableStateOf(com.music.spotui.data.preferences.PodcastPrefs.libraryFilter()) }
    Column(Modifier.fillMaxSize().background(Background).statusBarsPadding()) {
        ScreenHeader(stringResource(R.string.your_episodes_title), navController)
        when (val list = episodes) {
            null -> Loading()
            else -> if (list.isEmpty()) Text(stringResource(R.string.your_episodes_empty), color = Subtle, fontSize = 14.sp, modifier = Modifier.padding(20.dp))
            else LazyColumn(contentPadding = PaddingValues(bottom = com.music.spotui.ui.components.bottomBarsSpace())) {
                item(key = "filter") {
                    Text(
                        text = stringResource(com.music.spotui.ui.components.episodeFilterLabel(filter)),
                        color = if (filter == com.music.spotui.data.preferences.EpisodeFilter.ALL) AppText.copy(alpha = 0.8f) else AppAccent,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier
                            .clickable {
                                filter = filter.next()
                                com.music.spotui.data.preferences.PodcastPrefs.setLibraryFilter(filter)
                            }
                            .padding(16.dp, 12.dp),
                    )
                }
                val shown = filter.apply(list) { it.played }
                if (shown.isEmpty()) item(key = "all-played") {
                    Text(stringResource(R.string.show_filter_all_played), color = Subtle, fontSize = 14.sp, modifier = Modifier.padding(20.dp))
                }
                items(shown, key = { it.url }) { e ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable {
                            SongPlayer.registerContext(list.map { it.url }, "spotify:collection:your-episodes")
                            SongPlayer.playSong(e.url)
                        }.padding(16.dp, 8.dp),
                    ) {
                        GlideImage(transition = CrossFade, model = e.coverUri, contentDescription = null, contentScale = ContentScale.Crop,
                            modifier = Modifier.size(56.dp).clip(RoundedCornerShape(6.dp)))
                        Column(Modifier.padding(start = 12.dp).weight(1f)) {
                            Text(e.title, color = AppText, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(e.singer + " • " + formatMinutes(e.durationMs / 60_000L), color = Subtle, fontSize = 12.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            if (!e.played) {
                                Spacer(Modifier.height(4.dp))
                                com.music.spotui.ui.components.EpisodeProgressBar(e.playedPositionMs, e.durationMs, AppAccent, AppText.copy(alpha = 0.15f))
                            }
                        }
                        if (e.played) Icon(Icons.Default.Check, contentDescription = stringResource(R.string.episode_marked_played), tint = AppAccent, modifier = Modifier.padding(start = 8.dp).size(18.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun formatMinutes(min: Long) = if (min >= 60) "${min / 60} h ${min % 60} min" else "$min min"

/**
 * The in-app recap: listens on this phone from Spotify, downloads and local files. Spotify's own
 * Wrapped only counts what Spotify streamed; this one counts everything, and stays on the phone.
 */
@OptIn(ExperimentalGlideComposeApi::class)
@Composable
fun RecapScreen(navController: NavController) {
    val periods = listOf(R.string.recap_month to 30L, R.string.recap_year to 365L, R.string.recap_all to 36_500L)
    var period by remember { mutableStateOf(1) }
    // Off the main thread: the history file grows with every listen, and reading
    // and ranking it during composition janked the screen open.
    val loaded by produceState<ListenHistory.Recap?>(null, period) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            ListenHistory.recap(System.currentTimeMillis() - periods[period].second * 86_400_000L)
        }
    }
    val recap = loaded ?: ListenHistory.Recap(0, 0, emptyMap(), emptyList(), emptyList(), emptyList())
    // Spotify's ranking of the account first (the official "Listening stats"), then this phone's counter.
    var tab by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(0) }
    Column(Modifier.fillMaxSize().background(Background).statusBarsPadding()) {
        ScreenHeader(stringResource(R.string.recap_title), navController)
        StatsTabs(tab) { tab = it }
        if (tab == 0) SpotifyStatsTab(navController) else LazyColumn(contentPadding = PaddingValues(bottom = com.music.spotui.ui.components.bottomBarsSpace())) {
            item {
                Row(Modifier.padding(16.dp, 12.dp, 16.dp, 0.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    periods.forEachIndexed { i, (label, _) ->
                        FilterChip(selected = i == period, onClick = { period = i }, label = { Text(stringResource(label)) },
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = AppAccent, selectedLabelColor = Color.Black,
                                labelColor = AppText, containerColor = tone(0xFF2A2A2A)))
                    }
                }
            }
            item {
                Column(Modifier.padding(16.dp, 20.dp)) {
                    Text(stringResource(R.string.recap_minutes, recap.minutes), color = AppText, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                    Text(stringResource(R.string.recap_listens, recap.listens), color = Subtle, fontSize = 14.sp)
                    Spacer(Modifier.height(10.dp))
                    val src = recap.bySource
                    Text(stringResource(R.string.recap_sources,
                        src[ListenHistory.Source.SPOTIFY] ?: 0, src[ListenHistory.Source.DOWNLOAD] ?: 0, src[ListenHistory.Source.LOCAL] ?: 0),
                        color = Subtle, fontSize = 13.sp)
                }
            }
            item { WeeklyMinutesChart() }
            if (recap.listens == 0) item {
                Text(stringResource(R.string.recap_empty), color = Subtle, fontSize = 14.sp, modifier = Modifier.padding(16.dp))
            }
            fun ranked(title: Int, list: List<ListenHistory.Ranked>) {
                if (list.isEmpty()) return
                item { SectionTitle(stringResource(title)) }
                items(list.size) { i ->
                    val r = list[i]
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(16.dp, 6.dp)) {
                        Text("${i + 1}", color = Subtle, fontSize = 16.sp, modifier = Modifier.width(28.dp))
                        GlideImage(transition = CrossFade, model = r.cover, contentDescription = null, contentScale = ContentScale.Crop,
                            modifier = Modifier.size(48.dp).clip(RoundedCornerShape(4.dp)))
                        Column(Modifier.padding(start = 12.dp).weight(1f)) {
                            Text(r.name, color = AppText, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(listOf(r.subtitle, stringResource(R.string.recap_plays, r.count)).filter { it.isNotBlank() }.joinToString(" • "),
                                color = Subtle, fontSize = 12.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            ranked(R.string.recap_top_tracks, recap.topTracks)
            ranked(R.string.recap_top_artists, recap.topArtists)
            ranked(R.string.recap_top_albums, recap.topAlbums)
            item {
                Text(stringResource(R.string.recap_private_note), color = Color(0xFF7A7A7A), fontSize = 12.sp, modifier = Modifier.padding(16.dp, 24.dp))
            }
        }
    }
}
