package com.music.spotui.data.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** How catalogue titles and artists are compared with ours: accents and the order of an artist's words do not matter. */
class LyricsMatchTest {
    @Test fun accentsAndCaseAreFoldedAway() {
        assertEquals("beyonce", LyricsMatch.key("Beyoncé"))
        assertEquals("rosalia", LyricsMatch.key("ROSALÍA"))
        assertEquals("strasse", LyricsMatch.key("Straße"))
        assertEquals("djordje", LyricsMatch.key("Đorđe"))
    }

    @Test fun kanaAndHangulKeepTheirLength() {
        // Voiced kana and Hangul syllables are not "accents": they are not decomposed away.
        assertEquals("がっこう", LyricsMatch.key("がっこう"))
        assertTrue(LyricsMatch.key("가나다").length == 3)
        assertFalse(LyricsMatch.key("か") == LyricsMatch.key("が"))
    }

    @Test fun sameArtistIgnoresAccentsAndWordOrder() {
        assertTrue(LyricsMatch.sameArtist("Beyoncé", "Beyonce"))
        assertTrue(LyricsMatch.sameArtist("Lee Hi", "Hi Lee"))
        assertTrue(LyricsMatch.sameArtist("Jay-Z", "Jay Z"))
        assertTrue(LyricsMatch.sameArtist("AC/DC", "ACDC"))
    }

    @Test fun differentArtistsStayDifferent() {
        assertFalse(LyricsMatch.sameArtist("Cher", "Cherry"))
        assertFalse(LyricsMatch.sameArtist("Lee Hi", "Lee"))
        assertFalse(LyricsMatch.sameArtist("", ""))
    }

    @Test fun plausibleMatchSeesThroughAccentsAndArtistOrderButKeepsTheLengthLimit() {
        assertTrue(LyricsApi.isPlausibleMatch("Rosalía", "Rosalía", 200, "Rosalia", "Rosalia", 200))
        assertTrue(LyricsApi.isPlausibleMatch("Hello", "Hi Lee", 200, "Hello", "Lee Hi", 201))
        assertFalse(LyricsApi.isPlausibleMatch("Hello", "Hi Lee", 205, "Hello", "Lee Hi", 200))
    }

    @Test fun sameSongForTheWordTimedSources() {
        assertTrue(LyricsApi.sameSong("Déjà Vu", "Beyoncé, Jay-Z", 240_000, "Deja Vu", "Beyonce", 241_000))
        assertTrue(LyricsApi.sameSong("Thunderstruck", "AC/DC", 292_000, "Thunderstruck", "AC/DC", 292_000))
        assertFalse(LyricsApi.sameSong("Déjà Vu", "Beyoncé", 240_000, "Deja Vu", "Beyonce", 250_000))
    }
}
