package com.music.spotui.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsSearchTest {
    @Test fun wordsMatchWithoutCaseOrAccents() {
        assertTrue(SettingsSearch.matches("ecran", "Garder l'écran allumé", ""))
        assertTrue(SettingsSearch.matches("image  IMAGE", "Image dans l'image", "Quitter l'app"))
        assertTrue(SettingsSearch.matches("pip window", "Picture-in-picture", "keeps it in a small window, pip"))
        assertFalse(SettingsSearch.matches("lyrics video", "Music videos", "Clips in the player"))
        assertFalse(SettingsSearch.matches("   ", "Anything", ""))
    }

    @Test fun titlesComeFirstAndOnce() {
        val text = mapOf(1 to "Lyrics", 2 to "Shows the words", 3 to "Video", 4 to "No lyrics while a video plays", 5 to "Lyrics")
        val entries = listOf(SettingsEntry(3, 4, "Display"), SettingsEntry(1, 2, "Lyrics"), SettingsEntry(5, 0, "Root"))
        val hits = SettingsSearch.search(entries, "lyrics") { text.getValue(it) }
        assertEquals(listOf(1, 3), hits.map { it.title })
    }

    @Test fun everyPlaceIsReachable() {
        val pages = setOf("Root", "Playback", "Sources", "Lyrics", "StreamingWifi", "StreamingCell", "Crossfade", "Downloads", "DownloadQuality",
            "Storage", "Data", "Display", "Look", "Rotation", "Language", "LyricsLanguage", "App", "Accounts", "Library")
        assertTrue(SettingsSearch.immersive.all { it.place in pages })
        assertTrue(SettingsSearch.classic.all { it.place == "transitions" || it.place.toIntOrNull() in 0..7 })
    }

    /** The listen sync and the passing to another device sit with the music account, the library rows on their own. */
    @Test fun rowsSitWithWhatTheyAreAbout() {
        fun place(list: List<SettingsEntry>, title: Int) = list.first { it.title == title }.place
        assertEquals("Accounts", place(SettingsSearch.immersive, app.myspoti.engine.R.string.settings_connect_mirror_title))
        assertEquals("Accounts", place(SettingsSearch.immersive, app.myspoti.engine.R.string.pass_send))
        assertEquals("Library", place(SettingsSearch.immersive, app.myspoti.engine.R.string.hidden_songs_title))
        assertEquals("6", place(SettingsSearch.classic, app.myspoti.engine.R.string.settings_connect_mirror_title))
        assertEquals("6", place(SettingsSearch.classic, app.myspoti.engine.R.string.pass_receive))
        assertEquals("7", place(SettingsSearch.classic, app.myspoti.engine.R.string.live_radio_settings_title))
    }
}
