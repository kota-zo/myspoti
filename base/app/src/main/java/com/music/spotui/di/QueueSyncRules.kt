package com.music.spotui.di

/**
 * The queue sync's decisions (see [QueueSync]), without Android: the songs the user queued while
 * the local player plays are Spotify's queue too (the account's, seen by every device and every
 * install of the app), and a local player that starts takes Spotify's queued songs back.
 * Songs are Spotify uris; a device file without one stays in the local queue only.
 */
object QueueSyncRules {
    /** [uris] go into Spotify's queue before its queued item at [beforeIndex] (null: at its end). */
    data class Insert(val uris: List<String>, val beforeIndex: Int?)

    /** What Spotify's queue needs: its queued items at [remove] go (indexes), then [inserts], in order. */
    data class Plan(val remove: List<Int>, val inserts: List<Insert>) {
        val empty: Boolean get() = remove.isEmpty() && inserts.isEmpty()
    }

    /**
     * Brings Spotify's queued songs ([page], in order) to the local queue's ([local], in order).
     * Page items are matched to the local ones in order (each at the first place after the last
     * match); a local song with no match is inserted before the next matched item (at the end when
     * none), so "Play next" lands first and "Add to queue" last. A page item with no match goes only
     * if the sync put it there ([owned]: how many of each uri it did): another device's queued song
     * stays. A reorder is a removal and an insertion. [current]: the song the local player is on, whose
     * first queued copy the mirror is about to play from the queue (it consumes it): never removed
     * meanwhile (null once the mirror is on it: a copy left then is a duplicate).
     */
    fun plan(local: List<String>, page: List<String>, owned: Map<String, Int>, current: String? = null): Plan {
        val matched = BooleanArray(page.size)
        val spare = page.indexOfFirst { it == current }
        val inserts = ArrayList<Insert>()
        val pending = ArrayList<String>()
        var from = 0
        for (uri in local) {
            val at = (from until page.size).firstOrNull { it != spare && page[it] == uri }
            if (at == null) { pending += uri; continue }
            matched[at] = true
            if (pending.isNotEmpty()) { inserts += Insert(pending.toList(), at); pending.clear() }
            from = at + 1
        }
        if (pending.isNotEmpty()) inserts += Insert(pending.toList(), null)
        val left = HashMap(owned)
        val remove = page.indices.filter { i ->
            if (matched[i] || i == spare) return@filter false
            val n = left[page[i]] ?: 0
            if (n <= 0) return@filter false
            left[page[i]] = n - 1
            true
        }
        return Plan(remove, inserts)
    }

    /**
     * A local player starts on a list ([localNext]: the songs after the picked one, in order) while
     * Spotify's queue holds [pageQueued]: those play first, as Spotify plays its queue before the list.
     * For each queued song in order, the index in [localNext] of the song that is it (the list
     * already has it right there: the takeover lists put Spotify's queue after the picked song), or
     * null when it must be added. The matched ones are always the first of [localNext], in order.
     */
    fun adopt(localNext: List<String>, pageQueued: List<String>): List<Int?> {
        var k = 0
        return pageQueued.map { uri -> if (localNext.getOrNull(k) == uri) k++ else null }
    }

    /** How the mirror puts the page on the local player's new song. */
    sealed interface Way {
        /** The page plays it already (it went on to its queued copy by itself): only kept in step. */
        data object Already : Way
        /** Its copy in Spotify's queue, at [index] of the queued items: a skip to it, which consumes it. */
        data class Skip(val index: Int) : Way
        /** A play (in the local list's Spotify list): Spotify keeps its user queue across a new play. */
        data object Play : Way
    }

    /**
     * [uri] is the local player's new song; the page is on [pageUri] ([pagePlaying]: not paused)
     * with [pageQueued] queued. A queued copy is skipped to rather than played anew: a play would
     * leave the copy queued, and Spotify would play the song a second time after it.
     */
    fun way(uri: String, pageUri: String?, pagePlaying: Boolean, pageQueued: List<String>): Way = when {
        pageUri == uri && pagePlaying -> Way.Already
        else -> pageQueued.indexOf(uri).takeIf { it >= 0 }?.let { Way.Skip(it) } ?: Way.Play
    }

    /**
     * Before the mirror's first play of a run ([firstPlay]), another device being Spotify Connect's
     * active one ([elsewhere]): playback is pulled here first. Sent as is, the page's play would be
     * a remote command to that device (another install of the app there takes it as playback "back
     * on this phone" and the two fight over it).
     */
    fun pullsHere(firstPlay: Boolean, elsewhere: Boolean): Boolean = firstPlay && elsewhere
}
