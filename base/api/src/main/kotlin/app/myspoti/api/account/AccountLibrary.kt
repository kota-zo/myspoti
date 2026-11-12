/* SPDX-License-Identifier: GPL-3.0-only */
package app.myspoti.api.account

import app.myspoti.api.Page
import app.myspoti.api.Playlist
import app.myspoti.api.Track
import app.myspoti.api.Uri

/**
 * The account providers the user can choose for the listen sync and the account's library (one at a
 * time): their keys, as in [app.myspoti.api.sync.ListenSync.provider] and the catalog's uris.
 */
object AccountProviders {
    const val SPOTIFY = "spotify"
    const val YTMUSIC = "ytmusic"
    const val DEEZER = "deezer"
    const val NONE = "none"

    /** The choices offered now, in the order shown. Deezer is left out: see the research notes (no documented web player control). */
    val OFFERED = listOf(SPOTIFY, YTMUSIC, NONE)

    /** [stored] if it is a choice offered now, else [fallback]. */
    fun normalized(stored: String?, fallback: String = SPOTIFY): String = stored?.takeIf { it in OFFERED } ?: fallback
}

/**
 * A neutral read of the signed-in account's own library, for a provider whose full library contract
 * ([app.myspoti.api.Library], [app.myspoti.api.Playlists]) is not implemented: its liked songs, its
 * playlists and their songs, and what it played lately. Uris are the provider's own
 * (`ytmusic:track:{videoId}`, `ytmusic:playlist:{id}`). Read only: nothing is changed on the account.
 */
interface AccountLibrary {
    /** The provider's key ([AccountProviders]). */
    val provider: String

    /** The account's liked songs, newest first as the provider lists them. */
    suspend fun likedTracks(offset: Int = 0, limit: Int = 50): Page<Track>

    /** The account's playlists (its own and those it saved), as the provider lists them. */
    suspend fun playlists(): List<Playlist>

    /** The songs of one of [playlists] (or any playlist the account can see). */
    suspend fun playlistTracks(uri: Uri, offset: Int = 0, limit: Int = 100): Page<Track>

    /** The songs the account played lately, newest first; empty where the provider keeps no history it shows. */
    suspend fun history(limit: Int = 50): List<Track>
}

/** The account's session ended or never began (signed out): the read needs a sign-in. */
class SignedOut(provider: String) : java.io.IOException("$provider: not signed in")
