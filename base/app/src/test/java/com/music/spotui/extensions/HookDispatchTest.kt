package com.music.spotui.extensions

import app.myspoti.extensions.LyricsResult
import com.music.spotui.data.entity.LyricLine
import com.music.spotui.data.entity.LyricWord
import com.music.spotui.data.entity.Lyrics
import com.music.spotui.data.entity.LyricsColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The order hooks are asked in, and how an extension's lyrics meet the app's own. */
class HookDispatchTest {
    @Test fun theFirstInstalledAnswerWinsEvenWhenALaterOneIsQuicker() = runBlocking {
        val r = HookDispatch.firstInOrder(listOf("a", "b", "c")) { c ->
            when (c) { "a" -> { delay(200); "from a" }; "b" -> "from b"; else -> "from c" }
        }
        assertEquals("a" to "from a", r)
    }

    @Test fun noAnswerOrAFailureGivesTheTurnToTheNext() = runBlocking {
        val r = HookDispatch.firstInOrder(listOf("a", "b", "c")) { c ->
            when (c) { "a" -> null; "b" -> throw IllegalStateException("boom"); else -> "from c" }
        }
        assertEquals("c" to "from c", r)
        assertNull(HookDispatch.firstInOrder(listOf("a")) { null })
        assertNull(HookDispatch.firstInOrder(emptyList<String>()) { "x" })
    }

    @Test fun aRoundTheUseRefusesGivesTheTurnToTheOthers() = runBlocking {
        val rounds = mutableListOf<List<String>>()
        val r = HookDispatch.inRounds(listOf("a", "b", "c"), ask = { c -> when (c) { "a" -> "from a"; "b" -> { delay(200); "from b" }; else -> null } },
            fit = { 1 }, bestFit = 1, graceMs = { 1_000 }) { round ->
            rounds += round.map { it.first }
            round.firstOrNull { it.first == "b" }?.second
        }
        assertEquals("from b", r)
        assertEquals(listOf(listOf("a"), listOf("b")), rounds)
    }

    @Test fun theBestFitGoesFirstThenTheOrder() = runBlocking {
        val r = HookDispatch.inRounds(listOf("a", "b", "c"), ask = { c -> c }, fit = { if (it == "c") 3 else 1 },
            bestFit = 3, graceMs = { 1_000 }) { round -> round.map { it.first } }
        assertEquals(listOf("c", "a", "b"), r)
    }

    @Test fun aSlowCandidateIsWaitedForOnlyItsGraceThenComesNext() = runBlocking {
        val rounds = mutableListOf<List<String>>()
        val started = System.currentTimeMillis()
        val r = HookDispatch.inRounds(listOf("slow", "quick"), ask = { c -> if (c == "slow") { delay(1_500); "slow" } else "quick" },
            fit = { 1 }, bestFit = 2, graceMs = { 300 }) { round ->
            rounds += round.map { it.first }
            round.firstOrNull { it.first == "slow" }?.second
        }
        assertEquals("slow", r)
        assertEquals(listOf(listOf("quick"), listOf("slow")), rounds)
        assertTrue(System.currentTimeMillis() - started < 3_000)
    }

    @Test fun theBestFitStartsWithoutWaitingTheGraceOnceThoseAboveAnswered() = runBlocking {
        val started = System.currentTimeMillis()
        val r = HookDispatch.inRounds(listOf("a", "b", "late"), ask = { c -> if (c == "late") { delay(5_000); c } else c },
            fit = { if (it == "b") 3 else 1 }, bestFit = 3, graceMs = { 4_000 }) { round -> round.first().first }
        assertEquals("b", r)
        assertTrue(System.currentTimeMillis() - started < 2_000)
    }

    @Test fun noAnswerAtAllIsNullWithoutARound() = runBlocking {
        var used = false
        assertNull(HookDispatch.inRounds(listOf("a", "b"), ask = { null as String? }, fit = { 1 }, bestFit = 1, graceMs = { 100 }) { used = true; "x" })
        assertTrue(!used)
    }

