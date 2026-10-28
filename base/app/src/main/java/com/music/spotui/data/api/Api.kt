package com.music.spotui.data.api

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import android.util.Log
import app.myspoti.api.Album
import app.myspoti.api.Artist
import app.myspoti.api.Image
import app.myspoti.api.LibraryFilter
import app.myspoti.api.LocalFile
import app.myspoti.api.MediaCard
import app.myspoti.api.MediaKind
import app.myspoti.api.MyspotiApi
import app.myspoti.api.Playlist
import app.myspoti.api.Track
import app.myspoti.api.best
import app.myspoti.engine.R
import app.myspoti.host.android.MyspotiRuntime
import com.music.spotui.data.entity.AlbumsModel
import com.music.spotui.data.entity.ArtistOverviewModel
import com.music.spotui.data.entity.ArtistTrackUi
import com.music.spotui.data.entity.ArtistsModel
import com.music.spotui.data.entity.ContextPageModel
import com.music.spotui.data.entity.HomeFeedModel
import com.music.spotui.data.entity.HomeItem
import com.music.spotui.data.entity.HomeSection
import com.music.spotui.data.entity.LibraryEntry
import com.music.spotui.data.entity.PodcastModel
import com.music.spotui.data.entity.SearchResults
import com.music.spotui.data.entity.ShowPage
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.data.local.LocalFileMatch
import com.music.spotui.data.local.LocalMusic
import com.music.spotui.di.Graph
import com.music.spotui.di.SongPlayer
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * The UI's data source, over [MyspotiApi]. Keeps the `Flow<Response<...>>` contract the
 * ViewModels use; every call maps the API's models to the UI's ones.
 */
