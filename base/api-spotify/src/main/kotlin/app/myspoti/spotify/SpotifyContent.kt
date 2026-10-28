package app.myspoti.spotify

import app.myspoti.api.Album
import app.myspoti.api.AlbumRef
import app.myspoti.api.Artist
import app.myspoti.api.Catalog
import app.myspoti.api.Episode
import app.myspoti.api.MediaCard
import app.myspoti.api.Page
import app.myspoti.api.Playlist
import app.myspoti.api.Search
import app.myspoti.api.SearchResults
import app.myspoti.api.Track
import app.myspoti.api.Uri
import app.myspoti.spotify.gql.Gql
import app.myspoti.spotify.gql.GqlException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Builds GraphQL variables from plain Kotlin values (maps and lists nest). */
internal fun vars(vararg pairs: Pair<String, Any?>): JsonObject = JsonObject(pairs.associate { (k, v) -> k to json(v) })

internal fun json(v: Any?): JsonElement = when (v) {
    null -> JsonNull
    is JsonElement -> v
    is String -> JsonPrimitive(v)
    is Number -> JsonPrimitive(v)
    is Boolean -> JsonPrimitive(v)
    is Map<*, *> -> JsonObject(v.entries.associate { (k, x) -> k.toString() to json(x) })
    is List<*> -> JsonArray(v.map(::json))
    else -> error("unsupported value $v")
}

/** The entity at [path], or an error when the server says it does not exist. */
internal fun JsonElement.entity(path: String, op: String): JsonElement {
    val e = at(path) ?: throw GqlException(op, "no $path")
    if (e.typename() == "NotFound") throw GqlException(op, "not found")
    return e
}

class SpotifySearch(private val gql: Gql) : Search {
    private fun tabVars(query: String, offset: Int, limit: Int) = vars(
        "searchTerm" to query, "offset" to offset, "limit" to limit, "numberOfTopResults" to limit,
        "includeAudiobooks" to true, "includeAuthors" to true, "includePreReleases" to false,
        "includeAlbumPreReleases" to false, "includeEpisodeContentRatingsV2" to true,
    )

    override suspend fun tracks(query: String, offset: Int, limit: Int): Page<Track> =
        page(gql.query("searchTracks", tabVars(query, offset, limit)).at("data.searchV2.tracksV2"), offset) { track(it.at("item.data")) }

    override suspend fun artists(query: String, offset: Int, limit: Int): Page<Artist> =
        page(gql.query("searchArtists", tabVars(query, offset, limit)).at("data.searchV2.artists"), offset) { artist(it.at("data")) }

    override suspend fun albums(query: String, offset: Int, limit: Int): Page<Album> =
        page(gql.query("searchAlbums", tabVars(query, offset, limit)).at("data.searchV2.albumsV2"), offset) { album(it.at("data")) }

    override suspend fun playlists(query: String, offset: Int, limit: Int): Page<Playlist> =
        page(gql.query("searchPlaylists", tabVars(query, offset, limit)).at("data.searchV2.playlists"), offset) { playlist(it.at("data")) }

    override suspend fun shows(query: String, offset: Int, limit: Int): Page<MediaCard> =
        page(gql.query("searchPodcasts", tabVars(query, offset, limit)).at("data.searchV2.podcasts"), offset, ::card)

    override suspend fun episodes(query: String, offset: Int, limit: Int): Page<Episode> =
        page(gql.query("searchFullEpisodes", vars(
            "searchTerm" to query, "offset" to offset, "limit" to limit, "includeEpisodeContentRatingsV2" to true,
        )).at("data.searchV2.episodes"), offset) { episode(it.at("data")) }

    override suspend fun audiobooks(query: String, offset: Int, limit: Int): Page<MediaCard> =
        page(gql.query("searchAudiobooks", tabVars(query, offset, limit)).at("data.searchV2.audiobooks"), offset, ::card)

    override suspend fun users(query: String, offset: Int, limit: Int): Page<MediaCard> =
        page(gql.query("searchUsers", tabVars(query, offset, limit)).at("data.searchV2.users"), offset, ::card)

    override suspend fun genres(query: String, offset: Int, limit: Int): Page<MediaCard> =
        page(gql.query("searchGenres", tabVars(query, offset, limit)).at("data.searchV2.genres"), offset, ::card)

    /** The four music tabs in parallel: one round trip of wall time. */
    override suspend fun all(query: String, limit: Int): SearchResults = coroutineScope {
        val t = async { tracks(query, 0, limit) }
        val ar = async { artists(query, 0, limit) }
        val al = async { albums(query, 0, limit) }
        val p = async { playlists(query, 0, limit) }
        SearchResults(t.await(), ar.await(), al.await(), p.await())
    }

    override suspend fun suggestions(query: String, limit: Int): List<String> {
        val r = gql.query("searchSuggestions", vars(
            "query" to query, "limit" to limit, "numberOfTopResults" to limit, "offset" to 0,
            "includeAuthors" to true, "includeAlbumPreReleases" to false, "includeEpisodeContentRatingsV2" to true,
        ))
        return r.list("data.searchV2.topResultsV2.itemsV2").mapNotNull { hit ->
            val item = hit.at("item")
            if (item.typename() == "SearchAutoCompleteEntity") item.str("text") ?: item.str("data.text") else null
        }
    }

    override suspend fun recent(): List<MediaCard> =
        gql.query("recentSearches", vars("limit" to 50, "includeAuthors" to true, "includeEpisodeContentRatingsV2" to true))
            .list("data.recentSearches.recentSearchesItems.items").mapNotNull(::card)

