package app.myspoti.api.sync

import kotlin.math.abs

/**
 * The listen sync's decisions (see [ListenSync]), without Android and without a provider: what the
 * provider's unheard page and the local player each do when the other one moves. The page is the
 * phone's device in the account's remote control (what other devices see and command); the local
 * player is what is heard. Songs are opaque keys (a provider's track uri): only their equality matters.
 */
object ListenSyncRules {
    /** Out of step by more than this: put back in step (less is not heard, and every seek costs). */
    const val DRIFT_MS = 1_500L
    /** This close to the end of the local song, the page moving on is the song ending, not a remote skip. */
    const val END_WINDOW_MS = 5_000L

    /**
     * Whether the song is synced: one an extension streams live ([live]) with a provider id
     * ([trackId], checked by the provider for its own form), the setting [on] and the provider able
     * to stream ([dataSaver] off). A download or a file of the phone never is: it plays alone.
     */
    fun eligible(live: Boolean, trackId: String?, on: Boolean, dataSaver: Boolean): Boolean =
        live && on && !dataSaver && !trackId.isNullOrBlank()

    /** What the page was last asked to be on (by the sync, or a remote command it followed). */
    data class Want(val uri: String, val paused: Boolean)

    /** The page's state as it reports it, its position read for now. */
    data class Remote(val uri: String, val paused: Boolean, val positionMs: Long)

    /** The local player now: its song, whether it is to play, where, and its neighbours in the queue. */
    data class Local(
        val uri: String, val playing: Boolean, val positionMs: Long, val durationMs: Long,
        val nextUri: String? = null, val previousUri: String? = null,
    ) {
        val nearEnd: Boolean get() = durationMs > 0 && durationMs - positionMs < END_WINDOW_MS
    }

    /** What the local player does about a page state. */
    sealed interface Follow {
        data object Nothing : Follow
        /** The sync's play never reached the page: sent again, or given up. */
        data object Retry : Follow
        /** The page answered an earlier play of the sync's (fast skips): the asked song is sent again. */
        data object Resend : Follow
        data object Pause : Follow
        data object Resume : Follow
        data class Seek(val ms: Long) : Follow
        /** Skipped to the local queue's next / previous song. */
        data object Next : Follow
        data object Previous : Follow
        /** Another song, picked on another device: routed as a pick on the phone is. */
        data class Play(val uri: String, val positionMs: Long) : Follow
        /** Only drift: the unheard page is put on the local position (no jump is heard). */
        data class Align(val ms: Long) : Follow
        /**
         * The page went on by itself (its song ended, or its own repeat moved it) while the local
         * song is to play again: put back on the local song, the local player not moved.
         */
        data object Back : Follow
    }

    /** Whether [f] takes the local player to another song (a pick, a next or a previous seen on the page). */
    fun movesSong(f: Follow): Boolean = f is Follow.Play || f == Follow.Next || f == Follow.Previous

    /**
     * A page state came. [quiet]: the sync's own last command may still be answering (its echo,
     * never a remote one). [landed]: the page was seen playing [want]'s song since it was asked.
     * [predictedMs]: where its previous state of the same song puts it now (null: none).
     * [previousUri]: the song it was asked to be on before [want]'s (what a stale state still shows).
     * [gapFromMs]: where it stood when a buffering gap began, just before this state (null: no gap):
     * a page that stalled in the gap is anywhere between that and [predictedMs], a seek is not.
     * [wantAtMs], [wantLengthMs]: where the page is in [want]'s song and that song's length as the
     * provider has it (a stream of the extension can be longer than the provider's track).
     * [recentUris]: the songs the sync itself asked the page to play lately: a state on one of them is
     * a late answer. [repeats]: the local song plays again at its end (repeat once still to replay
     * it, or a queue of one song repeated): the page going on at the end is never followed, it is put back.
     */
    fun onRemote(
        remote: Remote, want: Want, local: Local, quiet: Boolean, landed: Boolean, predictedMs: Long?,
        previousUri: String? = null, gapFromMs: Long? = null, wantAtMs: Long? = null, wantLengthMs: Long = 0,
        recentUris: Collection<String> = emptyList(), repeats: Boolean = false,
    ): Follow {
        val late = remote.uri != want.uri && remote.uri in recentUris
        // During an echo, only a third song once it landed is remote (a double skip on another
        // device): the old song, the asked one and the others just asked are our own commands' answers.
        if (quiet && !(landed && remote.uri != want.uri && remote.uri != previousUri && !late)) return Follow.Nothing
        // Fast skips: plays sent within a second can land out of order, the page staying on an
        // earlier one. Never a remote command; the song the local player is on is sent again.
        if (late) return Follow.Resend
        // Not seen playing yet: sent again, unless the local player was paused meanwhile (its resume plays it).
        if (!landed) return if (want.paused || remote.uri == want.uri && !remote.paused) Follow.Nothing else Follow.Retry
        // The local player went on by itself, or the song is ending (on either side): the page's
        // move is that end, and the local side sends what comes next.
        val pageEnding = wantLengthMs > 0 && wantAtMs != null && wantLengthMs - wantAtMs < END_WINDOW_MS
        // The local song plays again: the page ending it and going on is its own move, undone.
        if (repeats && local.uri == want.uri && remote.uri != want.uri && (local.nearEnd || pageEnding)) return Follow.Back
        if (local.uri != want.uri || local.nearEnd || pageEnding) return Follow.Nothing
        if (remote.uri != want.uri) return when (remote.uri) {
            local.nextUri -> Follow.Next
            local.previousUri -> Follow.Previous
            else -> Follow.Play(remote.uri, remote.positionMs)
        }
        if (remote.paused != want.paused) return if (remote.paused) Follow.Pause else Follow.Resume
        val jumped = predictedMs != null &&
            (remote.positionMs > predictedMs + DRIFT_MS || remote.positionMs < (gapFromMs ?: predictedMs) - DRIFT_MS)
        if (jumped) return Follow.Seek(remote.positionMs)
        if (!remote.paused && local.playing && abs(remote.positionMs - local.positionMs) > DRIFT_MS) return Follow.Align(local.positionMs)
        return Follow.Nothing
    }

