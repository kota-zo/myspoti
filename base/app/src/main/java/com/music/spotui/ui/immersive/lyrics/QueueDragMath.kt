package com.music.spotui.ui.immersive.lyrics

/**
 * The queue drag's rules, kept free of Compose so they can be tested. The panel's rows are one
 * flat order of keys: the queued rows, the context's heading, then the context's rows (no heading
 * when the context has none left). The row dragged swaps with the rows and the heading it passes
 * (sh.calvin.reorderable), so the order on screen is always the one dropped.
 */
internal object QueueDragMath {
    fun queuedPart(order: List<String>, heading: String): List<String> = order.takeWhile { it != heading }
    fun nextPart(order: List<String>, heading: String): List<String> = order.dropWhile { it != heading }.drop(1)

    /**
     * [order] with [from] moved to [to]'s place, or null when that move is not allowed: a song of
     * Spotify's queue ([canLeaveQueue] false) cannot go back into "next from" (Spotify has no such
     * move; it would come back to the queue's end). Nor can a Spotify song cross from the context's
     * songs to autoplay's or back ([block] tells them apart): asked to, Spotify puts a context song
     * at the context's end and an autoplay song first of autoplay's (seen on the test account,
     * 04/11: "Brise du vent", dropped under an autoplay song, landed after the context's last, "K9").
     * A local song goes anywhere.
     */
    fun swap(
        order: List<String>, from: String, to: String, heading: String, canLeaveQueue: Boolean,
        block: (String) -> Int = { 0 },
    ): List<String>? {
        val fi = order.indexOf(from); val ti = order.indexOf(to)
        if (fi < 0 || ti < 0 || fi == ti || from == heading) return null
        val r = order.toMutableList().apply { add(ti, removeAt(fi)) }
        if (!canLeaveQueue && from in queuedPart(order, heading) && from !in queuedPart(r, heading)) return null
        if (!canLeaveQueue && inOrder(nextPart(order, heading), block) && !inOrder(nextPart(r, heading), block)) return null
        return r
    }

    /** The context's songs, then autoplay's: never mixed. */
    private fun inOrder(part: List<String>, block: (String) -> Int) = part.zipWithNext().all { (a, b) -> block(a) <= block(b) }

    /** What a drop asks of the engine. */
    sealed interface Move {
        data object None : Move
        /** Out of "next from" into the queue, beside [target] (null: first in the queue). */
        data class IntoQueue(val target: String?, val after: Boolean) : Move
        /** Before / [after] [target], in [target]'s part (only a local song changes part this way). */
        data class Beside(val target: String, val after: Boolean) : Move
    }

    /**
     * The engine call for [dragged] going from [before] to [after]: named by the row it now
     * follows (or, first of its part, the row it now precedes), as SongPlayer's moves take it.
     */
    fun moveFor(before: List<String>, after: List<String>, dragged: String, heading: String, block: (String) -> Int = { 0 }): Move {
        if (before == after) return Move.None
        val wasQueued = dragged in queuedPart(before, heading)
        val q = queuedPart(after, heading)
        val nowQueued = dragged in q
        val part = if (nowQueued) q else nextPart(after, heading)
        val j = part.indexOf(dragged)
        if (j < 0) return Move.None
        val prev = part.getOrNull(j - 1); val next = part.getOrNull(j + 1)
        return when {
            nowQueued && !wasQueued -> if (prev != null) Move.IntoQueue(prev, true) else Move.IntoQueue(next, false)
            // First of autoplay's songs: named by the one it precedes, not by the context's last.
            !nowQueued && prev != null && next != null && block(prev) != block(dragged) && block(next) == block(dragged) -> Move.Beside(next, false)
            prev != null -> Move.Beside(prev, true)
            next != null -> Move.Beside(next, false)
            else -> Move.None
        }
    }

    /** What the engine's latest report means for a [Pending] order. */
    sealed interface Verdict {
        /** Nothing to do: the pending order stays (the engine is still before / half-way through the move, or settling). */
        data object Keep : Verdict
        /** The engine now shows the expected order. */
        data object Matched : Verdict
        /** The pending order is over; the engine's is trusted from now. */
        data class Drop(val why: String) : Verdict
    }

