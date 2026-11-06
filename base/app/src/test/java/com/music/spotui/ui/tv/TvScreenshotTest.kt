/* SPDX-License-Identifier: GPL-3.0-only */
package com.music.spotui.ui.tv

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.takahirom.roborazzi.captureRoboImage
import com.music.spotui.data.entity.AlbumsModel
import com.music.spotui.data.entity.ArtistOverviewModel
import com.music.spotui.data.entity.ArtistTrackUi
import com.music.spotui.data.entity.ArtistsModel
import com.music.spotui.data.entity.ContextPageModel
import com.music.spotui.data.entity.HomeItem
import com.music.spotui.data.entity.HomeSection
import com.music.spotui.data.entity.LibraryEntry
import com.music.spotui.data.entity.SearchResults
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.di.SongPlayer
import com.music.spotui.ui.immersive.player.ImmersivePlayerActions
import com.music.spotui.ui.immersive.player.ImmersivePlayerState
import com.music.spotui.ui.immersive.player.ImmersiveRepeat
import com.music.spotui.ui.look.MeshPalette
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The TV screens drawn on the computer at 1920×1080, with made-up data and no network: the covers
 * stay as placeholders. Run with `./gradlew :app:testDebugUnitTest --tests '*TvScreenshotTest*'`;
 * the images land in app/build/outputs/roborazzi/.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-xhdpi", application = Application::class)
class TvScreenshotTest {
    @get:Rule val rule = createComposeRule()

    private fun shoot(name: String, content: @Composable () -> Unit) {
        // The player never idles (its clock, the marquee, the playhead): time is moved by hand.
        rule.mainClock.autoAdvance = false
        rule.setContent(content)
        rule.mainClock.advanceTimeBy(1_000)
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/tv-$name.png")
    }

    private fun song(i: Int, title: String, artist: String) =
        SongsModel(i, title, "Album", artist, "", "spotify:track:$i", durationMs = 180_000 + i * 7_000)

    private val songs = listOf(
        song(1, "Midnight City", "M83"), song(2, "Breathe Deeper", "Tame Impala"), song(3, "Nightcall", "Kavinsky"),
        song(4, "Instant Crush", "Daft Punk, Julian Casablancas"), song(5, "Electric Feel", "MGMT"), song(6, "Redbone", "Childish Gambino"),
        song(7, "The Less I Know the Better", "Tame Impala"), song(8, "Kids", "MGMT"), song(9, "Genesis", "Justice"), song(10, "Odd Look", "Kavinsky, The Weeknd"),
    )

    private fun playlists(n: Int, prefix: String) = List(n) { HomeItem.Playlist("$prefix ${it + 1}", "", "Mix of the week", id = "pl$prefix$it") }

    @Test fun home() = shoot("home") {
        TvFrame(TvTab.Home, {}) {
            TvHomeShelves("Good evening", listOf(
                HomeSection("", playlists(6, "Daily Mix")),
                HomeSection("Made for you", playlists(9, "Discover")),
                HomeSection("Recently played", playlists(9, "Radio")),
            )) {}
        }
    }

    @Test fun list() = shoot("list") {
        val entry = LibraryEntry("pl1", "Night Drive", "Myspoti", "", isPlaylist = true, uri = "spotify:playlist:pl1")
        TvFrame(TvTab.Library, {}) {
            TvSongsPage(entry, ContextPageModel("spotify:playlist:pl1", "playlist", "Night Drive", "", ownerName = "Myspoti", tracks = songs), {}, {}, {})
        }
    }

    @Test fun search() = shoot("search") {
        TvFrame(TvTab.Search, {}) {
            TvSearchResults(SearchResults(
                songs = songs.take(8),
                artists = listOf("M83", "Tame Impala", "Kavinsky", "MGMT", "Justice", "Daft Punk", "Air").mapIndexed { i, n -> ArtistsModel(n, "", id = "a$i") },
                albums = List(8) { AlbumsModel(it, "M83", "", "Album ${it + 1}", "2011", spotifyId = "al$it") },
            ), {}, {})
        }
    }

    @Test fun artist() = shoot("artist") {
        TvFrame(TvTab.Search, {}) {
            TvArtistPage(ArtistOverviewModel(
                id = "a1", name = "Tame Impala", monthlyListeners = 31_245_871,
                topTracks = songs.map { ArtistTrackUi(it) },
                popularReleases = List(8) { AlbumsModel(it, "Tame Impala", "", "Release ${it + 1}", "2020", spotifyId = "r$it") },
                relatedArtists = listOf("MGMT", "Pond", "Unknown Mortal Orchestra", "Mac DeMarco").mapIndexed { i, n -> ArtistsModel(n, "", id = "r$i") },
            ), {}, {})
        }
    }

    @Test fun player() = shoot("player") {
        TvFrame(TvTab.NowPlaying, {}) {
            TvPlayerScreen(
                ImmersivePlayerState(title = "Breathe Deeper", artist = "Tame Impala", playing = true, positionMs = { 74_000L }, durationMs = 372_000L,
                    shuffle = true, repeatMode = ImmersiveRepeat.ALL, liked = true),
                ImmersivePlayerActions(),
                MeshPalette(listOf(Color(0xFF5B2A86), Color(0xFFC04A7A), Color(0xFF1F3B73), Color(0xFFE08A4F))),
                onArtist = {}, onDevices = {},
            ) { _, modifier -> FakeLyrics(modifier) }
        }
    }

    @Test fun devices() = shoot("devices") {
        TvDevicesList(listOf(
            SongPlayer.ConnectDevice("1", "Salon TV", "TV", isSelf = true, isActive = false),
            SongPlayer.ConnectDevice("2", "Pixel 9", "SMARTPHONE", isSelf = false, isActive = true),
            SongPlayer.ConnectDevice("3", "Kitchen speaker", "CAST_AUDIO", isSelf = false, isActive = false),
        )) {}
    }

    /** Stands in for the lyrics panel, which needs the engine. */
    @Composable
    private fun FakeLyrics(modifier: Modifier) {
        Column(modifier.padding(top = 40.dp)) {
            listOf("I don't know where I'm going", "But I know I'm on my way", "Breathe deeper, feel the night", "Every colour in the light", "We could stay here")
                .forEachIndexed { i, line ->
                    Text(line, color = Color.White.copy(alpha = if (i == 2) 1f else 0.35f), fontSize = 34.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(vertical = 12.dp))
                }
        }
    }
}
