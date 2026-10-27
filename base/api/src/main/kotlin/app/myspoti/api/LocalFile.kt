package app.myspoti.api

import java.net.URLDecoder

/**
 * A "local file" of a Spotify playlist: a song the user's desktop app added from its own disk.
 * Spotify keeps only its tags, in the uri: `spotify:local:{artist}:{album}:{title}:{seconds}`,
 * URL-encoded (a space is `+`, a colon `%3A`), any part possibly empty.
 */
object LocalFile {
    const val PREFIX = "spotify:local:"

    fun isLocal(uri: Uri): Boolean = uri.startsWith(PREFIX)

    /**
     * The row for [uri], or null when it is not a local file. [uid] is the playlist row's id.
     * It is not playable from Spotify (the file lives on another device), hence `playable = false`.
     */
    fun track(uri: Uri, uid: String? = null): Track? {
        if (!isLocal(uri)) return null
        val parts = uri.removePrefix(PREFIX).split(':')
        fun decode(s: String) = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)
        fun part(i: Int) = decode(parts.getOrNull(i).orEmpty())
        val artist = part(0)
        val album = part(1)
        // A title is the last-but-one part; an unencoded colon in it must not shift the duration.
        val seconds = parts.lastOrNull()?.toLongOrNull()
        val title = if (parts.size >= 4 && seconds != null) parts.subList(2, parts.size - 1).joinToString(":") { decode(it) } else part(2)
        return Track(
            uri = uri,
            name = title,
            durationMs = (seconds ?: 0L) * 1000,
            artists = if (artist.isBlank()) emptyList() else listOf(ArtistRef("", artist)),
            album = if (album.isBlank()) null else AlbumRef("", album),
            playable = false,
            uid = uid,
        )
    }
}
