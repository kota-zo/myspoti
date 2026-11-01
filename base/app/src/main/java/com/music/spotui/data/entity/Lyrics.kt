package com.music.spotui.data.entity

/** One word (or syllable) of word-synced lyrics; [endMs] 0 = until the next word. */
data class LyricWord(
    val startMs: Long,
    val endMs: Long,
    val text: String,
)

/**
 * One line of lyrics. [timeMs] is the start time in ms (0 for unsynced lyrics);
 * [words] is set only when the source is word-synced (karaoke-style highlight).
 *
 * What a source may add to a line, all null when it did not: [translation] (in
 * [Lyrics.translationLanguage]), [romanization] (the line in Latin letters), [voice] (who sings it:
 * 1 the first singer, 2 the second..., 0 several together) and [background] (the backing vocals
 * sung over the line, without their brackets; [backgroundWords] when they are timed).
 */
data class LyricLine(
    val timeMs: Long,
    val text: String,
    val words: List<LyricWord>? = null,
    val translation: String? = null,
    val romanization: String? = null,
    val voice: Int? = null,
    val background: String? = null,
    val backgroundWords: List<LyricWord>? = null,
)

/** Spotify's per-track lyrics colours (color-lyrics `colors`), ARGB ints. */
data class LyricsColors(
    val background: Int,
    val text: Int,
    val highlight: Int,
)

/**
 * Lyrics for a track. When [synced] is true [lines] carry real timestamps and the
 * UI highlights/scrolls in time with playback; otherwise [lines] are just the
 * plain lyric lines (timeMs = 0) shown statically.
 */
data class Lyrics(
    val lines: List<LyricLine>,
    val synced: Boolean,
    val language: String? = null,
    /** Who provides the text, as Spotify credits it ("Musixmatch"), or our source name. */
    val provider: String? = null,
    val colors: LyricsColors? = null,
    /** False when Spotify only gives a preview (Free cap): the card says "Aperçu des paroles". */
    val uncapped: Boolean = true,
    /** The language of the lines' [LyricLine.translation] (as the source says it: "en", "zh"), null when unknown. */
    val translationLanguage: String? = null,
) {
    val isEmpty: Boolean get() = lines.isEmpty()
    val wordSynced: Boolean get() = lines.any { it.words != null }

    /** Some lines carry a romanization from the source. */
    val hasRomanization: Boolean get() = lines.any { !it.romanization.isNullOrBlank() }

    /** At least two singers share the song: worth showing who sings what. */
    val hasDuet: Boolean get() = lines.mapNotNull { it.voice }.filter { it > 0 }.distinct().size > 1

    /** Some lines carry backing vocals. */
    val hasBackground: Boolean get() = lines.any { !it.background.isNullOrBlank() }

    /**
     * The source's translations when they are in [targetLang] (language tags compared by their
     * first part), one per line (null: this line has none), else null: then a machine translation
     * is the way. Null too when the song is already in [targetLang].
     */
    fun translationsFor(targetLang: String): List<String?>? {
        if (!sameLanguage(translationLanguage, targetLang) || sameLanguage(language, targetLang)) return null
        val out = lines.map { it.translation?.trim()?.ifEmpty { null } }
        return out.takeIf { o -> o.any { it != null } }
    }

    companion object {
        /** "en-US" and "en" are the same language; unknown (null) is no language. */
        fun sameLanguage(a: String?, b: String?): Boolean =
            !a.isNullOrBlank() && !b.isNullOrBlank() &&
                a.substringBefore('-').substringBefore('_').equals(b.substringBefore('-').substringBefore('_'), ignoreCase = true)
    }
}
