package com.music.spotui.ui.components

import android.app.Activity
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Rational
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import app.myspoti.engine.R
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import com.bumptech.glide.integration.compose.placeholder
import com.music.spotui.di.Graph
import com.music.spotui.di.SongPlayer

/**
 * The song in a picture-in-picture window ([com.music.spotui.data.preferences.SongPipPref], off by
 * default): asked for on Discord by people whose phone kills apps in the background, with the
 * lyrics and the playback buttons (the window's own actions: previous, play/pause, next).
 */
object SongPip {
    private const val ACTION = "app.myspoti.PIP_CONTROL"
    private const val EXTRA = "what"

    /** A card a little taller than wide, like the one shared on Discord. */
    private val RATIO = Rational(2, 3)

    fun supported(c: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && c.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    fun wanted(): Boolean =
        supported(Graph.app) && com.music.spotui.data.preferences.SongPipPref.on && SongPlayer.isPlaying() && Graph.currentSongState.title.value.isNotBlank()

    @RequiresApi(Build.VERSION_CODES.O)
    fun params(c: Context): PictureInPictureParams {
        val playing = Graph.currentSongState.playingState.value
        return PictureInPictureParams.Builder()
            .setAspectRatio(RATIO)
            .setActions(
                listOf(
                    action(c, 0, app.myspoti.host.android.R.drawable.msp_ic_previous, R.string.pip_previous, "prev"),
                    if (playing) action(c, 1, app.myspoti.host.android.R.drawable.msp_ic_pause, R.string.pip_pause, "toggle")
                    else action(c, 1, app.myspoti.host.android.R.drawable.msp_ic_play, R.string.pip_play, "toggle"),
                    action(c, 2, app.myspoti.host.android.R.drawable.msp_ic_next, R.string.pip_next, "next"),
                ),
            )
            .build()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun action(c: Context, code: Int, icon: Int, label: Int, what: String): RemoteAction {
        val intent = Intent(ACTION).setPackage(c.packageName).putExtra(EXTRA, what)
        val pending = PendingIntent.getBroadcast(c, code, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val text = c.getString(label)
        return RemoteAction(Icon.createWithResource(c, icon), text, text, pending)
    }

    /** Listens to the window's buttons while it is shown; returns what [Activity.unregisterReceiver] takes. */
    fun listen(activity: Activity): BroadcastReceiver {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.getStringExtra(EXTRA)) {
                    "prev" -> SongPlayer.previous()
                    "next" -> SongPlayer.next()
                    "toggle" -> if (SongPlayer.isPlaying()) SongPlayer.pause() else SongPlayer.play()
                }
            }
        }
        ContextCompat.registerReceiver(activity, receiver, IntentFilter(ACTION), ContextCompat.RECEIVER_NOT_EXPORTED)
        return receiver
    }
}

/** What the song's picture-in-picture window shows: the cover, the title and artist, the line being sung. */
@OptIn(ExperimentalGlideComposeApi::class)
@Composable
fun PipSong() {
    val song = Graph.currentSongState
    val title = song.title.value
    val artist = song.singer.value
    val (line, next) = com.music.spotui.ui.screens.rememberPipLyricLines(title, artist)
    Column(
        Modifier.fillMaxSize().background(Color(0xFF121212)).padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (line == null) {
            GlideImage(
                model = song.coverUri.value.takeIf { it.isNotBlank() },
                contentDescription = null,
                contentScale = ContentScale.Crop,
                loading = placeholder(R.drawable.placeholder),
                failure = placeholder(R.drawable.placeholder),
                modifier = Modifier.fillMaxWidth(0.8f).aspectRatio(1f).clip(RoundedCornerShape(8.dp)),
            )
            Spacer(Modifier.height(8.dp))
        }
        Text(title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1,
            overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        Text(artist, color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp, maxLines = 1,
            overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        if (line != null) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(line, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 4,
                        overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                    if (next != null) {
                        Spacer(Modifier.height(6.dp))
                        Text(next, color = Color.White.copy(alpha = 0.45f), fontSize = 12.sp, maxLines = 2,
                            overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                    }
                }
            }
        }
    }
}
