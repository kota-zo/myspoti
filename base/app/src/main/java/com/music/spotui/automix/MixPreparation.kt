/* SPDX-License-Identifier: GPL-3.0-only */
package com.music.spotui.automix

/** Decisions independent of ExoPlayer, so late analysis and buffering can be tested without audio. */
internal object MixPreparation {
    const val MIN_LEAD_MS = 850L
    const val BUFFER_MS = 750L

    fun mayReplacePlan(committed: Boolean, provisional: Boolean, analysesReady: Boolean): Boolean =
        !committed && provisional && analysesReady

    fun readyToTakeOver(playing: Boolean, bufferedMs: Long, remainingMs: Long, phaseMs: Double?, synced: Boolean): Boolean =
        playing && bufferedMs >= minOf(BUFFER_MS, remainingMs.coerceAtLeast(0)) &&
            (!synced || phaseMs != null && kotlin.math.abs(phaseMs) <= 80.0)

    /** Audio lost on B must not make the still-playing A fade away. */
    fun preserveOutgoing(incomingReady: Boolean, outgoingPlaying: Boolean): Boolean = !incomingReady && outgoingPlaying
}
