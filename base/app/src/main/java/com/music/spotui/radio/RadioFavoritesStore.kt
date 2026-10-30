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

/**
 * Stations saved as favorites, kept on this phone only (the radio-browser.info directory has
 * no account to sync them to). Same JSON-file-on-disk pattern as
 * [com.music.spotui.data.local.LocalPlaylists]: a small store of its own, so this module stays
 * removable without touching the app's other data files.
 */
object RadioFavoritesStore {
    private const val VERSION = 1
    private lateinit var file: File
    private val lock = Any()
    @Volatile private var loaded = false

    /** stationUuid → the station, as it was when favorited (the directory's own copy may change). */
    val favorites: SnapshotStateMap<String, RadioStation> = mutableStateMapOf()

    fun init(context: Context) {
        if (::file.isInitialized) return
        file = File(context.filesDir, "radio_favorites.json")
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

    fun isFavorite(stationUuid: String): Boolean {
        ensureLoaded()
        return favorites.containsKey(stationUuid)
    }

    fun toggle(station: RadioStation) {
        if (favorites.containsKey(station.stationUuid)) remove(station.stationUuid) else add(station)
    }

    fun add(station: RadioStation) = update { favorites[station.stationUuid] = station }
    fun remove(stationUuid: String) = update { favorites.remove(stationUuid) }

    /** Newest-added first; favorites carry no timestamp of their own, so insertion order stands in. */
    fun list(): List<RadioStation> = favorites.values.toList().asReversed()

    private fun update(change: () -> Unit) {
        synchronized(lock) {
            ensureLoaded()
            change()
            if (::file.isInitialized) {
                val tmp = File(file.parentFile, file.name + ".tmp")
                tmp.writeText(write())
                tmp.renameTo(file)
            }
        }
    }

    private fun stationJson(s: RadioStation) = JSONObject()
        .put("uuid", s.stationUuid).put("name", s.name).put("url", s.streamUrl).put("homepage", s.homepage)
        .put("favicon", s.favicon).put("tags", JSONArray(s.tags)).put("country", s.country)
        .put("countrycode", s.countryCode).put("language", s.language).put("codec", s.codec)
        .put("bitrate", s.bitrateKbps).put("votes", s.votes).put("clicks", s.clickCount).put("ok", s.lastCheckOk)

    private fun stationOf(o: JSONObject): RadioStation {
        val tags = o.optJSONArray("tags")
        return RadioStation(
            stationUuid = o.getString("uuid"), name = o.optString("name"), streamUrl = o.optString("url"),
            homepage = o.optString("homepage"), favicon = o.optString("favicon"),
            tags = (0 until (tags?.length() ?: 0)).map { tags!!.getString(it) },
            country = o.optString("country"), countryCode = o.optString("countrycode"),
            language = o.optString("language"), codec = o.optString("codec"),
            bitrateKbps = o.optInt("bitrate"), votes = o.optInt("votes"), clickCount = o.optInt("clicks"),
            lastCheckOk = o.optBoolean("ok", true),
        )
    }

    internal fun write(): String {
        val arr = JSONArray()
        favorites.values.forEach { arr.put(stationJson(it)) }
        return JSONObject().put("version", VERSION).put("favorites", arr).toString()
    }

    internal fun read(text: String) {
        val o = JSONObject(text)
        if (o.optInt("version") != VERSION) return
        val arr = o.optJSONArray("favorites") ?: JSONArray()
        for (i in 0 until arr.length()) {
            runCatching { stationOf(arr.getJSONObject(i)) }.getOrNull()?.let { favorites[it.stationUuid] = it }
        }
    }
}
