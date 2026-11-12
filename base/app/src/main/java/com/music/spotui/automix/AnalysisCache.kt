package com.music.spotui.automix

/** The rules of AutoMix's analyses on disk and ahead of time (see [AutoMix]); pure, unit-tested. */
internal object AnalysisCache {
    /** The analyses kept on disk at most; one is a few tens of KB, so thousands of songs. */
    const val MAX_BYTES = 64L * 1024 * 1024

    data class Entry(val name: String, val bytes: Long, val usedAt: Long)

    /** The files to delete so the rest fits in [maxBytes]: the least recently used first. */
    fun toTrim(entries: List<Entry>, maxBytes: Long = MAX_BYTES): List<String> {
        var total = entries.sumOf { it.bytes }
        if (total <= maxBytes) return emptyList()
        val out = ArrayList<String>()
        for (e in entries.sortedBy { it.usedAt }) {
            if (total <= maxBytes) break
            out += e.name
            total -= e.bytes
        }
        return out
    }

    /**
     * How many songs are analysed ahead, the one playing included: two (it and the next, all a mix
     * needs), three only while charging, so the third is analysed on the charger's energy.
     */
    fun lookahead(charging: Boolean, frugal: Boolean): Int = if (charging && !frugal) 3 else 2
}
