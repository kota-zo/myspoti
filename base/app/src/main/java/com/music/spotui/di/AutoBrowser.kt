package com.music.spotui.di

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat.MediaItem
import android.support.v4.media.MediaDescriptionCompat
import app.myspoti.engine.R
import app.myspoti.host.android.MyspotiRuntime
import com.music.spotui.data.api.Response
import com.music.spotui.data.entity.ContextPageModel
import com.music.spotui.data.entity.HomeItem
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.data.offline.LocalPlayPlan
import com.music.spotui.data.offline.OfflineCollections
import com.music.spotui.data.offline.OfflineStore
import com.music.spotui.data.offline.OfflineTrack
import app.myspoti.api.MediaKind
import app.myspoti.api.best
import app.myspoti.api.groupRecents
import com.music.spotui.data.DataSaver
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The hidden player did not come up in time (cold start, no network). The browse answer is
 * an empty folder then — MediaBrowserCompat has no error reply — and the log says why.
 */
class AudioNotReady(what: String) : IllegalStateException("player not ready: $what")

/**
 * Android Auto / media browser tree, built from the app's own data (not the web page).
 *
 * Root tabs (Auto allows four): Home (recently played, then the home feed's shelves, each a titled
 * group), Library (Liked Songs, playlists, albums, artists), Downloads (served from the files,
 * no network needed). Search answers songs, playlists, albums and artists as titled groups.
 * Tapping a song plays it in its context, the way the album / playlist screens do: a downloaded
 * song plays the local copy (see [playTrack]). "Start radio" items start Spotify's radio.
 *
 * Media ids: "home", "library", "downloads" (tabs); "liked", "playlists", "albums", "artists",
 * "dsongs" (lists); "pl:<id>", "al:<id>", "ar:<id>|<name>", "dl:<list uri>" (contexts);
 * "t|<context uri>|<play key>" (a song), "d|<list uri or empty>|<track id>" (a downloaded song),
 * "radio|<seed uri>|<fallback song uri>" (start a radio).
 */
object AutoBrowser {
    const val ROOT = "root"
    private const val TIMEOUT_MS = 15_000L
    /**
     * The most one folder lists. A library of thousands of liked songs, each with a cover the car
     * fetches through [ArtworkProvider] (a binder thread held per image), is what froze Android
     * Auto's screen; the car shows no more than a page of a list while driving anyway.
     */
    private const val MAX_ITEMS = 300
    /** Cold start: the car binds before the hidden Web Player is up; browse waits this long. */
    private const val READY_WAIT_MS = 25_000L
    /** Home: how many recents, shelves and items per shelf (the car pages through a long tab slowly). */
    private const val HOME_RECENTS = 8
    private const val HOME_SHELVES = 6
    private const val HOME_PER_SHELF = 10

    private const val STYLE_LIST = 1
    private const val STYLE_GRID = 2
    private const val KEY_BROWSABLE = "android.media.browse.CONTENT_STYLE_BROWSABLE_HINT"
    private const val KEY_PLAYABLE = "android.media.browse.CONTENT_STYLE_PLAYABLE_HINT"
    private const val KEY_GROUP = "android.media.browse.CONTENT_STYLE_GROUP_TITLE_HINT"

