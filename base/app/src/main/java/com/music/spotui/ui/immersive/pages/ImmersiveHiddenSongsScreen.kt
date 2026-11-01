package com.music.spotui.ui.immersive.pages

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.navigation.NavController
import app.myspoti.engine.R
import com.music.spotui.data.preferences.HiddenSongs
import com.music.spotui.ui.immersive.Immersive
import com.music.spotui.ui.immersive.components.immersiveClickable
import androidx.compose.ui.unit.dp

/**
 * Settings > Hidden songs (Immersive): the songs marked "Not interested" (see [HiddenSongs]),
 * newest first, each with a "Show again" that takes it off the list.
 */
@Composable
fun ImmersiveHiddenSongsScreen(navController: NavController) {
    val context = LocalContext.current
    val songs = HiddenSongs.songs
    ImmersivePage(title = stringResource(R.string.hidden_songs_title), onBack = { navController.popBackStack() }) {
        if (songs.isEmpty()) immersiveBlock("empty") {
            Text(stringResource(R.string.hidden_songs_empty), style = Immersive.Subhead, textAlign = TextAlign.Center)
        } else immersiveGroup("songs", footerRes = R.string.hidden_songs_detail) {
            songs.forEach { s ->
                row {
                    ImmersiveGroupRow(
                        s.title, detail = s.singer,
                        trailing = {
                            Text(
                                stringResource(R.string.show_song_again),
                                style = Immersive.Body.copy(color = Immersive.Accent),
                                modifier = Modifier.immersiveClickable(onClick = { HiddenSongs.show(context, s.id) }).padding(8.dp),
                            )
                        },
                    )
                }
            }
        }
    }
}
