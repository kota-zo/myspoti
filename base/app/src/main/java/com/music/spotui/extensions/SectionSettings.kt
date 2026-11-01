package com.music.spotui.extensions

import app.myspoti.extensions.Manifest
import app.myspoti.extensions.Setting
import app.myspoti.extensions.SettingSection

/**
 * The settings an extension announces in a section of the app's Settings (manifest `section`):
 * those of [extensionId] ([name]), and whether the app's own quality gives way to them
 * ([hidesAppQuality]; it keeps its value and is used as before).
 */
data class SectionPlacement(
    val extensionId: String,
    val name: String,
    val settings: List<Setting>,
    val quality: Setting?,
    val hidesAppQuality: Boolean,
)

/**
 * Who shows settings in Settings → Playback and → Downloads. Only the first source of the order
 * does: in Playback the first of the audio order, and only when it is an extension (above
 * Spotify), then Spotify's Wi-Fi and mobile data quality leave the screen; in Downloads the first
 * extension of the download order, and the app's download quality leaves only for its
 * `role: quality`. An extension turned off is not a source: the next one leads. Nothing when the
 * leader announces nothing in that section, so an extension without `section` changes nothing.
 */
object SectionSettings {
    /** The first source of [line] that is asked: Spotify, or an extension turned on. */
    fun leader(line: List<String>, enabled: (String) -> Boolean): String? =
        line.firstOrNull { it == SPOTIFY_SOURCE || enabled(it) }

    fun place(section: SettingSection, line: List<String>, enabled: (String) -> Boolean, manifest: (String) -> Manifest?): SectionPlacement? {
        val lead = leader(line, enabled)?.takeIf { it != SPOTIFY_SOURCE } ?: return null
        val m = manifest(lead) ?: return null
        val shown = m.settingsIn(section).takeIf { it.isNotEmpty() } ?: return null
        val quality = m.qualitySetting(section)
        return SectionPlacement(lead, m.name, shown, quality, hidesAppQuality = section == SettingSection.PLAYBACK || quality != null)
    }

    /**
     * The quality the extension [m] is set to for [section], "Quality: FLAC" (its label, its
     * value), as `myspoti.settings.get` reads it now; null without a `role: quality` there.
     */
    fun qualityLine(m: Manifest, section: SettingSection, stored: (String) -> String?, metered: Boolean): String? {
        val s = m.qualitySetting(section) ?: return null
        val v = s.value(stored, metered)?.let { runCatching { kotlinx.serialization.json.Json.parseToJsonElement(it) }.getOrNull() }
            ?.let { runCatching { (it as kotlinx.serialization.json.JsonPrimitive).content }.getOrNull() } ?: return null
        return "${s.label}: $v"
    }
}
