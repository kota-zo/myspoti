package com.music.spotui.di

import com.music.spotui.di.MirrorRules.Follow
import com.music.spotui.di.MirrorRules.Local
import com.music.spotui.di.MirrorRules.Want
import com.music.spotui.di.MirrorRules.Web
import com.music.spotui.di.QueueSyncRules.Insert
import com.music.spotui.di.QueueSyncRules.Plan
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What the queue sync and the Connect mirror decide today when another device or a late answer
 * of the page is involved, pinned down before any refactoring.
 */
class PlayerOrchestrationCharacterizationTest {
    private val a = "spotify:track:4uLU6hMCjMI75M1A2tKUQC"
    private val b = "spotify:track:7ouMYWpwJ422jRcDASZB7P"
    private val c = "spotify:track:0VjIjW4GlUZAMYd2vXMi3b"
    private val d = "spotify:track:1301WleyT98MSxVHPZCA6M"
    private val foreign = "spotify:track:3n3Ppam7vgaVa1iaRUc9Lp"

    private fun owns(vararg uris: String) = uris.groupingBy { it }.eachCount()

    @Test fun `the queue sync never removes a song another device queued`() {
        // Spotify's queue: the sync's a and b, a song queued from another device between them.
        val page = listOf(a, foreign, b)
        // The local queue lost b and gained c: b goes, c is added, the other device's song stays.
        val plan = QueueSyncRules.plan(listOf(a, c), page, owns(a, b))
        assertEquals(Plan(listOf(2), listOf(Insert(listOf(c), null))), plan)
        // The local queue cleared: only what the sync queued goes.
        assertEquals(listOf(0, 2), QueueSyncRules.plan(emptyList(), page, owns(a, b)).remove)
        // The other device queued a copy of a song the sync queued once: one copy is the sync's, one stays.
        val twice = QueueSyncRules.plan(emptyList(), listOf(a, a), owns(a))
        assertEquals(1, twice.remove.size)
    }

    @Test fun `a late state of a song the mirror sent is not taken for a remote skip`() {
        // The mirror sent c, then b (fast skips); the local player is on b, whose previous song is c.
        val local = Local(b, playing = true, positionMs = 3_000, durationMs = 200_000, nextUri = d, previousUri = c)
        val want = Want(b, paused = false)
        val late = Web(c, paused = false, positionMs = 6_000)
        // Past the echo window, c landing late is the mirror's own answer: b is sent again, never
        // followed as "Previous" (the local player's neighbour) or as a pick from another device.
        assertEquals(Follow.Resend, MirrorRules.onWeb(late, want, local, quiet = false, landed = true, predictedMs = null,
            previousUri = c, recentUris = listOf(c, b)))
        // A song the mirror sent that is not the local neighbour: still sent again, not followed as a remote pick.
        assertEquals(Follow.Resend, MirrorRules.onWeb(Web(a, paused = false, positionMs = 0), want, local, quiet = false, landed = true,
            predictedMs = null, previousUri = c, recentUris = listOf(a, c)))
        // During the echo window it is not followed either.
        assertEquals(Follow.Nothing, MirrorRules.onWeb(late, want, local, quiet = true, landed = true, predictedMs = null,
            previousUri = c, recentUris = listOf(c, b)))
        // The same state of a song the mirror did not send lately is a remote move, followed.
        assertEquals(Follow.Previous, MirrorRules.onWeb(late, want, local, quiet = false, landed = true, predictedMs = null, previousUri = c))
    }
}
