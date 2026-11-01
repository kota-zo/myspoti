package com.music.spotui.di

import kotlin.random.Random

/**
 * Myspoti's shuffle of a list (see [AppShuffle]): every song of the list once, in an order drawn
 * once, before the list starts over. A song twice in the list is there twice. [at] is the index of
 * the song playing in the order.
 */
internal object ShuffleOrder {
    /** Fisher-Yates over [songs]; [first] (one of them) opens the order. */
    fun <T> draw(songs: List<T>, first: T?, random: Random = Random.Default): List<T> {
        val order = songs.toMutableList()
        for (i in order.lastIndex downTo 1) {
            val j = random.nextInt(i + 1)
            val t = order[i]; order[i] = order[j]; order[j] = t
        }
        val from = if (first == null) -1 else order.indexOf(first)
        if (from > 0) order.add(0, order.removeAt(from))
        return order
    }

    /**
     * The order dealt before, for the list as it is now ([songs]): the songs still in it keep their
     * place, the ones gone are dropped, and the ones added since go at random places after the song
     * playing, so they still come in this round. The new order and the playing song's index in it.
     */
    fun <T> refresh(order: List<T>, at: Int, songs: List<T>, random: Random = Random.Default): Pair<List<T>, Int> {
        val left = songs.groupingBy { it }.eachCount().toMutableMap()
        val kept = ArrayList<T>(songs.size)
        var now = -1
        order.forEachIndexed { i, t ->
            val n = left[t] ?: 0
            if (n > 0) {
                left[t] = n - 1
                if (i == at) now = kept.size
                kept += t
            } else if (i <= at) now = kept.lastIndex
        }
        val added = songs.filter { t -> (left[t] ?: 0).let { n -> if (n > 0) { left[t] = n - 1; true } else false } }
        added.forEach { kept.add(now + 1 + random.nextInt(kept.size - now), it) }
        return kept to now
    }

    /**
     * [song] picked in the list while its order plays: it plays now, right after the song at [at]
     * (the songs before stay as heard, the rest keeps its order). Its first place still to come is
     * taken, else its last one. The new order and the picked song's index; null: not in the order.
     */
    fun <T> pick(order: List<T>, at: Int, song: T): Pair<List<T>, Int>? {
        val from = (at + 1 until order.size).firstOrNull { order[it] == song } ?: order.lastIndexOf(song).takeIf { it >= 0 } ?: return null
        val to = if (from > at) at + 1 else at.coerceAtLeast(0)
        if (from == to) return order to to
        val moved = order.toMutableList()
        moved.add(to, moved.removeAt(from))
        return moved to to
    }
}
