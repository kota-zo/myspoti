package com.music.spotui.data.preferences

import com.music.spotui.data.entity.SongsModel
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

// The last session's queue (and the list it was played in, kept beside it) and the rule that decides who wins at launch, kept pure so they
// are tested without Android. The song itself lives in SongPlayer's "myspoti_last_track".

object LastSession {
    /** Where the saved session played: Spotify's own player, or the local player (an extension's stream, a download). */
    const val SOURCE_SPOTIFY = "spotify"
    const val SOURCE_LOCAL = "local"

    private const val MAX_QUEUE = 100
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** The songs of the session's queue as stored; capped so the preferences stay small. */
    fun encodeQueue(songs: List<SongsModel>): String =
        json.encodeToString(ListSerializer(SongsModel.serializer()), songs.take(MAX_QUEUE))

    /** The stored queue, empty when missing or unreadable. */
    fun decodeQueue(raw: String?): List<SongsModel> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString(ListSerializer(SongsModel.serializer()), raw) }.getOrDefault(emptyList())
    }

    /**
     * The saved queue of the song [trackId]: [songs] from that song on, once each. The page's queue
     * can still list the song before it, and a queue read just after a skip belongs to the song
     * left: that one holds no [trackId] and is not saved with it. Empty when it does not hold it.
     */
    fun queueFor(trackId: String, songs: List<SongsModel>): List<SongsModel> {
        val at = songs.indexOfFirst { it.spotifyTrackId == trackId }
        if (trackId.isBlank() || at < 0) return emptyList()
        return songs.drop(at).distinctBy { it.spotifyTrackId.ifBlank { it.url } }
    }

    /**
     * Spotify's state at launch is older than the session saved from the local player: it still
     * shows the song played before and must not replace it. A state stamped after the save
     * (another device played since) or one that plays is real and wins.
     */
    fun engineStateIsStale(savedSource: String, savedAtMs: Long, engineTimestampMs: Long, enginePaused: Boolean): Boolean =
        savedSource == SOURCE_LOCAL && enginePaused && engineTimestampMs <= savedAtMs
}
