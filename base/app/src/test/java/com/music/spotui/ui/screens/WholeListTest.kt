package com.music.spotui.ui.screens

import com.music.spotui.data.api.Response
import com.music.spotui.data.entity.ContextPageModel
import com.music.spotui.data.entity.SongsModel
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WholeListTest {
    private fun page(songs: Int, complete: Boolean): Response<ContextPageModel> = Response.Success(ContextPageModel(
        uri = "spotify:playlist:p", type = "playlist", name = "p", coverUri = "", complete = complete,
        tracks = (1..songs).map { SongsModel(it, "s$it", "", "", "", "spotify:track:$it", spotifyTrackId = "$it") },
    ))

    @Test fun aLongPlaylistIsReadToItsEnd() = runBlocking {
        // Reported (#207): smart shuffle from the player mixed only the first 100 songs of a longer playlist.
        val pages = flowOf<Response<ContextPageModel>>(Response.Loading(), page(100, false), page(200, false), page(250, true))
        assertEquals(250, wholeList(pages)?.tracks?.size)
    }

    @Test fun aListOnStorageIsTakenAtOnce() = runBlocking {
        // Liked Songs on storage come whole first: the refresh behind them is not waited for.
        var sent = 0
        val pages = flow { sent++; emit(page(40, true)); sent++; emit(page(41, true)) }
        assertEquals(40, wholeList(pages)?.tracks?.size)
        assertEquals(1, sent)
    }

    @Test fun aListThatCannotBeReadIsNone() = runBlocking {
        assertNull(wholeList(flowOf<Response<ContextPageModel>>(Response.Loading(), page(100, false), Response.Error("refused"))))
        assertNull(wholeList(flowOf<Response<ContextPageModel>>(Response.Loading(), page(100, false))))
    }
}
