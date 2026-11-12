package com.music.spotui.extensions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The audio source picked in the player for the session: first, then the user's order; gone with the source. */
class SessionSourceTest {
    private val a = "io.github.a.hifi"
    private val b = "io.github.b.tidal"
    private val c = "io.github.c.below"
    private val saved = listOf(a, b, ACCOUNT_SOURCE, c)
    private val on = { _: String -> true }

    @Test fun thePickedSourceComesFirstThenTheUsersOrder() {
        assertEquals(listOf(b, a, ACCOUNT_SOURCE, c), SessionSource.effective(saved, b))
        // One under Spotify, not asked in the user's order: it is, first.
        assertEquals(listOf(c, a, b, ACCOUNT_SOURCE), SessionSource.effective(saved, c))
        // Spotify first: no extension is asked before it.
        assertEquals(listOf(ACCOUNT_SOURCE, a, b, c), SessionSource.effective(saved, ACCOUNT_SOURCE))
    }

    @Test fun withoutOneTheOrderIsTheUsers() {
        assertEquals(saved, SessionSource.effective(saved, null))
        // Back to my order: nothing picked any more.
        assertEquals(saved, SessionSource.effective(saved, SessionSource.valid(null, saved, on)))
    }

    @Test fun aSourceTurnedOffOrRemovedLeavesIt() {
        // Turned off: no longer a lead, the user's order is back.
        assertNull(SessionSource.valid(b, saved, { it != b }))
        assertEquals(saved, SessionSource.effective(saved, SessionSource.valid(b, saved, { it != b })))
        // Removed (or no longer an audio source): not in the order, not a lead.
        val without = saved - b
        assertNull(SessionSource.valid(b, without, on))
        assertEquals(without, SessionSource.effective(without, b))
        // Spotify is always a source.
        assertEquals(ACCOUNT_SOURCE, SessionSource.valid(ACCOUNT_SOURCE, saved, { false }))
    }

    @Test fun pickingTheSourceTheOrderAsksFirstIsTheUsersOrder() {
        assertNull(SessionSource.pick(a, saved, on))
        assertEquals(b, SessionSource.pick(b, saved, on))
        assertEquals(ACCOUNT_SOURCE, SessionSource.pick(ACCOUNT_SOURCE, saved, on))
        // The first one turned off: Spotify is asked first after it, picking it changes nothing.
        assertNull(SessionSource.pick(ACCOUNT_SOURCE, listOf(a, ACCOUNT_SOURCE), { it != a }))
        // A source turned off cannot be picked.
        assertNull(SessionSource.pick(b, saved, { it != b }))
    }

    @Test fun theSheetOffersSpotifyAndTheSourcesOnInTheOrderAskedNow() {
        assertEquals(listOf(a, b, ACCOUNT_SOURCE, c), SessionSource.choices(saved, null, on))
        assertEquals(listOf(c, a, ACCOUNT_SOURCE), SessionSource.choices(saved, c, { it != b }))
        // The pick turned off meanwhile: the user's order, without it.
        assertEquals(listOf(a, ACCOUNT_SOURCE, c), SessionSource.choices(saved, b, { it != b }))
    }

    @Test fun spotifyPickedWhilePausedPlaysTheSongThereAtTheNextPlayOnly() {
        // Still on that song, Spotify still first: the next play plays it on Spotify.
        assertTrue(SessionSource.spotifyDue("4uLU6hMCjMI75M1A2tKUQC", "4uLU6hMCjMI75M1A2tKUQC", ACCOUNT_SOURCE))
        // Nothing picked while paused, or a song with no Spotify id.
        assertFalse(SessionSource.spotifyDue(null, "4uLU6hMCjMI75M1A2tKUQC", ACCOUNT_SOURCE))
        assertFalse(SessionSource.spotifyDue("", "", ACCOUNT_SOURCE))
        // Another song meanwhile (a next, a pick): it plays where it is.
        assertFalse(SessionSource.spotifyDue("4uLU6hMCjMI75M1A2tKUQC", "7ouMYWpwJ422jRcDASZB7P", ACCOUNT_SOURCE))
        // "Back to my order" meanwhile, an extension first again: the song stays on it.
        assertFalse(SessionSource.spotifyDue("4uLU6hMCjMI75M1A2tKUQC", "4uLU6hMCjMI75M1A2tKUQC", a))
    }
}
