package com.music.spotui.ui.components

import android.content.Context
import android.content.Intent
import android.widget.Toast
import app.myspoti.engine.R

/**
 * Opens the device's own voice search for music: the exact intent the Google app itself answers
 * ("Search with your voice"), used as-is rather than building a speech-recognition UI of our own.
 * Shared by the Classic and Immersive Search screens' mic button. A no-op but a toast if nothing
 * on the device can handle it (no Google app installed).
 */
fun launchVoiceSearch(context: Context) {
    val intent = Intent("com.google.android.googlequicksearchbox.MUSIC_SEARCH")
        .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
        .onFailure { Toast.makeText(context, context.getString(R.string.voice_search_unavailable), Toast.LENGTH_SHORT).show() }
}
