/* SPDX-License-Identifier: GPL-3.0-only */
package app.myspoti.catalog

import app.myspoti.api.Album
import app.myspoti.api.Artist
import app.myspoti.api.ArtistRef
import app.myspoti.api.MyspotiApi
import app.myspoti.api.Page
import app.myspoti.api.Playlist
import app.myspoti.api.Search
import app.myspoti.api.Track
import app.myspoti.api.sync.SyncTrack
import app.myspoti.api.sync.TrackIdResolver
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** How the catalog is plugged into the app: the contract the UI sees, the sync's ids, the fallback of a downloaded script. */
class CatalogWiringTest {
    private fun track(uri: String, name: String, artist: String, ms: Long = 200_000) = Track(uri, name, ms, listOf(ArtistRef("x:artist:1", artist)), null)

    private open class Fake(
        override val id: String,
        val hits: List<Hit> = emptyList(),
        val albums: List<Album> = emptyList(),
        val down: Boolean = false,
    ) : MetadataProvider {
        var calls = 0
        private fun check() { calls++; if (down) throw ProviderDown(id, "down") }
        override suspend fun searchTracks(query: String, offset: Int, limit: Int): Page<Hit> { check(); return Page(hits, offset, hits.size, null) }
        override suspend fun searchAlbums(query: String, offset: Int, limit: Int): Page<Album> { check(); return Page(albums, offset, albums.size, null) }
        override suspend fun searchArtists(query: String, offset: Int, limit: Int) = Page<Artist>(emptyList(), offset, 0, null)
        override suspend fun find(title: String, artist: String, limit: Int): List<Hit> { check(); return hits }
        override suspend fun byIsrc(isrc: String): List<Hit> { check(); return hits.filter { it.isrc == isrc } }
        override suspend fun track(id: String) = hits.first { it.track.uri.endsWith(":$id") }
        override suspend fun album(id: String) = albums.firstOrNull { it.uri.endsWith(":$id") } ?: throw NotFound(this.id, id)
        override suspend fun albumTracks(id: String, offset: Int, limit: Int) = Page(hits, offset, hits.size, null)
        override suspend fun artist(id: String) = Artist("${this.id}:artist:$id", "Band", emptyList())
        override suspend fun topTracks(id: String) = hits
        override suspend fun discography(id: String, offset: Int, limit: Int) = Page(albums, offset, albums.size, null)
        override suspend fun related(id: String) = emptyList<Artist>()
    }

    /** A platform's own implementation: its search and catalog, the rest unused here. */
    private fun platform(search: Search, catalog: app.myspoti.api.Catalog): MyspotiApi =
        java.lang.reflect.Proxy.newProxyInstance(MyspotiApi::class.java.classLoader, arrayOf(MyspotiApi::class.java)) { _, m, _ ->
            when (m.name) {
                "getSearch" -> search
                "getCatalog" -> catalog
                else -> throw UnsupportedOperationException(m.name)
            }
        } as MyspotiApi

    // ── A downloaded script that fails ──

    private class Web : ScriptHttp {
        override fun request(method: String, url: String, headers: Map<String, String>, body: String?) = Response(200, "{}")
    }

    private val spec = ScriptSpec("test", setOf("allowed.example"), "track", "album", "artist")
    private fun script(getTrack: String) =
        "registerExtension({initialize:function(){return true;},getTrack:$getTrack,customSearch:function(){return [];}});"
    private val good = script("""function(id){ return {id:id, name:"Bundled"}; }""")

    @Test fun aDownloadedScriptThatThrowsGivesWayToTheBundledOne() = runBlocking {
        val p = ScriptProvider(spec, Web(), "Test/1", script("""function(id){ throw new Error("broken"); }"""), fallback = good)
        assertTrue(p.downloaded)
        assertEquals("Bundled", p.track("1").track.name)
        assertFalse(p.downloaded)
        assertNotNull(p.setAside)
        // Set aside until the app restarts: the bundled script answers at once from then on.
        assertEquals("Bundled", p.track("2").track.name)
    }

