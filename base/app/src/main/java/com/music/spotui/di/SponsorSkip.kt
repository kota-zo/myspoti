package com.music.spotui.di

import android.content.Context
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.myspoti.host.android.MyspotiRuntime
import com.music.spotui.data.api.SponsorBlock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Skips the sponsor reads of podcast episodes, with the segments [SponsorBlock]'s listeners
 * marked (ported from EeveeSpotify's SponsorBlock skipper). While an episode with segments
 * plays, the player's clock is checked four times a second; inside a segment of a category
 * the listener chose, the player seeks to its end (or goes to the next item when the segment
 * runs to the end). A seek the listener makes into a segment lets that segment play.
 */
object SponsorSkip {
    private const val PREF = "settings_prefs"
    private const val KEY_ON = "sponsorblock_on"
    private const val KEY_CATEGORIES = "sponsorblock_categories"
    private const val EPISODE_PREFIX = "spotify:episode:"
    private const val TICK_MS = 250L
    /** The clock runs ahead of the player between two states: never seek twice in a row. */
    private const val COOLDOWN_MS = 1200L

    private val prefs get() = Graph.app.getSharedPreferences(PREF, Context.MODE_PRIVATE)
    private val scope get() = MyspotiRuntime.scope

    var enabled by mutableStateOf(prefs.getBoolean(KEY_ON, true))
        private set
    var categories by mutableStateOf(prefs.getStringSet(KEY_CATEGORIES, null)?.toSet() ?: SponsorBlock.DEFAULT_CATEGORIES)
        private set
    /** The segments of the episode playing now (empty for a song), for the seek bar. */
    var segments by mutableStateOf<List<SponsorBlock.Segment>>(emptyList())
        private set

    @Volatile private var episodeId = ""
    private val cache = ConcurrentHashMap<String, List<SponsorBlock.Segment>>()
    private val letThrough: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private var fetchJob: Job? = null
    private var loop: Job? = null
    @Volatile private var skipping = false
    private var lastSkipAt = 0L

    fun turn(on: Boolean) {
        enabled = on
        prefs.edit().putBoolean(KEY_ON, on).apply()
        stop()
        if (on && episodeId.isNotEmpty()) load(episodeId)
    }

    fun setCategory(category: String, on: Boolean) {
        categories = if (on) categories + category else categories - category
        prefs.edit().putStringSet(KEY_CATEGORIES, categories).apply()
    }

    /** From the player state: the item playing now. */
    fun onItem(uri: String) {
        val id = if (uri.startsWith(EPISODE_PREFIX)) uri.removePrefix(EPISODE_PREFIX) else ""
        if (id == episodeId) return
        episodeId = id
        letThrough.clear()
        stop()
        if (id.isNotEmpty() && enabled) load(id)
    }

    /** A seek: one the listener makes into a segment means they want to hear it. */
    fun onSeek(ms: Long) {
        if (skipping) return
        segments.filter { ms >= it.startMs && ms < it.endMs }.forEach { letThrough += it.uuid }
    }

    private fun stop() {
        fetchJob?.cancel(); fetchJob = null
        loop?.cancel(); loop = null
        segments = emptyList()
    }

    private fun load(id: String) {
        cache[id]?.let { start(it); return }
        fetchJob = scope.launch {
            val found = withContext(Dispatchers.IO) { SponsorBlock.fetch(id) } ?: return@launch
            cache[id] = found
            if (id == episodeId && enabled) start(found)
        }
    }

    private fun start(found: List<SponsorBlock.Segment>) {
        segments = found
        if (found.isEmpty()) return
        loop = scope.launch {
            while (isActive) {
                tick()
                delay(TICK_MS)
            }
        }
    }

    private fun tick() {
        if (LocalPlayer.active.value || SongPlayer.isBuffering || !SongPlayer.isPlaying()) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastSkipAt < COOLDOWN_MS) return
        val segment = SponsorBlock.segmentAt(segments, SongPlayer.getCurrentPosition(), categories, letThrough) ?: return
        lastSkipAt = now
        val duration = SongPlayer.getDuration()
        skipping = true
        try {
            if (duration > 0 && segment.endMs >= duration - 1000) SongPlayer.next()
            else SongPlayer.seekTo(segment.endMs + 50)
        } finally {
            skipping = false
        }
        val ctx = Graph.app
        android.widget.Toast.makeText(
            ctx,
            ctx.getString(app.myspoti.engine.R.string.sponsorblock_skipped, ctx.getString(label(segment.category))),
            android.widget.Toast.LENGTH_SHORT,
        ).show()
    }

    /** The name of a category, as Settings shows it. */
    fun label(category: String): Int = when (category) {
        "sponsor" -> app.myspoti.engine.R.string.sponsorblock_cat_sponsor
        "selfpromo" -> app.myspoti.engine.R.string.sponsorblock_cat_selfpromo
        "interaction" -> app.myspoti.engine.R.string.sponsorblock_cat_interaction
        "intro" -> app.myspoti.engine.R.string.sponsorblock_cat_intro
        "outro" -> app.myspoti.engine.R.string.sponsorblock_cat_outro
        "preview" -> app.myspoti.engine.R.string.sponsorblock_cat_preview
        else -> app.myspoti.engine.R.string.sponsorblock_cat_filler
    }

    /** The seek bar's colour for a category: SponsorBlock's own. */
    fun color(category: String): Long = when (category) {
        "sponsor" -> 0xFF00D400
        "selfpromo" -> 0xFFFFFF00
        "interaction" -> 0xFFCC00FF
        "intro" -> 0xFF00FFFF
        "outro" -> 0xFF0202ED
        "preview" -> 0xFF008FD6
        else -> 0xFF7300FF
    }
}
