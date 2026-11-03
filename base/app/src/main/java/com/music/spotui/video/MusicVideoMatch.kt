package com.music.spotui.video

import java.text.Normalizer
import java.util.Locale

/**
 * Which search results can be the music video of a track, and in which order. Pure (no network,
 * no Android): a candidate is a YouTube result's name, uploader and duration in seconds.
 *
 * A candidate passes only when all of these hold:
 * - the artist is in its channel or its title (whole words, accents and case ignored);
 * - the track's title is in its title (normalized: "(Official Video)", "[MV]", "feat. …" dropped);
 * - it is not a live, cover, lyric, reaction, fan, remix, audio-only… version, unless the track's
 *   own title says so;
 * - its length is near the track's: an MV may be longer than the song by an intro or an outro.
 *   A duration unknown to either side is not held against the candidate, except a candidate
 *   without a duration when the track's is known.
 * An empty result is an answer: no video is better than a wrong one.
 */
internal object MusicVideoMatch {
    data class Entry(val name: String, val uploader: String, val durationSec: Long)

    /** A candidate may be this much shorter than the track (a cut clip). */
    const val SHORTER_TOLERANCE_MS = 10_000L
    /** A candidate may be this much longer than the track (an intro or an outro in the clip). */
    const val LONGER_TOLERANCE_MS = 90_000L

    /** Versions that are not the track's clip, each refused unless the track's own title names it. */
    private val unwanted: List<Regex> = listOf(
        Regex("""\blive\b"""),
        Regex("""\bcover(?:ed|s)?\b|\breprise\b"""),
        Regex("""\blyrics?\b|\bparoles\b|\bletra\b|\btesto\b"""),
        Regex("""\breact(?:ion|ions|s)?\b|\breaction video\b"""),
        Regex("""\bfan ?(?:made|edit|cam|mv|video)\b|\bfancam\b|\bfanmade\b"""),
        Regex("""\bremix\b|\brmx\b|\bbootleg\b|\bmashup\b"""),
        Regex("""\bkaraoke\b|\binstrumental\b|\btutorial\b|\bpiano\b|\bguitar\b"""),
        Regex("""\bslowed\b|\bsped ?up\b|\bnightcore\b|\b8d\b|\breverb\b"""),
        Regex("""\b(?:official )?audio\b|\baudio officiel\b"""),
    )

    /** Words of a track title that are not part of its name. */
    private val titleNoise = Regex("""\(.*?\)|\[.*?]|\{.*?}|\b(?:feat|ft|featuring)\b\.?\s.*""")

    /** Accents, case, "$" read as "s" ("A$AP" is "ASAP") and the elided apostrophe ("J'SAVAIS" is "jsavais"). */
    private fun fold(s: String): String = Normalizer.normalize(s.replace('$', 's').replace('’', '\''), Normalizer.Form.NFD)
        .replace(Regex("""\p{M}+"""), "")
        .lowercase(Locale.ROOT)
        .replace(Regex("""(?<=\p{L})'(?=\p{L})"""), "")

    private fun tokens(s: String): List<String> = fold(s).split(Regex("""[^\p{L}\p{N}]+""")).filter { it.isNotEmpty() }

    /** The primary artist of "A, B": a track's artist list is matched on its first name. */
    private fun primaryArtist(artist: String): String = artist.substringBefore(",").trim()

    /** A channel's own name: "Artist - Topic" and "ArtistVEVO" are the artist. */
    private fun channelTokens(uploader: String): List<String> =
        tokens(fold(uploader).replace(Regex("""\s*-\s*topic$|(?<=.)vevo$"""), ""))

    private fun containsRun(haystack: List<String>, needle: List<String>): Boolean {
        if (needle.isEmpty() || needle.size > haystack.size) return false
        return (0..haystack.size - needle.size).any { i -> needle.indices.all { j -> haystack[i + j] == needle[j] } }
    }

    /** The artist is the uploader's channel, or appears among the words of the name. */
    internal fun artistMatches(e: Entry, artist: String): Boolean {
        val a = tokens(primaryArtist(artist))
        if (a.isEmpty()) return false
        return containsRun(channelTokens(e.uploader), a) || containsRun(tokens(e.name), a)
    }

    /** The track's words (without the parenthesised and featured parts) must be in the name, most of them. */
    internal fun titleMatches(name: String, title: String): Boolean {
        val words = tokens(fold(title).replace(titleNoise, " "))
        if (words.isEmpty()) return fold(name).contains(fold(title))
        val nameWords = tokens(name).toSet()
        return words.count { it in nameWords } >= (words.size * 0.6).coerceAtLeast(1.0)
    }

    /** True when the name shows a version the track is not, and the track's own title does not name it. */
    internal fun isOtherVersion(name: String, title: String): Boolean {
        val n = fold(name)
        val t = fold(title)
        return unwanted.any { re -> re.containsMatchIn(n) && !re.containsMatchIn(t) }
    }

    /** Length within the tolerance of the track's; unknown on either side is not held against it. */
    internal fun durationMatches(durationSec: Long, durationMs: Long): Boolean {
        if (durationMs <= 0) return true
        if (durationSec <= 0) return false
        val sec = durationSec * 1000
        return sec >= durationMs - SHORTER_TOLERANCE_MS && sec <= durationMs + LONGER_TOLERANCE_MS
    }

    private val officialWords = Regex("""\bofficial\b|\bofficiel(?:le)?\b|\bvevo\b|\bmusic video\b|\bmv\b|\bclip\b""")

    /**
     * Preference among the accepted candidates: the artist's own channel first, then a name that
     * says it is the official clip, then a length that is the track's own.
     */
    private fun preference(e: Entry, title: String, artist: String, durationMs: Long): Int {
        val a = tokens(primaryArtist(artist))
        var score = 0
        if (channelTokens(e.uploader) == a) score += 4
        if (officialWords.containsMatchIn(fold(e.name))) score += 2
        if (durationMs > 0 && kotlin.math.abs(e.durationSec * 1000 - durationMs) <= 5_000) score += 1
        return score
    }

    /**
     * Indices of the entries that can be the track's clip, best first (ties keep the search order).
     * Empty when none passes: the caller shows no video.
     */
    fun rank(title: String, artist: String, durationMs: Long, entries: List<Entry>): List<Int> =
        entries.indices
            .filter { i ->
                val e = entries[i]
                titleMatches(e.name, title) && artistMatches(e, artist) &&
                    !isOtherVersion(e.name, title) && durationMatches(e.durationSec, durationMs)
            }
            .sortedByDescending { i -> preference(entries[i], title, artist, durationMs) }
}