class Api constructor(
    private val context: android.content.Context = Graph.app,
) {
    private val api: MyspotiApi get() = MyspotiRuntime.api

    private fun stableId(key: String): Int = key.hashCode() and 0x7fffffff

    /** Process-level caches: screens show the last result at once, then refresh. */
    companion object HomeCache {
        /** Releases loaded for an artist page, at most. */
        const val DISCOGRAPHY_MAX = 300
        /** Sentinel id for the pinned "Liked Songs" library entry. */
        const val LIKED_SONGS_ID = "liked-songs"
        /** The "Local Files" folder pinned under Liked Songs in the library (not a Spotify item). */
        const val LOCAL_FILES_ID = "local-files"
        /** "Downloaded" (songs kept for offline listening), pinned under Local Files. */
        const val DOWNLOADS_ID = "downloads"
        /** "Your Episodes" (saved podcast episodes). */
        const val EPISODES_ID = "your-episodes"
        /** "Recently played": Spotify's own list for the account, pinned in the library. */
        const val RECENT_ID = "recently-played"
        /** Pages of 50 asked for a show's episodes / chapters, at most (500 rows). */
        const val MAX_EPISODE_PAGES = 10
        /** The page's collection set behind its "Mark as finished" menu. */
        private const val MARKED_FINISHED = "markedasfinished"
        @Volatile var albums: List<AlbumsModel>? = null
        @Volatile var artists: List<ArtistsModel>? = null
        @Volatile var home: HomeFeedModel? = null
        val facets = java.util.concurrent.ConcurrentHashMap<String, HomeFeedModel>()
        @Volatile var library: List<LibraryEntry>? = null
        /** Library as shown by the Library screen (root level, folders closed). */
        @Volatile var libraryRoot: List<LibraryEntry>? = null
        /** Contents of the library folders opened so far, by folder uri. */
        val folders = java.util.concurrent.ConcurrentHashMap<String, List<LibraryEntry>>()

        /** A playlist's songs were changed from the app: the next page of it reads them again. */
        fun invalidatePlaylist(playlistId: String) = PlaylistFresh.drop("spotify:playlist:$playlistId")

        /** Drop all cached feeds (e.g. on logout / account switch). */
        fun clear() {
            PlaylistFresh.drop(null)
            albums = null; artists = null; home = null; library = null
            libraryRoot = null; folders.clear()
            facets.clear()
            LikedCache.tracks = null
            ArtistCache.map.clear()
            ScreenCache.clear()
        }
    }

    object LikedCache {
        @Volatile var tracks: List<SongsModel>? = null
        private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
        @Volatile private var loading: kotlinx.coroutines.Deferred<List<SongsModel>?>? = null

        /**
         * All liked songs, loaded once for the whole app (not again by every screen that needs
         * them, nor started over when that screen closes before the end).
         */
        @Synchronized
        fun loadOnce(): kotlinx.coroutines.Deferred<List<SongsModel>?> {
            tracks?.let { return kotlinx.coroutines.CompletableDeferred(it) }
            loading?.takeIf { it.isActive }?.let { return it }
            return scope.async {
                runCatching {
                    Graph.api.getLikedPage().first { it is Response.Success && it.data.complete || it is Response.Error }
                        .let { (it as? Response.Success)?.data?.tracks }
                }.getOrNull()?.also { if (tracks == null) tracks = it }
            }.also { loading = it }
        }

        /**
         * [head] (the newest liked songs, from Spotify) then the rest of [known] after them.
         * A song unliked inside the head's span is gone from it, so it goes from the list too.
         */
        /** True when [head] ends inside [known] (or is the whole list), so [merge] loses nothing. */
        fun reaches(head: List<SongsModel>, known: List<SongsModel>): Boolean {
            if (head.size < 50) return true
            val last = head.last().spotifyTrackId
            return known.any { it.spotifyTrackId == last }
        }

        fun merge(head: List<SongsModel>, known: List<SongsModel>): List<SongsModel> {
            val ids = head.mapTo(HashSet()) { it.spotifyTrackId }
            val lastHead = head.lastOrNull()?.spotifyTrackId
            // What came after the head's last song in the old list is older: kept as it was.
            val tailStart = known.indexOfFirst { it.spotifyTrackId == lastHead }.let { if (it < 0) known.size else it + 1 }
            val tail = if (head.size < 50) emptyList() else known.drop(tailStart).filterNot { it.spotifyTrackId in ids }
            return head + tail
        }
    }

    /** True once the Web Player is signed in and ready (waits up to 30 s at startup). */
    private suspend fun ready(): Boolean =
        withTimeoutOrNull(30_000) { api.ready.first { it } } != null

    private suspend fun diskHome(): HomeFeedModel? =
        withContext(Dispatchers.IO) { ScreenCache.home.read() }?.also { HomeCache.home = it }

    private fun notSignedIn() = context.getString(R.string.not_signed_in)

    // ── Mapping ──

    private fun cover(images: List<Image>) = images.best(300)?.url.orEmpty()
    private fun big(images: List<Image>) = images.best(640)?.url.orEmpty()

    fun songOf(t: Track): SongsModel {
        val id = t.uri.substringAfterLast(':')
        val singer = t.artists.joinToString(", ") { it.name }
        if (t.uri.startsWith("spotify:episode:")) return SongsModel(
            id = stableId("episode:$id"),
            title = t.name.take(128),
            album = t.album?.name.orEmpty(),
            singer = singer,
            coverUri = cover(t.album?.images.orEmpty()),
            url = "episode:$id",
            explicit = t.explicit,
            durationMs = t.durationMs.toInt(),
            addedAt = t.addedAt.orEmpty(),
            playable = t.playable,
            rowUid = t.uid.orEmpty(),
        )
        // A local file of the user's computer: the uri holds its tags, nothing else. Greyed out
        // until [LocalFileMatch] finds the same song on this device.
        if (LocalFile.isLocal(t.uri)) return SongsModel(
            id = stableId("local:${t.uri}"),
            title = t.name.take(128),
            album = t.album?.name.orEmpty(),
            singer = singer,
            coverUri = "",
            url = t.uri,
            durationMs = t.durationMs.toInt(),
            addedAt = t.addedAt.orEmpty(),
            playable = false,
            rowUid = t.uid.orEmpty(),
        )
        return SongsModel(
            id = stableId("track:$id"),
            title = t.name.take(128),
            album = t.album?.name.orEmpty(),
            singer = singer,
            coverUri = cover(t.album?.images.orEmpty()),
            url = SongPlayer.buildSpotifyPlayQuery(id, t.name, singer),
            spotifyTrackId = id,
            explicit = t.explicit,
            durationMs = t.durationMs.toInt(),
            albumId = t.album?.uri?.substringAfterLast(':').orEmpty(),
            addedAt = t.addedAt.orEmpty(),
            playable = t.playable,
            artistIds = t.artists.map { it.uri.substringAfterLast(':') },
            rowUid = t.uid.orEmpty(),
        )
    }

    /** [songs] with the playlist's local files played from this device's own copy, when there is one. */
    private suspend fun withDeviceFiles(songs: List<SongsModel>): List<SongsModel> {
        if (songs.none { it.url.startsWith(LocalFileMatch.SPOTIFY_PREFIX) }) return songs
        return LocalFileMatch.resolve(songs, LocalMusic.tracks(context))
    }

    private fun episodeSong(e: app.myspoti.api.Episode): SongsModel {
        val id = e.uri.substringAfterLast(':')
        val show = e.show?.name ?: "Podcast"
        return SongsModel(
            id = stableId("episode:$id"),
            title = e.name,
            album = show,
            singer = show,
            coverUri = cover(e.images).ifBlank { cover(e.show?.images.orEmpty()) },
            url = "episode:$id",
            durationMs = e.durationMs.toInt(),
            // No spotifyTrackId on purpose: an episode is keyed by its "episode:<id>" url
            // everywhere (download, playback). "playable" is what the downloader refuses on.
            playable = e.playable,
            description = e.description.orEmpty(),
            releaseDate = e.releaseDate.orEmpty(),
            played = e.played,
            playedPositionMs = e.playedPositionMs,
            showId = e.show?.uri?.substringAfterLast(':').orEmpty(),
        )
    }

    private fun albumOf(a: Album) = AlbumsModel(
        spotifyId = a.uri.substringAfterLast(':'),
        id = stableId("album:${a.uri}"),
        artists = a.artists.joinToString(", ") { it.name },
        coverUri = cover(a.images),
        name = a.name,
        time = a.releaseDate?.take(10).orEmpty(),
        type = a.type,
    )

    private fun artistOf(a: Artist) = ArtistsModel(name = a.name, coverUri = cover(a.images), id = a.uri.substringAfterLast(':'))

    private fun playlistEntry(p: Playlist) = LibraryEntry(
        spotifyId = p.uri.substringAfterLast(':'),
        name = p.name,
        subtitle = context.getString(R.string.assisted_curation_search_playlist) + (p.owner?.let { " • $it" } ?: ""),
        coverUri = cover(p.images),
        isPlaylist = true,
    )

    private fun homeItem(c: MediaCard): HomeItem? {
        val id = c.uri.substringAfterLast(':')
        val image = cover(c.images)
        return when (c.kind) {
            MediaKind.ALBUM -> HomeItem.Album(id = id, name = c.title, imageUrl = image, subtitle = c.subtitle ?: context.getString(R.string.album_type_album), artists = c.subtitle.orEmpty())
            MediaKind.ARTIST -> HomeItem.Artist(name = c.title, imageUrl = image, id = id)
            MediaKind.PLAYLIST -> HomeItem.Playlist(name = c.title, imageUrl = image, subtitle = c.subtitle?.let { context.getString(R.string.assisted_curation_search_playlist) + " • $it" } ?: context.getString(R.string.assisted_curation_search_playlist), id = id)
            MediaKind.SHOW, MediaKind.AUDIOBOOK -> HomeItem.Show(id = id, name = c.title, imageUrl = image, subtitle = c.subtitle.orEmpty(), isEpisode = false)
            MediaKind.EPISODE, MediaKind.CHAPTER -> HomeItem.Show(id = id, name = c.title, imageUrl = image, subtitle = c.subtitle.orEmpty(), isEpisode = true)
            MediaKind.COLLECTION -> HomeItem.Playlist(name = c.title, imageUrl = image.ifBlank { LIKED_COVER }, subtitle = context.getString(R.string.assisted_curation_search_playlist), id = LIKED_SONGS_ID)
            else -> null
        }
    }

    private val LIKED_COVER = "https://misc.scdn.co/liked-songs/liked-songs-640.png"

    private fun likedEntry() = LibraryEntry(
        spotifyId = LIKED_SONGS_ID,
        name = context.getString(R.string.collection_liked_songs_title),
        subtitle = context.getString(R.string.assisted_curation_search_playlist),
        coverUri = LIKED_COVER,
        isPlaylist = true,
    )

    // ── Home ──

    /** New releases: albums from the home feed's music shelves. */
    suspend fun getAlbums(): Flow<Response<List<AlbumsModel>>> = flow {
        HomeCache.albums?.let { emit(Response.Success(it)) } ?: emit(Response.Loading())
        if (!ready()) { if (HomeCache.albums == null) emit(Response.Error(notSignedIn())); return@flow }
        runCatching { newReleases() }.fold(
            onSuccess = { HomeCache.albums = it; emit(Response.Success(it)) },
            onFailure = { Log.e("Api", "getAlbums failed", it); if (HomeCache.albums == null) emit(Response.Error(it.message ?: "error")) },
        )
    }

    /**
     * New releases for the "New releases" screen: albums from artists the user follows
     * (home shelves), then Spotify's new-release editorial playlists' albums.
     */
    suspend fun newReleases(): List<AlbumsModel> {
        val feed = api.home.feed(app.myspoti.api.HomeFilter.MUSIC, itemsPerShelf = 30)
        val albums = feed.shelves.flatMap { it.items }.filter { it.kind == MediaKind.ALBUM }.distinctBy { it.uri }
        return albums.map { c ->
            AlbumsModel(spotifyId = c.uri.substringAfterLast(':'), id = stableId("album:${c.uri}"), artists = c.subtitle.orEmpty(),
                coverUri = cover(c.images), name = c.title, time = "", type = "ALBUM")
        }
    }

    /** Artists the user follows (library "Artists" filter). */
    suspend fun getFollowedArtists(): List<ArtistsModel> {
        if (!ready()) return emptyList()
        val out = ArrayList<ArtistsModel>()
        var offset = 0
        while (true) {
            val page = runCatching { api.library.items(filter = LibraryFilter.ARTISTS, offset = offset, limit = 50) }.getOrNull() ?: break
            page.items.filter { it.kind == MediaKind.ARTIST }.forEach { out += ArtistsModel(it.name, cover(it.images), it.uri.substringAfterLast(':')) }
            offset = page.nextOffset ?: break
        }
        return out
    }

    /** The user's top artists. */
    suspend fun getArtists(): Flow<Response<List<ArtistsModel>>> = flow {
        HomeCache.artists?.let { emit(Response.Success(it)) } ?: emit(Response.Loading())
        if (!ready()) { if (HomeCache.artists == null) emit(Response.Error(notSignedIn())); return@flow }
        runCatching { api.users.top(limit = 20).artists.map(::artistOf) }.fold(
            onSuccess = { HomeCache.artists = it; emit(Response.Success(it)) },
            onFailure = { Log.e("Api", "getArtists failed", it); if (HomeCache.artists == null) emit(Response.Error(it.message ?: "error")) },
        )
    }

    /** The user's top tracks. */
    suspend fun getSongs(): Flow<Response<List<SongsModel>>> = flow {
        emit(Response.Loading())
        if (!ready()) { emit(Response.Error(notSignedIn())); return@flow }
        runCatching { api.users.top(limit = 50).tracks.map(::songOf) }.fold(
            onSuccess = { emit(Response.Success(it)) },
            onFailure = { Log.e("Api", "getSongs failed", it); emit(Response.Error(it.message ?: "error")) },
        )
    }

    /** [facet]: "" (all), "music-chip", "podcasts-chip", "audiobooks-chip". */
    suspend fun getHomeFeed(facet: String = ""): Flow<Response<HomeFeedModel>> = flow {
        val cached = if (facet.isEmpty()) HomeCache.home ?: diskHome() else HomeCache.facets[facet]
        cached?.let { emit(Response.Success(it)) } ?: emit(Response.Loading())
        if (!ready()) { if (cached == null) emit(Response.Error(notSignedIn())); return@flow }
        val filter = app.myspoti.api.HomeFilter.entries.firstOrNull { it.facet == facet } ?: app.myspoti.api.HomeFilter.ALL
        runCatching { api.home.feed(filter, itemsPerShelf = 20) }.fold(
            onSuccess = { feed ->
                val sections = feed.shelves.mapNotNull { shelf ->
                    val items = shelf.items.distinctBy { it.uri }.mapNotNull(::homeItem)
                        .distinctBy { it::class.simpleName + "|" + it.name.lowercase() }
                    if (items.isEmpty()) null else HomeSection(title = shelf.title.orEmpty(), items = items)
                }.distinctBy { it.title.lowercase().ifBlank { it.hashCode().toString() } }
                val model = HomeFeedModel(greeting = feed.greeting, sections = sections)
                if (facet.isEmpty()) { HomeCache.home = model; ScreenCache.home.write(model) } else HomeCache.facets[facet] = model
                emit(Response.Success(model))
            },
            onFailure = { Log.e("Api", "getHomeFeed failed", it); if (cached == null) emit(Response.Error(it.message ?: "error")) },
        )
    }

    // ── Search ──

    suspend fun searchTracks(query: String): Flow<Response<List<SongsModel>>> = flow {
        emit(Response.Loading())
        if (query.isBlank()) { emit(Response.Success(emptyList())); return@flow }
        if (!ready()) { emit(Response.Error(notSignedIn())); return@flow }
        runCatching { api.search.tracks(query, limit = 30).items.map(::songOf) }.fold(
            onSuccess = { emit(Response.Success(it)) },
            onFailure = { Log.e("Api", "searchTracks failed", it); emit(Response.Error(it.message ?: "error")) },
        )
    }

    /** Tracks, albums, artists and playlists in parallel, then podcasts and episodes. */
    suspend fun searchEverything(query: String): Flow<Response<SearchResults>> = flow {
        emit(Response.Loading())
        if (query.isBlank()) { emit(Response.Success(SearchResults())); return@flow }
        if (!ready()) { emit(Response.Error(notSignedIn())); return@flow }
        val music = runCatching { api.search.all(query, limit = 20) }.getOrElse {
            Log.e("Api", "searchEverything failed", it); emit(Response.Error(it.message ?: "error")); return@flow
        }
        val base = SearchResults(
            songs = music.tracks.items.map(::songOf),
            albums = music.albums.items.map(::albumOf),
            artists = music.artists.items.map(::artistOf),
            playlists = music.playlists.items.map(::playlistEntry),
        )
        emit(Response.Success(base))
        val (shows, episodes) = coroutineScope {
            val s = async { runCatching { api.search.shows(query, limit = 12).items }.getOrDefault(emptyList()) }
            val e = async { runCatching { api.search.episodes(query, limit = 12).items }.getOrDefault(emptyList()) }
            s.await() to e.await()
        }
        if (shows.isNotEmpty() || episodes.isNotEmpty()) emit(Response.Success(base.copy(
            shows = shows.map { PodcastModel(it.uri.substringAfterLast(':'), it.title, it.subtitle.orEmpty(), cover(it.images)) },
            episodes = episodes.map(::episodeSong),
        )))
    }

    // ── Podcasts ──

    /** A podcast's episodes, newest first, as playable songs (url = "episode:<id>"). */
    suspend fun getShowEpisodes(showId: String): Flow<Response<List<SongsModel>>> = flow {
        emit(Response.Loading())
        if (!ready()) { emit(Response.Error(notSignedIn())); return@flow }
        val uri = "spotify:show:$showId"
        val episodes = runCatching {
            val out = ArrayList<SongsModel>()
            var offset = 0
            repeat(10) {
                val page = api.podcasts.episodes(uri, offset, 50)
                out += page.items.map(::episodeSong)
                emit(Response.Success(out.toList()))
                offset = page.nextOffset ?: return@runCatching out
            }
            out
        }
        if (episodes.exceptionOrNull() is CancellationException) throw episodes.exceptionOrNull()!!
        episodes.fold(
            onSuccess = { songs -> SongPlayer.registerContext(songs.map { it.url }, uri); emit(Response.Success(songs)) },
            onFailure = { Log.e("Api", "getShowEpisodes failed", it); emit(Response.Error(it.message ?: "error")) },
        )
    }

    /** Show header (name, publisher, cover). */
    suspend fun getShow(showId: String): PodcastModel? {
        if (!ready()) return null
        return runCatching { api.podcasts.show("spotify:show:$showId") }.getOrNull()?.let {
            PodcastModel(showId, it.name, it.publisher, big(it.images))
        }
    }

    /**
     * The show page: header and rows in one flow, so the screen has a single loading state.
     *
     * A podcast answers [app.myspoti.api.Podcasts.episodes]; a book answers nothing there and
     * only has chapters, which is why its page used to show a cover and a title and nothing
     * else (B13).
     */
    suspend fun getShowPage(showId: String): Flow<Response<ShowPage>> = flow {
        emit(Response.Loading())
        if (!ready()) { emit(Response.Error(notSignedIn())); return@flow }
        val uri = "spotify:show:$showId"
        val head = runCatching { api.podcasts.show(uri) }.getOrNull()
        val rows = ArrayList<SongsModel>()
        var failure: Throwable? = null
        var audiobook = false
        var book: app.myspoti.api.Audiobook? = null

        try {
            var offset = 0
            loop@ for (page in 0 until MAX_EPISODE_PAGES) {
                val p = api.podcasts.episodes(uri, offset, 50)
                if (p.items.isEmpty()) break@loop
                rows += p.items.map(::episodeSong)
                emit(Response.Success(pageOf(showId, head, book, rows, audiobook = false)))
                offset = p.nextOffset ?: break@loop
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failure = e
        }

        if (rows.isEmpty()) {
            // A book: its chapters come from queryBookChapters, not from the episodes query.
            audiobook = true
            book = runCatching { api.podcasts.audiobook(uri) }.getOrNull()
            try {
                var offset = 0
                loop@ for (page in 0 until MAX_EPISODE_PAGES) {
                    val p = api.podcasts.chapters(uri, offset, 50)
                    if (p.items.isEmpty()) break@loop
                    rows += p.items.map(::episodeSong)
                    emit(Response.Success(pageOf(showId, head, book, rows, audiobook = true)))
                    offset = p.nextOffset ?: break@loop
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (rows.isEmpty()) failure = e
            }
        }

        if (rows.isEmpty() && failure != null) {
            Log.e("Api", "getShowPage failed", failure)
            emit(Response.Error(failure.message ?: "error"))
            return@flow
        }
        SongPlayer.registerContext(rows.map { it.url }, uri)
        emit(Response.Success(pageOf(showId, head, book, rows, audiobook)))
    }

    private fun pageOf(
        showId: String,
        head: app.myspoti.api.Show?,
        book: app.myspoti.api.Audiobook?,
        rows: List<SongsModel>,
        audiobook: Boolean,
    ): ShowPage {
        val cover = head?.let { big(it.images) }.orEmpty()
            .ifBlank { book?.images?.let(::big).orEmpty() }
            .ifBlank { rows.firstOrNull()?.coverUri.orEmpty() }
        val model = PodcastModel(
            id = showId,
            name = head?.name ?: book?.name ?: rows.firstOrNull()?.album.orEmpty(),
            publisher = head?.publisher?.takeIf { it.isNotBlank() }
                ?: book?.authors?.joinToString(", ").orEmpty(),
            coverUri = cover,
            description = head?.description ?: book?.description?.let { app.myspoti.spotify.stripHtml(it) }.orEmpty(),
            rating = head?.rating ?: 0.0,
            saved = head?.saved == true,
            authors = book?.authors.orEmpty(),
            narrators = book?.narrators.orEmpty(),
            audiobook = audiobook,
        )
        return ShowPage(model, rows, audiobook, cover)
    }

    /** One episode (or chapter) by raw Spotify id, for the episode page. */
    suspend fun getEpisode(episodeId: String): SongsModel? {
        if (!ready()) return null
        return runCatching { episodeSong(api.podcasts.episode("spotify:episode:$episodeId")) }.getOrNull()
    }

    /**
     * Marks an episode finished, or not started. The page keeps this in its own
     * "markedasfinished" collection — the set behind its "Mark as finished" menu — so the
     * change follows the account like Spotify's own menu does.
     */
    suspend fun setEpisodePlayed(episodeUri: String, played: Boolean) {
        if (episodeUri.isBlank()) return
        MyspotiRuntime.spotify.raw.service(
            "CollectionPlatformAPI",
            if (played) "add" else "remove",
            kotlinx.serialization.json.JsonPrimitive(MARKED_FINISHED),
            kotlinx.serialization.json.JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive(episodeUri))),
        )
    }

    /** Raw uris the page has in its "markedasfinished" set (empty when it refuses). */
    suspend fun finishedEpisodes(): Set<String> {
        if (!ready()) return emptySet()
        // The page's get() throws on an empty set: that simply means nothing is marked.
        val raw = runCatching {
            MyspotiRuntime.spotify.raw.service(
                "CollectionPlatformAPI", "get", kotlinx.serialization.json.JsonPrimitive(MARKED_FINISHED),
            )
        }.getOrNull()
        return (raw as? kotlinx.serialization.json.JsonArray).orEmpty()
            .mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull }
            .toSet()
    }

    /** "Your Episodes": saved podcast episodes. */
    suspend fun getSavedEpisodes(): List<SongsModel> {
        if (!ready()) return emptyList()
        return runCatching { api.podcasts.savedEpisodes(limit = 100).items.map(::episodeSong) }.getOrDefault(emptyList())
    }

    /** Release date of a show's newest episode (one episode asked for), "" when unknown. */
    suspend fun latestEpisodeDate(showId: String): String {
        if (!ready()) return ""
        return runCatching { api.podcasts.episodes("spotify:show:$showId", 0, 1).items.firstOrNull()?.releaseDate.orEmpty() }.getOrDefault("")
    }

    // ── Radio and categories ──

    /** The song radio after [seedTrackIds]' last track: Spotify's own radio playlist. */
    suspend fun getRecommendations(seedTrackIds: List<String>): List<SongsModel> {
        val seed = seedTrackIds.lastOrNull { it.isNotBlank() } ?: return emptyList()
        if (!ready()) return emptyList()
        val radio = runCatching { api.extras.radio("spotify:track:$seed") }.getOrNull() ?: return emptyList()
        return runCatching { api.playlists.tracks(radio, 0, 50).items.map(::songOf).filter { it.spotifyTrackId != seed } }.getOrDefault(emptyList())
    }

    /** The radio playlist id for a track or artist uri (for "Go to song radio"). */
    suspend fun radioPlaylistId(seedUri: String): String? =
        if (!ready()) null else runCatching { api.extras.radio(seedUri) }.getOrNull()?.substringAfterLast(':')

    /**
     * A browse category: Spotify's own category page when [genre] is a page/genre uri or id,
     * else playlists matching the name.
     */
    suspend fun getCategoryPlaylists(genre: String): Flow<Response<List<LibraryEntry>>> = flow {
        emit(Response.Loading())
        if (genre.isBlank()) { emit(Response.Success(emptyList())); return@flow }
        if (!ready()) { emit(Response.Error(notSignedIn())); return@flow }
        val pageUri = when {
            genre.startsWith("spotify:page:") || genre.startsWith("spotify:genre:") -> genre
            else -> runCatching { api.home.categories() }.getOrNull()?.firstOrNull { it.title.equals(genre, ignoreCase = true) }?.uri
        }
        val entries = runCatching {
            if (pageUri != null) {
                api.home.category(pageUri).shelves.flatMap { it.items }.distinctBy { it.uri }.mapNotNull { c ->
                    if (c.kind != MediaKind.PLAYLIST && c.kind != MediaKind.ALBUM) return@mapNotNull null
                    LibraryEntry(c.uri.substringAfterLast(':'), c.title, c.subtitle.orEmpty(), cover(c.images), isPlaylist = c.kind == MediaKind.PLAYLIST)
                }
            } else api.search.playlists(genre, limit = 24).items.map(::playlistEntry)
        }
        entries.fold(
            onSuccess = { emit(Response.Success(it)) },
            onFailure = { Log.e("Api", "getCategoryPlaylists failed", it); emit(Response.Error(it.message ?: "error")) },
        )
    }

    // ── Artists ──

    private suspend fun findArtist(name: String): Artist? {
        val hits = api.search.artists(name, limit = 5).items
        return hits.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: hits.firstOrNull()
    }

    suspend fun getArtistSongs(artistName: String): Flow<Response<List<SongsModel>>> = flow {
        emit(Response.Loading())
        if (artistName.isBlank()) { emit(Response.Success(emptyList())); return@flow }
        if (!ready()) { emit(Response.Error(notSignedIn())); return@flow }
        runCatching {
            val artist = findArtist(artistName) ?: return@runCatching emptyList()
            api.catalog.artistTopTracks(artist.uri).map { songOf(it).let { s -> if (s.coverUri.isBlank()) s.copy(coverUri = cover(artist.images)) else s } }
        }.fold(
            onSuccess = { emit(Response.Success(it)) },
            onFailure = { Log.e("Api", "getArtistSongs failed", it); emit(Response.Error(it.message ?: "error")) },
        )
    }

    /** The full artist page; [knownArtistId] avoids the fuzzy name lookup. */
    suspend fun getArtistOverview(artistName: String, knownArtistId: String = ""): Flow<Response<ArtistOverviewModel>> = flow {
        emit(Response.Loading())
        if (artistName.isBlank() && knownArtistId.isBlank()) { emit(Response.Success(ArtistOverviewModel(name = artistName))); return@flow }
        if (!ready()) { emit(Response.Error(notSignedIn())); return@flow }
        val uri = if (knownArtistId.isNotBlank()) "spotify:artist:$knownArtistId"
        else runCatching { findArtist(artistName) }.getOrNull()?.uri
        if (uri == null) { emit(Response.Error(context.getString(R.string.artist_not_found))); return@flow }
        // Shown again within a few minutes (back, the releases page, another tap): from memory,
        // without asking Spotify for the page and the whole discography once more.
        ArtistCache.map[uri]?.let { (at, cached) ->
            emit(Response.Success(cached))
            if (System.currentTimeMillis() - at < ArtistCache.FRESH_MS) return@flow
        }
        val firstReleases = kotlinx.coroutines.coroutineScope {
            // The discography's first page goes out with the page itself, not after it.
            val releases = async { runCatching { api.catalog.discography(uri, 0, 50) }.getOrNull() }
            val page = runCatching { api.catalog.artistPage(uri) }.getOrElse {
                releases.cancel()
                Log.e("Api", "getArtistOverview failed", it)
                return@coroutineScope null
            }
            page to releases
        } ?: run {
            if (ArtistCache.map[uri] == null) emit(Response.Error(context.getString(R.string.page_load_failed)))
            return@flow
        }
        val page = firstReleases.first
        val avatar = big(page.artist.images)
        val top = page.topTracks.map { t -> songOf(t.track).let { if (it.coverUri.isBlank()) it.copy(coverUri = avatar) else it } to t.playcount }
        SongPlayer.registerContext(top.map { it.first.url }, uri)
        val model = ArtistOverviewModel(
            id = uri.substringAfterLast(':'),
            name = page.artist.name.ifBlank { artistName },
            verified = page.artist.verified,
            monthlyListeners = page.artist.monthlyListeners,
            biography = page.biography,
            headerImage = big(page.headerImages).ifBlank { avatar },
            avatarImage = avatar,
            topTracks = top.map { (song, plays) -> ArtistTrackUi(song, plays) },
            // Newest first (the sort is stable: same-day releases keep Spotify's order).
            popularReleases = page.popularReleases.sortedByDescending { it.releaseDate.orEmpty().take(10) }.map(::albumOf),
            appearsOn = page.appearsOn.map(::albumOf),
            relatedArtists = page.related.map(::artistOf),
        )
        emit(Response.Success(model))
        ArtistCache.map[uri] = System.currentTimeMillis() to model
        // The overview carries only part of the discography: the whole list follows.
        // Every page of it (big artists have hundreds of singles), up to a sane cap.
        val all = runCatching {
            val out = ArrayList<app.myspoti.api.Album>()
            val first = firstReleases.second.await()
            var offset: Int? = if (first != null) { out += first.items; first.nextOffset.takeIf { first.items.isNotEmpty() } } else 0
            while (offset != null && out.size < DISCOGRAPHY_MAX) {
                val next = api.catalog.discography(uri, offset, 50)
                if (next.items.isEmpty()) break
                out += next.items
                offset = next.nextOffset
            }
            out
        }.getOrNull().orEmpty()
        val known = page.popularReleases.map { it.uri }.toSet()
        if (all.any { it.uri !in known }) {
            val merged = (page.popularReleases + all).distinctBy { it.uri }.sortedByDescending { it.releaseDate.orEmpty().take(10) }
            val full = model.copy(popularReleases = merged.map(::albumOf))
            ArtistCache.map[uri] = System.currentTimeMillis() to full
            emit(Response.Success(full))
        }
    }

    /**
     * The songs of playlists read in full lately, by playlist uri (a few, small ones: a long list
     * stays on the network). Dropped by an edit made from the app, see [invalidatePlaylist].
     */
    private object PlaylistFresh {
        const val FRESH_MS = 5 * 60_000L
        const val MAX_TRACKS = 1_000
        private const val KEEP = 6
        private val map = object : LinkedHashMap<String, Pair<Long, List<SongsModel>>>(8, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Long, List<SongsModel>>>) = size > KEEP
        }

        @Synchronized fun get(uri: String): List<SongsModel>? =
            map[uri]?.takeIf { System.currentTimeMillis() - it.first < FRESH_MS }?.second

        @Synchronized fun put(uri: String, songs: List<SongsModel>) {
            if (songs.isEmpty() || songs.size > MAX_TRACKS) map.remove(uri) else map[uri] = System.currentTimeMillis() to songs
        }

        @Synchronized fun drop(uri: String?) { if (uri == null) map.clear() else map.remove(uri) }
    }

    /** Artist pages shown lately, by artist uri, with when they were loaded. */
    private object ArtistCache {
        const val FRESH_MS = 5 * 60_000L
        val map = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, ArtistOverviewModel>>()
    }

    // ── Albums and playlists ──

    private fun dateLabel(iso: String?): String {
        if (iso.isNullOrBlank()) return ""
        return runCatching {
            val d = java.time.LocalDate.parse(iso.take(10))
            d.format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy", java.util.Locale.getDefault()))
        }.getOrDefault(iso.take(4))
    }

    private fun durationLabel(totalMs: Long): String {
        val min = totalMs / 60000
        return if (min >= 60) "${min / 60} h ${min % 60} min" else "$min min"
    }

    /** Album id for a name (+ artist) when the caller only knows the name. */
    suspend fun resolveAlbumId(albumName: String, artist: String = ""): String? {
        if (albumName.isBlank() || !ready()) return null
        val hits = runCatching { api.search.albums(if (artist.isBlank()) albumName else "$albumName $artist", limit = 10).items }.getOrNull().orEmpty()
        return pickAlbum(hits, albumName, artist)?.uri?.substringAfterLast(':')
    }

    /**
     * The user's liked songs by one artist, as their own page (Spotify's "Liked Songs" row on an
     * artist page). They play inside Liked Songs, in this list's order, so only they play.
     */
    suspend fun getLikedByArtistPage(artistId: String, artistName: String): Flow<Response<ContextPageModel>> = flow {
        emit(Response.Loading())
        val all = LikedCache.tracks ?: LikedCache.loadOnce().await()
            ?: run { emit(Response.Error(context.getString(R.string.page_load_failed))); return@flow }
        val songs = all.filter { s ->
            if (s.artistIds.isNotEmpty()) artistId in s.artistIds
            else artistName.isNotBlank() && s.singer.split(", ").any { it.equals(artistName, ignoreCase = true) }
        }
        emit(Response.Success(ContextPageModel(
            uri = "spotify:collection:tracks",
            type = "liked-artist",
            name = context.getString(R.string.artist_liked_songs),
            coverUri = "",
            ownerName = ProfileCache.name.orEmpty(),
            ownerAvatar = ProfileCache.imageUrl.orEmpty(),
            metaLine = context.resources.getQuantityString(R.plurals.artist_liked_songs_count, songs.size, songs.size, artistName),
            tracks = songs,
            complete = true,
        )))
    }

    /**
     * Liked Songs as a page: cached list shown instantly, then refreshed. A list read in full less
     * than [LIKED_FULL_MS] ago only has its newest page read again (the hearts of this account
     * and a like or unlike anywhere else land there); after that, with no list on storage, or
     * with [full] (a pull-to-refresh), every page is read.
     */
    suspend fun getLikedPage(full: Boolean = false): Flow<Response<ContextPageModel>> = flow {
        fun page(tracks: List<SongsModel>, complete: Boolean) = ContextPageModel(
            uri = "spotify:collection:tracks",
            type = "liked",
            name = context.getString(R.string.collection_liked_songs_title),
            coverUri = "",
            ownerName = ProfileCache.name.orEmpty(),
            ownerAvatar = ProfileCache.imageUrl.orEmpty(),
            metaLine = context.resources.getQuantityString(R.plurals.liked_songs_header_metadata_text, tracks.size, tracks.size),
            tracks = tracks,
            complete = complete,
        )
        if (LikedCache.tracks == null) LikedCache.tracks = withContext(Dispatchers.IO) { ScreenCache.liked.read() }
        LikedCache.tracks?.let { emit(Response.Success(page(it, true))) } ?: emit(Response.Loading())
        if (!ready()) { if (LikedCache.tracks == null) emit(Response.Error(notSignedIn())); return@flow }
        val known = LikedCache.tracks
        if (!full && known != null) {
            val at = withContext(Dispatchers.IO) { ScreenCache.likedFullAt() }
            if (at != null && System.currentTimeMillis() - at < LIKED_FULL_MS) {
                val head = runCatching { likedHead() }.getOrElse { if (it is CancellationException) throw it; null }
                // The list shown stays when Spotify cannot be reached.
                if (head == null) return@flow
                // A head that does not reach the list known (more than a page of new likes) cannot be merged.
                if (LikedCache.reaches(head, known)) {
                    val merged = LikedCache.merge(head, known)
                    LikedCache.tracks = merged
                    ScreenCache.liked.write(merged)
                    SongPlayer.registerLikedSongs(merged.map { it.url })
                    emit(Response.Success(page(merged, true)))
                    return@flow
                }
            }
        }
        val first = LikedCache.tracks == null
        // Cancellation must escape: emit() throws AbortFlowException (a CancellationException)
        // when the collector stops early (first { … }); catching it and emitting again
        // violates flow exception transparency and breaks Android Auto's firstResult.
        val tracks = runCatching { allLiked { if (first) emit(Response.Success(page(it, false))) } }.getOrElse {
            if (it is CancellationException) throw it
            Log.e("Api", "getLikedPage failed", it)
            if (LikedCache.tracks == null) emit(Response.Error(context.getString(R.string.couldnt_load, context.getString(R.string.collection_liked_songs_title))))
            return@flow
        }
        LikedCache.tracks = tracks
        ScreenCache.liked.write(tracks)
        ScreenCache.markLikedFull()
        SongPlayer.registerLikedSongs(tracks.map { it.url })
        emit(Response.Success(page(tracks, true)))
    }

    /**
     * After a like / unlike: the newest Liked Songs (one page) put on top of what is
     * known, so the list is right at once without loading every page again.
     */
    suspend fun mergeLikedHead(): List<SongsModel>? = runCatching {
        if (!ready()) return null
        val head = likedHead()
        val merged = LikedCache.merge(head, LikedCache.tracks.orEmpty())
        LikedCache.tracks = merged
        withContext(Dispatchers.IO) { ScreenCache.liked.write(merged) }
        SongPlayer.registerLikedSongs(merged.map { it.url })
        merged
    }.getOrNull()

    /** The newest Liked Songs: one page of 50. */
    private suspend fun likedHead(): List<SongsModel> =
        api.library.likedTracks(0, 50).items.map { songOf(it.track).copy(addedAt = it.addedAt.orEmpty()) }

    private suspend fun allLiked(onPage: suspend (List<SongsModel>) -> Unit): List<SongsModel> {
        val out = ArrayList<SongsModel>()
        var offset = 0
        while (true) {
            val page = api.library.likedTracks(offset, 50)
            out += page.items.map { songOf(it.track).copy(addedAt = it.addedAt.orEmpty()) }
            onPage(out.toList())
            offset = page.nextOffset ?: break
        }
        return out
    }

    /**
     * An album as a page: the copy on storage (opened before) at once, then the album and its
     * songs from Spotify, and the artist's avatar a moment after in a second emit. Without
     * [stored] the copy on storage is left out (a sync that must see the album as it is now).
     */
    suspend fun getAlbumPage(albumId: String, stored: Boolean = true): Flow<Response<ContextPageModel>> = flow {
        emit(Response.Loading())
        val uri = "spotify:album:$albumId"
        val saved = if (stored) withContext(Dispatchers.IO) { ScreenCache.albumPage(albumId).read() } else null
        if (saved != null) {
            SongPlayer.registerContext(saved.tracks.map { it.url }, uri)
            emit(Response.Success(saved))
        }
        if (!ready()) { if (saved == null) emit(Response.Error(notSignedIn())); return@flow }
        val (album, tracks) = runCatching {
            coroutineScope {
                val a = async { api.catalog.album(uri) }
                val t = async {
                    val out = ArrayList<Track>()
                    var offset = 0
                    while (true) {
                        val p = api.catalog.albumTracks(uri, offset, 50)
                        out += p.items
                        offset = p.nextOffset ?: break
                    }
                    out
                }
                a.await() to t.await()
            }
        }.getOrElse { if (saved == null) emit(Response.Error(context.getString(R.string.album_not_found))); return@flow }
        val songs = tracks.map { songOf(it).copy(album = album.name, coverUri = cover(album.images), albumId = albumId) }
        SongPlayer.registerContext(songs.map { it.url }, uri)
        val type = context.getString(when (album.type) {
            "SINGLE" -> R.string.album_type_single; "EP" -> R.string.album_type_ep
            "COMPILATION" -> R.string.album_type_compilation; else -> R.string.album_type_album
        })
        val firstArtist = album.artists.firstOrNull()
        val page = ContextPageModel(
            uri = uri,
            type = "album",
            name = album.name,
            coverUri = big(album.images),
            ownerName = album.artists.joinToString(", ") { it.name },
            // The avatar of the copy on storage (same artist) until the new one is known.
            ownerAvatar = saved?.takeIf { it.ownerUri == firstArtist?.uri.orEmpty() }?.ownerAvatar.orEmpty(),
            ownerUri = firstArtist?.uri.orEmpty(),
            metaLine = listOf(type, dateLabel(album.releaseDate)).filter { it.isNotBlank() }.joinToString(" • "),
            explicit = songs.any { it.explicit },
            tracks = songs,
        )
        // Written before the first emit: a collector that only wants the first page stops the flow there.
        ScreenCache.albumPage(albumId).write(page)
        ScreenCache.pruneAlbumPages(ALBUM_PAGES_KEPT)
        emit(Response.Success(page))
        val avatar = firstArtist?.let { runCatching { api.catalog.artist(it.uri) }.getOrNull() }?.images?.let(::cover).orEmpty()
        if (avatar.isNotBlank() && avatar != page.ownerAvatar) {
            val withAvatar = page.copy(ownerAvatar = avatar)
            ScreenCache.albumPage(albumId).write(withAvatar)
            emit(Response.Success(withAvatar))
        }
    }

    /**
     * A playlist as a page. Its details are always asked for; its songs are kept in memory for
     * [PlaylistFresh.FRESH_MS] after a full read and reused when the playlist still has as many
     * songs, so reopening it does not read every page again. An edit made here drops them
     * ([invalidatePlaylist]); [force] (a reload) reads everything.
     */
    suspend fun getPlaylistPage(playlistId: String, force: Boolean = false): Flow<Response<ContextPageModel>> = flow {
        emit(Response.Loading())
        if (!ready()) { emit(Response.Error(notSignedIn())); return@flow }
        val uri = "spotify:playlist:$playlistId"
        val fresh = if (force) null else PlaylistFresh.get(uri)
        // The first songs are asked for together with the playlist's details, not after them.
        val flowScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.currentCoroutineContext())
        fun startFirstPage() = flowScope.async(Dispatchers.IO) {
            runCatching { api.playlists.tracks(uri, 0, 100) }
        }
        var firstPage = if (fresh == null) startFirstPage() else null
        val pl = runCatching { api.playlists.playlist(uri) }.getOrElse {
            firstPage?.cancel()
            emit(Response.Error(context.getString(R.string.playlist_not_found))); return@flow
        }
        if (fresh != null && fresh.size != pl.trackCount) firstPage = startFirstPage()
        val saves = pl.followers?.takeIf { it > 0 }?.let {
            context.resources.getQuantityString(R.plurals.profile_list_item_saves_count, it.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), it)
        }.orEmpty()
        val header = ContextPageModel(
            uri = uri,
            type = "playlist",
            name = pl.name,
            coverUri = big(pl.images),
            description = stripHtml(pl.description),
            ownerName = pl.owner.orEmpty(),
            ownerAvatar = cover(pl.ownerImages),
            ownerUri = pl.ownerUri.orEmpty(),
            metaLine = saves,
            isBlend = pl.isBlend,
            canLeave = pl.canLeave,
        )
        // The first page gives the total: the rest is asked for PLAYLIST_PARALLEL pages at a time
        // and shown in order as it lands, at most every PLAYLIST_EMIT_MS. One page after the
        // other, each followed by the whole list shown and registered again, a 6000-song
        // playlist took about two minutes (tester report).
        val songs = ArrayList<SongsModel>()
        var failed = false
        fun publish(done: Boolean): Response<ContextPageModel> {
            SongPlayer.registerContext(songs.map { it.url }, uri)
            // Kept before the emit: a collector that stops at the complete page ends the flow there.
            if (done && !failed) PlaylistFresh.put(uri, songs.toList())
            val total = songs.sumOf { it.durationMs.toLong() }
            // The song count rides along with the duration ("1 h 2 min / 12 songs") so the two
            // numbers that grow together as pages arrive stay next to each other.
            val durationAndCount = if (songs.isNotEmpty()) {
                val count = context.resources.getQuantityString(R.plurals.liked_songs_header_metadata_text, songs.size, songs.size)
                if (total > 0) "${durationLabel(total)} / $count" else count
            } else ""
            val meta = listOf(saves, durationAndCount).filter { it.isNotBlank() }.joinToString(" • ")
            // A playlist without a cover of its own shows the mosaic of its songs' albums, as in the official app.
            val cover = header.coverUri.ifBlank { com.music.spotui.ui.components.mosaicCoverFor(songs.take(200).map { it.coverUri }) }
            return Response.Success(header.copy(coverUri = cover, metaLine = meta, tracks = songs.toList(), complete = done))
        }
        if (firstPage == null) {
            songs += fresh!!
            emit(publish(done = true))
            return@flow
        }
        val first = firstPage.await().getOrElse { failed = true; null }
        if (first != null) {
            songs += withDeviceFiles(first.items.map(::songOf))
            val offsets = generateSequence(first.nextOffset) { (it + PLAYLIST_PAGE).takeIf { n -> n < first.total } }.toList()
            emit(publish(done = offsets.isEmpty()))
            if (offsets.isNotEmpty()) {
                val gate = kotlinx.coroutines.sync.Semaphore(PLAYLIST_PARALLEL)
                coroutineScope {
                    val jobs = offsets.map { o -> async(Dispatchers.IO) { gate.withPermit { runCatching { api.playlists.tracks(uri, o, PLAYLIST_PAGE) }.getOrNull() } } }
                    var lastEmit = System.currentTimeMillis()
                    for ((i, job) in jobs.withIndex()) {
                        val page = job.await()
                        if (page == null) { failed = true; jobs.drop(i + 1).forEach { it.cancel() }; break }
                        songs += withDeviceFiles(page.items.map(::songOf))
                        val last = i == jobs.lastIndex
                        if (last || System.currentTimeMillis() - lastEmit >= PLAYLIST_EMIT_MS) {
                            emit(publish(done = last)); lastEmit = System.currentTimeMillis()
                        }
                    }
                }
                // A page that failed: what came before it stays, marked complete so nothing waits.
                if (failed && songs.isNotEmpty()) emit(publish(done = true))
            }
        }
        if (failed && songs.isEmpty()) emit(Response.Error(context.getString(R.string.couldnt_load, pl.name)))
    }

    suspend fun getAlbumSongs(albumName: String, artist: String = ""): Flow<Response<List<SongsModel>>> = flow {
        emit(Response.Loading())
        if (albumName.isBlank()) { emit(Response.Success(emptyList())); return@flow }
        if (!ready()) { emit(Response.Error(notSignedIn())); return@flow }
        val id = resolveAlbumId(albumName, artist) ?: run { emit(Response.Success(emptyList())); return@flow }
        runCatching { api.catalog.albumTracks("spotify:album:$id", 0, 50).items.map(::songOf) }.fold(
            onSuccess = { songs -> SongPlayer.registerContext(songs.map { it.url }, "spotify:album:$id"); emit(Response.Success(songs)) },
            onFailure = { Log.e("Api", "getAlbumSongs failed", it); emit(Response.Error(it.message ?: "error")) },
        )
    }

    /** A playlist's tracks, first page at once, then the rest. */
    suspend fun getPlaylistSongs(playlistId: String): Flow<Response<List<SongsModel>>> = flow {
        emit(Response.Loading())
        if (playlistId.isBlank()) { emit(Response.Success(emptyList())); return@flow }
        if (!ready()) { emit(Response.Error(notSignedIn())); return@flow }
        val uri = "spotify:playlist:$playlistId"
        val songs = ArrayList<SongsModel>()
        var offset = 0
        while (true) {
            val page = runCatching { api.playlists.tracks(uri, offset, 100) }.getOrNull() ?: break
            songs += withDeviceFiles(page.items.map(::songOf))
            SongPlayer.registerContext(songs.map { it.url }, uri)
            emit(Response.Success(songs.toList()))
            offset = page.nextOffset ?: break
        }
        if (songs.isEmpty()) emit(Response.Error(context.getString(R.string.page_load_failed)))
    }

    // ── Library ──

    private fun entryOf(item: app.myspoti.api.LibraryItem): LibraryEntry? = when (item.kind) {
        MediaKind.FOLDER -> LibraryEntry(
            spotifyId = item.uri, name = item.name,
            subtitle = context.getString(R.string.create_menu_item_folder_title) + " • " +
                context.resources.getQuantityString(R.plurals.add_to_playlist_folder_subtitle_playlists_count, item.count ?: 0, (item.count ?: 0).toString()),
            coverUri = "", isPlaylist = false, folderUri = item.uri, childCount = item.count ?: 0,
            uri = item.uri, pinned = item.pinned, addedAt = item.addedAt.orEmpty(),
        )
        MediaKind.PLAYLIST -> LibraryEntry(item.uri.substringAfterLast(':'), item.name,
            context.getString(R.string.assisted_curation_search_playlist) + (item.subtitle?.let { " • $it" } ?: ""), cover(item.images), isPlaylist = true,
            uri = item.uri, pinned = item.pinned, addedAt = item.addedAt.orEmpty())
        MediaKind.ALBUM -> LibraryEntry(item.uri.substringAfterLast(':'), item.name,
            context.getString(R.string.album_type_album) + (item.subtitle?.let { " • $it" } ?: ""), cover(item.images), isPlaylist = false, artists = item.subtitle.orEmpty(),
            uri = item.uri, pinned = item.pinned, addedAt = item.addedAt.orEmpty())
        MediaKind.COLLECTION -> if (item.uri == "spotify:collection:tracks") null
            else LibraryEntry(EPISODES_ID, item.name, "Podcast", cover(item.images), isPlaylist = true)
        else -> null
    }

    private suspend fun libraryLevel(folderUri: String?, filter: LibraryFilter = LibraryFilter.ALL): List<app.myspoti.api.LibraryItem> {
        val out = ArrayList<app.myspoti.api.LibraryItem>()
        var offset = 0
        repeat(40) {
            val page = api.library.items(filter = filter, folderUri = folderUri, offset = offset, limit = 50)
            out += page.items
            offset = page.nextOffset ?: return out
        }
        return out
    }

    /** Flat library (Android Auto, pickers): Liked Songs, playlists, then albums. */
    suspend fun getLibrary(): Flow<Response<List<LibraryEntry>>> = flow {
        HomeCache.library?.let { emit(Response.Success(it)) } ?: emit(Response.Loading())
        if (!ready()) { if (HomeCache.library == null) emit(Response.Error(notSignedIn())); return@flow }
        runCatching {
            val playlists = api.playlists.mine().map(::playlistEntry)
            val albums = libraryLevel(null, LibraryFilter.ALBUMS).mapNotNull(::entryOf).filter { !it.isPlaylist }
            listOf(likedEntry()) + playlists + albums
        }.fold(
            onSuccess = { HomeCache.library = it; emit(Response.Success(it)) },
            onFailure = { Log.e("Api", "getLibrary failed", it); if (HomeCache.library == null) emit(Response.Error(it.message ?: "error")) },
        )
    }

    /** "Your Library" as Spotify shows it: Liked Songs, then folders, playlists and albums in the user's order. */
    suspend fun getLibraryRoot(): Flow<Response<List<LibraryEntry>>> = flow {
        if (HomeCache.libraryRoot == null) HomeCache.libraryRoot = withContext(Dispatchers.IO) { ScreenCache.libraryRoot.read() }
        HomeCache.libraryRoot?.let { emit(Response.Success(it)) } ?: emit(Response.Loading())
        if (!ready()) { if (HomeCache.libraryRoot == null) emit(Response.Error(notSignedIn())); return@flow }
        runCatching { listOf(likedEntry()) + libraryLevel(null).mapNotNull(::entryOf) }.fold(
            onSuccess = { HomeCache.libraryRoot = it; ScreenCache.libraryRoot.write(it); PagePrefetch.schedule(it); emit(Response.Success(it)) },
            onFailure = { Log.e("Api", "getLibraryRoot failed", it); if (HomeCache.libraryRoot == null) emit(Response.Error(it.message ?: "error")) },
        )
    }

    /** The folders and playlists inside one library folder. */
    suspend fun getLibraryFolder(folderUri: String): Flow<Response<List<LibraryEntry>>> = flow {
        HomeCache.folders[folderUri]?.let { emit(Response.Success(it)) } ?: emit(Response.Loading())
        if (!ready()) { if (HomeCache.folders[folderUri] == null) emit(Response.Error(notSignedIn())); return@flow }
        runCatching { libraryLevel(folderUri).mapNotNull(::entryOf) }.fold(
            onSuccess = { HomeCache.folders[folderUri] = it; emit(Response.Success(it)) },
            onFailure = { Log.e("Api", "getLibraryFolder failed", it); if (HomeCache.folders[folderUri] == null) emit(Response.Error(it.message ?: "error")) },
        )
    }

    /** The looping Canvas video for a track, cached per track (also when there is none). */
    suspend fun getCanvasUrl(trackId: String): String? {
        if (trackId.isBlank()) return null
        CanvasCache.map[trackId]?.let { return it.value }
        if (!ready()) return null
        val url = runCatching { api.extras.canvas("spotify:track:$trackId")?.videoUrl }.getOrNull()
        CanvasCache.map[trackId] = CanvasCache.Entry(url)
        return url
    }

    /** The Canvas already looked up for [trackId]: null when never asked, else its url (or none). */
    fun knownCanvas(trackId: String): Result<String?>? = CanvasCache.map[trackId]?.let { Result.success(it.value) }

    private object CanvasCache {
        class Entry(val value: String?)
        val map = java.util.concurrent.ConcurrentHashMap<String, Entry>()
    }

    /** Liked Songs as playable songs; also keeps the local hearts in step. */
    suspend fun getLikedSongs(): Flow<Response<List<SongsModel>>> = flow {
        emit(Response.Loading())
        if (!ready()) { emit(Response.Error(notSignedIn())); return@flow }
        val liked = runCatching {
            allLiked { emit(Response.Success(it)) }
        }
        if (liked.exceptionOrNull() is CancellationException) throw liked.exceptionOrNull()!!
        liked.fold(
            onSuccess = { songs ->
                SongPlayer.registerLikedSongs(songs.map { it.url })
                com.music.spotui.data.preferences.syncSpotifyLikedSongIds(context, songs.mapTo(HashSet()) { it.id.toString() }, complete = true)
                LikedCache.tracks = songs
                emit(Response.Success(songs))
            },
            onFailure = { emit(Response.Error(context.getString(R.string.couldnt_load, context.getString(R.string.collection_liked_songs_title)))) },
        )
    }

    /** The signed-in account (name, avatar, plan). Spotify does not give the e-mail to the Web Player. */
    suspend fun getAccount(): Flow<Response<com.music.spotui.data.entity.AccountModel>> = flow {
        emit(Response.Loading())
        if (!ready()) { emit(Response.Error(notSignedIn())); return@flow }
        runCatching { api.users.me() }.fold(
            onSuccess = { u ->
                emit(Response.Success(com.music.spotui.data.entity.AccountModel(
                    name = u.name, email = "", imageUrl = cover(u.images), plan = if (u.premium) "Premium" else "Free",
                )))
            },
            onFailure = { Log.e("Api", "getAccount failed", it); emit(Response.Error(it.message ?: "error")) },
        )
    }

    /** Playlist header by id. */
    suspend fun getPlaylist(playlistId: String): Flow<Response<AlbumsModel>> = flow {
        emit(Response.Loading())
        if (playlistId.isBlank()) { emit(Response.Error("missing playlist id")); return@flow }
        if (!ready()) { emit(Response.Error(notSignedIn())); return@flow }
        runCatching { api.playlists.playlist("spotify:playlist:$playlistId") }.fold(
            onSuccess = { p ->
                emit(Response.Success(AlbumsModel(
                    id = stableId("playlist:${p.uri}"), artists = p.owner.orEmpty(), coverUri = big(p.images),
                    name = p.name, time = stripHtml(p.description), spotifyId = playlistId,
                )))
            },
            onFailure = { Log.e("Api", "getPlaylist failed", it); emit(Response.Error(it.message ?: "error")) },
        )
    }

    /** Playlist descriptions come as HTML: keep the text and link labels. */
    private fun stripHtml(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        return androidx.core.text.HtmlCompat.fromHtml(raw, androidx.core.text.HtmlCompat.FROM_HTML_MODE_LEGACY).toString().trim()
    }

    /** Among same-named albums, prefer the exact name by the requested artist. */
    private fun pickAlbum(candidates: List<Album>, albumName: String, artist: String): Album? {
        if (candidates.isEmpty()) return null
        if (artist.isBlank()) return candidates.firstOrNull { it.name.equals(albumName, ignoreCase = true) } ?: candidates.first()
        val want = artist.split(",").map { it.trim().lowercase() }.filter { it.isNotBlank() }
        fun artistMatches(a: Album) = a.artists.joinToString(" ") { it.name }.lowercase().let { names -> want.any { names.contains(it) } }
        val nameMatches = candidates.filter { it.name.equals(albumName, ignoreCase = true) }
        return nameMatches.firstOrNull(::artistMatches) ?: candidates.firstOrNull(::artistMatches) ?: nameMatches.firstOrNull() ?: candidates.first()
    }
}

/** Songs per playlist page, pages asked for at once, and the most often a growing list is shown again. */
private const val PLAYLIST_PAGE = 100
private const val PLAYLIST_PARALLEL = 4
private const val PLAYLIST_EMIT_MS = 350L
/** A Liked Songs list read in full is trusted this long: only its newest page is read again. */
private const val LIKED_FULL_MS = 24 * 3_600_000L
/** Album pages kept on storage. */
private const val ALBUM_PAGES_KEPT = 30
