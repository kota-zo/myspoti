/* SPDX-License-Identifier: GPL-3.0-only */
package app.myspoti.catalog

import app.myspoti.api.Album
import app.myspoti.api.AlbumRef
import app.myspoti.api.Artist
import app.myspoti.api.ArtistRef
import app.myspoti.api.Image
import app.myspoti.api.Page
import app.myspoti.api.Track
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** A metadata script bundled with the app: which one, where it may go and what it calls its search kinds. */
data class ScriptSpec(
    val id: String,
    val hosts: Set<String>,
    val tracks: String,
    val albums: String,
    val artists: String,
) {
    companion object {
        val DEEZER = ScriptSpec("deezer", setOf("api.deezer.com", "www.deezer.com", "link.deezer.com"), "track", "album", "artist")
        val TIDAL = ScriptSpec("tidal", setOf("tidal.com", "resources.tidal.com"), "track", "album", "artist")
        val YOUTUBE_MUSIC = ScriptSpec("ytmusic", setOf("music.youtube.com", "www.youtube.com", "i.ytimg.com"), "tracks", "albums", "artists")
    }
}

/**
 * A provider whose knowledge lives in a script, so a service that changes is mended without a new version of the app.
 * What it answers is read by field name, the same for every script.
 *
 * [fallback]: the bundled script, when [source] is a downloaded one. A downloaded script that fails by itself (an
 * error, an answer that is not one) is set aside for the bundled one until the app restarts, and the call is asked
 * again of the bundled one; one that only ran too long gives that one call to the bundled script. When the bundled
 * script fails too, the provider is down and the chain moves on.
 */
