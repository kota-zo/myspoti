package com.music.spotui.radio

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import com.music.spotui.data.local.LocalTrack
import com.music.spotui.di.Graph
import com.music.spotui.di.LocalPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Starts a Live Radio station by handing it to [LocalPlayer] as a one-item "queue" (no duration,
 * no next/previous, no crossfade — a live stream has none of that). From there it is simply a
 * Local Files track as far as the rest of the app is concerned: [LocalPlayer] is what already
 * feeds the mini player, the full player, the system notification and the lock screen (and
 * already pauses Spotify / resumes it, see LocalPlayer.play / SongPlayer.pauseEngineForLocal),
 * so none of that is reproduced here. No separate player, session, service or notification for
 * radio exists any more; removing this file (and its call sites) removes the feature cleanly.
 */
object RadioPlayer {
    /** The last stream that failed to even start, so the UI can offer "try again" rather than nothing. */
    val lastError = mutableStateOf<String?>(null)

    /** The station [LocalPlayer] is currently on — null once anything else plays, or nothing does. */
    val current: State<RadioStation?> get() = _current
    private val _current = mutableStateOf<RadioStation?>(null)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Starts [station]: LocalPlayer.play pauses Spotify / whatever else was playing on its own. */
    fun play(station: RadioStation) {
        if (station.streamUrl.isBlank()) { lastError.value = "no_stream_url"; return }
        lastError.value = null
        _current.value = station
        LocalPlayer.play(
            listOf(track(station)), 0,
            context = Graph.app.getString(app.myspoti.engine.R.string.live_radio_title), contextUri = "",
        )
        scope.launch { RadioBrowserApi.reportClick(station.stationUuid) }
    }

    /** Leaves radio: only actually stops LocalPlayer if it is still on the station (not, say, a song since played). */
    fun stop() {
        if (LocalPlayer.current()?.isRadio == true) LocalPlayer.stop()
        _current.value = null
    }

    /** The synthetic song id LocalPlayer/SongPlayer show [station] under, to match against Graph.currentSongState. */
    fun songIdFor(station: RadioStation): Int = ("local:" + stableId(station)).hashCode() and 0x7fffffff

    private fun track(station: RadioStation) = LocalTrack(
        id = stableId(station), uri = android.net.Uri.parse(station.streamUrl),
        title = station.name, artist = station.tags.firstOrNull()?.ifBlank { null } ?: station.country,
        album = Graph.app.getString(app.myspoti.engine.R.string.live_radio_title), durationMs = 0L,
        artUri = station.favicon.takeIf { it.isNotBlank() }?.let(android.net.Uri::parse)
            ?: android.net.Uri.parse("android.resource://${Graph.app.packageName}/drawable/live_radio_cover"),
        isRadio = true,
    )

    /** Out of the range MediaStore ever hands out (always >= 0), so it can never collide with a real file's id. */
    private fun stableId(station: RadioStation) = -(kotlin.math.abs(station.stationUuid.hashCode().toLong()) + 1)
}
