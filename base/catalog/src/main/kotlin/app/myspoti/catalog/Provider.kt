/* SPDX-License-Identifier: GPL-3.0-only */
package app.myspoti.catalog

import app.myspoti.api.Album
import app.myspoti.api.Artist
import app.myspoti.api.Page
import app.myspoti.api.Track

/** A track as a provider gives it, with the recording's ISRC when it knows it. */
data class Hit(val track: Track, val isrc: String? = null)

/**
 * One source of music metadata. Its items carry uris `<id>:<kind>:<its own id>`, so any of them can later be
 * asked back of the same provider. What a service does not offer comes back empty: never an invented answer.
 */
interface MetadataProvider {
    /** The scheme of its uris, such as `musicbrainz`. */
    val id: String

    suspend fun searchTracks(query: String, offset: Int = 0, limit: Int = 20): Page<Hit>
    suspend fun searchAlbums(query: String, offset: Int = 0, limit: Int = 20): Page<Album>
    suspend fun searchArtists(query: String, offset: Int = 0, limit: Int = 20): Page<Artist>

    /** Candidates for a known title and artist, for matching. */
    suspend fun find(title: String, artist: String, limit: Int = 10): List<Hit>
    suspend fun byIsrc(isrc: String): List<Hit>

    suspend fun track(id: String): Hit
    suspend fun album(id: String): Album
    suspend fun albumTracks(id: String, offset: Int = 0, limit: Int = 50): Page<Hit>
    suspend fun artist(id: String): Artist
    suspend fun topTracks(id: String): List<Hit>
    suspend fun discography(id: String, offset: Int = 0, limit: Int = 50): Page<Album>
    suspend fun related(id: String): List<Artist>
}

/** A uri cut into its three parts. */
data class ItemUri(val provider: String, val kind: String, val id: String) {
    override fun toString() = "$provider:$kind:$id"

    companion object {
        fun parse(uri: String): ItemUri {
            val p = uri.split(':', limit = 3)
            require(p.size == 3 && p.all { it.isNotEmpty() }) { "not an item uri: $uri" }
            return ItemUri(p[0], p[1], p[2])
        }
    }
}

internal fun <T> pageOf(items: List<T>, offset: Int, total: Int, asked: Int): Page<T> =
    Page(items, offset, total, (offset + asked).takeIf { items.isNotEmpty() && it < total })

internal fun String.q(): String = java.net.URLEncoder.encode(this, Charsets.UTF_8)