class ScriptProvider(
    private val spec: ScriptSpec,
    private val http: ScriptHttp,
    private val userAgent: String,
    source: String = bundledScript(spec.id),
    settings: Map<String, String> = emptyMap(),
    private val fallback: String? = null,
    /** How long one run of the script may last. */
    private val runMs: Long = 25_000,
) : MetadataProvider {
    override val id = spec.id

    /** One script and whether it was started. Used on [thread] only. */
    private inner class Runner(source: String) {
        val engine = ScriptEngine(spec.id, source, spec.hosts, http, userAgent, runMs)
        var started = false

        fun run(function: String, args: Array<out JsonElement>): JsonElement? {
            if (!started) {
                engine.call("initialize", startup)
                started = true
            }
            return engine.call(function, *args)
        }
    }

    private val startup = JsonObject(settings.mapValues { JsonPrimitive(it.value) })
    private val primary = Runner(source)
    private val bundled by lazy { fallback?.let { Runner(it) } }
    private val thread = Executors.newSingleThreadExecutor { r -> Thread(r, "metadata-${spec.id}").apply { isDaemon = true } }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + thread)

    /** Why the downloaded script was set aside for the bundled one; null while it is used, or with no downloaded script. */
    @Volatile var setAside: String? = null
        private set

    /** Whether the script answering is a downloaded one. */
    val downloaded: Boolean get() = fallback != null && setAside == null

    /** The last run could not reach the service (of whichever script ran it). */
    @Volatile private var networkFailed = false

    /** Not `withContext`: a caller that stops waiting must not wait for a script in the middle of a request. */
    private suspend fun ask(function: String, what: String, vararg args: JsonElement): JsonElement {
        return scope.async {
            val out = try {
                (if (setAside == null) primary else bundled!!).let { r -> r.run(function, args).also { networkFailed = r.engine.networkFailed } }
            } catch (e: ScriptFailed) {
                val other = bundled?.takeIf { setAside == null } ?: throw e
                if (e.definite) setAside = e.message ?: "failed"
                other.run(function, args).also { networkFailed = other.engine.networkFailed }
            }
            out ?: if (networkFailed) throw ProviderDown(id, "$what: no answer") else throw NotFound(id, what)
        }.await()
    }

    private suspend fun search(query: String, filter: String, limit: Int): List<JsonObject> {
        val options = JsonObject(mapOf("limit" to JsonPrimitive(limit), "filter" to JsonPrimitive(filter)))
        val out = ask("customSearch", "search", JsonPrimitive(query), options) as? JsonArray
        if ((out == null || out.isEmpty()) && networkFailed) throw ProviderDown(id, "search: no answer")
        return out.objects()
    }

    /** The scripts search from the top only. */
    private fun <T> onePage(items: List<T>, offset: Int) = Page(if (offset == 0) items else emptyList(), offset, items.size, null)

    private fun kindOf(o: JsonObject, kind: String) = o.str("item_type")?.let { it == kind } ?: true

    override suspend fun searchTracks(query: String, offset: Int, limit: Int): Page<Hit> =
        onePage(search(query, spec.tracks, limit).filter { kindOf(it, "track") }.mapNotNull(::hit), offset)

    override suspend fun searchAlbums(query: String, offset: Int, limit: Int): Page<Album> =
        onePage(search(query, spec.albums, limit).filter { kindOf(it, "album") }.mapNotNull(::album), offset)

    override suspend fun searchArtists(query: String, offset: Int, limit: Int): Page<Artist> =
        onePage(search(query, spec.artists, limit).filter { kindOf(it, "artist") }.mapNotNull(::artist), offset)

    override suspend fun find(title: String, artist: String, limit: Int): List<Hit> = searchTracks("$artist $title", 0, limit).items

    override suspend fun byIsrc(isrc: String): List<Hit> = try {
        val asked = JsonObject(mapOf("isrc" to JsonPrimitive(isrc)))
        val o = ask("enrichTrack", "isrc $isrc", asked) as? JsonObject
        // A script that cannot tell gives back what it was given.
        listOfNotNull(o?.let(::hit)).filter { Match.isrc(it.isrc) == Match.isrc(isrc) }
    } catch (_: NotFound) {
        emptyList()
    }

    override suspend fun track(id: String): Hit =
        (ask("getTrack", "track $id", JsonPrimitive(id)) as? JsonObject)?.let(::hit) ?: throw NotFound(this.id, "track $id")

    private suspend fun albumAnswer(id: String) = ask("getAlbum", "album $id", JsonPrimitive(id)) as? JsonObject ?: throw NotFound(this.id, "album $id")

    private fun albumInfo(o: JsonObject) = o.obj("album_info") ?: o.obj("album") ?: o
    private fun trackList(o: JsonObject) = o.objects("track_list").ifEmpty { o.objects("tracks") }

    /** A name without an id of its own, from a credit, stands for the first result of that name. */
    private suspend fun albumId(id: String): String {
        if (!id.startsWith("~")) return id
        val name = id.drop(1)
        val found = searchAlbums(name, 0, 10).items.firstOrNull { Match.similar(Match.title(it.name), Match.title(name)) >= SAME_NAME }
        return ItemUri.parse(found?.uri ?: throw NotFound(this.id, "album $name")).id
    }

    private suspend fun artistId(id: String): String {
        if (!id.startsWith("~")) return id
        val name = id.drop(1)
        val found = searchArtists(name, 0, 10).items.firstOrNull { Match.similar(Match.name(it.name), Match.name(name)) >= SAME_NAME }
        return ItemUri.parse(found?.uri ?: throw NotFound(this.id, "artist $name")).id
    }

    override suspend fun album(id: String): Album {
        val o = albumAnswer(albumId(id))
        val a = album(albumInfo(o)) ?: throw NotFound(this.id, "album $id")
        return if (a.trackCount > 0) a else a.copy(trackCount = trackList(o).size)
    }

    override suspend fun albumTracks(id: String, offset: Int, limit: Int): Page<Hit> {
        val o = albumAnswer(albumId(id))
        val info = album(albumInfo(o))
        val all = trackList(o).mapNotNull(::hit).map { h ->
            if (h.track.album != null || info == null) h
            else h.copy(track = h.track.copy(album = AlbumRef(info.uri, info.name, info.images)))
        }
        return pageOf(all.drop(offset).take(limit), offset, all.size, limit)
    }

    private suspend fun artistAnswer(id: String) = ask("getArtist", "artist $id", JsonPrimitive(id)) as? JsonObject ?: throw NotFound(this.id, "artist $id")

    private fun artistInfo(o: JsonObject) = o.obj("artist_info") ?: o.obj("artist") ?: o

    override suspend fun artist(id: String): Artist {
        val raw = artistId(id)
        return artist(artistInfo(artistAnswer(raw))) ?: throw NotFound(this.id, "artist $raw")
    }

    /** Not every service has them on the artist's page: its best results under that name say about the same. */
    override suspend fun topTracks(id: String): List<Hit> {
        val o = artistAnswer(artistId(id))
        val listed = o.objects("top_tracks").ifEmpty { o.objects("tracks") }.mapNotNull(::hit)
        if (listed.isNotEmpty()) return listed
        val name = artistInfo(o).str("name") ?: return emptyList()
        return searchTracks(name, 0, 20).items.filter { h -> h.track.artists.any { Match.similar(Match.name(it.name), Match.name(name)) >= SAME_NAME } }
    }

    override suspend fun discography(id: String, offset: Int, limit: Int): Page<Album> {
        val all = artistAnswer(artistId(id)).objects("albums").mapNotNull(::album).sortedByDescending { it.releaseDate.orEmpty() }
        return pageOf(all.drop(offset).take(limit), offset, all.size, limit)
    }

    /** None of the scripts knows related artists: MusicBrainz answers for them further down the chain. */
    override suspend fun related(id: String): List<Artist> = emptyList()

    /** A script's ids may already carry its name. */
    private fun plainId(raw: String) = raw.removePrefix("$id:")

    private fun uri(kind: String, raw: String) = ItemUri(id, kind, plainId(raw)).toString()

    private fun images(o: JsonObject): List<Image> =
        (o.str("cover_url") ?: o.str("image_url") ?: o.str("images"))?.let { listOf(Image(it)) }.orEmpty()

    private fun credits(o: JsonObject): List<ArtistRef> {
        val names = o.str("artists")?.split(", ")?.filter { it.isNotBlank() } ?: return emptyList()
        val first = o.str("artist_id")
        return names.mapIndexed { i, n -> ArtistRef(if (i == 0 && first != null) uri("artist", first) else ItemUri(id, "artist", "~$n").toString(), n) }
    }

    private fun hit(o: JsonObject): Hit? {
        val raw = o.str("id") ?: return null
        val album = o.str("album_id")?.let { AlbumRef(uri("album", it), o.str("album_name").orEmpty(), images(o)) }
            ?: o.str("album_name")?.let { AlbumRef(uri("album", "~$it"), it, images(o)) }
        return Hit(
            Track(
                uri = uri("track", raw),
                name = o.str("name").orEmpty(),
                durationMs = o.long("duration_ms") ?: 0,
                artists = credits(o),
                album = album,
                explicit = o.bool("explicit") ?: false,
            ),
            Match.isrc(o.str("isrc")),
        )
    }

    private fun album(o: JsonObject): Album? {
        val raw = o.str("id") ?: return null
        return Album(
            uri = uri("album", raw),
            name = o.str("name").orEmpty(),
            artists = credits(o),
            images = images(o),
            type = o.str("album_type") ?: "album",
            releaseDate = o.str("release_date"),
            trackCount = o.int("total_tracks") ?: 0,
            label = o.str("label"),
        )
    }

    private fun artist(o: JsonObject): Artist? {
        val raw = o.str("id") ?: return null
        return Artist(uri("artist", raw), o.str("name").orEmpty(), images(o))
    }

    private companion object {
        const val SAME_NAME = 0.9
    }
}

/** The script of [id] bundled with the app. */
fun bundledScript(id: String): String = ScriptProvider::class.java.getResource("metadata/$id.js")!!.readText()
