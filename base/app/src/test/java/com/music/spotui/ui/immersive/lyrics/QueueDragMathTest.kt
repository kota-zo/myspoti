package com.music.spotui.ui.immersive.lyrics

import com.music.spotui.di.LocalQueueOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueDragMathTest {
    private val H = "heading"

    /** cur, then [nq] queued songs q0.., then [nn] list songs n0.. */
    private fun local(nq: Int, nn: Int): Triple<List<String>, Set<String>, Int> {
        val queued = (0 until nq).map { "q$it" }
        return Triple(listOf("cur") + queued + (0 until nn).map { "n$it" }, queued.toSet(), 0)
    }

    /** The panel's flat order: the queued rows, the heading (with a context row), the context's rows. */
    private fun panel(order: List<String>, queued: Set<String>): List<String> {
        val rows = order.drop(1)
        val next = rows.filter { it !in queued }
        return rows.filter { it in queued } + (if (next.isEmpty()) emptyList() else listOf(H)) + next
    }

    @Test fun everyDropEndsInThePanelsOrderOnTheLocalPlayer() {
        var checked = 0
        for (nq in 0..3) for (nn in 0..3) {
            val (order, queuedSet, cur) = local(nq, nn)
            val before = panel(order, queuedSet)
            for (dragged in order.drop(1)) for (to in before) {
                // One swap with each row or the heading: every place the drag can reach in one step,
                // and the result of a longer drag is a list that one step also gives.
                val after = QueueDragMath.swap(before, dragged, to, H, canLeaveQueue = true) ?: continue
                val move = QueueDragMath.moveFor(before, after, dragged, H)
                val from = order.indexOf(dragged)
                val result: Pair<List<String>, Boolean> = when (move) {
                    QueueDragMath.Move.None -> continue
                    is QueueDragMath.Move.IntoQueue -> if (move.target == null) LocalQueueOrder.moveToFront(order, cur, from)!! to true
                        else LocalQueueOrder.moveBeside(order, cur, from, order.indexOf(move.target), move.after) { it in queuedSet }!!
                    is QueueDragMath.Move.Beside -> LocalQueueOrder.moveBeside(order, cur, from, order.indexOf(move.target), move.after) { it in queuedSet }!!
                }
                val (played, toQueue) = result
                val nowQueued = { x: String -> if (x == dragged) toQueue else x in queuedSet }
                assertEquals("cur stays", "cur", played[0])
                assertEquals("drag $dragged to $to nq=$nq nn=$nn: $move", QueueDragMath.queuedPart(after, H), played.drop(1).filter(nowQueued))
                assertEquals("drag $dragged to $to nq=$nq nn=$nn: $move", QueueDragMath.nextPart(after, H), played.drop(1).filterNot(nowQueued))
                assertEquals("queued songs come first", played.drop(1).filter(nowQueued), played.drop(1).take(QueueDragMath.queuedPart(after, H).size))
                checked++
            }
        }
        assertTrue(checked > 100)
    }

    @Test fun aQueuedSpotifySongStaysInTheQueue() {
        val order = listOf("q0", "q1", H, "n0", "n1")
        assertNull(QueueDragMath.swap(order, "q1", H, H, canLeaveQueue = false))
        assertNull(QueueDragMath.swap(order, "q0", "n0", H, canLeaveQueue = false))
        assertEquals(listOf("q1", "q0", H, "n0", "n1"), QueueDragMath.swap(order, "q0", "q1", H, canLeaveQueue = false))
        // A song of "next from" goes up into the queue past the heading.
        assertEquals(listOf("q0", "q1", "n0", H, "n1"), QueueDragMath.swap(order, "n0", H, H, canLeaveQueue = false))
        assertNull(QueueDragMath.swap(order, H, "n0", H, canLeaveQueue = true))
    }

    /**
     * Spotify's reorderQueue within "next from", as seen on the test account (04/11): exact inside
     * the context's songs (c..) and inside autoplay's (a..); asked across, a context song goes to the
     * context's end, an autoplay song first of autoplay's.
     */
    private fun spotifyReorder(next: List<String>, moved: String, target: String, after: Boolean): List<String> {
        val rest = next - moved
        val auto = { x: String -> x.startsWith("a") }
        val i = when {
            auto(moved) == auto(target) -> rest.indexOf(target) + if (after) 1 else 0
            auto(moved) -> rest.indexOfFirst(auto).let { if (it < 0) rest.size else it }
            else -> rest.indexOfFirst(auto).let { if (it < 0) rest.size else it }
        }
        return rest.toMutableList().apply { add(i, moved) }
    }

    @Test fun everyDropOfASpotifySongEndsInThePanelsOrder() {
        val block = { k: String -> if (k.startsWith("a")) 1 else 0 }
        var checked = 0; var refused = 0
        for (nc in 0..4) for (na in 0..4) {
            val next = (0 until nc).map { "c$it" } + (0 until na).map { "a$it" }
            if (next.isEmpty()) continue
            val before = listOf(H) + next
            for (dragged in next) for (to in next) {
                val after = QueueDragMath.swap(before, dragged, to, H, canLeaveQueue = false, block = block)
                if (after == null) { if (dragged != to) refused++; continue }
                val move = QueueDragMath.moveFor(before, after, dragged, H, block)
                assertTrue("drag $dragged to $to: $move", move is QueueDragMath.Move.Beside)
                move as QueueDragMath.Move.Beside
                assertEquals("drag $dragged to $to: $move", QueueDragMath.nextPart(after, H), spotifyReorder(next, dragged, move.target, move.after))
                checked++
            }
        }
        assertTrue(checked > 50 && refused > 10)
        // The reported case: a context song under an autoplay one is not a place it can go.
        assertNull(QueueDragMath.swap(listOf(H, "c0", "c1", "a0", "a1"), "c0", "a0", H, canLeaveQueue = false, block = block))
        // An autoplay song moved first of autoplay's is named by the song it now precedes.
        assertEquals(QueueDragMath.Move.Beside("a0", false),
            QueueDragMath.moveFor(listOf(H, "c0", "a0", "a1"), listOf(H, "c0", "a1", "a0"), "a1", H, block))
    }

    @Test fun theEngineCallNamesTheRowBeforeElseTheRowAfter() {
        val before = listOf("q0", "q1", H, "n0", "n1")
        val m = QueueDragMath.Move.IntoQueue("q1", true)
        assertEquals(m, QueueDragMath.moveFor(before, listOf("q0", "q1", "n0", H, "n1"), "n0", H))
        // With no queued row, over the heading: first in the queue.
        assertEquals(QueueDragMath.Move.IntoQueue(null, false), QueueDragMath.moveFor(listOf(H, "n0", "n1"), listOf("n0", H, "n1"), "n0", H))
        // Within "next from": after the row it now follows, else before the one it now precedes.
        assertEquals(QueueDragMath.Move.Beside("n0", false), QueueDragMath.moveFor(listOf(H, "n0", "n1"), listOf(H, "n1", "n0"), "n1", H))
        assertEquals(QueueDragMath.Move.Beside("n1", true), QueueDragMath.moveFor(listOf(H, "n0", "n1", "n2"), listOf(H, "n1", "n0", "n2"), "n0", H))
        assertEquals(QueueDragMath.Move.None, QueueDragMath.moveFor(before, before, "q0", H))
    }

    // ── The Spotify queue's reports while a move is applied: stale, half-way, final ──

    private val Match = QueueDragMath.Verdict.Matched
    private val Keep = QueueDragMath.Verdict.Keep

    /** "n1" dragged from the list into the queue between q0 and q1. */
    private val q0 = listOf("q0", "q1")
    private val n0 = listOf("n0", "n1", "n2")
    /** Where a drop lands: in the queue or the context's part, at that index of the other rows. */
    private data class Place(val toQueue: Boolean, val index: Int)
    private val place = Place(true, 1)
    private fun move(dragged: String = "n1", q: List<String> = q0, n: List<String> = n0, p: Place = place, at: Long = 0L, prev: QueueDragMath.Pending<String>? = null): QueueDragMath.Pending<String> {
        val oq = q - dragged; val on = n - dragged
        val eq = if (p.toQueue) oq.toMutableList().apply { add(p.index, dragged) } else oq
        val en = if (p.toQueue) on else on.toMutableList().apply { add(p.index, dragged) }
        return QueueDragMath.Pending(eq, en, q, n, eq, en, dragged, "cur", at, prev)
    }
    private val finalQ = listOf("q0", "n1", "q1")
    private val finalN = listOf("n0", "n2")

    @Test fun noReportBeforeTheFinalOneEndsThePendingOrder() {
        val p = move()
        assertEquals(finalQ, p.queued); assertEquals(finalN, p.next)
        assertEquals(Keep, p.report(q0, n0, "cur", 100))                          // stale
        assertEquals(Keep, p.report(q0, listOf("n0", "n2"), "cur", 200))          // out of the list, not yet queued
        assertEquals(Keep, p.report(q0, listOf("n0", "n2"), "cur", 300))          // the same again
        assertEquals(Keep, p.report(q0 + "n1", listOf("n0", "n2"), "cur", 400))   // queued at the end
        assertEquals(Match, p.report(finalQ, finalN, "cur", 500))
    }

    @Test fun aSlowFinalReportIsWaitedFor() {
        val p = move()
        // Reports of the old order keep coming for 9 s: the pending order is never dropped before the limit.
        for (t in listOf(1_000L, 3_500L, 6_000L, 9_000L)) assertEquals(Keep, p.report(q0, n0, "cur", t))
        assertEquals(null, p.expired(9_900))
        assertEquals(Match, p.report(finalQ, finalN, "cur", 9_500))
        // ...and a silent engine is let go only at the limit.
        assertEquals("no final report in 10s", move().expired(10_000))
    }

    @Test fun aStaleReportAfterTheMatchIsIgnoredForAWhile() {
        val p = move()
        assertEquals(Match, p.report(finalQ, finalN, "cur", 500))
        assertEquals(Keep, p.report(q0, n0, "cur", 700))                          // older state, arriving late
        assertEquals(Keep, p.report(q0, listOf("n0", "n2"), "cur", 900))          // and a step of the move
        assertEquals(Keep, p.report(finalQ, finalN, "cur", 1_000))
        assertEquals("settled", p.expired(500 + QueueDragMath.GRACE_MS))
        assertEquals(QueueDragMath.Verdict.Drop("settled"), p.report(finalQ, finalN, "cur", 500 + QueueDragMath.GRACE_MS))
    }

    @Test fun anotherOrderOrSongEndsIt() {
        assertTrue(move().report(listOf("x"), listOf("y"), "cur", 10) is QueueDragMath.Verdict.Drop)
        assertEquals(QueueDragMath.Verdict.Drop("another song plays"), move().report(q0, n0, "other", 10))
        val p = move(); p.report(finalQ, finalN, "cur", 10)
        assertTrue(p.report(listOf("q0"), listOf("n0", "n2"), "cur", 20) is QueueDragMath.Verdict.Drop) // changed after the match
    }

    @Test fun aSameSectionMoveEndsOnItsFinalReportOnly() {
        val n = listOf("n0", "n1", "n2", "n3")
        val p = move("n0", emptyList(), n, Place(false, 3))
        assertEquals(listOf("n1", "n2", "n3", "n0"), p.next)
        assertEquals(Keep, p.report(emptyList(), n, "cur", 10))
        assertEquals(Match, p.report(emptyList(), listOf("n1", "n2", "n3", "n0"), "cur", 20))
    }

    @Test fun quickDropsChainTheirReports() {
        val first = move()
        // A second drag, made before anything was reported: n2 into the queue, first.
        val second = move("n2", finalQ, finalN, Place(true, 0), at = 300, prev = first.detach())
        assertEquals(listOf("n2", "q0", "n1", "q1"), second.queued); assertEquals(listOf("n0"), second.next)
        assertEquals(Keep, second.report(q0, n0, "cur", 400))                     // before the first
        assertEquals(Keep, second.report(q0, listOf("n0", "n2"), "cur", 500))     // half-way through the first
        assertEquals(Keep, second.report(finalQ, finalN, "cur", 600))             // the first done, the second not yet
        assertEquals(Keep, second.report(finalQ, listOf("n0"), "cur", 700))       // the second half-way
        assertEquals(Match, second.report(second.queued.map { it }, second.next, "cur", 800))
    }

    @Test fun anotherSongPlayingEndsThePendingOrder() {
        assertTrue(move().report(listOf("x"), listOf("y"), "other", 1) is QueueDragMath.Verdict.Drop)
    }

}
