package app.myspoti.spotify

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LicenceRefusalsTest {
    @Test fun `three refusals within ten seconds`() {
        val r = LicenceRefusals()
        assertFalse(r.record(0)); assertFalse(r.record(4_000))
        assertTrue(r.record(9_000))
    }

    @Test fun `refusals too far apart are songs, not the account`() {
        val r = LicenceRefusals()
        assertFalse(r.record(0)); assertFalse(r.record(6_000))
        assertFalse(r.record(12_000))
    }

    @Test fun `counted again from zero once it fired`() {
        val r = LicenceRefusals()
        repeat(3) { r.record(it * 1_000L) }
        assertFalse(r.record(3_500)); assertFalse(r.record(4_000))
        assertTrue(r.record(4_500))
    }

    @Test fun `the song's licence, not the certificate`() {
        assertTrue(LicenceRefusals.isAudio("https://gew4-spclient.spotify.com/widevine-license/v1/audio/license"))
        assertFalse(LicenceRefusals.isAudio("https://gew4-spclient.spotify.com/widevine-license/v1/application/certificate"))
    }
}