    @Test fun aDownloadedScriptThatAnswersNonsenseGivesWayToo() = runBlocking {
        val p = ScriptProvider(spec, Web(), "Test/1", "function (", fallback = good)
        assertEquals("Bundled", p.track("1").track.name)
        assertFalse(p.downloaded)
    }

    @Test(timeout = 30_000) fun aDownloadedScriptTooSlowGivesOnlyThatCallAway() = runBlocking {
        val spinning = script("""function(id){ if (id == "1") { while (true) {} } return {id:id, name:"Downloaded"}; }""")
        val p = ScriptProvider(spec, Web(), "Test/1", spinning, fallback = good, runMs = 300)
        // Maybe only a slow network: the bundled script answers this call, the downloaded one stays.
        assertEquals("Bundled", p.track("1").track.name)
        assertTrue(p.downloaded)
        assertEquals("Downloaded", p.track("2").track.name)
    }

    @Test fun whenTheBundledScriptFailsTooTheChainMovesOn() = runBlocking {
        val broken = script("""function(id){ throw new Error("broken"); }""")
        val p = ScriptProvider(spec, Web(), "Test/1", broken, fallback = broken)
        assertTrue(runCatching { p.track("1") }.exceptionOrNull() is ProviderDown)
        val other = Fake("other", listOf(Hit(track("other:track:1", "Song", "Band"))))
        val chain = ProviderChain(listOf(p, other))
        assertEquals(listOf("other:track:1"), chain.tracks("song", 0, 10).items.map { it.uri })
    }

    @Test fun aChainBuiltFromADownloadedSetFallsBackPerProvider() {
        val set = ScriptSet(2, mapOf("deezer" to "function ("))
        val providers = accountlessProviders(okhttp3.OkHttpClient(), "Test/1", "CH", set)
        val deezer = providers.getValue("deezer") as ScriptProvider
        val tidal = providers.getValue("tidal") as ScriptProvider
        assertTrue(deezer.downloaded)
        assertFalse(tidal.downloaded)
    }

    // ── Order ──

    @Test fun theSignedInPlatformGoesWhereTheOrderPutsItElseFirst() {
        val all = mapOf("deezer" to Fake("deezer"), "itunes" to Fake("itunes"), "musicbrainz" to Fake("musicbrainz"))
        val spotify = Fake("spotify")
        assertEquals(listOf("spotify", "deezer", "itunes"), chainOf(all, listOf("deezer", "itunes"), spotify).map { it.id })
        assertEquals(listOf("deezer", "spotify", "itunes"), chainOf(all, listOf("deezer", "spotify", "itunes"), spotify).map { it.id })
        // A provider turned off is not in the order and never asked; nor is a signed-in platform not passed.
        assertEquals(listOf("itunes"), chainOf(all, listOf("spotify", "itunes"), null).map { it.id })
    }

    // ── The contract the UI sees ──

    private val spotifyTrack = Hit(track("spotify:track:S1", "Song", "Band"), "AAAAA0000001")
    private val deezerSame = Hit(track("deezer:track:D1", "Song", "Band"), "AAAAA0000001")
    private val deezerOnly = Hit(track("deezer:track:D2", "Rare Song", "Band"), "AAAAA0000002")

