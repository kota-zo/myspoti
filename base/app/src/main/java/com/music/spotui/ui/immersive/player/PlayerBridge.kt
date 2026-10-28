/*
 * Adapted from BitChord (https://github.com/kushagrasinghx/BitChord), GPL-3.0-or-later,
 * sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/PlayerHost.kt and PlayerState.kt at edc1e6f.
 * Modified for Myspoti, 2026-10.
 *
 * Changes: only the shape is kept (a player screen that is given plain state and a set of
 * actions, and knows nothing about the engine). The state is filled from Myspoti's
 * CurrentSongState, SongPlayer and PlayerViewModel, read-only, and every action does what the
 * Classic player's does.
 */
package com.music.spotui.ui.immersive.player

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import app.myspoti.engine.R
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.di.SongPlayer
import com.music.spotui.ui.navigation.PlayerSheet
import com.music.spotui.ui.navigation.albumRoute
import com.music.spotui.ui.screens.SmartShuffle
import com.music.spotui.ui.screens.nowPlayingSong
import com.music.spotui.ui.viewmodel.PlayerViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Repeat modes, as [SongPlayer.repeatMode] reports them. */
object ImmersiveRepeat {
    const val OFF = 0
    const val ALL = 1
    const val ONE = 2
}

/**
 * What the Immersive player draws, with no engine behind it. [positionMs] is a provider: the
 * scrubber and the time labels call it every frame, so the screen does not recompose for it.
 */
@Immutable
data class ImmersivePlayerState(
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val coverUrl: String = "",
    val playing: Boolean = false,
    val buffering: Boolean = false,
    /** The playhead in milliseconds, read per frame (SongPlayer.getCurrentPosition()). */
    val positionMs: () -> Long = { 0L },
    val durationMs: Long = 0L,
    val shuffle: Boolean = false,
    /** Smart shuffle is on for the list that plays. */
    val smart: Boolean = false,
    /** [ImmersiveRepeat]. */
    val repeatMode: Int = ImmersiveRepeat.OFF,
    val liked: Boolean = false,
    val isEpisode: Boolean = false,
    val explicit: Boolean = false,
    /** An ad break: the page publishes no track, a label stands in for the title. */
    val adPlaying: Boolean = false,
    /** The list the music plays from (playlist, album, Liked Songs…), or blank. */
    val contextName: String = "",
    /** The other device the sound goes to, or null for this phone. */
    val deviceName: String? = null,
    /** The track as the options sheet wants it; null while nothing is known. */
    val song: SongsModel? = null,
    /**
     * A real crossfade going on: its outgoing cover is laid over [coverUrl] and the colours
     * blend; until half way [title], [artist] and the times are still the outgoing song's.
     */
    val blend: com.music.spotui.ui.components.SongBlend? = null,
)

/** What the player does; nothing here touches the UI, the screen owns its sheets and panels. */
@Stable
class ImmersivePlayerActions(
    val playPause: () -> Unit = {},
    val next: () -> Unit = {},
    val previous: () -> Unit = {},
    val seekTo: (Long) -> Unit = {},
    val toggleShuffle: () -> Unit = {},
    val cycleRepeat: () -> Unit = {},
    val toggleLike: () -> Unit = {},
    /** Opens the album of the playing track. */
    val openAlbum: () -> Unit = {},
    /** Opens the artist of the playing track (a choice first when there are several). */
    val openArtist: () -> Unit = {},
    /** Closes the player (PlayerSheet.close()). */
    val close: () -> Unit = {},
)

/** The two, as [rememberImmersivePlayer] returns them. */
@Immutable
data class ImmersivePlayerBridge(val state: ImmersivePlayerState, val actions: ImmersivePlayerActions)

/**
 * Fills the player's state from Myspoti's playback (read-only) and wires its actions to the
 * same calls the Classic player makes. Needs the [navController] for the artist and album pages.
 */
