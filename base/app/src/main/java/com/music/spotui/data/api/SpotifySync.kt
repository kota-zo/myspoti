package com.music.spotui.data.api

import android.content.Context
import android.util.Log
import app.myspoti.host.android.MyspotiRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import app.myspoti.engine.R
import com.music.spotui.data.entity.LibraryEntry
import java.util.concurrent.ConcurrentHashMap

/**
 * Library and playlist edits on the user's Spotify account. Failures are logged and
 * reported through the callbacks; the UI has already shown the change.
 */
object SpotifySync {
    private const val TAG = "SpotifySync"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val api get() = MyspotiRuntime.api

    fun addTrackToPlaylist(context: Context, playlistId: String, trackId: String, onDone: (Boolean) -> Unit = {}) {
        if (playlistId.isBlank() || trackId.isBlank()) { onDone(false); return }
        scope.launch {
            val ok = runCatching { api.playlists.addTracks("spotify:playlist:$playlistId", listOf("spotify:track:$trackId")) }
                .onFailure { Log.w(TAG, "add $trackId to $playlistId failed: ${it.message}") }.isSuccess
            if (ok) { membershipCache[playlistId]?.add(trackId); Api.invalidatePlaylist(playlistId) }
            if (ok) com.music.spotui.data.offline.OfflineCollections.refreshOne(context.applicationContext, "spotify:playlist:$playlistId")
            onDone(ok)
        }
    }

    /**
     * Adds many songs at once (a whole album or playlist, read beforehand) at the bottom of one of
     * the user's playlists, in 100-song requests. Run in the app's scope so closing the sheet
     * does not cancel it. [onDone] gets whether Spotify took all of it.
     */
    fun addUrisToPlaylist(context: Context, playlistId: String, uris: List<String>, onDone: (Boolean) -> Unit = {}) {
        if (playlistId.isBlank() || uris.isEmpty()) { onDone(false); return }
        scope.launch {
            val ok = runCatching { api.playlists.addTracks("spotify:playlist:$playlistId", uris) }
                .onFailure { Log.w(TAG, "add ${uris.size} songs to $playlistId failed: ${it.message}") }.isSuccess
            // Part of it may be in even when refused: read the playlist again when asked.
            membershipCache.remove(playlistId); Api.invalidatePlaylist(playlistId)
            com.music.spotui.data.offline.OfflineCollections.refreshOne(context.applicationContext, "spotify:playlist:$playlistId")
            onDone(ok)
        }
    }

    /**
     * Takes one row out of a playlist, named by its row id (not by its song: the same song twice
     * in a playlist is two rows, and removing "the song" took both). Without a row id (an old
     * cache) the first row of that song goes, never all of them.
     */
    fun removeRowFromPlaylist(context: Context, playlistId: String, trackId: String, rowUid: String, onDone: (Boolean) -> Unit = {}) {
        if (playlistId.isBlank() || (trackId.isBlank() && rowUid.isBlank())) { onDone(false); return }
        scope.launch {
            val ok = runCatching {
                val uri = "spotify:playlist:$playlistId"
                val uid = rowUid.ifBlank { allTracks(uri).firstOrNull { it.uri == "spotify:track:$trackId" }?.uid }
                checkNotNull(uid) { "track not in playlist" }
                api.playlists.removeTracks(uri, listOf(uid))
            }.onFailure { Log.w(TAG, "remove row $rowUid ($trackId) from $playlistId failed: ${it.message}") }.isSuccess
            // The song may still be in the playlist through another row: read it again when asked.
            if (ok) { membershipCache.remove(playlistId); Api.invalidatePlaylist(playlistId) }
            onDone(ok)
        }
    }

    /**
     * Removes every occurrence of a track from one of the user's playlists: the "Saved in"
     * sheet's switch means "this song is not in that playlist any more".
     */
    fun removeTrackFromPlaylist(context: Context, playlistId: String, trackId: String, onDone: (Boolean) -> Unit = {}) {
        if (playlistId.isBlank() || trackId.isBlank()) { onDone(false); return }
        scope.launch {
            val ok = runCatching {
                val uri = "spotify:playlist:$playlistId"
                val uids = allTracks(uri).filter { it.uri == "spotify:track:$trackId" }.mapNotNull { it.uid }
                check(uids.isNotEmpty()) { "track not in playlist" }
                api.playlists.removeTracks(uri, uids)
            }.onFailure { Log.w(TAG, "remove $trackId from $playlistId failed: ${it.message}") }.isSuccess
            if (ok) { membershipCache[playlistId]?.remove(trackId); Api.invalidatePlaylist(playlistId) }
            onDone(ok)
        }
    }

