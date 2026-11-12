package com.music.spotui.ui.screens

import com.music.spotui.data.api.Response
import com.music.spotui.data.entity.ContextPageModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull

/**
 * The list [pages] (a page flow, or a page's state) once read in full. A long playlist comes in
 * 100-song steps: its first step alone leaves the rest of the list out. A list on storage comes
 * whole at once and is taken as it is. Null: the list could not be read.
 */
internal suspend fun wholeList(pages: Flow<Response<ContextPageModel>>): ContextPageModel? =
    (pages.firstOrNull { it is Response.Error || (it is Response.Success && it.data.complete) } as? Response.Success)?.data
