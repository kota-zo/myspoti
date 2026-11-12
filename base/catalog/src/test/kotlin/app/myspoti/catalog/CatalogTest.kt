/* SPDX-License-Identifier: GPL-3.0-only */
package app.myspoti.catalog

import app.myspoti.api.Album
import app.myspoti.api.AlbumRef
import app.myspoti.api.Artist
import app.myspoti.api.ArtistRef
import app.myspoti.api.Page
import app.myspoti.api.Track
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogTest {
    private class Scripted(val answers: Map<String, Response>) : Fetch {
        val asked = ArrayList<String>()
        override suspend fun get(url: String): Response {
            asked += url
            return answers.entries.firstOrNull { url.contains(it.key) }?.value ?: Response(404, "{}")
        }
    }

    private fun track(uri: String, name: String, artist: String, ms: Long = 200_000) =
        Track(uri, name, ms, listOf(ArtistRef("x:artist:1", artist)), null)

    private open class FakeProvider(
        override val id: String,
        val hits: List<Hit> = emptyList(),
        val artists: List<Artist> = emptyList(),
        val top: List<Hit> = emptyList(),
        val down: Boolean = false,
    ) : MetadataProvider {
        var calls = 0
        private fun check() { calls++; if (down) throw ProviderDown(id, "down") }
        override suspend fun searchTracks(query: String, offset: Int, limit: Int): Page<Hit> { check(); return Page(hits, offset, hits.size, null) }
        override suspend fun searchAlbums(query: String, offset: Int, limit: Int) = Page<Album>(emptyList(), offset, 0, null)
        override suspend fun searchArtists(query: String, offset: Int, limit: Int): Page<Artist> { check(); return Page(artists, offset, artists.size, null) }
        override suspend fun find(title: String, artist: String, limit: Int): List<Hit> { check(); return hits }
        override suspend fun byIsrc(isrc: String): List<Hit> { check(); return hits.filter { it.isrc == isrc } }
        override suspend fun track(id: String) = hits.first()
        override suspend fun album(id: String) = throw NotFound(this.id, id)
        override suspend fun albumTracks(id: String, offset: Int, limit: Int) = Page<Hit>(emptyList(), offset, 0, null)
        override suspend fun artist(id: String) = artists.first()
        override suspend fun topTracks(id: String) = top
        override suspend fun discography(id: String, offset: Int, limit: Int) = Page<Album>(emptyList(), offset, 0, null)
        override suspend fun related(id: String) = emptyList<Artist>()
    }

    // ── Matching ──

    @Test fun whatNeverTellsARecordingApartIsLeftOut() {
        assertEquals("beyonce song", Match.title("Beyoncé: Song"))
        assertEquals("song", Match.title("Song (feat. Someone Else)"))
        assertEquals("song", Match.title("Song - 2011 Remaster"))
        assertEquals("song", Match.title("Song (Remastered 2009)"))
        assertEquals("song", Match.title("Song - Radio Edit"))
        assertEquals("song", Match.title("Song [with Someone]"))
        assertEquals("song", Match.title("Song feat. Someone"))
        assertEquals("song", Match.title("Song (From \"A Film\")"))
        // These words belong to the title, and a credit that names a version stays.
        assertEquals("with you", Match.title("With You"))
        assertEquals("song with a remix", Match.title("Song (with A Remix)"))
    }

    @Test fun anISRCIsReadWhateverItsWriting() {
        assertEquals("GBDUW0000053", Match.isrc("gb-duw-00-00053"))
        assertNull(Match.isrc("not an isrc"))
        assertNull(Match.isrc(null))
    }

    @Test fun oneArtistInCommonIsEnoughButNotPartOfAName() {
        assertTrue(Match.artistFit("Queen & David Bowie", listOf("Queen")) >= 0.9)
        assertTrue(Match.artistFit("Band", listOf("Someone, Band")) >= 0.9)
        assertTrue(Match.artistFit("The Rolling Stones", listOf("Rolling Stones")) >= 0.5)
        assertTrue(Match.artistFit("Queen", listOf("Queens of the Stone Age")) < 0.5)
    }

    @Test fun aPreviewIsNotTheSong() {
        val clip = track("a:track:1", "Song", "Band", 30_000)
        assertEquals(0.0, Match.score("Song", "Band", 200_000, clip), 0.0)
    }

    @Test fun anotherScriptTakesTheSameArtistAndLength() {
        val hangul = track("a:track:1", "\uBD04\uB0A0", "BTS", 200_000)
        assertTrue(Match.score("Spring Day", "BTS", 201_000, hangul) >= Match.SURE)
        assertEquals(0.0, Match.score("Spring Day", "BTS", 206_000, hangul), 0.0)
        assertEquals(0.0, Match.score("Spring Day", "Someone Else", 201_000, hangul), 0.0)
    }

    @Test fun anotherVersionIsNotTheSameRecording() {
        val studio = track("a:track:1", "Song", "Band")
        assertEquals(0.0, Match.score("Song (Live at Wembley)", "Band", 200_000, studio), 0.0)
        assertEquals(0.0, Match.score("Song - Acoustic", "Band", 200_000, studio), 0.0)
        assertEquals(0.0, Match.score("Song (Remix)", "Band", 200_000, studio), 0.0)
        assertTrue(Match.score("Song - 2011 Remaster", "Band", 201_000, studio) >= Match.SURE)
    }

    @Test fun anotherLengthOrArtistIsNotTheSameRecording() {
        val song = track("a:track:1", "Song", "Band")
        assertEquals(0.0, Match.score("Song", "Band", 260_000, song), 0.0)
        assertEquals(0.0, Match.score("Song", "Somebody Else", 200_000, song), 0.0)
        assertEquals(0.0, Match.score("Another Thing", "Band", 200_000, song), 0.0)
    }

    @Test fun anISRCDecidesOverEverythingElse() {
        val a = Hit(track("a:track:1", "Song", "Band"), "AAAAA0000001")
        assertTrue(Match.same(a, Hit(track("b:track:2", "Different Words", "Others", 1), "aaaaa0000001")))
        assertFalse(Match.same(a, Hit(track("b:track:2", "Song", "Band"), "BBBBB0000002")))
    }

    // ── Dependability ──

    @Test fun aFailureIsTriedAgainAsLongAsTheServiceAsks() = runBlocking {
        val answers = ArrayDeque(listOf(Response(503, "", 2_000), Response(429, ""), Response(200, "ok")))
        val slept = ArrayList<Long>()
        val f = ReliableFetch({ answers.removeFirst() }, "t", attempts = 3, backoffMs = 100, sleep = { slept += it })
        assertEquals("ok", f.get("u").body)
        assertEquals(listOf(2_000L, 200L), slept)
    }

    @Test fun aNetworkErrorIsTriedAgainThenTold() = runBlocking {
        var calls = 0
        val f = ReliableFetch({ calls++; throw IOException("no route") }, "t", attempts = 3, sleep = {})
        val e = runCatching { f.get("u") }.exceptionOrNull()
        assertTrue(e is ProviderDown)
        assertEquals(3, calls)
    }

    @Test fun whatWasSeenOutlivesAServiceThatIsDown() = runBlocking {
        var time = 0L
        var up = true
        val f = ReliableFetch({ if (up) Response(200, "kept") else Response(500, "") }, "t", freshMs = 100, now = { time }, sleep = {})
        assertEquals("kept", f.get("u").body)
        up = false; time = 1_000
        assertEquals("kept", f.get("u").body)
    }

    @Test fun aFreshAnswerIsNotAskedAgainAndNotFoundIsAnAnswer() = runBlocking {
        var calls = 0
        val f = ReliableFetch({ calls++; Response(404, "") }, "t", sleep = {})
        assertEquals(404, f.get("u").status)
        assertEquals(404, f.get("u").status)
        assertEquals(1, calls)
    }

    // ── Several providers ──

    @Test fun aSearchMergesTheSameRecordingOnce() = runBlocking {
        val chain = ProviderChain(listOf(
            FakeProvider("a", listOf(Hit(track("a:track:1", "Song", "Band"), "AAAAA0000001"))),
            FakeProvider("b", listOf(Hit(track("b:track:9", "Song - 2011 Remaster", "Band"), "AAAAA0000001"), Hit(track("b:track:8", "Other", "Band"), "BBBBB0000002"))),
        ))
        assertEquals(listOf("a:track:1", "b:track:8"), chain.tracks("q").items.map { it.uri })
    }

    @Test fun aProviderDownLeavesTheOthersAnswer() = runBlocking {
        val chain = ProviderChain(listOf(FakeProvider("a", down = true), FakeProvider("b", listOf(Hit(track("b:track:1", "Song", "Band"))))))
        assertEquals(1, chain.tracks("q").items.size)
    }

    @Test fun allProvidersDownIsAnError() = runBlocking {
        val chain = ProviderChain(listOf(FakeProvider("a", down = true), FakeProvider("b", down = true)))
        assertTrue(runCatching { chain.tracks("q") }.exceptionOrNull() is ProviderDown)
    }

    @Test fun aProviderThatKeepsFailingRestsThenIsTriedAgain() = runBlocking {
        var time = 0L
        val bad = FakeProvider("a", down = true)
        val chain = ProviderChain(listOf(bad, FakeProvider("b", listOf(Hit(track("b:track:1", "Song", "Band"))))), restMs = 1_000, failuresToRest = 2, now = { time })
        repeat(5) { chain.tracks("q") }
        assertEquals(2, bad.calls)
        time = 2_000
        chain.tracks("q")
        assertEquals(3, bad.calls)
    }

    @Test fun resolvingPrefersTheISRCThenRefusesAGuess() = runBlocking {
        val chain = ProviderChain(listOf(FakeProvider("a", listOf(
            Hit(track("a:track:1", "Song", "Band"), "AAA"),
            Hit(track("a:track:2", "Song (Live)", "Band"), "BBBBB0000002"),
        ))))
        assertEquals("a:track:2", chain.resolve("Whatever", "Whoever", 0, "BBBBB0000002")?.track?.uri)
        assertEquals("a:track:1", chain.resolve("Song", "Band", 200_000)?.track?.uri)
        assertNull(chain.resolve("Song (Acoustic)", "Band", 200_000))
        assertNull(chain.resolve("Song", "Nobody Related", 200_000))
    }

    @Test fun aRecordingIsFoundOnTheOtherPlatforms() = runBlocking {
        val source = Hit(track("a:track:1", "Song", "Band"), "AAAAA0000001")
        val chain = ProviderChain(listOf(
            FakeProvider("a", listOf(source)),
            FakeProvider("b", listOf(Hit(track("b:track:2", "Other Words", "Others", 199_000), "AAAAA0000001"))),
            FakeProvider("c", listOf(Hit(track("c:track:3", "Song - 2011 Remaster", "Band", 201_000)))),
            FakeProvider("d", listOf(Hit(track("d:track:4", "Song (Live)", "Band")))),
            FakeProvider("e", down = true),
        ))
        val found = chain.counterparts(source)
        assertEquals(mapOf("b" to "b:track:2", "c" to "c:track:3"), found.mapValues { it.value.track.uri })
    }

    @Test fun anISRCNeverBringsBackAPreview() = runBlocking {
        val chain = ProviderChain(listOf(FakeProvider("a", listOf(
            Hit(track("a:track:1", "Song", "Band", 30_000), "AAAAA0000001"),
            Hit(track("a:track:2", "Song", "Band", 199_000), "AAAAA0000001"),
        ))))
        assertEquals("a:track:2", chain.resolve("Song", "Band", 200_000, "AAAAA0000001")?.track?.uri)
    }

    @Test fun aTitleWithBracketsIsAlsoSearchedWithout() = runBlocking {
        val asked = ArrayList<String>()
        val p = object : FakeProvider("a") {
            override suspend fun find(title: String, artist: String, limit: Int): List<Hit> {
                asked += title
                return if ("feat" in title) emptyList() else listOf(Hit(track("a:track:1", "Song", "Band")))
            }
        }
        assertEquals("a:track:1", ProviderChain(listOf(p)).resolve("Song (feat. Someone)", "Band", 200_000)?.track?.uri)
        assertEquals(listOf("Song (feat. Someone)", "Song"), asked)
    }

    @Test fun whatAProviderLacksComesFromAnotherOnTheSameNameOnly() = runBlocking {
        val withPicture = Artist("b:artist:7", "Band", listOf(app.myspoti.api.Image("pic")))
        val a = FakeProvider("a", artists = listOf(Artist("a:artist:1", "Band", emptyList())))
        val b = FakeProvider("b", artists = listOf(Artist("b:artist:6", "Band Two", listOf(app.myspoti.api.Image("wrong"))), withPicture),
            top = listOf(Hit(track("b:track:1", "Hit", "Band"))))
        val chain = ProviderChain(listOf(a, b))
        assertEquals("pic", chain.artist("a:artist:1").images.single().url)
        assertEquals(listOf("b:track:1"), chain.artistTopTracks("a:artist:1").map { it.uri })
    }

    @Test fun anItemGoesBackToItsProvider() = runBlocking {
        val chain = ProviderChain(listOf(FakeProvider("a"), FakeProvider("b", listOf(Hit(track("b:track:1", "Song", "Band"))))))
        assertEquals("b:track:1", chain.track("b:track:1").uri)
        assertTrue(runCatching { chain.track("spotify:track:1") }.exceptionOrNull() is NotFound)
        assertTrue(runCatching { chain.track("broken") }.exceptionOrNull() is IllegalArgumentException)
    }

    // ── Reading the services' answers ──

    @Test fun musicBrainzRecordingsComeWithTheirAlbumAndCover() = runBlocking {
        val f = Scripted(mapOf("/recording?" to Response(200, """{"count":1,"recordings":[{"id":"r1","title":"Song","length":200000,
          "artist-credit":[{"name":"Band","artist":{"id":"a1","name":"Band"}}],
          "releases":[{"id":"bootleg","status":"Bootleg","date":"1999","release-group":{"id":"gX","title":"Wrong"}},
                      {"id":"late","status":"Official","date":"2010","release-group":{"id":"gY","title":"Later"}},
                      {"id":"first","status":"Official","date":"2001","title":"Album","release-group":{"id":"g1","title":"Album"}}]}]}""")))
        val hit = MusicBrainz(f).searchTracks("song").items.single()
        assertEquals("musicbrainz:track:r1", hit.track.uri)
        assertEquals("Band", hit.track.artists.single().name)
        assertEquals("musicbrainz:album:g1", hit.track.album?.uri)
        assertTrue(hit.track.album!!.images.all { it.url.contains("/release-group/g1/") })
        assertTrue(f.asked.single().contains("fmt=json"))
    }

    @Test fun musicBrainzAlbumsReadAnOfficialEdition() = runBlocking {
        val f = Scripted(mapOf(
            "/release-group/g1" to Response(200, """{"id":"g1","title":"Album","releases":[{"id":"e2","status":"Official","date":"2001"},{"id":"e3","status":"Promotion","date":"2000"}]}"""),
            "/release/e2" to Response(200, """{"media":[{"track-count":1,"tracks":[{"title":"One","recording":{"id":"r1","title":"One rec","length":1000,"isrcs":["AAA"]}}]},
              {"track-count":1,"tracks":[{"title":"Two","recording":{"id":"r2","title":"Two","length":2000}}]}]}"""),
        ))
        val p = MusicBrainz(f).albumTracks("g1")
        assertEquals(listOf("One", "Two"), p.items.map { it.track.name })
        assertEquals("AAA", p.items[0].isrc)
        assertEquals("musicbrainz:album:g1", p.items[0].track.album?.uri)
        assertEquals(2, MusicBrainz(f).album("g1").trackCount)
    }

    @Test fun deezerTracksAlbumsAndItsErrors() = runBlocking {
        val f = Scripted(mapOf(
            "/search?" to Response(200, """{"total":40,"data":[{"id":5,"title":"Song","duration":200,"isrc":"AAA","explicit_lyrics":true,
               "artist":{"id":7,"name":"Band"},"album":{"id":9,"title":"Album","cover_medium":"m","cover_big":"b"}}]}"""),
            "/track/isrc:NONE" to Response(200, """{"error":{"type":"DataException","message":"no data","code":800}}"""),
            "/track/1" to Response(200, """{"error":{"type":"Exception","message":"Quota limit exceeded","code":4}}"""),
        ))
        val d = Deezer(f)
        val page = d.searchTracks("song", 0, 1)
        val hit = page.items.single()
        assertEquals(1, page.nextOffset)
        assertEquals("deezer:track:5", hit.track.uri)
        assertEquals(200_000L, hit.track.durationMs)
        assertEquals("AAA", hit.isrc)
        assertTrue(hit.track.explicit)
        assertEquals(listOf("m", "b"), hit.track.album!!.images.map { it.url })
        assertTrue(d.byIsrc("NONE").isEmpty())
        assertTrue(runCatching { d.track("1") }.exceptionOrNull() is ProviderDown)
    }

    @Test fun itunesSongsAlbumsAndItsMissingCount() = runBlocking {
        val f = Scripted(mapOf(
            "/search?term=song" to Response(200, """{"resultCount":2,"results":[
              {"wrapperType":"track","kind":"song","trackId":5,"trackName":"Song","artistId":7,"artistName":"Band","collectionId":9,"collectionName":"Album","artworkUrl100":"a100","trackTimeMillis":200000,"trackExplicitness":"explicit"},
              {"wrapperType":"track","kind":"music-video","trackId":6,"trackName":"Clip"}]}"""),
            "/lookup?id=9&entity=song" to Response(200, """{"results":[{"wrapperType":"collection","collectionId":9,"collectionName":"Album"},
              {"wrapperType":"track","kind":"song","trackId":5,"trackName":"Song","artistId":7,"artistName":"Band","collectionId":9,"collectionName":"Album"}]}"""),
            "/lookup?id=404" to Response(200, """{"results":[]}"""),
        ))
        val d = ITunes(f, country = "CH")
        val hit = d.searchTracks("song", 0, 1).items.single()
        assertEquals("itunes:track:5", hit.track.uri)
        assertEquals("itunes:album:9", hit.track.album?.uri)
        assertTrue(hit.track.explicit)
        assertNull(hit.isrc)
        assertTrue(f.asked.single().contains("country=CH"))
        assertEquals(1, d.albumTracks("9").items.size)
        assertTrue(d.byIsrc("AAA").isEmpty())
        assertTrue(runCatching { d.track("404") }.exceptionOrNull() is NotFound)
    }

    @Test fun aServiceThatAnswersNonsenseIsDown() = runBlocking {
        val d = Deezer(Scripted(mapOf("/search" to Response(200, "<html>Bad gateway</html>"))))
        assertTrue(runCatching { d.searchTracks("q") }.exceptionOrNull() is ProviderDown)
    }

    @Test fun lyricsFallBackOnASearchWithinTheSameLength() = runBlocking {
        val f = Scripted(mapOf("/api/search" to Response(200, """[
            {"duration":100,"syncedLyrics":"[00:01.00] far off"},
            {"duration":201,"plainLyrics":"plain words"},
            {"duration":199,"syncedLyrics":"[00:01.00] timed words","plainLyrics":"plain words"}]""")))
        val l = LrcLib(f).lyrics("Song", "Band", null, 200_000)
        assertEquals("[00:01.00] timed words", l?.synced)
        assertNull(LrcLib(Scripted(emptyMap())).lyrics("Song", "Band", null, 200_000))
    }

    @Test fun aUriIsThreeParts() {
        assertEquals(ItemUri("musicbrainz", "track", "a:b"), ItemUri.parse("musicbrainz:track:a:b"))
        assertEquals("deezer:album:1", ItemUri("deezer", "album", "1").toString())
    }

    @Suppress("unused") private val keep = AlbumRef::class
}
