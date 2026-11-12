package com.music.spotui.data.preferences

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Settings > Library: the pinned "Local Files" row of the Library, shown unless turned off (asked
 * on Discord: "I do not have local files on my phone"). Hiding it touches no file and no playlist.
 */
object LocalFilesPref {
    private const val PREF = "library_prefs"
    private const val KEY = "local_files_shown"

    var shown by mutableStateOf(true)
        private set

    fun init(context: Context) {
        shown = context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean(KEY, true)
    }

    fun set(context: Context, value: Boolean) {
        shown = value
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putBoolean(KEY, value).apply()
    }
}
