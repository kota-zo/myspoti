package com.music.spotui.data.preferences

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

// Trimmed from Spotui: stream quality, YouTube and web-playback settings are gone (the Web
// Player engine streams Spotify's own audio). Crossfade is ours: a fade, not an overlap.

private const val PREF = "settings_prefs"
private const val KEY_LIBRARY_GRID = "library_grid_view"
private const val KEY_LYRIC_TRANSLATE_LANG = "lyric_translate_lang"
private const val KEY_LYRICS_EXTRA = "lyrics_extra_sources"
private const val KEY_CROSSFADE = "crossfade_seconds"

/** Extra lyrics sources (NetEase, Genius) when Spotify and LRCLIB have none. Off by default. */
fun isLyricsExtraSources(c: Context): Boolean = prefs(c).getBoolean(KEY_LYRICS_EXTRA, false)
fun setLyricsExtraSources(c: Context, v: Boolean) = prefs(c).edit().putBoolean(KEY_LYRICS_EXTRA, v).apply()

/** Lyrics timed word by word first (karaoke); off: line by line, Spotify's first. */
fun isLyricsWordSync(c: Context): Boolean = prefs(c).getBoolean("lyrics_word_sync", true)
fun setLyricsWordSync(c: Context, v: Boolean) = prefs(c).edit().putBoolean("lyrics_word_sync", v).apply()

private fun prefs(c: Context) = c.getSharedPreferences(PREF, Context.MODE_PRIVATE)

/**
 * "Show romanization" under the lyrics' lines (the song in Latin letters), offered when the lyrics
 * carry one. Off by default; read as state by both looks, so the menu and the lines agree.
 */
object LyricsRomanizationPref {
    private const val KEY = "lyrics_romanization"
    var on by androidx.compose.runtime.mutableStateOf(prefs(com.music.spotui.di.Graph.app).getBoolean(KEY, false))
        private set

    fun set(c: Context, value: Boolean) {
        on = value
        prefs(c).edit().putBoolean(KEY, value).apply()
    }
}

/** The bottom bar: fading into the list (the default), or solid black. Read by the bar as state. */
object SolidNavBar {
    private const val KEY = "solid_nav_bar"
    var on by androidx.compose.runtime.mutableStateOf(prefs(com.music.spotui.di.Graph.app).getBoolean(KEY, false))
        private set

    fun set(c: Context, value: Boolean) {
        on = value
        prefs(c).edit().putBoolean(KEY, value).apply()
    }
}

/** "When your music ends, keep playing similar songs" (on by default). Read by the player. */
object AutoplayPref {
    private const val KEY = "autoplay_similar"
    var on by androidx.compose.runtime.mutableStateOf(prefs(com.music.spotui.di.Graph.app).getBoolean(KEY, true))
        private set

    fun set(c: Context, value: Boolean) {
        on = value
        prefs(c).edit().putBoolean(KEY, value).apply()
    }
}

/**
 * "Myspoti shuffle" (off by default): with shuffle on, a list plays every one of its songs once, in
 * an order Myspoti draws, before it starts over, instead of Spotify's shuffle (see
 * [com.music.spotui.di.AppShuffle]). Off: Spotify shuffles, as before.
 */
object AppShufflePref {
    private const val KEY = "myspoti_shuffle"
    var on by androidx.compose.runtime.mutableStateOf(prefs(com.music.spotui.di.Graph.app).getBoolean(KEY, false))
        private set

    fun set(c: Context, value: Boolean) {
        on = value
        prefs(c).edit().putBoolean(KEY, value).apply()
        if (!value) com.music.spotui.di.AppShuffle.forget()
    }
}

/**
 * "Count extension plays on Spotify" (on by default): a song an extension streams is played by the
 * Web Player too, unheard, so Spotify counts it and Spotify Connect sees and controls it (see
 * [com.music.spotui.di.ConnectMirror]). Off: the extension's stream alone, as before.
 */
object ConnectMirrorPref {
    private const val KEY = "connect_mirror"
    var on by androidx.compose.runtime.mutableStateOf(prefs(com.music.spotui.di.Graph.app).getBoolean(KEY, true))
        private set

    fun set(c: Context, value: Boolean) {
        on = value
        prefs(c).edit().putBoolean(KEY, value).apply()
        com.music.spotui.di.ConnectMirror.settingChanged()
    }
}

