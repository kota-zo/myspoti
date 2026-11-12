/* SPDX-License-Identifier: GPL-3.0-only */
package app.myspoti.catalog

import app.myspoti.api.Album
import app.myspoti.api.Artist
import app.myspoti.api.Catalog
import app.myspoti.api.Page
import app.myspoti.api.Search
import app.myspoti.api.SearchResults
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred

/**
 * A platform the user is signed in to, through the app's own contract: its uris are already of this form.
 *
 * A search of every kind ([searchAll]) is asked of the platform once and shared for [sharedMs]: the chain asks for
 * tracks, albums and artists side by side, and the platform answers all three (and its playlists) in one request.
 */
class ApiProvider(
    override val id: String,
    private val search: Search,
    private val catalog: Catalog,
    private val sharedMs: Long = 20_000,
    private val now: () -> Long = System::currentTimeMillis,
) : MetadataProvider {
    private fun uri(kind: String, itemId: String) = ItemUri(id, kind, itemId).toString()

    private class Shared(val at: Long, val answer: CompletableDeferred<SearchResults>)

    private val shared = object : LinkedHashMap<String, Shared>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Shared>?) = size > 8
    }

    /** The platform's own search of every kind, asked once for callers that come together. */
    suspend fun searchAll(query: String, limit: Int): SearchResults {
        val key = "$limit|$query"
        val (mine, entry) = synchronized(shared) {
            val kept = shared[key]?.takeIf { now() - it.at < sharedMs }
            if (kept != null) false to kept else true to Shared(now(), CompletableDeferred<SearchResults>()).also { shared[key] = it }
        }
        if (mine) {
            try {
                entry.answer.complete(search.all(query, limit))
            } catch (e: Throwable) {
                // A failure is not shared beyond the callers already waiting: the next one asks again.
                synchronized(shared) { if (shared[key] === entry) shared.remove(key) }
                // The caller asking stopped waiting: the others are told the platform did not answer, not cancelled.
                entry.answer.completeExceptionally(if (e is CancellationException) ProviderDown(id, "search stopped") else e)
                if (e is CancellationException) throw e
            }
        }
        return entry.answer.await()
    }

    private fun <T> firstPage(p: Page<T>, limit: Int) = Page(p.items.take(limit), 0, p.total, p.nextOffset)

    override suspend fun searchTracks(query: String, offset: Int, limit: Int): Page<Hit> {
        val p = if (offset == 0) firstPage(searchAll(query, limit).tracks, limit) else search.tracks(query, offset, limit)
        return Page(p.items.map { Hit(it) }, p.offset, p.total, p.nextOffset)
    }

    override suspend fun searchAlbums(query: String, offset: Int, limit: Int): Page<Album> =
        if (offset == 0) firstPage(searchAll(query, limit).albums, limit) else search.albums(query, offset, limit)

    override suspend fun searchArtists(query: String, offset: Int, limit: Int): Page<Artist> =
        if (offset == 0) firstPage(searchAll(query, limit).artists, limit) else search.artists(query, offset, limit)

    override suspend fun find(title: String, artist: String, limit: Int): List<Hit> = search.tracks("$artist $title", 0, limit).items.map { Hit(it) }

    /**
     * The platform's search with an `isrc:` filter, each answer checked against the recording's own ISRC: a platform
     * that does not know the filter answers with other songs, and those are left out.
     */
    override suspend fun byIsrc(isrc: String): List<Hit> {
        val code = Match.isrc(isrc) ?: return emptyList()
        return search.tracks("isrc:$code", 0, 3).items.take(3).mapNotNull { t ->
            val own = runCatching { catalog.isrc(t.uri) }.getOrNull()
            if (Match.isrc(own) == code) Hit(t, code) else null
        }
    }

    override suspend fun track(id: String): Hit {
        val u = uri("track", id)
        return Hit(catalog.track(u), catalog.isrc(u))
    }

    override suspend fun album(id: String): Album = catalog.album(uri("album", id))

    override suspend fun albumTracks(id: String, offset: Int, limit: Int): Page<Hit> {
        val p = catalog.albumTracks(uri("album", id), offset, limit)
        return Page(p.items.map { Hit(it) }, p.offset, p.total, p.nextOffset)
    }

    override suspend fun artist(id: String): Artist = catalog.artist(uri("artist", id))
    override suspend fun topTracks(id: String): List<Hit> = catalog.artistTopTracks(uri("artist", id)).map { Hit(it) }
    override suspend fun discography(id: String, offset: Int, limit: Int): Page<Album> = catalog.discography(uri("artist", id), offset, limit)
    override suspend fun related(id: String): List<Artist> = catalog.relatedArtists(uri("artist", id))
}
