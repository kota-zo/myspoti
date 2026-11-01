package app.myspoti.spotify

/**
 * Why the song the Web Player plays changed, for the Logbook (a skip report says nothing about
 * it otherwise), and the guard against a page that chains through songs it cannot play.
 */
internal object TrackChanges {
    /** A song this close to its end that changes has ended. */
    const val ENDED_TAIL_MS = 4_000L
    /** A command (ours or the user's) this recent explains the change. */
    const val COMMAND_MS = 5_000L

    enum class Cause(val text: String) {
        COMMAND("after a command"),
        AD("after an ad break"),
        ENDED("the song ended"),
        /** Nothing asked for it and the song had not ended: the page moved on by itself. */
        OWN("the page moved on by itself"),
    }

    /** [commandUsed]: the last command already explained an earlier change (see [Commands]). */
    fun cause(playedMs: Long, durationMs: Long, sinceCommandMs: Long, afterAd: Boolean, crossfading: Boolean, commandUsed: Boolean = false): Cause = when {
        !commandUsed && sinceCommandMs in 0..COMMAND_MS -> Cause.COMMAND
        afterAd -> Cause.AD
        crossfading || (durationMs > 0 && durationMs - playedMs <= ENDED_TAIL_MS) -> Cause.ENDED
        else -> Cause.OWN
    }

    /**
     * A command explains one change of song, not every change in the [COMMAND_MS] after it: the
     * first one, or the one onto the song it asked for. Reported (2.2.0, logs of 30/10): a page
     * refused every licence and moved on about every second after a play, four or five songs in a
     * row "after a command", and the [Chain] never counted one of them.
     */
    class Commands {
        private var usedAt = Long.MIN_VALUE

        /**
         * A change onto [to] after the command sent at [commandAt] ([target]: the song a play asked
         * for, null for a skip): true when that command already explained an earlier change.
         */
        fun used(commandAt: Long, to: String, target: String?): Boolean {
            val used = usedAt == commandAt && to != target
            usedAt = commandAt
            return used
        }
    }

    fun clock(ms: Long): String = "%d:%02d".format(ms / 60_000, ms / 1000 % 60)

    /**
     * Songs the page moved on from by itself after only a few seconds, in a row: it is skipping
     * through songs it cannot play (licences refused, a market, a stream that fails), each
     * reported as a skip by the users. [record] says when it is time to stop it.
     */
    class Chain(private val limit: Int = 3, private val windowMs: Long = 30_000, private val shortMs: Long = 10_000) {
        private val times = ArrayDeque<Long>()

        /** A change of [cause] after [playedMs] of the old song at [now]; true when the chain reached [limit]. */
        fun record(cause: Cause, playedMs: Long, now: Long): Boolean {
            // Anything but a short song left by the page itself breaks the chain.
            if (cause != Cause.OWN || playedMs > shortMs) { times.clear(); return false }
            times.addLast(now)
            while (times.isNotEmpty() && now - times.first() > windowMs) times.removeFirst()
            if (times.size < limit) return false
            times.clear()
            return true
        }

        fun reset() = times.clear()
    }
}
