package app.myspoti.host.android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopSpoofTest {

    private val fingerprint = Fingerprint(141, 7390, 122, "6.8.0")

    @Test
    fun `the script carries the install's own browser, not a constant`() {
        val js = DesktopSpoof.script(fingerprint)
        assertTrue(js.contains(fingerprint.userAgent))
        assertTrue(js.contains("'141.0.7390.122'"))
        assertTrue(js.contains("'6.8.0'"))
        assertTrue(js.contains("\"version\":\"141\""))
        assertTrue(js.contains("\"version\":\"141.0.7390.122\""))
        // Nothing left of the template.
        assertFalse(js.contains("__UA__"))
        assertFalse(js.contains("__BRANDS__"))
        assertFalse(js.contains("__FULL_BRANDS__"))
        assertFalse(js.contains("__PLATFORM_VERSION__"))
    }

    @Test
    fun `the page is told a desktop browser, the parts a WebView gets wrong`() {
        val js = DesktopSpoof.script(fingerprint)
        // navigator.platform of an Android WebView; the user agent says x86_64 desktop.
        assertTrue(js.contains("'Linux x86_64'"))
        assertTrue(js.contains("'Google Inc.'"))
        assertTrue(js.contains("getHighEntropyValues"))
        assertTrue(js.contains("userAgentData"))
        assertTrue(js.contains("'maxTouchPoints'"))
        // Installed once per document, and never able to break the page it runs in.
        assertTrue(js.contains("window.__myspotiDesktop"))
        assertTrue(js.startsWith("(function(){try{"))
        assertTrue(js.endsWith("catch(e){}})()"))
    }

    @Test
    fun `a different install describes itself, not this one`() {
        val other = Fingerprint(155, 8100, 44, "6.6.9")
        val js = DesktopSpoof.script(other)
        assertTrue(js.contains("Chrome/155.0.0.0"))
        assertTrue(js.contains("'155.0.8100.44'"))
        assertFalse(js.contains("Chrome/141"))
    }
}
