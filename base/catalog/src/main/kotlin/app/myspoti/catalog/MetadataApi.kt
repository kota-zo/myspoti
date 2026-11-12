/* SPDX-License-Identifier: GPL-3.0-only */
package app.myspoti.catalog

import app.myspoti.api.Album
import app.myspoti.api.Artist
import app.myspoti.api.ArtistPage
import app.myspoti.api.Catalog
import app.myspoti.api.Episode
import app.myspoti.api.MediaCard
import app.myspoti.api.MyspotiApi
import app.myspoti.api.Page
import app.myspoti.api.Playlist
import app.myspoti.api.Search
import app.myspoti.api.SearchResults
import app.myspoti.api.Track
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * The app's contract with its metadata from the catalog: music searches (tracks, albums, artists) and the items they
 * lead to come from [chain], the providers in the user's order; everything else stays on [base], the signed-in
 * platform's own implementation (library, playlists, home, podcasts, Connect, playback).
 *
 * An item of [base] (its uri scheme is [ownScheme]) is read from [base] when the chain does not have that platform, so
 * what the user's library or home leads to keeps opening; its artist page, the albums it appears on and "more like
 * this" are always [base]'s, which the other providers do not know. [signedIn] is the platform as a provider when it
 * is in the chain: its playlists come from the same request as its tracks.
 */
class MetadataApi(
    private val base: MyspotiApi,
    private val chain: () -> ProviderChain,
    private val signedIn: () -> ApiProvider? = { null },
    private val ownScheme: String = "spotify",
) : MyspotiApi by base {
    override val search: Search = MetadataSearch()
    override val catalog: Catalog = MetadataCatalog()

    private fun <T> none(offset: Int) = Page<T>(emptyList(), offset, 0, null)

    private inner class MetadataSearch : Search by base.search {
        override suspend fun all(query: String, limit: Int): SearchResults = coroutineScope {
            val c = chain()
            val own = signedIn()?.takeIf { c.providerOf(it.id) === it }
            val music = async { c.all(query, limit) }
            // The platform's playlists, from its own search of every kind when it is in the chain.
            val playlists = async {
                runCatching { own?.searchAll(query, limit)?.playlists ?: base.search.playlists(query, 0, limit) }.getOrDefault(none(0))
            }
            music.await().copy(playlists = playlists.await())
        }

        override suspend fun tracks(query: String, offset: Int, limit: Int): Page<Track> = chain().tracks(query, offset, limit)
        override suspend fun artists(query: String, offset: Int, limit: Int): Page<Artist> = chain().artists(query, offset, limit)
        override suspend fun albums(query: String, offset: Int, limit: Int): Page<Album> = chain().albums(query, offset, limit)
        override suspend fun playlists(query: String, offset: Int, limit: Int): Page<Playlist> = base.search.playlists(query, offset, limit)
        override suspend fun shows(query: String, offset: Int, limit: Int): Page<MediaCard> = base.search.shows(query, offset, limit)
        override suspend fun episodes(query: String, offset: Int, limit: Int): Page<Episode> = base.search.episodes(query, offset, limit)
    }

    private inner class MetadataCatalog : Catalog {
        /** [chain]'s when it has the item's provider, else [base]'s. */
        private fun of(uri: String): Catalog = chain().takeIf { it.knows(uri) } ?: base.catalog

        private fun own(uri: String) = uri.startsWith("$ownScheme:")

        override suspend fun track(uri: String): Track = of(uri).track(uri)
        override suspend fun album(uri: String): Album = of(uri).album(uri)
        override suspend fun albumTracks(uri: String, offset: Int, limit: Int): Page<Track> = of(uri).albumTracks(uri, offset, limit)
        override suspend fun artist(uri: String): Artist = of(uri).artist(uri)
        override suspend fun artistTopTracks(uri: String): List<Track> = of(uri).artistTopTracks(uri)
        override suspend fun artistPage(uri: String): ArtistPage = if (own(uri)) base.catalog.artistPage(uri) else of(uri).artistPage(uri)
        override suspend fun isrc(trackUri: String): String? = of(trackUri).isrc(trackUri)
        override suspend fun discography(uri: String, offset: Int, limit: Int): Page<Album> = of(uri).discography(uri, offset, limit)
        override suspend fun relatedArtists(uri: String): List<Artist> = of(uri).relatedArtists(uri)
        override suspend fun appearsOn(uri: String): List<Album> = if (own(uri)) base.catalog.appearsOn(uri) else emptyList()
        override suspend fun similarAlbums(trackUri: String, limit: Int): List<Album> = if (own(trackUri)) base.catalog.similarAlbums(trackUri, limit) else emptyList()
    }
}
