package com.music.spotui.di

/**
 * How [LocalPlayer] repeats, kept pure so it is tested off-device. [mode] is SongPlayer.repeatMode:
 * 0 off, 1 the queue, 2 the song for good, 3 the song once.
 *
 * A song that follows itself (repeat one, or a queue of one song repeated) is not left to
 * ExoPlayer's own repeat: that plays the song again as a new period of the same item, and with an
 * extension's stream it was seen starting again near the last seek instead of at 0:00 (and earlier
 * on each loop). The player pauses at the song's end instead and Myspoti puts it back at 0:00.
 *
 * Repeat one loops the song for good, as Spotify's repeat-track does. Repeat once plays the song
 * again one time, then the queue goes on and the mode goes back to off ([atEnd]). A queue of one
 * song repeated (repeat all) loops it for good.
 */
object LocalRepeat {
    const val OFF = 0
    const val ALL = 1
    const val ONE = 2
    const val ONCE = 3

    /**
     * The song pauses at its end for Myspoti to play it again from 0:00 (see the object's note), or,
     * after repeat once's replay, to go on to the next song ([atEnd]).
     */
    fun loopsSong(mode: Int, items: Int): Boolean = mode == ONE || mode == ONCE || mode == ALL && items == 1

    /**
     * The repeat the Web Player is given for [mode]: its repeat-track for both ways of repeating the
     * song (0 off, 1 the context, 2 the track, as Spotify's Repeat), repeat once being Myspoti's own.
     */
    fun pageMode(mode: Int): Int = if (mode >= ONCE) ONE else mode.coerceIn(OFF, ONE)

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
        mode == ONCE -> if (replayed) End.ONCE_DONE else End.REPLAY
        mode == ONE || mode == ALL && items == 1 -> End.REPLAY
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

    /** The page's clock is within [PAGE_END_WINDOW_MS] of the song's end ([lengthMs] > 0). */
    fun atSongEnd(positionMs: Long, lengthMs: Long): Boolean =
        lengthMs > 0 && positionMs >= lengthMs - PAGE_END_WINDOW_MS

    /**
     * The page played the song at its end again, without Myspoti asking: its clock was at the end
     * ([beforeMs] of [lengthMs]) and is near 0:00 ([nowMs]). A manual skip near the end looks the
     * same; the caller only keeps a repeat the user chose on it.
     */
    fun pagePlayedAgain(beforeMs: Long, lengthMs: Long, nowMs: Long): Boolean =
        atSongEnd(beforeMs, lengthMs) && nowMs <= PAGE_RESTART_MS && nowMs < beforeMs

    /**
     * Repeat once on a Spotify song: Spotify's repeat-track loops for good, so the page seen
     * starting the same song again ([sameSong]) near 0 ([nowMs]) when its clock had it at the end
     * ([beforeMs] of [lengthMs]) is the one replay: repeat goes back to off, the replay then ends
     * into the next song.
     */
    fun pageRestarted(sameSong: Boolean, beforeMs: Long, lengthMs: Long, nowMs: Long): Boolean =
        sameSong && pagePlayedAgain(beforeMs, lengthMs, nowMs)

    /**
     * The page reports less repeat ([reported]) than the repeat one or all the user chose ([mode])
     * as the song or album comes round again ([cameRound]): the choice still stands, not what the
     * page says there. It drops repeat one to the queue's repeat at the song's end (seen on the
     * phone), and repeat off just as well. A weaker repeat away from the loop is a change from
     * another device: theirs.
     */
    fun pageForgotRepeat(mode: Int, reported: Int, cameRound: Boolean): Boolean =
        cameRound && (mode == ONE || mode == ALL) && reported < mode

    /** The page's clock this close to the end: the song was ending (the page reports every few seconds). */
    const val PAGE_END_WINDOW_MS = 5_000L
    /** A song the page shows this close to 0:00 started again. */
    const val PAGE_RESTART_MS = 10_000L
}
