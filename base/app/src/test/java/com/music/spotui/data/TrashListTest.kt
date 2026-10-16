package com.music.spotui.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Hidden songs and artists: what gets skipped, the desktop format, and the brake on skips. */
class TrashListTest {
    private val song = "spotify:track:4uLU6hMCjMI75M1A2tKUQC"
    private val artist = "spotify:artist:0gxyHStUsqpMadRV0Di1Qt"

    @Test fun aHiddenSongOrAnyOfItsArtistsIsSkipped() {
        assertTrue(TrashList.shouldSkip(song, emptyList(), setOf(song), emptySet()))
        assertTrue(TrashList.shouldSkip("spotify:track:other", listOf("spotify:artist:a", artist), emptySet(), setOf(artist)))
        assertFalse(TrashList.shouldSkip("spotify:track:other", listOf("spotify:artist:a"), setOf(song), setOf(artist)))
    }

    @Test fun theExportReadsBackAsTheSameLists() {
        val (songs, artists) = TrashList.parse(TrashList.export(setOf(song), setOf(artist)))
        assertEquals(setOf(song), songs)
        assertEquals(setOf(artist), artists)
    }

    @Test fun spicetifyDesktopsExportIsRead() {
        // As desktop writes it; an entry set to false is not hidden there either.
        val desktop = """{"songs":{"$song":true,"spotify:track:kept":false},"artists":{"$artist":true}}"""
        val (songs, artists) = TrashList.parse(desktop)
        assertEquals(setOf(song), songs)
        assertEquals(setOf(artist), artists)
    }

    @Test(expected = Exception::class) fun anythingElseIsRefused() {
        TrashList.parse("""{"tracks":["x"]}""")
    }

    @Test(expected = Exception::class) fun plainTextIsRefused() {
        TrashList.parse("not a list")
    }

    @Test fun atMostFiveSkipsInTenSeconds() {
        val guard = TrashList.Guard()
        repeat(5) { assertTrue(guard.allow(1_000L + it)) }
        assertFalse(guard.allow(2_000))
        // Once the oldest leaves the window, one more gets in.
        assertTrue(guard.allow(11_001))
    }
}
