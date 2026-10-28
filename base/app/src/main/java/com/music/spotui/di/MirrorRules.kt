package com.music.spotui.di

import kotlin.math.abs

/**
 * The Connect mirror's decisions (see [ConnectMirror]), without Android: what the unheard Web
 * Player and the local player each do when the other one moves. The Web Player is the phone's
 * Spotify Connect device (what other devices see and command); the local player is what is heard.
 */
object MirrorRules {
    /** Out of step by more than this: put back in step (less is not heard, and every seek costs). */
    const val DRIFT_MS = 1_500L
    /** This close to the end of the local song, the Web Player moving on is the song ending, not a remote skip. */
    const val END_WINDOW_MS = 5_000L

    /** Where the local player's song comes from, as far as the mirror cares. */
    enum class Origin { AUDIO_SOURCE, DOWNLOAD, LOCAL_FILE }

    private val SPOTIFY_ID = Regex("[A-Za-z0-9]{22}")

    /**
     * Whether the song is mirrored: one an extension streams ([Origin.AUDIO_SOURCE]) with a Spotify
     * id, the setting [on], and Spotify able to stream ([dataSaver] off). Downloads and files of the
     * device never are: nothing of Spotify plays them.
     */
    fun eligible(origin: Origin, spotifyId: String, on: Boolean, dataSaver: Boolean): Boolean =
        on && !dataSaver && origin == Origin.AUDIO_SOURCE && SPOTIFY_ID.matches(spotifyId)

    /** What the Web Player was last asked to be on (by the mirror, or a remote command it followed). */
    data class Want(val uri: String, val paused: Boolean)

    /** The Web Player's state as it reports it, its position read for now. */
    data class Web(val uri: String, val paused: Boolean, val positionMs: Long)

    /** The local player now: its song, whether it is to play, where, and its neighbours in the queue. */
    data class Local(
        val uri: String, val playing: Boolean, val positionMs: Long, val durationMs: Long,
        val nextUri: String? = null, val previousUri: String? = null,
    ) {
        val nearEnd: Boolean get() = durationMs > 0 && durationMs - positionMs < END_WINDOW_MS
    }

    /** What the local player does about a Web Player state. */
    sealed interface Follow {
        data object Nothing : Follow
        /** The mirror's play never reached the Web Player: sent again, or given up. */
        data object Retry : Follow
        /** The Web Player answered an earlier play of the mirror's (fast skips): the asked song is sent again. */
        data object Resend : Follow
        data object Pause : Follow
        data object Resume : Follow
        data class Seek(val ms: Long) : Follow
        /** Skipped to the local queue's next / previous song. */
        data object Next : Follow
        data object Previous : Follow
        /** Another song, picked on another device: routed as a pick on the phone is. */
        data class Play(val uri: String, val positionMs: Long) : Follow
        /** Only drift: the unheard Web Player is put on the local position (no jump is heard). */
        data class Align(val ms: Long) : Follow
        /**
         * The Web Player went on by itself (its song ended, or its own repeat moved it) while the
         * local song is to play again: put back on the local song, the local player not moved.
         */
        data object Back : Follow
    }