    // Visibility taps are written one at a time, in the order they were made.
    private val visibilityWrites = Mutex()

    /** Who may see or change a playlist (public, private, collaborative), as chosen. [onDone] gets whether Spotify took it. */
    fun setPlaylistAccess(uri: String, access: app.myspoti.api.PlaylistAccess, onDone: (Boolean) -> Unit = {}) {
        scope.launch {
            val ok = visibilityWrites.withLock {
                runCatching { api.playlists.setAccess(uri, access) }
                    .onFailure { Log.w(TAG, "access of $uri to $access refused: ${it.message}") }.isSuccess
            }
            onDone(ok)
        }
    }

    /** Stops a collaboration (owner): collaborators become listeners (private) or leave (public). */
    fun stopPlaylistCollaboration(uri: String, onDone: (Boolean) -> Unit = {}) {
        scope.launch {
            val ok = visibilityWrites.withLock {
                runCatching { api.playlists.stopCollaboration(uri) }
                    .onFailure { Log.w(TAG, "stopping the collaboration on $uri refused: ${it.message}") }.isSuccess
            }
            onDone(ok)
        }
    }

    /** Whether the playlist is listed on the user's profile. */
    fun setPlaylistPublished(uri: String, published: Boolean, onDone: (Boolean) -> Unit = {}) {
        scope.launch {
            val ok = visibilityWrites.withLock {
                runCatching { api.playlists.setPublished(uri, published) }
                    .onFailure { Log.w(TAG, "profile listing of $uri to $published refused: ${it.message}") }.isSuccess
            }
            onDone(ok)
        }
    }

    private val pendingIds = java.util.concurrent.atomic.AtomicInteger()

    /**
     * Creates a playlist (and adds the track when given). In the library it shows at once as a
     * row that cannot be opened yet, and becomes the real playlist when Spotify has answered;
     * a refused creation takes the row away again.
     */
    fun createPlaylistWithTrack(context: Context, name: String, trackId: String, folderUri: String? = null, onDone: (Boolean) -> Unit = {}) {
        val title = name.trim()
        if (title.isBlank()) { onDone(false); return }
        val app = context.applicationContext
        val placeholderId = LibraryEdits.PENDING_PREFIX + pendingIds.incrementAndGet()
        val edit = LibraryEdits.Add(folderUri, LibraryEntry(
            spotifyId = placeholderId, name = title,
            subtitle = app.getString(R.string.assisted_curation_search_playlist) + ProfileCache.name?.let { " • $it" }.orEmpty(),
            coverUri = "", isPlaylist = true, uri = placeholderId,
            addedAt = java.time.Instant.now().toString(),
        ))
        var trackAdded = true
        LibraryEdits.run(context, edit, write = {
            val uri = api.playlists.create(title, folderUri)
            LibraryEdits.resolve(edit, edit.entry.copy(spotifyId = uri.substringAfterLast(':'), uri = uri))
            Api.HomeCache.libraryRoot = null
            folderUri?.let { Api.HomeCache.folders.remove(it) }
            if (trackId.isNotBlank()) {
                // The playlist exists now: a refused track must not take its row away again.
                trackAdded = runCatching { api.playlists.addTracks(uri, listOf("spotify:track:$trackId")) }
                    .onFailure { Log.w(TAG, "add $trackId to new playlist '$title' failed: ${it.message}") }.isSuccess
                if (trackAdded) membershipCache[uri.substringAfterLast(':')] = ConcurrentHashMap.newKeySet<String>().apply { add(trackId) }
            }
        }, onDone = { ok -> onDone(ok && trackAdded) })
    }

    private suspend fun allTracks(uri: String): List<app.myspoti.api.Track> {
        val out = ArrayList<app.myspoti.api.Track>()
        var offset = 0
        while (true) {
            val page = api.playlists.tracks(uri, offset, 100)
            out += page.items
            offset = page.nextOffset ?: break
        }
        return out
    }

    // Which playlists contain a track, cached per playlist for the session.
    private val membershipCache = ConcurrentHashMap<String, MutableSet<String>>()

    /** Track ids contained in [playlistId] (first call fetches, then cached). */
    suspend fun playlistTrackIds(context: Context, playlistId: String): Set<String> {
        membershipCache[playlistId]?.let { return it }
        val ids = runCatching { allTracks("spotify:playlist:$playlistId").map { it.uri.substringAfterLast(':') } }.getOrNull() ?: return emptySet()
        val set = ConcurrentHashMap.newKeySet<String>().apply { addAll(ids) }
        membershipCache[playlistId] = set
        return set
    }
}
