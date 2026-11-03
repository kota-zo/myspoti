package app.myspoti.extensions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ManifestTranslationsTest {
    private val text = """
        {
          "id": "io.github.someone.music",
          "name": "Some music",
          "version": "1.0.0",
          "author": "someone",
          "description": "Music from example.com.",
          "actions": [
            { "name": "audio.source", "why": "Plays songs from example.com" },
            { "name": "settings.page", "why": "Its quality" }
          ],
          "settings": [{ "key": "quality", "type": "choice", "label": "Quality", "options": ["lossless", "high"] }],
          "translations": {
            "fr": {
              "description": "De la musique d'example.com.",
              "actions": { "audio.source": "Lit les titres d'example.com" },
              "settings": { "quality": { "label": "Qualité", "options": ["Sans perte", "Haute"] } }
            },
            "pt-BR": { "description": "Música do example.com." }
          }
        }
    """.trimIndent()

    @Test fun theTextsFollowTheLanguageAndTheValuesStay() {
        val m = Manifest.parse(text)
        assertEquals(emptyList<String>(), m.problems())
        val fr = m.localized("fr-FR")
        assertEquals("De la musique d'example.com.", fr.description)
        assertEquals("Some music", fr.name)
        val q = fr.settings.single()
        assertEquals("Qualité", q.label)
        assertEquals(listOf("lossless", "high"), q.options)
        assertEquals("Sans perte", q.optionLabel("lossless"))
        assertEquals("Lit les titres d'example.com", fr.whyShown(fr.actions[0]))
        assertEquals("Its quality", fr.whyShown(fr.actions[1]))
    }

    @Test fun whatWasAcceptedIsWeighedOnTheWrittenReasons() {
        val m = Manifest.parse(text)
        val fr = m.localized("fr")
        assertEquals(m.asks(), fr.asks())
        assertEquals("Lit les titres d'example.com", fr.asksShown().first { it.key == "audio.source" }.why)
    }

    @Test fun aRegionFallsBackToItsLanguageAndBackToTheWrittenTexts() {
        val m = Manifest.parse(text)
        assertEquals("Música do example.com.", m.localized("pt").description)
        assertEquals("Música do example.com.", m.localized("pt-PT").description)
        val back = m.localized("fr").localized("de")
        assertEquals("Music from example.com.", back.description)
        assertEquals("Quality", back.settings.single().label)
        assertSame(m, m.localized("de"))
    }

    @Test fun aTranslationOfWhatIsNotThereIsAProblem() {
        val bad = Manifest.parse(text.replace("\"audio.source\": \"Lit", "\"lyrics.provide\": \"Lit").replace("[\"Sans perte\", \"Haute\"]", "[\"Sans perte\"]"))
        val problems = bad.problems()
        assertTrue(problems.toString(), problems.any { "lyrics.provide" in it })
        assertTrue(problems.toString(), problems.any { "one label per option" in it })
    }
}
