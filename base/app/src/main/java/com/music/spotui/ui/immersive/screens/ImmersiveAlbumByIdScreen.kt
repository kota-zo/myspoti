package com.music.spotui.ui.immersive.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavController
import app.myspoti.engine.R
import com.music.spotui.di.Graph
import com.music.spotui.ui.immersive.Immersive
import com.music.spotui.ui.immersive.components.ImmersiveMessageState
import com.music.spotui.ui.immersive.shell.ImmersiveTopBar
import com.music.spotui.ui.immersive.shell.immersiveTopContentPadding

/**
 * Opens the album page by Spotify id in the Immersive look, resolving it from the name only when
 * needed (the Classic `AlbumByIdScreen`'s look-up). The page itself is [ImmersiveContextScreen].
 */
@Composable
fun ImmersiveAlbumByIdScreen(navController: NavController, name: String, artist: String, id: String) {
    var resolved by remember(name, artist, id) { mutableStateOf(id.ifBlank { null }) }
    var failed by remember { mutableStateOf(false) }
    // Bumped by Try again: the look-up runs once more.
    var attempt by remember { mutableStateOf(0) }
    LaunchedEffect(name, artist, id, attempt) {
        if (resolved == null) {
            resolved = Graph.api.resolveAlbumId(name, artist)
            if (resolved == null) failed = true
        }
    }
    val albumId = resolved
    when {
        albumId != null -> ImmersiveContextScreen(navController, type = "album", id = albumId)
        failed -> Box(Modifier.fillMaxSize().background(Immersive.Page)) {
            ImmersiveMessageState(
                message = stringResource(R.string.album_not_found),
                actionLabel = stringResource(R.string.retry_button),
                onAction = { failed = false; attempt++ },
                modifier = Modifier.padding(top = immersiveTopContentPadding()),
            )
            ImmersiveTopBar(title = "", collapse = { 0f }, onBack = { navController.navigateUp() })
        }
        else -> BoxWithConstraints(Modifier.fillMaxSize().background(Immersive.Page)) {
            ContextLoading(navController, sleeve = albumSleeve(maxWidth, maxHeight, com.music.spotui.ui.components.windowSize().large))
        }
    }
}