    /**
     * A Web Player state came. [quiet]: the mirror's own last command may still be answering (its
     * echo, never a remote one). [landed]: the Web Player was seen playing [want]'s song since it was
     * asked. [predictedMs]: where its previous state of the same song puts it now (null: none).
     * [previousUri]: the song it was asked to be on before [want]'s (what a stale state still shows).
     * [gapFromMs]: where it stood when a buffering gap began, just before this state (null: no gap):
     * a page that stalled in the gap is anywhere between that and [predictedMs], a seek is not.
     * [wantAtMs], [wantLengthMs]: where the Web Player is in [want]'s song and that song's length as it
     * has it (a stream of the extension can be longer than Spotify's track). [recentUris]: the songs
     * the mirror itself asked the Web Player to play lately: a state on one of them is a late answer.
     * [repeats]: the local song plays again at its end (repeat once still to replay it, or a queue
     * of one song repeated): the Web Player going on at the end is never followed, it is put back.
     */
    fun onWeb(
        web: Web, want: Want, local: Local, quiet: Boolean, landed: Boolean, predictedMs: Long?,
        previousUri: String? = null, gapFromMs: Long? = null, wantAtMs: Long? = null, wantLengthMs: Long = 0,
        recentUris: Collection<String> = emptyList(), repeats: Boolean = false,
    ): Follow {
        val late = web.uri != want.uri && web.uri in recentUris
        // During an echo, only a third song once it landed is remote (a double skip on another
        // device): the old song, the asked one and the others just asked are our own commands' answers.
        if (quiet && !(landed && web.uri != want.uri && web.uri != previousUri && !late)) return Follow.Nothing
        // Fast skips: plays sent within a second can land out of order, the Web Player staying on an
        // earlier one. Never a remote command; the song the local player is on is sent again.
        if (late) return Follow.Resend
        // Not seen playing yet: sent again, unless the local player was paused meanwhile (its resume plays it).
        if (!landed) return if (want.paused || web.uri == want.uri && !web.paused) Follow.Nothing else Follow.Retry
        // The local player went on by itself, or the song is ending (on either side): the Web
        // Player's move is that end, and the local side sends what comes next.
        val pageEnding = wantLengthMs > 0 && wantAtMs != null && wantLengthMs - wantAtMs < END_WINDOW_MS
        // The local song plays again: the Web Player ending it and going on is its own move, undone.
        if (repeats && local.uri == want.uri && web.uri != want.uri && (local.nearEnd || pageEnding)) return Follow.Back
        if (local.uri != want.uri || local.nearEnd || pageEnding) return Follow.Nothing
        if (web.uri != want.uri) return when (web.uri) {
            local.nextUri -> Follow.Next
            local.previousUri -> Follow.Previous
            else -> Follow.Play(web.uri, web.positionMs)
        }
        if (web.paused != want.paused) return if (web.paused) Follow.Pause else Follow.Resume
        val jumped = predictedMs != null &&
            (web.positionMs > predictedMs + DRIFT_MS || web.positionMs < (gapFromMs ?: predictedMs) - DRIFT_MS)
        if (jumped) return Follow.Seek(web.positionMs)
        if (!web.paused && local.playing && abs(web.positionMs - local.positionMs) > DRIFT_MS) return Follow.Align(local.positionMs)
        return Follow.Nothing
    }

    /** What the Web Player is asked about a local player state. */
    sealed interface Lead {
        data object Nothing : Lead
        data class Start(val uri: String, val positionMs: Long) : Lead
        data object Pause : Lead
        /** [alignMs]: where it resumes when it stood elsewhere; null: where it is. */
        data class Resume(val alignMs: Long?) : Lead
        data class Align(val ms: Long) : Lead
    }

    /**
     * The local player moved. [want]: what the Web Player was asked last (null: nothing yet).
     * [stalled]: the local player has been buffering a while (the Web Player waits for it).
     * [webAtMs]: where the Web Player should be now, null when unknown. [previousUri]: the song it
     * was asked to be on before [want]'s.
     */
    fun onLocal(local: Local, want: Want?, stalled: Boolean, quiet: Boolean, webAtMs: Long?, previousUri: String? = null): Lead {
        val paused = !local.playing || stalled
        // A remote skip just moved the Web Player on and the local player still shows the song it
        // left: not a new song to send back there.
        if (quiet && want != null && local.uri != want.uri && local.uri == previousUri) return Lead.Nothing
        // A new song starts on the Web Player once the local one plays; until then the old one is
        // paused there (not counted for a song no longer heard).
        if (want == null || local.uri != want.uri) return when {
            !paused -> Lead.Start(local.uri, local.positionMs)
            want != null && !want.paused -> Lead.Pause
            else -> Lead.Nothing
        }
        val off = webAtMs != null && abs(webAtMs - local.positionMs) > DRIFT_MS
        if (paused != want.paused) return if (paused) Lead.Pause else Lead.Resume(local.positionMs.takeIf { off })
        if (!quiet && !paused && off) return Lead.Align(local.positionMs)
        return Lead.Nothing
    }

