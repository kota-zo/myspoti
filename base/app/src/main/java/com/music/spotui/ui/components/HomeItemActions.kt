package com.music.spotui.ui.components

import androidx.navigation.NavController
import com.music.spotui.data.api.Api
import com.music.spotui.data.entity.HomeItem
import com.music.spotui.di.SongPlayer
import com.music.spotui.ui.navigation.Routes
import com.music.spotui.ui.navigation.albumRoute
import com.music.spotui.ui.navigation.artistRoute
import com.music.spotui.ui.navigation.playlistRoute
import com.music.spotui.ui.navigation.showRoute
import com.music.spotui.ui.screens.ContextViewModel

/*
 * What a Home card does, shared by the Classic and Immersive feeds (only the card drawing
 * differs): where it goes and what is loaded before the tap.
 */

/** Albums and playlists start loading under the finger (see pressHint). */
internal fun prefetchHomeItem(item: HomeItem) {
    when (item) {
        is HomeItem.Album -> if (item.id.isNotBlank()) ContextViewModel.prefetch("album", item.id)
        is HomeItem.Playlist ->
            if (item.id == Api.HomeCache.LIKED_SONGS_ID) ContextViewModel.prefetch("liked", "")
            else if (item.id.isNotBlank()) ContextViewModel.prefetch("playlist", item.id)
        else -> {}
    }
}

/** The shortcuts at the top load in the background, ready before a tap. */
internal fun prefetchHomeShortcuts(shortcuts: List<HomeItem>?) {
    val top = shortcuts.orEmpty().mapNotNull { item ->
        when (item) {
            is HomeItem.Album -> item.id.takeIf { it.isNotBlank() }?.let { "album" to it }
            is HomeItem.Playlist ->
                if (item.id == Api.HomeCache.LIKED_SONGS_ID) "liked" to ""
                else item.id.takeIf { it.isNotBlank() }?.let { "playlist" to it }
            else -> null
        }
    }
    if (top.isNotEmpty()) ContextViewModel.prefetchSoon(top)
}

internal fun onHomeItemClick(navController: NavController, item: HomeItem) {
    when (item) {
        is HomeItem.Album -> navController.navigate(albumRoute(item.name, item.artists.ifBlank { item.subtitle }, item.id))
        is HomeItem.Artist -> navController.navigate(artistRoute(item.name, item.id))
        is HomeItem.Show ->
            if (item.isEpisode) SongPlayer.playSong("episode:${item.id}")
            else navController.navigate(showRoute(item.id, item.name))
        // Load the real playlist content by its Spotify id (daily mixes, etc).
        is HomeItem.Playlist ->
            if (item.id == Api.HomeCache.LIKED_SONGS_ID) navController.navigate(Routes.Liked.route)
            else if (item.id.isNotBlank()) navController.navigate(playlistRoute(item.id, item.name))
            else navController.navigate(albumRoute(item.name))
    }
}
