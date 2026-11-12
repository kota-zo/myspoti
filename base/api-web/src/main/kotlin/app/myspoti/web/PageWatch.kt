/* SPDX-License-Identifier: GPL-3.0-only */
package app.myspoti.web

/**
 * Whether the hidden page's playback stands still while it should play (see [WebListenSync]), from
 * the states the page script posts (a new one at each change, and every few seconds anyway). Moving:
 * a state of the asked song, playing, whose position ran on since the one before. A pause, an ad or
 * another song are not stalls (the sync answers those itself). Stalled: nothing moving for [stallMs]
 * (buffering for good, a frozen clock), or no state at all for that long (the page is dead).
 */
internal class PageWatch(private val stallMs: Long) {
    private var movedAt = 0L
    private var stateAt = 0L
    private var last: PageState? = null

    /** Starts over at [now] (the sync starts, the page was just reloaded). */
    fun reset(now: Long) { movedAt = now; stateAt = now; last = null }

    /** A state [s] the page posted at [now], while the sync wants [wantId]. */
    fun state(s: PageState, now: Long, wantId: String?) {
        val before = last
        last = s
        stateAt = now
        // Its clock ran, as a playing song's does: forward, by no more than the time between the two
        // states (a seek jumps; a frozen player answers the same place, a seek's target included).
        val advance = before?.let { s.positionMs - it.positionMs }
        val elapsed = before?.let { s.timestampMs - it.timestampMs } ?: 0L
        val moved = before == null || before.trackId != s.trackId || (advance != null && advance > 0 && advance <= elapsed + CLOCK_SLACK_MS)
        val playingAsked = s.trackId == wantId && !s.paused && !s.buffering
        if (!(s.trackId == wantId && !s.paused) || s.ad || (playingAsked && moved)) movedAt = now
    }

    /** Whether the page has stood still for [stallMs] by [now]; [expects]: it should play now (else nothing is judged). */
    fun stalled(now: Long, expects: Boolean): Boolean {
        if (stallMs <= 0) return false
        if (!expects) { movedAt = now; stateAt = now; return false }
        return now - movedAt >= stallMs || now - stateAt >= stallMs
    }

    private companion object {
        /** A state's position may be read that much later than its time stamp. */
        const val CLOCK_SLACK_MS = 2_000L
    }
}
