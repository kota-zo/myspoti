package app.myspoti.extensions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The optional fields of a lyrics line are additive: absent or of a wrong type, they are no error. */
class LyricsResultTest {
    @Test fun translationRomanizationVoiceAndBackgroundAreRead() {
        val r = LyricsResult.parse(
            """{"translationLanguage":"en-US","lines":[
                {"time":1000,"text":"안녕","translation":" Hello ","romanization":"annyeong","voice":2,"background":"(ooh)"},
                {"time":2000,"text":"b","voice":"v1"}]}""", "Ext")!!
        assertEquals("en-US", r.translationLanguage)
        val a = r.lines[0]
        assertEquals("Hello", a.translation)
        assertEquals("annyeong", a.romanization)
        assertEquals(2, a.voice)
        assertEquals("(ooh)", a.background)
        assertEquals(1, r.lines[1].voice)
        assertNull(r.lines[1].translation)
    }

    @Test fun nothingBecomesAnErrorForAnExtensionThatSendsNoneOrWrongOnes() {
        val plain = LyricsResult.parse("""{"lines":[{"time":1,"text":"a"}]}""", "Ext")!!
        assertNull(plain.translationLanguage)
        assertNull(plain.lines[0].translation); assertNull(plain.lines[0].voice)
        val odd = LyricsResult.parse(
            """{"translationLanguage":"not a language!","lines":[{"time":1,"text":"a","translation":5,"romanization":["x"],"voice":"loud","background":{"a":1}},
                {"time":2,"text":"b","translation":"  ","voice":1000}]}""", "Ext")!!
        assertNull(odd.translationLanguage)
        val line = odd.lines[0]
        assertNull(line.translation); assertNull(line.romanization); assertNull(line.voice); assertNull(line.background)
        assertNull(odd.lines[1].translation)
        assertNull(odd.lines[1].voice)
        assertEquals(listOf("a", "b"), odd.lines.map { it.text })
    }

    @Test fun plainLyricsHaveNone() {
        val r = LyricsResult.parse("""{"plain":"one\ntwo","translationLanguage":"fr"}""", "Ext")!!
        assertNull(r.translationLanguage)
        assertNull(r.lines[0].translation)
    }
}
