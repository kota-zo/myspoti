package com.music.spotui.data.offline

import android.content.Context
import android.util.Log
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshots.SnapshotStateMap
import com.music.spotui.data.api.Response
import com.music.spotui.data.entity.ContextPageModel
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.di.Graph
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A playlist, album or Liked Songs the user downloaded as a whole. */
data class OfflineCollection(
    val uri: String,
    val type: String,
    val name: String,
    val trackIds: Set<String>,
    val syncedAt: Long,
    /** The quality chosen when the list was first downloaded; its new songs follow it. */
    val quality: String = "",
)

/**
 * Lists downloaded as a whole stay in step with Spotify, like the official app: songs
 * added to them are downloaded, songs that left them (a Blend renewed, a song removed)
 * are deleted. A file stays on the phone as long as a downloaded list holds it or it was
 * downloaded on its own (from a song's menu).
 */
object OfflineCollections {
    private const val TAG = "OfflineLists"
    private const val SYNC_EVERY_MS = 6 * 3_600_000L
    private lateinit var file: File
    private val lock = Any()
    // A refresh that fails (no network, a page that cannot be read) is logged, never a crash of the app.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e -> Log.w(TAG, "refresh failed: ${e.javaClass.simpleName}: ${e.message}") })

    val collections: SnapshotStateMap<String, OfflineCollection> = mutableStateMapOf()
    /** Songs downloaded on their own, kept whatever the lists do. */
    private val singles = HashSet<String>()

    fun init(context: Context) {
        if (::file.isInitialized) return
        file = File(context.filesDir, "offline/collections.json")
        // Songs downloaded before lists existed belong to nothing: they stay until a list
        // that held them is downloaded again and drops them (e.g. a renewed Blend).
        runCatching { read(file.readText()) }
    }

    /** Reads the downloaded lists again (main thread): another account's downloads were put in place. */
    fun reopen() {
        synchronized(lock) { collections.clear(); singles.clear() }
        runCatching { read(file.readText()) }
    }

    fun isDownloaded(uri: String) = collections.containsKey(uri)

    /** A song still has a reason to stay on the phone. */
    fun isKept(trackId: String): Boolean = synchronized(lock) { trackId in singles } ||
        collections.values.any { trackId in it.trackIds }

    @Volatile private var wifiPending = false

    /** Automatic downloads were held back for Wi-Fi: the next Wi-Fi brings every list up to date. */
    fun waitForWifi() { wifiPending = true }

    /** The phone is on Wi-Fi (or another unmetered network) again. */
    fun onUnmetered(context: Context) {
        if (!wifiPending) return
        wifiPending = false
        refreshAll(context, force = true)
    }

    /** Download a song on its own. */
    fun downloadSong(song: SongsModel, quality: com.music.spotui.data.preferences.DownloadQuality? = null) {
        // Episodes have no track id and their own downloader (PodcastDownloader): sending one
        // here would only be dropped, or worse, hit the track download path.
        if (song.spotifyTrackId.isBlank() || episodeKeyOf(song) != null) return
        // Kept only when the download can start: otherwise it would show as downloaded with no file.
        if (!OfflineDownloader.ready()) return
        synchronized(lock) { singles += song.spotifyTrackId }
        save()
        OfflineDownloader.enqueue(listOf(song), quality)
    }

    /**
     * Remove a song downloaded on its own. Returns false when a downloaded list still
     * holds it (the file stays).
     */
    fun removeSong(trackId: String): Boolean {
        synchronized(lock) { singles -= trackId }
        save()
        if (isKept(trackId)) return false
        OfflineDownloader.remove(trackId)
        return true
    }

    /** Download a list as a whole, and keep it in step from now on. */
    fun download(page: ContextPageModel, quality: com.music.spotui.data.preferences.DownloadQuality? = null) {
        // An episode in a list is not a track: it stays out of the collection, and the
        // list download never enqueues it (its own menu row is the way to download an episode).
        val ids = page.tracks.map { it.spotifyTrackId }.filter { it.isNotBlank() && !isEpisodeKey(it) }.toSet()
        // Marked downloaded only when the download can start (downloads on, a download extension).
        if (!OfflineDownloader.ready()) return
        collections[page.uri] = OfflineCollection(page.uri, page.type, page.name, ids, System.currentTimeMillis(), quality?.name.orEmpty())
        save()
        keepPage(page)
        OfflineDownloader.enqueue(page.tracks, quality)
    }

    /** Stop keeping a list: its songs go, unless another reason keeps them. */
    fun remove(uri: String) {
        val c = collections.remove(uri) ?: return
        save()
        com.music.spotui.data.api.ScreenCache.dropOfflinePage(uri.substringAfterLast(':'))
        c.trackIds.filterNot { isKept(it) }.forEach { OfflineDownloader.remove(it) }
    }

    fun removeAll() {
        collections.clear()
        synchronized(lock) { singles.clear() }
        save()
    }