    @Test fun searchesComeFromTheChainAndTheRestFromThePlatform() = runBlocking {
        val spotify = Fake("spotify", listOf(spotifyTrack), albums = listOf(Album("spotify:album:A", "Album", listOf(ArtistRef("spotify:artist:1", "Band")), emptyList(), "album", null, 1)))
        val own = ProviderChain(listOf(spotify))
        var playlistsAsked = 0
        val platformSearch = object : Search by own {
            override suspend fun playlists(query: String, offset: Int, limit: Int): Page<Playlist> {
                playlistsAsked++
                return Page(listOf(Playlist("spotify:playlist:P", "Mix", null, null, emptyList(), 0)), offset, 1, null)
            }
        }
        val deezer = Fake("deezer", listOf(deezerSame, deezerOnly))
        val api = MetadataApi(platform(platformSearch, own), chain = { ProviderChain(listOf(deezer)) })
        val all = api.search.all("song", 10)
        // The same recording once: the platform is not in this chain, so its answer is Deezer's.
        assertEquals(listOf("deezer:track:D1", "deezer:track:D2"), all.tracks.items.map { it.uri })
        assertEquals(listOf("spotify:playlist:P"), all.playlists.items.map { it.uri })
        assertEquals(1, playlistsAsked)
        // An item of the platform still opens from the platform; one of a provider from that provider.
        assertEquals("Album", api.catalog.album("spotify:album:A").name)
        assertEquals("Rare Song", api.catalog.track("deezer:track:D2").name)
        assertEquals("AAAAA0000002", api.catalog.isrc("deezer:track:D2"))
        assertTrue(api.catalog.similarAlbums("deezer:track:D2", 5).isEmpty())
    }

    @Test fun theSignedInPlatformFirstAnswersEveryKindInOneRequest() = runBlocking {
        var alls = 0
        val spotify = Fake("spotify", listOf(spotifyTrack))
        val own = ProviderChain(listOf(spotify))
        val platformSearch = object : Search by own {
            override suspend fun all(query: String, limit: Int): app.myspoti.api.SearchResults {
                alls++
                delay(50)
                return own.all(query, limit).copy(playlists = Page(listOf(Playlist("spotify:playlist:P", "Mix", null, null, emptyList(), 0)), 0, 1, null))
            }
        }
        val signedIn = ApiProvider("spotify", platformSearch, own)
        val deezer = Fake("deezer", listOf(deezerSame, deezerOnly))
        val chain = ProviderChain(listOf(signedIn, deezer))
        val api = MetadataApi(platform(platformSearch, own), chain = { chain }, signedIn = { signedIn })
        val all = api.search.all("song", 10)
        assertEquals(1, alls)
        // The platform's own recording first; Deezer adds only what the platform did not have.
        assertEquals(listOf("spotify:track:S1", "deezer:track:D2"), all.tracks.items.map { it.uri })
        assertEquals(listOf("spotify:playlist:P"), all.playlists.items.map { it.uri })
    }

    @Test fun aSharedSearchThatFailsIsAskedAgain() = runBlocking {
        var alls = 0
        val own = ProviderChain(listOf(Fake("spotify", listOf(spotifyTrack))))
        val flaky = object : Search by own {
            override suspend fun all(query: String, limit: Int): app.myspoti.api.SearchResults {
                if (alls++ == 0) throw IOException("no route")
                return own.all(query, limit)
            }
        }
        val p = ApiProvider("spotify", flaky, own)
        assertTrue(runCatching { p.searchTracks("song", 0, 10) }.exceptionOrNull() is IOException)
        assertEquals("spotify:track:S1", p.searchTracks("song", 0, 10).items.single().track.uri)
    }

    @Test fun anIsrcSearchKeepsOnlyTheRecordingWithThatIsrc() = runBlocking {
        val inner = ProviderChain(listOf(Fake("spotify", listOf(Hit(track("spotify:track:S9", "Other", "Band"), "AAAAA0000009"), spotifyTrack))))
        val p = ApiProvider("spotify", inner, inner)
        // The fake search answers both songs whatever is asked, as a platform ignoring the filter would.
        assertEquals(listOf("spotify:track:S1"), p.byIsrc("AAAAA0000001").map { it.track.uri })
        assertTrue(p.byIsrc("not an isrc").isEmpty())
    }

    // ── The sync's ids ──

