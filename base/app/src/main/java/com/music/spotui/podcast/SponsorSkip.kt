package com.music.spotui.podcast

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.myspoti.host.android.Logbook
import app.myspoti.host.android.MyspotiRuntime
import com.music.spotui.data.preferences.SponsorBlockPref
import com.music.spotui.di.Graph
import com.music.spotui.di.SongPlayer
import com.music.spotui.video.YouTubeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import okhttp3.Request

/**
 * SponsorBlock for podcasts: while an episode plays, the parts the SponsorBlock community marked
 * (sponsor reads, and the other categories chosen in the settings) are skipped, each once, with
 * an Undo ([justSkipped], shown by [SponsorSkipUndo]).
 *
 * The episode is asked by its Spotify id first (the server keeps Spotify episodes too); when it
 * has nothing, a YouTube upload of the same episode (same length within 2 s, the same title and
 * show) is asked by its video id. Both by hash prefix: only 4 hex characters of the id's SHA-256
 * leave the phone, with no account, key or user id. Results are kept for the session.
 */
object SponsorSkip {
    private const val TAG = "SponsorSkip"
    private const val SERVER = "https://sponsor.ajay.app"
    /** Between checks while an episode with segments plays (a segment is entered within it). */
    private const val CHECK_MS = 500L
    /** Between checks while nothing is to be skipped (off, music, paused, nothing ahead). */
    private const val IDLE_MS = 3_000L
    /** Episodes whose answer is kept. */
    private const val CACHE_SIZE = 48
    /** A failed lookup (no network) is tried again after that. */
    private const val RETRY_MS = 60_000L

    /** The last skip, for the Undo; null once it is shown or dismissed. */
    internal class Skipped(val segment: SponsorSegments.Segment, val from: Long)
    internal var justSkipped by mutableStateOf<Skipped?>(null)

