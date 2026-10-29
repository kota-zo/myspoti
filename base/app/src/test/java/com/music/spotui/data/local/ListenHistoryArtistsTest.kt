package com.music.spotui.data.local

import org.junit.Assert.assertEquals
import org.junit.Test

class ListenHistoryArtistsTest {
    @Test
    fun splitsOnlyWhenNamesAndIdsAgree() {
        assertEquals(listOf("A", "B"), ListenHistory.artistsOf("A, B", listOf("1", "2")))
        assertEquals(listOf("Tyler, The Creator"), ListenHistory.artistsOf("Tyler, The Creator", listOf("1")))
        assertEquals(listOf("A, B"), ListenHistory.artistsOf("A, B", emptyList()))
    }

    @Test
    fun everyCreditedArtistCountsTheListen() {
        fun l(artists: List<String>) = ListenHistory.Listen(0, "k", "t", artists.joinToString(", "), "", "", 60_000, ListenHistory.Source.SPOTIFY, artists)
        val top = ListenHistory.rankArtists(listOf(l(listOf("A", "B")), l(listOf("B")), l(listOf("C"))))
        assertEquals("B", top.first().name)
        assertEquals(2, top.first().count)
        assertEquals(setOf("A", "B", "C"), top.map { it.name }.toSet())
    }
}
