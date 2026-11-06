package com.music.spotui.di

/**
 * The local player's play order with Spotify's two parts after the current song: the songs the
 * user queued ("Next in queue", right after it, in the order they were added), then the rest of
 * the list ("Next from"). Songs are told apart by identity (a list may hold one song twice);
 * [isQueued] says which are queued. [cur] is the current song's index.
 */
internal object LocalQueueOrder {
    /** Where a song added goes: right after the current one ([next]), else after the songs queued before it. */
    fun <T> insertIndex(order: List<T>, cur: Int, next: Boolean, isQueued: (T) -> Boolean): Int =
        (cur + 1 + if (next) 0 else queuedAfter(order, cur, isQueued)).coerceIn(0, order.size)

    /** How many queued songs follow the current one. */
    fun <T> queuedAfter(order: List<T>, cur: Int, isQueued: (T) -> Boolean): Int =
        order.drop(cur + 1).takeWhile(isQueued).size

    /** [order] with the queued songs after the current one moved up right after it, in their order. */
    fun <T> settle(order: List<T>, cur: Int, isQueued: (T) -> Boolean): List<T> {
        val tail = order.drop(cur + 1)
        return order.take(cur + 1) + tail.filter(isQueued) + tail.filterNot(isQueued)
    }

    /**
     * A tap on the song at [target] of the list: the list's songs before it are skipped, the queued
     * ones stay queued, right after it (as Spotify keeps them). The new order and [target]'s index in it.
     */
    fun <T> skip(order: List<T>, cur: Int, target: Int, isQueued: (T) -> Boolean): Pair<List<T>, Int> {
        if (target <= cur || target !in order.indices || isQueued(order[target])) return order to target
        val between = order.subList(cur + 1, target)
        val skipped = between.filterNot(isQueued)
        return (order.take(cur + 1) + skipped + order[target] + between.filter(isQueued) + order.drop(target + 1)) to cur + 1 + skipped.size
    }

    /** Shuffle on: the queued songs keep their place and order, the rest of the list is [shuffle]d. */
    fun <T> shuffled(order: List<T>, cur: Int, isQueued: (T) -> Boolean, shuffle: (List<T>) -> List<T>): List<T> {
        val tail = order.drop(cur + 1)
        return order.take(cur + 1) + tail.filter(isQueued) + shuffle(tail.filterNot(isQueued))
    }

    /**
     * A list played from the song at [cur] with shuffle on: that song first, the queued songs right
     * after it, then every other song of the list [shuffle]d, those before [cur] too (as Spotify's
     * shuffle plays a whole list from a picked song). [shuffled] alone left the songs above the
     * pick out: a tap low in a 40-song list queued 5 to 10 of them (reported, B6).
     */
    fun <T> shuffledWhole(order: List<T>, cur: Int, isQueued: (T) -> Boolean, shuffle: (List<T>) -> List<T>): List<T> {
        val current = order.getOrNull(cur) ?: return order
        val others = order.filterIndexed { i, _ -> i != cur }
        return listOf(current) + order.drop(cur + 1).filter(isQueued) + shuffle(others.filterNot(isQueued))
    }

    /**
     * Shuffle off: the list back in its order [before] (songs added since at its end), the queued
     * songs still right after the current one.
     */
    fun <T> unshuffled(order: List<T>, cur: Int, before: List<T>, isQueued: (T) -> Boolean): List<T> {
        val current = order.getOrNull(cur)
        val queued = order.drop(cur + 1).filter(isQueued)
        val rest = before.filter { b -> order.any { it === b } && queued.none { it === b } } +
            order.filter { t -> before.none { it === t } && queued.none { it === t } }
        val at = rest.indexOfFirst { it === current }
        return if (at < 0) queued + rest else rest.take(at + 1) + queued + rest.drop(at + 1)
    }

    /**
     * The song at [from] dropped before / [after] the one at [target], into [target]'s part (queued
     * or the list's). The new order and whether the song is queued now; null when nothing moves.
     * [cur] is the current song's index: it keeps playing, and the queued songs stay right after it.
     */
    fun <T> moveBeside(order: List<T>, cur: Int, from: Int, target: Int, after: Boolean, isQueued: (T) -> Boolean): Pair<List<T>, Boolean>? {
        if (from !in order.indices || target !in order.indices || from == target) return null
        val t = order[from]
        val toQueue = isQueued(order[target])
        val rest = order.toMutableList().apply { removeAt(from) }
        val at = rest.indexOfFirst { it === order[target] }
        rest.add(if (after) at + 1 else at, t)
        val playing = order.getOrNull(cur)
        return settle(rest, rest.indexOfFirst { it === playing }, { x -> if (x === t) toQueue else isQueued(x) }) to toQueue
    }

    /** The song at [from] queued as the first queued one, right after the current song. */
    fun <T> moveToFront(order: List<T>, cur: Int, from: Int): List<T>? {
        if (from !in order.indices || from == cur) return null
        val rest = order.toMutableList().apply { removeAt(from) }
        val playing = order[cur]
        rest.add(rest.indexOfFirst { it === playing } + 1, order[from])
        return rest
    }
}
