package app.myspoti.extensions

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A setting's `section`, `role` and `perNetwork`: read, checked, and the value `get` gives on each network. */
class SettingSectionTest {
    private val audio = """
        {
          "id": "io.github.someone.hifi", "name": "Hi-Fi", "version": "1.0", "author": "someone",
          "actions": [{ "name": "audio.source", "why": "Plays songs in lossless" }, { "name": "settings.page", "why": "Its quality" }],
          "settings": [
            { "key": "quality", "type": "choice", "label": "Quality", "options": ["FLAC", "MP3"], "default": "FLAC",
              "section": "playback", "role": "quality", "perNetwork": true },
            { "key": "server", "type": "text", "label": "Server", "section": "playback" },
            { "key": "token", "type": "secret", "label": "Token" }
          ]
        }
    """.trimIndent()

    @Test fun anOldManifestReadsAsBefore() {
        val text = """{ "id": "io.github.a.b", "name": "A", "version": "1", "author": "a",
            "actions": [{ "name": "settings.page", "why": "Options" }],
            "settings": [{ "key": "lang", "type": "choice", "label": "Language", "options": ["en", "fr"] }] }"""
        val m = Manifest.parse(text)
        val s = m.settings.single()
        assertEquals(Setting("lang", "choice", "Language", options = listOf("en", "fr")), s)
        assertNull(s.sectionOf); assertFalse(s.isQuality); assertFalse(s.splitsByNetwork)
        assertEquals(emptyList<String>(), m.problems())
        assertEquals(emptyList<String>(), m.settingFieldProblems())
        assertEquals(emptyList<String>(), m.settingFieldWarnings())
        SettingSection.entries.forEach { assertEquals(emptyList<Setting>(), m.settingsIn(it)); assertNull(m.qualitySetting(it)) }
        // Its value is read as before: stored, else default, on any network.
        val stored = mapOf("lang" to "\"fr\"")
        assertEquals("\"fr\"", s.value(stored::get, metered = true))
        assertEquals(emptyList<String>(), m.networkKeys(stored::get))
    }

    @Test fun theNewFieldsAreRead() {
        val m = Manifest.parse(audio)
        assertEquals(emptyList<String>(), m.problems())
        assertEquals(emptyList<String>(), m.settingFieldProblems())
        assertEquals(emptyList<String>(), m.settingFieldWarnings())
        assertEquals(listOf("quality", "server"), m.settingsIn(SettingSection.PLAYBACK).map { it.key })
        assertEquals("quality", m.qualitySetting(SettingSection.PLAYBACK)?.key)
        assertNull(m.qualitySetting(SettingSection.DOWNLOADS))
        assertTrue(m.settings[0].splitsByNetwork)
    }

    @Test fun unknownValuesAreWarnedNotRefused() {
        val m = Manifest.parse(audio.replace("\"section\": \"playback\" }", "\"section\": \"lyrics\", \"role\": \"accent\" }"))
        assertEquals(emptyList<String>(), m.problems())
        assertEquals(emptyList<String>(), m.settingFieldProblems())
        val w = m.settingFieldWarnings().joinToString("\n")
        assertTrue(w, "section \"lyrics\" unknown" in w)
        assertTrue(w, "role \"accent\" unknown" in w)
        // Kept on its page, shown nowhere else.
        assertEquals(listOf("quality"), m.settingsIn(SettingSection.PLAYBACK).map { it.key })
        assertEquals(3, m.settings.size)
    }

    @Test fun theRulesOfTheNewFields() {
        val m = Manifest.parse(audio).let { m ->
            m.copy(settings = listOf(
                Setting("a", "text", "A", section = "playback", role = "quality"),
                Setting("b", "choice", "B", options = listOf("x"), section = "playback", role = "quality"),
                Setting("c", "secret", "C", perNetwork = true),
                Setting("d", "button", "D", perNetwork = true),
                Setting("e", "choice", "E", options = listOf("x"), role = "quality"),
                Setting("f", "choice", "F", options = listOf("x"), section = "downloads"),
            ))
        }
        val p = m.settingFieldProblems().joinToString("\n")
        assertTrue(p, "\"a\": role quality is a choice" in p)
        assertTrue(p, "\"c\": perNetwork is for" in p)
        assertTrue(p, "\"d\": perNetwork is for" in p)
        assertTrue(p, "\"e\": role quality needs a section" in p)
        assertTrue(p, "playback has more" in p)
        // Still installs: these are the check's, never the install's.
        assertFalse(m.problems().any { "role" in it || "perNetwork" in it })
        // The app ignores what does not hold: a text is no quality, a secret one value.
        assertEquals("b", m.qualitySetting(SettingSection.PLAYBACK)?.key)
        assertFalse(m.settings[2].splitsByNetwork)
        // A downloads section on an extension that gives no downloads can never be shown.
        assertTrue(m.settingFieldWarnings().any { "\"f\"" in it && "download.source" in it })
    }

    @Test fun aPerNetworkValueFollowsTheNetwork() {
        val s = Manifest.parse(audio).settings[0]
        val stored = HashMap<String, String>()
        // Nothing set: the default on both.
        assertEquals("\"FLAC\"", s.value(stored::get, false))
        assertEquals("\"FLAC\"", s.value(stored::get, true))
        // Wi-Fi set: mobile data follows it until set, as the app's own streaming quality.
        stored["quality"] = "\"MP3\""
        assertEquals("\"MP3\"", s.value(stored::get, true))
        stored[Setting.cellKey("quality")] = "\"FLAC\""
        assertEquals("\"MP3\"", s.value(stored::get, false))
        assertEquals("\"FLAC\"", s.value(stored::get, true))
        assertEquals("quality", Setting.baseKey(Setting.cellKey("quality")))
        assertEquals(listOf("quality"), Manifest.parse(audio).networkKeys(stored::get))
        // Without perNetwork the mobile value is never read.
        assertEquals("\"MP3\"", s.copy(perNetwork = false).value(stored::get, true))
    }

    private class NetHost(var cell: Boolean, val values: Map<String, String>) : Host {
        override fun log(extensionId: String, message: String) {}
        override fun storage(extensionId: String): Storage = MemoryStorage()
        override fun setting(extensionId: String, key: String): String? = values[key]
        override fun metered(): Boolean = cell
    }

    @Test fun getGivesTheCurrentNetworksValue() {
        val m = Manifest.parse(audio).copy(actions = Manifest.parse(audio).actions + ActionRequest("lyrics.provide", "Test"))
        val host = NetHost(false, mapOf("quality" to "\"MP3\"", Setting.cellKey("quality") to "\"FLAC\"", "server" to "\"s\""))
        val script = "myspoti.provide('lyrics', function (t) { return { v: myspoti.settings.get('quality') + '/' + myspoti.settings.get('server') + '/' + myspoti.settings.get('token') }; });"
        val track = TrackInfo("abc", "spotify:track:abc", "Song", "Artist", listOf("Artist"), "Album", 180_000).toJson()
        ExtensionRuntime(m, script, host, m.asks().map { it.key }.toSet()).use { rt ->
            rt.start()
            assertTrue(rt.call(Hook.LYRICS, track, 3_000)!!.contains("MP3/s/undefined"))
            host.cell = true
            assertTrue(rt.call(Hook.LYRICS, track, 3_000)!!.contains("FLAC/s/undefined"))
        }
        // A default stays a plain value.
        assertEquals(JsonPrimitive("FLAC"), m.settings[0].default)
    }
}
