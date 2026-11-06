package app.myspoti.api

import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset

/** What the "What's New" feed is narrowed to: none, or one of the Web Player's two pills. */
enum class WhatsNewContent { ALL, MUSIC, PODCASTS }

/** Where an entry of the feed stands for the account, in the Web Player's words. */
enum class WhatsNewState { NEW, SEEN, HIDDEN, UNKNOWN }

/**
 * One entry of the "What's New" feed (`queryWhatsNewFeed`): a release of an artist the account
 * follows ([album]) or an episode of a show it follows ([episode]), exactly one of the two.
 */
data class WhatsNewItem(
    /** The feed's own id for the entry: what [Home.markWhatsNewSeen] takes. */
    val id: String,
    val state: WhatsNewState,
    /** The entry's state timestamp (ISO 8601), when the server gives one. */
    val stateAt: String? = null,
    val album: Album? = null,
    val episode: Episode? = null,
) {
    val uri: Uri get() = album?.uri ?: episode?.uri.orEmpty()
    /** The day it came out (ISO 8601), what the feed is ordered by. */
    val releaseDate: String? get() = album?.releaseDate ?: episode?.releaseDate
}

/** The feed as the Web Player's page splits it: the "New" entries on top, then "Earlier". */
data class WhatsNewSections(val fresh: List<WhatsNewItem>, val earlier: List<WhatsNewItem>)

/**
 * The Web Player's "New" rule: an entry is new while unseen with no timestamp, or within an hour
 * of its timestamp. The page marks every entry seen as soon as it opens, so what was new still
 * reads as new when it is opened again shortly after. "New" is the run of such entries at the top;
 * everything from the first other one on is "Earlier".
 *
 * Each section is ordered newest first by release date, the server's own order kept for a tie or
 * an entry without a date.
 */
fun whatsNewSections(items: List<WhatsNewItem>, nowMs: Long = System.currentTimeMillis()): WhatsNewSections {
    val split = items.indexOfFirst { !it.isFresh(nowMs) }.let { if (it < 0) items.size else it }
    fun newestFirst(list: List<WhatsNewItem>) = list.sortedByDescending { isoMs(it.releaseDate) }
    return WhatsNewSections(newestFirst(items.take(split)), newestFirst(items.drop(split)))
}

private fun WhatsNewItem.isFresh(nowMs: Long): Boolean {
    val at = isoMs(stateAt) ?: return state == WhatsNewState.NEW
    return nowMs - at <= FRESH_MS
}

/** One hour, the Web Player's own window. */
private const val FRESH_MS = 3_600_000L

/** Epoch ms of an ISO date or date-time ("2026-10-31", "2026-10-31T00:00:00Z"); null when unreadable. */
private fun isoMs(iso: String?): Long? {
    if (iso.isNullOrBlank()) return null
    return runCatching { OffsetDateTime.parse(iso).toInstant().toEpochMilli() }.getOrNull()
        ?: runCatching { Instant.parse(iso).toEpochMilli() }.getOrNull()
        ?: runCatching { LocalDate.parse(iso.take(10)).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() }.getOrNull()
}
