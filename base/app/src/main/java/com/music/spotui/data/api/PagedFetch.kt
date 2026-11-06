/* SPDX-License-Identifier: GPL-3.0-only */
package com.music.spotui.data.api

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * The pages of a list whose size the first page told: the rest asked [parallel] at a time and
 * kept in order. A 3,000-song Liked Songs was 60 requests one after the other (15 to 20 s); four
 * at a time it is 15 rounds. [onBatch] gets the whole list so far after the first page and after
 * each round (not after each page: a screen redrawn 60 times for one list).
 */
internal object PagedFetch {
    suspend fun <T> inOrder(
        first: List<T>,
        total: Int,
        pageSize: Int,
        parallel: Int = 4,
        fetch: suspend (offset: Int) -> List<T>,
        onBatch: suspend (List<T>) -> Unit = {},
    ): List<T> = coroutineScope {
        val out = ArrayList<T>(maxOf(total, first.size)).apply { addAll(first) }
        onBatch(out.toList())
        // A first page shorter than asked is the whole list, whatever the total said.
        if (first.size < pageSize) return@coroutineScope out
        val offsets = (first.size until total step pageSize).toList()
        for (round in offsets.chunked(parallel.coerceAtLeast(1))) {
            val pages = round.map { o -> async { fetch(o) } }.awaitAll()
            pages.forEach { out += it }
            onBatch(out.toList())
            // A page shorter than asked: the list ended sooner than its total (songs removed meanwhile).
            if (pages.any { it.size < pageSize }) break
        }
        out
    }
}
