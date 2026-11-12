package com.music.spotui.di

/**
 * Timing and volume curve of the real (overlapping) crossfade of [LocalPlayer], kept pure so it
 * is tested off-device. Equal-power: the two gains' squares add up to 1, so the loudness stays
 * level through the overlap.
 */
object CrossfadeRamp {
    /** The overlap for a song of [durationMs] and a setting of [seconds]: never over half the song. */
    fun fadeMs(seconds: Int, durationMs: Long): Long =
        if (seconds <= 0 || durationMs <= 0) 0L else minOf(seconds * 1000L, durationMs / 2)

    /** The fade starts once [remainingMs] of the song is left. */
    fun shouldStart(seconds: Int, durationMs: Long, positionMs: Long): Boolean {
        val fade = fadeMs(seconds, durationMs)
        return fade > 0 && positionMs >= 0 && durationMs - positionMs <= fade
    }

    /**
     * When the player looks again for the fade's start ([shouldStart]), in real ms at [speed]:
     * null with no crossfade (nothing to wait for: it looked 5 times a second all song long), soon
     * near the start, else up to [MAX_LOOK_MS] away (a seek or a new song is looked at by itself).
     */
    fun nextLookMs(seconds: Int, durationMs: Long, positionMs: Long, speed: Float = 1f): Long? {
        if (seconds <= 0) return null
        if (durationMs <= 0) return MAX_LOOK_MS
        val left = durationMs - positionMs - fadeMs(seconds, durationMs)
        if (left <= MIN_LOOK_MS) return MIN_LOOK_MS
        return ((left - MIN_LOOK_MS) / speed.coerceAtLeast(0.25f)).toLong().coerceIn(MIN_LOOK_MS, MAX_LOOK_MS)
    }

    const val MIN_LOOK_MS = 200L
    const val MAX_LOOK_MS = 5_000L

    /** Progress 0..1 of a fade of [fadeMs] when the incoming song is at [incomingPositionMs]. */
    fun progress(incomingPositionMs: Long, fadeMs: Long): Float =
        if (fadeMs <= 0) 1f else (incomingPositionMs.toFloat() / fadeMs).coerceIn(0f, 1f)

    fun incomingGain(t: Float): Float = Math.sin(t.coerceIn(0f, 1f) * Math.PI / 2).toFloat()
    fun outgoingGain(t: Float): Float = Math.cos(t.coerceIn(0f, 1f) * Math.PI / 2).toFloat()
}
