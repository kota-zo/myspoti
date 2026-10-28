package app.myspoti.spotify

/**
 * How [SpotifyPlayer] gets past an ad and what plays on a new page, without the page.
 *
 * While the page only mirrors a song the app plays itself ([SpotifyPlayer.setMirroring]: the
 * local player is heard, the page plays the same song unheard so Spotify counts it), the app's
 * mirror alone says what the page plays. Reported (2.1.0, log seen): an ad on the mirror, the
 * user's next, a reload; then the song the mirror had sent before the ad ("picked before the
 * ad") and an even older one ("page back after a reload: … goes on") were both played on the new
 * page, next to the mirror's own song, and the ad's unmute made the page heard over the local player.
 */
object AdRecoveryRules {
    /** What is done about an ad stuck on the page. */
    enum class Stuck {
        /** Play the song after it (the page's own queue). */
        NEXT,
        /** Reload the page, then play on from the song before the ad. */
        RELOAD,
        /**
         * Reload the page and play nothing there: the mirror plays its song again once the new
         * page's device has joined. Waiting is no option: a refused ad stays current for good
         * (20 min measured) and refuses every play, so the mirror's song would never be counted,
         * and Spotify would start from a page still stuck when the local player hands over.
         * Playing on is none either: it plays the page's own queue, never the mirror's song.
         */
        RELOAD_ONLY,
    }

    /** The [attempt]-th try on a stuck ad; [playing]: a muted ad that plays (not refused). */
    fun stuck(attempt: Int, playing: Boolean, mirrored: Boolean): Stuck = when {
        mirrored -> Stuck.RELOAD_ONLY
        SpotifyPlayer.adRecovery(attempt, playing) == "next" -> Stuck.NEXT
        else -> Stuck.RELOAD
    }

    /**
     * Whether a song played just before an ad (the ad came in its place) is kept to be played
     * again after the break. Not the mirror's: it sends the local player's song itself, which may
     * be another one by then.
     */
    fun keepsPickBeforeAd(mirrored: Boolean): Boolean = !mirrored

    /** What a page that is ready again plays. */
    enum class PageBack {
        /** Nothing (our own reload says itself what plays, or nothing played). */
        NOTHING,
        /** The song picked before an ad, held over the reload. */
        PICK,
        /** What played when the page went away, where it was. */
        RESUME,
        /** The song the user asked for during the reload. */
        INTENT,
        /** Nothing from here: the mirror plays the local player's song again. */
        MIRROR,
    }

    /**
     * [ownReload]: the page came back from a reload of ours, read when "ready" came (not after a
     * call that waits for the page, by which time our reload may be over). [pickHeld]: a song
     * picked before an ad waits for the new page; [lost]: something played when the page went;
     * [intent]: the user asked for a song meanwhile.
     */
    fun pageBack(ownReload: Boolean, mirrored: Boolean, pickHeld: Boolean, lost: Boolean, intent: Boolean): PageBack = when {
        mirrored -> PageBack.MIRROR
        ownReload -> PageBack.NOTHING
        pickHeld -> PageBack.PICK
        lost -> PageBack.RESUME
        intent -> PageBack.INTENT
        else -> PageBack.NOTHING
    }

    /** After our own reload: whether the player plays a song there itself (else the mirror does). */
    fun playsAfterOwnReload(mirrored: Boolean): Boolean = !mirrored
}
