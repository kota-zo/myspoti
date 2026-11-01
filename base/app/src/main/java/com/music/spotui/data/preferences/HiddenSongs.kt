package com.music.spotui.data.preferences

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.music.spotui.data.entity.SongsModel
import org.json.JSONArray
import org.json.JSONObject

/** A song marked "Not interested": enough to list it in Settings and show it again. */
data class HiddenSong(val id: String, val title: String, val singer: String, val coverUri: String)

/**
 * "Not interested", as YouTube Music's dislike: a list that stays on this phone (nothing is sent to
 * Spotify). A hidden song is skipped when a list, a radio or the queue comes to it, and is dimmed
 * in song lists; playing it by hand still plays it. Newest first.
 */
object HiddenSongs {
    private const val PREF = "HiddenSongs"
    private const val KEY = "songs"

    var songs by mutableStateOf(load(com.music.spotui.di.Graph.app))
        private set
    private var ids by mutableStateOf(songs.map { it.id }.toSet())

    /** The song [justHidden] is offered to be taken back (the Undo snackbar of the app's root). */
    var justHidden by mutableStateOf<HiddenSong?>(null)

    fun isHidden(trackId: String): Boolean = trackId.isNotBlank() && trackId in ids

    fun hide(context: Context, song: SongsModel) {
        if (song.spotifyTrackId.isBlank() || isHidden(song.spotifyTrackId)) return
        val h = HiddenSong(song.spotifyTrackId, song.title, song.singer, song.coverUri)
        set(context, listOf(h) + songs)
        justHidden = h
    }

    fun show(context: Context, trackId: String) = set(context, songs.filterNot { it.id == trackId })

    private fun set(context: Context, list: List<HiddenSong>) {
        songs = list
        ids = list.map { it.id }.toSet()
        val arr = JSONArray().also { a ->
            list.forEach { a.put(JSONObject().put("id", it.id).put("title", it.title).put("singer", it.singer).put("cover", it.coverUri)) }
        }
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
    }

    private fun load(context: Context): List<HiddenSong> {
        val raw = context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.getJSONObject(it).let { o -> HiddenSong(o.getString("id"), o.optString("title"), o.optString("singer"), o.optString("cover")) } }
        }.getOrDefault(emptyList())
    }
}
