package com.music.spotui.podcast

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * The pure part of SponsorBlock for podcasts: reading the server's answer, keeping the segments
 * that fit the episode playing, telling whether a YouTube upload is that same episode, and which
 * segment to skip now.
 *
 * SponsorBlock (https://sponsor.ajay.app, API: https://wiki.sponsor.ajay.app/w/API_Docs) is a
 * crowdsourced list of the sponsor reads and other parts of a video, by video id. Its server also
 * holds Spotify episodes (`service=Spotify`, keyed by the episode's base62 id: what the Spot
 * SponsorBlock browser extension submits, https://github.com/Spot-SponsorBlock/Spot-SponsorBlock-Extension),
 * so an episode is asked by its own id first; a YouTube upload of it is only the fallback.
 * Both are asked by hash prefix ([hashPrefix]): the server never learns which episode plays.
 */
internal object SponsorSegments {
    /** The categories the settings offer, as the server names them. */
    const val SPONSOR = "sponsor"
    const val SELF_PROMO = "selfpromo"
    const val INTRO = "intro"
    const val OUTRO = "outro"
    const val INTERACTION = "interaction"
    val ALL_CATEGORIES = listOf(SPONSOR, SELF_PROMO, INTRO, OUTRO, INTERACTION)

    /** Hex characters of the id's SHA-256 sent to the server (it accepts 4 to 32; 4 hides the id among thousands). */
    const val HASH_PREFIX = 4
    /** A segment marked on a copy longer or shorter than this (in ms) is not placed on the one playing. */
    const val DURATION_TOLERANCE_MS = 3_000L
    /** A YouTube upload is the same episode only when its length is this close (in ms). */
    const val YOUTUBE_DURATION_TOLERANCE_MS = 2_000L
    /** Shorter segments are not worth a seek. */
    const val MIN_SEGMENT_MS = 1_000L
    /** Segments closer than this are skipped as one. */
    const val MERGE_GAP_MS = 1_000L
    /** Episode title words a YouTube upload must carry, at least. */
    const val MIN_TITLE_COVERAGE = 0.8

    data class Segment(val startMs: Long, val endMs: Long, val category: String, val uuid: String, val videoDurationMs: Long = 0L)

    private val json = Json { ignoreUnknownKeys = true }

    fun sha256Hex(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    fun hashPrefix(id: String): String = sha256Hex(id).take(HASH_PREFIX)

    /**
     * The skip segments of [videoId] in a hash-prefix answer (`[{videoID, segments: [{segment:
     * [start, end], category, actionType, UUID, videoDuration}]}]`, other ids sharing the prefix
     * included). Only `skip` segments: `mute`, `full` (a label for the whole episode) and `poi`
     * are not parts to jump over. Null when the answer cannot be read.
     */
    fun parse(body: String, videoId: String): List<Segment>? {
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonArray ?: return null
        val entry = root.firstOrNull { (it as? JsonObject)?.str("videoID") == videoId } as? JsonObject ?: return emptyList()
        val segments = entry["segments"] as? JsonArray ?: return emptyList()
        return segments.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            if ((o.str("actionType") ?: "skip") != "skip") return@mapNotNull null
            val range = o["segment"] as? JsonArray ?: return@mapNotNull null
            if (range.size != 2) return@mapNotNull null
            val start = range[0].seconds() ?: return@mapNotNull null
            val end = range[1].seconds() ?: return@mapNotNull null
            Segment(
                startMs = start, endMs = end,
                category = o.str("category") ?: return@mapNotNull null,
                uuid = o.str("UUID").orEmpty(),
                videoDurationMs = o["videoDuration"]?.seconds() ?: 0L,
            )
        }
    }

    private fun JsonObject.str(key: String): String? = runCatching { this[key]?.jsonPrimitive?.contentOrNull }.getOrNull()

    private fun kotlinx.serialization.json.JsonElement.seconds(): Long? =
        runCatching { jsonPrimitive.doubleOrNull }.getOrNull()?.takeIf { it >= 0 }?.let { (it * 1000).roundToLong() }

    /**
     * The segments to skip in an episode [durationMs] long (0: not known yet), sorted, of the
     * [categories] chosen. A segment marked on a copy of another length is left out (an ad
     * inserted for that listener moves everything after it); one with no length given is kept.
     * Overlapping or touching segments become one; the last one ends before the episode does.
     */
    fun usable(segments: List<Segment>, durationMs: Long, categories: Set<String>): List<Segment> {
        val kept = segments.asSequence()
            .filter { it.category in categories }
            .filter { durationMs <= 0 || it.videoDurationMs <= 0 || abs(it.videoDurationMs - durationMs) <= DURATION_TOLERANCE_MS }
            .map { if (durationMs > 0) it.copy(endMs = minOf(it.endMs, durationMs)) else it }
            .filter { it.endMs - it.startMs >= MIN_SEGMENT_MS }
            .sortedBy { it.startMs }
            .toList()
        val merged = ArrayList<Segment>()
        for (s in kept) {
            val last = merged.lastOrNull()
            if (last != null && s.startMs <= last.endMs + MERGE_GAP_MS) {
                // The first one's category names the skip; the uuid of both keeps the skip once.
                merged[merged.size - 1] = last.copy(endMs = maxOf(last.endMs, s.endMs), uuid = last.uuid + "+" + s.uuid)
            } else merged += s
        }
        return merged
    }

    // --- YouTube fallback: is this upload the episode playing? ---

    private fun fold(s: String): String = Normalizer.normalize(s.replace('’', '\''), Normalizer.Form.NFD)
        .replace(Regex("""\p{M}+"""), "").lowercase(Locale.ROOT)

    internal fun tokens(s: String): List<String> =
        fold(s).replace(Regex("""(?<=\p{L})'(?=\p{L})"""), "").split(Regex("""[^\p{L}\p{N}]+""")).filter { it.isNotEmpty() }

    /** The share of the episode title's words found in [candidate] (1.0: all of them). */
    fun titleCoverage(episodeTitle: String, candidate: String): Double {
        val want = tokens(episodeTitle).distinct()
        if (want.isEmpty()) return 0.0
        val have = tokens(candidate).toSet()
        return want.count { it in have }.toDouble() / want.size
    }

    /** The show's name in the upload's title or channel, all its words. */
    fun showMatches(show: String, name: String, uploader: String): Boolean {
        val want = tokens(show).distinct()
        if (want.isEmpty()) return false
        val have = (tokens(name) + tokens(uploader)).toSet()
        return want.all { it in have } || tokens(uploader).joinToString("") == want.joinToString("")
    }

    /**
     * Whether a YouTube upload ([name] by [uploader], [durationSec] long) is the episode [title]
     * of [show], [durationMs] long: the same length within 2 s (so the segments need no shift),
     * most of the title's words, and the show named, or, without it, every word of a title long
     * enough not to be a coincidence.
     */
    fun matches(title: String, show: String, durationMs: Long, name: String, uploader: String, durationSec: Long): Boolean {
        if (durationMs <= 0 || durationSec <= 0) return false
        if (abs(durationSec * 1000 - durationMs) > YOUTUBE_DURATION_TOLERANCE_MS) return false
        val coverage = titleCoverage(title, name)
        if (coverage < MIN_TITLE_COVERAGE) return false
        return showMatches(show, name, uploader) || (coverage >= 1.0 && tokens(title).distinct().size >= 4)
    }

    /** The index of the best upload in [hits] (name, uploader, seconds) that [matches], or -1. */
    fun best(title: String, show: String, durationMs: Long, hits: List<Triple<String, String, Long>>): Int =
        hits.indices
            .filter { matches(title, show, durationMs, hits[it].first, hits[it].second, hits[it].third) }
            .maxWithOrNull(compareBy<Int> { titleCoverage(title, hits[it].first) }.thenBy { -abs(hits[it].third * 1000 - durationMs) }.thenBy { -it })
            ?: -1
}

/**
 * Which segment to skip, each one once: a segment the playhead enters is skipped, and not again
 * after that (going back into it, by the Undo or by hand, plays it). A playhead already in its
 * last [END_MARGIN_MS] lets it end by itself.
 */
internal class SkipTracker(val segments: List<SponsorSegments.Segment>) {
    private val done = HashSet<String>()

    fun due(positionMs: Long): SponsorSegments.Segment? {
        val s = segments.firstOrNull { positionMs >= it.startMs && positionMs < it.endMs - END_MARGIN_MS && key(it) !in done } ?: return null
        done += key(s)
        return s
    }

    /** How long until the next segment not skipped yet starts (ms), or null when none is left ahead. */
    fun untilNext(positionMs: Long): Long? =
        segments.filter { key(it) !in done && it.endMs - END_MARGIN_MS > positionMs }.minOfOrNull { (it.startMs - positionMs).coerceAtLeast(0) }

    private fun key(s: SponsorSegments.Segment) = s.uuid.ifEmpty { "${s.startMs}-${s.endMs}" }

    companion object {
        const val END_MARGIN_MS = 1_500L
    }
}
