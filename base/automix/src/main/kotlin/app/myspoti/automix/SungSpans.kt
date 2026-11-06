/* SPDX-License-Identifier: GPL-3.0-only */
package app.myspoti.automix

import kotlin.math.min

/** Where a song is sung, read from its timed lyrics. Better than the spectral estimate wherever a song has them. */
object SungSpans {
    /** A line's start, in ms; [lastWordEndMs] when its words are timed; [sung]: it has words at all. */
    class Line(val startMs: Long, val lastWordEndMs: Long?, val sung: Boolean)

    /** Fewer timed lines than this says little about the song. */
    private const val MIN_LINES = 5
    /** A line whose end is unknown is taken to last until the next one, but no longer than this. */
    private const val LONGEST_LINE_MS = 7_000L
    private const val LAST_LINE_MS = 4_000L

    /** The spans in seconds, or null when these lines cannot be trusted for a song of [durationSec]: too few, or running past its end. */
    fun of(lines: List<Line>, durationSec: Double): List<Pair<Double, Double>>? {
        val sorted = lines.sortedBy { it.startMs }
        if (sorted.count { it.sung } < MIN_LINES) return null
        // Lyrics of another version of the song; a little past the end is only a late last line.
        if (sorted.last().startMs / 1000.0 > durationSec + 2) return null
        return sorted.indices.filter { sorted[it].sung }.map { i ->
            val line = sorted[i]
            val end = line.lastWordEndMs?.takeIf { it > line.startMs }
                ?: sorted.getOrNull(i + 1)?.let { min(it.startMs, line.startMs + LONGEST_LINE_MS) }
                ?: (line.startMs + LAST_LINE_MS)
            line.startMs / 1000.0 to end / 1000.0
        }
    }
}
