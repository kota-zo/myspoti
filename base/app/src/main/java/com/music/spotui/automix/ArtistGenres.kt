/* SPDX-License-Identifier: GPL-3.0-only */
package com.music.spotui.automix

import android.net.Uri
import app.myspoti.automix.segue.GenreFamily
import com.music.spotui.di.Graph
import com.music.spotui.extensions.ExtensionAudio
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * A song's genre family for AutoMix, from its artists' genres on Spotify. The planner trusts it over
 * what the analysis guesses from the sound: it is what keeps a song with no steady beat from being
 * beat-matched. Read once per song on the planner's worker, kept on the phone; a song Spotify gives
 * no genre for keeps the analyser's guess.
 */
object ArtistGenres {
    private const val PREFS = "automix_genres"
    private const val TIMEOUT_MS = 4_000L
    private const val API = "https://api.spotify.com/v1"
    private val memory = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** The family of [uri]'s song, or null when it is unknown (not a Spotify song, no genre, offline). Blocks: not on the main thread. */
    fun familyOf(uri: Uri): GenreFamily? {
        if (uri.scheme != ExtensionAudio.SCHEME) return null
        val id = uri.lastPathSegment?.takeIf { it.matches(Regex("[A-Za-z0-9]{22}")) } ?: return null
        val known = memory[id] ?: Graph.app.getSharedPreferences(PREFS, 0).getString(id, null)?.also { memory[id] = it }
        if (known != null) return known.takeIf { it.isNotEmpty() }?.let(GenreFamily::parse)
        val names = runCatching { fetch(id) }.onFailure { AutoMix.journal("genre of $id: ${it.javaClass.simpleName}") }.getOrNull() ?: return null
        val family = GenreFamily.fromNames(names)
        val wire = family?.wire.orEmpty()
        memory[id] = wire
        Graph.app.getSharedPreferences(PREFS, 0).edit().putString(id, wire).apply()
        AutoMix.journal("genre of $id: ${family?.wire ?: "none"} (${names.take(4).joinToString()})")
        return family
    }

    /** The genres of the song's artists, the first artist's first; null when Spotify could not be asked. */
    private fun fetch(id: String): List<String>? = kotlinx.coroutines.runBlocking {
        kotlinx.coroutines.withTimeoutOrNull(TIMEOUT_MS) {
            val raw = app.myspoti.host.android.MyspotiRuntime.spotify.raw
            val track = raw.get("$API/tracks/$id") as? JsonObject ?: return@withTimeoutOrNull null
            val artists = (track["artists"] as? JsonArray).orEmpty()
                .mapNotNull { (it as? JsonObject)?.get("id")?.jsonPrimitive?.contentOrNull }.take(5)
            if (artists.isEmpty()) return@withTimeoutOrNull emptyList()
            val info = raw.get("$API/artists?ids=${artists.joinToString(",")}") as? JsonObject ?: return@withTimeoutOrNull null
            (info["artists"] as? JsonArray).orEmpty().flatMap { a ->
                ((a as? JsonObject)?.get("genres") as? JsonArray).orEmpty().mapNotNull { it.jsonPrimitive.contentOrNull }
            }
        }
    }
}
