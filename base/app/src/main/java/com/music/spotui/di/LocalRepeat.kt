package com.music.spotui.di

/**
 * How [LocalPlayer] repeats, kept pure so it is tested off-device. [mode] is SongPlayer.repeatMode:
 * 0 off, 1 the queue, 2 the song.
 *
 * A song that follows itself (repeat one, or a queue of one song repeated) is not left to
 * ExoPlayer's own repeat: that plays the song again as a new period of the same item, and with an
 * extension's stream it was seen starting again near the last seek instead of at 0:00 (and earlier
 * on each loop). The player pauses at the song's end instead and Myspoti puts it back at 0:00.
 */
object LocalRepeat {
    const val OFF = 0
    const val ALL = 1
    const val ONE = 2

    /** The song plays again at its end, put back at 0:00 by Myspoti (see the object's note). */
    fun loopsSong(mode: Int, items: Int): Boolean = mode == ONE || mode == ALL && items == 1

    /** The repeat ExoPlayer is given: its own only for a queue of several songs repeated. */
    fun playerMode(mode: Int, items: Int): Int =
        if (mode == ALL && !loopsSong(mode, items)) androidx.media3.common.Player.REPEAT_MODE_ALL
        else androidx.media3.common.Player.REPEAT_MODE_OFF

    /** How far into a song the player may report it at the start (a song starting plays from 0:00). */
    const val START_SLACK_MS = 1_500L

    /** A song the player moved to by itself (the next one, the queue again) that did not start at 0:00. */
    fun startedLate(positionMs: Long): Boolean = positionMs > START_SLACK_MS
}
