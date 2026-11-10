package com.music.spotui.di

import kotlin.math.abs

/**
 * The seek guard's arithmetic, without Android (see `SongPlayer.seekTo`, `applyState`).
 *
 * Two things were reported on a TV, both of them the bar and the song's place disagreeing:
 *
 * - Pressing "previous" moved the bar to 0:00 and then to about a minute. The player shows the
 *   new song at 0 at once, and the page then hands over a state of the song it left — a snapshot
 *   taken before the move — whose position (a minute in) was taken for the new song's. A state
 *   of an older position, arriving after a seek, is the old place, not the song moving back.
 * - A drag or a D-pad scrub jumped back to where the song had been and climbed forward again:
 *   the same stale state, arriving after the seek on the very spot the seek was sent from.
 *
 * The rule here is the one the player runs: for a while after a seek, a reported position on the
 * place the seek was sent from is the old position (refused), a reported position at the seek's
 * target ends the guard (the page showed the seek), and anything else is the page's word.
 */
object SeekRules {
    /** Something a seek left behind, to be compared with what the page reports next. */
    data class Sent(val uri: String, val from: Long, val target: Long)

    /**
     * Whether [reported] (the page's position for [uri], now) is a position from before a seek
     * sent to [target] from [from], handed over late — and so must not move the clock.
     *
     * [held]: the seek is still waiting to be shown (its hold has not run out); [guardUntil]:
     * while a *landed* seek's old place is still refused; [now]: `elapsedRealtime()`; [near]:
     * [SEEK_NEAR_MS].
     *
     * The two not-held cases are the ones that broke: a state read after the seek's hold ended,
     * and a state read by a reconcile, were both taken over the seek.
     */
    fun stale(sent: Sent, uri: String, reported: Long, held: Boolean, guardUntil: Long, now: Long, near: Long = NEAR_MS): Boolean {
        if (uri != sent.uri || uri.isBlank()) return false
        // The page has shown the seek: nothing of it is left to refuse.
        if (abs(reported - sent.target) <= near) return false
        val onOldSpot = abs(reported - sent.from) <= near
        if (!onOldSpot) return false
        // The seek is in flight (its hold covers it), or it landed a moment ago and this state is
        // older than it: both are the old position.
        return held || now <= guardUntil
    }

    /**
     * Where on the bar the song is, from a state whose position was read at its own timestamp:
     * extrapolated by what has been played since, but never past what the seek itself allowed.
     * A snapshot taken seconds ago on a busy page is moved forward by its own age, not refused
     * for it (the seek that "never showed" was that refusal).
     */
    fun placeAt(statePosition: Long, ageMs: Long, speed: Double, playedSinceSeekMs: Long): Double =
        statePosition + ageMs.coerceIn(0L, playedSinceSeekMs.coerceAtLeast(0L)) * speed

    /**
     * Whether a state of [uri] is the song that was left by a skip, not the song played: a skip
     * (with nothing held how long a drag holds) shows the next song at 0:00, and the page then
     * hands over a state of the song it is leaving — a snapshot a minute in, taken before the
     * move. Taken for the new song, it is the bar going to 0 and then to about a minute.
     *
     * [movedTo] is the song the skip asked for (the page's neighbour), [movedAt] when it was
     * asked; states newer than [freshMs] into the move belong to the new song.
     */
    fun leftBySkip(uri: String, movedFrom: String, movedTo: String?, movedAt: Long, now: Long, freshMs: Long = MOVE_FRESH_MS): Boolean =
        movedTo != null && uri == movedFrom && movedFrom != movedTo && movedFrom.isNotBlank() && now - movedAt in 0..freshMs

    /** How long after a skip a state of the song that was left is still the page catching up. */
    const val MOVE_FRESH_MS = 3_000L

    /** The biggest gap the code allows between a seek's target and where the page shows it. */
    const val NEAR_MS = 2_000L
}