    /**
     * The list's current songs (from a complete page): new ones are downloaded, those
     * that left it are deleted unless something else keeps them.
     */
    fun sync(page: ContextPageModel) {
        val c = collections[page.uri] ?: return
        if (!page.complete) return
        // Episodes are never part of a downloaded list: they have their own queue and their
        // downloaded copies are kept by their own key, not by the list that shows them.
        val songs = page.tracks.filter { it.spotifyTrackId.isNotBlank() && !isEpisodeKey(it.spotifyTrackId) }
        val ids = songs.map { it.spotifyTrackId }.toSet()
        val gone = c.trackIds - ids
        collections[page.uri] = c.copy(name = page.name, trackIds = ids, syncedAt = System.currentTimeMillis())
        save()
        keepPage(page)
        val missing = songs.filter { !OfflineStore.isDownloaded(it.spotifyTrackId) && OfflineStore.status[it.spotifyTrackId] == null }
        if (missing.isNotEmpty()) OfflineDownloader.enqueue(missing, com.music.spotui.data.preferences.DownloadQuality.saved(c.quality), auto = true)
        gone.filterNot { isKept(it) || isEpisodeKey(it) }.forEach { OfflineDownloader.remove(it) }
        if (missing.isNotEmpty() || gone.isNotEmpty()) Log.i(TAG, "${page.name}: +${missing.size} -${gone.size}")
    }

    /** The list's page as it is now, for opening it offline ([offlinePage]). */
    private fun keepPage(page: ContextPageModel) {
        if (page.complete) com.music.spotui.data.api.ScreenCache.offlinePage(page.uri.substringAfterLast(':')).write(page)
    }

    /**
     * The downloaded list at [uri] as a page, for offline: the one kept when it was downloaded or
     * last synced, else (a list downloaded before pages were kept) its downloaded songs. Blocks.
     */
    fun offlinePage(uri: String): ContextPageModel? {
        val c = collections[uri] ?: return null
        com.music.spotui.data.api.ScreenCache.offlinePage(uri.substringAfterLast(':')).read()?.let { return it }
        val songs = c.trackIds.mapNotNull { id -> OfflineStore.tracks[id]?.takeIf { OfflineStore.isDownloaded(id) }?.let(OfflineStore::toSong) }
        if (songs.isEmpty()) return null
        return ContextPageModel(uri = uri, type = c.type, name = c.name, coverUri = songs.first().coverUri, tracks = songs)
    }

    /** Brings every downloaded list up to date (at start, at most every 6 hours, online only). */
    fun refreshAll(context: Context, force: Boolean = false) {
        scope.launch {
            delay(15_000)
            if (!OfflineStore.isOnline(context)) return@launch
            // On mobile data the lists are not even read: the sync waits for Wi-Fi.
            if (com.music.spotui.data.DataSaver.metered) { waitForWifi(); return@launch }
            val due = collections.values.filter { force || System.currentTimeMillis() - it.syncedAt > SYNC_EVERY_MS }
            for (c in due) {
                // One list that fails does not stop the others.
                try {
                    val page = withTimeoutOrNull(60_000) { loadComplete(c) } ?: continue
                    withContext(Dispatchers.Main) { sync(page) }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    Log.w(TAG, "${c.name} not refreshed: ${e.javaClass.simpleName}: ${e.message}")
                }
            }
        }
    }

    /**
     * One downloaded list changed from the app (a song added to it): read again and synced at
     * once, so the new song is on the phone without opening the list (Wi-Fi rule as above).
     */
    fun refreshOne(context: Context, uri: String) {
        val c = collections[uri] ?: return
        scope.launch {
            delay(1_500) // Spotify's own copy of the list catches up first.
            if (!OfflineStore.isOnline(context)) return@launch
            if (com.music.spotui.data.DataSaver.metered) { waitForWifi(); return@launch }
            val page = withTimeoutOrNull(60_000) { loadComplete(c) } ?: return@launch
            withContext(Dispatchers.Main) { sync(page) }
        }
    }

    private suspend fun loadComplete(c: OfflineCollection): ContextPageModel? {
        val api = Graph.api
        val id = c.uri.substringAfterLast(":")
        val flow = when (c.type) {
            "album" -> api.getAlbumPage(id, stored = false)
            "liked" -> api.getLikedPage()
            else -> api.getPlaylistPage(id)
        }
        // A flow that ends without a complete page (the player never ready) is no page, not an error.
        return (flow.firstOrNull { it is Response.Error || (it is Response.Success && it.data.complete) } as? Response.Success)?.data
    }

    private fun save() {
        if (!::file.isInitialized) return
        val text = synchronized(lock) {
            val lists = JSONArray()
            collections.values.forEach { c ->
                lists.put(JSONObject().put("uri", c.uri).put("type", c.type).put("name", c.name)
                    .put("synced", c.syncedAt).put("tracks", JSONArray(c.trackIds.toList())).put("quality", c.quality))
            }
            JSONObject().put("version", 1).put("lists", lists).put("singles", JSONArray(singles.toList())).toString()
        }
        runCatching { com.music.spotui.data.writeTextSafely(file, text) }
            .onFailure { app.myspoti.host.android.Logbook.log("offline: downloaded lists not saved: ${it.message}") }
    }

    private fun read(text: String) {
        val o = JSONObject(text)
        val lists = o.optJSONArray("lists") ?: JSONArray()
        for (i in 0 until lists.length()) {
            val c = lists.getJSONObject(i)
            val tracks = c.optJSONArray("tracks") ?: JSONArray()
            collections[c.getString("uri")] = OfflineCollection(
                c.getString("uri"), c.optString("type"), c.optString("name"),
                (0 until tracks.length()).map { tracks.getString(it) }.toSet(), c.optLong("synced"), c.optString("quality"),
            )
        }
        val s = o.optJSONArray("singles") ?: JSONArray()
        synchronized(lock) { (0 until s.length()).forEach { singles += s.getString(it) } }
    }
}
