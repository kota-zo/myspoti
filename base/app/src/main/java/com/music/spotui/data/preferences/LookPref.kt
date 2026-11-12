package com.music.spotui.data.preferences

/*
 * The app's look: Classic (today's) or Immersive, picked during the onboarding.
 */

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** The two looks. [CLASSIC] is the default, so nothing changes for anyone who has not chosen. */
enum class Look {
    CLASSIC, IMMERSIVE;

    companion object {
        fun of(name: String?): Look = entries.firstOrNull { it.name == name } ?: CLASSIC
    }
}

object LookPref {
    private const val PREF = "settings_prefs"
    private const val KEY = "look"
    private const val KEY_CHOSEN = "look_chosen"
    private const val KEY_OFFERED = "look_offered"

    /** The chosen look, as Compose state: everything reading it redraws when it changes. */
    var look by mutableStateOf(Look.of(prefs(com.music.spotui.di.Graph.app).getString(KEY, null)))
        private set

    fun set(c: Context, value: Look) {
        look = value
        prefs(c).edit().putString(KEY, value.name).apply()
    }

    /** The look step of the onboarding was seen (new installs and updates alike). */
    fun isChosen(c: Context): Boolean = prefs(c).getBoolean(KEY_CHOSEN, false)
    fun setChosen(c: Context) {
        prefs(c).edit().putBoolean(KEY_CHOSEN, true).putBoolean(KEY_OFFERED, true).apply()
        offered = true
    }

    /**
     * The new-look popup was shown to an existing user (or the onboarding look step covered it), as
     * Compose state. The activity kept its own copy, read once at its start: the look step of the
     * onboarding saved "offered" but the copy stayed false, and the popup came up as soon as the
     * onboarding ended, asking the look a second time.
     */
    var offered by mutableStateOf(prefs(com.music.spotui.di.Graph.app).getBoolean(KEY_OFFERED, false))
        private set
    fun isOffered(c: Context): Boolean = prefs(c).getBoolean(KEY_OFFERED, false)
    fun setOffered(c: Context) {
        prefs(c).edit().putBoolean(KEY_OFFERED, true).apply()
        offered = true
    }
    fun clearOffered(c: Context) {
        prefs(c).edit().remove(KEY_OFFERED).apply()
        offered = false
    }

    private fun prefs(c: Context) = c.getSharedPreferences(PREF, Context.MODE_PRIVATE)
}
