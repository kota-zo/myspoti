package app.myspoti.spotify

/**
 * Spotify refusing this account its audio licences (`403`). Reported (2.2.0, logs of 30/10, free
 * accounts signed in less than a day before): every song after the first of a page got a 403, and
 * the page moved on by itself to the next one a fraction of a second later, through the list.
 * One refusal is a song that cannot play here (a market); [limit] within [windowMs] is the account.
 * Only observed: the request and its answer are Spotify's, nothing is answered in their place.
 */
internal class LicenceRefusals(private val limit: Int = 3, private val windowMs: Long = 10_000) {
    private val times = ArrayDeque<Long>()

    /** A refusal at [now]; true when it makes [limit] within [windowMs] (counted again from zero then). */
    fun record(now: Long): Boolean {
        times.addLast(now)
        while (times.isNotEmpty() && now - times.first() > windowMs) times.removeFirst()
        if (times.size < limit) return false
        times.clear()
        return true
    }

    companion object {
        /** A song's licence, not the Widevine certificate (both are under `widevine-license`). */
        fun isAudio(url: String): Boolean = "widevine-license" in url && "/audio/license" in url
    }
}
