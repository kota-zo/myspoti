package com.music.spotui.data.local

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshots.SnapshotStateMap
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

/** One occurrence of a phone file in a list (its own [key]: the same file can be there twice). */
data class LocalItem(
    val key: String,
    val uri: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val artUri: String,
) {
    fun toTrack() = LocalTrack(
        id = key.hashCode().toLong() and 0x7fffffffL,
        uri = Uri.parse(uri), title = title, artist = artist, album = album,
        durationMs = durationMs, artUri = Uri.parse(artUri),
    )

    companion object {
        fun of(t: LocalTrack) = LocalItem(
            key = UUID.randomUUID().toString(), uri = t.uri.toString(), title = t.title,
            artist = t.artist, album = t.album, durationMs = t.durationMs, artUri = t.artUri.toString(),
        )
    }
}

/** A playlist kept on this phone only (Spotify cannot hold phone files). */
data class LocalPlaylist(val id: String, val name: String, val items: List<LocalItem>, val createdAt: Long)

/**
 * Playlists of phone files, and phone files added to Spotify playlists ("Added on this
 * device"). Stored in the app's files; nothing is sent to Spotify, whose API does not
 * accept local files.
 */
object LocalPlaylists {
    private const val VERSION = 1
    private lateinit var file: File
    private val lock = Any()

    val playlists: SnapshotStateMap<String, LocalPlaylist> = mutableStateMapOf()
    /** Spotify playlist id → phone files added to it on this phone. */
    val additions: SnapshotStateMap<String, List<LocalItem>> = mutableStateMapOf()

    @Volatile private var loaded = false

    fun init(context: Context) {
        if (::file.isInitialized) return
        file = File(context.filesDir, "local_playlists.json")
        // Read off the main thread; the screens show the lists as they land, and a change made
        // before then reads the file first ([ensureLoaded]) so it cannot overwrite it.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { ensureLoaded() }
    }

    private fun ensureLoaded() {
        if (loaded) return
        synchronized(lock) {
            if (loaded || !::file.isInitialized) return
            loaded = true
            runCatching { read(file.readText()) }
        }
    }

    /** Newest first. */
    fun list(): List<LocalPlaylist> = playlists.values.sortedByDescending { it.createdAt }

    fun create(name: String): LocalPlaylist {
        val p = LocalPlaylist(UUID.randomUUID().toString(), name.trim(), emptyList(), System.currentTimeMillis())
        update { playlists[p.id] = p }
        return p
    }

    fun rename(id: String, name: String) = update { playlists[id]?.let { playlists[id] = it.copy(name = name.trim()) } }
    fun delete(id: String) = update { playlists.remove(id) }

    fun add(id: String, track: LocalTrack) =
        update { playlists[id]?.let { playlists[id] = it.copy(items = it.items + LocalItem.of(track)) } }

    fun remove(id: String, key: String) =
        update { playlists[id]?.let { playlists[id] = it.copy(items = it.items.filterNot { i -> i.key == key }) } }

    fun addToSpotify(playlistId: String, track: LocalTrack) =
        update { additions[playlistId] = additions[playlistId].orEmpty() + LocalItem.of(track) }

    fun removeFromSpotify(playlistId: String, key: String) = update {
        val left = additions[playlistId].orEmpty().filterNot { it.key == key }
        if (left.isEmpty()) additions.remove(playlistId) else additions[playlistId] = left
    }

    private fun update(change: () -> Unit) {
        synchronized(lock) {
            ensureLoaded()
            change()
            if (::file.isInitialized) {
                runCatching { com.music.spotui.data.writeTextSafely(file, write()) }
                    .onFailure { app.myspoti.host.android.Logbook.log("local playlists not saved: ${it.message}") }
            }
        }
    }

    // ── JSON ──

    private fun itemJson(i: LocalItem) = JSONObject().put("key", i.key).put("uri", i.uri).put("title", i.title)
        .put("artist", i.artist).put("album", i.album).put("duration", i.durationMs).put("art", i.artUri)

    private fun itemOf(o: JSONObject) = LocalItem(o.getString("key"), o.getString("uri"), o.optString("title"),
        o.optString("artist"), o.optString("album"), o.optLong("duration"), o.optString("art"))

    private fun items(a: JSONArray?) = (0 until (a?.length() ?: 0)).mapNotNull { runCatching { itemOf(a!!.getJSONObject(it)) }.getOrNull() }

    internal fun write(): String {
        val lists = JSONArray()
        playlists.values.forEach { p ->
            lists.put(JSONObject().put("id", p.id).put("name", p.name).put("created", p.createdAt)
                .put("items", JSONArray().apply { p.items.forEach { put(itemJson(it)) } }))
        }
        val adds = JSONObject()
        additions.forEach { (id, list) -> adds.put(id, JSONArray().apply { list.forEach { put(itemJson(it)) } }) }
        return JSONObject().put("version", VERSION).put("playlists", lists).put("additions", adds).toString()
    }

    internal fun read(text: String) {
        val o = JSONObject(text)
        if (o.optInt("version") != VERSION) return
        val lists = o.optJSONArray("playlists") ?: JSONArray()
        for (i in 0 until lists.length()) {
            val p = lists.getJSONObject(i)
            playlists[p.getString("id")] = LocalPlaylist(p.getString("id"), p.optString("name"), items(p.optJSONArray("items")), p.optLong("created"))
        }
        val adds = o.optJSONObject("additions") ?: JSONObject()
        adds.keys().forEach { id -> items(adds.optJSONArray(id)).takeIf { it.isNotEmpty() }?.let { additions[id] = it } }
    }
}
