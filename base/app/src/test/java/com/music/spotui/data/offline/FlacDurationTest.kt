package com.music.spotui.data.offline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FlacDurationTest {

    private fun fakeFlac(sampleRate: Int = 44_100, samples: Long = sampleRate * 200L): ByteArray {
        val head = ByteArray(42)
        "fLaC".toByteArray().copyInto(head)
        head[4] = 0x80.toByte() // last metadata block, STREAMINFO
        head[7] = 34
        // STREAMINFO fields a real FLAC carries, packed as the parser expects:
        // 20 bits sample rate | 3 channels | 5 bits/sample | 36-bit total samples.
        val packed = (sampleRate.toLong() and 0xFFFFF) shl 44 or
            (1L shl 41) or // stereo (channels - 1)
            (15L shl 36) or ((samples and 0xFFFFFFFFL))
        for (i in 0 until 8) head[18 + i] = (packed ushr (56 - 8 * i)).toByte()
        return head
    }

    @Test
    fun readsTheDuration() {
        assertEquals(210, OfflineDownloader.flacDurationSec(fakeFlac(44_100, 44_100L * 210)))
    }

    @Test
    fun notFlacOrTooShortIsNull() {
        assertNull(OfflineDownloader.flacDurationSec(ByteArray(42)))
        assertNull(OfflineDownloader.flacDurationSec(ByteArray(20)))
    }
}
