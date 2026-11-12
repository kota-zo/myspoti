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
 * MusicBrainz, the open identity of recordings, with cover art from the Cover Art Archive and similar artists
 * from ListenBrainz. The firmest source for "which recording is this", the weakest for free-text search, which
 * ranks covers and tributes as high as the original: it comes behind a commercial catalogue in a search.
 * Its albums are release groups, so one album is one uri whatever its editions.
 */
class MusicBrainz(
    private val fetch: Fetch,
    private val labs: Fetch = fetch,
    private val base: String = "https://musicbrainz.org/ws/2",
    private val labsBase: String = "https://labs.api.listenbrainz.org",
    private val coverBase: String = "https://coverartarchive.org",
) : MetadataProvider {
    override val id = "musicbrainz"

    private suspend fun json(path: String): JsonObject {
        val sep = if ('?' in path) '&' else '?'
        val r = fetch.get("$base$path${sep}fmt=json")
        if (r.status == 404) throw NotFound(id, path)
        return parseObject(id, r.body)
    }

    override suspend fun searchTracks(query: String, offset: Int, limit: Int): Page<Hit> {
        val o = json("/recording?query=${query.q()}&dismax=true&limit=$limit&offset=$offset")
        return pageOf(o.objects("recordings").map(::recording), offset, o.int("count") ?: 0, limit)
    }

    override suspend fun searchAlbums(query: String, offset: Int, limit: Int): Page<Album> {
        val o = json("/release-group?query=${query.q()}&dismax=true&limit=$limit&offset=$offset")
        return pageOf(o.objects("release-groups").map(::album), offset, o.int("count") ?: 0, limit)
    }

    override suspend fun searchArtists(query: String, offset: Int, limit: Int): Page<Artist> {
        val o = json("/artist?query=${query.q()}&dismax=true&limit=$limit&offset=$offset")
        return pageOf(o.objects("artists").map(::artist), offset, o.int("count") ?: 0, limit)
    }

    override suspend fun find(title: String, artist: String, limit: Int): List<Hit> {
        val q = "recording:\"${title.quoted()}\" AND artist:\"${artist.quoted()}\""
        return json("/recording?query=${q.q()}&limit=$limit").objects("recordings").map(::recording)
    }

    override suspend fun byIsrc(isrc: String): List<Hit> = try {
        val found = json("/isrc/${isrc.q()}?inc=artist-credits").objects("recordings").map { recording(it).copy(isrc = isrc) }
        // That answer has no album: the first recording is asked again for it.
        found.mapIndexed { i, h -> if (i == 0) track(ItemUri.parse(h.track.uri).id).copy(isrc = isrc) else h }
    } catch (_: NotFound) {
        emptyList()
    }

    override suspend fun track(id: String): Hit = recording(json("/recording/${id.q()}?inc=artist-credits+isrcs+releases+release-groups"))

    override suspend fun album(id: String): Album {
        val o = json("/release-group/${id.q()}?inc=artist-credits+releases")
        val edition = bestRelease(o.objects("releases"))?.str("id")?.let { json("/release/${it.q()}?inc=recordings") }
        val count = edition?.objects("media")?.sumOf { it.int("track-count") ?: 0 } ?: 0
        return album(o).copy(trackCount = count, label = null)
    }

    override suspend fun albumTracks(id: String, offset: Int, limit: Int): Page<Hit> {
        val group = json("/release-group/${id.q()}?inc=artist-credits+releases")
        val edition = bestRelease(group.objects("releases"))?.str("id") ?: return Page(emptyList(), offset, 0, null)
        val r = json("/release/${edition.q()}?inc=recordings+artist-credits+isrcs")
        val ref = AlbumRef(uri("album", id), group.str("title").orEmpty(), covers(id))
        val all = r.objects("media").flatMap { it.objects("tracks") }.mapNotNull { t ->
            val rec = t.obj("recording") ?: return@mapNotNull null
            val hit = recording(rec)
            hit.copy(track = hit.track.copy(album = ref, name = t.str("title") ?: hit.track.name))
        }
        return pageOf(all.drop(offset).take(limit), offset, all.size, limit)
    }

    override suspend fun artist(id: String): Artist = artist(json("/artist/${id.q()}"))

    /** MusicBrainz does not know what is popular. */
    override suspend fun topTracks(id: String): List<Hit> = emptyList()

    override suspend fun discography(id: String, offset: Int, limit: Int): Page<Album> {
        val o = json("/release-group?artist=${id.q()}&type=album|single|ep&limit=$limit&offset=$offset")
        val items = o.objects("release-groups").map(::album).sortedByDescending { it.releaseDate.orEmpty() }
        return pageOf(items, offset, o.int("release-group-count") ?: 0, limit)
    }

    override suspend fun related(id: String): List<Artist> {
        val r = labs.get("$labsBase/similar-artists/json?artist_mbids=${id.q()}&algorithm=$SIMILAR_ALGORITHM")
        if (r.status != 200) return emptyList()
        return parseArray(id, r.body).objects().mapNotNull { o ->
            val mbid = o.str("artist_mbid") ?: return@mapNotNull null
            val name = o.str("name") ?: return@mapNotNull null
            Artist(uri("artist", mbid), name, emptyList())
        }
    }

    private fun uri(kind: String, itemId: String) = ItemUri(id, kind, itemId).toString()

    private fun recording(o: JsonObject): Hit {
        val release = bestRelease(o.objects("releases"))
        val group = release?.obj("release-group")
        val albumRef = when {
            group?.str("id") != null -> AlbumRef(uri("album", group.str("id")!!), group.str("title") ?: release.str("title").orEmpty(), covers(group.str("id")!!))
            else -> null
        }
        return Hit(
            Track(
                uri = uri("track", o.str("id").orEmpty()),
                name = o.str("title").orEmpty(),
                durationMs = o.long("length") ?: 0,
                artists = credits(o),
                album = albumRef,
            ),
            o["isrcs"].strings().firstOrNull(),
        )
    }

    private fun album(o: JsonObject): Album {
        val groupId = o.str("id").orEmpty()
        return Album(
            uri = uri("album", groupId),
            name = o.str("title").orEmpty(),
            artists = credits(o),
            images = covers(groupId),
            type = o.str("primary-type")?.lowercase() ?: "album",
            releaseDate = o.str("first-release-date"),
            trackCount = o.objects("releases").firstOrNull()?.int("track-count") ?: 0,
        )
    }

    private fun artist(o: JsonObject) = Artist(uri("artist", o.str("id").orEmpty()), o.str("name").orEmpty(), emptyList())

    private fun credits(o: JsonObject) = o.objects("artist-credit").mapNotNull { c ->
        val a = c.obj("artist") ?: return@mapNotNull null
        ArtistRef(uri("artist", a.str("id").orEmpty()), c.str("name") ?: a.str("name").orEmpty())
    }

    private fun covers(groupId: String) = listOf(250, 500, 1200).map { Image("$coverBase/release-group/$groupId/front-$it", it, it) }

    private companion object {
        /** The model its site offers as the default. */
        const val SIMILAR_ALGORITHM = "session_based_days_7500_session_300_contribution_5_threshold_10_limit_100_filter_True_skip_30"

        fun String.quoted() = replace("\\", "\\\\").replace("\"", "\\\"")

        /** The edition that stands for an album: an official one, the earliest. */
        fun bestRelease(releases: List<JsonObject>): JsonObject? =
            releases.sortedWith(compareBy<JsonObject>({ if (it.str("status") == "Official") 0 else 1 }, { it.str("date") ?: "9999" })).firstOrNull()
    }
}
