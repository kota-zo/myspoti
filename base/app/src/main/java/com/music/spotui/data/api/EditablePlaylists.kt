package com.music.spotui.data.api

import app.myspoti.api.LibraryFilter
import app.myspoti.api.MediaKind
import app.myspoti.api.best
import app.myspoti.host.android.MyspotiRuntime
import org.json.JSONArray
import org.json.JSONObject

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
    /** Ids of the playlists this account can change, read at most every [IDS_TTL_MS]. */
    @Volatile private var ids: Set<String>? = null
    @Volatile private var idsAt = 0L
    private const val IDS_TTL_MS = 5 * 60_000L

    /**
     * The pickers' last lists ([ALL] for [loadWithCovers], a folder's uri or [ROOT] for [level]):
     * shown at once while they are read again. Reported: a minute of "Loading playlists" each
     * time a song was saved to a playlist (#145).
     */
    private val shown = java.util.concurrent.ConcurrentHashMap<String, List<EditablePlaylist>>()
    /** Playlist uri → the library's cover, kept so the library is walked again only for a new playlist. */
    private val covers = java.util.concurrent.ConcurrentHashMap<String, String>()
    private const val ALL = "all"
    private const val ROOT = "root"

    /** What [loadWithCovers] gave last, or null. */
    fun cachedWithCovers(): List<EditablePlaylist>? = kept(ALL)

    /** What [level] gave last for [folderUri], or null. */
    fun cachedLevel(folderUri: String?): List<EditablePlaylist>? = kept(folderUri ?: ROOT)

    private const val PREF = "playlist_pickers"
    private fun prefs() = com.music.spotui.di.Graph.app.getSharedPreferences(PREF, android.content.Context.MODE_PRIVATE)

    /**
     * The last list, from the disk after a restart. Kept in memory only, every first picker of a
     * session waited for the whole library (reported: 5-6 s of "Loading playlists", on the build
     * that showed the last list at once).
     */
    private fun kept(key: String): List<EditablePlaylist>? =
        shown[key] ?: prefs().getString(key, null)?.let(::decodeList)?.also { shown[key] = it }

    private fun keep(key: String, list: List<EditablePlaylist>) {
        if (shown.put(key, list) != list) prefs().edit().putString(key, encodeList(list)).apply()
    }

    internal fun encodeList(list: List<EditablePlaylist>): String = JSONArray().apply {
        list.forEach { p ->
            put(JSONObject().put("id", p.id).put("name", p.name).put("count", p.trackCount)
                .put("image", p.imageUrl ?: JSONObject.NULL).put("folder", p.folderUri ?: JSONObject.NULL))
        }
    }.toString()

    /** Null when unreadable: the list is read from Spotify as if never kept. */
    internal fun decodeList(raw: String): List<EditablePlaylist>? = runCatching {
        val a = JSONArray(raw)
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            EditablePlaylist(o.getString("id"), o.getString("name"), o.getInt("count"),
                o.optString("image").takeUnless { o.isNull("image") }, o.optString("folder").takeUnless { o.isNull("folder") })
        }
    }.getOrNull()

    /** Sign-out: nothing of the account's playlists is kept. */
    fun clear() {
        shown.clear(); covers.clear(); ids = null
        prefs().edit().clear().apply()
    }

    suspend fun ids(): Set<String> {
        val now = System.currentTimeMillis()
        ids?.takeIf { now - idsAt < IDS_TTL_MS }?.let { return it }
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
     */
    suspend fun loadWithCovers(): List<EditablePlaylist> {
        // Not read (offline, the page reloading): the last list rather than none.
        val list = load().ifEmpty { return cachedWithCovers().orEmpty() }
        if (list.any { it.imageUrl == null && !covers.containsKey("spotify:playlist:${it.id}") }) covers.putAll(libraryCovers())
        return list.map { if (it.imageUrl != null) it else it.copy(imageUrl = covers["spotify:playlist:${it.id}"]) }
            .also { keep(ALL, it) }
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
     */
    suspend fun level(folderUri: String?): List<EditablePlaylist> {
        val api = MyspotiRuntime.api
        val editable = runCatching { api.playlists.editable() }.getOrNull()?.associateBy { it.uri } ?: return cachedLevel(folderUri) ?: load()
        val items = ArrayList<app.myspoti.api.LibraryItem>()
        val read = runCatching {
            var offset = 0
            repeat(40) {
                val page = api.library.items(filter = LibraryFilter.ALL, folderUri = folderUri, offset = offset, limit = 50)
                items += page.items
                offset = page.nextOffset ?: return@runCatching
            }
        }
        if (read.isFailure && items.isEmpty()) return cachedLevel(folderUri) ?: if (folderUri == null) load() else emptyList()
        val list = items.mapNotNull { item ->
            when (item.kind) {
                MediaKind.FOLDER -> EditablePlaylist(item.uri, item.name, item.count ?: 0, null, folderUri = item.uri)
                MediaKind.PLAYLIST -> editable[item.uri]?.let {
                    EditablePlaylist(item.uri.substringAfterLast(':'), item.name, it.trackCount,
                        item.images.best(160)?.url ?: it.images.best(160)?.url)
                }
                else -> null
            }
        }
        // A read cut short does not replace the whole list (reported: not all the playlists shown).
        if (read.isFailure) return cachedLevel(folderUri) ?: list
        keep(folderUri ?: ROOT, list)
        return list
    }
}
