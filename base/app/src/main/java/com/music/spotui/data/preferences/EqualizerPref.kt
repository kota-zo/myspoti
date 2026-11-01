package com.music.spotui.data.preferences

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.music.spotui.di.EqBands
import com.music.spotui.di.EqTuning
import com.music.spotui.di.Equalizer

/** The equalizer's ready-made curves: ten band gains and the preamp that keeps them under full scale, in dB. */
enum class EqPreset(val gains: FloatArray, val preamp: Float) {
    FLAT(floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f), 0f),
    BASS_BOOST(floatArrayOf(6f, 5f, 4f, 2f, 0f, 0f, 0f, 0f, 0f, 0f), -5f),
    BASS_CUT(floatArrayOf(-6f, -5f, -4f, -2f, 0f, 0f, 0f, 0f, 0f, 0f), 0f),
    TREBLE_BOOST(floatArrayOf(0f, 0f, 0f, 0f, 0f, 1f, 2f, 4f, 5f, 6f), -5f),
    TREBLE_CUT(floatArrayOf(0f, 0f, 0f, 0f, 0f, -1f, -2f, -4f, -5f, -6f), 0f),
    VOCAL(floatArrayOf(-2f, -2f, -1f, 0f, 2f, 4f, 4f, 2f, 0f, -1f), -3f),
    ROCK(floatArrayOf(5f, 4f, 2f, -1f, -2f, -1f, 1f, 3f, 4f, 5f), -4f),
    POP(floatArrayOf(-1f, 1f, 3f, 4f, 3f, 0f, -1f, -1f, 1f, 2f), -3f),
    JAZZ(floatArrayOf(3f, 2f, 1f, 2f, -1f, -1f, 0f, 1f, 2f, 3f), -2f),
    CLASSICAL(floatArrayOf(3f, 2f, 1f, 0f, 0f, 0f, -1f, -1f, 1f, 2f), -2f),
    ELECTRONIC(floatArrayOf(5f, 4f, 1f, 0f, -2f, 1f, 0f, 1f, 4f, 5f), -4f),
    HIP_HOP(floatArrayOf(5f, 4f, 2f, 3f, -1f, -1f, 1f, -1f, 2f, 3f), -4f),
    ACOUSTIC(floatArrayOf(4f, 4f, 3f, 1f, 2f, 1f, 3f, 3f, 3f, 2f), -4f),
    SPOKEN_WORD(floatArrayOf(-4f, -3f, 0f, 1f, 3f, 4f, 4f, 3f, 1f, -2f), -3f),
    /** The user's own curve (a band or the preamp moved by hand). */
    CUSTOM(FloatArray(10), 0f),
}

/**
 * The equalizer, speed and silence skipping of the local player (ExoPlayer: extensions' streams,
 * downloads, the phone's files). Spotify's own songs play in the Web Player, whose decoded sound
 * the app never gets: none of this applies to them, nor to a Cast device. Kept across launches.
 *
 * Speed: 0.5× to 2× for everything the local player plays, 1× by default (podcasts streamed by
 * Spotify keep their own speed, the podcast player's). Off 1×, Spotify does not follow the song
 * (see ConnectMirror): its page plays at 1× only.
 */
object EqualizerPref {
    private const val PREF = "equalizer_prefs"
    private fun prefs(c: Context) = c.getSharedPreferences(PREF, Context.MODE_PRIVATE)
    private val p get() = prefs(com.music.spotui.di.Graph.app)

    val SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)

    var enabled by mutableStateOf(p.getBoolean("enabled", false))
        private set
    var preset by mutableStateOf(runCatching { EqPreset.valueOf(p.getString("preset", null) ?: "FLAT") }.getOrDefault(EqPreset.FLAT))
        private set
    private var customGains by mutableStateOf(FloatArray(EqBands.COUNT) { p.getFloat("gain$it", 0f) })
    private var customPreamp by mutableStateOf(p.getFloat("preamp", 0f))
    var speed by mutableStateOf(p.getFloat("speed", 1f).coerceIn(0.5f, 2f))
        private set
    var skipSilence by mutableStateOf(p.getBoolean("skip_silence", false))
        private set

    /** The gains shown and heard: the preset's, or the user's own. */
    val gains: FloatArray get() = if (preset == EqPreset.CUSTOM) customGains else preset.gains
    val preamp: Float get() = if (preset == EqPreset.CUSTOM) customPreamp else preset.preamp

    init { push() }

    fun setEnabled(c: Context, on: Boolean) {
        enabled = on
        prefs(c).edit().putBoolean("enabled", on).apply()
        push()
    }

    fun setPreset(c: Context, value: EqPreset) {
        // Custom picked from the list: the curve last made by hand.
        preset = value
        prefs(c).edit().putString("preset", value.name).apply()
        push()
    }

    /**
     * A band moved by hand: the curve shown becomes the user's own, from where it was. Heard at
     * once; kept when [save] (a slider saves once, at the end of its drag, see [save]).
     */
    fun setGain(c: Context, band: Int, db: Float, save: Boolean = true) {
        if (band !in 0 until EqBands.COUNT) return
        val g = gains.copyOf().also { it[band] = db.coerceIn(EqBands.MIN_DB, EqBands.MAX_DB) }
        custom(c, g, preamp, save)
    }

    fun setPreamp(c: Context, db: Float, save: Boolean = true) =
        custom(c, gains.copyOf(), db.coerceIn(EqBands.PREAMP_MIN_DB, EqBands.PREAMP_MAX_DB), save)

    private fun custom(c: Context, g: FloatArray, pre: Float, save: Boolean) {
        customGains = g; customPreamp = pre; preset = EqPreset.CUSTOM
        push()
        if (save) save(c)
    }

    /** Keeps the user's own curve as it is now. */
    fun save(c: Context) {
        prefs(c).edit().apply {
            customGains.forEachIndexed { i, v -> putFloat("gain$i", v) }
            putFloat("preamp", customPreamp); putString("preset", preset.name)
        }.apply()
    }

    fun setSpeed(c: Context, value: Float) {
        speed = value.coerceIn(0.5f, 2f)
        prefs(c).edit().putFloat("speed", speed).apply()
        com.music.spotui.di.LocalPlayer.applyPlayback()
        // Spotify's page plays at 1× only: the mirror stops off 1×, and starts again at 1×.
        com.music.spotui.di.ConnectMirror.settingChanged()
    }

    fun setSkipSilence(c: Context, on: Boolean) {
        skipSilence = on
        prefs(c).edit().putBoolean("skip_silence", on).apply()
        com.music.spotui.di.LocalPlayer.applyPlayback()
    }

    /** Hands the curve to the players' equalizers (read on their audio threads). */
    private fun push() {
        Equalizer.current = if (enabled) EqTuning(gains, preamp) else null
    }
}
