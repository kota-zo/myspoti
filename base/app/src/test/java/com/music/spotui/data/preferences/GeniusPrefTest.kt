/* SPDX-License-Identifier: GPL-3.0-only */
package com.music.spotui.data.preferences

import app.myspoti.engine.R
import com.music.spotui.data.api.GeniusMeaning
import com.music.spotui.data.api.LyricsApi
import com.music.spotui.di.Graph
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The Genius switch of the settings: on by default; off, Genius is neither asked nor shown. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class GeniusPrefTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val track = LyricsApi.Track("0000000000000000000000", "Song", "Artist", "Album", 200_000L)
    private val meaning = GeniusMeaning.Meaning("https://example.com/song", "Song", "Artist", "What it is about", emptyList())

    @Before fun setUp() { Graph::class.java.getDeclaredField("app").apply { isAccessible = true }.set(null, app) }
    @After fun tearDown() { GeniusPref.set(app, true); GeniusMeaning.current.value = null }

    @Test fun `on by default, Genius among the lyrics sources`() {
        assertTrue(app.getSharedPreferences("settings_prefs", 0).getBoolean("genius", true))
        GeniusPref.set(app, true)
        assertTrue(LyricsApi.sources(track).any { it.id == "genius" })
        GeniusMeaning.current.value = track.trackId to meaning
        assertEquals(meaning, GeniusMeaning.currentFor(track.trackId))
    }

    @Test fun `off, Genius is asked nothing and shows nothing`() {
        GeniusMeaning.current.value = track.trackId to meaning
        GeniusPref.set(app, false)
        assertFalse(app.getSharedPreferences("settings_prefs", 0).getBoolean("genius", true))
        // What was read before is dropped, and nothing of it is handed to the lyrics.
        assertNull(GeniusMeaning.current.value)
        GeniusMeaning.current.value = track.trackId to meaning
        assertNull(GeniusMeaning.currentFor(track.trackId))
        GeniusMeaning.current.value = null
        // No look-up: find answers at once (it would try genius.com, and throw offline, if it asked).
        assertNull(runBlocking { GeniusMeaning.find("Another Song", "Another Artist") })
        GeniusMeaning.prefetch("1111111111111111111111", "Another Song", "Another Artist", "en")
        Thread.sleep(200)
        assertNull(GeniusMeaning.current.value)
        // Not a lyrics source to pick or to fall back on.
        assertFalse(LyricsApi.sources(track).any { it.id == "genius" })
    }

    /** Found by Settings' search in both looks, by its name or by the annotations it shows (reported: not found). */
    @Test @Config(qualifiers = "fr") fun `found by the settings search`() = found()
    @Test fun `found by the settings search, in English`() = found()

    private fun found() {
        val search = com.music.spotui.ui.screens.SettingsSearch
        for (list in listOf(search.immersive, search.classic)) for (q in listOf("genius", "annotation")) {
            val hits = search.search(list, q) { app.getString(it) }
            assertTrue("$q", hits.any { it.title == R.string.settings_genius_title })
        }
    }
}
