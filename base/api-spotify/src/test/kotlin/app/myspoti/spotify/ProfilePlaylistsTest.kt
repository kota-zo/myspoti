package app.myspoti.spotify

import app.myspoti.spotify.bridge.Bridge
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Reported (#258): a profile showed only the first 50 of its public playlists. */
class ProfilePlaylistsTest {
    private fun json(s: String) = Bridge.json.parseToJsonElement(s)
    private fun playlists(from: Int, n: Int) = (from until from + n).joinToString(",") { """{"uri":"spotify:playlist:p$it","name":"P$it"}""" }
    private fun profile(n: Int, total: Int) = json("""{"public_playlists":[${playlists(0, n)}],"total_public_playlists_count":$total}""")

    @Test fun `the playlists after the first 50 are read page by page`() = runBlocking {
        val asked = mutableListOf<Int>()
        val all = allPublicPlaylists(profile(50, 120)) { offset ->
            asked += offset
            json("""{"public_playlists":[${playlists(offset, minOf(50, 120 - offset))}]}""")
        }
        assertEquals(listOf(50, 100), asked)
        assertEquals((0 until 120).map { "spotify:playlist:p$it" }, all.map { it.str("uri") })
    }

    @Test fun `a profile with 50 or fewer is not asked again`() = runBlocking {
        val all = allPublicPlaylists(profile(12, 12)) { error("not asked") }
        assertEquals(12, all.size)
    }

    @Test fun `a short page is not the last, an empty one is`() = runBlocking {
        val asked = mutableListOf<Int>()
        val all = allPublicPlaylists(profile(50, 200)) { offset ->
            asked += offset
            if (offset == 50) json("""{"public_playlists":[${playlists(50, 30)}]}""") else json("""{"public_playlists":[]}""")
        }
        assertEquals(listOf(50, 100), asked)
        assertEquals(80, all.size)
    }

    @Test fun `a failed page keeps what was read`() = runBlocking {
        val all = allPublicPlaylists(profile(50, 120)) { throw java.io.IOException("503") }
        assertEquals(50, all.size)
    }

    @Test fun `a huge profile stops at the cap`() = runBlocking {
        var pages = 0
        val all = allPublicPlaylists(profile(50, 5000)) { offset -> pages++; json("""{"public_playlists":[${playlists(offset, 50)}]}""") }
        assertEquals(PROFILE_PLAYLISTS_MAX, all.size)
        assertEquals(PROFILE_PLAYLISTS_MAX / PROFILE_PAGE - 1, pages)
    }
}
