package com.music.spotui.ui.viewmodel

import com.music.spotui.di.Graph
import androidx.compose.runtime.State
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.music.spotui.data.api.Response
import com.music.spotui.data.entity.ArtistOverviewModel
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.di.CurrentSongState
import com.music.spotui.ui.repository.AppRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class ArtistViewModel constructor(private val repository: AppRepository = Graph.repository, private val currentSongState: CurrentSongState = Graph.currentSongState) : ViewModel() {

    val currentSongPlayingState: State<Boolean> get() = currentSongState.playingState
    val currentSongId: State<Int> get() = currentSongState.songId


    private val _overview : MutableStateFlow<Response<ArtistOverviewModel>> = MutableStateFlow(Response.Loading())
    val overview : StateFlow<Response<ArtistOverviewModel>> = _overview

    fun updateQueue(songs: List<SongsModel>) = currentSongState.updateQueue(songs)

    fun updateSongState(coverUri: String, title: String, singer: String, playingState: Boolean, songId :Int, songIndex : Int = 0, album : String = "") {
        currentSongState.updateSongState(coverUri, title, singer, playingState, songId, songIndex, album)
    }

    val likeState = currentSongState.likeState

    fun updateLikeState(likeState : Boolean){
        currentSongState.updateLikeState(likeState)
    }

    // No look-ups at creation: the page loads what it shows (follow state comes from
    // the library check in ArtistScreen).


    private var loadedOverview: String? = null

    // Loads the full Spotify-style artist page (header, monthly listeners, bio,
    // popular tracks, discography, related artists) in one GQL round-trip.
    // Passing the exact Spotify artist id avoids the fuzzy name search.
    fun loadArtistOverview(artistName: String, artistId: String = "") {
        val key = artistId.ifBlank { artistName }
        if (loadedOverview == key) return
        loadedOverview = key
        viewModelScope.launch(Dispatchers.IO) {
            repository.provideArtistOverview(artistName, artistId).collect { overview ->
                _overview.value = overview
                // A failed page may be loaded again (Try again, or opening it anew).
                if (overview is Response.Error) loadedOverview = null
            }
        }
    }
}