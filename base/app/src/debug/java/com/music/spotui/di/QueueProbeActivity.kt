package com.music.spotui.di

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.ui.immersive.lyrics.ImmersiveQueuePanel

/**
 * Debug builds only: the player's queue panel over a made-up queue of [ROWS] songs of an extension
 * (the local player's rows), as tall as in the player, to drag rows on an emulator:
 * adb shell am start -n <package>/com.music.spotui.di.QueueProbeActivity [--ei queued <n>]
 */
class QueueProbeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val queuedCount = intent.getIntExtra("queued", 0)
        val items = (1..ROWS).map { i ->
            val song = SongsModel(i, "Song %02d".format(i), "Album", "Artist $i", "", "", spotifyTrackId = "")
            SongPlayer.QueueItem(song, "local:-$i", (i - 1).toString(), SongPlayer.LOCAL)
        }
        SongPlayer.showProbeQueue(SongPlayer.EngineQueue(
            current = SongsModel(0, "Now playing", "Album", "Artist", "", "", spotifyTrackId = ""),
            queued = items.take(queuedCount),
            nextUp = items.drop(queuedCount),
        ))
        setContent {
            Column(Modifier.fillMaxSize().background(Color(0xFF3B2A2E)).statusBarsPadding()) {
                // The player's header (cover, title) above the panel, its controls under it.
                Box(Modifier.fillMaxWidth().height(110.dp).background(Color(0xFF2A1E21)))
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    ImmersiveQueuePanel(Modifier.fillMaxSize(), PaddingValues(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 12.dp))
                }
                Box(Modifier.fillMaxWidth().height(330.dp).background(Color(0xFF2A1E21)))
            }
        }
    }

    private companion object { const val ROWS = 30 }
}
