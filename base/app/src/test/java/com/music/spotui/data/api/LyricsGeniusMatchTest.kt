package com.music.spotui.data.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Genius's search hits against the playing track: what Genius adds in brackets and co-artists do not count. */
class LyricsGeniusMatchTest {
    @Test fun featuredArtistsInBracketsAreLeftOut() {
        assertTrue(LyricsApi.geniusMatch("LOYALTY.", "Kendrick Lamar (Ft. Rihanna)", "Kendrick Lamar", "LOYALTY.", "Kendrick Lamar"))
        assertTrue(LyricsApi.geniusMatch("Despacito (Remix)", "Luis Fonsi & Daddy Yankee (Ft. Justin Bieber)", "Luis Fonsi", "Despacito (Remix)", "Luis Fonsi"))
    }

    @Test fun coArtistsAndNativeNamesMatchTheFirstArtist() {
        assertTrue(LyricsApi.geniusMatch("Ma Meilleure Ennemie", "Stromae & Pomme", "Stromae", "Ma Meilleure Ennemie", "Stromae"))
        assertTrue(LyricsApi.geniusMatch("Blueming", "IU (아이유)", "IU (아이유)", "Blueming", "IU"))
        assertTrue(LyricsApi.geniusMatch("CARNIVAL", "¥\$, Kanye West & Ty Dolla \$ign (Ft. Rich The Kid & Playboi Carti)", "¥\$", "CARNIVAL", "Kanye West"))
    }

    @Test fun theirPrimaryArtistIsEnough() {
        assertTrue(LyricsApi.geniusMatch("Song", "Someone Else", "Our Artist", "Song", "Our Artist"))
    }

    @Test fun aRomanizedTitleInBracketsIsLeftOut() {
        assertTrue(LyricsApi.geniusMatch("夜に駆ける (Yoru Ni Kakeru)", "YOASOBI", "YOASOBI", "夜に駆ける", "YOASOBI"))
    }

    @Test fun anotherSongOrArtistIsStillRejected() {
        assertFalse("Hello and Hello Again are distinct songs", LyricsApi.geniusMatch("Hello Again", "Adele", "Adele", "Hello", "Adele"))
        assertFalse(LyricsApi.geniusMatch("Hello", "Lionel Richie", "Lionel Richie", "Hello", "Adele"))
        assertFalse("Cher and Cherry are distinct artists", LyricsApi.geniusMatch("Hello", "Cherry (Ft. Cher)", "Cherry", "Hello", "Cher"))
        assertFalse(LyricsApi.geniusMatch("Skyfall", "Adele", "Adele", "Hello", "Adele"))
    }

    @Test fun theHeaderAboveTheLyricsIsLeftOut() {
        val part = """<div data-exclude-from-selection="true" class="h"><div>354 Contributors</div><div>Translations</div></div>[Verse 1]<br/>First line"""
        assertEquals("[Verse 1]<br/>First line", LyricsApi.withoutGeniusHeader(part))
        assertEquals("plain", LyricsApi.withoutGeniusHeader("plain"))
    }
}
