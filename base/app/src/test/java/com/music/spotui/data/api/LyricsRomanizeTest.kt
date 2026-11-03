package com.music.spotui.data.api

import com.music.spotui.data.entity.LyricLine
import com.music.spotui.data.entity.Lyrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** Romanizations from an extension: asked for the lines left without one, never over a source's own. */
class LyricsRomanizeTest {
    private fun line(t: Long, text: String, ro: String? = null) = LyricLine(t, text, romanization = ro)

    private val lyrics = Lyrics(
        listOf(line(0, "Intro"), line(1000, "夢ならば"), line(2000, "♪"), line(3000, "今でも", ro = "ima demo"), line(4000, "사랑해")),
        synced = true, language = "ja",
    )

    @Test fun onlyTheLinesInAnotherScriptWithoutARomanizationAreAsked() {
        assertEquals(mapOf(1 to "夢ならば", 4 to "사랑해"), LyricsRomanize.wanted(lyrics))
    }

    @Test fun theAnswersFillTheLinesLeftEmpty() {
        val out = LyricsRomanize.apply(lyrics, mapOf(1 to "yume naraba", 4 to "saranghae"))
        assertEquals(listOf(null, "yume naraba", null, "ima demo", "saranghae"), out.lines.map { it.romanization })
        assertEquals(lyrics.lines.map { it.text }, out.lines.map { it.text })
        assertEquals("ja", out.language)
    }

    @Test fun aSourcesOwnRomanizationIsNeverReplaced() {
        val out = LyricsRomanize.apply(lyrics, mapOf(3 to "IMA DEMO"))
        assertSame(lyrics, out)
    }

    @Test fun aRomanizationThatRepeatsItsLineOrAnUnknownIndexIsLeftOut() {
        assertSame(lyrics, LyricsRomanize.apply(lyrics, mapOf(4 to "사랑해", 9 to "nothing here", -1 to "x")))
    }

    @Test fun aLineInLatinLettersGetsNoRomanization() {
        val out = LyricsRomanize.apply(lyrics, mapOf(0 to "something else", 1 to "yume naraba"))
        assertNull(out.lines[0].romanization)
        assertEquals("yume naraba", out.lines[1].romanization)
    }

    @Test fun nothingFoundKeepsTheSameLyrics() {
        assertSame(lyrics, LyricsRomanize.apply(lyrics, emptyMap()))
    }
}
