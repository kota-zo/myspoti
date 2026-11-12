package com.music.spotui.data.entity

import com.music.spotui.data.api.Api
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeShortcutsTest {
    private fun playlist(id: String) = HomeItem.Playlist(name = id, imageUrl = "", subtitle = "", id = id)

    @Test fun likedSongsLeadsTheGridAndTheGridStaysAtEight() {
        val grid = (1..8).map { playlist("p$it") }
        val shown = grid.withLikedFirst("Liked Songs")
        assertEquals(8, shown.size)
        assertEquals(Api.HomeCache.LIKED_SONGS_ID, (shown[0] as HomeItem.Playlist).id)
        assertEquals("Liked Songs", shown[0].name)
        assertEquals(grid.take(7), shown.drop(1))
    }

    @Test fun aGridThatHasLikedSongsAlreadyGetsNoSecondTile() {
        val grid = listOf(playlist("p1"), playlist(Api.HomeCache.LIKED_SONGS_ID), playlist("p2"))
        assertEquals(grid, grid.withLikedFirst("Liked Songs"))
    }

    @Test fun aFeedLedByATitledSectionStillShowsLikedSongs() {
        val alone = homeShortcuts(null, feedEmpty = false, facet = "", likedName = "Liked Songs")
        assertEquals(listOf(Api.HomeCache.LIKED_SONGS_ID), alone?.map { (it as HomeItem.Playlist).id })
        assertEquals(1, homeShortcuts(null, feedEmpty = false, facet = "music-chip", likedName = "L")?.size)
        assertEquals(null, homeShortcuts(null, feedEmpty = false, facet = "podcasts-chip", likedName = "L"))
        assertEquals(null, homeShortcuts(null, feedEmpty = true, facet = "", likedName = "L"))
    }
}
