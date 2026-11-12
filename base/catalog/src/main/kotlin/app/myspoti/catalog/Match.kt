/* SPDX-License-Identifier: GPL-3.0-only */
package app.myspoti.catalog

import app.myspoti.api.Track
import java.text.Normalizer
import kotlin.math.abs

/**
 * What one recording looks like on another platform, for telling it apart from its neighbours. The order of the checks
 * follows what Mornye learned doing the same (https://github.com/mornyeapp/Mornye, LGPL-3.0): the ISRC first, then a
 * gate on version words, artists and length. It is stricter than Mornye on a title in another script, and treats a
 * cover as another recording: a missed match can be retried, a wrong song is simply wrong.
 */
object Match {
    private val MARKS = Regex("\\p{M}+")
    private val BRACKETED = Regex("\\s*[(\\[]\\s*(feat\\.?|ft\\.?|featuring|with|from)\\b([^)\\]]*)[)\\]]", RegexOption.IGNORE_CASE)
    private val PLAIN_FEATURING = Regex("\\s+(feat\\.?|ft\\.?|featuring)\\s.*$", RegexOption.IGNORE_CASE)
    private val TAIL = Regex("\\s*[-(\\[]\\s*((\\d{4}\\s*)?(digital\\s*)?(remaster(ed)?|original( mix| version)?|album version|single version|radio edit|explicit|clean|mono|stereo|deluxe|bonus track)(\\s*\\d{4})?(\\s*version)?)\\s*[)\\]]?\\s*$", RegexOption.IGNORE_CASE)
    private val NOT_WORD = Regex("[^\\p{L}\\p{N}]+")
    private val ARTIST_SPLIT = Regex("\\s*(?:,|;|&|\\+|/|\\bfeat\\.?|\\bft\\.?|\\bfeaturing\\b|\\band\\b|\\bwith\\b|\\bet\\b|\\bx\\b)\\s*", RegexOption.IGNORE_CASE)
    private val ISRC = Regex("^[A-Z]{2}[A-Z0-9]{3}\\d{7}$")

    /** Words that make it another recording: one side having them and not the other is no match. */
    private val VERSIONS = setOf(
        "live", "remix", "mix", "extended", "acoustic", "instrumental", "karaoke", "cover", "tribute", "demo", "reprise",
        "edit", "rework", "bootleg", "mashup", "sped", "slowed", "nightcore", "unplugged", "orchestral",
    )

    private fun plain(s: String) = Normalizer.normalize(s, Normalizer.Form.NFKD).replace(MARKS, "").lowercase()

    /** An ISRC as services write it, or null if it is not one. */
    fun isrc(s: String?): String? = s?.uppercase()?.replace("-", "")?.replace(" ", "")?.takeIf { ISRC.matches(it) }

    /** The title without credits, without what never tells a recording apart, keeping a credit that names a version. */
    fun stripped(s: String): String {
        var t = BRACKETED.replace(s) { m -> if (m.groupValues[2].lowercase().split(' ').any { it in VERSIONS }) m.value else "" }
        t = PLAIN_FEATURING.replace(t, "")
        repeat(3) { t = TAIL.replace(t, "") }
        return t.trim()
    }

    /** Lower case, without accents and signs: what is compared. */
    fun title(s: String): String = plain(stripped(s)).replace(NOT_WORD, " ").trim()

    fun name(s: String): String = plain(s).replace(NOT_WORD, " ").trim()

    /** How much two names share, from 0 to 1: order does not count. */
    fun similar(a: String, b: String): Double {
        if (a == b) return if (a.isEmpty()) 0.0 else 1.0
        val x = a.split(' ').filter { it.isNotEmpty() }.toSet()
        val y = b.split(' ').filter { it.isNotEmpty() }.toSet()
        if (x.isEmpty() || y.isEmpty()) return 0.0
        return 2.0 * x.intersect(y).size / (x.size + y.size)
    }

    /** The people behind a credit such as "A, B & C feat. D". */
    fun artists(credit: String): List<String> = ARTIST_SPLIT.split(credit).map(::name).filter { it.isNotEmpty() }

    /** How well a credit fits a track's artists: one person in common is enough, a name inside another needs two words. */
    fun artistFit(credit: String, hit: List<String>): Double {
        val wanted = name(credit)
        val theirs = hit.map(::name) + hit.flatMap(::artists)
        val parts = (artists(credit) + wanted).distinct()
        return parts.maxOfOrNull { w ->
            theirs.maxOfOrNull { t ->
                val shorter = minOf(w.count { it == ' ' }, t.count { it == ' ' }) + 1
                if (shorter >= 2 && (w.contains(t) || t.contains(w))) 0.9 else similar(w, t)
            } ?: 0.0
        } ?: 0.0
    }

    private fun versions(t: String) = t.split(' ').filter { it in VERSIONS }.toSet()

    /** The title up to its first bracket or dash. */
    private fun core(s: String) = name(stripped(s).split(" (", " [", " - ").first())

    private fun latin(s: String): Boolean {
        val letters = s.filter { it.isLetter() }
        return letters.isNotEmpty() && letters.count { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.LATIN } * 2 >= letters.length
    }

    /** A clip standing for a whole song. */
    fun preview(expectedMs: Long, hitMs: Long) = expectedMs > 45_000 && hitMs in 1..35_000

    /**
     * How sure [hit] is the recording described, from 0 (not it) to 1. It is not it if its version words differ, its
     * title or artist are too far off, or its length is too far away, however close the rest.
     */
    fun score(title: String, artist: String, durationMs: Long, hit: Track): Double {
        val a = title(title)
        val b = title(hit.name)
        if (a.isEmpty() || b.isEmpty()) return 0.0
        if (versions(a) != versions(b)) return 0.0
        if (preview(durationMs, hit.durationMs)) return 0.0
        val artists = artistFit(artist, hit.artists.map { it.name })
        if (artists < MIN_ARTIST) return 0.0
        val known = durationMs > 0 && hit.durationMs > 0
        val off = if (known) abs(durationMs - hit.durationMs) else 0L
        if (off > MOST_OFF_MS) return 0.0
        // The same song written in another script can only be told by who sings it and how long it lasts.
        if (a.any { it.isLetter() } && b.any { it.isLetter() } && latin(a) != latin(b)) {
            return if (known && artists >= 0.9 && off <= CLOSE_MS) SURE else 0.0
        }
        val titles = if (core(title) == core(hit.name) && core(title).isNotEmpty()) maxOf(0.9, similar(a, b)) else similar(a, b)
        if (titles < MIN_TITLE) return 0.0
        val lengths = if (known) 1.0 - off.toDouble() / MOST_OFF_MS else 0.5
        return titles * 0.5 + artists * 0.3 + lengths * 0.2
    }

    /** The same recording, as far as two tracks can tell: by ISRC when both have one. */
    fun same(a: Hit, b: Hit): Boolean {
        val x = isrc(a.isrc)
        val y = isrc(b.isrc)
        if (x != null && y != null) return x == y
        return score(a.track.name, a.track.artists.firstOrNull()?.name.orEmpty(), a.track.durationMs, b.track) >= SURE
    }

    const val SURE = 0.8
    private const val MIN_TITLE = 0.75
    private const val MIN_ARTIST = 0.5
    private const val MOST_OFF_MS = 10_000L
    private const val CLOSE_MS = 2_000L
}
