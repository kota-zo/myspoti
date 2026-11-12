/* SPDX-License-Identifier: GPL-3.0-only
 *
 * The browse ids (FEmusic_liked_playlists, VLLM, FEmusic_history) are those InnerTune and Metrolist use
 * (https://github.com/z-huang/InnerTune, GPL-3.0, innertube/.../YouTube.kt).
 */
package app.myspoti.ytmusic

import app.myspoti.api.Album
import app.myspoti.api.Artist
import app.myspoti.api.ArtistPage
import app.myspoti.api.Catalog
import app.myspoti.api.Episode
import app.myspoti.api.MediaCard
import app.myspoti.api.Page
import app.myspoti.api.Playlist
import app.myspoti.api.Search
import app.myspoti.api.SearchResults
import app.myspoti.api.TopTrack
import app.myspoti.api.Track
import app.myspoti.api.Uri
import app.myspoti.api.account.AccountLibrary
import app.myspoti.ytmusic.YtmParse.Item
import kotlinx.serialization.json.JsonObject

/** The account's library on YouTube Music, read on the user's session ([api]): Liked music, its playlists, its history. */
class YtmLibrary(private val api: InnerTube, private val maxPages: Int = 20) : AccountLibrary {
    override val provider: String get() = Ytm.PROVIDER

    /** The songs of a browse page and of its next pages, until [needed] are there (or the list ends). */
    private suspend fun songs(browseId: String, needed: Int): Pair<List<Track>, Boolean> {
        val out = ArrayList<Track>()
        var answer: JsonObject = api.browse(browseId)
        var pages = 1
        while (true) {
            out += YtmParse.rows(answer).filterIsInstance<Item.Song>().map { it.track }
            val token = YtmParse.continuation(answer)
            if (token == null || out.size >= needed || pages >= maxPages) return out to (token != null)
            answer = api.browseMore(token)
            pages++
        }
    }

    private suspend fun page(browseId: String, offset: Int, limit: Int): Page<Track> {
        val (all, more) = songs(browseId, offset + limit)
        val items = all.drop(offset).take(limit)
        val hasNext = all.size > offset + limit || more
        return Page(items, offset, if (more) all.size + 1 else all.size, if (hasNext) offset + items.size else null)
    }

    override suspend fun likedTracks(offset: Int, limit: Int): Page<Track> = page(LIKED_MUSIC, offset, limit)

    override suspend fun playlists(): List<Playlist> =
        YtmParse.cards(api.browse(LIKED_PLAYLISTS)).filterIsInstance<Item.PlaylistItem>().map { it.playlist }

    override suspend fun playlistTracks(uri: Uri, offset: Int, limit: Int): Page<Track> = page("VL" + idOf(uri, "playlist"), offset, limit)

    override suspend fun history(limit: Int): List<Track> =
        YtmParse.rows(api.browse(HISTORY)).filterIsInstance<Item.Song>().map { it.track }.distinctBy { it.uri }.take(limit)

    companion object {
        const val LIKED_MUSIC = "VLLM"
        const val LIKED_PLAYLISTS = "FEmusic_liked_playlists"
        const val HISTORY = "FEmusic_history"

        /** The id of a `ytmusic:{kind}:{id}` uri (or the bare id). */
        fun idOf(uri: Uri, kind: String): String {
            val prefix = "${Ytm.PROVIDER}:$kind:"
            require(!uri.contains(':') || uri.startsWith(prefix)) { "not a YouTube Music $kind: $uri" }
            return uri.removePrefix(prefix)
        }
    }
}

/**
 * YouTube Music's metadata on the user's session, through the app's contract (the catalog's
 * [app.myspoti.catalog.ApiProvider] wraps it): searches, songs, albums and artists. YouTube Music
 * gives no ISRC; kinds it does not have (shows, audiobooks, users, genres) answer empty. One page per
 * search: a later page is empty.
 */
class YtmMetadata(private val api: InnerTube) : Search, Catalog {
    private fun <T> one(items: List<T>, offset: Int, limit: Int) =
        if (offset > 0) Page(emptyList<T>(), offset, items.size, null) else Page(items.take(limit), 0, items.size, null)

