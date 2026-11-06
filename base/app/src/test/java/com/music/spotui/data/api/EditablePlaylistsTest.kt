/* SPDX-License-Identifier: GPL-3.0-only */
package com.music.spotui.data.api

import com.music.spotui.data.entity.LibraryEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditablePlaylistsTest {
    @Test fun aKeptListComesBackAsItWas() {
        val list = listOf(
            EditablePlaylist("a", "Road \"trip\"", 12, "https://i/a"),
            EditablePlaylist("spotify:user:u:folder:f", "Folder", 3, null, folderUri = "spotify:user:u:folder:f"),
            EditablePlaylist("b", "No cover", 0, null),
        )
        assertEquals(list, EditablePlaylists.decodeList(EditablePlaylists.encodeList(list)))
    }

    @Test fun anUnreadableListIsNoList() {
        assertNull(EditablePlaylists.decodeList("not json"))
    }

    private fun entry(uri: String) = LibraryEntry(spotifyId = uri.substringAfterLast(':'), name = "", subtitle = "", coverUri = "", isPlaylist = true, uri = uri)

    @Test fun aListedPlaylistSpotifyDoesNotCountIsAnOldList() {
        val p = "spotify:playlist:p"
        assertTrue(LibraryEdits.listedButNotSaved(p, emptySet(), listOf(entry(p))))
        assertFalse(LibraryEdits.listedButNotSaved(p, setOf(p), listOf(entry(p))))
        assertFalse(LibraryEdits.listedButNotSaved(p, emptySet(), listOf(entry("spotify:playlist:q"))))
    }
}
