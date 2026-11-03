package app.myspoti.host.android

/**
 * The queue row the session marks as playing. Android's Bluetooth stack (AVRCP, what a car stereo,
 * a bike dashboard or headphones read) holds every now-playing change back while the marked row is
 * not the song in the metadata ("metadata out of sync", up to 2 s per change), and sends nothing
 * when the player becomes the active one in that state. The row is marked only when it is the song
 * shown (the same title and artist); otherwise none is ([NONE]), which the stack takes as in sync.
 * The queue lags the metadata at every song change and during ads.
 */
object QueueSync {
    /** MediaSessionCompat.QueueItem.UNKNOWN_ID: no row marked. */
    const val NONE = -1L

    fun activeRow(queue: List<QueueEntry>, activeId: Long, title: String?, artist: String?): Long {
        if (activeId == NONE) return NONE
        val row = queue.firstOrNull { it.id == activeId } ?: return NONE
        // Nothing shown yet: no metadata to be out of sync with.
        if (title == null) return activeId
        return if (row.title == title && row.artist == artist.orEmpty()) activeId else NONE
    }
}
