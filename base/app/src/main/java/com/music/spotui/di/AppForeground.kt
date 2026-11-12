package com.music.spotui.di

/**
 * Whether the app is on screen, set by the activity when it starts and stops. The background
 * look-ups (lyrics, the extension status) run only while it is, or while a song plays.
 */
object AppForeground {
    @Volatile private var visible = false

    /** The app is in front. */
    val inForeground: Boolean get() = visible

    /** The activity came to the front. */
    fun onForeground() { visible = true }

    /** The activity went to the background. */
    fun onBackground() { visible = false }
}
