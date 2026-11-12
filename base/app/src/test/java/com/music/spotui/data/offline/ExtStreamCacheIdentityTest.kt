/* SPDX-License-Identifier: GPL-3.0-only */
package com.music.spotui.data.offline

import app.myspoti.extensions.AudioStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * A song's cache keeps one file: another answer of the extension for it (another master, depth or
 * rate) drops the bytes cached of the first, which read with the second gave a FLAC whose frames no
 * longer matched its header (the song cut off).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ExtStreamCacheIdentityTest {
    private fun flac(bits: Int, rate: Int, url: String = "https://example.invalid/a") =
        AudioStream(url = url, headers = emptyMap(), mimeType = "audio/flac", durationMs = 165_000, providerId = "audio.example",
            bitDepth = bits, sampleRate = rate, codec = "FLAC", source = "Example")

    @Test fun anotherFileIsAnotherIdentityAndANewAddressIsNot() {
        assertEquals(ExtStreamCache.identity(flac(16, 44_100)), ExtStreamCache.identity(flac(16, 44_100, url = "https://example.invalid/b")))
        assertNotEquals(ExtStreamCache.identity(flac(16, 44_100)), ExtStreamCache.identity(flac(24, 48_000)))
    }

    @Test fun theCacheGoesOnlyWhenTheFileChanges() {
        val app = RuntimeEnvironment.getApplication()
        // Never seen: whatever is cached for the song is not known to be this file.
        assertTrue(ExtStreamCache.answered(app, "t1", flac(16, 44_100)))
        // The same file again (a new address, a retry): the cache stays.
        assertFalse(ExtStreamCache.answered(app, "t1", flac(16, 44_100, url = "https://example.invalid/b")))
        // Another master: dropped, and then kept for it.
        assertTrue(ExtStreamCache.answered(app, "t1", flac(24, 48_000)))
        assertFalse(ExtStreamCache.answered(app, "t1", flac(24, 48_000)))
        // Another song is its own.
        assertTrue(ExtStreamCache.answered(app, "t2", flac(24, 48_000)))
    }

    @Test fun onlyAKnownSameFileKeepsTheBytes() {
        assertTrue(ExtStreamCache.mustDrop(null, "a"))
        assertTrue(ExtStreamCache.mustDrop("a", "b"))
        assertFalse(ExtStreamCache.mustDrop("a", "a"))
    }
}
