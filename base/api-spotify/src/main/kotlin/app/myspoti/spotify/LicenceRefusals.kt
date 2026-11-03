package app.myspoti.spotify

/**
 * Spotify refusing this account its audio licences (`403`). Reported (2.2.0, logs of 30/10, free
 * accounts signed in less than a day before): every song after the first of a page got a 403, and
 * the page moved on by itself to the next one a fraction of a second later, through the list.
 * One refusal is a song that cannot play here (a market); [limit] within [windowMs] is the account.
 * Only observed: the request and its answer are Spotify's, nothing is answered in their place.
 * In #134 and the four diagnostics of 26/10 to 30/10 not one song's licence was granted, the first
 * refused before any ad or reload of the session: what this app does to ads within the session is
 * not what starts it. What clears it is not known; ads queued meanwhile are let play, muted
 * ([app.myspoti.spotify.bridge.AdBlock.adsOwed]), and the first licence granted ends it all.
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

        /**
         * Whether an ad that plays is let play (muted) to its end instead of got past (a reload, a
         * play of the next song): only while ads are owed ([app.myspoti.spotify.bridge.AdBlock.adsOwed]),
         * not on a page that only mirrors an extension's song (it is reloaded as before), and not an
         * ad whose file did not load ([refused]: it never ends by itself).
         */
        fun letAdPlay(owed: Boolean, mirroring: Boolean, refused: Boolean): Boolean = owed && !mirroring && !refused
    }
}
