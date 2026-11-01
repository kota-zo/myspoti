package com.music.spotui.ui.viewmodel

import com.music.spotui.di.Graph
import android.content.Context
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.di.CurrentSongState
import com.music.spotui.di.SongPlayer
import com.music.spotui.ui.repository.AppRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class PlayerViewModel constructor(private val currentSongState: CurrentSongState = Graph.currentSongState, private val repository: AppRepository = Graph.repository) : ViewModel(){

    val currentSongTitle: State<String> get() = currentSongState.title
    val currentSongSinger: State<String> get() = currentSongState.singer
    val currentSongCoverUri: State<String> get() = currentSongState.coverUri
    val currentSongPlayingState: State<Boolean> get() = currentSongState.playingState
    val currentSongIndex : State<Int> get() = currentSongState.songIndex
    val currentSongAlbum : State<String> get() = currentSongState.album

    val currentSongId : State<Int> get() = currentSongState.songId

    val queue : State<List<SongsModel>> get() = currentSongState.queue

    fun updateQueue(songs: List<SongsModel>) = currentSongState.updateQueue(songs)

    /** "Play next" / "Add to queue": Spotify's own queue. */
    fun playNext(song: SongsModel) = SongPlayer.playNext(song.spotifyTrackId)

    fun addToQueue(song: SongsModel): Boolean {
        if (song.spotifyTrackId.isBlank()) return false
        SongPlayer.addToQueue(song.spotifyTrackId)
        return true
    }


    val shuffleState = currentSongState.shuffle
    val repeatState = currentSongState.repeat
    val likeState = currentSongState.likeState


    val playingArtist by mutableStateOf(currentSongSinger.value)




    // Spotify owns the queue (context + autoplay): skip in the engine.
    fun playNextSongs(queueSongs : List<SongsModel>, context: Context) {
        SongPlayer.swipeNext()
    }

    /**
     * Opens the artist page for a track: resolves the EXACT artist (name + id)
     * from the track's Spotify id when available, so a display name like "RAM"
     * can't fuzzy-match to "Rammstein". Falls back to the display name.
     */
    // Associate every Canvas result with the confirmed track that requested it.
    private val canvasRequests = CanvasRequests()
    private val _canvasState = mutableStateOf(CanvasTrackState())
    internal val canvasState: State<CanvasTrackState> get() = _canvasState
    private var canvasJob: Job? = null

    fun loadCanvas(trackId: String, allowFetch: Boolean = true) {
        // Already looked up (prefetched when the song started, or a reopened player): at once,
        // so the screen knows before its first frame whether the Canvas or the cover shows.
        repository.knownCanvas(trackId)?.let { known ->
            canvasJob?.cancel(); canvasJob = null
            canvasRequests.resolve(trackId, known.getOrNull())
            _canvasState.value = canvasRequests.state
            return
        }
        val request = canvasRequests.select(trackId, allowFetch) ?: return
        canvasJob?.cancel()
        canvasJob = null
        _canvasState.value = canvasRequests.state
        if (!request.fetch) return
        canvasJob = viewModelScope.launch {
            try {
                val url = withContext(Dispatchers.IO) { repository.provideCanvasUrl(request.trackId) }
                if (canvasRequests.complete(request, url)) _canvasState.value = canvasRequests.state
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // A transient network/token failure falls back to the artwork.
                if (canvasRequests.fail(request)) _canvasState.value = canvasRequests.state
            } finally {
                if (canvasRequests.owns(request)) canvasJob = null
            }
        }
    }

    /** "Go to song radio": opens the track's radio, Spotify's station playlist. */
    fun goToSongRadio(trackId: String, title: String, navigate: (route: String) -> Unit, onFailed: () -> Unit) {
        viewModelScope.launch {
            val id = com.music.spotui.di.Graph.api.radioPlaylistId("spotify:track:$trackId")
            if (id.isNullOrBlank()) onFailed()
            else navigate(com.music.spotui.ui.navigation.playlistRoute(id, title))
        }
    }

    /** The song's artist page; a song by several artists asks which one first (see ArtistChooser). */
    fun goToArtist(trackId: String, fallbackName: String, navigate: (route: String) -> Unit) =
        com.music.spotui.ui.components.ArtistChooser.goForTrack(trackId, fallbackName, navigate)

    /**
     * Play the track at an absolute [index] in [queueSongs]. Used by the now-playing
     * swipe pager, where the artwork follows the finger and settles on a chosen page.
     * No-op if [index] is already the current track (prevents a feedback replay when
     * the pager is merely snapping to reflect an external track change).
     */
    fun playSongAt(queueSongs: List<SongsModel>, index: Int, context: Context) {
        if (index !in queueSongs.indices) return
        val song = queueSongs[index]
        if (song.id == currentSongId.value) return
        updateSongState(song.coverUri, song.title, song.singer, true, song.id, index, song.album)
        SongPlayer.playSong(song.url, context, songs = queueSongs)
    }

    fun playPreviousSong(queueSongs : List<SongsModel>, context: Context) {
        SongPlayer.previous()
    }
    fun formatDuration(durationMillis: Long): String {
        val minutes = TimeUnit.MILLISECONDS.toMinutes(durationMillis)
        val seconds = TimeUnit.MILLISECONDS.toSeconds(durationMillis) - TimeUnit.MINUTES.toSeconds(minutes)
        return String.format("%01d:%02d", minutes, seconds)
    }
    fun updateSongState(coverUri: String, title: String, singer: String, playingState: Boolean, songId : Int, songIndex : Int = 0, album : String = "") {
        currentSongState.updateSongState(coverUri, title, singer, playingState, songId, songIndex, album)
    }

    // Shuffle / repeat / like are Spotify's: toggle them in the engine and show
    // the new value right away (the engine status confirms it shortly after).
    fun updateShuffleState(shuffleState : Boolean){
        SongPlayer.setShuffle(shuffleState)
        currentSongState.shuffle.value = shuffleState
    }
    fun updateRepeatState(repeatState : Boolean){
        SongPlayer.setRepeat(repeatState)
        currentSongState.updateRepeatState(repeatState)
    }
    fun updateLikeState(likeState : Boolean){
        SongPlayer.setLiked(likeState)
    }
}