/**
 * "Sync downloaded songs with Spotify" (on by default): a downloaded song with a Spotify id, played
 * from its file while the phone is online, is played by the Web Player too, unheard, as an
 * extension's stream is (see [com.music.spotui.di.ConnectMirror]): Spotify counts it, Spotify Connect
 * sees and controls it. Off, or offline: the file plays alone, as before.
 */
object DownloadSyncPref {
    private const val KEY = "download_spotify_sync"
    var on by androidx.compose.runtime.mutableStateOf(prefs(com.music.spotui.di.Graph.app).getBoolean(KEY, true))
        private set

    fun set(c: Context, value: Boolean) {
        on = value
        prefs(c).edit().putBoolean(KEY, value).apply()
        com.music.spotui.di.ConnectMirror.settingChanged()
    }
}

/**
 * The automatic update check at launch (on by default: the app behaved that way before this
 * option existed). Off means no check when the app starts — a check asked for in Settings still
 * works, it reads the same release list.
 */
object AutoUpdateCheckPref {
    private const val KEY = "auto_update_check"
    var on by androidx.compose.runtime.mutableStateOf(prefs(com.music.spotui.di.Graph.app).getBoolean(KEY, true))
        private set

    fun set(c: Context, value: Boolean) {
        on = value
        prefs(c).edit().putBoolean(KEY, value).apply()
    }
}

/** Screen rotation: portrait (the default), automatic (follows the system), or landscape. */
object OrientationPref {
    private const val KEY = "screen_orientation"
    const val PORTRAIT = "portrait"
    const val AUTO = "auto"
    const val LANDSCAPE = "landscape"
    var mode by androidx.compose.runtime.mutableStateOf(prefs(com.music.spotui.di.Graph.app).getString(KEY, PORTRAIT) ?: PORTRAIT)
        private set

    fun set(c: Context, value: String) {
        mode = value
        prefs(c).edit().putString(KEY, value).apply()
    }

    /** The [android.content.pm.ActivityInfo] value for the chosen mode. */
    fun requested(): Int = when (mode) {
        AUTO -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        LANDSCAPE -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        else -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    }
}

/** Library layout: false = rows (default), true = Spotify-style 3-column grid. */
fun isLibraryGridView(c: Context): Boolean = prefs(c).getBoolean(KEY_LIBRARY_GRID, false)
fun setLibraryGridView(c: Context, v: Boolean) = prefs(c).edit().putBoolean(KEY_LIBRARY_GRID, v).apply()

/**
 * Crossfade length in seconds, 0 = off (the default: nothing changes unless asked for).
 *
 * It is a fade, not an overlap: the page plays one media element, so the next song cannot
 * start before this one ends. The song fades out over its last N seconds, the next one fades
 * in. The value is Compose state, so the settings row redraws when it changes.
 */
object CrossfadePref {
    const val MAX = 12
    var seconds by androidx.compose.runtime.mutableStateOf(
        prefs(com.music.spotui.di.Graph.app).getInt(KEY_CROSSFADE, 0).coerceIn(0, MAX),
    )
        private set

    /** Saves it and hands it to the player: the change applies to the song playing now. */
    fun set(c: Context, value: Int) {
        val v = value.coerceIn(0, MAX)
        seconds = v
        prefs(c).edit().putInt(KEY_CROSSFADE, v).apply()
        applyCrossfade(v)
    }
}

/**
 * "Music videos": the video button in the player, the lookup behind it and a clip that starts
 * by itself on the next song. On by default; off, the player is audio (and Canvas) only.
 */
object MusicVideoPref {
    private const val KEY = "music_video_enabled"
    var on by androidx.compose.runtime.mutableStateOf(prefs(com.music.spotui.di.Graph.app).getBoolean(KEY, true))
        private set

    fun set(c: Context, value: Boolean) {
        on = value
        prefs(c).edit().putBoolean(KEY, value).apply()
        if (!value) com.music.spotui.video.MusicVideoController.setOn(false)
    }
}

/**
 * "Hide the DJ" on Home (off by default): Spotify's DJ, which comes in the home feed as a
 * playlist card, leaves the shelves. Read by both Home screens as state.
 */
