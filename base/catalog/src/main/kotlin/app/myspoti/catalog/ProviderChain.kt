/* SPDX-License-Identifier: GPL-3.0-only */
package app.myspoti.catalog

import app.myspoti.api.Album
import app.myspoti.api.Artist
import app.myspoti.api.ArtistPage
import app.myspoti.api.Catalog
import app.myspoti.api.MediaCard
import app.myspoti.api.Page
import app.myspoti.api.Playlist
import app.myspoti.api.Search
import app.myspoti.api.SearchResults
import app.myspoti.api.TopTrack
import app.myspoti.api.Track
import app.myspoti.api.Episode
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException

/**
 * Several providers as one, in the order given. A search asks them all and merges their answers, the same recording
 * once. An item goes back to the provider its uri names. What that provider lacks (an artist's picture, what is
 * popular) comes from another, on the same name only. A provider that keeps failing is left alone for a while, and
 * one failing never takes the others' answers with it: only all of them failing is an error.
 */
class ProviderChain(
    private val providers: List<MetadataProvider>,
    private val timeoutMs: Long = 8_000,
    /** Once the first provider of the order has answered, how long the others are still waited for. */
    private val graceMs: Long = 300,
    private val restMs: Long = 60_000,
    private val failuresToRest: Int = 3,
    private val now: () -> Long = System::currentTimeMillis,
) : Catalog, Search {
    private class Health { var failures = 0; var restUntil = 0L }

    private val health = providers.associate { it.id to Health() }

    /** The providers asked, in order. */
    val ids: List<String> get() = providers.map { it.id }

    /** The provider of this id in the chain, if it is in it. */
    fun providerOf(id: String): MetadataProvider? = providers.firstOrNull { it.id == id }

    /** Whether an item of [uri] goes to a provider of this chain. */
    fun knows(uri: String): Boolean = runCatching { ItemUri.parse(uri).provider }.getOrNull()?.let { s -> providers.any { it.id == s } } == true

    private fun available() = providers.filter { p -> now() >= health.getValue(p.id).restUntil }.ifEmpty { providers }

    private fun provider(uri: String): Pair<MetadataProvider, String> {
        val u = ItemUri.parse(uri)
        val p = providers.firstOrNull { it.id == u.provider } ?: throw NotFound(u.provider, uri)
        return p to u.id
    }

    private suspend fun <T> guarded(p: MetadataProvider, call: suspend () -> T): T {
        val h = health.getValue(p.id)
        try {
            val r = call()
            synchronized(h) { h.failures = 0; h.restUntil = 0 }
            return r
        } catch (e: IOException) {
            synchronized(h) {
                h.failures++
                if (h.failures >= failuresToRest) h.restUntil = now() + restMs
            }
            throw e
        }
    }

    /**
     * The available providers' answers, in order; what failed is left out, unless nothing answered.
     * All are asked at once. For a search ([quick]) the first of the order to answer leads: the
     * others get [graceMs] more, then what they would have added is left out of this answer. Waiting for every one
     * showed a search only when the slowest provider had answered (up to its time limit, 8 s)
     * while the account's own answer was there in half a second (reported: search slow to load).
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private suspend fun <T> fromAll(quick: Boolean = false, call: suspend (MetadataProvider) -> T): List<T> = coroutineScope {
        val asked = available()
        val failures = arrayOfNulls<Throwable>(asked.size)
        val answers = asked.mapIndexed { i, p ->
            async {
                try {
                    withTimeoutOrNull(timeoutMs) { guarded(p) { call(p) } }
                        ?: run { failures[i] = ProviderDown(p.id, "timed out"); null }
                } catch (e: IOException) {
                    failures[i] = e
                    null
                } catch (_: NotFound) {
                    null
                }
            }
        }
        // A search ([quick]) shows on the first answer of the order; a look-up of one song across
        // the providers (an ISRC, a match) waits for every one, as its answer may be the slow one's.
        if (quick) {
            var lead = 0
            while (lead < answers.size && answers[lead].await() == null) lead++
            if (lead < answers.size) withTimeoutOrNull(graceMs) { answers.awaitAll() }
        } else answers.awaitAll()
        val got = answers.mapNotNull { a -> if (a.isCompleted) a.getCompleted() else { a.cancel(); null } }
        if (got.isEmpty()) failures.firstOrNull { it != null }?.takeIf { failures.count { f -> f != null } == asked.size }?.let { throw it }
        got
    }

    private fun <T> merged(pages: List<Page<T>>, offset: Int, limit: Int, same: (T, T) -> Boolean): Page<T> {
        val out = ArrayList<T>()
        for (page in pages) for (item in page.items) if (out.none { same(it, item) }) out += item
        val total = pages.maxOfOrNull { it.total } ?: 0
        return Page(out.take(limit), offset, maxOf(total, out.size), if (pages.any { it.nextOffset != null }) offset + limit else null)
    }

    /** Tracks with their ISRCs, the same recording once. */
    suspend fun searchHits(query: String, offset: Int = 0, limit: Int = 30): Page<Hit> =
        merged(fromAll(quick = true) { it.searchTracks(query, offset, limit) }, offset, limit, Match::same)

    /**
     * The recording described, on whichever provider knows it best. Its ISRC decides when there is one, then a
     * search whose best answer must clear [Match.SURE]: no match is better than the wrong song.
     */
    suspend fun resolve(title: String, artist: String, durationMs: Long = 0, isrc: String? = null): Hit? {
        Match.isrc(isrc)?.let { code ->
            fromAll { it.byIsrc(code) }.flatten().filterNot { Match.preview(durationMs, it.track.durationMs) }.closestTo(durationMs)?.let { return it }
        }
        return best(title, artist, durationMs) { p, query -> p.find(query, artist) }
    }

    /** The same recording on every other provider that has it, by provider. What cannot be told surely is left out. */
    suspend fun counterparts(hit: Hit): Map<String, Hit> {
        val own = ItemUri.parse(hit.track.uri).provider
        val title = hit.track.name
        val artist = hit.track.artists.firstOrNull()?.name.orEmpty()
        val ms = hit.track.durationMs
        val code = Match.isrc(hit.isrc)
        return fromAll { p ->
            if (p.id == own) return@fromAll p.id to null
            val exact = code?.let { c -> p.byIsrc(c).filterNot { Match.preview(ms, it.track.durationMs) }.closestTo(ms) }
            p.id to (exact ?: bestOf(p, title, artist, ms))
        }.mapNotNull { (id, h) -> h?.let { id to it } }.toMap()
    }

    /**
     * The recording described, on the provider [id] only: by its [isrc] when that provider can tell, else a search whose
     * best answer must clear [Match.SURE]. Null when it is not in the chain, does not have it, or cannot answer now
     * ([counterpartAnswer] tells those apart).
     */
    suspend fun counterpartOn(id: String, title: String, artist: String, durationMs: Long = 0, isrc: String? = null): Hit? =
        (counterpartAnswer(id, title, artist, durationMs, isrc) as? Counterpart.Found)?.hit

    /**
     * [counterpartOn], with why there is none: [Counterpart.Absent] only when the provider answered and has no such
     * recording; a provider resting after failures, slow, refusing or failing is [Counterpart.Unknown], never taken for
     * an absence (reported: "not found on Spotify" for a song that played when tapped again).
     */
    suspend fun counterpartAnswer(id: String, title: String, artist: String, durationMs: Long = 0, isrc: String? = null): Counterpart {
        val p = providerOf(id) ?: return Counterpart.Unknown("$id is not asked")
        if (now() < health.getValue(p.id).restUntil) return Counterpart.Unknown("$id rests after failures")
        return try {
            val answer = withTimeoutOrNull(timeoutMs * 2) {
                Counterpart.Answered(guarded(p) {
                    Match.isrc(isrc)?.let { code -> p.byIsrc(code).filterNot { Match.preview(durationMs, it.track.durationMs) }.closestTo(durationMs) }
                        ?: bestOf(p, title, artist, durationMs)
                })
            } ?: return Counterpart.Unknown("$id timed out")
            answer.hit?.let { Counterpart.Found(it) } ?: Counterpart.Absent
        } catch (_: NotFound) {
            Counterpart.Absent
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Counterpart.Unknown("$id: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** First as written, then without what is in brackets, for services that search on every word. */
    private fun queries(title: String) = listOf(title, Match.stripped(title)).distinct()

    private suspend fun bestOf(p: MetadataProvider, title: String, artist: String, durationMs: Long): Hit? {
        for (query in queries(title)) {
            p.find(query, artist).map { it to Match.score(title, artist, durationMs, it.track) }
                .filter { it.second >= Match.SURE }.maxByOrNull { it.second }?.let { return it.first }
        }
        return null
    }

    private suspend fun best(title: String, artist: String, durationMs: Long, find: suspend (MetadataProvider, String) -> List<Hit>): Hit? {
        for (query in queries(title)) {
            fromAll { find(it, query) }.flatten()
                .map { it to Match.score(title, artist, durationMs, it.track) }
                .filter { it.second >= Match.SURE }
                .maxByOrNull { it.second }?.let { return it.first }
        }
        return null
    }

    private fun List<Hit>.closestTo(durationMs: Long): Hit? =
        if (durationMs <= 0) firstOrNull() else minByOrNull { if (it.track.durationMs > 0) kotlin.math.abs(it.track.durationMs - durationMs) else Long.MAX_VALUE }

    // ── Search ──

    override suspend fun all(query: String, limit: Int): SearchResults = coroutineScope {
        val t = async { tracks(query, 0, limit) }
        val ar = async { artists(query, 0, limit) }
        val al = async { albums(query, 0, limit) }
        SearchResults(t.await(), ar.await(), al.await(), none(0))
    }

    override suspend fun tracks(query: String, offset: Int, limit: Int): Page<Track> {
        val p = searchHits(query, offset, limit)
        return Page(p.items.map { it.track }, p.offset, p.total, p.nextOffset)
    }

    override suspend fun artists(query: String, offset: Int, limit: Int): Page<Artist> =
        merged(fromAll(quick = true) { it.searchArtists(query, offset, limit) }, offset, limit) { a, b -> Match.name(a.name) == Match.name(b.name) }

    override suspend fun albums(query: String, offset: Int, limit: Int): Page<Album> =
        merged(fromAll(quick = true) { it.searchAlbums(query, offset, limit) }, offset, limit) { a, b ->
            Match.title(a.name) == Match.title(b.name) && Match.name(a.artists.firstOrNull()?.name.orEmpty()) == Match.name(b.artists.firstOrNull()?.name.orEmpty())
        }

    private fun <T> none(offset: Int) = Page<T>(emptyList(), offset, 0, null)

    override suspend fun playlists(query: String, offset: Int, limit: Int): Page<Playlist> = none(offset)
    override suspend fun shows(query: String, offset: Int, limit: Int): Page<MediaCard> = none(offset)
    override suspend fun episodes(query: String, offset: Int, limit: Int): Page<Episode> = none(offset)
    override suspend fun audiobooks(query: String, offset: Int, limit: Int): Page<MediaCard> = none(offset)
    override suspend fun users(query: String, offset: Int, limit: Int): Page<MediaCard> = none(offset)
    override suspend fun genres(query: String, offset: Int, limit: Int): Page<MediaCard> = none(offset)
    override suspend fun suggestions(query: String, limit: Int): List<String> = emptyList()
    override suspend fun recent(): List<MediaCard> = emptyList()
    override suspend fun clearRecent() = Unit

    // ── Catalog ──

    override suspend fun track(uri: Uri): Track = provider(uri).let { (p, id) -> guarded(p) { p.track(id).track } }
    override suspend fun album(uri: Uri): Album = provider(uri).let { (p, id) -> guarded(p) { p.album(id) } }

    override suspend fun albumTracks(uri: Uri, offset: Int, limit: Int): Page<Track> {
        val (p, id) = provider(uri)
        val page = guarded(p) { p.albumTracks(id, offset, limit) }
        return Page(page.items.map { it.track }, page.offset, page.total, page.nextOffset)
    }

    /** With a picture from another provider when its own has none. */
    override suspend fun artist(uri: Uri): Artist {
        val (p, id) = provider(uri)
        val a = guarded(p) { p.artist(id) }
        if (a.images.isNotEmpty()) return a
        return a.copy(images = sameArtistElsewhere(p, a.name)?.images.orEmpty())
    }

    private suspend fun sameArtistElsewhere(not: MetadataProvider, name: String): Artist? {
        val wanted = Match.name(name)
        for (other in available()) {
            if (other === not) continue
            val found = runCatching { withTimeoutOrNull(timeoutMs) { guarded(other) { other.searchArtists(name, 0, 5) } } }.getOrNull()
            found?.items?.firstOrNull { Match.name(it.name) == wanted }?.let { return it }
        }
        return null
    }

    /** From another provider, on the same name, when its own does not know what is popular. */
    override suspend fun artistTopTracks(uri: Uri): List<Track> {
        val (p, id) = provider(uri)
        val own = guarded(p) { p.topTracks(id) }
        if (own.isNotEmpty()) return own.map { it.track }
        val a = guarded(p) { p.artist(id) }
        val other = sameArtistElsewhere(p, a.name) ?: return emptyList()
        val (q, otherId) = provider(other.uri)
        return runCatching { guarded(q) { q.topTracks(otherId) } }.getOrDefault(emptyList()).map { it.track }
    }

    override suspend fun artistPage(uri: Uri): ArtistPage = coroutineScope {
        val artist = async { artist(uri) }
        val top = async { runCatching { artistTopTracks(uri) }.getOrDefault(emptyList()) }
        val albums = async { runCatching { discography(uri, 0, 12).items }.getOrDefault(emptyList()) }
        val related = async { runCatching { relatedArtists(uri) }.getOrDefault(emptyList()) }
        val a = artist.await()
        ArtistPage(a, null, a.images, top.await().map { TopTrack(it, null) }, albums.await(), emptyList(), related.await())
    }

    override suspend fun isrc(trackUri: Uri): String? = provider(trackUri).let { (p, id) -> guarded(p) { p.track(id).isrc } }
    override suspend fun discography(uri: Uri, offset: Int, limit: Int): Page<Album> = provider(uri).let { (p, id) -> guarded(p) { p.discography(id, offset, limit) } }
    override suspend fun relatedArtists(uri: Uri): List<Artist> = provider(uri).let { (p, id) -> guarded(p) { p.related(id) } }
    override suspend fun appearsOn(uri: Uri): List<Album> = emptyList()
    override suspend fun similarAlbums(trackUri: Uri, limit: Int): List<Album> = emptyList()
}

private typealias Uri = String

/** What [ProviderChain.counterpartAnswer] found on one provider. */
sealed class Counterpart {
    data class Found(val hit: Hit) : Counterpart()
    /** The provider answered: it has no such recording. */
    data object Absent : Counterpart()
    /** The provider could not answer now ([why]): asked again later, never kept as an absence. */
    data class Unknown(val why: String) : Counterpart()
    internal class Answered(val hit: Hit?)
}