    override suspend fun clearRecent() {
        val uris = recent().map { it.uri }
        if (uris.isNotEmpty()) gql.query("removeRecentSearches", vars("uris" to uris))
    }
}

class SpotifyCatalog(private val gql: Gql, private val http: app.myspoti.spotify.gql.SpotifyHttp? = null) : Catalog {
    /** spclient metadata/4 knows the ISRC; the public Web API is rate-limited for web tokens. */
    override suspend fun isrc(trackUri: Uri): String? {
        val client = http ?: return null
        val hex = base62ToHex(trackUri.substringAfterLast(':'))
        val meta = client.getJson("https://spclient.wg.spotify.com/metadata/4/track/$hex?market=from_token") ?: return null
        return meta.list("external_id").firstOrNull { it.str("type") == "isrc" }?.str("id")
    }

    override suspend fun track(uri: Uri): Track =
        track(gql.query("getTrack", vars("uri" to uri, "includeVideoAssociationItems" to false)).entity("data.trackUnion", "getTrack"))
            ?: throw GqlException("getTrack", "bad track")

    private suspend fun albumUnion(uri: Uri, offset: Int, limit: Int) =
        gql.query("getAlbum", vars("uri" to uri, "locale" to "", "offset" to offset, "limit" to limit)).entity("data.albumUnion", "getAlbum")

    override suspend fun album(uri: Uri): Album =
        album(albumUnion(uri, 0, 1)) ?: throw GqlException("getAlbum", "bad album")

    override suspend fun albumTracks(uri: Uri, offset: Int, limit: Int): Page<Track> {
        val a = albumUnion(uri, offset, limit)
        val cover = album(a)
        return page(a.at("tracksV2"), offset) { row ->
            track(row.at("track"), row.str("uid"))?.let { t ->
                t.copy(album = t.album ?: cover?.let { AlbumRef(it.uri, it.name, it.images) })
            }
        }
    }

    private suspend fun artistUnion(uri: Uri) =
        gql.query("queryArtistOverview", vars("uri" to uri, "locale" to "", "preReleaseV2" to false)).entity("data.artistUnion", "queryArtistOverview")

    override suspend fun artist(uri: Uri): Artist {
        val a = artistUnion(uri)
        return artist(a)?.copy(verified = a.bool("onPlatformReputationTrait.verification.isVerified") ?: false)
            ?: throw GqlException("queryArtistOverview", "bad artist")
    }

    override suspend fun artistTopTracks(uri: Uri): List<Track> =
        artistUnion(uri).list("discography.topTracks.items").mapNotNull { track(it.at("track"), it.str("uid")) }

    override suspend fun artistPage(uri: Uri): app.myspoti.api.ArtistPage {
        val a = artistUnion(uri)
        val base = artist(a)?.copy(verified = a.bool("onPlatformReputationTrait.verification.isVerified") ?: false)
            ?: throw GqlException("queryArtistOverview", "bad artist")
        return app.myspoti.api.ArtistPage(
            artist = base,
            biography = a.str("profile.biography.text")?.let { stripHtml(it) },
            headerImages = images(a.list("headerImage.data.sources")),
            topTracks = a.list("discography.topTracks.items").mapNotNull { row ->
                track(row.at("track"), row.str("uid"))?.let { app.myspoti.api.TopTrack(it, row.long("track.playcount")) }
            },
            popularReleases = a.list("discography.popularReleasesAlbums.items").mapNotNull { album(it) },
            appearsOn = a.list("relatedContent.appearsOn.items").mapNotNull(::release),
            related = a.list("relatedContent.relatedArtists.items").mapNotNull(::artist),
        )
    }

    override suspend fun discography(uri: Uri, offset: Int, limit: Int): Page<Album> {
        val all = gql.query("queryArtistDiscographyAll", vars("uri" to uri, "offset" to offset, "limit" to limit, "order" to "DATE_DESC"))
            .entity("data.artistUnion", "queryArtistDiscographyAll").at("discography.all")
        return page(all, offset, ::release)
    }

    override suspend fun relatedArtists(uri: Uri): List<Artist> =
        gql.query("queryArtistRelated", vars("uri" to uri)).entity("data.artistUnion", "queryArtistRelated")
            .list("relatedContent.relatedArtists.items").mapNotNull(::artist)

    override suspend fun appearsOn(uri: Uri): List<Album> =
        gql.query("queryArtistAppearsOn", vars("uri" to uri)).entity("data.artistUnion", "queryArtistAppearsOn")
            .list("relatedContent.appearsOn.items").mapNotNull(::release)

    override suspend fun similarAlbums(trackUri: Uri, limit: Int): List<Album> =
        gql.query("similarAlbumsBasedOnThisTrack", vars("uri" to trackUri, "limit" to limit, "albumsOnly" to true))
            .list("data.seoRecommendedTrackAlbum.items").mapNotNull { album(it.at("data")) }
}

private const val BASE62 = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"

/** Spotify's base62 id -> the 32-char hex gid used by metadata/4. */
internal fun base62ToHex(id: String): String {
    var n = java.math.BigInteger.ZERO
    val radix = java.math.BigInteger.valueOf(62)
    for (c in id) n = n.multiply(radix).add(java.math.BigInteger.valueOf(BASE62.indexOf(c).toLong()))
    return n.toString(16).padStart(32, '0')
}
