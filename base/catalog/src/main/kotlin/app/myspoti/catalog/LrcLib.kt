/* SPDX-License-Identifier: GPL-3.0-only */
package app.myspoti.catalog

import kotlin.math.abs
import kotlinx.serialization.json.JsonObject

/** A song's words, timed when they can be. */
data class FoundLyrics(val synced: String?, val plain: String?, val instrumental: Boolean)

/** LRCLIB, a free and open lyrics database: no key, and it asks for a client name. */
class LrcLib(private val fetch: Fetch, private val base: String = "https://lrclib.net") {
    /** The song's words, or null when nobody has added them. [durationMs] tells versions apart. */
    suspend fun lyrics(title: String, artist: String, album: String?, durationMs: Long): FoundLyrics? {
        val exact = buildString {
            append("$base/api/get?track_name=${title.q()}&artist_name=${artist.q()}&duration=${durationMs / 1000}")
            if (!album.isNullOrEmpty()) append("&album_name=${album.q()}")
        }
        val r = fetch.get(exact)
        if (r.status == 200) return read(parseObject(NAME, r.body))
        if (r.status != 404) throw ProviderDown(NAME, "HTTP ${r.status}")
        val s = fetch.get("$base/api/search?track_name=${title.q()}&artist_name=${artist.q()}")
        if (s.status != 200) return null
        return parseArray(NAME, s.body).objects()
            .filter { abs((it.long("duration") ?: Long.MAX_VALUE / 2000) * 1000 - durationMs) <= MOST_OFF_MS }
            .sortedBy { if (it.str("syncedLyrics") != null) 0 else 1 }
            .firstOrNull()?.let(::read)
    }

    private fun read(o: JsonObject) = FoundLyrics(o.str("syncedLyrics"), o.str("plainLyrics"), o.bool("instrumental") ?: false)

    private companion object {
        const val NAME = "lrclib"
        const val MOST_OFF_MS = 3_000L
    }
}
