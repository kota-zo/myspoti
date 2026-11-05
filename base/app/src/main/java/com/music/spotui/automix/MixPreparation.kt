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

    /**
     * A plan's outgoing lanes are armed this long before the audio chain reaches the cue, at most and
     * at least. It is the chain's position that counts: the chain runs ahead of what is heard by the
     * player's whole buffer (seconds on some devices), and what it has passed can no longer change.
     */
    const val SEGUE_ARM_LEAD_MS = 1_500L
    const val SEGUE_ARM_MIN_MS = 500L
    /** How far ahead of what is heard the audio chain may be working. */
    private const val SEGUE_PLACE_SLACK_MS = 5_000L

    /** How long, in ms of the song, before the chain reaches the cue. */
    fun chainLeadMs(cueUs: Long, chainPositionUs: Long): Long = (cueUs - chainPositionUs) / 1000

    /** The audio chain's idea of the song position agrees with the player's: its automation will land where the plan says. */
    fun placed(chainPositionUs: Long, playerPositionMs: Long): Boolean =
        kotlin.math.abs(chainPositionUs / 1000 - playerPositionMs) <= SEGUE_PLACE_SLACK_MS

    fun segueReady(prepared: Boolean, bufferedMs: Long, remainingMs: Long, placedA: Boolean, placedB: Boolean, phaseMs: Double?): Boolean =
        prepared && placedA && placedB && bufferedMs >= minOf(BUFFER_MS, remainingMs.coerceAtLeast(0)) &&
            (phaseMs == null || kotlin.math.abs(phaseMs) <= 80.0)

    /** Audio lost on B must not make the still-playing A fade away. */
    fun preserveOutgoing(incomingReady: Boolean, outgoingPlaying: Boolean): Boolean = !incomingReady && outgoingPlaying
}
