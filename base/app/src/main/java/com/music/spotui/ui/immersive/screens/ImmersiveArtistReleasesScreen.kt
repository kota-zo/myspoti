/*
 * Adapted from BitChord (https://github.com/kushagrasinghx/BitChord), GPL-3.0-or-later,
 * app/src/main/java/com/music/bitchord/ui/screens/DetailScreen.kt (ArtistShelfGridPage, the
 * page's section headings) at edc1e6f. Modified for Myspoti, 2026-10.
 */
package com.music.spotui.ui.immersive.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import app.myspoti.engine.R
import com.music.spotui.data.api.Response
import com.music.spotui.data.entity.AlbumsModel
import com.music.spotui.ui.immersive.Immersive
import com.music.spotui.ui.immersive.components.ImmersiveListRow
import com.music.spotui.ui.immersive.components.ImmersiveSongRowSkeleton
import com.music.spotui.ui.immersive.components.SectionHeader
import com.music.spotui.ui.immersive.components.immersiveClickable
import com.music.spotui.ui.immersive.shell.ImmersiveTopBar
import com.music.spotui.ui.immersive.shell.immersiveHazeSource
import com.music.spotui.ui.immersive.shell.immersiveLargeTitle
import com.music.spotui.ui.immersive.shell.immersiveTopContentPadding
import com.music.spotui.ui.navigation.albumRoute
import com.music.spotui.ui.viewmodel.ArtistViewModel

/**
 * All of an artist's releases, in the Immersive look: filter chips (albums, singles and EPs,
 * featured on) over the latest release and then the releases grouped by kind, newest first.
 * Same data as the Classic releases page.
 */
@Composable
fun ImmersiveArtistReleasesScreen(navController: NavController, artistName: String, artistId: String) {
    val artistViewModel: ArtistViewModel = viewModel()
    val overview by artistViewModel.overview.collectAsState()

    LaunchedEffect(artistName, artistId) { artistViewModel.loadArtistOverview(artistName, artistId) }

    val data = (overview as? Response.Success)?.data
    val releases = data?.popularReleases ?: emptyList()
    val featuredOn = data?.appearsOn ?: emptyList()
    // Releases grouped by kind. The catalogue names the kinds in capitals ("ALBUM", "SINGLE"…):
    // compared without case.
    val albums = releases.filter { it.type.lowercase() == "album" || it.type.lowercase() == "compilation" }
    val singles = releases.filter { it.type.lowercase() == "single" || it.type.lowercase() == "ep" }
    val untyped = releases.filter { it.type.lowercase() !in setOf("album", "compilation", "single", "ep") }
    val latest = releases.maxByOrNull { it.time }
    var filter by rememberSaveable { mutableStateOf("") } // "" | "albums" | "singles" | "featured"
    val listState = rememberLazyListState()
    val releasesTitle = stringResource(R.string.releases_title)

    fun openAlbum(album: AlbumsModel) {
        navController.navigate(albumRoute(album.name, album.artists.ifBlank { artistName }, album.spotifyId))
    }

    Box(Modifier.fillMaxSize().background(Immersive.Page)) {
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(top = immersiveTopContentPadding(), bottom = Immersive.BottomChrome),
            modifier = Modifier.fillMaxSize().immersiveHazeSource(),
        ) {
            immersiveLargeTitle(title = releasesTitle, subtitle = data?.name?.ifBlank { artistName } ?: artistName)
            item(key = "chips") {
                Row(
                    modifier = Modifier.padding(horizontal = Immersive.Gutter).padding(top = 4.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ReleaseFilterChip(stringResource(R.string.artist_releases_albums), filter == "albums") {
                        filter = if (filter == "albums") "" else "albums"
                    }
                    ReleaseFilterChip(stringResource(R.string.artist_releases_singles), filter == "singles") {
                        filter = if (filter == "singles") "" else "singles"
                    }
                    if (featuredOn.isNotEmpty()) {
                        ReleaseFilterChip(stringResource(R.string.immersive_detail_featured_on), filter == "featured") {
                            filter = if (filter == "featured") "" else "featured"
                        }
                    }
                }
            }
            if (overview is Response.Loading) {
                items(6, key = { "skeleton:release:$it" }) { index -> ImmersiveSongRowSkeleton(index) }
            }
            when (filter) {
                "albums" -> releaseRows(albums, ::openAlbum)
                "singles" -> releaseRows(singles, ::openAlbum)
                "featured" -> releaseRows(featuredOn, ::openAlbum)
                else -> {
                    latest?.let {
                        item(key = "latest-header") { SectionHeader(stringResource(R.string.latest_release_title)) }
                        releaseRows(listOf(it), ::openAlbum)
                    }
                    if (albums.isNotEmpty()) {
                        item(key = "albums-header") { SectionHeader(stringResource(R.string.artist_releases_albums)) }
                        releaseRows(albums, ::openAlbum)
                    }
                    if (singles.isNotEmpty()) {
                        item(key = "singles-header") { SectionHeader(stringResource(R.string.artist_releases_singles)) }
                        releaseRows(singles, ::openAlbum)
                    }
                    if (untyped.isNotEmpty()) {
                        if (albums.isEmpty() && singles.isEmpty()) {
                            item(key = "untyped-header") { SectionHeader(stringResource(R.string.popular_releases_section_title)) }
                        }
                        releaseRows(untyped, ::openAlbum)
                    }
                }
            }
        }
        ImmersiveTopBar(
            title = releasesTitle,
            listState = listState,
            onBack = { navController.navigateUp() },
        )
    }
}

/** One [ImmersiveListRow] per release. */
private fun androidx.compose.foundation.lazy.LazyListScope.releaseRows(
    releases: List<AlbumsModel>,
    onOpen: (AlbumsModel) -> Unit,
) {
    items(releases) { album ->
        ReleaseRow(album) { onOpen(album) }
    }
}

@Composable
private fun ReleaseRow(album: AlbumsModel, onClick: () -> Unit) {
    ImmersiveListRow(
        title = album.name,
        subtitle = releaseDetail(album).ifBlank { album.artists },
        imageUrl = album.coverUri,
        round = false,
        onClick = onClick,
    )
}

/** A filter pill: the accent when it is on, the raised grey when it is not. */
@Composable
private fun ReleaseFilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .immersiveClickable(onClick = onClick)
            .clip(CircleShape)
            .background(if (selected) Immersive.Accent else Immersive.Raised)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = Immersive.Subhead.copy(
                color = if (selected) androidx.compose.ui.graphics.Color.White else Immersive.Text,
                fontWeight = FontWeight.SemiBold,
            ),
        )
    }
}
