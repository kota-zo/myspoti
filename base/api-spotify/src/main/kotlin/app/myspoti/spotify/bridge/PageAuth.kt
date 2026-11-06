package app.myspoti.spotify.bridge

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * Spotify answers 401 to the page: its token went stale and the page does not get a new one by
 * itself. Reported (2.3.0, log seen, free account behind a VPN): after a network change every
 * request of the page answered 401 — its relayed play reports, registrations and commands, and
 * its own requests (social-connect, license_url) — for an hour; the relay carries the page's own
 * headers and answers, and the commands then went out without an Authorization header at all.
 * The next song's licence was answered 401 three times and it never played. A new page gets a
 * new token: the host reports each 401 here, and the player reloads the page ([stale]).
 */
object PageAuth {
    private val loss = AuthLoss()
    private val _stale = MutableSharedFlow<String>(extraBufferCapacity = 1)
    /** The page's token is stale (what showed it): reload the page. */
    val stale: SharedFlow<String> = _stale

    /** When a licence (or its address) was last answered 401 (0: never). */
    @Volatile var licenceUnauthorizedAt = 0L
        private set

    /** Spotify answered 401 to one of the page's requests ([what], for the log); [licence]: an audio licence or its address. */
    fun unauthorized(what: String, licence: Boolean = false, now: Long = System.currentTimeMillis()) {
        if (licence) licenceUnauthorizedAt = now
        synchronized(loss) { loss.record(now, licence) }?.let { _stale.tryEmit("$it, last: $what") }
    }

    /** A new page: its token is new. */
    fun pageGone() = synchronized(loss) { loss.clear() }
}

/**
 * When 401s mean the page's token is stale. A licence answered 401 is at once (the song cannot
 * play without one); otherwise [RUN] of them within [WINDOW_MS] (one alone can be a token the
 * page refreshes right after). Said at most once per [QUIET_MS].
 */
internal class AuthLoss {
    private val times = ArrayDeque<Long>()
    private var saidAt = Long.MIN_VALUE / 2

    /** A 401 at [now]: the reason when the token is now taken as stale, else null. */
    fun record(now: Long, licence: Boolean): String? {
        times.addLast(now)
        while (times.isNotEmpty() && now - times.first() > WINDOW_MS) times.removeFirst()
        val why = when {
            licence -> "a licence answered 401"
            times.size >= RUN -> "${times.size} answers 401 in ${(now - times.first()) / 1000} s"
            else -> return null
        }
        if (now - saidAt < QUIET_MS) return null
        saidAt = now
        times.clear()
        return why
    }

    fun clear() = times.clear()

    companion object {
        const val RUN = 3
        const val WINDOW_MS = 30_000L
        const val QUIET_MS = 60_000L
    }
}
