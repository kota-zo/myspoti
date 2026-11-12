/* SPDX-License-Identifier: GPL-3.0-only */
package app.myspoti.catalog

import app.myspoti.api.Page
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScriptProviderTest {
    private class Web(val answers: Map<String, Response>) : ScriptHttp {
        val asked = ArrayList<String>()
        override fun request(method: String, url: String, headers: Map<String, String>, body: String?): Response {
            asked += "$method $url"
            return answers.entries.firstOrNull { url.contains(it.key) }?.value ?: Response(404, "{}")
        }
    }

    private fun deezerTrack(id: Int, title: String) = """{"id":$id,"title":"$title","duration":200,"isrc":"AAAAA000000$id","artist":{"id":7,"name":"Band"},"album":{"id":9,"title":"Album","cover_medium":"m"}}"""

    private val deezerWeb = mapOf(
        "api.deezer.com/search?" to Response(200, """{"data":[${deezerTrack(1, "Song")}],"total":1}"""),
        "api.deezer.com/track/1" to Response(200, deezerTrack(1, "Song")),
        "api.deezer.com/album/9/tracks" to Response(200, """{"data":[${deezerTrack(1, "Song")},${deezerTrack(2, "Other")}],"total":2}"""),
        "api.deezer.com/album/9" to Response(200, """{"id":9,"title":"Album","nb_tracks":2,"record_type":"album","artist":{"id":7,"name":"Band"},"tracks":{"data":[${deezerTrack(1, "Song")},${deezerTrack(2, "Other")}]}}"""),
    )

    private fun script(body: String) = "$body\nregisterExtension({initialize:function(){return true;},getTrack:getTrack,customSearch:customSearch});"

    private fun provider(web: ScriptHttp, source: String) = ScriptProvider(ScriptSpec("test", setOf("allowed.example"), "track", "album", "artist"), web, "Test/1", source)

    @Test fun deezerRunsInItsSandbox() = runBlocking {
        val d = ScriptProvider(ScriptSpec.DEEZER, Web(deezerWeb), "Test/1")
        val hit = d.searchTracks("song").items.single()
        assertEquals("deezer:track:1", hit.track.uri)
        assertEquals("Song", hit.track.name)
        assertEquals(200_000L, hit.track.durationMs)
        assertEquals("Band", hit.track.artists.single().name)
        assertEquals("AAAAA0000001", hit.isrc)
        assertEquals("deezer:album:9", hit.track.album?.uri)
    }

    @Test fun deezerAlbumsComeWithTheirTracks() = runBlocking {
        val d = ScriptProvider(ScriptSpec.DEEZER, Web(deezerWeb), "Test/1")
        assertEquals(2, d.album("9").trackCount)
        assertEquals(listOf("Song", "Other"), d.albumTracks("9").items.map { it.track.name })
    }

    @Test fun nothingOfDownloadingIsLeftInTheScripts() {
        val forbidden = Regex("zarz|cobalt|yt1d|poToken|googlevideo|lowfish|signedFetch|gw-light|youtubei/v1/player|file\\.download|\\bdownload\\(", RegexOption.IGNORE_CASE)
        for (s in listOf("deezer", "tidal", "ytmusic")) {
            val text = ScriptProvider::class.java.getResource("metadata/$s.js")!!.readText().lines().drop(4).joinToString("\n")
            assertNull("$s: ${forbidden.find(text)?.value}", forbidden.find(text))
        }
    }

    @Test fun everyScriptStarts() = runBlocking {
        for (spec in listOf(ScriptSpec.DEEZER, ScriptSpec.TIDAL, ScriptSpec.YOUTUBE_MUSIC)) {
            val p = ScriptProvider(spec, Web(emptyMap()), "Test/1")
            // The service answers nothing useful here: the script may say so, but it has to load and run.
            val failure = runCatching { p.searchTracks("x") }.exceptionOrNull()
            println("${spec.id}: ${failure?.message}")
            assertFalse("${spec.id}: ${failure?.message}", failure?.message.orEmpty().contains(Regex("syntax|missing|not defined|invalid|is not a function|Cannot", RegexOption.IGNORE_CASE)))
        }
    }

    @Test fun aScriptReachesOnlyItsHostsOverHttps() = runBlocking {
        val web = Web(mapOf("allowed.example" to Response(200, "ok")))
        val p = provider(web, script("""
            function getTrack(id) { return {id: id, name: http.get(id).error || http.get(id).body}; }
            function customSearch() { return []; }"""))
        assertEquals("ok", p.track("https://allowed.example/a").track.name)
        assertEquals("ok", p.track("https://sub.allowed.example/a").track.name)
        assertEquals("host not allowed", p.track("https://elsewhere.example/a").track.name)
        assertEquals("host not allowed", p.track("http://allowed.example/a").track.name)
        assertEquals("host not allowed", p.track("https://allowed.example.evil.test/a").track.name)
        assertEquals(2, web.asked.size / 2)
    }

    @Test fun aScriptHasNoJava() = runBlocking {
        val p = provider(Web(emptyMap()), script("""
            function getTrack(id) { return {id: "1", name: typeof java + " " + typeof Packages + " " + typeof JavaImporter}; }
            function customSearch() { return []; }"""))
        assertEquals("undefined undefined undefined", p.track("1").track.name)
    }

    @Test fun anEmptyAnswerIsNotFoundUnlessTheServiceFailed() = runBlocking {
        val source = script("""
            function getTrack(id) { http.get("https://allowed.example/x"); return null; }
            function customSearch() { http.get("https://allowed.example/x"); return []; }""")
        assertTrue(runCatching { provider(Web(emptyMap()), source).track("1") }.exceptionOrNull() is NotFound)
        val down = provider(Web(mapOf("allowed.example" to Response(503, ""))), source)
        assertTrue(runCatching { down.track("1") }.exceptionOrNull() is ProviderDown)
        assertTrue(runCatching { down.searchTracks("x") }.exceptionOrNull() is ProviderDown)
        val cut = provider({ _, _, _, _ -> throw IOException("no route") }, source)
        assertTrue(runCatching { cut.track("1") }.exceptionOrNull() is ProviderDown)
    }

    @Test fun aScriptThatFailsIsDown() = runBlocking {
        val broken = provider(Web(emptyMap()), script("""
            function getTrack(id) { throw new Error("broken"); }
            function customSearch() { return []; }"""))
        assertTrue(runCatching { broken.track("1") }.exceptionOrNull() is ProviderDown)
        val syntax = provider(Web(emptyMap()), "function (")
        assertTrue(runCatching { syntax.track("1") }.exceptionOrNull() is ProviderDown)
    }

    @Test(timeout = 20_000) fun aScriptThatNeverEndsIsStopped() {
        val e = ScriptEngine("loop", "registerExtension({spin:function(){ while (true) {} }});", emptySet(), { _, _, _, _ -> Response(404, "") }, "Test/1", runMs = 300)
        assertTrue(runCatching { e.call("spin") }.exceptionOrNull() is ProviderDown)
    }

    @Test fun aTitleOfDigitsIsNotAnotherScript() {
        val hit = app.myspoti.api.Track("x:track:1", "10 / 10", 241_000, listOf(app.myspoti.api.ArtistRef("x:artist:1", "Band")), null)
        assertEquals(0.0, Match.score("Song", "Band", 241_000, hit), 0.0)
    }

    @Test fun anArtistKnownUnderANewNameIsStillFound() {
        val hit = app.myspoti.api.Track("x:track:1", "Song", 226_000, listOf(app.myspoti.api.ArtistRef("x:artist:1", "Band")), null)
        assertTrue(Match.score("Song", "Master Band", 226_000, hit) >= Match.SURE)
    }

    @Test fun aSignedInPlatformIsAProviderLikeTheOthers() = runBlocking {
        val inner = object : CatalogTestProvider("spotify") {}
        val chain = ProviderChain(listOf(inner))
        val p = ApiProvider("spotify", chain, chain)
        assertEquals("spotify:track:1", p.track("1").track.uri)
        assertEquals("AAAAA0000001", p.track("1").isrc)
        assertEquals("spotify:track:1", p.searchTracks("q").items.single().track.uri)
        // Its search by ISRC, checked against the track's own ISRC.
        assertEquals("spotify:track:1", p.byIsrc("AAAAA0000001").single().track.uri)
        assertTrue(p.byIsrc("AAAAA0000002").isEmpty())
    }

    private open class CatalogTestProvider(override val id: String) : MetadataProvider {
        private val t = app.myspoti.api.Track("$id:track:1", "Song", 1000, emptyList(), null)
        override suspend fun searchTracks(query: String, offset: Int, limit: Int) = Page(listOf(Hit(t)), offset, 1, null)
        override suspend fun searchAlbums(query: String, offset: Int, limit: Int) = Page<app.myspoti.api.Album>(emptyList(), offset, 0, null)
        override suspend fun searchArtists(query: String, offset: Int, limit: Int) = Page<app.myspoti.api.Artist>(emptyList(), offset, 0, null)
        override suspend fun find(title: String, artist: String, limit: Int) = emptyList<Hit>()
        override suspend fun byIsrc(isrc: String) = emptyList<Hit>()
        override suspend fun track(id: String) = Hit(t, "AAAAA0000001")
        override suspend fun album(id: String) = throw NotFound(this.id, id)
        override suspend fun albumTracks(id: String, offset: Int, limit: Int) = Page<Hit>(emptyList(), offset, 0, null)
        override suspend fun artist(id: String) = throw NotFound(this.id, id)
        override suspend fun topTracks(id: String) = emptyList<Hit>()
        override suspend fun discography(id: String, offset: Int, limit: Int) = Page<app.myspoti.api.Album>(emptyList(), offset, 0, null)
        override suspend fun related(id: String) = emptyList<app.myspoti.api.Artist>()
    }
}
