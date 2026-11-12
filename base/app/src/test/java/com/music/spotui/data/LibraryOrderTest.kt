package com.music.spotui.data

import com.music.spotui.data.entity.ArtistsModel
import com.music.spotui.data.entity.LibraryEntry
import com.music.spotui.data.local.LibraryOrder
import com.music.spotui.data.local.LibraryOrder.Sort
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryOrderTest {
    private fun playlist(id: String, name: String, owner: String) =
        LibraryEntry(spotifyId = id, name = name, subtitle = "Playlist • $owner", coverUri = "", isPlaylist = true)
    private fun album(id: String, name: String, artists: String) =
        LibraryEntry(spotifyId = id, name = name, subtitle = "Album • $artists", coverUri = "", isPlaylist = false, artists = artists)
    private fun folder(uri: String, name: String) =
        LibraryEntry(spotifyId = uri, name = name, subtitle = "Folder • 2 playlists", coverUri = "", isPlaylist = false, folderUri = uri)

    private val liked = playlist("liked", "Liked Songs", "")
    private val b = playlist("b", "beta", "Zoe")
    private val a = playlist("a", "Alpha", "Max")
    private val e = album("e", "Éclat", "Adele")
    private val f = folder("spotify:user:x:folder:1", "Zzz folder")
    private val all = listOf(liked, b, a, e, f)

    private fun ids(l: List<LibraryEntry>) = l.map { it.spotifyId }

    @Test fun customWithoutSavedOrderKeepsSpotifys() =
        assertEquals(ids(all), ids(LibraryOrder.arrange(all, Sort.CUSTOM, emptyList(), 1)))

    @Test fun customFollowsSavedOrderAndPutsNewEntriesFirst() {
        val saved = listOf("spotify:user:x:folder:1", "a", "b")
        assertEquals(listOf("liked", "e", "spotify:user:x:folder:1", "a", "b"), ids(LibraryOrder.arrange(all, Sort.CUSTOM, saved, 1)))
    }

    @Test fun alphabeticalIgnoresCaseAndAccentsAndKeepsPinned() =
        assertEquals(listOf("liked", "a", "b", "e", "spotify:user:x:folder:1"), ids(LibraryOrder.arrange(all, Sort.ALPHABETICAL, emptyList(), 1)))

    @Test fun creatorPutsFoldersFirstThenOwnersAndArtists() =
        assertEquals(listOf("liked", "spotify:user:x:folder:1", "e", "a", "b"), ids(LibraryOrder.arrange(all, Sort.CREATOR, emptyList(), 1)))

    @Test fun folderKeyIsItsUri() = assertEquals("spotify:user:x:folder:1", LibraryOrder.key(f))

    @Test
    fun followedArtistsFollowTheSortByName() {
        val artists = listOf(ArtistsModel("Zaz", ""), ArtistsModel("ABBA", ""), ArtistsModel("éric", ""))
        assertEquals(listOf("ABBA", "éric", "Zaz"), LibraryOrder.arrangeArtists(artists, LibraryOrder.Sort.ALPHABETICAL).map { it.name })
        assertEquals(listOf("ABBA", "éric", "Zaz"), LibraryOrder.arrangeArtists(artists, LibraryOrder.Sort.CREATOR).map { it.name })
        // Spotify's order (the latest followed first) for the other sorts.
        assertEquals(artists, LibraryOrder.arrangeArtists(artists, LibraryOrder.Sort.RECENTS))
        assertEquals(artists, LibraryOrder.arrangeArtists(artists, LibraryOrder.Sort.CUSTOM))
        // Spotify's order alone, the dates unknown.
        assertEquals(artists, LibraryOrder.arrangeArtists(artists, LibraryOrder.Sort.RECENTLY_ADDED))
    }

    @Test
    fun followedArtistsFollowTheSortByDate() {
        val artists = listOf(
            ArtistsModel("Old", "", "o", "2020-01-01T00:00:00Z"),
            ArtistsModel("Undated", "", "u"),
            ArtistsModel("New", "", "n", "2026-11-01T00:00:00Z"),
        )
        assertEquals(listOf("New", "Old", "Undated"), LibraryOrder.arrangeArtists(artists, LibraryOrder.Sort.RECENTLY_ADDED).map { it.name })
    }
}
