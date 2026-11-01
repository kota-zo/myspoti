package com.music.spotui.data.api

import com.music.spotui.data.entity.Lyrics
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.xmlpull.v1.XmlPullParser

/** What the sources give besides the words: translations, romanizations, who sings, backing vocals. */
class LyricsSourcesParseTest {
    // A real pull parser needs kxml2 (android.jar only has stubs): without it these tests are skipped.
    private fun ttml(xml: String): Lyrics? {
        val parser = runCatching { Class.forName("org.kxml2.io.KXmlParser").getDeclaredConstructor().newInstance() as XmlPullParser }.getOrNull()
        assumeTrue("kxml2 is not on the test classpath", parser != null)
        return LyricsApi.parseTtml(xml) { parser!! }
    }

    private val head = """<tt xmlns="http://www.w3.org/ns/ttml" xmlns:lrc="http://lrc.red/lyric-ttml-internal" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">"""

    @Test fun headTranslationAndTransliterationAreAttachedByLineKey() {
        val l = ttml("""$head<head><metadata><ttm:agent type="person" xml:id="v1"/>
            <sourceMetadata><translations><translation xml:lang="en-US"><text for="L1">If it were a dream</text><text for="L2">Still now</text></translation></translations>
            <transliterations><transliteration xml:lang="ja-Latn"><text for="L1"><span begin="0.0" end="1.0">yume</span> <span begin="1.0" end="2.0">naraba</span><span ttm:role="x-bg"><span begin="2.0" end="3.0">(ah)</span></span></text></transliteration></transliterations>
            </sourceMetadata></metadata></head>
            <body><div><p begin="0:01.000" end="0:03.000" lrc:key="L1" ttm:agent="v1"><span begin="0:01.000" end="0:02.000">夢</span><span begin="0:02.000" end="0:03.000">ならば</span></p>
            <p begin="0:04.000" end="0:06.000" lrc:key="L2" ttm:agent="v2"><span begin="0:04.000" end="0:06.000">今でも</span></p></div></body></tt>""")!!
        assertEquals(2, l.lines.size)
        assertEquals("夢ならば", l.lines[0].text)
        assertEquals("If it were a dream", l.lines[0].translation)
        assertEquals("yume naraba", l.lines[0].romanization)
        assertEquals("Still now", l.lines[1].translation)
        assertNull(l.lines[1].romanization)
        assertEquals("en-US", l.translationLanguage)
        assertEquals(listOf(1, 2), l.lines.map { it.voice })
        assertTrue(l.hasDuet)
        assertEquals(1000L, l.lines[0].timeMs)
    }

    @Test fun backingVocalsAreKeptApartFromTheLine() {
        val l = ttml("""$head<body><div><p begin="1.000" end="4.000" ttm:agent="v1"><span begin="1.000" end="2.000">I'm</span> <span begin="2.000" end="3.000">ready</span> <span ttm:role="x-bg"><span begin="3.000" end="3.500">(Woo-</span><span begin="3.500" end="4.000">hoo)</span></span></p></div></body></tt>""")!!
        val line = l.lines.single()
        assertEquals("I'm ready", line.text)
        assertEquals(listOf("I'm ", "ready "), line.words!!.map { it.text }.map { it.trimEnd() + " " })
        assertEquals("Woo-hoo", line.background)
        assertEquals(listOf(3000L to 3500L, 3500L to 4000L), line.backgroundWords!!.map { it.startMs to it.endMs })
        assertNull(l.translationLanguage)
    }

    @Test fun inlineTranslationAndRomanSpansAreTaken() {
        val l = ttml("""$head<body><div><p begin="1.0" end="3.0"><span begin="1.0" end="3.0">안녕</span><span ttm:role="x-translation" xml:lang="en">Hello</span><span ttm:role="x-roman">annyeong</span></p></div></body></tt>""")!!
        assertEquals("안녕", l.lines[0].text)
        assertEquals("Hello", l.lines[0].translation)
        assertEquals("annyeong", l.lines[0].romanization)
        assertEquals("en", l.translationLanguage)
    }

    @Test fun aTransliterationThatIsTheLineItselfIsDropped() {
        val l = ttml("""$head<head><metadata><transliterations><transliteration xml:lang="ko-Latn"><text for="L1"><span begin="0" end="1">Hello there</span></text></transliteration></transliterations></metadata></head>
            <body><div><p begin="1.0" end="3.0" lrc:key="L1"><span begin="1.0" end="3.0">Hello there</span></p></div></body></tt>""")!!
        assertNull(l.lines[0].romanization)
    }

    @Test fun lyricsPlusKeepsBackingVocalsVoicesAndTranslations() {
        val o = JSONObject("""{"metadata":{"source":"qApple","language":"ja"},"lyrics":[
            {"time":1000,"duration":2000,"text":"I'm ready (Woo-hoo)","syllabus":[{"time":1000,"duration":500,"text":"I'm "},{"time":1500,"duration":500,"text":"ready "},{"time":2000,"duration":300,"text":"(Woo-","isBackground":true},{"time":2300,"duration":300,"text":"hoo)","isBackground":true}],
             "element":{"singer":"v2"},"translation":{"text":"Je suis prêt","lang":"fr"},"transliteration":{"text":"aimu redi"}},
            {"time":5000,"duration":1000,"text":"(Ooh)","syllabus":[{"time":5000,"duration":1000,"text":"(Ooh)","isBackground":true}],"element":{"singer":"v1000"}}]}""")
        val l = LyricsApi.lyricsPlusLines(o)!!
        val a = l.lines[0]
        assertEquals("I'm ready", a.text)
        assertEquals(2, a.words!!.size)
        assertEquals("Woo-hoo", a.background)
        assertEquals(2, a.voice)
        assertEquals("Je suis prêt", a.translation)
        assertEquals("aimu redi", a.romanization)
        assertEquals("fr", l.translationLanguage)
        // A line of backing vocals only is a line of its own, sung by everyone.
        val b = l.lines[1]
        assertEquals("(Ooh)", b.text)
        assertNull(b.background)
        assertEquals(0, b.voice)
        assertEquals("Apple Music · LyricsPlus", l.provider)
    }

    @Test fun lyricsPlusWithoutExtrasIsAsBefore() {
        val l = LyricsApi.lyricsPlusLines(JSONObject("""{"lyrics":[{"time":0,"duration":1000,"text":"a b","syllabus":[{"time":0,"duration":500,"text":"a "},{"time":500,"duration":500,"text":"b"}]}]}"""))!!
        val line = l.lines.single()
        assertEquals("a b", line.text)
        assertNull(line.translation); assertNull(line.voice); assertNull(line.background)
        assertNull(l.translationLanguage)
    }

    @Test fun voiceNames() {
        assertEquals(1, LyricsApi.voiceOf("v1")); assertEquals(0, LyricsApi.voiceOf("v1000"))
        assertNull(LyricsApi.voiceOf("narrator")); assertNull(LyricsApi.voiceOf(null))
    }
}
