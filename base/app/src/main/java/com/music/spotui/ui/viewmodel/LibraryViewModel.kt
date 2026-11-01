package com.music.spotui.ui.viewmodel

import com.music.spotui.di.Graph
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.music.spotui.data.api.LibraryEdits
import com.music.spotui.data.api.Response
import com.music.spotui.data.entity.AccountModel
import com.music.spotui.data.entity.LibraryEntry
import com.music.spotui.data.preferences.cacheFollowedArtists
import com.music.spotui.data.preferences.cacheLibraryEntries
import com.music.spotui.data.preferences.getCachedFollowedArtists
import com.music.spotui.data.preferences.getCachedLibraryEntries
import com.music.spotui.ui.repository.AppRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch

class LibraryViewModel constructor(
    private val repository: AppRepository = Graph.repository,
    private val context: android.content.Context = Graph.app,
) : ViewModel() {

    private val _entries: MutableStateFlow<Response<List<LibraryEntry>>> = MutableStateFlow(Response.Loading())
    val entries: StateFlow<Response<List<LibraryEntry>>> = _entries

    private val _account: MutableStateFlow<Response<AccountModel>> = MutableStateFlow(Response.Loading())
    val account: StateFlow<Response<AccountModel>> = _account

    private val _followedArtists: MutableStateFlow<List<com.music.spotui.data.entity.ArtistsModel>> =
        MutableStateFlow(emptyList())
    val followedArtists: StateFlow<List<com.music.spotui.data.entity.ArtistsModel>> = _followedArtists

    private val _followedShows: MutableStateFlow<List<LibraryEntry>> = MutableStateFlow(emptyList())
    /** The shows the user follows, read when the Podcasts chip is chosen (see [loadFollowedShows]). */
    val followedShows: StateFlow<List<LibraryEntry>> = _followedShows

    /** The list as Spotify gave it; [entries] is this with the changes not yet in Spotify's list on top. */
    @Volatile private var server: List<LibraryEntry>? = null

    private fun show() {
        val list = server ?: return
        _entries.value = Response.Success(LibraryEdits.applyTo(null, list))
    }

    init {
        // Seed UI with the last-seen library immediately so it's visible offline.
        val cached = getCachedLibraryEntries(context)
        if (cached.isNotEmpty()) { server = cached; show() }
        // A delete, rename or new playlist is shown at once, and taken back if Spotify refuses.
        viewModelScope.launch { LibraryEdits.version.drop(1).collect { show() } }
        viewModelScope.launch { LibraryEdits.reloads.collect { load() } }
        val cachedArtists = getCachedFollowedArtists(context)
        if (cachedArtists.isNotEmpty()) _followedArtists.value = cachedArtists

        load()
        loadAccount()
        loadFollowedArtists()
    }

    private val _refreshing = MutableStateFlow(false)
    /** A pull-to-refresh is reading the library again. */
    val refreshing: StateFlow<Boolean> = _refreshing

    fun refresh() {
        if (_refreshing.value) return
        _refreshing.value = true
        viewModelScope.launch {
            // Both flows end when Spotify has answered (or failed): that is when the spinner goes.
            listOfNotNull(load(), loadFollowedArtists(), if (showsAsked) loadFollowedShows() else null).joinAll()
            _refreshing.value = false
        }
    }

    fun load() = viewModelScope.launch(Dispatchers.IO) {
        repository.provideLibrary().collect { response ->
            // Offline: keep the saved library instead of replacing it with a
            // spinner/error, so downloads stay reachable after a restart (#51).
            if (response !is Response.Success && _entries.value is Response.Success) return@collect
            // Nothing saved yet and no network: offer just the downloads (not cached).
            if (response is Response.Error) {
                _entries.value = Response.Success(emptyList())
                return@collect
            }
            if (response is Response.Success) {
                server = response.data
                show()
                cacheLibraryEntries(context, response.data)
            } else _entries.value = response
        }
    }

    private fun loadFollowedArtists() = viewModelScope.launch(Dispatchers.IO) {
        val artists = repository.provideFollowedArtists()
        _followedArtists.value = artists
        if (artists.isNotEmpty()) cacheFollowedArtists(context, artists)
    }

    /** The Podcasts chip was chosen once: a pull-to-refresh reads the shows again too. */
    @Volatile private var showsAsked = false

    /** Reads the followed shows again; the list stays as it was when Spotify does not answer. */
    fun loadFollowedShows() = viewModelScope.launch(Dispatchers.IO) {
        showsAsked = true
        val shows = repository.provideFollowedShows()
        if (shows.isNotEmpty()) _followedShows.value = shows
    }

    private fun loadAccount() = viewModelScope.launch(Dispatchers.IO) {
        repository.provideAccount().collect { _account.value = it }
    }
}
