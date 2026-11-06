package com.music.spotui.data.preferences

/*
 * Download preferences: availability and quality.
 */

import android.content.Context

// The stored file keeps its first name: renaming it would reset everyone's settings.
private const val PREF = "LosslessPref"
private const val KEY_HI_RES = "hires_24bit"
private const val KEY_ENABLED = "enabled"
private const val KEY_SMALL = "small_aac"
private const val KEY_ASK = "ask_each_time"

/**
 * Hi-res downloads (24-bit when the source has it: bigger files, ~3× a CD-quality FLAC).
 * Default off: CD quality is asked for.
 */
fun isHiRes(context: Context): Boolean = ProfilePrefs.getBoolean(prefs(context), KEY_HI_RES, false)

fun setHiRes(context: Context, on: Boolean) = prefs(context).edit().putBoolean(ProfilePrefs.key(KEY_HI_RES), on).apply()

/** Downloads on. Off: no downloads at all, "Downloaded" becomes Local Files only. */
fun isDownloadsEnabled(context: Context): Boolean = ProfilePrefs.getBoolean(prefs(context), KEY_ENABLED, true)

fun setDownloadsEnabled(context: Context, on: Boolean) = prefs(context).edit().putBoolean(ProfilePrefs.key(KEY_ENABLED), on).apply()

/** Downloads kept small: the checked FLAC re-encoded to AAC on the phone. */
fun isSmallDownloads(context: Context): Boolean = ProfilePrefs.getBoolean(prefs(context), KEY_SMALL, false)

fun setSmallDownloads(context: Context, on: Boolean) = prefs(context).edit().putBoolean(ProfilePrefs.key(KEY_SMALL), on).apply()

/** The quality is asked at each download (once for a whole list). */
fun isAskDownloadQuality(context: Context): Boolean = ProfilePrefs.getBoolean(prefs(context), KEY_ASK, false)

fun setAskDownloadQuality(context: Context, on: Boolean) = prefs(context).edit().putBoolean(ProfilePrefs.key(KEY_ASK), on).apply()

private fun prefs(c: Context) = c.getSharedPreferences(PREF, Context.MODE_PRIVATE)
