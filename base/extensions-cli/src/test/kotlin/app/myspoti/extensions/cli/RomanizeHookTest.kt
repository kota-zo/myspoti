package app.myspoti.extensions.cli

import app.myspoti.extensions.Hook
import app.myspoti.extensions.Manifest
import app.myspoti.extensions.Romanize
import app.myspoti.extensions.TrackInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The lyrics-romanize hook in the runner: the lines the app would send, and a script answering in the real sandbox. */
class RomanizeHookTest {
    private fun res(name: String) = File(javaClass.classLoader.getResource(name)!!.toURI())
    private val dir = res("romanize-ext")
    private val manifest = Manifest.parse(File(dir, "manifest.json").readText())
    private val script = File(dir, manifest.main).readText()
    private val track = TrackInfo("", "", "Title", "Artist", listOf("Artist"), "", 0)

    @Test fun theInputHoldsTheLinesInAnotherScriptByTheirIndexInTheSong() {
        val input = hookInput(Hook.LYRICS_ROMANIZE, track, mapOf("text" to "Intro\n夢ならば\n♪\n사랑해", "language" to "ja"))!!
        assertEquals(mapOf(1 to "夢ならば", 3 to "사랑해"), Romanize.asked(input))
        assertNull(hookInput(Hook.LYRICS_ROMANIZE, track, emptyMap()))
    }

    @Test fun aScenarioStepSendsItsLinesAndReadsTheAnswer() {
        val r = ScenarioRunner(manifest, script, Scenario.parse("""{ "offline": true, "steps": [
            { "hook": "lyrics-romanize", "language": "ja", "lines": ["Intro", "夢ならば"], "expect": { "outcome": "found" } },
            { "hook": "lyrics-romanize", "lines": ["Привет"], "expect": { "outcome": "not-found" } } ] }"""), echo = {}).run()
        assertTrue(r.joinToString("\n"), r.all { it.ok })
    }

    @Test fun aStepWithoutLinesSaysWhatItNeeds() {
        val r = ScenarioRunner(manifest, script, Scenario.parse("""{ "offline": true, "steps": [ { "hook": "lyrics-romanize" } ] }"""), echo = {}).run()
        assertEquals(listOf("lyrics-romanize needs \"lines\" (or \"input\")"), r.single().problems)
    }
}
