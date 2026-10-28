package com.music.spotui.di

import com.music.spotui.di.QueueSyncRules.Insert
import com.music.spotui.di.QueueSyncRules.Plan
import com.music.spotui.di.QueueSyncRules.Way
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueSyncRulesTest {
    private val a = "spotify:track:a"
    private val b = "spotify:track:b"
    private val c = "spotify:track:c"
    private val d = "spotify:track:d"
    private val f = "spotify:track:foreign"

    private fun owns(vararg uris: String) = uris.groupingBy { it }.eachCount()

    // ── Spotify's queue brought to the local one ──

    @Test fun `in step nothing to do`() {
        assertTrue(QueueSyncRules.plan(listOf(a, b), listOf(a, b), owns(a, b)).empty)
        assertTrue(QueueSyncRules.plan(emptyList(), emptyList(), emptyMap()).empty)
    }

    @Test fun `add to queue goes at the end`() {
        assertEquals(Plan(emptyList(), listOf(Insert(listOf(c), null))), QueueSyncRules.plan(listOf(a, b, c), listOf(a, b), owns(a, b)))
        assertEquals(Plan(emptyList(), listOf(Insert(listOf(a, b), null))), QueueSyncRules.plan(listOf(a, b), emptyList(), emptyMap()))
    }

    @Test fun `play next goes before the first queued item, a list in its order`() {
        assertEquals(Plan(emptyList(), listOf(Insert(listOf(c, d), 0))), QueueSyncRules.plan(listOf(c, d, a, b), listOf(a, b), owns(a, b)))
    }

    @Test fun `a removal here removes the song there`() {
        assertEquals(Plan(listOf(1), emptyList()), QueueSyncRules.plan(listOf(a, c), listOf(a, b, c), owns(a, b, c)))
    }

    @Test fun `clear queue removes only what the sync queued`() {
        assertEquals(Plan(listOf(0, 2), emptyList()), QueueSyncRules.plan(emptyList(), listOf(a, f, b), owns(a, b)))
    }

    @Test fun `another device's queued song stays where it is`() {
        val plan = QueueSyncRules.plan(listOf(a, b, c), listOf(a, f, b), owns(a, b))
        assertEquals(Plan(emptyList(), listOf(Insert(listOf(c), null))), plan)
    }

    @Test fun `a reorder is a removal and an insertion`() {
        // b moved before a: a matched first, b found nowhere after it, so the old b goes and a new one is added after a.
        assertEquals(Plan(listOf(0), listOf(Insert(listOf(b), null))), QueueSyncRules.plan(listOf(a, b), listOf(b, a), owns(a, b)))
        assertEquals(Plan(listOf(0), listOf(Insert(listOf(a), 2))), QueueSyncRules.plan(listOf(b, a, c), listOf(a, b, c), owns(a, b, c)))
    }

    @Test fun `a song queued twice is two copies`() {
        assertEquals(Plan(emptyList(), listOf(Insert(listOf(a), null))), QueueSyncRules.plan(listOf(a, a), listOf(a), owns(a)))
        assertEquals(Plan(listOf(1), emptyList()), QueueSyncRules.plan(listOf(a), listOf(a, a), owns(a, a)))
        // Owned once: the other copy is another device's.
        assertTrue(QueueSyncRules.plan(emptyList(), listOf(a, a), owns(a)).remove.size == 1)
    }

    @Test fun `the copy the mirror is about to play from the queue is spared`() {
        // The local player went on to a: the copy stays until the mirror skips to it (it consumes it).
        assertTrue(QueueSyncRules.plan(listOf(b), listOf(a, b), owns(a, b), current = a).empty)
        // Once the mirror is on a, a copy left is a duplicate and goes.
        assertEquals(Plan(listOf(0), emptyList()), QueueSyncRules.plan(listOf(b), listOf(a, b), owns(a, b), current = null))
        // Spared, never matched: a queued again after it is a second copy.
        assertEquals(Plan(emptyList(), listOf(Insert(listOf(a), null))), QueueSyncRules.plan(listOf(b, a), listOf(a, b), owns(a, b), current = a))
    }

    @Test fun `a queue wiped by a play is put back whole`() {
        assertEquals(Plan(emptyList(), listOf(Insert(listOf(a, b, c), null))), QueueSyncRules.plan(listOf(a, b, c), emptyList(), owns(a, b, c)))
    }

    // ── A local player that starts takes Spotify's queue ──

    @Test fun `the takeover list has Spotify's queue after the song already`() {
        assertEquals(listOf(0, 1), QueueSyncRules.adopt(listOf(a, b, c, d), listOf(a, b)))
    }

    @Test fun `queued songs the list does not have are added, in Spotify's order`() {
        assertEquals(listOf(null, null), QueueSyncRules.adopt(listOf(c, d), listOf(a, b)))
        // b left out of the list (once per song): added where Spotify has it.
        assertEquals(listOf(0, null, 1), QueueSyncRules.adopt(listOf(a, c, d), listOf(a, b, c)))
        assertEquals(emptyList<Int?>(), QueueSyncRules.adopt(listOf(a), emptyList()))
    }

    // ── How the mirror puts the page on a new song ──

    @Test fun `a queued song is skipped to, not played anew`() {
        assertEquals(Way.Skip(1), QueueSyncRules.way(b, pageUri = c, pagePlaying = true, pageQueued = listOf(a, b)))
        assertEquals(Way.Play, QueueSyncRules.way(d, pageUri = c, pagePlaying = true, pageQueued = listOf(a, b)))
    }

    @Test fun `a page already on the song is only kept in step`() {
        assertEquals(Way.Already, QueueSyncRules.way(a, pageUri = a, pagePlaying = true, pageQueued = listOf(b)))
        assertEquals(Way.Play, QueueSyncRules.way(a, pageUri = a, pagePlaying = false, pageQueued = listOf(b)))
    }

    @Test fun `playback is pulled here only before the run's first play`() {
        assertTrue(QueueSyncRules.pullsHere(firstPlay = true, elsewhere = true))
        assertFalse(QueueSyncRules.pullsHere(firstPlay = false, elsewhere = true))
        assertFalse(QueueSyncRules.pullsHere(firstPlay = true, elsewhere = false))
    }
}
