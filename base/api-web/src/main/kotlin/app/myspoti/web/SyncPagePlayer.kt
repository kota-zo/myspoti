/* SPDX-License-Identifier: GPL-3.0-only */
package app.myspoti.web

import kotlinx.coroutines.flow.StateFlow

/**
 * The provider's web player in its hidden page, as [WebListenSync] uses it: its status and the few
 * commands of its own player. The page is always unheard (its media held at a volume nobody hears,
 * never the player's own mute or volume setting) and plays only the song it was asked for: another
 * song it starts by itself (its autoplay, a suggestion) is paused at once by the page script.
 */
interface SyncPagePlayer {
    /** The page is loaded, its script answered, and the account is signed in: it takes commands. */
    val ready: StateFlow<Boolean>
    /** The page plays an ad now: commands wait, the page is put back in step after it. */
    val adPlaying: StateFlow<Boolean>
    /** The page's last state (null: no player there yet). */
    val state: StateFlow<PageState?>

    /**
     * Plays [trackId] on the page from [positionMs] (read when the page is ready to seek, after a
     * load), and returns once the page shows that song or it gave up.
     */
    suspend fun play(trackId: String, positionMs: () -> Long)
    suspend fun pause()
    suspend fun resume()
    suspend fun seek(positionMs: Long)
    /** Nothing is asked of the page any more: whatever it plays is paused. */
    suspend fun release()
    /**
     * Loads the page again, on no song (its playback stalled): the sync puts the asked song back on it
     * once it is ready.
     */
    suspend fun reload()
}

/**
 * One state of the page's player: its song ([trackId], the provider's id), whether it is [paused] or
 * [buffering], its position at [timestampMs] (epoch ms, the page's clock), its song's length, and
 * whether an [ad] plays.
 */
data class PageState(
    val trackId: String?,
    val paused: Boolean,
    val buffering: Boolean,
    val positionMs: Long,
    val timestampMs: Long,
    val durationMs: Long,
    val ad: Boolean = false,
) {
    /** Where it is at [nowMs], within its song. */
    fun positionAt(nowMs: Long): Long {
        val p = if (paused || buffering) positionMs else positionMs + (nowMs - timestampMs)
        return if (durationMs > 0) p.coerceIn(0, durationMs) else p.coerceAtLeast(0)
    }
}
