package com.music.spotui.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.myspoti.engine.R
import com.music.spotui.data.preferences.HiddenSongs
import com.music.spotui.ui.theme.AppAccent
import com.music.spotui.ui.theme.AppText
import com.music.spotui.ui.theme.tone

/**
 * The confirmation of "Not interested", with its Undo. Drawn by the app's root over both looks
 * (the menu that asked has closed, and so has the player's): above the bars, under nothing.
 */
@Composable
fun HiddenSongUndo() {
    val context = LocalContext.current
    val host = remember { SnackbarHostState() }
    val hidden = HiddenSongs.justHidden
    LaunchedEffect(hidden) {
        val song = hidden ?: return@LaunchedEffect
        val chosen = host.showSnackbar(
            message = context.getString(R.string.hidden_song_toast, song.title),
            actionLabel = context.getString(R.string.undo),
            duration = SnackbarDuration.Long,
        )
        if (chosen == SnackbarResult.ActionPerformed) HiddenSongs.show(context, song.id)
        HiddenSongs.justHidden = null
    }
    Box(Modifier.fillMaxSize().navigationBarsPadding().padding(bottom = 140.dp, start = 8.dp, end = 8.dp), contentAlignment = Alignment.BottomCenter) {
        SnackbarHost(host) { data ->
            Snackbar(snackbarData = data, containerColor = tone(0xFF282828), contentColor = AppText, actionContentColor = AppAccent)
        }
    }
}