internal fun confirmedTrackIdFromUri(uri: String): String =
    if (uri.startsWith("spotify:track:")) uri.removePrefix("spotify:track:") else ""

/** CurrentSongState uses this canonical id after the engine confirms a track. */
internal fun confirmedTrackIdForDisplay(uri: String, displayedSongId: Int): String {
    val trackId = confirmedTrackIdFromUri(uri)
    val confirmedSongId = ("track:$trackId").hashCode() and 0x7fffffff
    return trackId.takeIf { it.isNotBlank() && confirmedSongId == displayedSongId }.orEmpty()
}

internal data class CanvasTrackState(val trackId: String = "", val url: String? = null)
internal data class CanvasRequest(val generation: Long, val trackId: String, val fetch: Boolean)

/** Main-thread request ownership; generations also distinguish a replay of the same id. */
internal class CanvasRequests {
    var state = CanvasTrackState()
        private set
    private var generation = 0L
    private var loading = false
    private var resolved = false

    fun select(trackId: String, allowFetch: Boolean = true): CanvasRequest? {
        val changed = trackId != state.trackId
        val fetch = trackId.isNotBlank() && allowFetch && (changed || (!loading && !resolved))
        val stopLoading = !allowFetch && loading
        if (!changed && !fetch && !stopLoading) return null
        generation++
        if (changed) {
            state = CanvasTrackState(trackId)
            resolved = false
        }
        loading = fetch
        return CanvasRequest(generation, trackId, fetch)
    }

    fun owns(request: CanvasRequest): Boolean =
        request.generation == generation && request.trackId == state.trackId

    fun complete(request: CanvasRequest, url: String?): Boolean {
        if (!owns(request) || !request.fetch || !loading) return false
        state = CanvasTrackState(request.trackId, url?.takeIf { it.isNotBlank() })
        loading = false
        resolved = true
        return true
    }

    /** A result known without asking (cache). */
    fun resolve(trackId: String, url: String?) {
        generation++
        state = CanvasTrackState(trackId, url?.takeIf { it.isNotBlank() })
        loading = false
        resolved = true
    }

    fun fail(request: CanvasRequest): Boolean {
        if (!owns(request) || !request.fetch || !loading) return false
        state = CanvasTrackState(request.trackId)
        loading = false
        resolved = false
        return true
    }
}
