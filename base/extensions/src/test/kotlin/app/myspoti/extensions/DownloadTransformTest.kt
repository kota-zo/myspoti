package app.myspoti.extensions

import org.junit.Assert.*
import org.junit.Test

class DownloadTransformTest {
    private val transform = """{"algorithm":"blowfish-cbc-pattern","keyHex":"0000000000000000","ivHex":"0000000000000000","segmentBytes":2048,"every":3,"offset":0}"""
    @Test fun parsesDownloadTransform() {
        val f = DownloadFile.parseList("""{"url":"https://example.org/a.flac","format":"flac","transform":$transform}""").single()
        assertNotNull(f.transform)
        assertEquals(2048, f.transform!!.segmentBytes)
    }
    @Test fun parsesStreamingTransform() {
        val f = AudioStream.parse("""{"url":"https://example.org/a.flac","transform":$transform}""")!!
        assertNotNull(f.transform)
    }
    @Test fun unknownTransformIsNotSilentlyIgnored() {
        try {
            DownloadFile.parseList("""{"url":"https://example.org/a.flac","format":"flac","transform":{"algorithm":"unknown"}}""")
            fail("unknown transform accepted")
        } catch (_: IllegalArgumentException) {} catch (_: ExtensionException) {}
    }
}