    @Test fun aCounterpartIsAskedOfOneProviderOnly() = runBlocking {
        val spotify = Fake("spotify", listOf(spotifyTrack))
        val deezer = Fake("deezer", listOf(deezerSame))
        val chain = ProviderChain(listOf(deezer, spotify))
        assertEquals("spotify:track:S1", chain.counterpartOn("spotify", "Song", "Band", 200_000, "AAAAA0000001")?.track?.uri)
        assertEquals("spotify:track:S1", chain.counterpartOn("spotify", "Song", "Band", 200_000)?.track?.uri)
        assertNull(chain.counterpartOn("spotify", "Another Song Entirely", "Nobody", 200_000))
        assertNull(chain.counterpartOn("tidal", "Song", "Band"))
        assertEquals(0, deezer.calls)
    }

    @Test fun aTrackWithoutAnIdIsLookedUpOnceThenKnown() = runBlocking {
        val spotify = Fake("spotify", listOf(spotifyTrack))
        val chain = ProviderChain(listOf(spotify))
        val found = CompletableDeferred<Unit>()
        val ids = CatalogTrackIds({ chain }, CoroutineScope(Dispatchers.Default), onFound = { found.complete(Unit) })
        val r = TrackIdResolver.chain(TrackIdResolver.KnownIds, ids)
        val local = SyncTrack(title = "Song", artists = listOf("Band"), durationMs = 200_000)
        assertNull(r.idFor(local, "spotify"))
        withTimeout(5_000) { found.await() }
        assertEquals("S1", r.idFor(local, "spotify"))
        val asked = spotify.calls
        assertEquals("S1", r.idFor(local, "spotify"))
        assertEquals(asked, spotify.calls)
        // The id a track carries comes first, without a lookup.
        assertEquals("K", r.idFor(local.copy(ids = mapOf("spotify" to "K")), "spotify"))
    }

    @Test fun aTrackNotFoundIsNotAskedAgainSoonAndAnUntitledOneNever() = runBlocking {
        val spotify = Fake("spotify", listOf(spotifyTrack))
        val chain = ProviderChain(listOf(spotify))
        val scope = CoroutineScope(Dispatchers.Default)
        val ids = CatalogTrackIds({ chain }, scope, onFound = {})
        val missing = SyncTrack(title = "Nothing Like It", artists = listOf("Nobody"), durationMs = 100_000)
        assertNull(ids.idFor(missing, "spotify"))
        delay(300)
        val asked = spotify.calls
        assertTrue(asked >= 1)
        assertNull(ids.idFor(missing, "spotify"))
        delay(100)
        assertEquals(asked, spotify.calls)
        assertNull(ids.idFor(SyncTrack(), "spotify"))
        // Lookups made side by side for the same track are one lookup.
        val one = CatalogTrackIds({ chain }, scope, onFound = {})
        val same = SyncTrack(title = "Song", artists = listOf("Band"), durationMs = 200_000, isrc = "AAAAA0000001")
        val before = spotify.calls
        (1..5).map { async { one.idFor(same, "spotify") } }.awaitAll()
        withTimeout(5_000) { while (spotify.calls == before) delay(10) }
        delay(100)
        assertEquals(before + 1, spotify.calls)
    }

    /** Reported: "not found on Spotify" for a song that played when tapped again. A failure is not an absence. */
    @Test fun aProviderThatCannotAnswerIsNeverTakenForOneThatHasNoSuchSong() = runBlocking {
        val chain = ProviderChain(listOf(Fake("spotify", listOf(spotifyTrack), down = true)), failuresToRest = 1)
        assertTrue(chain.counterpartAnswer("spotify", "Song", "Band", 200_000) is Counterpart.Unknown)
        // Rested after the failure: still unknown, not absent.
        assertTrue(chain.counterpartAnswer("spotify", "Song", "Band", 200_000) is Counterpart.Unknown)
        val up = ProviderChain(listOf(Fake("spotify", listOf(spotifyTrack))))
        assertEquals(Counterpart.Absent, up.counterpartAnswer("spotify", "Another Song Entirely", "Nobody", 200_000))
        assertEquals("spotify:track:S1", (up.counterpartAnswer("spotify", "Song", "Band", 200_000) as Counterpart.Found).hit.track.uri)
        // Another failure than the network's (a session not ready) is unknown too, and thrown at nobody.
        val odd = object : Fake("spotify", listOf(spotifyTrack)) {
            override suspend fun find(title: String, artist: String, limit: Int): List<Hit> = throw IllegalStateException("not ready")
        }
        assertTrue(ProviderChain(listOf(odd)).counterpartAnswer("spotify", "Song", "Band", 200_000) is Counterpart.Unknown)
    }