    // Contexts recently listed, so a tap plays with the right queue.
    private val pages = java.util.concurrent.ConcurrentHashMap<String, ContextPageModel>()
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)

    /**
     * Browse for [parentId]. The tabs, the Library list and the Downloads answer at once (no
     * session, no network); every other node needs the session, and waits for it instead of
     * returning an empty list, which Android Auto keeps as the folder's content until it
     * unsubscribes (issue #41, cold start).
     */
    suspend fun children(context: Context, parentId: String): List<MediaItem> {
        when {
            parentId == ROOT -> return listOf(
                tab(context, "home", R.string.aa_home, "aa_ic_home", STYLE_GRID),
                tab(context, "library", R.string.aa_library, "aa_ic_library", STYLE_LIST),
                tab(context, "downloads", R.string.aa_downloads, "aa_ic_downloads", STYLE_LIST),
            )
            parentId == "library" -> return libraryTab(context)
            parentId == "downloads" -> return downloadsTab(context)
            parentId == "dsongs" -> return downloadedSongs("")
            parentId.startsWith("dl:") -> return downloadedSongs(parentId.removePrefix("dl:"))
        }
        // No connection: a downloaded list opens from its files, the web page is not needed.
        if (!OfflineStore.isOnline(context)) offlineContext(parentId)?.let { return it }
        if (!awaitPlayer()) throw AudioNotReady("browse $parentId")
        return when {
            parentId == "home" -> homeTab(context)
            parentId == "playlists" || parentId == "albums" -> {
                val wantPlaylists = parentId == "playlists"
                val entries = (firstResult { Graph.api.getLibrary() } ?: emptyList())
                    .filter { it.isPlaylist == wantPlaylists && it.spotifyId != com.music.spotui.data.api.Api.HomeCache.LIKED_SONGS_ID }
                entries.take(MAX_ITEMS).map { e ->
                    browsable(if (wantPlaylists) "pl:${e.spotifyId}" else "al:${e.spotifyId}", e.name, art(e.coverUri), subtitle = e.subtitle)
                }
            }
            parentId == "artists" -> Graph.api.getFollowedArtists().filter { it.id.isNotBlank() }.take(MAX_ITEMS).map { a ->
                browsable("ar:${a.id}|${a.name}", a.name, art(a.coverUri))
            }
            parentId == "liked" -> tracksOf(context, firstResult { Graph.api.getLikedPage() })
            parentId.startsWith("pl:") -> tracksOf(context, firstResult { Graph.api.getPlaylistPage(parentId.removePrefix("pl:")) })
            parentId.startsWith("al:") -> tracksOf(context, firstResult { Graph.api.getAlbumPage(parentId.removePrefix("al:")) })
            parentId.startsWith("ar:") -> {
                val (id, name) = parentId.removePrefix("ar:").split("|", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
                val overview = firstResult { Graph.api.getArtistOverview(name, id) } ?: return emptyList()
                val songs = context.getString(R.string.aa_songs)
                listOf(radioItem(context, "spotify:artist:$id", "", name)) +
                    overview.topTracks.map { playable("spotify:artist:$id", it.song, songs) } +
                    overview.popularReleases.filter { it.spotifyId.isNotBlank() }.take(12).map {
                        browsable("al:${it.spotifyId}", it.name, art(it.coverUri), subtitle = it.year, group = context.getString(R.string.aa_albums))
                    }
            }
            else -> emptyList()
        }
    }

    // ── Tabs ──

    private fun tab(context: Context, id: String, title: Int, icon: String, style: Int): MediaItem {
        val d = MediaDescriptionCompat.Builder().setMediaId(id).setTitle(context.getString(title))
            .setIconUri(Uri.parse("android.resource://${context.packageName}/drawable/$icon"))
            .setExtras(hints(style, null)).build()
        return MediaItem(d, MediaItem.FLAG_BROWSABLE)
    }

    private fun libraryTab(context: Context): List<MediaItem> = listOf(
        browsable("liked", context.getString(R.string.collection_liked_songs_title), resource(context, "aa_ic_liked"), grid = false),
        browsable("playlists", context.getString(R.string.aa_playlists), resource(context, "aa_ic_playlists"), grid = false),
        browsable("albums", context.getString(R.string.aa_albums), resource(context, "aa_ic_albums"), grid = false),
        browsable("artists", context.getString(R.string.aa_artists), resource(context, "aa_ic_artists"), grid = false),
    )

    /** Recently played, then the home feed's shelves (mixes, jump back in, …), each a titled group. */
    private suspend fun homeTab(context: Context): List<MediaItem> = kotlinx.coroutines.coroutineScope {
        val recents = async { runCatching { recentItems(context) }.getOrDefault(emptyList()) }
        val feed = async { runCatching { firstResult { Graph.api.getHomeFeed() } }.getOrNull() }
        val out = ArrayList<MediaItem>(recents.await())
        feed.await()?.sections?.take(HOME_SHELVES)?.forEach { section ->
            val title = section.title.ifBlank { context.getString(R.string.aa_home) }
            section.items.mapNotNull { homeItem(it, title) }.take(HOME_PER_SHELF).let(out::addAll)
        }
        out
    }

    private fun homeItem(item: HomeItem, group: String): MediaItem? = when (item) {
        is HomeItem.Playlist -> browsable(
            if (item.id == com.music.spotui.data.api.Api.HomeCache.LIKED_SONGS_ID) "liked" else "pl:${item.id}",
            item.name, art(item.imageUrl), group = group,
        ).takeIf { item.id.isNotBlank() }
        is HomeItem.Album -> browsable("al:${item.id}", item.name, art(item.imageUrl), subtitle = item.artists, group = group).takeIf { item.id.isNotBlank() }
        is HomeItem.Artist -> browsable("ar:${item.id}|${item.name}", item.name, art(item.imageUrl), group = group).takeIf { item.id.isNotBlank() }
        else -> null
    }

    /** The account's recents (all devices): playlists, albums, artists and Liked Songs, as a group. */
    private suspend fun recentItems(context: Context): List<MediaItem> {
        val entries = withTimeoutOrNull(TIMEOUT_MS) { MyspotiRuntime.api.recents.recentEntries(0, 50).items } ?: return emptyList()
        val title = context.getString(R.string.aa_recent)
        val seen = HashSet<String>()
        return groupRecents(entries).flatMap { it.groups }.filter { it.music }.mapNotNull { g ->
            val c = g.context
            val id = c.uri.substringAfterLast(':')
            val mediaId = when {
                c.kind == MediaKind.PLAYLIST -> "pl:$id"
                c.kind == MediaKind.ALBUM -> "al:$id"
                c.kind == MediaKind.ARTIST -> "ar:$id|${c.title}"
                c.kind == MediaKind.COLLECTION && c.uri == "spotify:collection:tracks" -> "liked"
                else -> return@mapNotNull null
            }
            if (id.isBlank() || !seen.add(mediaId)) return@mapNotNull null
            browsable(mediaId, c.title, art(c.images.best(300)?.url), subtitle = c.subtitle.orEmpty(), group = title)
        }.take(HOME_RECENTS)
    }

    // ── Downloads (from the files, no session) ──

    private fun downloadsTab(context: Context): List<MediaItem> {
        val out = ArrayList<MediaItem>()
        val songs = OfflineStore.list()
        if (songs.isNotEmpty()) out += browsable(
            "dsongs", context.getString(R.string.aa_downloaded_songs), downloadedCover(context, songs.first()),
            subtitle = songs.size.toString(), grid = false,
        )
        val lists = context.getString(R.string.aa_downloaded_lists)
        OfflineCollections.collections.values.sortedBy { it.name.lowercase() }.take(MAX_ITEMS).forEach { c ->
            val first = c.trackIds.firstNotNullOfOrNull { OfflineStore.tracks[it] }
            out += browsable("dl:${c.uri}", c.name, first?.let { downloadedCover(context, it) }, group = lists)
        }
        return out
    }

    /** The downloaded songs of the list at [listUri] (all of them when empty), as local-play items. */
    private fun downloadedSongs(listUri: String): List<MediaItem> =
        downloadedIds(listUri).take(MAX_ITEMS).mapNotNull { OfflineStore.tracks[it] }.map { t ->
            val d = MediaDescriptionCompat.Builder().setMediaId("d|$listUri|${t.trackId}")
                .setTitle(t.title).setSubtitle(t.artist).setIconUri(ArtworkProvider.uriForDownloaded(t))
                .setExtras(hints(STYLE_LIST, null)).build()
            MediaItem(d, MediaItem.FLAG_PLAYABLE)
        }

    /** Track ids of the downloaded songs of a list in its order, or of every download (newest first). */
    private fun downloadedIds(listUri: String): List<String> {
        if (listUri.isBlank()) return OfflineStore.list().map { it.trackId }
        val c = OfflineCollections.collections[listUri] ?: return emptyList()
        val ordered = OfflineCollections.offlinePage(listUri)?.tracks?.map { it.spotifyTrackId.ifBlank { it.url } }.orEmpty()
        return (ordered + c.trackIds).distinct().filter(OfflineStore::isDownloaded)
    }

    private fun downloadedCover(context: Context, t: OfflineTrack): Uri? = ArtworkProvider.uriForDownloaded(t)

    /** Offline, a downloaded list (or Liked Songs) opens from its stored page; null when it was never downloaded. */
    private fun offlineContext(parentId: String): List<MediaItem>? {
        val uri = when {
            parentId == "liked" -> "spotify:collection:tracks"
            parentId.startsWith("pl:") -> "spotify:playlist:${parentId.removePrefix("pl:")}"
            parentId.startsWith("al:") -> "spotify:album:${parentId.removePrefix("al:")}"
            else -> return null
        }
        if (!OfflineCollections.isDownloaded(uri)) return null
        return downloadedSongs(uri)
    }

    // ── Search ──

    suspend fun search(query: String): List<MediaItem> {
        if (!awaitPlayer()) throw AudioNotReady("search")
        val r = firstResult { Graph.api.searchEverything(query) } ?: return emptyList()
        val context = Graph.app
        val songs = r.songs.take(10)
        val out = ArrayList<MediaItem>()
        songs.firstOrNull()?.let { out += radioItem(context, it.url, it.url, it.title) }
        val songsTitle = context.getString(R.string.aa_songs)
        songs.forEach { out += playable("", it, songsTitle) }
        val playlists = context.getString(R.string.aa_playlists)
        r.playlists.filter { it.spotifyId.isNotBlank() && !it.isFolder }.take(8).forEach {
            out += browsable("pl:${it.spotifyId}", it.name, art(it.coverUri), subtitle = it.subtitle, group = playlists)
        }
        val albums = context.getString(R.string.aa_albums)
        r.albums.filter { it.spotifyId.isNotBlank() }.take(8).forEach {
            out += browsable("al:${it.spotifyId}", it.name, art(it.coverUri), subtitle = it.artists, group = albums)
        }
        val artists = context.getString(R.string.aa_artists)
        r.artists.filter { it.id.isNotBlank() }.take(8).forEach {
            out += browsable("ar:${it.id}|${it.name}", it.name, art(it.coverUri), group = artists)
        }
        return out
    }

    /** Voice search ("play X on Myspoti"): the best matching track. */
    suspend fun playFromSearch(query: String): Boolean {
        // A cold start asks before the page is up: waiting gives the answer, returning at
        // once would tell the driver nothing matched.
        if (!awaitPlayer()) {
            android.util.Log.w("Myspoti", "voice search dropped: the player page is not ready")
            return false
        }
        val song = (firstResult { Graph.api.searchEverything(query) })?.songs?.firstOrNull() ?: return false
        play("t||${song.url}")
        return true
    }

    /** [MyspotiRuntime.api.ready], waited for (bounded) — the page loads after the car connects. */
    private suspend fun awaitPlayer(): Boolean =
        MyspotiRuntime.api.ready.value ||
            withTimeoutOrNull(READY_WAIT_MS) { MyspotiRuntime.api.ready.first { it } } != null

    // ── Playing ──

    fun play(mediaId: String) {
        val parts = mediaId.split("|", limit = 3)
        if (parts.size < 3) return
        when (parts[0]) {
            "t" -> playTrack(parts[1].ifBlank { null }, parts[2])
            "d" -> playDownloaded(parts[1], parts[2])
            "radio" -> startRadio(parts[1], parts[2])
        }
    }

    private fun onMain(block: () -> Unit) { android.os.Handler(android.os.Looper.getMainLooper()).post(block) }

    private fun playTrack(contextUri: String?, key: String) {
        val page = contextUri?.let { pages[it] }
        val song = page?.tracks?.firstOrNull { it.url == key }
        // A downloaded song plays its file, not the stream (the phone's rule, see LocalPlayPlan):
        // offline or on the data saver the list's downloaded songs, online the whole list when it
        // is all downloaded; a lone downloaded song (search) always.
        val store = OfflineStore
        val id = song?.spotifyTrackId?.ifBlank { null } ?: key.removePrefix("spotify:track:")
        val ids = if (page != null) LocalPlayPlan.localIds(
            page.tracks.map { it.spotifyTrackId.ifBlank { it.url } }, id, page.complete,
            offline = !store.isOnline(Graph.app), dataSaver = DataSaver.active, isDownloaded = store::isDownloaded,
        ) else if (store.isDownloaded(id)) listOf(id) else null
        if (ids != null) {
            val local = ids.mapNotNull { store.tracks[it] }
            val at = local.indexOfFirst { it.trackId == id }
            if (at >= 0) {
                onMain { LocalPlayer.play(local.map(store::toLocalTrack), at, shuffle = Graph.currentSongState.shuffle.value, context = page?.name ?: local[at].title, contextUri = page?.uri.orEmpty()) }
                return
            }
        }
        if (page != null) {
            Graph.currentSongState.updateQueue(page.tracks)
            if (page.type == "liked") SongPlayer.registerLikedSongs(page.tracks.map { it.url })
        }
        SongPlayer.playSong(key, contextUri = contextUri)
        song?.let { Graph.currentSongState.updateSongState(it.coverUri, it.title, it.singer, true, it.id, 0, it.album) }
    }

    /** The Downloads tab: always the files, from the tapped song on. */
    private fun playDownloaded(listUri: String, trackId: String) {
        val store = OfflineStore
        val local = downloadedIds(listUri).mapNotNull { store.tracks[it] }
        val at = local.indexOfFirst { it.trackId == trackId }
        if (at < 0) return
        val name = OfflineCollections.collections[listUri]?.name ?: Graph.app.getString(R.string.aa_downloaded_songs)
        onMain { LocalPlayer.play(local.map(store::toLocalTrack), at, shuffle = Graph.currentSongState.shuffle.value, context = name, contextUri = listUri) }
    }

    /**
     * "Start radio": Spotify's radio playlist for the song / artist / list (the phone's "Go to song
     * radio" opens the same playlist), played from its first song. A list that has no radio of its
     * own falls back to the radio of [fallbackSong].
     */
    private fun startRadio(seed: String, fallbackSong: String) {
        scope.launch {
            val id = Graph.api.radioPlaylistId(seed)
                ?: fallbackSong.takeIf { it.startsWith("spotify:track:") }?.let { Graph.api.radioPlaylistId(it) }
                ?: return@launch
            val page = firstResult { Graph.api.getPlaylistPage(id) }?.takeIf { it.tracks.isNotEmpty() } ?: return@launch
            pages[page.uri] = page
            play("t|${page.uri}|${page.tracks.first().url}")
        }
    }

    // ── Items ──

    private fun tracksOf(context: Context, page: ContextPageModel?): List<MediaItem> {
        page ?: return emptyList()
        pages[page.uri] = page
        val first = page.tracks.firstOrNull()
        val songs = context.getString(R.string.aa_songs)
        val radio = if (first == null) emptyList() else listOf(radioItem(context, page.uri.takeIf { page.type != "liked" } ?: first.url, first.url, page.name))
        return radio + page.tracks.take(MAX_ITEMS).map { playable(page.uri, it, songs) }
    }

    private suspend fun <T> firstResult(load: suspend () -> kotlinx.coroutines.flow.Flow<Response<T>>): T? =
        withTimeoutOrNull(TIMEOUT_MS) {
            (load().first { it !is Response.Loading } as? Response.Success)?.data
        }

    private fun art(url: String?): Uri? = ArtworkProvider.uriFor(url)

    private fun resource(context: Context, name: String): Uri = Uri.parse("android.resource://${context.packageName}/drawable/$name")

    private fun hints(style: Int, group: String?) = Bundle().apply {
        putInt(KEY_BROWSABLE, style)
        putInt(KEY_PLAYABLE, STYLE_LIST)
        if (group != null) putString(KEY_GROUP, group)
    }

    private fun browsable(id: String, title: String, icon: Uri?, subtitle: String = "", grid: Boolean = true, group: String? = null): MediaItem {
        val d = MediaDescriptionCompat.Builder().setMediaId(id).setTitle(title).setSubtitle(subtitle)
            .setIconUri(icon).setExtras(hints(if (grid) STYLE_GRID else STYLE_LIST, group)).build()
        return MediaItem(d, MediaItem.FLAG_BROWSABLE)
    }

    private fun playable(contextUri: String, s: SongsModel, group: String? = null): MediaItem {
        val d = MediaDescriptionCompat.Builder().setMediaId("t|$contextUri|${s.url}")
            .setTitle(s.title).setSubtitle(s.singer).setIconUri(art(s.coverUri)).setExtras(hints(STYLE_LIST, group)).build()
        return MediaItem(d, MediaItem.FLAG_PLAYABLE)
    }

    private fun radioItem(context: Context, seed: String, fallbackSong: String, name: String): MediaItem {
        val d = MediaDescriptionCompat.Builder().setMediaId("radio|$seed|$fallbackSong")
            .setTitle(context.getString(R.string.aa_start_radio)).setSubtitle(name)
            .setIconUri(resource(context, "aa_ic_radio")).setExtras(hints(STYLE_LIST, null)).build()
        return MediaItem(d, MediaItem.FLAG_PLAYABLE)
    }
}

