/* SPDX-License-Identifier: GPL-3.0-only */
package app.myspoti.spotify.gql

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** The build is looked at every 6 h at most, and a new build's bundles wait for a network that is not metered. */
class GqlOpsUpdaterTest {
    @TempDir lateinit var dir: File

    private val hash = "a".repeat(64)
    private var build = "b1"
    private val asked = mutableListOf<String>()
    private val http = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
        val r = chain.request(); asked += r.url.toString()
        val body = if (r.url.host == "open.spotify.com")
            """<script id="appServerConfig" type="text/plain">{"buildVersion":"$build"}</script>
               <script src="https://open.spotifycdn.com/cdn/build/web-player/web-player.x1.js"></script>"""
        else """x=new A("fetchLibraryTracks","query","$hash")"""
        Response.Builder().request(r).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body.toResponseBody()).build()
    }).build()

    @Test fun lookedAtEverySixHoursAndBundlesOffMeteredNetworks() = runBlocking {
        val u = GqlOpsUpdater(http, File(dir, "gql-ops.json"))
        val ops = GqlOps(mapOf("fetchLibraryTracks" to "0".repeat(64)))
        assertTrue(u.due(now = 0))
        // Metered: the new build is seen, its bundles are not read and the look stays due.
        assertNull(u.refresh(ops, bundlesAllowed = false, now = 1_000))
        assertEquals(1, asked.count { "spotifycdn" !in it }); assertEquals(0, asked.count { "spotifycdn" in it })
        assertTrue(u.due(now = 2_000))
        // On Wi-Fi: read, then not due for 6 h.
        val fresh = u.refresh(ops, bundlesAllowed = true, now = 3_000)
        assertNotNull(fresh); assertEquals(hash, fresh!!.hash("fetchLibraryTracks"))
        assertFalse(u.due(now = 3_000 + GqlOpsUpdater.CHECK_GAP_MS - 1))
        assertTrue(u.due(now = 3_000 + GqlOpsUpdater.CHECK_GAP_MS))
        // Same build later: nothing downloaded beyond the page, and marked checked.
        asked.clear()
        assertNull(u.refresh(fresh, bundlesAllowed = false, now = 30_000_000))
        assertEquals(0, asked.count { "spotifycdn" in it })
        assertFalse(u.due(now = 30_000_001))
    }
}
