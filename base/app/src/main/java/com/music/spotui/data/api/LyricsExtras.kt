package com.music.spotui.data.api

import com.music.spotui.data.entity.LyricLine
import com.music.spotui.data.entity.LyricWord
import com.music.spotui.data.entity.Lyrics

/**
 * Translations and romanizations a source gives apart from the lines (NetEase), and carrying them,
 * the backing vocals and the singers between sources.
 */
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
    private fun LyricLine.hasBacks() = !background.isNullOrBlank()

    /** Some lines of [lyrics] have backing vocals of their own. */
    fun hasBacks(lyrics: Lyrics): Boolean = lyrics.lines.any { it.hasBacks() }

    // What a line sings in brackets: Spotify keeps the backing vocals in the line ("Stay (stay)").
    private val aside = Regex("""\s*\([^()]*\)""")
    private val spaces = Regex("\\s+")

    /**
     * Each line of [to] with its line in [from] (null: none), by the text of the line, the brackets
     * left out when that is what makes them differ; a line sung several times goes with the same
     * time it is sung in [from] (the third chorus with the third).
     */
    private fun pair(to: Lyrics, from: Lyrics): List<LyricLine?> {
        val byText = HashMap<String, MutableList<LyricLine>>()
        for (l in from.lines) LyricsMatch.key(l.text).takeIf { it.isNotEmpty() }?.let { byText.getOrPut(it) { ArrayList() }.add(l) }
        val seen = HashMap<String, Int>()
        return to.lines.map { l ->
            val k = LyricsMatch.key(l.text).takeIf { it in byText }
                ?: LyricsMatch.key(l.text.replace(aside, "")).takeIf { it in byText }
                ?: return@map null
            val n = seen.merge(k, 1, Int::plus)!! - 1
            byText.getValue(k).let { it.getOrNull(n) ?: it.last() }
        }
    }

    /**
     * [to] with what [from] (another source, same song) has and it lacks, each only when at least a
     * third of [to]'s lines are found in [from]:
     * - the translations and romanizations, when [to] has none of its own;
     * - the backing vocals, when [to] has none apart: their words moved by the gap between the two
     *   lines (the sources' recordings may start apart), and taken out of a line of [to] that sings
     *   them in brackets;
     * - who sings each line, when [from] has two singers and [to] says nothing of them.
     */
    fun carry(to: Lyrics, from: Lyrics?): Lyrics {
        if (from == null) return to
        val wantsExtras = to.lines.none { it.hasExtra() } && from.lines.any { it.hasExtra() }
        val wantsBacks = !hasBacks(to) && hasBacks(from)
        val wantsVoices = to.lines.all { it.voice == null } && from.hasDuet
        if (!wantsExtras && !wantsBacks && !wantsVoices) return to
        val pairs = pair(to, from)
        var out = to
        if (wantsExtras) out = carryExtras(out, from, pairs)
        if (pairs.count { it != null } * 3 < to.lines.size) return out
        if (wantsBacks) out = carryBacks(out, pairs)
        if (wantsVoices) out = out.copy(lines = out.lines.mapIndexed { i, l -> pairs[i]?.voice?.let { l.copy(voice = it) } ?: l })
        return out
    }

    private fun carryExtras(to: Lyrics, from: Lyrics, pairs: List<LyricLine?>): Lyrics {
        var hit = 0
        val lines = to.lines.mapIndexed { i, l ->
            val m = pairs[i]?.takeIf { it.hasExtra() }
            if (m == null) l else { hit++; l.copy(translation = m.translation, romanization = m.romanization) }
        }
        if (hit * 3 < to.lines.size) return to
        return to.copy(lines = lines, translationLanguage = from.translationLanguage.takeIf { lines.any { it.translation != null } })
    }

    private fun carryBacks(to: Lyrics, pairs: List<LyricLine?>): Lyrics {
        var changed = false
        val lines = to.lines.mapIndexed { i, l ->
            val m = pairs[i]?.takeIf { it.hasBacks() } ?: return@mapIndexed l
            val sungInBrackets = aside.findAll(l.text).joinToString("") { it.value }
            // Brackets saying something else than the backing vocals: the line is left as it is.
            if (sungInBrackets.isNotEmpty() && LyricsMatch.key(sungInBrackets) != LyricsMatch.key(m.background.orEmpty())) return@mapIndexed l
            val shift = l.timeMs - m.timeMs
            changed = true
            l.copy(
                text = if (sungInBrackets.isEmpty()) l.text else l.text.replace(aside, "").replace(spaces, " ").trim(),
                words = if (sungInBrackets.isEmpty()) l.words else l.words?.let(::outsideBrackets)?.takeIf { it.isNotEmpty() },
                background = m.background,
                backgroundWords = if (!to.synced) null else m.backgroundWords?.map { w ->
                    w.copy(startMs = (w.startMs + shift).coerceAtLeast(0), endMs = if (w.endMs > 0) (w.endMs + shift).coerceAtLeast(0) else 0)
                },
            )
        }
        return if (changed) to.copy(lines = lines) else to
    }

    /** The words of a line sung outside its brackets (a word half inside keeps its outer part). */
    internal fun outsideBrackets(words: List<LyricWord>): List<LyricWord> {
        val out = ArrayList<LyricWord>()
        var depth = 0
        for (w in words) {
            val before = depth
            depth = (depth + w.text.count { it == '(' } - w.text.count { it == ')' }).coerceAtLeast(0)
            val kept = when {
                before == 0 && '(' !in w.text -> w.text
                before == 0 -> w.text.substringBefore('(')
                depth == 0 -> w.text.substringAfterLast(')')
                else -> ""
            }
            if (kept.isNotBlank()) out += w.copy(text = kept)
        }
        return out
    }
}