/**
 * Cover art for Android Auto, which only shows local (content://) artwork: serves an
 * image from Spotify's image CDN through Glide's disk cache. Other hosts are refused.
 */
class ArtworkProvider : android.content.ContentProvider() {
    companion object {
        private val AUTHORITY = app.myspoti.engine.BuildConfig.APPLICATION_ID + ".artwork"
        private val allowedHosts = setOf("i.scdn.co", "mosaic.scdn.co", "image-cdn-ak.spotifycdn.com", "image-cdn-fa.spotifycdn.com", "thisis-images.spotifycdn.com", "seeded-session-images.scdn.co", "pickasso.spotifycdn.com")

        /** A downloaded song's cover: its file (the car is offline), else the CDN image. */
        fun uriForDownloaded(t: com.music.spotui.data.offline.OfflineTrack): Uri? {
            if (t.cover.isNotBlank() && java.io.File(com.music.spotui.data.offline.OfflineStore.coverDir, t.cover).isFile)
                return Uri.Builder().scheme("content").authority(AUTHORITY).appendPath("cover").appendQueryParameter("f", t.cover).build()
            return uriFor(t.coverUrl)
        }

        fun uriFor(url: String?): Uri? {
            if (url.isNullOrBlank() || !allowed(url)) return null
            return Uri.Builder().scheme("content").authority(AUTHORITY).appendPath("img").appendQueryParameter("u", url).build()
        }

        /**
         * Only https URLs on the CDN hosts: a `file:` URL whose authority happens to be
         * an allowed host (`file://i.scdn.co/data/data/...`) must never reach Glide,
         * which would open the local path and hand this app's private files to any caller
         * (the provider is exported, for Android Auto).
         */
        private fun allowed(url: String): Boolean {
            val u = Uri.parse(url)
            return u.scheme == "https" && u.host in allowedHosts
        }
    }

    override fun openFile(uri: Uri, mode: String): android.os.ParcelFileDescriptor? {
        // A downloaded song's cover: only a file that a download of the store names, never a path.
        uri.getQueryParameter("f")?.let { name ->
            val file = com.music.spotui.data.offline.OfflineStore.tracks.values.firstOrNull { it.cover == name }
                ?.let(com.music.spotui.data.offline.OfflineStore::coverFile) ?: return null
            return android.os.ParcelFileDescriptor.open(file, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
        }
        val url = uri.getQueryParameter("u") ?: return null
        if (!allowed(url)) return null
        // Bounded: a binder thread must not wait on the network forever.
        val file = runCatching {
            com.bumptech.glide.Glide.with(context!!).asFile().load(url).submit()
                .get(15, java.util.concurrent.TimeUnit.SECONDS)
        }.getOrNull() ?: return null
        return android.os.ParcelFileDescriptor.open(file, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri) = "image/jpeg"
    override fun onCreate() = true
    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?) = null
    override fun insert(uri: Uri, values: android.content.ContentValues?) = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(uri: Uri, v: android.content.ContentValues?, s: String?, a: Array<out String>?) = 0
}
