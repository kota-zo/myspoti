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
 * Apple's public search: no key, good free-text results, no ISRC, and about 20 calls a minute, so it is paced well
 * below everything else. It knows no popularity and no related artists.
 */
class ITunes(private val fetch: Fetch, private val country: String = "US", private val base: String = "https://itunes.apple.com") : MetadataProvider {
    override val id = "itunes"

    private suspend fun results(path: String): List<JsonObject> {
        val sep = if ('?' in path) '&' else '?'
        val r = fetch.get("$base$path${sep}country=${country.q()}")
        if (r.status == 404) throw NotFound(id, path)
        return parseObject(id, r.body).objects("results")
    }

    private fun <T> page(items: List<T>, offset: Int, limit: Int) =
        // It never says how many there are: one more page while a page comes back full.
        Page(items, offset, offset + items.size + if (items.size >= limit) 1 else 0, (offset + limit).takeIf { items.size >= limit })

    override suspend fun searchTracks(query: String, offset: Int, limit: Int): Page<Hit> =
        page(results("/search?term=${query.q()}&entity=song&limit=$limit&offset=$offset").filter { it.str("kind") == "song" }.map(::track), offset, limit)

    override suspend fun searchAlbums(query: String, offset: Int, limit: Int): Page<Album> =
        page(results("/search?term=${query.q()}&entity=album&limit=$limit&offset=$offset").map(::album), offset, limit)

    override suspend fun searchArtists(query: String, offset: Int, limit: Int): Page<Artist> =
        page(results("/search?term=${query.q()}&entity=musicArtist&limit=$limit&offset=$offset").map(::artist), offset, limit)

    override suspend fun find(title: String, artist: String, limit: Int): List<Hit> = searchTracks("$artist $title", 0, limit).items

    /** It does not know ISRCs. */
    override suspend fun byIsrc(isrc: String): List<Hit> = emptyList()

    private suspend fun single(itemId: String, what: String) = results("/lookup?id=${itemId.q()}").firstOrNull() ?: throw NotFound(id, "$what $itemId")

    override suspend fun track(id: String): Hit = track(single(id, "track"))
    override suspend fun album(id: String): Album = album(single(id, "album"))

    override suspend fun albumTracks(id: String, offset: Int, limit: Int): Page<Hit> {
        val all = results("/lookup?id=${id.q()}&entity=song").filter { it.str("kind") == "song" }.map(::track)
        return pageOf(all.drop(offset).take(limit), offset, all.size, limit)
    }

    override suspend fun artist(id: String): Artist = artist(single(id, "artist"))
    override suspend fun topTracks(id: String): List<Hit> = emptyList()

    override suspend fun discography(id: String, offset: Int, limit: Int): Page<Album> {
        val all = results("/lookup?id=${id.q()}&entity=album").filter { it.str("wrapperType") == "collection" }.map(::album).sortedByDescending { it.releaseDate.orEmpty() }
        return pageOf(all.drop(offset).take(limit), offset, all.size, limit)
    }

    override suspend fun related(id: String): List<Artist> = emptyList()

    private fun uri(kind: String, itemId: Long?) = ItemUri(id, kind, itemId.toString()).toString()

    private fun track(o: JsonObject) = Hit(
        Track(
            uri = uri("track", o.long("trackId")),
            name = o.str("trackName").orEmpty(),
            durationMs = o.long("trackTimeMillis") ?: 0,
            artists = listOf(ArtistRef(uri("artist", o.long("artistId")), o.str("artistName").orEmpty())),
            album = o.long("collectionId")?.let { AlbumRef(uri("album", it), o.str("collectionName").orEmpty(), art(o)) },
            explicit = o.str("trackExplicitness") == "explicit",
        ),
    )

    private fun album(o: JsonObject) = Album(
        uri = uri("album", o.long("collectionId")),
        name = o.str("collectionName").orEmpty(),
        artists = listOf(ArtistRef(uri("artist", o.long("artistId")), o.str("artistName").orEmpty())),
        images = art(o),
        type = "album",
        releaseDate = o.str("releaseDate")?.take(10),
        trackCount = o.int("trackCount") ?: 0,
    )

    private fun artist(o: JsonObject) = Artist(uri("artist", o.long("artistId")), o.str("artistName").orEmpty(), emptyList())

    private fun art(o: JsonObject) = listOfNotNull(o.str("artworkUrl100")?.let { Image(it, 100, 100) })
}
