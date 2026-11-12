package app.myspoti.host.cdp

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

/**
 * A browser with no window on an empty profile of its own (no account in it): the cookies the
 * Web Player leaves for a visitor are seen by name, and none is the signed-in one.
 * Run with ORPHEON_BROWSER_TEST=<path of Chrome or Chromium>.
 */
class CdpBrowserTest {
    @Test fun `a visitor's cookies are seen by name and none of them is the session`() = runBlocking {
        val chrome = System.getenv("ORPHEON_BROWSER_TEST")
        assumeTrue(!chrome.isNullOrBlank())
        val profile = Files.createTempDirectory("orpheon-cdp").toFile()
        val p = ProcessBuilder(chrome, "--headless=new", "--user-data-dir=${profile.path}", "--remote-debugging-port=0", "--no-first-run", "https://open.spotify.com/")
            .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        try {
            val portFile = File(profile, "DevToolsActivePort")
            var port: Int? = null
            repeat(100) { if (port == null) { port = runCatching { portFile.readLines().first().trim().toInt() }.getOrNull(); if (port == null) Thread.sleep(150) } }
            val endpoint = "http://127.0.0.1:${port!!}"
            var names = emptySet<String>()
            repeat(40) { if (names.isEmpty()) { Thread.sleep(500); names = runCatching { CdpBrowser.cookieNames(endpoint, "spotify.com") }.getOrDefault(emptySet()) } }
            println("visitor cookies on spotify.com: ${names.size}")
            assertTrue(names.isNotEmpty())
            assertFalse("sp_dc" in names)
        } finally {
            p.descendants().forEach { it.destroy() }; p.destroy()
            profile.deleteRecursively()
        }
    }
}