    @Test fun theOthersAreNotWaitedForOnceTheFirstAnswers() = runBlocking {
        val started = System.currentTimeMillis()
        val r = HookDispatch.firstInOrder(listOf("a", "b")) { c -> if (c == "a") "quick" else { delay(5_000); "slow" } }
        assertEquals("quick", r?.second)
        assertTrue(System.currentTimeMillis() - started < 2_000)
    }

    @Test fun theBestRankWinsAndTiesGoToTheFirstInstalled() = runBlocking {
        val rank = { s: String -> s.length }
        assertEquals("b" to "long", HookDispatch.bestInOrder(listOf("a", "b", "c"), rank) { c -> mapOf("a" to "ab", "b" to "long", "c" to "abc")[c] })
        assertEquals("a" to "xy", HookDispatch.bestInOrder(listOf("a", "b"), rank) { c -> if (c == "a") "xy" else "zw" })
        assertEquals("b" to "zw", HookDispatch.bestInOrder(listOf("a", "b"), rank) { c -> if (c == "a") throw RuntimeException() else "zw" })
    }

    // ── Lyrics ──

    private fun lyrics(synced: Boolean, words: Boolean, provider: String, colors: LyricsColors? = null, uncapped: Boolean = true) = Lyrics(
        lines = listOf(LyricLine(if (synced) 1000 else 0, "line", if (words) listOf(LyricWord(1000, 1200, "line")) else null)),
        synced = synced, provider = provider, colors = colors, uncapped = uncapped,
    )

    private val colors = LyricsColors(1, 2, 3)

    @Test fun anExtensionFillsInWhenTheAppHasNothing() {
        val ext = lyrics(synced = true, words = false, provider = "Ext")
        assertSame(ext, LyricsMerge.choose(null, ext))
        val own = lyrics(synced = true, words = false, provider = "LRCLIB")
        assertSame(own, LyricsMerge.choose(own, null))
    }

    @Test fun betterLyricsWinWhoeverGivesThem() {
        val plain = lyrics(synced = false, words = false, provider = "Genius")
        val line = lyrics(synced = true, words = false, provider = "Ext")
        assertEquals("Ext", LyricsMerge.choose(plain, line)?.provider)
        val words = lyrics(synced = true, words = true, provider = "LyricsPlus")
        assertEquals("LyricsPlus", LyricsMerge.choose(words, line)?.provider)
    }

    @Test fun atTheSameLevelSpotifysOwnStayFirstAndOtherSourcesGiveWay() {
        val spotify = lyrics(synced = true, words = false, provider = "Musixmatch", colors = colors)
        val ext = lyrics(synced = true, words = false, provider = "Ext")
        assertEquals("Musixmatch", LyricsMerge.choose(spotify, ext)?.provider)
        val lrclib = lyrics(synced = true, words = false, provider = "LRCLIB")
        assertEquals("Ext", LyricsMerge.choose(lrclib, ext)?.provider)
        // Spotify's preview only (Free cap): the extension's full lyrics, with Spotify's colours.
        val preview = lyrics(synced = true, words = false, provider = "Musixmatch", colors = colors, uncapped = false)
        val chosen = LyricsMerge.choose(preview, ext)
        assertEquals("Ext", chosen?.provider)
        assertEquals(colors, chosen?.colors)
    }

    @Test fun anExtensionsAnswerBecomesTheAppsLyrics() {
        val r = LyricsResult.parse("""{"provider":"P","lines":[{"time":1500,"text":"a","words":[{"time":1500,"end":1700,"text":"a"}]},{"time":"2500","text":"b"}]}""", "Ext")!!
        val l = LyricsMerge.toLyrics(r)
        assertTrue(l.synced)
        assertEquals("P", l.provider)
        assertEquals(listOf(1500L, 2500L), l.lines.map { it.timeMs })
        assertEquals(1700L, l.lines[0].words!![0].endMs)
        assertEquals(3, LyricsMerge.level(l))
        val plain = LyricsMerge.toLyrics(LyricsResult.parse("""{"plain":"one\ntwo"}""", "Ext")!!)
        assertEquals("Ext", plain.provider)
        assertEquals(1, LyricsMerge.level(plain))
        assertEquals(listOf("one", "two"), plain.lines.map { it.text })
    }

