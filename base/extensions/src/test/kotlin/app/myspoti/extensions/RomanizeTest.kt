package app.myspoti.extensions

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The lyrics-romanize hook: which lines are asked, the input sent, and an answer read leniently. */
class RomanizeTest {
    private val track = TrackInfo("id1", "spotify:track:id1", "Title", "Artist", listOf("Artist"), "Album", 1000)

    @Test fun aLetterOutsideLatinScriptNeedsARomanization() {
        assertTrue(Romanize.needs("夢ならば"))
        assertTrue(Romanize.needs("ありがとう"))
        assertTrue(Romanize.needs("사랑해"))
        assertTrue(Romanize.needs("Привет"))
        assertTrue(Romanize.needs("I love you 君を"))
        assertTrue(Romanize.needs("ｶﾞ"))
    }

    @Test fun latinLettersDigitsAndSymbolsDoNot() {
        assertFalse(Romanize.needs("Hello, world"))
        assertFalse(Romanize.needs("Ça va, déjà vu"))
        assertFalse(Romanize.needs("♪"))
        assertFalse(Romanize.needs("(ooh) 1, 2, 3!"))
        assertFalse(Romanize.needs(""))
    }

    @Test fun wantedKeepsTheIndexOfTheLinesStillWithoutARomanization() {
        val lines = listOf("Intro" to null, "夢ならば" to null, "♪" to null, "今でも" to "ima demo", "사랑해" to "")
        val wanted = Romanize.wanted(lines, { it.first }, { !it.second.isNullOrBlank() })
        assertEquals(mapOf(1 to "夢ならば", 4 to "사랑해"), wanted)
    }

    @Test fun theRequestCarriesTheTrackTheLanguageAndTheLinesByIndex() {
        val o = Json.parseToJsonElement(Romanize.request(track, "ja", mapOf(1 to "夢ならば", 4 to "今でも"))).jsonObject
        assertEquals("Title", o.getValue("track").jsonObject.getValue("title").jsonPrimitive.content)
        assertEquals("ja", o.getValue("language").jsonPrimitive.content)
        val lines = o.getValue("lines").jsonArray.map { it.jsonObject }
        assertEquals(listOf(1, 4), lines.map { it.getValue("index").jsonPrimitive.int })
        assertEquals(listOf("夢ならば", "今でも"), lines.map { it.getValue("text").jsonPrimitive.content })
        assertNull(Json.parseToJsonElement(Romanize.request(track, null, emptyMap())).jsonObject["language"])
    }

    @Test fun askedReadsTheLinesOfARequestBack() {
        assertEquals(mapOf(1 to "夢ならば", 4 to "今でも"), Romanize.asked(Romanize.request(track, "ja", mapOf(1 to "夢ならば", 4 to "今でも"))))
        assertEquals(emptyMap<Int, String>(), Romanize.asked("not json"))
    }

    @Test fun anAnswerIsReadByIndexTrimmedAndCapped() {
        val long = "a".repeat(Romanize.MAX_TEXT + 20)
        val got = Romanize.parse("""{"lines":[{"index":1,"romanization":" yume naraba "},{"index":"4","romanization":"$long"}]}""")
        assertEquals("yume naraba", got[1])
        assertEquals(Romanize.MAX_TEXT, got[4]?.length)
    }

    @Test fun aWrongLineIsIgnoredNeverAnError() {
        val got = Romanize.parse(
            """{"lines":[5,{"romanization":"no index"},{"index":1.5,"romanization":"x"},{"index":2,"romanization":7},
               {"index":3,"romanization":"  "},{"index":6,"romanization":"first"},{"index":6,"romanization":"second"}]}""",
        )
        assertEquals(mapOf(6 to "first"), got)
    }

    @Test(expected = ExtensionException::class) fun anAnswerThatIsNotAnObjectFails() {
        Romanize.parse("[1, 2]")
    }

    @Test(expected = ExtensionException::class) fun anAnswerWithoutLinesFails() {
        Romanize.parse("""{"outcome":"not-found"}""")
    }

    @Test fun theHookIsUnlockedByItsOwnActionInTheLyricsArea() {
        assertEquals(Hook.LYRICS_ROMANIZE, Hook.of("lyrics-romanize"))
        assertEquals(Action.LYRICS_ROMANIZE, Hook.LYRICS_ROMANIZE.action)
        assertEquals(Area.LYRICS, Action.LYRICS_ROMANIZE.area)
        assertEquals(Level.NORMAL, Action.LYRICS_ROMANIZE.level)
        assertEquals(Mode.ADDITIVE, Action.LYRICS_ROMANIZE.mode)
        assertEquals(HookBudgets.ROMANIZE, HookBudgets.of(Hook.LYRICS_ROMANIZE))
        assertTrue(Category.LYRICS.fits(setOf(Action.LYRICS_ROMANIZE)))
    }
}