    /**
     * The order just dropped, shown until the engine can be trusted again. [queued] / [next] are the
     * rows, the uris the same songs in order: [preQueued] / [preNext] before the move, [expQueued] /
     * [expNext] after it. [dragged] is the moved song's uri, [current] what played then.
     *
     * The engine reports the move late and in steps (Spotify: the song out of one part, then
     * queued at the end, then placed), and may repeat or reorder reports. So the order is kept:
     * through reports of the queue as it was or half-way through this move (however long the
     * round-trip takes, up to [MAX_MS]), through [GRACE_MS] after the expected order is reported
     * (a late report of an older state is ignored), and ended by a report that is none of those,
     * by another song playing, or by those limits. A drop made before the last one was reported
     * chains to it: its reports are expected on the way.
     */
    class Pending<T>(
        val queued: List<T>, val next: List<T>,
        private val preQueued: List<String>, private val preNext: List<String>,
        private val expQueued: List<String>, private val expNext: List<String>,
        private val dragged: String, private val current: String?, private val setAt: Long,
        private val previous: Pending<T>? = null,
    ) {
        var matchedAt: Long? = null
            private set

        private fun isExpected(q: List<String>, n: List<String>) = q == expQueued && n == expNext
        private fun isBefore(q: List<String>, n: List<String>) = q == preQueued && n == preNext

        /** Half-way: the song is out of both parts, or queued at the end only; the rest as it ends up. */
        private fun isHalfWay(q: List<String>, n: List<String>): Boolean {
            if (q.filter { it != dragged } != expQueued.filter { it != dragged } ||
                n.filter { it != dragged } != expNext.filter { it != dragged }) return false
            val count = q.count { it == dragged } + n.count { it == dragged }
            return count == 0 || (count == 1 && n.none { it == dragged } && q.lastOrNull() == dragged)
        }

        /** A report of the queue before this move or part-way through it (or through the drops it follows). */
        private fun passing(q: List<String>, n: List<String>): Boolean =
            isBefore(q, n) || isHalfWay(q, n) ||
                previous?.let { it.isExpected(q, n) || it.passing(q, n) } == true

        fun report(q: List<String>, n: List<String>, reportedCurrent: String?, now: Long): Verdict {
            if (reportedCurrent != current) return Verdict.Drop("another song plays")
            expired(now)?.let { return Verdict.Drop(it) }
            val matched = matchedAt
            return when {
                isExpected(q, n) -> if (matched == null) { matchedAt = now; Verdict.Matched } else Verdict.Keep
                passing(q, n) -> Verdict.Keep
                else -> Verdict.Drop(if (matched == null) "engine shows another order" else "queue changed after the move")
            }
        }

        /** Why the order is over by time alone, else null. */
        fun expired(now: Long): String? = when {
            matchedAt?.let { now - it >= GRACE_MS } == true -> "settled"
            now - setAt >= MAX_MS -> "no final report in ${MAX_MS / 1000}s"
            else -> null
        }

        /** Chains are not needed once this one is reported: [previous] is let go. */
        fun detach(): Pending<T> = Pending(queued, next, preQueued, preNext, expQueued, expNext, dragged, current, setAt, null)
    }

    /** After the expected order is reported, reports of older states are ignored this long. */
    const val GRACE_MS = 1_500L
    /** The longest the engine is waited for. */
    const val MAX_MS = 10_000L

    /** Share of the full speed the list scrolls at as the finger enters an edge zone, so it visibly moves at once. */
    private const val EDGE_FLOOR = 0.2f

    /**
     * Pixels a second to scroll the list at for a finger at [y], in a list whose clear part spans
     * [start]..[end] (0 when the finger is clear of both [zone]s). It starts at [EDGE_FLOOR] of
     * [maxSpeed] at the zone's inner border and eases up (quadratic, so the first half of the zone
     * stays slow enough to stop on a row) to the full speed at the edge, and goes no faster past it.
     */
    fun edgePace(y: Float, start: Float, end: Float, zone: Float, maxSpeed: Float): Float {
        if (zone <= 0f || end - start < 2 * zone) return 0f
        val depth = maxOf((start + zone) - y, y - (end - zone))
        if (depth <= 0f) return 0f
        val t = (depth / zone).coerceAtMost(1f)
        return maxSpeed * (EDGE_FLOOR + (1f - EDGE_FLOOR) * t * t)
    }

    /**
     * The same pace for a held row spanning [top]..[bottom] (the queue sheet, which knows where its row
     * is drawn), signed: negative towards the start, positive towards the end. A viewport too short to
     * hold the row clear of both zones scrolls neither way.
     */
    fun edgeSpeed(top: Float, bottom: Float, start: Float, end: Float, zone: Float, maxSpeed: Float): Float {
        if (zone <= 0f || end <= start) return 0f
        val intoStart = (start + zone) - top
        val intoEnd = bottom - (end - zone)
        val reach = when {
            intoStart > 0f && intoEnd <= 0f -> -intoStart
            intoEnd > 0f && intoStart <= 0f -> intoEnd
            else -> return 0f
        }
        val t = (kotlin.math.abs(reach) / zone).coerceAtMost(1f)
        val pace = maxSpeed * (EDGE_FLOOR + (1f - EDGE_FLOOR) * t * t)
        return if (reach < 0f) -pace else pace
    }
}