    @Test fun anExtensionsTranslationAndVoicesReachTheAppsLyrics() {
        val r = LyricsResult.parse("""{"translationLanguage":"en","lines":[{"time":1000,"text":"a","translation":"A","romanization":"ei","voice":2,"background":"oh"}]}""", "Ext")!!
        val l = LyricsMerge.toLyrics(r)
        assertEquals("en", l.translationLanguage)
        val line = l.lines.single()
        assertEquals("A", line.translation)
        assertEquals("ei", line.romanization)
        assertEquals(2, line.voice)
        assertEquals("oh", line.background)
    }

    @Test fun theChosenLyricsKeepTheTranslationOfTheOneThatLost() {
        val translated = Lyrics(listOf(LyricLine(1000, "line", translation = "ligne")), synced = true, provider = "NetEase", translationLanguage = "fr")
        val own = Lyrics(listOf(LyricLine(1000, "line", listOf(LyricWord(1000, 1200, "line")))), synced = true, provider = "LyricsPlus")
        val chosen = LyricsMerge.choose(own, translated)!!
        assertEquals("LyricsPlus", chosen.provider)
        assertEquals("ligne", chosen.lines[0].translation)
        assertEquals("fr", chosen.translationLanguage)
    }

    @Test fun atTheSameLevelBackingVocalsOrADuetWinTheTie() {
        fun lines(vararg t: String) = t.mapIndexed { i, s -> LyricLine(i * 1000L, s) }
        val own = Lyrics(lines("a", "b", "c"), synced = true, provider = "LRCLIB")
        val backs = Lyrics(lines("x", "y", "z").mapIndexed { i, l -> if (i == 0) l.copy(background = "ooh") else l }, synced = true, provider = "Ext")
        assertEquals("Ext", LyricsMerge.choose(own, backs)?.provider)
        // Spotify's complete lyrics stay first, unless the other one has what they lack.
        val spotify = own.copy(provider = "Spotify", colors = LyricsColors(0, 0, 0))
        assertEquals("Ext", LyricsMerge.choose(spotify, backs)?.provider)
        assertEquals("Spotify", LyricsMerge.choose(spotify, backs.copy(lines = lines("x", "y", "z")))?.provider)
        val duet = own.copy(lines = own.lines.mapIndexed { i, l -> l.copy(voice = i % 2 + 1) })
        assertEquals("LRCLIB", LyricsMerge.choose(duet, backs.copy(lines = lines("x", "y", "z")))?.provider)
    }

    @Test fun withinTakesTheLowerAnswerWhenTheOneAboveIsSlow() = runBlocking {
        val t0 = System.currentTimeMillis()
        val r = HookDispatch.firstInOrderWithin(listOf("slow", "quick"), graceMs = 100) { c ->
            if (c == "slow") { delay(5_000); "a" } else { delay(10); "b" }
        }
        assertEquals("quick" to "b", r)
        assert(System.currentTimeMillis() - t0 < 2_000)
    }

    @Test fun withinKeepsTheOrderWhenTheOneAboveAnswersInTime() = runBlocking {
        val r = HookDispatch.firstInOrderWithin(listOf("first", "second"), graceMs = 1_000) { c ->
            if (c == "first") { delay(80); "a" } else "b"
        }
        assertEquals("first" to "a", r)
    }

    @Test fun withinSkipsAFailureAboveAtOnce() = runBlocking {
        val r = HookDispatch.firstInOrderWithin(listOf("none", "fails", "ok"), graceMs = 5_000) { c ->
            when (c) { "none" -> null; "fails" -> error("boom"); else -> "c" }
        }
        assertEquals("ok" to "c", r)
    }

    @Test fun withinGivesNullWhenNobodyAnswers() = runBlocking {
        assertNull(HookDispatch.firstInOrderWithin(listOf(1, 2), graceMs = 10) { null })
    }
}
