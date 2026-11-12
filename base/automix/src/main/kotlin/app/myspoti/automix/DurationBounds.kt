/* SPDX-License-Identifier: GPL-3.0-only */
package app.myspoti.automix

import kotlin.math.ceil
import kotlin.math.floor

/** Hard limits in seconds of the listener's elapsed transition window. */
data class DurationBounds(
    val minSeconds: Double = 0.0,
    val maxSeconds: Double = Double.POSITIVE_INFINITY,
) {
    init {
        require(minSeconds.isFinite() && minSeconds >= 0.0) { "minimum duration must be finite and nonnegative" }
        require(maxSeconds > 0.0 && !maxSeconds.isNaN()) { "maximum duration must be positive or positive infinity" }
        require(minSeconds <= maxSeconds) { "minimum duration exceeds maximum duration" }
    }

    val isUnbounded: Boolean get() = minSeconds == 0.0 && maxSeconds == Double.POSITIVE_INFINITY

    fun contains(seconds: Double): Boolean = seconds.isFinite() && seconds > 0.0 && seconds >= minSeconds && seconds <= maxSeconds

    /** Convert listener seconds to a player's source timeline (source seconds per elapsed second).
     * SegueRequest performs this conversion internally; classic MixPlanner callers apply it once.
     */
    fun forPlaybackRate(rate: Double): DurationBounds {
        require(rate.isFinite() && rate > 0.0) { "playback rate must be finite and positive" }
        if (isUnbounded || rate == 1.0) return this
        return DurationBounds((minSeconds * rate).coerceAtMost(Double.MAX_VALUE), maxSeconds * rate)
    }

    /** Round inwards: serialising microseconds may never move a plan outside the requested bounds. */
    internal val minUs: Long get() = ceil(minSeconds * 1e6).toLong()
    internal val maxUs: Long get() = if (maxSeconds == Double.POSITIVE_INFINITY) Long.MAX_VALUE else floor(maxSeconds * 1e6).toLong()
    internal fun containsUs(us: Long): Boolean = us > 0 && us >= minUs && us <= maxUs
}
