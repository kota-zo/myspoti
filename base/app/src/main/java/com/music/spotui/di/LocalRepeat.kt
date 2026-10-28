package com.music.spotui.di

/**
 * How [LocalPlayer] repeats, kept pure so it is tested off-device. [mode] is SongPlayer.repeatMode:
 * 0 off, 1 the queue, 2 the song.
 *
 * A song that follows itself (repeat one, or a queue of one song repeated) is not left to
 * ExoPlayer's own repeat: that plays the song again as a new period of the same item, and with an
 * extension's stream it was seen starting again near the last seek instead of at 0:00 (and earlier
 * on each loop). The player pauses at the song's end instead and Myspoti puts it back at 0:00.
 *
 * Repeat one is "repeat once": the song plays again one time, then the queue goes on and the mode
 * goes back to off ([atEnd]). A queue of one song repeated (repeat all) loops it for good.
 */
object LocalRepeat {
    const val OFF = 0
    const val ALL = 1
    const val ONE = 2

    /**
     * The song pauses at its end for Myspoti to play it again from 0:00 (see the object's note), or,
     * after repeat once's replay, to go on to the next song ([atEnd]).
     */
    fun loopsSong(mode: Int, items: Int): Boolean = mode == ONE || mode == ALL && items == 1

    /** The repeat ExoPlayer is given: its own only for a queue of several songs repeated. */
    fun playerMode(mode: Int, items: Int): Int =
        if (mode == ALL && !loopsSong(mode, items)) androidx.media3.common.Player.REPEAT_MODE_ALL
        else androidx.media3.common.Player.REPEAT_MODE_OFF

    /** How far into a song the player may report it at the start (a song starting plays from 0:00). */
    const val START_SLACK_MS = 1_500L

    /** A song the player moved to by itself (the next one, the queue again) that did not start at 0:00. */
    fun startedLate(positionMs: Long): Boolean = positionMs > START_SLACK_MS

    /** What a song that reached its end does. */
    enum class End {
        /** The player's own way: the next song, the queue again (repeat all), or the queue's end. */
        NEXT,
        /** Played again from 0:00 by Myspoti. */
        REPLAY,
        /** Repeat once and its replay just ended: the mode goes back to off and the queue goes on. */
        ONCE_DONE,
    }

    /** [replayed]: the song ending is the one repeat once already played again. */
    fun atEnd(mode: Int, items: Int, replayed: Boolean): End = when {
        mode == ONE -> if (replayed) End.ONCE_DONE else End.REPLAY
        mode == ALL && items == 1 -> End.REPLAY
        else -> End.NEXT
    }

    /** This close to the end the player knows, a seek is to the song's end (the bar dragged to its end). */
    const val SEEK_END_SLACK_MS = 500L

    /** A seek to [ms] reaches the end of a song the player knows to last [knownMs] (null: not known). */
    fun seekEnds(ms: Long, knownMs: Long?): Boolean = knownMs != null && knownMs > 0 && ms >= knownMs - SEEK_END_SLACK_MS

    /**
     * Where a seek to [ms] goes: within the length the player knows ([knownMs], null: not known),
     * never past it (a length told by Spotify or an extension is not the audio's).
     */
    fun seekTarget(ms: Long, knownMs: Long?): Long =
        if (knownMs != null && knownMs > 0) ms.coerceIn(0L, knownMs - 1) else ms.coerceAtLeast(0L)

    /**
     * Repeat once on a Spotify song: Spotify's repeat-track loops for good, so the page seen
     * starting the same song again ([sameSong]) near 0 ([nowMs]) when its clock had it at the end
     * ([beforeMs] of [lengthMs]) is the one replay: repeat goes back to off, the replay then ends
     * into the next song.
     */
    fun pageRestarted(sameSong: Boolean, beforeMs: Long, lengthMs: Long, nowMs: Long): Boolean =
        sameSong && lengthMs > 0 && beforeMs >= lengthMs - PAGE_END_WINDOW_MS && nowMs <= PAGE_RESTART_MS && nowMs < beforeMs

    /** The page's clock this close to the end: the song was ending (the page reports every few seconds). */
    const val PAGE_END_WINDOW_MS = 5_000L
    /** A song the page shows this close to 0:00 started again. */
    const val PAGE_RESTART_MS = 10_000L
}
