package com.music.spotui.di

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ShuffleOrderTest {
    private val list = (1..40).map { "s$it" }

    @Test fun everySongOnceAndThePickedOneFirst() {
        repeat(50) { seed ->
            val order = ShuffleOrder.draw(list, "s17", Random(seed))
            assertEquals("s17", order.first())
            assertEquals(list.sorted(), order.sorted())
        }
        // Nothing picked (or a song not in the list): the list shuffled as it is.
        assertEquals(list.sorted(), ShuffleOrder.draw(list, null, Random(1)).sorted())
        assertEquals(list.sorted(), ShuffleOrder.draw(list, "x", Random(1)).sorted())
    }

    @Test fun theDrawIsUniform() {
        // Reported: Spotify's shuffle came back to the same ~15 songs of 40. Each song is about as
        // often in each place: the first ten of 4000 draws hold every song about 1000 times.
        val random = Random(7)
        val seen = HashMap<String, Int>()
        repeat(4000) { ShuffleOrder.draw(list, null, random).take(10).forEach { seen[it] = (seen[it] ?: 0) + 1 } }
        assertEquals(40, seen.size)
        assertTrue(seen.toString(), seen.values.all { it in 850..1150 })
    }

    @Test fun aPickPlaysNextAndTheRestKeepsItsOrder() {
        val order = listOf("A", "B", "C", "D", "E", "F")
        // C plays; E is picked: it comes right after C, nothing else moves.
        assertEquals(listOf("A", "B", "C", "E", "D", "F") to 3, ShuffleOrder.pick(order, 2, "E"))
        // A song heard already is heard again now, right after the one playing.
        assertEquals(listOf("B", "C", "A", "D", "E", "F") to 2, ShuffleOrder.pick(order, 2, "A"))
        // The next song, or the one playing: nothing moves.
        assertEquals(order to 3, ShuffleOrder.pick(order, 2, "D"))
        assertEquals(order to 2, ShuffleOrder.pick(order, 2, "C"))
        assertNull(ShuffleOrder.pick(order, 2, "X"))
    }

    @Test fun theListChangingKeepsTheOrderAndNewSongsComeThisRound() {
        val order = listOf("A", "B", "C", "D", "E")
        // B and E left the list, X and Y joined it, C plays.
        repeat(20) { seed ->
            val (now, at) = ShuffleOrder.refresh(order, 2, listOf("Y", "A", "C", "D", "X"), Random(seed))
            assertEquals("C", now[at])
            assertEquals(listOf("A", "C"), now.take(at + 1))
            assertEquals(listOf("D"), now.drop(at + 1).filter { it in order })
            assertEquals(listOf("A", "C", "D", "X", "Y"), now.sorted())
        }
        // The song playing left the list: the order goes on after the last song heard.
        val (now, at) = ShuffleOrder.refresh(order, 2, listOf("A", "B", "D", "E"), Random(1))
        assertEquals(listOf("A", "B", "D", "E") to 1, now to at)
        // Nothing changed: the same order.
        assertEquals(order to 2, ShuffleOrder.refresh(order, 2, order.reversed(), Random(1)))
    }
}
