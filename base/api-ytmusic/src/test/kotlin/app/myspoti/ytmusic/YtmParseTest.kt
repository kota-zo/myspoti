package app.myspoti.ytmusic

import app.myspoti.ytmusic.YtmParse.Item
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The web app's answers, as synthetic fixtures shaped like the recorded ones (no real titles). */
class YtmParseTest {
    companion object {
        fun fixture(name: String): JsonObject =
            Json.parseToJsonElement(YtmParseTest::class.java.getResourceAsStream("/fixtures/$name.json")!!.bufferedReader().readText()).jsonObject
    }

    @Test fun `liked music rows, not the suggestions under them, and the next page's token`() {
        val a = fixture("liked")
        val songs = YtmParse.rows(a).filterIsInstance<Item.Song>().map { it.track }
        assertEquals(listOf("ytmusic:track:vidAAAAAAA1", "ytmusic:track:vidAAAAAAA2"), songs.map { it.uri })
        val one = songs[0]
        assertEquals("Song One", one.name)
        assertEquals(225_000L, one.durationMs)
        assertEquals(listOf("ytmusic:artist:UCartistA000000000000000"), one.artists.map { it.uri })
        assertEquals("ytmusic:album:MPREb_albumX", one.album?.uri)
        assertTrue(one.explicit)
        assertEquals("set1", one.uid)
        assertEquals(3_723_000L, songs[1].durationMs)
        assertNull(songs[1].album)
        assertEquals("TOKEN-PAGE-2", YtmParse.continuation(a))
    }

    @Test fun `a continuation answer, without podcast episodes`() {
        val more = YtmParse.rows(fixture("liked-more")).filterIsInstance<Item.Song>().map { it.track.uri }
        assertEquals(listOf("ytmusic:track:vidAAAAAAA3"), more)
        assertNull(YtmParse.continuation(fixture("liked-more")))
    }

    @Test fun `the account's playlists, without the new playlist card`() {
        val p = YtmParse.cards(fixture("playlists")).filterIsInstance<Item.PlaylistItem>().map { it.playlist }
        assertEquals(listOf("ytmusic:playlist:LM", "ytmusic:playlist:PLroadmix0001"), p.map { it.uri })
        assertEquals(12, p[1].trackCount)
        assertEquals("Tester", p[1].owner)
        assertTrue(p[1].images.isNotEmpty())
    }

    @Test fun `a search, its top result, songs and videos, albums, playlists`() {
        val items = YtmParse.search(fixture("search"))
        val artist = items.filterIsInstance<Item.ArtistItem>().single().artist
        assertEquals("ytmusic:artist:UCartistA000000000000000", artist.uri)
        assertEquals(listOf("ytmusic:track:vidAAAAAAA1", "ytmusic:track:vidVIDEO001"), items.filterIsInstance<Item.Song>().map { it.track.uri })
        val album = items.filterIsInstance<Item.AlbumItem>().single().album
        assertEquals("ytmusic:album:MPREb_albumX", album.uri)
        assertEquals("2021", album.releaseDate)
        assertEquals("Artist A", album.artists.single().name)
        assertEquals("ytmusic:playlist:PLroadmix0001", items.filterIsInstance<Item.PlaylistItem>().single().playlist.uri)
    }

    @Test fun `an album page, its header and its songs, which take the album and its artist`() {
        val (album, songs) = YtmParse.album("MPREb_albumX", fixture("album"))
        assertEquals("Album X", album.name)
        assertEquals("single", album.type)
        assertEquals("2021", album.releaseDate)
        assertEquals(2, album.trackCount)
        assertEquals(listOf("vidAAAAAAA1", "vidAAAAAAA3"), songs.map { it.uri.substringAfterLast(':') })
        assertTrue(songs.all { it.album?.uri == "ytmusic:album:MPREb_albumX" && it.artists.single().uri == "ytmusic:artist:UCartistA000000000000000" })
        assertEquals(240_000L, songs[1].durationMs)
    }

    @Test fun `an artist page, top songs, releases, related artists`() {
        val p = YtmParse.artist("UCartistA000000000000000", fixture("artist"))
        assertEquals("Artist A", p.artist.name)
        assertEquals("A synthetic artist.", p.about)
        assertEquals(listOf("ytmusic:track:vidAAAAAAA1"), p.songs.map { it.uri })
        assertEquals(listOf("Album X", "Album Y"), p.albums.map { it.name })
        assertEquals(listOf("ytmusic:artist:UCartistB000000000000000"), p.related.map { it.uri })
    }

    @Test fun `a song read from its watch-next list`() {
        val t = YtmParse.watchNextTrack("vidAAAAAAA1", fixture("next"))!!
        assertEquals("Song One", t.name)
        assertEquals(225_000L, t.durationMs)
        assertEquals("ytmusic:album:MPREb_albumX", t.album?.uri)
        assertEquals("Artist A", t.artists.single().name)
        assertNull(YtmParse.watchNextTrack("vidMISSING1", fixture("next")))
    }

    @Test fun `lengths and odd answers`() {
        assertEquals(59_000L, YtmParse.lengthMs("0:59"))
        assertNull(YtmParse.lengthMs("2021"))
        assertNull(YtmParse.lengthMs(null))
        assertTrue(YtmParse.rows(JsonObject(emptyMap())).isEmpty())
        assertFalse(YtmParse.search(JsonObject(emptyMap())).isNotEmpty())
    }
}