    @Test fun aLookupThatFailedIsAskedAgainSoonNotHalfAnHourLater() = runBlocking {
        var down = true
        val spotify = object : Fake("spotify", listOf(spotifyTrack)) {
            override suspend fun find(title: String, artist: String, limit: Int): List<Hit> { calls++; if (down) throw ProviderDown(id, "down"); return hits }
        }
        var clock = 1_000_000L
        val chain = ProviderChain(listOf(spotify), failuresToRest = 99, now = { clock })
        val found = CompletableDeferred<Unit>()
        val ids = CatalogTrackIds({ chain }, CoroutineScope(Dispatchers.Default), onFound = { found.complete(Unit) }, retryMs = 60_000, now = { clock })
        val local = SyncTrack(title = "Song", artists = listOf("Band"), durationMs = 200_000)
        assertNull(ids.idFor(local, "spotify"))
        withTimeout(5_000) { while (spotify.calls == 0) delay(10) }
        delay(100)
        // Within the retry wait: not asked again.
        clock += 10_000
        assertNull(ids.idFor(local, "spotify"))
        delay(100)
        assertEquals(1, spotify.calls)
        // Past it, the network back: asked again and found.
        down = false
        clock += 60_000
        assertNull(ids.idFor(local, "spotify"))
        withTimeout(5_000) { found.await() }
        assertEquals("S1", ids.idFor(local, "spotify"))
    }

    // ── Updates ──

    @Test fun onlyHttpsOrThisDevicesOwnAddressInDebug() {
        assertTrue(scriptsUrlAllowed("https://raw.githubusercontent.com/x/y/main/"))
        assertFalse(scriptsUrlAllowed("http://localhost:8000/"))
        assertTrue(scriptsUrlAllowed("http://localhost:8000/", allowLocalhost = true))
        assertTrue(scriptsUrlAllowed("http://127.0.0.1:8000/", allowLocalhost = true))
        assertFalse(scriptsUrlAllowed("http://192.168.1.2:8000/", allowLocalhost = true))
        assertFalse(scriptsUrlAllowed("http://localhost.example.com/", allowLocalhost = true))
        assertFalse(scriptsUrlAllowed("http://user@localhost/", allowLocalhost = true))
        assertFalse(scriptsUrlAllowed("file:///sdcard/x", allowLocalhost = true))
    }

    @Test fun updatesAreLookedForDailyAndSoonerAfterAFailure() {
        val h = 3_600_000L
        assertTrue(ScriptsSchedule.due(10 * h, 0, 0))
        assertFalse(ScriptsSchedule.due(23 * h, 0 + 1, 0))
        assertTrue(ScriptsSchedule.due(25 * h, 1, 0))
        assertTrue(ScriptsSchedule.due(1 * h + 1, 1, 1))
        assertFalse(ScriptsSchedule.due(1 * h + 1, 1, 2))
        assertTrue(ScriptsSchedule.due(2 * h + 1, 1, 2))
        assertFalse(ScriptsSchedule.due(23 * h, 1, 30))
        assertTrue(ScriptsSchedule.due(24 * h + 1, 1, 30))
        // A clock moved back.
        assertTrue(ScriptsSchedule.due(5, 10, 0))
        assertEquals(3, ScriptsSchedule.failuresAfter(UpdateResult.Unreachable("x"), 2))
        assertEquals(0, ScriptsSchedule.failuresAfter(UpdateResult.Rejected("x"), 2))
        assertEquals(0, ScriptsSchedule.failuresAfter(UpdateResult.UpToDate(2), 2))
    }
}
