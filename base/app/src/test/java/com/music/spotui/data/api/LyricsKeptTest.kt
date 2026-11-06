package com.music.spotui.data.api

import com.music.spotui.data.entity.LyricLine
import com.music.spotui.data.entity.Lyrics
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsKeptTest {
    private val lines = listOf(LyricLine(0, "a"), LyricLine(1_000, "b"))

    @Test fun syncedAndPlainLyricsAreKeptWithADownload() {
        assertTrue(LyricsApi.keepable(Lyrics(lines, synced = true)))
        assertTrue(LyricsApi.keepable(Lyrics(lines, synced = false)))
    }

    @Test fun aFreePreviewOrNothingIsNotKept() {
        assertFalse(LyricsApi.keepable(Lyrics(lines, synced = true, uncapped = false)))
        assertFalse(LyricsApi.keepable(Lyrics(emptyList(), synced = true)))
    }
}