@Composable
fun rememberImmersivePlayer(navController: NavController): ImmersivePlayerBridge {
    val vm: PlayerViewModel = viewModel()
    val context = LocalContext.current
    val np by SongPlayer.nowPlaying
    val title = vm.currentSongTitle.value
    val singer = vm.currentSongSinger.value
    val cover = vm.currentSongCoverUri.value
    val album = vm.currentSongAlbum.value
    val playing = vm.currentSongPlayingState.value
    val songId = vm.currentSongId.value
    val liked = vm.likeState.value
    val shuffle = vm.shuffleState.value
    val smart = SmartShuffle.on.value != null && SmartShuffle.on.value == np.contextUri
    val smartScope = rememberCoroutineScope()
    val repeat = SongPlayer.repeatMode.value
    val buffering = SongPlayer.isBuffering
    val adPlaying = SongPlayer.adPlaying
    val episode = SongPlayer.isEpisode
    val device = SongPlayer.activeDevice.value?.name

    // The length is plain data in the engine, not Compose state: asked again until it changes
    // (a track still loading reports 0), then left alone.
    var duration by remember { mutableLongStateOf(SongPlayer.getDuration()) }
    LaunchedEffect(songId, np.uri) {
        while (true) {
            val d = SongPlayer.getDuration()
            if (d != duration) duration = d
            delay(if (d > 0L) 1_500L else 300L)
        }
    }

    // The words follow what is heard: the song going out until half way through a crossfade.
    val blend = com.music.spotui.ui.components.rememberSongBlend()
    val outgoing = blend?.takeIf { it.outgoingWords }
    val state = ImmersivePlayerState(
        title = outgoing?.song?.title ?: title,
        artist = outgoing?.song?.artist ?: singer,
        album = album,
        coverUrl = app.myspoti.host.android.Covers.large(cover),
        playing = playing,
        buffering = buffering,
        positionMs = remember(outgoing) { outgoing?.let { o -> { o.positionMs() } } ?: { SongPlayer.getCurrentPosition() } },
        durationMs = outgoing?.song?.durationMs ?: duration,
        shuffle = shuffle,
        smart = smart,
        repeatMode = repeat,
        liked = liked,
        isEpisode = episode,
        explicit = np.explicit,
        adPlaying = adPlaying,
        contextName = np.contextName.ifBlank { SongPlayer.knownContextName(np.contextUri) }.ifBlank { album },
        deviceName = device,
        song = if (title.isBlank()) null else nowPlayingSong(vm),
        blend = blend,
    )

    // Read at the moment of the tap, not captured: the lambdas below live as long as the screen.
    val actions = remember(vm, navController) {
        ImmersivePlayerActions(
            playPause = {
                // The same optimistic update as the Classic player: the glyph flips at once.
                val nowPlaying = vm.currentSongPlayingState.value
                if (nowPlaying) SongPlayer.pause() else SongPlayer.play()
                vm.updateSongState(
                    vm.currentSongCoverUri.value,
                    vm.currentSongTitle.value,
                    vm.currentSongSinger.value,
                    !nowPlaying,
                    vm.currentSongId.value,
                    vm.currentSongIndex.value,
                    vm.currentSongAlbum.value,
                )
            },
            next = { SongPlayer.swipeNext() },
            previous = { SongPlayer.previous() },
            seekTo = { SongPlayer.seekTo(it) },
            // As in the Classic player: off → shuffle → smart shuffle (playlists, Liked Songs) → off.
            toggleShuffle = {
                val ctx = SongPlayer.nowPlaying.value.contextUri
                when {
                    SmartShuffle.on.value != null && SmartShuffle.on.value == ctx -> { SmartShuffle.on.value = null; vm.updateShuffleState(false) }
                    vm.shuffleState.value && SmartShuffle.canPlay(ctx) -> {
                        SmartShuffle.on.value = ctx
                        Toast.makeText(context, context.getString(R.string.smart_shuffle_on), Toast.LENGTH_SHORT).show()
                        smartScope.launch {
                            if (!SmartShuffle.startFor(ctx, SongPlayer.currentTrackId)) {
                                SmartShuffle.on.value = null
                                Toast.makeText(context, context.getString(R.string.page_load_failed), Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    else -> vm.updateShuffleState(!vm.shuffleState.value)
                }
            },
            cycleRepeat = { SongPlayer.cycleRepeat() },
            toggleLike = {
                val on = !vm.likeState.value
                vm.updateLikeState(on)
                if (on) Toast.makeText(context, context.getString(R.string.immersive_player_added_to_liked), Toast.LENGTH_SHORT).show()
            },
            openAlbum = {
                val albumId = SongPlayer.nowPlaying.value.albumUri.substringAfterLast(":")
                if (albumId.isNotBlank()) {
                    navController.navigate(albumRoute(vm.currentSongAlbum.value, vm.currentSongSinger.value, albumId))
                }
            },
            openArtist = {
                vm.goToArtist(SongPlayer.currentTrackId, vm.currentSongSinger.value) { route -> navController.navigate(route) }
            },
            close = { PlayerSheet.close() },
        )
    }
    return ImmersivePlayerBridge(state, actions)
}
