package app.myspoti.ytmusic

import app.myspoti.api.account.SignedOut
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The web app's API on a recorded session: requests checked, answers from fixtures, nothing goes out. */
class InnerTubeTest {
    private val cookie = "PREF=x; SAPISID=sapisid-test; SID=sid-test"

    /** Answers each request from [answer] (by endpoint and body) and keeps it. */
    private class Recorded(private val answer: (Request, String) -> String) : Interceptor {
        val requests = ArrayList<Pair<Request, String>>()
        override fun intercept(chain: Interceptor.Chain): Response {
            val r = chain.request()
            val body = Buffer().also { r.body!!.writeTo(it) }.readUtf8()
            requests += r to body
            return Response.Builder().request(r).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(answer(r, body).toResponseBody("application/json".toMediaType())).build()
        }
    }

    private fun fixtureText(name: String) = InnerTubeTest::class.java.getResourceAsStream("/fixtures/$name.json")!!.bufferedReader().readText()

    private fun api(rec: Recorded, cookies: String? = cookie) =
        InnerTube(OkHttpClient.Builder().addInterceptor(rec).build(), cookies = { cookies }, clientVersion = { "1.2.3" }, nowSeconds = { 1_700_000_000 })

    @Test fun `the auth header is the web app's SAPISIDHASH`() {
        assertEquals("SAPISIDHASH 1700000000_c0178091cdd52a25dadb4af9a2cf9979070658fc", Ytm.authorization("sapisid-test", 1_700_000_000))
        assertEquals("sapisid-test", Ytm.sapisid(cookie))
        assertEquals("b", Ytm.sapisid("__Secure-3PAPISID=b"))
        assertFalse(Ytm.signedIn("PREF=x; VISITOR_INFO1_LIVE=y"))
        assertTrue(Ytm.isVideoId("vidAAAAAAA1"))
        assertFalse(Ytm.isVideoId("spotify:track:4uLU6hMCjMI75M1A2tKUQC"))
        assertTrue(Ytm.signInDone("https://music.youtube.com/"))
        assertFalse(Ytm.signInDone("https://accounts.google.com/ServiceLogin?continue=https://music.youtube.com/"))
    }

    @Test fun `a browse carries the session, the auth header and the client, no API key`() = runTest {
        val rec = Recorded { _, _ -> fixtureText("liked") }
        api(rec).browse("VLLM")
        val (r, body) = rec.requests.single()
        assertEquals("https://music.youtube.com/youtubei/v1/browse?prettyPrint=false", r.url.toString())
        assertNull(r.url.queryParameter("key"))
        assertEquals(cookie, r.header("Cookie"))
        assertEquals("SAPISIDHASH 1700000000_c0178091cdd52a25dadb4af9a2cf9979070658fc", r.header("Authorization"))
        assertEquals("https://music.youtube.com", r.header("X-Origin"))
        val json = Json.parseToJsonElement(body).jsonObject
        assertEquals("VLLM", json["browseId"]!!.jsonPrimitive.content)
        val client = json["context"]!!.jsonObject["client"]!!.jsonObject
        assertEquals("WEB_REMIX", client["clientName"]!!.jsonPrimitive.content)
        assertEquals("1.2.3", client["clientVersion"]!!.jsonPrimitive.content)
    }

    @Test fun `signed out, nothing is asked`() = runTest {
        val rec = Recorded { _, _ -> "{}" }
        assertTrue(runCatching { api(rec, cookies = "PREF=x").browse("VLLM") }.exceptionOrNull() is SignedOut)
        assertTrue(runCatching { api(rec, cookies = null).search("q") }.exceptionOrNull() is SignedOut)
        assertTrue(rec.requests.isEmpty())
    }

    @Test fun `liked songs follow the next pages until enough are there`() = runTest {
        val rec = Recorded { r, body -> if (r.url.queryParameter("ctoken") == "TOKEN-PAGE-2" && "TOKEN-PAGE-2" in body) fixtureText("liked-more") else fixtureText("liked") }
        val lib = YtmLibrary(api(rec))
        val first = lib.likedTracks(0, 2)
        assertEquals(2, first.items.size)
        assertEquals(2, first.nextOffset)
        assertEquals(1, rec.requests.size)
        val all = lib.likedTracks(0, 10)
        assertEquals(listOf("Song One", "Song Two", "Song Three"), all.items.map { it.name })
        assertNull(all.nextOffset)
        val second = lib.likedTracks(2, 2)
        assertEquals(listOf("Song Three"), second.items.map { it.name })
    }

    @Test fun `playlists, a playlist's songs, the history`() = runTest {
        val rec = Recorded { _, body ->
            when {
                "FEmusic_liked_playlists" in body -> fixtureText("playlists")
                "VLPLroadmix0001" in body || "FEmusic_history" in body -> fixtureText("liked-more")
                else -> "{}"
            }
        }
        val lib = YtmLibrary(api(rec))
        assertEquals(2, lib.playlists().size)
        assertEquals(1, lib.playlistTracks("ytmusic:playlist:PLroadmix0001").items.size)
        assertTrue("VLPLroadmix0001" in rec.requests.last().second)
        assertEquals(1, lib.history().size)
        assertTrue(runCatching { lib.playlistTracks("spotify:playlist:x") }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test fun `metadata on the session, search, song, album, artist`() = runTest {
        val rec = Recorded { r, body ->
            when {
                r.url.encodedPath.endsWith("/search") -> fixtureText("search")
                r.url.encodedPath.endsWith("/next") -> fixtureText("next")
                "MPREb_albumX" in body -> fixtureText("album")
                else -> fixtureText("artist")
            }
        }
        val m = YtmMetadata(api(rec))
        val all = m.all("artist a song one")
        assertEquals(2, all.tracks.items.size)
        assertEquals(1, all.albums.items.size)
        assertTrue(m.tracks("q", offset = 30).items.isEmpty())
        assertEquals("Song One", m.track("ytmusic:track:vidAAAAAAA1").name)
        assertEquals(2, m.albumTracks("ytmusic:album:MPREb_albumX").items.size)
        assertEquals("Artist A", m.artistPage("ytmusic:artist:UCartistA000000000000000").artist.name)
        assertNull(m.isrc("ytmusic:track:vidAAAAAAA1"))
    }
}
