package com.music.spotui.radio

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/**
 * "Live Radio" (Settings > Playback): on adds the pinned Library row under Local Files; off hides
 * it and stops anything playing. Off by default — an opt-in, not something that appears unasked.
 * Its own tiny prefs file, not [com.music.spotui.data.preferences.SettingsPref]'s, so the whole
 * `radio` package stays removable without touching the app's other data files.
 */
object RadioFeaturePref {
    private const val PREF = "live_radio_prefs"
    private const val KEY = "live_radio_enabled"

    /** The pinned Library row's sentinel id (parallel to Api.HomeCache.LOCAL_FILES_ID / DOWNLOADS_ID). */
    const val LIBRARY_ENTRY_ID = "live radio"

    private fun prefs(c: Context) = c.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    var on by androidx.compose.runtime.mutableStateOf(false)
        private set

    fun init(context: Context) {
        on = prefs(context).getBoolean(KEY, false)
    }

    fun set(context: Context, value: Boolean) {
        on = value
        prefs(context).edit().putBoolean(KEY, value).apply()
        if (!value) RadioPlayer.stop()
    }
}