    /** What the page is asked about a local player state. */
    sealed interface Lead {
        data object Nothing : Lead
        data class Start(val uri: String, val positionMs: Long) : Lead
        data object Pause : Lead
        /** [alignMs]: where it resumes when it stood elsewhere; null: where it is. */
        data class Resume(val alignMs: Long?) : Lead
        data class Align(val ms: Long) : Lead
    }

    /**
     * The local player moved. [want]: what the page was asked last (null: nothing yet).
     * [stalled]: the local player has been buffering a while (the page waits for it).
     * [remoteAtMs]: where the page should be now, null when unknown. [previousUri]: the song it
     * was asked to be on before [want]'s.
     */
    fun onLocal(local: Local, want: Want?, stalled: Boolean, quiet: Boolean, remoteAtMs: Long?, previousUri: String? = null): Lead {
        val paused = !local.playing || stalled
        // A remote skip just moved the page on and the local player still shows the song it
        // left: not a new song to send back there.
        if (quiet && want != null && local.uri != want.uri && local.uri == previousUri) return Lead.Nothing
        // A new song starts on the page once the local one plays; until then the old one is
        // paused there (not counted for a song no longer heard).
        if (want == null || local.uri != want.uri) return when {
            !paused -> Lead.Start(local.uri, local.positionMs)
            want != null && !want.paused -> Lead.Pause
            else -> Lead.Nothing
        }
        val off = remoteAtMs != null && abs(remoteAtMs - local.positionMs) > DRIFT_MS
        if (paused != want.paused) return if (paused) Lead.Pause else Lead.Resume(local.positionMs.takeIf { off })
        if (!quiet && !paused && off) return Lead.Align(local.positionMs)
        return Lead.Nothing
    }

    private val BACKOFF_MS = longArrayOf(5_000, 15_000, 30_000)

    /**
     * How long after its [step]-th failed try (from 0) a song the page would not play is played there
     * again: 5 s, 15 s, then every 30 s. The sync never gives a song up for good.
     */
    fun backoffMs(step: Int): Long = BACKOFF_MS[step.coerceIn(0, BACKOFF_MS.lastIndex)]

    /**
     * The local player was moved (played after a pause, or put elsewhere than its clock says):
     * [expectedMs] is where its previous event puts it now (null: unknown).
     */
    fun localMoved(wasPlaying: Boolean, playing: Boolean, expectedMs: Long?, positionMs: Long): Boolean =
        (playing && !wasPlaying) || (expectedMs != null && abs(positionMs - expectedMs) > DRIFT_MS)

    /**
     * Whether a song the provider's page plays heard on this phone goes back to the extensions (and
     * so to the sync): playback came back here after the sync had handed it away ([handedAway]), or
     * another device moved the page here (a resume, a skip, a pick: [moved]) without the app having
     * asked for it ([intended]). Only while it plays here ([playingHere]) with the setting [on], and
     * never while the device list says another device is the active one ([activeElsewhere]): the
     * page then shows that device's song as playing (another install of the app that resumed it),
     * and taking it back would start a fight between the two.
     */
    fun backHere(playingHere: Boolean, handedAway: Boolean, moved: Boolean, intended: Boolean, on: Boolean, activeElsewhere: Boolean = false): Boolean =
        on && playingHere && !intended && (handedAway || moved) && !activeElsewhere

    /** A remote move of the page (next, previous, a pick) is followed only while no other device is the active one. */
    fun followsRemote(activeElsewhere: Boolean): Boolean = !activeElsewhere

    /** How long a page ready again is given for its device to join the remote control before it is commanded anyway. */
    const val JOIN_WAIT_MS = 8_000L

    /**
     * Whether the page takes the sync's commands: loaded ([ready]), no [ad] on, and its device
     * [joined] to the account's remote control (a play sent between "ready" and the join is refused).
     * The join is only waited for when the host reports joins ([joinReported]), and no longer than
     * [JOIN_WAIT_MS] ([waitedOut]).
     */
    fun pageUsable(ready: Boolean, ad: Boolean, joined: Boolean, joinReported: Boolean, waitedOut: Boolean): Boolean =
        ready && !ad && (joined || !joinReported || waitedOut)

    /**
     * The loop guard: a command the sync sends is answered by page states that must not be taken
     * for a remote device's commands. Until [sent]'s window is over, its states are its echo.
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
