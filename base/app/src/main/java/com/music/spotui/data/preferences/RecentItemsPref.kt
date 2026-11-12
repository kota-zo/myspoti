package com.music.spotui.data.preferences

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * One "Recent searches" entry — Spotify-style: not the typed query but the
 * actual item the user opened from the results (song / artist / album / show).
 * Carries enough payload to re-launch the item on tap.
 */
data class RecentItem(
    val type: String,            // "song" | "artist" | "album" | "show"
    val key: String,             // stable id for dedupe (spotify id, or name)
    val name: String,
    val singer: String = "",     // artist line (songs/albums)
    val image: String = "",
    // Song-only payload so a tap can replay it.
    val songId: Int = -1,
    val songAlbum: String = "",
    val songUrl: String = "",
    val spotifyTrackId: String = "",
    val explicit: Boolean = false,
    val durationMs: Int = 0,
)

/** [this], a search's song the user played, as the recent search to keep. */
fun com.music.spotui.data.entity.SongsModel.toRecentItem() = RecentItem(
    type = "song",
    key = spotifyTrackId.ifBlank { url },
    name = title,
    singer = singer,
    image = coverUri,
    songId = id,
    songAlbum = album,
    songUrl = url,
    spotifyTrackId = spotifyTrackId,
    explicit = explicit,
    durationMs = durationMs,
)

/**
 * A recent song as the song to play or queue again; null for any other kind of item. A song kept
 * with its Spotify id is played by that id, whatever play key was saved with it.
 */
fun RecentItem.toSong(): com.music.spotui.data.entity.SongsModel? {
    if (type != "song") return null
    val byId = { com.music.spotui.di.SongPlayer.buildSpotifyPlayQuery(spotifyTrackId, name, singer) }
    val url = when {
        songUrl.isBlank() -> byId()
        spotifyTrackId.isNotBlank() && !songUrl.startsWith("spotify:track:") -> byId()
        else -> songUrl
    }
    return com.music.spotui.data.entity.SongsModel(
        songId, name, songAlbum, singer, image, url, spotifyTrackId,
        explicit = explicit, durationMs = durationMs,
    )
}

private const val PREF = "RecentItems"
private const val KEY = "items"
private const val MAX_ITEMS = 20

private fun RecentItem.toJson(): JSONObject = JSONObject().apply {
    put("type", type); put("key", key); put("name", name); put("singer", singer)
    put("image", image); put("songId", songId); put("songAlbum", songAlbum)
    put("songUrl", songUrl); put("spotifyTrackId", spotifyTrackId)
    put("explicit", explicit); put("durationMs", durationMs)
}

private fun JSONObject.toRecentItem(): RecentItem = RecentItem(
    type = optString("type"), key = optString("key"), name = optString("name"),
    singer = optString("singer"), image = optString("image"),
    songId = optInt("songId", -1), songAlbum = optString("songAlbum"),
    songUrl = optString("songUrl"), spotifyTrackId = optString("spotifyTrackId"),
    explicit = optBoolean("explicit", false), durationMs = optInt("durationMs", 0),
)

fun getRecentItems(context: Context): List<RecentItem> {
    val raw = context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY, null)
        ?: return emptyList()
    return runCatching {
        val arr = JSONArray(raw)
        (0 until arr.length()).map { arr.getJSONObject(it).toRecentItem() }
    }.getOrDefault(emptyList())
}

private fun save(context: Context, items: List<RecentItem>) {
    val arr = JSONArray().also { a -> items.forEach { a.put(it.toJson()) } }
    context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        .edit().putString(KEY, arr.toString()).apply()
}

fun addRecentItem(context: Context, item: RecentItem) {
    if (item.name.isBlank()) return
    val rest = getRecentItems(context).filterNot { it.type == item.type && it.key == item.key }
    save(context, (listOf(item) + rest).take(MAX_ITEMS))
}

fun removeRecentItem(context: Context, item: RecentItem) {
    save(context, getRecentItems(context).filterNot { it.type == item.type && it.key == item.key })
}

fun clearRecentItems(context: Context) {
    context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().remove(KEY).apply()
}
