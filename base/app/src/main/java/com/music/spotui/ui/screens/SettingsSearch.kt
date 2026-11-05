package com.music.spotui.ui.screens

import app.myspoti.engine.R

/** One row Settings' search finds: its title, its description (0: none) and where it is ([place]). */
internal class SettingsEntry(val title: Int, val detail: Int, val place: String)

/**
 * What the search button of Settings looks through, in both looks: the rows of every page, by
 * their title and description in the app's language. A hit opens the page (Immersive: a
 * `SettingsPage` name) or the tab (Classic: its index, or `transitions` for that page) it is on.
 * Kept by hand with the pages: a row added to a page is added here too.
 */
internal object SettingsSearch {
    val immersive: List<SettingsEntry> = listOf(
        SettingsEntry(R.string.settings_sources_tab, 0, "Sources"),
        SettingsEntry(R.string.settings_transitions_title, R.string.settings_transitions_detail, "Crossfade"),
        SettingsEntry(R.string.accounts_title, 0, "Accounts"),
        SettingsEntry(R.string.extensions_title, R.string.extensions_row_detail, "Sources"),
        SettingsEntry(R.string.settings_autoplay_title, R.string.settings_autoplay_detail, "Playback"),
        SettingsEntry(R.string.settings_release_notify, R.string.settings_release_notify_detail, "Playback"),
        SettingsEntry(R.string.myspoti_shuffle_title, R.string.myspoti_shuffle_detail, "Playback"),
        SettingsEntry(R.string.equalizer_title, R.string.equalizer_settings_detail, "Playback"),
        SettingsEntry(R.string.settings_keep_playing_title, R.string.settings_keep_playing_detail, "Playback"),
        SettingsEntry(R.string.settings_headset_skip_title, R.string.settings_headset_skip_detail, "Playback"),
        SettingsEntry(R.string.settings_connect_mirror_title, R.string.settings_connect_mirror_detail, "Playback"),
        SettingsEntry(R.string.settings_download_sync_title, R.string.settings_download_sync_detail, "Playback"),
        SettingsEntry(R.string.live_radio_settings_title, R.string.live_radio_settings_detail, "Playback"),
        SettingsEntry(R.string.hidden_songs_title, R.string.hidden_songs_detail, "Playback"),
        SettingsEntry(R.string.lyrics_word_sync_title, 0, "Lyrics"),
        SettingsEntry(R.string.lyrics_extra_title, 0, "Lyrics"),
        SettingsEntry(R.string.settings_lyrics_keep_awake_title, R.string.settings_lyrics_keep_awake_detail, "Lyrics"),
        SettingsEntry(R.string.lyrics_romanization, R.string.settings_lyrics_romanization_detail, "Lyrics"),
        SettingsEntry(R.string.lyrics_translation_title, R.string.lyrics_translation_detail, "Lyrics"),
        SettingsEntry(R.string.settings_automix_segue, R.string.settings_automix_segue_detail, "Crossfade"),
        SettingsEntry(R.string.settings_automix_session, R.string.settings_automix_session_detail, "Crossfade"),
        SettingsEntry(R.string.settings_automix_album_order, R.string.settings_automix_album_order_detail, "Crossfade"),
        SettingsEntry(R.string.settings_automix_podcasts, R.string.settings_automix_podcasts_detail, "Crossfade"),
        SettingsEntry(R.string.settings_automix_harmonic, R.string.settings_automix_harmonic_detail, "Crossfade"),
        SettingsEntry(R.string.settings_automix_eq, R.string.settings_automix_eq_detail, "Crossfade"),
        SettingsEntry(R.string.settings_automix_show, R.string.settings_automix_show_detail, "Crossfade"),
        SettingsEntry(R.string.settings_automix_mobile_data, R.string.settings_automix_mobile_data_detail, "Crossfade"),
        SettingsEntry(R.string.settings_automix_cache, 0, "Crossfade"),
        SettingsEntry(R.string.downloads_source_row_title, 0, "Downloads"),
        SettingsEntry(R.string.settings_download_lyrics_title, R.string.settings_download_lyrics_detail, "Downloads"),
        SettingsEntry(R.string.settings_storage_clear_cache, 0, "Storage"),
        SettingsEntry(R.string.settings_item_remove_downloads_title, 0, "Storage"),
        SettingsEntry(R.string.energy_saver_title, R.string.energy_saver_summary, "Data"),
        SettingsEntry(R.string.data_saver_title, R.string.data_saver_summary, "Data"),
        SettingsEntry(R.string.background_playback_title, 0, "Data"),
        SettingsEntry(R.string.settings_look_title, 0, "Display"),
        SettingsEntry(R.string.settings_dynamic_color_title, R.string.settings_dynamic_color_detail, "Display"),
        SettingsEntry(R.string.settings_system_font_title, R.string.settings_system_font_detail, "Display"),
        SettingsEntry(R.string.settings_liquid_glass_title, R.string.settings_liquid_glass_detail, "Display"),
        SettingsEntry(R.string.settings_amoled_theme, R.string.settings_amoled_theme_subtitle, "Display"),
        SettingsEntry(R.string.settings_item_canvas_title, 0, "Display"),
        SettingsEntry(R.string.canvas_wifi_only, R.string.canvas_wifi_only_detail, "Display"),
        SettingsEntry(R.string.settings_music_video_title, R.string.settings_music_video_detail, "Display"),
        SettingsEntry(R.string.settings_song_pip_title, R.string.settings_song_pip_detail, "Display"),
        SettingsEntry(R.string.settings_hide_dj_title, R.string.settings_hide_dj_detail, "Display"),
        SettingsEntry(R.string.settings_player_lyrics_card_title, R.string.settings_player_lyrics_card_detail, "Display"),
        SettingsEntry(R.string.settings_player_source_title, R.string.settings_player_source_detail, "Display"),
        SettingsEntry(R.string.settings_player_capsule_title, R.string.settings_player_capsule_detail, "Display"),
        SettingsEntry(R.string.settings_player_modes_title, R.string.settings_player_modes_detail, "Display"),
        SettingsEntry(R.string.settings_player_volume_title, R.string.settings_player_volume_detail, "Display"),
        SettingsEntry(R.string.settings_rotation_title, 0, "Display"),
        SettingsEntry(R.string.settings_item_app_language_title, 0, "Display"),
        SettingsEntry(R.string.settings_auto_update_title, R.string.settings_auto_update_detail, "App"),
        SettingsEntry(R.string.restart_player_title, R.string.restart_player_detail, "App"),
        SettingsEntry(R.string.diagnostic_export_title, R.string.diagnostic_export_detail, "App"),
        SettingsEntry(R.string.settings_section_playback, 0, "Playback"),
        SettingsEntry(R.string.settings_section_downloads, 0, "Downloads"),
        SettingsEntry(R.string.settings_section_lyrics, 0, "Lyrics"),
        SettingsEntry(R.string.settings_section_display, 0, "Display"),
        SettingsEntry(R.string.settings_section_data, 0, "Data"),
        SettingsEntry(R.string.settings_section_storage, 0, "Storage"),
        SettingsEntry(R.string.settings_section_app, 0, "App"),
        SettingsEntry(R.string.supporter_accent, 0, "Display"),
    )

