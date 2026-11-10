package com.music.spotui.ui.screens

import com.music.spotui.data.api.Response
import com.music.spotui.data.entity.ContextPageModel
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Smart shuffle mixes the whole list (#207): the page is taken once every song of it is loaded,
 * not at its first 100-song step.
 */
class SmartShuffleWholeListTest {
    private fun page(name: String, complete: Boolean) =
        ContextPageModel(uri = "spotify:playlist:p", type = "playlist", name = name, coverUri = "", complete = complete)

    @Test fun waitsForTheCompletePage() = runBlocking {
        val pages = flowOf<Response<ContextPageModel>>(
            Response.Loading(),
            Response.Success(page("first 100", complete = false)),
            Response.Success(page("first 300", complete = false)),
            Response.Success(page("all", complete = true)),
        )
        assertEquals("all", SmartShuffle.completeOf(pages)?.name)
    }

    @Test fun aPageAlreadyCompleteIsTakenAtOnce() = runBlocking {
        var read = 0
        val pages = flow<Response<ContextPageModel>> {
            emit(Response.Success(page("cached", complete = true)))
            read++
            emit(Response.Success(page("later", complete = true)))
        }
        assertEquals("cached", SmartShuffle.completeOf(pages)?.name)
        assertEquals("nothing is read after the complete page", 0, read)
    }

    @Test fun anErrorGivesNothing() = runBlocking {
        val pages = flowOf<Response<ContextPageModel>>(Response.Loading(), Response.Error("refused"))
        assertNull(SmartShuffle.completeOf(pages))
    }

    @Test fun aListThatNeverCompletesGivesNothing() = runBlocking {
        val pages = flowOf<Response<ContextPageModel>>(Response.Success(page("first 100", complete = false)))
        assertNull(SmartShuffle.completeOf(pages))
    }
}
