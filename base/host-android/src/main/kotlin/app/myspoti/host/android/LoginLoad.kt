package app.myspoti.host.android

/**
 * The sign-in page as the user meets it while it opens. The page used to be shown from the first
 * tap: white until Spotify's page painted, white for ever when it never did (no timeout watched a
 * login page, only the player's), and a page that failed to load had no way back but killing the
 * app. Now the app's own waiting screen covers the page until it has content ([LOADING]), and an
 * error with "Try again" replaces a page that did not come ([FAILED]). [WebPlayerHost] feeds this
 * what the WebView reports; the rules below are pure so they are tested.
 */
enum class LoginLoad {
    /** No sign-in page is being opened (or it is past this stage: signed in, or the player is back). */
    NONE,
    /** Asked for, no content yet: the waiting screen shows, the page is kept out of sight. */
    LOADING,
    /** Spotify's page has content: it is shown. */
    SHOWN,
    /** It did not load, or stayed blank: the error shows. */
    FAILED;

    /** What to do after a probe found the page still without content. */
    enum class Blank { WAIT, RELOAD, FAIL }

    companion object {
        /** From the request to a page with content; past it the load is given up. */
        const val TIMEOUT_MS = 25_000L

        /** The page is looked at this long after it finished loading, then every [PROBE_EVERY_MS]. */
        const val FIRST_PROBE_MS = 500L
        const val PROBE_EVERY_MS = 1_000L

        /** Probes of a page that finished but stays empty: loaded again once, then given up. */
        private const val RELOAD_AFTER = 6
        private const val FAIL_AFTER = 10

        /**
         * Read in the page: how much it shows (its text and its fields, buttons and links). Spotify's
         * login is a script-built page, so an empty body after "finished" is a page that did not run.
         */
        const val PAINT_JS = "(function(){var b=document.body;if(!b)return 0;" +
            "return (b.innerText||'').trim().length+b.querySelectorAll('input,button,a').length})()"

        /** [PAINT_JS]'s answer as evaluateJavascript hands it back ("42", "0", "null"). */
        fun painted(answer: String?): Boolean = (answer?.trim()?.trim('"')?.toDoubleOrNull() ?: 0.0) > 0.0

        /** [probes] empty looks so far at this page; [reloaded]: it was already loaded again once. */
        fun blank(probes: Int, reloaded: Boolean): Blank = when {
            probes < RELOAD_AFTER -> Blank.WAIT
            !reloaded -> Blank.RELOAD
            probes < FAIL_AFTER -> Blank.WAIT
            else -> Blank.FAIL
        }

        /** Spotify's own account pages: the one whose failure is the sign-in's failure. */
        fun isSpotifyAccounts(url: String?): Boolean = hostOf(url) == "accounts.spotify.com"

        /**
         * A page of the sign-in: Spotify's account and challenge pages, a `/login` path anywhere,
         * and the "Continue with Google / Facebook / Apple" pages. Judged on the host and the path,
         * not on the whole address: a player address that merely carries one of these names in its
         * query (a `continue=` back to the login) is not a sign-in page, and was taken for one.
         */
        fun isLoginUrl(url: String?): Boolean {
            val uri = runCatching { java.net.URI((url ?: return false).substringBefore('#').substringBefore('?')) }.getOrNull() ?: return false
            val host = uri.host?.lowercase() ?: return false
            if (host == "accounts.spotify.com" || host == "challenge.spotify.com") return true
            if (uri.path?.contains("/login") == true) return true
            return host == "accounts.google.com" || host == "facebook.com" || host.endsWith(".facebook.com") || host == "appleid.apple.com"
        }

        private fun hostOf(url: String?): String? =
            runCatching { java.net.URI((url ?: return null).substringBefore('#').substringBefore('?')).host?.lowercase() }.getOrNull()
    }
}

/**
 * How long the app waits for a session once the sign-in page has handed back to the player.
 * Two clocks, both real time: [waitedMs] runs only while the screen is in front and the player's
 * bridge is ready (a player that has not answered yet proves nothing), [elapsedMs] runs while the
 * screen is in front, ready or not, and is the hard limit. The wait used to add 500 for every turn
 * of its loop, but a turn that asked the page for its token could itself take 20 s (the bridge's
 * call timeout): the "60 s" limit was 120 turns, which is 40 minutes of spinner when the page did
 * not answer.
 */
class SessionWait(private val readyBudgetMs: Long, private val maxMs: Long) {
    var waitedMs = 0L
        private set
    var elapsedMs = 0L
        private set

    /**
     * Accounts for [dtMs] of real time that just passed. A turn counts for at most [MAX_TURN_MS]: the
     * slowest honest one is a token request given its 20 s, and a longer gap is the phone asleep (Doze
     * holds a coroutine's delay while the user reads the e-mail), which is not time spent waiting.
     */
    fun tick(dtMs: Long, inFront: Boolean, ready: Boolean) {
        if (dtMs <= 0 || !inFront) return
        val dt = minOf(dtMs, MAX_TURN_MS)
        elapsedMs += dt
        if (ready) waitedMs += dt
    }

    companion object { const val MAX_TURN_MS = 22_000L }

    val expired: Boolean get() = waitedMs >= readyBudgetMs || elapsedMs >= maxMs

    /** Expired without the player ever being ready for its budget: it did not start, which is not "signed out". */
    val playerNeverReady: Boolean get() = expired && waitedMs < readyBudgetMs
}
