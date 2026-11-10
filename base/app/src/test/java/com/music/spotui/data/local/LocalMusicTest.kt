package com.music.spotui.data.local

import org.junit.Assert.assertEquals
import org.junit.Test

class LocalMusicTest {
    @Test
    fun folderFromPathMatchesMediaStoreRelativePath() {
        assertEquals("Music/Rock/", LocalMusic.folderFromPath("/storage/emulated/0/Music/Rock/song.mp3"))
        assertEquals("", LocalMusic.folderFromPath("/storage/emulated/0/song.mp3"))
        assertEquals("Music/", LocalMusic.folderFromPath("/storage/ABCD-1234/Music/track.flac"))
        assertEquals("", LocalMusic.folderFromPath("/storage/ABCD-1234/track.mp3"))
        assertEquals("Download/", LocalMusic.folderFromPath("/storage/emulated/legacy/Download/podcast.m4a"))
        assertEquals("Music/", LocalMusic.folderFromPath("/sdcard/Music/song.mp3"))
        assertEquals("", LocalMusic.folderFromPath(null))
    }
}