    private suspend fun found(query: String) = YtmParse.search(api.search(query))

    override suspend fun all(query: String, limit: Int): SearchResults {
        val items = found(query)
        return SearchResults(
            one(items.filterIsInstance<Item.Song>().map { it.track }, 0, limit),
            one(items.filterIsInstance<Item.ArtistItem>().map { it.artist }, 0, limit),
            one(items.filterIsInstance<Item.AlbumItem>().map { it.album }, 0, limit),
            one(items.filterIsInstance<Item.PlaylistItem>().map { it.playlist }, 0, limit),
        )
    }

    override suspend fun tracks(query: String, offset: Int, limit: Int) = one(if (offset > 0) emptyList() else found(query).filterIsInstance<Item.Song>().map { it.track }, offset, limit)
    override suspend fun artists(query: String, offset: Int, limit: Int) = one(if (offset > 0) emptyList() else found(query).filterIsInstance<Item.ArtistItem>().map { it.artist }, offset, limit)
    override suspend fun albums(query: String, offset: Int, limit: Int) = one(if (offset > 0) emptyList() else found(query).filterIsInstance<Item.AlbumItem>().map { it.album }, offset, limit)
    override suspend fun playlists(query: String, offset: Int, limit: Int) = one(if (offset > 0) emptyList() else found(query).filterIsInstance<Item.PlaylistItem>().map { it.playlist }, offset, limit)
    override suspend fun shows(query: String, offset: Int, limit: Int) = Page(emptyList<MediaCard>(), offset, 0, null)
    override suspend fun episodes(query: String, offset: Int, limit: Int) = Page(emptyList<Episode>(), offset, 0, null)
    override suspend fun audiobooks(query: String, offset: Int, limit: Int) = Page(emptyList<MediaCard>(), offset, 0, null)
    override suspend fun users(query: String, offset: Int, limit: Int) = Page(emptyList<MediaCard>(), offset, 0, null)
    override suspend fun genres(query: String, offset: Int, limit: Int) = Page(emptyList<MediaCard>(), offset, 0, null)
    override suspend fun suggestions(query: String, limit: Int): List<String> = emptyList()
    override suspend fun recent(): List<MediaCard> = emptyList()
    override suspend fun clearRecent() {}

    override suspend fun track(uri: Uri): Track {
        val id = YtmLibrary.idOf(uri, "track")
        return YtmParse.watchNextTrack(id, api.next(id)) ?: throw NoSuchElementException("${Ytm.PROVIDER} has no track $id")
    }

    private suspend fun albumPage(uri: Uri) = YtmLibrary.idOf(uri, "album").let { YtmParse.album(it, api.browse(it)) }
    private suspend fun artistOf(uri: Uri) = YtmLibrary.idOf(uri, "artist").let { YtmParse.artist(it, api.browse(it)) }

    override suspend fun album(uri: Uri): Album = albumPage(uri).first
    override suspend fun albumTracks(uri: Uri, offset: Int, limit: Int): Page<Track> {
        val songs = albumPage(uri).second
        return Page(songs.drop(offset).take(limit), offset, songs.size, (offset + limit).takeIf { it < songs.size })
    }
    override suspend fun artist(uri: Uri): Artist = artistOf(uri).artist
    override suspend fun artistTopTracks(uri: Uri): List<Track> = artistOf(uri).songs
    override suspend fun artistPage(uri: Uri): ArtistPage {
        val p = artistOf(uri)
        return ArtistPage(p.artist, p.about, p.artist.images, p.songs.map { TopTrack(it, null) }, p.albums, emptyList(), p.related)
    }
    override suspend fun isrc(trackUri: Uri): String? = null
    override suspend fun discography(uri: Uri, offset: Int, limit: Int): Page<Album> {
        val albums = artistOf(uri).albums
        return Page(albums.drop(offset).take(limit), offset, albums.size, (offset + limit).takeIf { it < albums.size })
    }
    override suspend fun relatedArtists(uri: Uri): List<Artist> = artistOf(uri).related
    override suspend fun appearsOn(uri: Uri): List<Album> = emptyList()
    override suspend fun similarAlbums(trackUri: Uri, limit: Int): List<Album> = emptyList()
}
