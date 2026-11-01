package com.music.spotui.di

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect

/**
 * Saved state of a library item (track in Liked Songs, saved album, followed artist)
 * as Spotify reports it. Every screen reads the same [SongPlayer.library], so a like
 * made anywhere (here or on another device) shows everywhere.
 */
@Composable
fun rememberSaved(uri: String, fallback: Boolean = false): Boolean {
    LaunchedEffect(uri) { SongPlayer.requestSaved(uri) }
    return SongPlayer.library[uri] ?: fallback
}

@Composable
fun rememberTrackSaved(trackId: String): Boolean =
    if (trackId.isBlank()) false else rememberSaved("spotify:track:$trackId")

/**
 * Whether a track is liked, for a row of a list: what Spotify last reported ([SongPlayer.library], which a like
 * made anywhere moves at once), else the list's own batch check [known] (the uris it asked about in one go).
 */
fun trackSaved(trackId: String, known: Set<String>): Boolean {
    val uri = "spotify:track:$trackId"
    return SongPlayer.library[uri] ?: (uri in known)
}