    val classic: List<SettingsEntry> = listOf(
        SettingsEntry(R.string.settings_autoplay_title, R.string.settings_autoplay_detail, "0"),
        SettingsEntry(R.string.settings_release_notify, R.string.settings_release_notify_detail, "0"),
        SettingsEntry(R.string.myspoti_shuffle_title, R.string.myspoti_shuffle_detail, "0"),
        SettingsEntry(R.string.settings_keep_playing_title, R.string.settings_keep_playing_detail, "0"),
        SettingsEntry(R.string.settings_headset_skip_title, R.string.settings_headset_skip_detail, "0"),
        SettingsEntry(R.string.settings_connect_mirror_title, R.string.settings_connect_mirror_detail, "0"),
        SettingsEntry(R.string.settings_download_sync_title, R.string.settings_download_sync_detail, "0"),
        SettingsEntry(R.string.live_radio_settings_title, R.string.live_radio_settings_detail, "0"),
        SettingsEntry(R.string.energy_saver_title, R.string.energy_saver_summary, "0"),
        SettingsEntry(R.string.data_saver_title, R.string.data_saver_summary, "0"),
        SettingsEntry(R.string.background_playback_title, 0, "0"),
        SettingsEntry(R.string.lyrics_word_sync_title, 0, "2"),
        SettingsEntry(R.string.lyrics_extra_title, R.string.lyrics_extra_summary, "2"),
        SettingsEntry(R.string.settings_lyrics_keep_awake_title, R.string.settings_lyrics_keep_awake_detail, "2"),
        SettingsEntry(R.string.lyrics_romanization, R.string.settings_lyrics_romanization_detail, "2"),
        SettingsEntry(R.string.settings_download_lyrics_title, R.string.settings_download_lyrics_detail, "3"),
        SettingsEntry(R.string.settings_amoled_theme, R.string.settings_amoled_theme_subtitle, "4"),
        SettingsEntry(R.string.settings_item_canvas_title, 0, "4"),
        SettingsEntry(R.string.canvas_wifi_only, R.string.canvas_wifi_only_detail, "4"),
        SettingsEntry(R.string.settings_music_video_title, R.string.settings_music_video_detail, "4"),
        SettingsEntry(R.string.settings_player_lyrics_card_title, R.string.settings_player_lyrics_card_detail, "4"),
        SettingsEntry(R.string.settings_player_source_title, R.string.settings_player_source_detail, "4"),
        SettingsEntry(R.string.settings_solid_nav_title, R.string.settings_solid_nav_detail, "4"),
        SettingsEntry(R.string.settings_song_pip_title, R.string.settings_song_pip_detail, "4"),
        SettingsEntry(R.string.settings_hide_dj_title, R.string.settings_hide_dj_detail, "4"),
        SettingsEntry(R.string.settings_dynamic_color_title, R.string.settings_dynamic_color_detail, "4"),
        SettingsEntry(R.string.settings_system_font_title, R.string.settings_system_font_detail, "4"),
        SettingsEntry(R.string.settings_auto_update_title, R.string.settings_auto_update_detail, "5"),
        SettingsEntry(R.string.settings_section_playback, 0, "0"),
        SettingsEntry(R.string.settings_sources_tab, 0, "1"),
        SettingsEntry(R.string.extensions_title, 0, "1"),
        SettingsEntry(R.string.settings_section_lyrics, 0, "2"),
        SettingsEntry(R.string.settings_section_downloads, 0, "3"),
        SettingsEntry(R.string.settings_section_storage, 0, "3"),
        SettingsEntry(R.string.settings_tab_appearance, 0, "4"),
        SettingsEntry(R.string.settings_section_display, 0, "4"),
        SettingsEntry(R.string.supporter_accent, 0, "4"),
        SettingsEntry(R.string.settings_look_title, 0, "4"),
        SettingsEntry(R.string.settings_rotation_title, 0, "4"),
        SettingsEntry(R.string.settings_item_app_language_title, 0, "4"),
        SettingsEntry(R.string.settings_tab_about, 0, "5"),
        SettingsEntry(R.string.settings_section_app, 0, "5"),
        SettingsEntry(R.string.accounts_title, 0, "6"),
        SettingsEntry(R.string.settings_transitions_title, 0, "transitions"),
        SettingsEntry(R.string.equalizer_title, 0, "0"),
        SettingsEntry(R.string.settings_section_data, 0, "0"),
        SettingsEntry(R.string.settings_automix_segue, R.string.settings_automix_segue_detail, "transitions"),
        SettingsEntry(R.string.settings_automix_session, R.string.settings_automix_session_detail, "transitions"),
        SettingsEntry(R.string.settings_automix_album_order, R.string.settings_automix_album_order_detail, "transitions"),
        SettingsEntry(R.string.settings_automix_podcasts, R.string.settings_automix_podcasts_detail, "transitions"),
        SettingsEntry(R.string.settings_automix_harmonic, R.string.settings_automix_harmonic_detail, "transitions"),
        SettingsEntry(R.string.settings_automix_eq, R.string.settings_automix_eq_detail, "transitions"),
        SettingsEntry(R.string.settings_automix_show, R.string.settings_automix_show_detail, "transitions"),
        SettingsEntry(R.string.settings_automix_mobile_data, R.string.settings_automix_mobile_data_detail, "transitions"),
        SettingsEntry(R.string.settings_automix_cache, 0, "transitions"),
    )

    /** Lower case, without accents, so "lecture" finds "Lecture" and "ecran" finds "écran". */
    fun normalize(text: String): String =
        java.text.Normalizer.normalize(text.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")

    /** Every word of [query] is in the title or the description. */
    fun matches(query: String, title: String, detail: String): Boolean {
        val words = normalize(query).split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return false
        val text = normalize("$title $detail")
        return words.all { it in text }
    }

    /** The entries [query] finds, titles first, each title once; [text] reads a string resource. */
    fun search(entries: List<SettingsEntry>, query: String, text: (Int) -> String): List<SettingsEntry> {
        val hits = entries.filter { matches(query, text(it.title), if (it.detail != 0) text(it.detail) else "") }
        val q = normalize(query.trim())
        return hits.sortedBy { if (normalize(text(it.title)).contains(q)) 0 else 1 }.distinctBy { text(it.title) }
    }
}
