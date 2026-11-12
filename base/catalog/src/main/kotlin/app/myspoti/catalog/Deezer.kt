/* SPDX-License-Identifier: GPL-3.0-only */
package app.myspoti.catalog

import app.myspoti.api.Album
import app.myspoti.api.AlbumRef
import app.myspoti.api.Artist
import app.myspoti.api.ArtistRef
import app.myspoti.api.Image
import app.myspoti.api.Page
import app.myspoti.api.Track
import kotlinx.serialization.json.JsonObject

/**
 * Deezer's public catalogue: the best free-text search here, with popularity, covers and artist pictures, and no
 * key. Its terms limit the API to non-commercial use, so whoever builds the app decides whether it is on.
 */
class Deezer(private val fetch: Fetch, private val base: String = "https://api.deezer.com") : MetadataProvider {
    override val id = "deezer"

    private suspend fun json(path: String): JsonObject {
        val r = fetch.get("$base$path")
        if (r.status == 404) throw NotFound(id, path)
        val o = parseObject(id, r.body)
        // Errors come as a 200.
        val error = o.obj("error") ?: return o
        if (error.int("code") == NO_DATA) throw NotFound(id, path)
        throw ProviderDown(id, error.str("message") ?: "error ${error.int("code")}")
    }

    override suspend fun searchTracks(query: String, offset: Int, limit: Int): Page<Hit> {
        val o = json("/search?q=${query.q()}&index=$offset&limit=$limit")
        return pageOf(o.objects("data").map { track(it, null) }, offset, o.int("total") ?: 0, limit)
    }

    override suspend fun searchAlbums(query: String, offset: Int, limit: Int): Page<Album> {
        val o = json("/search/album?q=${query.q()}&index=$offset&limit=$limit")
        return pageOf(o.objects("data").map(::album), offset, o.int("total") ?: 0, limit)
    }

    override suspend fun searchArtists(query: String, offset: Int, limit: Int): Page<Artist> {
        val o = json("/search/artist?q=${query.q()}&index=$offset&limit=$limit")
        return pageOf(o.objects("data").map(::artist), offset, o.int("total") ?: 0, limit)
    }

    override suspend fun find(title: String, artist: String, limit: Int): List<Hit> =
        searchTracks("$artist $title", 0, limit).items

    override suspend fun byIsrc(isrc: String): List<Hit> = try {
        listOf(track(json("/track/isrc:${isrc.q()}"), null).copy(isrc = isrc))
    } catch (_: NotFound) {
        emptyList()
    }

    override suspend fun track(id: String): Hit = track(json("/track/${id.q()}"), null)

    override suspend fun album(id: String): Album = album(json("/album/${id.q()}"))

    override suspend fun albumTracks(id: String, offset: Int, limit: Int): Page<Hit> {
        val a = json("/album/${id.q()}")
        val o = json("/album/${id.q()}/tracks?index=$offset&limit=$limit")
        val ref = AlbumRef(uri("album", id), a.str("title").orEmpty(), covers(a))
        return pageOf(o.objects("data").map { track(it, ref) }, offset, o.int("total") ?: a.int("nb_tracks") ?: 0, limit)
    }

    override suspend fun artist(id: String): Artist = artist(json("/artist/${id.q()}"))

    override suspend fun topTracks(id: String): List<Hit> = json("/artist/${id.q()}/top?limit=10").objects("data").map { track(it, null) }

    override suspend fun discography(id: String, offset: Int, limit: Int): Page<Album> {
        val o = json("/artist/${id.q()}/albums?index=$offset&limit=$limit")
        return pageOf(o.objects("data").map(::album), offset, o.int("total") ?: 0, limit)
    }

    override suspend fun related(id: String): List<Artist> = json("/artist/${id.q()}/related").objects("data").map(::artist)

    private fun uri(kind: String, itemId: String) = ItemUri(id, kind, itemId).toString()

    private fun track(o: JsonObject, album: AlbumRef?): Hit {
        val artists = o.objects("contributors").ifEmpty { listOfNotNull(o.obj("artist")) }.map {
            ArtistRef(uri("artist", it.long("id").toString()), it.str("name").orEmpty())
        }
        val own = o.obj("album")?.let { a -> AlbumRef(uri("album", a.long("id").toString()), a.str("title").orEmpty(), covers(a)) }
        return Hit(
            Track(
                uri = uri("track", o.long("id").toString()),
                name = o.str("title").orEmpty(),
                durationMs = (o.long("duration") ?: 0) * 1000,
                artists = artists,
                album = album ?: own,
                explicit = o.bool("explicit_lyrics") ?: false,
                playable = o.bool("readable") ?: true,
            ),
            o.str("isrc"),
        )
    }

    private fun album(o: JsonObject) = Album(
        uri = uri("album", o.long("id").toString()),
        name = o.str("title").orEmpty(),
        artists = listOfNotNull(o.obj("artist")).map { ArtistRef(uri("artist", it.long("id").toString()), it.str("name").orEmpty()) },
        images = covers(o),
        type = o.str("record_type") ?: "album",
        releaseDate = o.str("release_date"),
        trackCount = o.int("nb_tracks") ?: 0,
        label = o.str("label"),
    )

    private fun artist(o: JsonObject) = Artist(
        uri = uri("artist", o.long("id").toString()),
        name = o.str("name").orEmpty(),
        images = images(o, "picture"),
        followers = o.long("nb_fan"),
    )

    private fun covers(o: JsonObject) = images(o, "cover")

    private fun images(o: JsonObject, field: String) = listOf("small" to 56, "medium" to 250, "big" to 500, "xl" to 1000)
        .mapNotNull { (size, px) -> o.str("${field}_$size")?.let { Image(it, px, px) } }

    private companion object {
        const val NO_DATA = 800
    }
}
