package com.music.spotui.ui.immersive.lyrics

import com.music.spotui.di.LocalQueueOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueDragMathTest {
    @Test fun slotCountsMidpointsAbove() {
        val mids = listOf(30f, 90f, 150f)
        assertEquals(0, QueueDragMath.slot(10f, mids))
        assertEquals(2, QueueDragMath.slot(100f, mids))
        assertEquals(3, QueueDragMath.slot(500f, mids))
    }

    @Test fun rowsStepAsideBetweenFromAndSlot() {
        // row 1 of 5 dragged down over gap 3 of the others: rows 2 and 3 step up
        assertEquals(listOf(0, 0, -1, -1, 0), (0..4).map { QueueDragMath.step(it, 1, 3) })
        // row 3 dragged up over gap 1: rows 1 and 2 step down
        assertEquals(listOf(0, 1, 1, 0, 0), (0..4).map { QueueDragMath.step(it, 3, 1) })
        assertEquals(listOf(0, 0, 0, 0, 0), (0..4).map { QueueDragMath.step(it, 2, 2) })
    }

    @Test fun placeSplitsAtTheSections() {
        assertEquals(QueueDragMath.Place(true, 1), QueueDragMath.place(1, 3, false))
        assertEquals(QueueDragMath.Place(false, 2), QueueDragMath.place(5, 3, true))
        assertEquals(QueueDragMath.Place(true, 3), QueueDragMath.place(3, 3, true))
        assertEquals(QueueDragMath.Place(false, 0), QueueDragMath.place(3, 3, false))
    }

    @Test fun movedKeepsTheDroppedOrder() {
        assertEquals(listOf("b", "c", "a"), QueueDragMath.moved(listOf("a", "b", "c"), 0, 2))
        assertEquals(listOf("c", "a", "b"), QueueDragMath.moved(listOf("a", "b", "c"), 2, 0))
    }

    @Test fun edgeStepGrowsTowardsTheEdge() {
        assertEquals(0f, QueueDragMath.edgeStep(500f, 1000f, 100f, 20f), 0f)
        assertEquals(-10f, QueueDragMath.edgeStep(50f, 1000f, 100f, 20f), 0.001f)
        assertEquals(20f, QueueDragMath.edgeStep(1000f, 1000f, 100f, 20f), 0.001f)
        assertTrue(QueueDragMath.edgeStep(20f, 1000f, 100f, 20f) < QueueDragMath.edgeStep(80f, 1000f, 100f, 20f))
    }

    // ── The panel's expected order against what the local player's move logic ends with ──

    /** cur, then [nq] queued songs q0.., then [nn] list songs n0.. */
    private fun local(nq: Int, nn: Int): Triple<List<String>, Set<String>, Int> {
        val queued = (0 until nq).map { "q$it" }
        return Triple(listOf("cur") + queued + (0 until nn).map { "n$it" }, queued.toSet(), 0)
    }

    @Test fun everyDropEndsInThePanelsOrderOnTheLocalPlayer() {
        var checked = 0
        for (nq in 0..3) for (nn in 0..3) {
            val (order, queuedSet, cur) = local(nq, nn)
            val rows = order.drop(1)
            for (dragged in rows) for (above in listOf(true, false)) {
                val oq = rows.filter { it in queuedSet && it != dragged }
                val on = rows.filter { it !in queuedSet && it != dragged }
                for (slot in 0..(oq.size + on.size)) {
                    val place = QueueDragMath.place(slot, oq.size, above)
                    val (eq, en) = QueueDragMath.apply(oq, on, dragged, place)
                    if (eq == rows.filter { it in queuedSet } && en == rows.filter { it !in queuedSet }) continue
                    val into = if (place.toQueue) oq else on
                    val target = QueueDragMath.target(into.size, place.index)
                    val from = order.indexOf(dragged)
                    val wasQueued = dragged in queuedSet
                    val result: Pair<List<String>, Boolean>? = when {
                        place.toQueue && !wasQueued && target == null ->
                            LocalQueueOrder.moveToFront(order, cur, from)?.let { it to true }
                        target == null -> continue
                        else -> LocalQueueOrder.moveBeside(order, cur, from, order.indexOf(into[target.first]), target.second) { it in queuedSet }
                    }
                    val (after, toQueue) = result!!
                    val nowQueued = { x: String -> if (x == dragged) toQueue else x in queuedSet }
                    val tail = after.drop(1)
                    assertEquals("cur stays", "cur", after[0])
                    assertEquals("queued part $dragged slot $slot nq=$nq nn=$nn above=$above", eq, tail.filter(nowQueued))
                    assertEquals("list part $dragged slot $slot nq=$nq nn=$nn above=$above", en, tail.filterNot(nowQueued))
                    assertEquals("queued songs come first", tail.filter(nowQueued), tail.take(eq.size))
                    checked++
                }
            }
        }
        assertTrue(checked > 100)
    }

    @Test fun targetNamesTheNeighbourToDropBesideOrNothing() {
        assertEquals(null, QueueDragMath.target(0, 0))
        assertEquals(0 to false, QueueDragMath.target(3, 0))
        assertEquals(1 to false, QueueDragMath.target(3, 1))
        assertEquals(2 to true, QueueDragMath.target(3, 3))
    }

    @Test fun aQueuedSpotifySongStaysInTheQueue() {
        val into = QueueDragMath.Place(false, 2)
        assertEquals(QueueDragMath.Place(true, 3), QueueDragMath.held(into, 3, canLeaveQueue = false, fromQueue = true))
        assertEquals(into, QueueDragMath.held(into, 3, canLeaveQueue = true, fromQueue = true))
        assertEquals(into, QueueDragMath.held(into, 3, canLeaveQueue = false, fromQueue = false))
    }

    // ── The Spotify queue's reports while a move is applied: stale, half-way, final ──

    private val Match = QueueDragMath.Verdict.Matched
    private val Keep = QueueDragMath.Verdict.Keep

    /** "n1" dragged from the list into the queue between q0 and q1. */
    private val q0 = listOf("q0", "q1")
    private val n0 = listOf("n0", "n1", "n2")
    private val place = QueueDragMath.Place(true, 1)
    private fun move(dragged: String = "n1", q: List<String> = q0, n: List<String> = n0, p: QueueDragMath.Place = place, at: Long = 0L, prev: QueueDragMath.Pending<String>? = null): QueueDragMath.Pending<String> {
        val (eq, en) = QueueDragMath.apply(q, n, dragged, p)
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
        val p = move("n0", emptyList(), n, QueueDragMath.Place(false, 3))
        assertEquals(listOf("n1", "n2", "n3", "n0"), p.next)
        assertEquals(Keep, p.report(emptyList(), n, "cur", 10))
        assertEquals(Match, p.report(emptyList(), listOf("n1", "n2", "n3", "n0"), "cur", 20))
    }

    @Test fun quickDropsChainTheirReports() {
        val first = move()
        // A second drag, made before anything was reported: n2 into the queue, first.
        val second = move("n2", finalQ, finalN, QueueDragMath.Place(true, 0), at = 300, prev = first.detach())
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

    @Test fun steppedAsideRowsAreDrawnWhereTheNewOrderLaysThemOut() {
        // A row drawn shifted by step() rows from its slot must sit exactly at its slot in the new order:
        // then dropping needs no row to move.
        for (size in 2..6) for (from in 0 until size) for (slot in 0 until size) {
            val order = (0 until size).toList()
            val after = QueueDragMath.moved(order, from, slot)
            for (row in order) if (row != from) {
                assertEquals("size=$size from=$from slot=$slot row=$row", row + QueueDragMath.step(row, from, slot), after.indexOf(row))
            }
            assertEquals(slot, after.indexOf(from))
        }
    }
}
