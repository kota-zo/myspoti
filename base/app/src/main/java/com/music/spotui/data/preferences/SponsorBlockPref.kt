package com.music.spotui.data.preferences

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.music.spotui.di.Graph
import com.music.spotui.podcast.SponsorSegments

/**
 * SponsorBlock for podcasts ([com.music.spotui.podcast.SponsorSkip]): off by default, since it
 * asks sponsor.ajay.app (and YouTube) about the episodes played. Which parts it skips: sponsor
 * reads on, the rest off until chosen.
 */
object SponsorBlockPref {
    private const val PREF = "sponsorblock"
    private fun prefs(c: Context) = c.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    var on by mutableStateOf(prefs(Graph.app).getBoolean("on", false))
        private set
    var sponsor by mutableStateOf(prefs(Graph.app).getBoolean("sponsor", true))
        private set
    var selfPromo by mutableStateOf(prefs(Graph.app).getBoolean("selfpromo", false))
        private set
    var introOutro by mutableStateOf(prefs(Graph.app).getBoolean("intro_outro", false))
        private set
    var interaction by mutableStateOf(prefs(Graph.app).getBoolean("interaction", false))
        private set

    fun set(c: Context, value: Boolean) { on = value; prefs(c).edit().putBoolean("on", value).apply() }
    fun setSponsor(c: Context, value: Boolean) { sponsor = value; prefs(c).edit().putBoolean("sponsor", value).apply() }
    fun setSelfPromo(c: Context, value: Boolean) { selfPromo = value; prefs(c).edit().putBoolean("selfpromo", value).apply() }
    fun setIntroOutro(c: Context, value: Boolean) { introOutro = value; prefs(c).edit().putBoolean("intro_outro", value).apply() }
    fun setInteraction(c: Context, value: Boolean) { interaction = value; prefs(c).edit().putBoolean("interaction", value).apply() }

    /** The categories to skip, as the server names them. */
    fun categories(): Set<String> = buildSet {
        if (sponsor) add(SponsorSegments.SPONSOR)
        if (selfPromo) add(SponsorSegments.SELF_PROMO)
        if (introOutro) { add(SponsorSegments.INTRO); add(SponsorSegments.OUTRO) }
        if (interaction) add(SponsorSegments.INTERACTION)
    }
}