object HideDjPref {
    private const val KEY = "hide_dj"
    var on by androidx.compose.runtime.mutableStateOf(prefs(com.music.spotui.di.Graph.app).getBoolean(KEY, false))
        private set

    fun set(c: Context, value: Boolean) {
        on = value
        prefs(c).edit().putBoolean(KEY, value).apply()
    }
}

fun getCrossfadeSeconds(c: Context): Int =
    prefs(c).getInt(KEY_CROSSFADE, 0).coerceIn(0, CrossfadePref.MAX)

/** Gives the saved length to the player (at start, and at every change); 0 stops a fade at once. */
fun applyCrossfade(seconds: Int) {
    val runtime = app.myspoti.host.android.MyspotiRuntime
    if (!runtime.isStarted) return
    runtime.spotify.player.crossfadeSeconds = seconds
}

fun getLyricTranslateLang(c: Context): String =
    prefs(c).getString(KEY_LYRIC_TRANSLATE_LANG, "").orEmpty()

fun setLyricTranslateLang(c: Context, code: String) =
    prefs(c).edit().putString(KEY_LYRIC_TRANSLATE_LANG, code.trim()).apply()

/**
 * Liquid Glass in the Immersive look (Settings > Display, under Look): the floating bars refract
 * the page through a lens instead of only frosting it. On by default; it only takes effect on
 * Android 12+ and when the phone does not ask for less blur or motion (see
 * [com.music.spotui.ui.immersive.shell.rememberLiquidGlassActive]).
 */
object LiquidGlassPref {
    private const val KEY = "immersive_liquid_glass"
    var on by androidx.compose.runtime.mutableStateOf(prefs(com.music.spotui.di.Graph.app).getBoolean(KEY, true))
        private set

    fun set(c: Context, value: Boolean) {
        on = value
        prefs(c).edit().putBoolean(KEY, value).apply()
    }
}

/**
 * "Lyrics preview in the player" (Settings > Display, Classic look): the lyrics card under the
 * player. On by default; off, the lyrics still open with a flick up on the cover.
 */
object PlayerLyricsCardPref {
    private const val KEY = "player_lyrics_card"
    var on by androidx.compose.runtime.mutableStateOf(prefs(com.music.spotui.di.Graph.app).getBoolean(KEY, true))
        private set

    fun set(c: Context, value: Boolean) {
        on = value
        prefs(c).edit().putBoolean(KEY, value).apply()
    }
}

/**
 * "Source and quality in the player" (Settings > Display, both looks): the line that says what
 * plays and how (under the title in the Immersive look, under the device in the Classic one). On
 * by default; off, the player's menu has the Sound quality row (and Stats for nerds) instead.
 */
object PlayerSourceLinePref {
    private const val KEY = "player_source_line"
    var on by androidx.compose.runtime.mutableStateOf(prefs(com.music.spotui.di.Graph.app).getBoolean(KEY, true))
        private set

    fun set(c: Context, value: Boolean) {
        on = value
        prefs(c).edit().putBoolean(KEY, value).apply()
    }
}

/**
 * "Capsule in the player" (Settings > Display, Immersive look): the capsule at the bottom of the
 * player (devices, with shuffle and repeat when [PlayerModesPref] is on). On by default; off, the
 * player's menu has a Listen on row for the devices.
 */
object PlayerCapsulePref {
    private const val KEY = "immersive_player_capsule"
    var on by androidx.compose.runtime.mutableStateOf(prefs(com.music.spotui.di.Graph.app).getBoolean(KEY, true))
        private set

    fun set(c: Context, value: Boolean) {
        on = value
        prefs(c).edit().putBoolean(KEY, value).apply()
    }
}

/**
 * "Shuffle and repeat in the player" (Settings > Display, Immersive look): on, the player's
 * bottom capsule holds shuffle, repeat and devices (2.2); off, only devices, as in 2.1.0 (the
 * queue panel keeps shuffle and repeat). Off by default: the owner's call, and asked on Discord.
 */
object PlayerModesPref {
    private const val KEY = "immersive_player_modes"
    var on by androidx.compose.runtime.mutableStateOf(prefs(com.music.spotui.di.Graph.app).getBoolean(KEY, false))
        private set

    fun set(c: Context, value: Boolean) {
        on = value
        prefs(c).edit().putBoolean(KEY, value).apply()
    }
}
