package app.myspoti.host.android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StabilityTest {

    private val script = Stability.SCRIPT

    @Test
    fun `the patches the working TV wrapper ships are all here`() {
        // Service worker: refused, and the one already installed dropped.
        assertTrue(script.contains("serviceWorker"))
        assertTrue(script.contains("getRegistrations"))
        // The page's quarter-second polling, at half the rate.
        assertTrue(script.contains("d===250"))
        assertTrue(script.contains("d=500"))
        // Frames nobody sees, and the way back to full rate.
        assertTrue(script.contains("requestAnimationFrame"))
        assertTrue(script.contains("__myspotiQuiet"))
        // Analytics answered without loading anything.
        assertTrue(script.contains("GoogleAnalyticsObject"))
    }

    @Test
    fun `it runs once per document and can never break the page`() {
        assertTrue(script.contains("window.__myspotiStable"))
        assertTrue(script.startsWith("(function(){try{"))
        assertTrue(script.endsWith("catch(e){}})()"))
        // Every patch is guarded on its own: one that fails leaves the others working.
        assertTrue(script.split("catch(e){}").size > 5)
    }

    @Test
    fun `the frame rate it asks for is a number the script carries`() {
        assertFalse(script.contains("__QUIET_FRAME_MS__"))
        // Ten a second: a timeout of 100 ms in the throttled path.
        assertTrue(script.contains("setTimeout(flush,100)"))
    }

    @Test
    fun `the page is told when its frames matter`() {
        assertTrue(Stability.quietJs(true).contains("__myspotiQuiet(true)"))
        assertTrue(Stability.quietJs(false).contains("__myspotiQuiet(false)"))
        // A page that has not run the script yet ignores it instead of throwing.
        assertTrue(Stability.quietJs(true).contains("if(window.__myspotiQuiet)"))
    }
}
