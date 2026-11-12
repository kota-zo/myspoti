package com.music.spotui.data.catalog

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.myspoti.catalog.DEFAULT_ORDER
import com.music.spotui.di.Graph

/** The order of the metadata providers and the ones turned off, as plain lists (no Android: tested on the computer). */
internal object MetadataOrder {
    const val SPOTIFY = "spotify"

    /** Every provider the app knows, in the order they are asked by default: the signed-in Spotify account first. */
    val ALL = listOf(SPOTIFY) + DEFAULT_ORDER

    /** [stored] with what it does not know left out and the providers it lacks (added by a newer app) after it. */
    fun normalized(stored: List<String>): List<String> {
        val known = stored.filter { it in ALL }.distinct()
        return known + ALL.filter { it !in known }
    }

    /** [order] with [id] moved [delta] places, within the list. */
    fun moved(order: List<String>, id: String, delta: Int): List<String> {
        val i = order.indexOf(id)
        if (i < 0) return order
        val to = (i + delta).coerceIn(0, order.lastIndex)
        if (to == i) return order
        return order.toMutableList().apply { removeAt(i); add(to, id) }
    }

    /** Whether [id] may be turned off: one provider at least stays on, or a search would have nobody to ask. */
    fun canTurnOff(order: List<String>, off: Set<String>, id: String): Boolean = id in off || order.count { it !in off } > 1

    /** The name shown for a provider: their own names, never translated. */
    fun name(id: String): String = when (id) {
        SPOTIFY -> "Spotify"
        "deezer" -> "Deezer"
        "ytmusic" -> "YouTube Music"
        "tidal" -> "Tidal"
        "itunes" -> "iTunes"
        "musicbrainz" -> "MusicBrainz"
        else -> id
    }
}

/**
 * Settings → Sources → Metadata: the order the providers of music metadata are asked in and the ones turned off. A
 * provider turned off is never asked. Spotify is asked only while an account is signed in.
 */
object MetadataPref {
    private const val PREF = "myspoti_prefs"
    private const val ORDER = "metadata_order"
    private const val OFF = "metadata_off"

    private fun prefs(c: Context) = c.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    var order by mutableStateOf(
        MetadataOrder.normalized(prefs(Graph.app).getString(ORDER, null)?.split(',')?.filter { it.isNotBlank() }.orEmpty()),
    )
        private set
    var off by mutableStateOf(prefs(Graph.app).getString(OFF, null)?.split(',')?.filter { it in MetadataOrder.ALL }?.toSet().orEmpty())
        private set

    /** Bumped at every change, so the chain is made again. */
    @Volatile var version = 0
        private set

    /** The providers to ask, in order. */
    fun enabledOrder(): List<String> = order.filter { it !in off }

    fun move(c: Context, id: String, delta: Int) {
        order = MetadataOrder.moved(order, id, delta)
        save(c)
    }

    fun setOn(c: Context, id: String, on: Boolean) {
        if (!on && !MetadataOrder.canTurnOff(order, off, id)) return
        off = if (on) off - id else off + id
        save(c)
    }

    /** Back to the default order, everything on. */
    fun reset(c: Context) {
        order = MetadataOrder.ALL
        off = emptySet()
        save(c)
    }

    private fun save(c: Context) {
        version++
        prefs(c).edit().putString(ORDER, order.joinToString(",")).putString(OFF, off.joinToString(",")).apply()
    }
}
