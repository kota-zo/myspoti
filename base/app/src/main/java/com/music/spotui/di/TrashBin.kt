package com.music.spotui.di

import android.content.Context
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.myspoti.api.ItemKind
import com.music.spotui.data.TrashList
import org.json.JSONObject

/**
 * Hidden songs and artists (Spicetify's Trash Bin, ported from its Android patches): a song or
 * an artist the listener hides from its menu is skipped whenever it comes up, in any list, radio
 * or shuffle. The lists stay on the phone, in the desktop extension's format, so they can be
 * copied between the two. Episodes are left alone.
 */
object TrashBin {
    private const val PREF = "trash_bin"

    private val prefs get() = Graph.app.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    var songs by mutableStateOf(load(TrashList.SONGS))
        private set
    var artists by mutableStateOf(load(TrashList.ARTISTS))
        private set

    private val guard = TrashList.Guard()
    @Volatile private var current: app.myspoti.api.NowPlaying? = null
    @Volatile private var lastKey: String? = null
    @Volatile private var stopNoticeShown = false

    fun isSongHidden(uri: String) = uri in songs
    fun isArtistHidden(uri: String) = uri in artists

    /** From the player state: each new item is checked once. */
    fun onItem(item: app.myspoti.api.NowPlaying?) {
        if (item == null || item.kind != ItemKind.TRACK) return
        current = item
        val key = item.uid ?: item.uri
        if (key == lastKey) return
        lastKey = key
        check(item)
    }

    /** Hiding the song that plays skips it at once. */
    fun setSong(uri: String, hidden: Boolean) {
        songs = if (hidden) songs + uri else songs - uri
        save(TrashList.SONGS, songs)
        current?.takeIf { hidden && it.uri == uri }?.let(::check)
    }

    /** Hiding the artist of the song that plays skips it at once. */
    fun setArtist(uri: String, hidden: Boolean) {
        artists = if (hidden) artists + uri else artists - uri
        save(TrashList.ARTISTS, artists)
        current?.takeIf { hidden && it.artists.any { a -> a.uri == uri } }?.let(::check)
    }

    fun clear() {
        songs = emptySet(); artists = emptySet()
        save(TrashList.SONGS, songs); save(TrashList.ARTISTS, artists)
    }

    fun export(): String = TrashList.export(songs, artists)

    /** Adds an export's songs and artists (Spicetify desktop or Myspoti); false when it is not one. */
    fun import(json: String): Boolean {
        val (s, a) = runCatching { TrashList.parse(json) }.getOrNull() ?: return false
        songs = songs + s; artists = artists + a
        save(TrashList.SONGS, songs); save(TrashList.ARTISTS, artists)
        return true
    }

    private fun check(item: app.myspoti.api.NowPlaying) {
        if (!TrashList.shouldSkip(item.uri, item.artists.map { it.uri }, songs, artists)) return
        if (!guard.allow(SystemClock.elapsedRealtime())) {
            if (!stopNoticeShown) {
                stopNoticeShown = true
                android.widget.Toast.makeText(Graph.app, app.myspoti.engine.R.string.trash_stopped, android.widget.Toast.LENGTH_LONG).show()
            }
            return
        }
        stopNoticeShown = false
        SongPlayer.skipHidden()
    }

    private fun load(key: String): Set<String> = runCatching {
        val saved = JSONObject(prefs.getString(key, "{}") ?: "{}")
        saved.keys().asSequence().toSet()
    }.getOrDefault(emptySet())

    private fun save(key: String, uris: Set<String>) {
        prefs.edit().putString(key, JSONObject().apply { uris.forEach { put(it, true) } }.toString()).apply()
    }
}
