package com.music.spotui.data.entity

/**
 * A single row in "Your Library": either a saved album or a saved/followed
 * playlist. [spotifyId] is the Spotify string id used to open the detail screen
 * (playlistRoute for playlists, name-based albumRoute for albums).
 */
@kotlinx.serialization.Serializable
data class LibraryEntry(
    val spotifyId: String,
    val name: String,
    val subtitle: String,
    val coverUri: String,
    val isPlaylist: Boolean,
    val artists: String = "",
    /** A Spotify library folder (its full `spotify:user:…:folder:…` uri); empty otherwise. */
    val folderUri: String = "",
    /** Playlists and folders inside a folder. */
    val childCount: Int = 0,
    /** The Spotify uri (playlist, album, folder); empty for the app's own entries. */
    val uri: String = "",
    /** Pinned at the top of Your Library. */
    val pinned: Boolean = false,
    /** When it was added to the library (ISO 8601), for "Recently added". */
    val addedAt: String = "",
    /** A playlist's owner (`spotify:user:…`), for the "By me" chip; empty when unknown. */
    val ownerUri: String = "",
) {
    val isFolder: Boolean get() = folderUri.isNotBlank()
}
