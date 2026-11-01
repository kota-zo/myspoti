package com.music.spotui.data.local

import com.music.spotui.data.entity.ArtistsModel
import com.music.spotui.data.entity.LibraryEntry

/**
 * The chips under "Your Library" (Playlists, By me, Artists, Albums, Podcasts, Songs), like the
 * official app's filter row: one at a time, tapping the chosen one again shows everything.
 */
enum class LibraryChip { ALL, PLAYLISTS, BY_ME, ARTISTS, ALBUMS, PODCASTS, SONGS }

object LibraryChips {
    /**
     * [songIds]: the library ids of the rows that hold songs rather than a playlist (Liked Songs,
     * Local Files, Downloaded). Folders count as playlists, as they sit among them in Spotify.
     * Artists are a separate list, so only the Artists chip keeps them. [myId]: the signed-in
     * account's Spotify user id; "By me" keeps the playlists it owns (a folder is not owned by
     * anyone, so it is left out). Podcasts keeps the rows of [podcastIds] (Your Episodes) and
     * then every followed show of [shows], which are read apart from the library list.
     */
    fun apply(
        chip: LibraryChip,
        entries: List<LibraryEntry>,
        artists: List<ArtistsModel>,
        songIds: Set<String>,
        myId: String? = null,
        shows: List<LibraryEntry> = emptyList(),
        podcastIds: Set<String> = emptySet(),
    ): Pair<List<LibraryEntry>, List<ArtistsModel>> = when (chip) {
        LibraryChip.ALL -> entries to artists
        LibraryChip.ARTISTS -> emptyList<LibraryEntry>() to artists
        LibraryChip.SONGS -> entries.filter { it.spotifyId in songIds } to emptyList()
        LibraryChip.PLAYLISTS -> entries.filter { it.spotifyId !in songIds && (it.isPlaylist || it.isFolder) } to emptyList()
        LibraryChip.BY_ME -> entries.filter { myId != null && it.ownerUri == "spotify:user:$myId" } to emptyList()
        LibraryChip.PODCASTS -> (entries.filter { it.spotifyId in podcastIds } + shows) to emptyList()
        LibraryChip.ALBUMS -> entries.filter { it.spotifyId !in songIds && !it.isPlaylist && !it.isFolder } to emptyList()
    }

    /** A tap on [tapped] while [current] is chosen. */
    fun toggle(current: LibraryChip, tapped: LibraryChip): LibraryChip = if (current == tapped) LibraryChip.ALL else tapped
}
