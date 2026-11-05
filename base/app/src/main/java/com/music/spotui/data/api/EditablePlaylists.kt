package com.music.spotui.data.api

import app.myspoti.api.LibraryFilter
import app.myspoti.api.MediaKind
import app.myspoti.api.best
import app.myspoti.host.android.MyspotiRuntime

/**
 * A playlist the user can add tracks to, for the "Add to playlist" pickers. With a
 * [folderUri] the row is a library folder instead, opened in place.
 */
data class EditablePlaylist(
    val id: String,
    val name: String,
    val trackCount: Int,
    val imageUrl: String?,
    val folderUri: String? = null,
) {
    val isFolder: Boolean get() = folderUri != null
}

object EditablePlaylists {
    /** Ids of the playlists this account can change, read at most every [CACHE_TTL_MS]. */
    @Volatile private var ids: Set<String>? = null
    @Volatile private var idsAt = 0L

    /** The pickers' playlists with covers, kept for [CACHE_TTL_MS]: walking a big library took a minute on every save. */
    @Volatile private var picker: List<EditablePlaylist>? = null
    @Volatile private var pickerAt = 0L

    /** The library's first level (folders and playlists), the "Saved in" sheet's list. */
    @Volatile private var rootLevel: List<EditablePlaylist>? = null
    @Volatile private var rootLevelAt = 0L

    private const val CACHE_TTL_MS = 5 * 60_000L

    suspend fun ids(): Set<String> {
        val now = System.currentTimeMillis()
        ids?.takeIf { now - idsAt < CACHE_TTL_MS }?.let { return it }
        return runCatching { load().mapTo(HashSet()) { it.id } }.getOrNull()?.also { ids = it; idsAt = now } ?: ids.orEmpty()
    }

    /** The user's own and collaborative playlists, in library order, folders flattened. */
    suspend fun load(): List<EditablePlaylist> =
        runCatching { MyspotiRuntime.api.playlists.editable() }.getOrDefault(emptyList()).map {
            EditablePlaylist(it.uri.substringAfterLast(':'), it.name, it.trackCount, it.images.best(160)?.url)
        }

    /**
     * [load] for the pickers that show covers. Reported: "Add to another playlist" showed grey
     * squares. Spotify's playlist list has no cover for a playlist without one of its own (the
     * mosaic of its albums); the library has it, so those are read from there, folders walked.
     *
     * The result is kept for [CACHE_TTL_MS], so opening the picker for the next song does not
     * read the whole library again (reported as a minute's wait on every save). A playlist made,
     * renamed, deleted or given a new cover in the app drops it ([invalidate]); a song added or
     * removed only moves that row's count ([trackCountChanged]).
     */
    suspend fun loadWithCovers(): List<EditablePlaylist> {
        val now = System.currentTimeMillis()
        picker?.takeIf { now - pickerAt < CACHE_TTL_MS }?.let { return it }
        val list = load()
        val withCovers = if (list.all { it.imageUrl != null }) list else {
            val covers = libraryCovers()
            list.map { if (it.imageUrl != null) it else it.copy(imageUrl = covers["spotify:playlist:${it.id}"]) }
        }
        // An empty list is also what a failed read looks like: kept out, so the next open tries again.
        if (withCovers.isNotEmpty()) {
            picker = withCovers
            pickerAt = System.currentTimeMillis()
        }
        return withCovers
    }

    /** A playlist of the pickers changed (made, renamed, deleted, given a new cover, or a whole list added): read them again next time. */
    fun invalidate() {
        ids = null
        idsAt = 0L
        picker = null
        pickerAt = 0L
        rootLevel = null
        rootLevelAt = 0L
    }

    /** A song went into or out of [id]: the pickers' counts follow without reading the library again. */
    fun trackCountChanged(id: String, delta: Int) {
        fun List<EditablePlaylist>.countChanged() = map {
            if (it.id == id) it.copy(trackCount = (it.trackCount + delta).coerceAtLeast(0)) else it
        }
        picker = picker?.countChanged()
        rootLevel = rootLevel?.countChanged()
    }

    /** Playlist uri → cover, as the library shows them, folders included. */
    private suspend fun libraryCovers(): Map<String, String> {
        val api = MyspotiRuntime.api
        val out = HashMap<String, String>()
        suspend fun walk(folderUri: String?, depth: Int) {
            var offset = 0
            repeat(40) {
                val page = runCatching {
                    api.library.items(filter = LibraryFilter.ALL, folderUri = folderUri, offset = offset, limit = 50)
                }.getOrNull() ?: return
                for (item in page.items) when (item.kind) {
                    MediaKind.FOLDER -> if (depth < 5) walk(item.uri, depth + 1)
                    MediaKind.PLAYLIST -> item.images.best(160)?.url?.let { out[item.uri] = it }
                    else -> Unit
                }
                offset = page.nextOffset ?: return
            }
        }
        walk(null, 0)
        return out
    }

    /**
     * One level of the library as Spotify shows it: its folders, then the playlists the user
     * can change, with their covers (Spotify's playlist list has none for mosaic covers;
     * the library has them). Falls back to [load] when the library cannot be read.
     *
     * The first level is kept for [CACHE_TTL_MS] like [loadWithCovers], so "Saved in" opens at
     * once the next time; a playlist made, renamed or deleted drops it ([invalidate]).
     */
    suspend fun level(folderUri: String?): List<EditablePlaylist> {
        val now = System.currentTimeMillis()
        if (folderUri == null) rootLevel?.takeIf { now - rootLevelAt < CACHE_TTL_MS }?.let { return it }
        val result = readLevel(folderUri)
        // As in [loadWithCovers]: an empty list may be a failed read, and is not kept.
        if (folderUri == null && result.isNotEmpty()) {
            rootLevel = result
            rootLevelAt = System.currentTimeMillis()
        }
        return result
    }

    private suspend fun readLevel(folderUri: String?): List<EditablePlaylist> {
        val api = MyspotiRuntime.api
        val editable = runCatching { api.playlists.editable() }.getOrNull()?.associateBy { it.uri } ?: return load()
        val items = ArrayList<app.myspoti.api.LibraryItem>()
        runCatching {
            var offset = 0
            repeat(40) {
                val page = api.library.items(filter = LibraryFilter.ALL, folderUri = folderUri, offset = offset, limit = 50)
                items += page.items
                offset = page.nextOffset ?: return@runCatching
            }
        }.onFailure { if (items.isEmpty()) return if (folderUri == null) load() else emptyList() }
        return items.mapNotNull { item ->
            when (item.kind) {
                MediaKind.FOLDER -> EditablePlaylist(item.uri, item.name, item.count ?: 0, null, folderUri = item.uri)
                MediaKind.PLAYLIST -> editable[item.uri]?.let {
                    EditablePlaylist(item.uri.substringAfterLast(':'), item.name, it.trackCount,
                        item.images.best(160)?.url ?: it.images.best(160)?.url)
                }
                else -> null
            }
        }
    }
}
