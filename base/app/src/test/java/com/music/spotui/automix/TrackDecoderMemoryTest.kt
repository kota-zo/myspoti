package com.music.spotui.automix

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reported: an OutOfMemoryError on the analysis thread closed the app (256 MB heap). */
class TrackDecoderMemoryTest {
    private val mb = 1L shl 20

    @Test fun `the signals of the longest song analysed fit in a third of the heap`() {
        for (heap in listOf(192 * mb, 256 * mb, 512 * mb)) {
            val bytes = TrackDecoder.maxSeconds(heap).toLong() * TrackDecoder.RATE * 2 * 4
            assertTrue("heap ${heap / mb} MB: $bytes bytes", bytes <= heap / 3)
        }
        // A 256 MB heap still takes an 8-minute song; a large one the 15 minutes as before.
        assertTrue(TrackDecoder.maxSeconds(256 * mb) >= 8 * 60)
        assertEquals(TrackDecoder.MAX_SECONDS, TrackDecoder.maxSeconds(1024 * mb))
    }
}
