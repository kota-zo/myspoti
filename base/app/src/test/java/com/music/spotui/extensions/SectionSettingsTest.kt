package com.music.spotui.extensions

import app.myspoti.extensions.ActionRequest
import app.myspoti.extensions.Manifest
import app.myspoti.extensions.Setting
import app.myspoti.extensions.SettingSection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which source shows its settings in Settings → Playback and → Downloads, and when the app's quality leaves. */
class SectionSettingsTest {
    private fun manifest(id: String, action: String, vararg settings: Setting) = Manifest(id, id.substringAfterLast('.'), "1", author = "me",
        actions = listOf(ActionRequest(action, "Gives songs"), ActionRequest("settings.page", "Options")), settings = settings.toList())

    private val quality = Setting("q", "choice", "Quality", options = listOf("FLAC", "MP3"), default = kotlinx.serialization.json.JsonPrimitive("FLAC"), section = "playback", role = "quality")
    private val hifi = manifest("io.github.a.hifi", "audio.source", quality, Setting("server", "text", "Server", section = "playback"), Setting("token", "secret", "Token"))
    private val plain = manifest("io.github.a.plain", "audio.source", Setting("token", "secret", "Token"))
    private val dl = manifest("io.github.a.dl", "download.source", quality.copy(section = "downloads"))
    private val dlOther = manifest("io.github.a.other", "download.source", Setting("folder", "text", "Folder", section = "downloads"))
    private val manifests = listOf(hifi, plain, dl, dlOther).associateBy { it.id }
    private val on = { _: String -> true }

    private fun playback(line: List<String>, enabled: (String) -> Boolean = on) = SectionSettings.place(SettingSection.PLAYBACK, line, enabled, manifests::get)
    private fun downloads(line: List<String>, enabled: (String) -> Boolean = on) = SectionSettings.place(SettingSection.DOWNLOADS, line, enabled, manifests::get)

    @Test fun theFirstSourceLeads() {
        assertEquals(SPOTIFY_SOURCE, SectionSettings.leader(listOf(SPOTIFY_SOURCE, hifi.id), on))
        assertEquals(hifi.id, SectionSettings.leader(listOf(hifi.id, SPOTIFY_SOURCE), on))
        // Turned off, it is not a source: the next one leads.
        assertEquals(SPOTIFY_SOURCE, SectionSettings.leader(listOf(hifi.id, SPOTIFY_SOURCE), { it != hifi.id }))
        assertNull(SectionSettings.leader(emptyList(), on))
    }

    @Test fun anExtensionAboveSpotifyShowsItsPlaybackSettingsAndHidesSpotifysQuality() {
        val p = playback(listOf(hifi.id, SPOTIFY_SOURCE, plain.id))!!
        assertEquals(hifi.id, p.extensionId)
        assertEquals(listOf("q", "server"), p.settings.map { it.key })
        assertEquals("q", p.quality?.key)
        assertTrue(p.hidesAppQuality)
    }

    @Test fun spotifyFirstIsTheScreenAsBefore() {
        // Spotify moved back to the top: the extension's settings stay on its page, Spotify's quality is back.
        assertNull(playback(listOf(SPOTIFY_SOURCE, hifi.id)))
        // Only the first source counts: an extension under another one shows nothing here.
        assertNull(playback(listOf(plain.id, hifi.id, SPOTIFY_SOURCE)))
        // Turned off: as if it were not there.
        assertNull(playback(listOf(hifi.id, SPOTIFY_SOURCE), { it != hifi.id }))
        // Not installed (no manifest): nothing.
        assertNull(playback(listOf("io.github.a.gone", SPOTIFY_SOURCE)))
    }

    @Test fun anExtensionWithoutSectionChangesNothing() {
        assertNull(playback(listOf(plain.id, SPOTIFY_SOURCE)))
    }

    @Test fun theFirstDownloaderHidesTheDownloadQualityOnlyForItsRoleQuality() {
        val p = downloads(listOf(dl.id, dlOther.id))!!
        assertEquals(dl.id, p.extensionId)
        assertTrue(p.hidesAppQuality)
        val other = downloads(listOf(dlOther.id, dl.id))!!
        assertEquals(listOf("folder"), other.settings.map { it.key })
        assertFalse(other.hidesAppQuality)
        assertEquals(dl.id, downloads(listOf(dlOther.id, dl.id), { it != dlOther.id })?.extensionId)
        assertNull(downloads(emptyList()))
    }

    @Test fun theQualityLineSaysTheValueOfTheCurrentNetwork() {
        val split = manifest("io.github.a.split", "audio.source", quality.copy(perNetwork = true))
        val stored = mapOf("q" to "\"MP3\"", Setting.cellKey("q") to "\"FLAC\"")
        assertEquals("Quality: MP3", SectionSettings.qualityLine(split, SettingSection.PLAYBACK, stored::get, metered = false))
        assertEquals("Quality: FLAC", SectionSettings.qualityLine(split, SettingSection.PLAYBACK, stored::get, metered = true))
        assertEquals("Quality: FLAC", SectionSettings.qualityLine(hifi, SettingSection.PLAYBACK, emptyMap<String, String>()::get, metered = false))
        assertNull(SectionSettings.qualityLine(plain, SettingSection.PLAYBACK, stored::get, metered = false))
    }
}
