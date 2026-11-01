package com.music.spotui.di

import androidx.media3.common.PlaybackException
import com.music.spotui.di.DeletedDownloads.Fix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeletedDownloadsTest {
    @Test fun `the song playing keeps its file, the others play from their source`() {
        assertEquals(listOf(Fix.STREAM, Fix.KEEP, Fix.STREAM, Fix.STREAM),
            DeletedDownloads.plan(listOf("a", "b", "c", "d"), current = 1, online = true))
    }

    @Test fun `songs whose download was not deleted are untouched`() {
        assertEquals(listOf(Fix.KEEP, Fix.STREAM, Fix.KEEP),
            DeletedDownloads.plan(listOf(null, "b", null), current = 0, online = true))
    }

    @Test fun `offline the deleted songs are left out`() {
        assertEquals(listOf(Fix.KEEP, Fix.DROP, Fix.KEEP),
            DeletedDownloads.plan(listOf("a", "b", null), current = 0, online = false))
    }

    @Test fun `a deleted song with no Spotify id is left out`() {
        assertEquals(listOf(Fix.KEEP, Fix.DROP), DeletedDownloads.plan(listOf("a", ""), current = 0, online = true))
    }

    @Test fun `a missing file is told by its code or its cause`() {
        assertTrue(DeletedDownloads.isMissingFile(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND, null))
        assertTrue(DeletedDownloads.isMissingFile(PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            java.io.IOException("open failed", java.io.FileNotFoundException("/x/audio/a.enc"))))
        assertFalse(DeletedDownloads.isMissingFile(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, java.io.IOException("reset")))
    }
}
