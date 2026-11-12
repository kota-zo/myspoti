package app.myspoti.spotify.sync

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SpotifySyncRulesTest {
    private val a = "spotify:track:4uLU6hMCjMI75M1A2tKUQC"

    @Test fun `only a Spotify track id is played on the page`() {
        assertTrue(SpotifySyncRules.isTrackId("4uLU6hMCjMI75M1A2tKUQC"))
        assertFalse(SpotifySyncRules.isTrackId(""))
        assertFalse(SpotifySyncRules.isTrackId(null))
        assertFalse(SpotifySyncRules.isTrackId("not-an-id"))
    }

    @Test fun `an artist context or one that missed plays the song alone`() {
        val artist = "spotify:artist:7jVv8c5Fj3E9VhNjxT4snq"
        val playlist = "spotify:playlist:37i9dQZF1DXcBWIGoYBM5M"
        assertEquals(a, SpotifySyncRules.playContext(artist, a, emptySet()))
        assertEquals(playlist, SpotifySyncRules.playContext(playlist, a, emptySet()))
        assertEquals(a, SpotifySyncRules.playContext(playlist, a, setOf(playlist)))
        assertEquals(a, SpotifySyncRules.playContext("", a, emptySet()))
    }

    @Test fun `a page back after an ad or a reload keeps the list of the local queue (#144)`() {
        val playlist = "spotify:playlist:37i9dQZF1DXcBWIGoYBM5M"
        // The same context the start used: the song sent again is played in its list, not alone.
        assertEquals(playlist, SpotifySyncRules.playContext(playlist, a, emptySet()))
        // A list that put the page on another song stays out of it, as before the reload.
        assertEquals(a, SpotifySyncRules.playContext(playlist, a, setOf(playlist)))
    }
}
