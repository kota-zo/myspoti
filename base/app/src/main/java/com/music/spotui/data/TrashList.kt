package com.music.spotui.data

import org.json.JSONException
import org.json.JSONObject

/**
 * The rules of the hidden songs and artists (Spicetify's Trash Bin, ported from its Android
 * patches): what to skip, the desktop extension's export format, and the brake on skips.
 */
object TrashList {
    const val SONGS = "songs"
    const val ARTISTS = "artists"

    /** True when the song or one of its artists is hidden. */
    fun shouldSkip(trackUri: String, artistUris: List<String>, songs: Set<String>, artists: Set<String>): Boolean =
        trackUri in songs || artistUris.any { it in artists }

    /** Spicetify desktop's export: `{"songs":{uri:true},"artists":{uri:true}}`. */
    fun export(songs: Set<String>, artists: Set<String>): String = JSONObject()
        .put(SONGS, JSONObject().apply { songs.forEach { put(it, true) } })
        .put(ARTISTS, JSONObject().apply { artists.forEach { put(it, true) } })
        .toString()

    /** Songs and artists of an export; anything else throws before the lists change. */
    fun parse(json: String): Pair<Set<String>, Set<String>> {
        val root = JSONObject(json.trim())
        if (!root.has(SONGS) && !root.has(ARTISTS)) throw JSONException("Neither songs nor artists")
        return hidden(root, SONGS) to hidden(root, ARTISTS)
    }

    private fun hidden(root: JSONObject, key: String): Set<String> {
        val list = root.optJSONObject(key) ?: if (root.has(key)) throw JSONException("$key is not an object") else return emptySet()
        return list.keys().asSequence().filter { list.getBoolean(it) && it.startsWith("spotify:") }.toSet()
    }

    /** At most 5 skips in any 10 seconds, so a queue of hidden songs cannot spin the player. */
    class Guard(private val max: Int = 5, private val windowMs: Long = 10_000) {
        private val times = ArrayDeque<Long>()

        @Synchronized fun allow(nowMs: Long): Boolean {
            while (times.isNotEmpty() && times.first() < nowMs - windowMs) times.removeFirst()
            if (times.size >= max) return false
            times.addLast(nowMs)
            return true
        }
    }
}
