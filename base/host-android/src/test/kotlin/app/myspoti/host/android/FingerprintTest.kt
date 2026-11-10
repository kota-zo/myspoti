package app.myspoti.host.android

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class FingerprintTest {

    // Real desktop Chrome headers: the grease brand, its version and the order follow the major.
    @Test fun `brands match what Chrome itself sends`() {
        val real = mapOf(
            116 to "\"Chromium\";v=\"116\", \"Not)A;Brand\";v=\"24\", \"Google Chrome\";v=\"116\"",
            120 to "\"Not_A Brand\";v=\"8\", \"Chromium\";v=\"120\", \"Google Chrome\";v=\"120\"",
            124 to "\"Chromium\";v=\"124\", \"Google Chrome\";v=\"124\", \"Not-A.Brand\";v=\"99\"",
            131 to "\"Google Chrome\";v=\"131\", \"Chromium\";v=\"131\", \"Not_A Brand\";v=\"24\"",
        )
        real.forEach { (major, header) ->
            assertEquals(header, Fingerprint(major, 1, 1, "6.8.0").secChUa)
        }
    }

    @Test fun `the user agent is reduced, the full version stays in the hints`() {
        val f = Fingerprint(141, 7390, 122, "6.8.0")
        assertEquals("Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36", f.userAgent)
        assertEquals("141.0.7390.122", f.fullVersion)
        assertTrue(f.brands.filter { it.name != "Chromium" && it.name != "Google Chrome" }.all { it.fullVersion.endsWith(".0.0.0") })
    }

    @Test fun `no lag is the WebView's own version`() {
        val f = Fingerprint.resolve("141.0.7390.122", Fingerprint.Draw(lag = 0, patch = 57, kernel = "6.6.9"))
        assertEquals(Fingerprint(141, 7390, 122, "6.6.9"), f)
    }

    @Test fun `a lag follows the WebView when it updates`() {
        val draw = Fingerprint.Draw(lag = 1, patch = 57, kernel = "6.6.9")
        assertEquals(140, Fingerprint.resolve("141.0.7390.122", draw).major)
        assertEquals(142, Fingerprint.resolve("143.0.7499.40", draw).major)
        assertEquals(7499 - Fingerprint.BUILDS_PER_MAJOR, Fingerprint.resolve("143.0.7499.40", draw).build)
        assertEquals(57, Fingerprint.resolve("143.0.7499.40", draw).patch)
    }

    @Test fun `an unreadable WebView version falls back to a plausible Chrome`() {
        val f = Fingerprint.resolve("not a version", Fingerprint.Draw(0, 1, "6.8.0"))
        assertTrue(f.major in 100..300 && f.build > 0)
        assertNull(Fingerprint.parseEngine("141.0"))
        assertNull(Fingerprint.parseEngine(null))
        // Android 6 System WebView (Chrome 95-106) keeps its own version instead of falling back.
        assertEquals(95, Fingerprint.resolve("95.0.4638.74", Fingerprint.Draw(0, 1, "6.8.0")).major)
    }

    @Test fun `draws stay within the bounds`() {
        val random = Random(7)
        repeat(500) {
            val d = Fingerprint.draw(random)
            assertTrue(d.lag in 0..Fingerprint.BACK_RELEASES)
            assertTrue(d.patch in 1..199)
            assertTrue(d.kernel in Fingerprint.KERNELS)
        }
    }

    @Test fun `a file of the old shape keeps its place behind the WebView`() {
        val old = JSONObject().put("major", 139).put("build", 8123).put("patch", 45).put("kernel", "6.7.2")
        assertEquals(Fingerprint.Draw(lag = 2, patch = 45, kernel = "6.7.2"), Fingerprint.drawFrom(old, "141.0.7390.122"))
        val newer = JSONObject().put("lag", 1).put("patch", 12).put("kernel", "6.8.0")
        assertEquals(Fingerprint.Draw(1, 12, "6.8.0"), Fingerprint.drawFrom(newer, "141.0.7390.122"))
        assertNull(Fingerprint.drawFrom(JSONObject().put("lag", 1), "141.0.7390.122"))
    }

    @Test fun `accept-language is written the way Chromium writes it`() {
        assertEquals("fr-CH,fr;q=0.9,en;q=0.8", Fingerprint.acceptLanguage(listOf("fr-CH", "fr", "en")))
        assertEquals("en", Fingerprint.acceptLanguage(listOf("en")))
        assertFalse(Fingerprint.acceptLanguage(List(12) { "l$it" }).contains(";q=0.0"))
    }
}
