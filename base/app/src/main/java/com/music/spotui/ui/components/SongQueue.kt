package com.music.spotui.ui.components

import android.content.Context
import android.widget.Toast
import app.myspoti.engine.R
import com.music.spotui.data.catalog.CatalogRuntime
import com.music.spotui.data.catalog.SpotifyMatch
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.di.SongPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "Add to queue" and "Play next", for every place that offers them (the song menus, the
 * swipe on a row). A song of another provider (a search's while Spotify is not first in the
 * metadata order) has no Spotify id: it is first found on Spotify, as a tap on it plays it,
 * instead of the swipe doing nothing and the menu rows being greyed out.
 */
object SongQueue {
    /** Whether [song] can be queued at all: a Spotify song, or one of another provider to look up. */
    fun canQueue(song: SongsModel): Boolean =
        song.spotifyTrackId.isNotBlank() || song.url.startsWith(CatalogRuntime.PLAY_PREFIX)

    /** Queues [song] (after the song playing when [next]) and says so; a song Spotify lacks says that instead. */
    fun add(context: Context, song: SongsModel, next: Boolean = false) {
        val appContext = context.applicationContext
        val id = song.spotifyTrackId
        if (id.isNotBlank()) { queue(appContext, id, next); return }
        if (!song.url.startsWith(CatalogRuntime.PLAY_PREFIX)) return
        val uri = song.url.removePrefix(CatalogRuntime.PLAY_PREFIX)
        app.myspoti.host.android.MyspotiRuntime.scope.launch {
            val m = try { CatalogRuntime.spotifyMatchFor(uri) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                SpotifyMatch.Unavailable(e.message ?: e.javaClass.simpleName)
            }
            withContext(Dispatchers.Main) {
                if (m is SpotifyMatch.Found) queue(appContext, m.id, next)
                else SpotifyMatch.message(m)?.let { Toast.makeText(appContext, it, Toast.LENGTH_SHORT).show() }
            }
        }
    }

    private fun queue(context: Context, spotifyTrackId: String, next: Boolean) {
        if (next) SongPlayer.playNext(spotifyTrackId) else SongPlayer.addToQueue(spotifyTrackId)
        val said = if (next) R.string.context_menu_play_next else R.string.snackbar_added_to_queue
        Toast.makeText(context, said, Toast.LENGTH_SHORT).show()
    }
}