    private val BACKOFF_MS = longArrayOf(5_000, 15_000, 30_000)

    /**
     * How long after its [step]-th failed try (from 0) a song the page would not play is played there
     * again: 5 s, 15 s, then every 30 s. The mirror never gives a song up for good.
     */
    fun backoffMs(step: Int): Long = BACKOFF_MS[step.coerceIn(0, BACKOFF_MS.lastIndex)]

    /**
     * The list the Web Player plays [trackUri] in: the local queue's Spotify list ([contextUri]), so
     * other devices see that queue after it, else the track alone. Never an artist: its context is
     * only the artist's top songs, a song of a list taken from an artist page (a mix, the radio,
     * other artists) is often not in it, and the page then plays the artist's first song instead.
     * Nor a list that already put the page on another song ([missed]).
     */
    fun playContext(contextUri: String, trackUri: String, missed: Set<String>): String =
        contextUri.takeIf { it.startsWith("spotify:") && !it.startsWith("spotify:artist:") && it !in missed } ?: trackUri

    /**
     * The local player was moved (played after a pause, or put elsewhere than its clock says):
     * [expectedMs] is where its previous event puts it now (null: unknown).
     */
    fun localMoved(wasPlaying: Boolean, playing: Boolean, expectedMs: Long?, positionMs: Long): Boolean =
        (playing && !wasPlaying) || (expectedMs != null && abs(positionMs - expectedMs) > DRIFT_MS)

    /**
     * Whether a song the engine plays heard on this phone goes back to the extensions (and so to the
     * mirror): Connect playback came back here after the mirror had handed it away ([handedAway]), or
     * another device moved the engine here (a resume, a skip, a pick: [moved]) without Myspoti having
     * asked for it ([intended]). Only while it plays here ([playingHere]) with the setting [on], and
     * never while the device list says another device is the active one ([activeElsewhere]): the
     * page then shows that device's song as playing (another install of the app that resumed it),
     * and taking it back would start a fight between the two.
     */
    fun backHere(playingHere: Boolean, handedAway: Boolean, moved: Boolean, intended: Boolean, on: Boolean, activeElsewhere: Boolean = false): Boolean =
        on && playingHere && !intended && (handedAway || moved) && !activeElsewhere

    /** A remote move of the page (next, previous, a pick) is followed only while no other device is the active one. */
    fun followsRemote(activeElsewhere: Boolean): Boolean = !activeElsewhere

    /** How long a page ready again is given for its device to join Spotify Connect before it is commanded anyway. */
    const val JOIN_WAIT_MS = 8_000L

    /**
     * Whether the page takes the mirror's commands: loaded ([ready]), no [ad] on, and its device
     * [joined] to Spotify Connect (a play sent between "ready" and the join was refused:
     * DEVICE_NOT_FOUND). The join is only waited for when the host reports joins ([joinReported]),
     * and no longer than [JOIN_WAIT_MS] ([waitedOut]).
     */
    fun pageUsable(ready: Boolean, ad: Boolean, joined: Boolean, joinReported: Boolean, waitedOut: Boolean): Boolean =
        ready && !ad && (joined || !joinReported || waitedOut)

    /**
     * The loop guard: a command the mirror sends is answered by Web Player states that must not be
     * taken for a remote device's commands. Until [sent]'s window is over, its states are its echo.
     */
    class Echo(private val clock: () -> Long) {
        private var until = 0L
        fun sent(windowMs: Long) { until = maxOf(until, clock() + windowMs) }
        fun quiet(): Boolean = clock() < until
        /** How long the window still lasts (0 when over). */
        fun remainingMs(): Long = (until - clock()).coerceAtLeast(0)
        fun clear() { until = 0L }
    }
}
