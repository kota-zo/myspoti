package com.music.spotui.data.offline

import com.music.spotui.extensions.MediaHttp

/**
 * The first bytes of the next song an extension streams (an `audio-source` answer with a url),
 * read ahead while the one before plays: the player starts on them at once and asks the rest of
 * the file from where they end (an HTTP range). One song at a time, in memory, dropped once served
 * or stale. Anything unusual (no range support, a short file, an error) leaves no head, and the
 * song opens as before.
 */
internal object StreamHead {
    /** How much is read ahead. */
    const val BYTES = 512 * 1024

    class Head(val key: String, val url: String, val bytes: ByteArray, val total: Long, val until: Long)

    private var slot: Head? = null

    @Synchronized fun put(head: Head) { slot = head }

    /**
     * The head of the song [key] read from [url], given once; null when there is none, it is of
     * another address, or it went stale ([Head.until]).
     */
    @Synchronized fun take(key: String, url: String, now: Long = System.currentTimeMillis()): Head? {
        val h = slot ?: return null
        if (h.key != key) {
            if (now >= h.until) slot = null
            return null
        }
        slot = null
        return h.takeIf { it.url == url && now < it.until }
    }

    /** The total of a `Content-Range: bytes 0-524287/9876543`, or null. */
    fun total(contentRange: String?): Long? =
        contentRange?.takeIf { it.trim().startsWith("bytes 0-") }?.substringAfter('/')?.trim()?.toLongOrNull()

    /**
     * Reads the first [BYTES] of [url] (with the extension's [headers]) and keeps them for the
     * song [key] until [until]. Stops early, keeping nothing, once [active] says the song changed.
     */
    fun fetch(key: String, url: String, headers: Map<String, String>, until: Long, active: () -> Boolean) {
        // The safe client: no local address, redirects re-checked, the extension's headers kept on its own host only.
        MediaHttp.open(url, headers = headers, fixed = mapOf("Range" to "bytes=0-${BYTES - 1}")).use { c ->
            if (c.response.code != 206) return
            val total = total(c.response.header("Content-Range")) ?: return
            if (total <= BYTES) return
            val buf = ByteArray(BYTES)
            var at = 0
            c.response.body.byteStream().use { s ->
                while (at < BYTES) {
                    if (!active()) return
                    val n = s.read(buf, at, BYTES - at)
                    if (n < 0) break
                    at += n
                }
            }
            if (at == BYTES && active()) put(Head(key, url, buf, total, until))
        }
    }
}
