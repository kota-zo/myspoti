package com.music.spotui.data.offline

import com.music.spotui.data.preferences.DownloadQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineDownloaderTest {
    private fun flacHeader(rate: Int = 44_100, depth: Int = 16, channels: Int = 2, seconds: Int = 200): ByteArray {
        val head = ByteArray(42)
        "fLaC".toByteArray(Charsets.US_ASCII).copyInto(head)
        head[4] = 0x80.toByte() // Last metadata block, type STREAMINFO.
        head[7] = 34
        val packed = (rate.toLong() shl 44) or ((channels - 1).toLong() shl 41) or
            ((depth - 1).toLong() shl 36) or (rate.toLong() * seconds)
        for (i in 0..7) head[18 + i] = (packed ushr ((7 - i) * 8)).toByte()
        return head
    }

    @Test fun flacQualityComesFromStreamInfo() {
        val cd = OfflineDownloader.flacStreamInfo(flacHeader())!!
        assertEquals(44_100, cd.sampleRate)
        assertEquals(16, cd.bitDepth)
        assertEquals(2, cd.channels)
        assertEquals(200L, cd.durationSeconds)
        val hiRes = OfflineDownloader.flacStreamInfo(flacHeader(96_000, 24, 1, 163))!!
        assertEquals(96_000, hiRes.sampleRate)
        assertEquals(24, hiRes.bitDepth)
        assertEquals(1, hiRes.channels)
        assertEquals(163, OfflineDownloader.flacDurationSec(flacHeader(96_000, 24, 1, 163)))
    }

    @Test fun measuredQualityReplacesFalseAndDuplicateLabels() {
        val cd = OfflineDownloader.flacStreamInfo(flacHeader())!!
        assertEquals("Qobuz 16-bit · 44.1 kHz", OfflineDownloader.downloadSourceLabel("Qobuz 24-bit 24-bit", cd))
        assertEquals("Tidal 16-bit · 44.1 kHz", OfflineDownloader.downloadSourceLabel("Tidal hi-res", cd))
        assertEquals("Example provider 16-bit · 44.1 kHz", OfflineDownloader.downloadSourceLabel("Example provider", cd))
        val hiRes = OfflineDownloader.flacStreamInfo(flacHeader(96_000, 24))!!
        assertEquals("Qobuz 24-bit · 96 kHz", OfflineDownloader.downloadSourceLabel("Qobuz 24-bit", hiRes))
        assertEquals("Qobuz 16-bit · 44.1 kHz", OfflineDownloader.downloadSourceLabel(" Qobuz 24-bit ", cd))
    }

    @Test fun filesAreTriedByFitToTheQualityThenRecordingThenOrder() {
        fun f(format: String, bits: Int? = null, isrc: Boolean = false) = app.myspoti.extensions.DownloadFile(
            url = "https://example.com/x.$format", manifest = null, headers = emptyMap(), format = format, bitDepth = bits,
            sampleRate = null, sizeHint = null, provider = null, isrcMatched = isrc)
        val first = listOf(f("m4a"), f("flac", 24))
        val second = listOf(f("flac", 16), f("flac", 16, isrc = true))
        val order = { quality: DownloadQuality ->
            OfflineDownloader.downloadOrder(quality, listOf(first, second)).map { (i, file) -> "$i:${file.format}/${file.bitDepth}/${file.isrcMatched}" }
        }
        assertEquals(listOf("1:flac/16/true", "1:flac/16/false", "0:flac/24/false", "0:m4a/null/false"), order(DownloadQuality.CD))
        assertEquals(listOf("0:flac/24/false", "1:flac/16/true", "1:flac/16/false", "0:m4a/null/false"), order(DownloadQuality.HI_RES))
        assertEquals(listOf("0:m4a/null/false", "1:flac/16/true", "0:flac/24/false", "1:flac/16/false"), order(DownloadQuality.SMALL))
        assertEquals(OfflineDownloader.BEST_FIT, OfflineDownloader.downloadFit(DownloadQuality.CD, f("flac")))
    }

    @Test fun malformedAndTruncatedFlacHeadersAreRefused() {
        val valid = flacHeader()
        assertNull(OfflineDownloader.flacStreamInfo(valid.copyOf(26)))
        assertNull(OfflineDownloader.flacStreamInfo(valid.copyOf().also { it[0] = 0 }))
        assertNull(OfflineDownloader.flacStreamInfo(valid.copyOf().also { it[4] = 0x81.toByte() }))
        assertNull(OfflineDownloader.flacStreamInfo(valid.copyOf().also { it[7] = 33 }))
        assertNull(OfflineDownloader.flacStreamInfo(flacHeader(rate = 0)))
        assertNull(OfflineDownloader.flacStreamInfo(flacHeader(depth = 1)))
        assertNull(OfflineDownloader.flacStreamInfo(flacHeader(seconds = 0)))
        // STREAMINFO need not be the final metadata block.
        assertEquals(16, OfflineDownloader.flacStreamInfo(valid.copyOf().also { it[4] = 0 })!!.bitDepth)
    }

    @Test fun contentRangeTotalReadsTheFullSize() {
        assertEquals(1234L, OfflineDownloader.contentRangeTotal("bytes 0-99/1234"))
        assertNull(OfflineDownloader.contentRangeTotal("bytes 0-99/*"))
        assertNull(OfflineDownloader.contentRangeTotal(null))
    }

    @Test fun contentRangeSpanReadsThePartSent() {
        assertEquals(100L..199L, OfflineDownloader.contentRangeSpan("bytes 100-199/1234"))
        assertEquals(0L..99L, OfflineDownloader.contentRangeSpan("Bytes 0-99/*"))
        assertNull(OfflineDownloader.contentRangeSpan("bytes 199-100/1234"))
        assertNull(OfflineDownloader.contentRangeSpan("bytes */1234"))
        assertNull(OfflineDownloader.contentRangeSpan(null))
    }

    @Test fun normalizeIsrcAcceptsDashesAndCase() {
        assertEquals("USUM71703861", OfflineDownloader.normalizeIsrc("us-um7-17-03861"))
        assertEquals("FRZ039800212", OfflineDownloader.normalizeIsrc("FR Z03 98 00212"))
    }

    @Test fun normalizeIsrcRejectsOtherIds() {
        assertNull(OfflineDownloader.normalizeIsrc(""))
        assertNull(OfflineDownloader.normalizeIsrc("0602557362806")) // a UPC
        assertNull(OfflineDownloader.normalizeIsrc("USUM7170386"))   // one digit short
    }

    @Test fun lengthMismatchRefusesPreviewsAndOtherVersions() {
        assertNull(OfflineDownloader.lengthMismatch(163, 163_400))
        assertNull(OfflineDownloader.lengthMismatch(160, 163_400))   // a few seconds trimmed
        assertNull(OfflineDownloader.lengthMismatch(175, 163_400))   // trailing silence
        assertNull(OfflineDownloader.lengthMismatch(30, 0))          // the song's length unknown
        assertTrue(OfflineDownloader.lengthMismatch(30, 163_400)!!.contains("preview"))
        assertTrue(OfflineDownloader.lengthMismatch(312, 163_400)!!.contains("another version"))
    }

    @Test fun localIdsNeverCollideWithMediaStoreIds() {
        for (id in listOf("4uLU6hMCjMI75M1A2tKUQC", "0", "", "zzzzzzzzzzzzzzzzzzzzzz")) {
            assertTrue(OfflineStore.localIdOf(id) < 0)
        }
    }
}
