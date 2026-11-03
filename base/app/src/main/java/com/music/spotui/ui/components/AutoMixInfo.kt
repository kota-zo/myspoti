package com.music.spotui.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.myspoti.engine.R
import com.music.spotui.automix.AutoMix
import com.music.spotui.di.Graph

/**
 * The sentence saying why the next transition is not a mix ([AutoMix.whyNot]), for the Transitions
 * page and "Stats for nerds"; null when it is mixed, still being analysed, or AutoMix is off.
 * The player shows nothing more than the missing mix zone on its seek bar.
 */
fun autoMixReasonText(reason: AutoMix.Reason?): Int? = when (reason) {
    null, AutoMix.Reason.Off -> null
    AutoMix.Reason.SpotifyStream -> R.string.automix_reason_spotify
    AutoMix.Reason.Cast -> R.string.automix_reason_cast
    AutoMix.Reason.Podcast -> R.string.automix_reason_podcast
    AutoMix.Reason.Album -> R.string.automix_reason_album
    AutoMix.Reason.MobileData -> R.string.automix_reason_mobile_data
    AutoMix.Reason.Frugal -> R.string.automix_reason_frugal
    AutoMix.Reason.NotAnalyzed -> R.string.automix_reason_not_analyzed
}

/** The explanation shown once, the first time AutoMix is picked in Transitions (both looks). */
object AutoMixIntro {
    private const val KEY = "automix_intro_seen"
    var seen by mutableStateOf(Graph.app.getSharedPreferences("myspoti_prefs", 0).getBoolean(KEY, false))
        private set

    fun markSeen() {
        seen = true
        Graph.app.getSharedPreferences("myspoti_prefs", 0).edit().putBoolean(KEY, true).apply()
    }
}
