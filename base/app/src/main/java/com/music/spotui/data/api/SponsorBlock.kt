package com.music.spotui.data.api

import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

/**
 * SponsorBlock for Spotify podcasts: the community database behind the Spot SponsorBlock
 * extension (and EeveeSpotify's skipper), at sponsor.ajay.app. Listeners mark the sponsor
 * reads, self-promotion, "subscribe" reminders… of an episode; Myspoti skips them.
 *
 * The query only carries the first characters of the SHA-256 of the episode ID, so the
 * server never learns which episode plays: it answers every episode under that prefix and
 * the app keeps its own.
 */
object SponsorBlock {
    private const val SERVER = "https://sponsor.ajay.app"
    /** Segments shorter than this are noise (a stray tap when marking). */
    private const val MIN_LENGTH_MS = 1000L
    /** Never seek this close to a segment's end: the player is about to leave it anyway. */
    private const val END_MARGIN_MS = 250L

    /** The categories offered in Settings, in their order there. */
    val CATEGORIES = listOf("sponsor", "selfpromo", "interaction", "intro", "outro", "preview", "filler")
    /** Skipped out of the box: paid and unpaid promotion, "like and subscribe". */
    val DEFAULT_CATEGORIES = setOf("sponsor", "selfpromo", "interaction")

    data class Segment(val startMs: Long, val endMs: Long, val category: String, val uuid: String)

    internal fun hashPrefix(episodeId: String): String = MessageDigest.getInstance("SHA-256")
        .digest(episodeId.toByteArray()).joinToString("") { "%02x".format(it) }.take(4)

    /** The segments listeners marked in [episodeId] (empty: none), or null when the server did not answer. */
    fun fetch(episodeId: String): List<Segment>? {
        val categories = URLEncoder.encode(JSONArray(CATEGORIES).toString(), "UTF-8")
        val spec = "$SERVER/api/skipSegments/${hashPrefix(episodeId)}?service=Spotify&categories=$categories"
        val conn = runCatching { URL(spec).openConnection() as HttpURLConnection }.getOrNull() ?: return null
        return try {
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.setRequestProperty("X-CLIENT-NAME", "Myspoti")
            when (conn.responseCode) {
                404 -> emptyList()
                in 200..299 -> parse(episodeId, conn.inputStream.bufferedReader().use { it.readText() })
                else -> null
            }
        } catch (_: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }

    /** [episodeId]'s skippable segments from a hash-prefix answer (which lists its neighbours too). */
    internal fun parse(episodeId: String, body: String): List<Segment> {
        val episodes = runCatching { JSONArray(body) }.getOrNull() ?: return emptyList()
        val out = ArrayList<Segment>()
        for (i in 0 until episodes.length()) {
            val episode = episodes.optJSONObject(i) ?: continue
            if (episode.optString("videoID") != episodeId) continue
            val segments = episode.optJSONArray("segments") ?: continue
            for (j in 0 until segments.length()) {
                val s = segments.optJSONObject(j) ?: continue
                if (s.optString("actionType") != "skip") continue
                val category = s.optString("category")
                if (category !in CATEGORIES) continue
                val range = s.optJSONArray("segment") ?: continue
                if (range.length() != 2) continue
                val start = (range.optDouble(0) * 1000).toLong()
                val end = (range.optDouble(1) * 1000).toLong()
                if (start < 0 || end - start < MIN_LENGTH_MS) continue
                out += Segment(start, end, category, s.optString("UUID"))
            }
        }
        return out.sortedBy { it.startMs }
    }

    /** The segment to skip at [positionMs]: one of the [skip] categories it is inside, unless let through. */
    internal fun segmentAt(segments: List<Segment>, positionMs: Long, skip: Set<String>, letThrough: Set<String>): Segment? =
        segments.firstOrNull {
            it.category in skip && it.uuid !in letThrough &&
                positionMs >= it.startMs && positionMs < it.endMs - END_MARGIN_MS
        }
}
