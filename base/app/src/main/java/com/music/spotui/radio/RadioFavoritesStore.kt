package com.music.spotui.radio

import android.content.Context
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshots.SnapshotStateMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A saved station, with when it was saved (the order the favorites show in). */
data class RadioFavorite(val station: RadioStation, val addedAt: Long)

/**
 * Stations saved as favorites, kept on this phone only (radio-browser.info has no account to
 * sync them to). Same JSON-file-on-disk pattern as [com.music.spotui.data.local.LocalPlaylists].
 */
object RadioFavoritesStore {
    private const val VERSION = 1
    private var file: File? = null
    private val lock = Any()
    private var started = false

    /** stationUuid → the station, as it was when favorited (the directory's own copy may change). */
    val favorites: SnapshotStateMap<String, RadioFavorite> = mutableStateMapOf()

    fun init(context: Context) {
        if (started) return
        started = true
        val f = File(context.filesDir, "radio_favorites.json")
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { load(f) }
    }

    /** The favorites become [f]'s, and every change is saved to it from then on; [init] does it off the main thread. */
    internal fun load(f: File) {
        synchronized(lock) {
            favorites.clear()
            if (f.exists()) runCatching { read(f.readText()) }
            file = f
        }
    }

    fun isFavorite(stationUuid: String): Boolean = favorites.containsKey(stationUuid)

    fun toggle(station: RadioStation) {
        if (favorites.containsKey(station.stationUuid)) remove(station.stationUuid) else add(station)
    }

    /** A station this app cannot play (see [RadioBrowserApi.playable]) is not kept. */
    fun add(station: RadioStation, now: Long = System.currentTimeMillis()) {
        if (!RadioBrowserApi.playable(station.streamUrl)) return
        update { favorites[station.stationUuid] = RadioFavorite(station, now) }
    }

    fun remove(stationUuid: String) = update { favorites.remove(stationUuid) }

    /** Newest-added first (a SnapshotStateMap keeps no insertion order, so by when each was saved). */
    fun list(): List<RadioStation> = favorites.values.sortedByDescending { it.addedAt }.map { it.station }

    private fun update(change: () -> Unit) {
        synchronized(lock) {
            change()
            val f = file ?: return
            val tmp = File(f.parentFile, f.name + ".tmp")
            runCatching { tmp.writeText(write()); tmp.renameTo(f) }
        }
    }

    private fun favoriteJson(fav: RadioFavorite) = fav.station.let { s ->
        JSONObject()
            .put("uuid", s.stationUuid).put("name", s.name).put("url", s.streamUrl).put("homepage", s.homepage)
            .put("favicon", s.favicon).put("tags", JSONArray(s.tags)).put("country", s.country)
            .put("countrycode", s.countryCode).put("language", s.language).put("codec", s.codec)
            .put("bitrate", s.bitrateKbps).put("votes", s.votes).put("clicks", s.clickCount).put("ok", s.lastCheckOk).put("hls", s.hls)
            .put("added", fav.addedAt)
    }

    private fun favoriteOf(o: JSONObject): RadioFavorite {
        val tags = o.optJSONArray("tags")
        val station = RadioStation(
            stationUuid = o.getString("uuid"), name = o.optString("name"), streamUrl = o.optString("url"),
            homepage = o.optString("homepage"), favicon = o.optString("favicon"),
            tags = (0 until (tags?.length() ?: 0)).map { tags!!.getString(it) },
            country = o.optString("country"), countryCode = o.optString("countrycode"),
            language = o.optString("language"), codec = o.optString("codec"),
            bitrateKbps = o.optInt("bitrate"), votes = o.optInt("votes"), clickCount = o.optInt("clicks"),
            lastCheckOk = o.optBoolean("ok", true),
            hls = o.optBoolean("hls") || RadioBrowserApi.looksHls(o.optString("url")),
        )
        return RadioFavorite(station, o.optLong("added"))
    }

    internal fun write(): String {
        val arr = JSONArray()
        favorites.values.forEach { arr.put(favoriteJson(it)) }
        return JSONObject().put("version", VERSION).put("favorites", arr).toString()
    }

    /** Adds the favorites of [text]; one that is unreadable or no longer playable is left out. */
    internal fun read(text: String) {
        val o = JSONObject(text)
        if (o.optInt("version") != VERSION) return
        val arr = o.optJSONArray("favorites") ?: JSONArray()
        for (i in 0 until arr.length()) {
            runCatching { favoriteOf(arr.getJSONObject(i)) }.getOrNull()
                ?.takeIf { RadioBrowserApi.playable(it.station.streamUrl) }
                ?.let { favorites[it.station.stationUuid] = it }
        }
    }
}
