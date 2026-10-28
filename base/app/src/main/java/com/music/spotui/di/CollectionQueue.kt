package com.music.spotui.di

import android.widget.Toast
import app.myspoti.engine.R
import com.music.spotui.data.entity.ContextPageModel
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.data.api.Response
import com.music.spotui.ui.screens.TrackOrder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * "Play next" / "Add to queue" on a playlist, an album or Liked Songs. The songs are the whole
 * list (a big playlist that has not finished loading is read to its end first), in the order the
 * list is played in (the user's sort for it), and [SongPlayer.queueSongs] sends each to the queue
 * that can play it. Runs in the app's own scope: closing the menu does not cancel it.
 *
 * Like the single-song action, nothing is started when nothing plays: Spotify keeps the queued
 * songs for the next play.
 */
object CollectionQueue {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private const val READ_MS = 120_000L

    /** [known]: the page on screen; used as it is when complete, else the list is read from [uri]. */
    fun queue(uri: String, next: Boolean, known: ContextPageModel? = null) {
        val app = Graph.app
        scope.launch {
            val shown = known?.takeIf { it.complete }
            if (shown == null) withContext(Dispatchers.Main) { Toast.makeText(app, R.string.add_collection_reading, Toast.LENGTH_SHORT).show() }
            val songs = shown?.tracks ?: read(uri)
            val list = songs?.let { TrackOrder.load(app, uri).apply(it) }.orEmpty()
            if (list.isEmpty()) {
                withContext(Dispatchers.Main) { Toast.makeText(app, if (songs == null) R.string.page_load_failed else R.string.queue_list_none, Toast.LENGTH_SHORT).show() }
                return@launch
            }
            SongPlayer.queueSongs(list, next) { count ->
                val text = if (count == 0) app.getString(R.string.queue_list_none)
                    else app.resources.getQuantityString(if (next) R.plurals.queue_list_next else R.plurals.queue_list_added, count, count)
                Toast.makeText(app, text, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** Every song of the playlist, album or Liked Songs [uri]; null when it cannot be read. */
    private suspend fun read(uri: String): List<SongsModel>? {
        val api = Graph.api
        val id = uri.substringAfterLast(':')
        val flow = when {
            uri.startsWith("spotify:album:") -> api.getAlbumPage(id)
            uri == "spotify:collection:tracks" -> api.getLikedPage()
            uri.startsWith("spotify:playlist:") -> api.getPlaylistPage(id)
            else -> return null
        }
        val last = withTimeoutOrNull(READ_MS) { flow.first { it is Response.Error || (it is Response.Success && it.data.complete) } }
        return (last as? Response.Success)?.data?.tracks
    }
}
