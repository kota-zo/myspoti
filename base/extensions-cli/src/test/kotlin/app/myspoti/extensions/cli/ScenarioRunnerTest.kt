package app.myspoti.extensions.cli

import app.myspoti.extensions.Hook
import app.myspoti.extensions.HookBudgets
import app.myspoti.extensions.Manifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ScenarioRunnerTest {
    private fun res(name: String) = File(javaClass.classLoader.getResource(name)!!.toURI())
    private val dir = res("sample-ext")
    private val manifest = Manifest.parse(File(dir, "manifest.json").readText())
    private val script = File(dir, manifest.main).readText()

    private fun run(scenario: String, state: File? = null) =
        ScenarioRunner(manifest, script, Scenario.parse(scenario), state, echo = {}).run()

    @Test fun sampleScenarioPasses() {
        val results = ScenarioRunner(manifest, script, Scenario.parse(res("sample-scenario.json").readText()), echo = {}).run()
        results.filter { !it.ok }.forEach { println(it) }
        assertTrue(results.all { it.ok })
        assertEquals(listOf("found", "rate-limited", "server-down", "network", "cancelled"),
            results.filter { it.what.startsWith("hook") }.map { it.outcome!!.substringBefore(':').substringBefore(' ') })
    }

    @Test fun aWrongExpectationFails() {
        val r = run("""{ "offline": true, "steps": [ { "hook": "audio-source", "expect": { "outcome": "found" } } ] }""")
        assertFalse(r.single().ok)
        assertEquals("network", r.single().outcome!!.substringBefore(':'))
    }

    @Test fun aRuleAppliesTheNumberOfTimesGiven() {
        val r = run("""{ "offline": true, "http": [
            { "match": "api.example.com", "status": 500, "times": 1 },
            { "match": "api.example.com", "body": "{\"url\":\"https://cdn.example.com/b.flac\"}" } ],
          "steps": [ { "hook": "audio-source", "expect": { "outcome": "server-down" } },
                     { "hook": "audio-source", "expect": { "outcome": "found" } } ] }""")
        assertTrue(r.joinToString("\n"), r.all { it.ok })
    }

    @Test fun storageOutlivesARunWithAStateFile() {
        val state = File.createTempFile("state", ".json").apply { delete(); deleteOnExit() }
        val scenario = """{ "offline": true, "http": [ { "match": "*", "status": 404 } ],
          "steps": [ { "hook": "audio-source", "expect": { "outcome": "not-found" } } ] }"""
        run(scenario, state); run(scenario, state)
        assertTrue(state.readText(), state.readText().contains("\"asked\":2"))
        val after = run("""{ "steps": [ { "storage": { "asked": 2, "lastTitle": "Sample" } } ] }""", state)
        assertTrue(after.joinToString(), after.single().ok)
    }

    @Test fun theRunnerUsesTheAppsBudgets() {
        // A request slower than the app gives audio-source ends as a network error, not a bug.
        val r = run("""{ "offline": true, "http": [ { "match": "*", "latencyMs": ${HookBudgets.AUDIO + 5_000} } ],
          "steps": [ { "hook": "audio-source", "cancelAtMs": 200 } ] }""")
        assertEquals("cancelled", r.single().outcome!!.substringBefore(':'))
        assertEquals(15_000L, HookBudgets.of(Hook.AUDIO_SOURCE))
    }

    @Test fun anUnknownStepIsReported() {
        assertFalse(run("""{ "steps": [ { "nothing": 1 } ] }""").single().ok)
    }
}
