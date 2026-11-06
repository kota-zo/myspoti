/* SPDX-License-Identifier: GPL-3.0-only */
package app.myspoti.automix

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Local section boundaries from the features already measured for the beat grid; no extra model. */
internal object Sections {
    class Map(val starts: IntArray, val strengths: FloatArray)

    fun detect(novelty: FloatArray): Map {
        val strength = FloatArray(novelty.size)
        if (novelty.size < 12) return Map(IntArray(0), strength)
        val finite = novelty.filter { it.isFinite() }.sorted()
        if (finite.isEmpty()) return Map(IntArray(0), strength)
        val median = finite[finite.size / 2]
        val deviations = finite.map { abs(it - median) }.sorted()
        // Sustained change across the surrounding bars, clearly above the song's novelty floor.
        val threshold = median + max(0.65f, deviations[deviations.size / 2] * 2.5f)
        val scale = max(0.9f, finite[(finite.size * 0.9).toInt().coerceAtMost(finite.lastIndex)] - median)
        val peaks = (4 until novelty.size - 4).filter { bar ->
            val value = novelty[bar]
            value.isFinite() && value >= threshold && (max(0, bar - 2)..min(novelty.lastIndex, bar + 2)).all { other ->
                novelty[other] < value || (novelty[other] == value && other >= bar)
            }
        }.sortedByDescending { novelty[it] }
        val kept = ArrayList<Int>()
        for (bar in peaks) {
            // One boundary for a broad change, never several adjacent "new sections".
            if (kept.any { abs(it - bar) < 4 }) continue
            kept.add(bar)
            strength[bar] = ((novelty[bar] - median) / scale * 0.75f).coerceIn(0.0f, 1.0f)
        }
        return Map(kept.sorted().toIntArray(), strength)
    }
}
