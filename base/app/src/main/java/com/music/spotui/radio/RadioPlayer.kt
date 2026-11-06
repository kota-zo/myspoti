package com.music.spotui.radio

import android.content.Context
import android.content.Intent
import com.music.spotui.data.local.LocalTrack
import com.music.spotui.di.Graph
import com.music.spotui.di.LocalPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Starts a Live Radio station by handing it to [LocalPlayer] as a one-item "queue" (no duration,
 * no next/previous, no crossfade). From there it is simply a Local Files track as far as the
 * rest of the app is concerned — [LocalPlayer] already feeds the mini player, the full player,
 * the notification and the lock screen, and already pauses/resumes Spotify. No separate player,
 * session or notification exists here, so removing this file removes the feature cleanly.
 */
object RadioPlayer {
    private var started: RadioStation? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The station [LocalPlayer] is on (playing or paused) — null once anything else plays, or nothing does. */
    fun current(): RadioStation? {
        val station = started ?: return null
        return station.takeIf { LocalPlayer.current()?.let { it.isRadio && it.id == stableId(station) } == true }
    }

    /** Starts [station]: LocalPlayer.play pauses Spotify / whatever else was playing on its own. */
    fun play(station: RadioStation) {
        // A favorite saved before a stream was checked, say: never handed to a player that would fail on it.
        if (!RadioBrowserApi.playable(station.streamUrl)) return
        started = station
        LocalPlayer.play(
            listOf(track(station)), 0,
            context = Graph.app.getString(app.myspoti.engine.R.string.live_radio_title), contextUri = "",
        )
        scope.launch { RadioBrowserApi.reportClick(station.stationUuid) }
    }

    /** Leaves radio: only actually stops LocalPlayer if it is still on the station (not, say, a song since played). */
    fun stop() {
        if (LocalPlayer.current()?.isRadio == true) LocalPlayer.stop()
        started = null
    }

    /**
     * Shares the station playing as plain text, its name and homepage: not as a Spotify Code (its
     * image is drawn by Spotify's server, which would be sent the station), and never the stream
     * address itself.
     */
    fun share(context: Context) {
        val station = current() ?: return
        val homepage = station.homepage.takeIf { it.startsWith("https://") || it.startsWith("http://") }
        val text = listOfNotNull(station.name, homepage).joinToString("\n")
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        context.startActivity(Intent.createChooser(send, context.getString(app.myspoti.engine.R.string.action_share_label)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** The synthetic song id LocalPlayer/SongPlayer show [station] under, to match against Graph.currentSongState. */
    fun songIdFor(station: RadioStation): Int = ("local:" + stableId(station)).hashCode() and 0x7fffffff

    private fun track(station: RadioStation) = LocalTrack(
        id = stableId(station), uri = android.net.Uri.parse(station.streamUrl),
        title = station.name, artist = station.tags.firstOrNull()?.ifBlank { null } ?: station.country,
        album = Graph.app.getString(app.myspoti.engine.R.string.live_radio_title), durationMs = 0L,
        artUri = station.favicon.takeIf { it.isNotBlank() }?.let(android.net.Uri::parse)
            ?: android.net.Uri.parse("android.resource://${Graph.app.packageName}/drawable/live_radio_cover"),
        isRadio = true, stationUuid = station.stationUuid,
    )

    /**
     * Below -2^32: out of the range MediaStore hands out (always >= 0) and of the downloads' own
     * negative ids (OfflineStore.localIdOf, -1 to -2^31), so it is never taken for either.
     */
    private fun stableId(station: RadioStation) = -(1L shl 32) - (station.stationUuid.hashCode().toLong() and 0x7fffffffL)
}
