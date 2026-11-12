package com.music.spotui.radio.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.music.spotui.radio.RadioBrowserApi
import com.music.spotui.radio.RadioStation
import kotlinx.coroutines.delay

/**
 * What the Live Radio screen shows, in either look: the search typed, the genre picked, and the
 * directory's answer to them. The two looks only draw it ([LiveRadioScreen], [ImmersiveLiveRadioScreen]).
 */
@Stable
internal class LiveRadioState(query: String = "", genre: String = "") {
    var query by mutableStateOf(query)
    var genre by mutableStateOf(genre)
        private set
    var genres by mutableStateOf<List<String>>(emptyList())
        private set
    var results by mutableStateOf<List<RadioStation>?>(null)
        private set
    var loading by mutableStateOf(false)
        private set
    /** How many more stations matched the search that the app cannot play: said under the list. */
    var unplayable by mutableStateOf(0)
        private set
    /** The directory did not answer the last ask: said on the screen, even over the curated picks. */
    var error by mutableStateOf(false)
        private set

    /** Nothing searched or picked: the favorites and the popular stations show. */
    val browsing: Boolean get() = query.isBlank() && genre.isBlank()

    /** A genre chip tapped: picked (a search typed is cleared), or put down when it already was. */
    fun pick(tag: String) {
        genre = if (genre == tag) "" else tag
        query = ""
    }

    /** The genre chips; none when the directory does not answer (the stations' own ask says so). */
    suspend fun loadGenres() {
        genres = try { RadioBrowserApi.tags(limit = 16).map { it.name } } catch (e: java.io.IOException) { emptyList() }
    }

    /**
     * Asks the directory for what [query] and [genre] say, a little after the user stops typing
     * (a newer ask cancels this one: its caller is keyed on both).
     */
    suspend fun load() {
        val q = query; val g = genre
        error = false
        unplayable = 0
        if (q.isBlank() && g.isBlank()) {
            // Curated picks show immediately, so "Popular" is never empty while the directory
            // call is in flight (or if it fails); the live top stations are appended after,
            // skipping any curated station the directory already returned.
            results = RadioBrowserApi.curatedPopular
            loading = true
            ask { RadioBrowserApi.topStations() }?.let { top ->
                val curatedNames = RadioBrowserApi.curatedPopular.map { it.name.lowercase() }.toSet()
                results = RadioBrowserApi.curatedPopular + top.filter { it.name.lowercase() !in curatedNames }
            }
            loading = false
            return
        }
        if (q.isNotBlank()) delay(400)
        loading = true
        // A station's uuid pasted from the directory's site is looked up as such, a name otherwise.
        val uuid = RadioBrowserApi.asStationUuid(q)
        val found = uuid?.let { ask { RadioBrowserApi.byUuid(it) }?.takeIf { r -> r.stations.isNotEmpty() || r.unplayable > 0 } }
            ?: ask { RadioBrowserApi.search(query = q, tag = g) }
        results = found?.stations
        unplayable = found?.unplayable ?: 0
        loading = false
    }

    /** [call]'s answer, or null with [error] set when the directory could not be reached. */
    private suspend fun <T> ask(call: suspend () -> T): T? =
        try { call() } catch (e: java.io.IOException) { error = true; null }

    companion object {
        val Saver = listSaver<LiveRadioState, String>(
            save = { listOf(it.query, it.genre) },
            restore = { LiveRadioState(it[0], it[1]) },
        )
    }
}

/** The screen's [LiveRadioState], kept across a rotation, with its asks to the directory running. */
@Composable
internal fun rememberLiveRadioState(): LiveRadioState {
    val state = rememberSaveable(saver = LiveRadioState.Saver) { LiveRadioState() }
    LaunchedEffect(Unit) { state.loadGenres() }
    LaunchedEffect(state.query, state.genre) { state.load() }
    return state
}

/** A station's line under its name, either look: its country, first genre and bitrate. */
internal fun stationSubtitle(station: RadioStation): String = listOfNotNull(
    station.country.takeIf { it.isNotBlank() },
    station.tags.firstOrNull()?.takeIf { it.isNotBlank() },
    station.bitrateKbps.takeIf { it > 0 }?.let { "$it kbps" },
).joinToString(" · ")
