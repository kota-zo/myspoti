package com.music.spotui.data.api

import com.music.spotui.data.entity.LyricLine
import com.music.spotui.data.entity.LyricWord
import com.music.spotui.data.entity.Lyrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** Translations, romanizations, backing vocals and singers: attached by time, carried between sources, chosen for a target language. */
class LyricsExtrasTest {
    private fun line(t: Long, text: String, tr: String? = null, ro: String? = null) = LyricLine(t, text, translation = tr, romanization = ro)

    @Test fun neteaseStyleLinesGetTheirNearestTranslationAndRomanization() {
        val lines = listOf(line(1000, "夢ならば"), line(6000, "♪"), line(12000, "今でも"))
        val out = LyricsExtras.attach(lines,
            listOf(line(1000, "如果是梦"), line(12100, "至今仍"), line(30000, "far away")),
            listOf(line(1050, "yume naraba"), line(12000, "//")))
        assertEquals("如果是梦", out[0].translation)
        assertEquals("yume naraba", out[0].romanization)
        assertNull(out[1].translation)
        assertEquals("至今仍", out[2].translation)
        assertNull("// means no romanization", out[2].romanization)
    }

    @Test fun nothingToAttachReturnsTheLinesAsTheyAre() {
        val lines = listOf(line(1, "a"))
        assertSame(lines, LyricsExtras.attach(lines, emptyList(), emptyList()))
    }

    @Test fun anotherSourcesTranslationIsCarriedByLineText() {
        val to = Lyrics(listOf(line(0, "Hello, world"), line(5, "Bye")), synced = true, provider = "LRCLIB")
        val from = Lyrics(listOf(line(1, "Hello world", tr = "Bonjour le monde"), line(9, "Other")), synced = true, provider = "NetEase", translationLanguage = "fr")
        val out = LyricsExtras.carry(to, from)
        assertEquals("Bonjour le monde", out.lines[0].translation)
        assertNull(out.lines[1].translation)
        assertEquals("fr", out.translationLanguage)
        assertEquals("LRCLIB", out.provider)
    }

    @Test fun carryKeepsAnExistingTranslationAndIgnoresAMismatch() {
        val own = Lyrics(listOf(line(0, "a line", tr = "une ligne")), synced = true, translationLanguage = "fr")
        val other = Lyrics(listOf(line(0, "a line", tr = "eine Zeile")), synced = true, translationLanguage = "de")
        assertSame(own, LyricsExtras.carry(own, other))
        val bare = Lyrics(listOf(line(0, "one"), line(1, "two"), line(2, "three")), synced = true)
        assertSame(bare, LyricsExtras.carry(bare, Lyrics(listOf(line(0, "unrelated", tr = "x")), synced = true, translationLanguage = "fr")))
        assertSame(bare, LyricsExtras.carry(bare, null))
    }

    @Test fun sourceTranslationsAreUsedOnlyInTheTargetLanguage() {
        val l = Lyrics(listOf(line(0, "a", tr = "A"), line(1, "b")), synced = true, language = "ja", translationLanguage = "en-US")
        assertEquals(listOf("A", null), l.translationsFor("en"))
        assertNull(l.translationsFor("fr"))
        // The song already is in that language, or the translation's language is not known: the machine decides.
        assertNull(l.copy(language = "en").translationsFor("en"))
        assertNull(l.copy(translationLanguage = null).translationsFor("en"))
        assertNull(Lyrics(listOf(line(0, "a")), synced = true, translationLanguage = "en").translationsFor("en"))
    }

    @Test fun duetNeedsTwoDifferentSingers() {
        fun v(vararg voices: Int?) = Lyrics(voices.mapIndexed { i, v -> LyricLine(i.toLong(), "x", voice = v) }, synced = true)
        assertEquals(false, v(1, 1, null).hasDuet)
        assertEquals(true, v(1, 2, 1).hasDuet)
        assertEquals(false, v(0, 1).hasDuet)
    }

    @Test fun backingVocalsAreCarriedOntoTheSameLinesMovedByTheGapBetweenTheSources() {
        val to = Lyrics(listOf(
            LyricLine(1000, "Stay (stay)", words = listOf(LyricWord(1000, 0, "Stay "), LyricWord(1400, 0, "(stay)"))),
            LyricLine(5000, "Go away"), LyricLine(9000, "Come back"),
        ), synced = true, provider = "Spotify")
        val from = Lyrics(listOf(
            LyricLine(1200, "Stay", background = "stay", backgroundWords = listOf(LyricWord(1500, 1800, "stay"))),
            LyricLine(5200, "Go away"), LyricLine(9200, "Come back"),
        ), synced = true, provider = "LyricsPlus")
        val out = LyricsExtras.carry(to, from)
        assertEquals("Spotify", out.provider)
        assertEquals("Stay", out.lines[0].text)
        assertEquals(listOf("Stay "), out.lines[0].words!!.map { it.text })
        assertEquals("stay", out.lines[0].background)
        assertEquals(listOf(LyricWord(1300, 1600, "stay")), out.lines[0].backgroundWords)
        assertNull(out.lines[1].background)
    }

    @Test fun bracketsSayingSomethingElseAndTooFewMatchesLeaveTheLinesAlone() {
        val to = Lyrics(listOf(LyricLine(0, "Hello (intro)"), LyricLine(1, "World")), synced = true)
        val from = Lyrics(listOf(LyricLine(0, "Hello", background = "ooh"), LyricLine(1, "World")), synced = true)
        assertEquals("Hello (intro)", LyricsExtras.carry(to, from).lines[0].text)
        assertNull(LyricsExtras.carry(to, from).lines[0].background)
        val unrelated = Lyrics(listOf(LyricLine(0, "one"), LyricLine(1, "two"), LyricLine(2, "three"), LyricLine(3, "four")), synced = true)
        assertSame(unrelated, LyricsExtras.carry(unrelated, Lyrics(listOf(LyricLine(0, "one", background = "oh")), synced = true)))
    }

    @Test fun theSingersAreCarriedFromADuet() {
        val to = Lyrics(listOf(LyricLine(0, "mine"), LyricLine(1, "yours")), synced = true)
        val from = Lyrics(listOf(LyricLine(0, "mine", voice = 1), LyricLine(1, "yours", voice = 2)), synced = true)
        assertEquals(listOf(1, 2), LyricsExtras.carry(to, from).lines.map { it.voice })
    }

    @Test fun onlyTheWordsSungOutsideTheBracketsAreKept() {
        fun w(vararg t: String) = t.mapIndexed { i, s -> LyricWord(i.toLong(), 0, s) }
        assertEquals(listOf("Oh ", "baby"), LyricsExtras.outsideBrackets(w("Oh ", "(yeah ", "yeah) ", "baby")).map { it.text })
        assertEquals(listOf("Go ", "on"), LyricsExtras.outsideBrackets(w("Go ", "(go)", "on")).map { it.text })
        assertEquals(listOf("Run"), LyricsExtras.outsideBrackets(w("Run(run)")).map { it.text })
    }
}