    /** Each episode's segments (all categories; the settings filter them when they are used). */
    private val cache = object : LinkedHashMap<String, List<SponsorSegments.Segment>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<SponsorSegments.Segment>>?) = size > CACHE_SIZE
    }
    private var lookup: Job? = null
    private var lookupUri = ""
    private var failedAt = 0L
    private var trackerUri = ""
    private var trackerKey = ""
    private var tracker: SkipTracker? = null

    fun init() {
        MyspotiRuntime.scope.launch(Dispatchers.Main) {
            while (true) delay(runCatching { tick() }.getOrElse { Log.w(TAG, "check failed: ${it.message}"); IDLE_MS })
        }
    }

    /** One check, on the main thread; returns how long to wait before the next one. */
    private fun tick(): Long {
        if (!SponsorBlockPref.on) return IDLE_MS
        val uri = SongPlayer.nowPlaying.value.uri
        if (!uri.startsWith("spotify:episode:")) return IDLE_MS
        val duration = SongPlayer.getDuration()
        val segments = synchronized(cache) { cache[uri] }
        if (segments == null) {
            startLookup(uri, duration)
            return IDLE_MS
        }
        val categories = SponsorBlockPref.categories()
        // Rebuilt when the episode, its length (known after it starts) or the categories change;
        // the same episode keeps what it skipped.
        val key = "$duration|${categories.sorted()}|${segments.size}"
        if (uri != trackerUri || key != trackerKey) {
            val keep = uri == trackerUri
            val usable = SponsorSegments.usable(segments, duration, categories)
            tracker = if (keep && tracker?.segments == usable) tracker else SkipTracker(usable)
            trackerUri = uri
            trackerKey = key
        }
        val t = tracker ?: return IDLE_MS
        if (t.segments.isEmpty() || !SongPlayer.isPlaying() || SongPlayer.isBuffering || SongPlayer.adPlaying) return IDLE_MS
        val position = SongPlayer.getCurrentPosition()
        val segment = t.due(position)
        if (segment != null) {
            // The episode's very end ends it: a segment up to there lands just before it.
            val target = if (duration > 0) minOf(segment.endMs, duration - 1_000L) else segment.endMs
            if (target > position) {
                SongPlayer.seekTo(target)
                justSkipped = Skipped(segment, position)
                Logbook.log("sponsorblock: skipped ${segment.category} ${position / 1000}s -> ${target / 1000}s")
            }
            return CHECK_MS
        }
        val next = t.untilNext(position) ?: return IDLE_MS
        return next.coerceIn(CHECK_MS, IDLE_MS)
    }

    /** Goes back to where the last skip started. */
    internal fun undo(skipped: Skipped) {
        SongPlayer.seekTo(skipped.from)
    }

    private fun startLookup(uri: String, durationMs: Long) {
        if (lookup?.isActive == true && lookupUri == uri) return
        if (uri == lookupUri && System.currentTimeMillis() - failedAt < RETRY_MS) return
        if (com.music.spotui.data.DataSaver.active) return
        // The length decides the YouTube match: wait for it.
        if (durationMs <= 0) return
        val state = Graph.currentSongState
        val title = state.title.value
        val show = state.album.value.ifBlank { state.singer.value }
        lookup?.cancel()
        lookupUri = uri
        failedAt = 0L
        lookup = MyspotiRuntime.scope.launch(Dispatchers.IO) {
            val id = uri.substringAfterLast(':')
            val found = runCatching { segmentsFor(id, title, show, durationMs) }
                .onFailure { Log.w(TAG, "lookup failed: ${it.message}") }
                .getOrNull()
            withContext(Dispatchers.Main) {
                if (found == null) failedAt = System.currentTimeMillis()
                else synchronized(cache) { cache[uri] = found }
            }
        }
    }

    /** The episode's segments: Spotify's own id first, then a matching YouTube upload. Null: not asked (no network). */
    private suspend fun segmentsFor(episodeId: String, title: String, show: String, durationMs: Long): List<SponsorSegments.Segment>? {
        val spotify = fetch(episodeId, "Spotify") ?: return null
        if (spotify.isNotEmpty()) {
            Logbook.log("sponsorblock: ${spotify.size} segment(s) for the episode")
            return spotify
        }
        if (title.isBlank()) return emptyList()
        val hits = runCatching { runInterruptible { YouTubeSource.searchVideos("$show $title") } }.getOrElse {
            Log.w(TAG, "YouTube search failed: ${it.message}"); return emptyList()
        }
        val best = SponsorSegments.best(title, show, durationMs, hits.map { Triple(it.name, it.uploader, it.durationSec) })
        if (best < 0) return emptyList()
        val videoId = YouTubeSource.videoId(hits[best].url) ?: return emptyList()
        val youtube = fetch(videoId, "YouTube") ?: return null
        Logbook.log("sponsorblock: no Spotify segments, YouTube upload of the same length: ${youtube.size} segment(s)")
        return youtube
    }

    /** One hash-prefix request; the entry of [id] in it (empty: none), or null when it failed. */
    private fun fetch(id: String, service: String): List<SponsorSegments.Segment>? {
        val categories = SponsorSegments.ALL_CATEGORIES.joinToString(",", "[", "]") { "\"$it\"" }
        val url = okhttp3.HttpUrl.Builder().scheme("https").host(SERVER.removePrefix("https://"))
            .addPathSegments("api/skipSegments").addPathSegment(SponsorSegments.hashPrefix(id))
            .addQueryParameter("service", service)
            .addQueryParameter("categories", categories)
            .addQueryParameter("actionTypes", "[\"skip\"]")
            .build()
        val request = Request.Builder().url(url).header("X-CLIENT-NAME", "Myspoti").build()
        return YouTubeSource.http.newCall(request).execute().use { r ->
            when {
                // No segment for any id of that prefix.
                r.code == 404 -> emptyList()
                !r.isSuccessful -> null
                else -> SponsorSegments.parse(r.body.string(), id)
            }
        }
    }
}
