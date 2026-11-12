/* SPDX-License-Identifier: GPL-3.0-only */
package com.music.spotui.automix

/** Export only fixed event labels: raw journal entries can contain track titles, URIs, or IDs. */
internal object AutoMixDiagnostics {
    private val analysisMetrics = Regex(
        """^analysed .*?: ([0-9]+(?:[.,][0-9]+)?) BPM(?: steady| \(tail grid\)| \(no grid\))?, ([0-9]+) bars, key -?[0-9]+, decode ([0-9]+) ms \(bytes [0-9]+ ms, [0-9]+(?:[.,][0-9]+)? MB, convert [0-9]+ ms, [0-9]+ restarts\), analysis ([0-9]+) ms on ([0-9]+) threads(?: \| grids refused: .*)?$"""
    )

    fun safeJournal(lines: List<String>): List<String> = lines.mapNotNull(::safeEvent)

    private fun safeEvent(line: String): String? {
        analysisMetrics.matchEntire(line)?.let { m ->
            // Emit only numeric captures from the strict journal shape, never the source label or key.
            return "Track analysis completed: ${m.groupValues[1]} BPM, ${m.groupValues[2]} bars, decode ${m.groupValues[3]} ms, analysis ${m.groupValues[4]} ms on ${m.groupValues[5]} threads"
        }
        return when {
            line == "AutoMix on" -> "AutoMix enabled"
            line == "AutoMix off" -> "AutoMix disabled"
            line == "no transition fits the current constraints" -> "No transition fits the current constraints"
            line.startsWith("analysed ") -> "Track analysis completed"
            line.startsWith("analysis deferred for ") || line.startsWith("analysis given up for ") -> "Track analysis unavailable"
            line.startsWith("plan ") -> "Transition plan updated"
            line.startsWith("provisional plan ") -> "Provisional transition plan updated"
            line.startsWith("skip:") || line.startsWith("skip moved:") -> "Next transition updated"
            line.startsWith("deck preparing ") -> "Incoming deck preparing"
            line.startsWith("deck ready on ") -> "Incoming deck ready"
            line.startsWith("run-up started") -> "Transition run-up started"
            line.startsWith("mix started") -> "Transition started"
            line.startsWith("mix done") -> "Transition completed"
            line.startsWith("incoming deck buffering") -> "Incoming deck buffering"
            line.startsWith("incoming deck recovered") -> "Incoming deck recovered"
            line.startsWith("deck dropped:") -> "Incoming deck stopped"
            line.startsWith("mix ended early") -> "Transition ended early"
            line.startsWith("segue armed:") -> "Segue prepared"
            line.startsWith("analysis ready:") -> "Analysis improved the prepared transition"
            line.startsWith("back to the song's own speed") -> "Playback speed restored"
            line.startsWith("EQ unavailable") -> "Transition EQ unavailable"
            else -> null
        }
    }
}
