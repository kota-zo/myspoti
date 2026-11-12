package com.music.spotui.ui.screens

import com.music.spotui.data.entity.LibraryEntry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryRemovableTest {
    private fun entry(uri: String, owner: String = "", folder: String = "") =
        LibraryEntry(spotifyId = uri.substringAfterLast(':'), name = "x", subtitle = "", coverUri = "", isPlaylist = uri.contains(":playlist:"),
            uri = uri, ownerUri = owner, folderUri = folder)

    @Test fun someoneElsesPlaylistAndSavedItemsCanBeRemoved() {
        assertTrue(removable(entry("spotify:playlist:a", owner = "spotify:user:other"), me = "me"))
        assertTrue(removable(entry("spotify:album:a"), me = "me"))
        assertTrue(removable(entry("spotify:artist:a"), me = null))
        assertTrue(removable(entry("spotify:show:a"), me = null))
    }

    @Test fun ownPlaylistsUnknownOwnersFoldersAndTheAppsRowsCannot() {
        assertFalse(removable(entry("spotify:playlist:a", owner = "spotify:user:me"), me = "me"))
        assertFalse(removable(entry("spotify:playlist:a"), me = "me"))
        assertFalse(removable(entry("spotify:playlist:a", owner = "spotify:user:other"), me = null))
        assertFalse(removable(entry("spotify:user:me:folder:f", folder = "spotify:user:me:folder:f"), me = "me"))
        assertFalse(removable(entry(""), me = "me"))
    }
}
