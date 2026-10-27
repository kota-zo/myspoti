package com.music.spotui.data.local

import com.music.spotui.data.entity.SongsModel

/**
 * Spotify playlists can hold "local files" of the user's computer (`spotify:local:...`), known by
 * their tags only. When the same song is among this device's files, the row plays from there.
 */
object LocalFileMatch {
    /** A playlist row that is a Spotify local file nothing on this device matched. */
    val SongsModel.isUnmatchedLocal: Boolean get() = url.startsWith(SPOTIFY_PREFIX)

    /** A playlist row that is a file of this device (its play key is `local:<content uri>`). */
    val SongsModel.isDeviceFile: Boolean get() = url.startsWith(DEVICE_PREFIX)

    const val SPOTIFY_PREFIX = "spotify:local:"
    const val DEVICE_PREFIX = "local:"

    /** The play key of a device file, as the player names it. */
    fun key(track: LocalTrack) = DEVICE_PREFIX + track.uri

    /** Lengths this close are the same song: Spotify keeps whole seconds, the tags are not exact. */
    private const val TOLERANCE_MS = 3_000L

    private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    /**
     * The device file for [song]: same title, and same artist when both say one; the closest
     * length wins, and a length off by more than [TOLERANCE_MS] is another recording.
     */
    fun find(song: SongsModel, files: List<LocalTrack>): LocalTrack? {
        val title = norm(song.title)
        if (title.isEmpty()) return null
        val artist = norm(song.singer)
        return files.asSequence()
            .filter { norm(it.title) == title }
            .filter { artist.isEmpty() || norm(it.artist).let { a -> a.isEmpty() || a in artist || artist in a } }
            .filter { song.durationMs <= 0 || it.durationMs <= 0 || kotlin.math.abs(it.durationMs - song.durationMs) <= TOLERANCE_MS }
            .minByOrNull { if (song.durationMs > 0) kotlin.math.abs(it.durationMs - song.durationMs) else 0L }
    }

    /** [songs] with their local-file rows switched to the matching device files, where there are. */
    fun resolve(songs: List<SongsModel>, files: List<LocalTrack>): List<SongsModel> {
        if (files.isEmpty() || songs.none { it.isUnmatchedLocal }) return songs
        return songs.map { s ->
            if (!s.isUnmatchedLocal) s else find(s, files)?.let { f ->
                s.copy(
                    id = (key(f).hashCode() and 0x7fffffff),
                    url = key(f),
                    coverUri = f.artUri.toString(),
                    album = s.album.ifBlank { f.album },
                    durationMs = if (s.durationMs > 0) s.durationMs else f.durationMs.toInt(),
                    playable = true,
                )
            } ?: s
        }
    }
}
