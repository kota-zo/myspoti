/* SPDX-License-Identifier: GPL-3.0-only */
package com.music.spotui.data.catalog

import app.myspoti.api.Track
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reported: a search's song of another provider said "Not found on Spotify" when tapped, and
 * played when tapped again. A lookup that got no answer is tried again, and never said absent.
 */
class SpotifyMatchTest {
    private val found = SpotifyMatch.Found("S1", Track("spotify:track:S1", "Song", 200_000, emptyList(), null))

    @Test fun aLookupWithoutAnswerIsTriedAgainUntilItAnswers() = runBlocking {
        val answers = ArrayDeque(listOf<SpotifyMatch>(SpotifyMatch.Unavailable("timed out"), SpotifyMatch.Unavailable("resting"), found))
        var asked = 0
        val r = SpotifyMatch.withRetries(longArrayOf(1, 1)) { asked++; answers.removeFirst() }
        assertEquals(found, r)
        assertEquals(3, asked)
    }

    @Test fun anAnswerIsNotAskedAgain() = runBlocking {
        var asked = 0
        assertEquals(SpotifyMatch.Absent, SpotifyMatch.withRetries(longArrayOf(1, 1)) { asked++; SpotifyMatch.Absent })
        assertEquals(1, asked)
    }

    @Test fun noAnswerAtAllSaysSpotifyDidNotAnswerNotThatTheSongIsMissing() = runBlocking {
        val r = SpotifyMatch.withRetries(longArrayOf(1, 1)) { SpotifyMatch.Unavailable("network") }
        assertTrue(r is SpotifyMatch.Unavailable)
        assertEquals(app.myspoti.engine.R.string.metadata_spotify_no_answer, SpotifyMatch.message(r))
        assertEquals(app.myspoti.engine.R.string.metadata_not_on_spotify, SpotifyMatch.message(SpotifyMatch.Absent))
        assertNull(SpotifyMatch.message(found))
    }
}
