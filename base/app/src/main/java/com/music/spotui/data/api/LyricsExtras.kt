package com.music.spotui.data.api

import com.music.spotui.data.entity.LyricLine
import com.music.spotui.data.entity.Lyrics

/** Translations and romanizations a source gives apart from the lines (NetEase), and carrying them between sources. */
object LyricsExtras {
    /** How far a translated line may sit from its line (ms) and still be its translation. */
    private const val TOLERANCE_MS = 500L

    /** [lines] with the nearest line of [translations] and of [romanizations] (timed lines of text) as theirs. */
    fun attach(lines: List<LyricLine>, translations: List<LyricLine>, romanizations: List<LyricLine>): List<LyricLine> {
        if (translations.isEmpty() && romanizations.isEmpty()) return lines
        fun near(from: List<LyricLine>, t: Long): String? =
            from.minByOrNull { kotlin.math.abs(it.timeMs - t) }?.takeIf { kotlin.math.abs(it.timeMs - t) <= TOLERANCE_MS }
                ?.text?.trim()?.takeIf { it.isNotEmpty() && it != "♪" && it != "//" }
        return lines.map { l ->
            if (l.text == "♪") l else l.copy(translation = near(translations, l.timeMs), romanization = near(romanizations, l.timeMs))
        }
    }

    private fun LyricLine.hasExtra() = translation != null || romanization != null

    /**
     * [to] with the translations and romanizations of [from] (another source, same song), matched
     * by the text of the line, when [to] has none of its own and at least a third of its lines are found.
     */
    fun carry(to: Lyrics, from: Lyrics?): Lyrics {
        if (from == null || to.lines.any { it.hasExtra() }) return to
        val byText = HashMap<String, LyricLine>()
        for (l in from.lines) if (l.hasExtra()) byText.putIfAbsent(LyricsMatch.key(l.text), l)
        if (byText.isEmpty()) return to
        var hit = 0
        val lines = to.lines.map { l ->
            val m = LyricsMatch.key(l.text).takeIf { it.isNotEmpty() }?.let { byText[it] }
            if (m == null) l else { hit++; l.copy(translation = m.translation, romanization = m.romanization) }
        }
        if (hit * 3 < to.lines.size) return to
        return to.copy(lines = lines, translationLanguage = from.translationLanguage.takeIf { lines.any { it.translation != null } })
    }
}
