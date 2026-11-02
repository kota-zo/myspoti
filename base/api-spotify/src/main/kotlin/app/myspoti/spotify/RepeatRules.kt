package app.myspoti.spotify

import app.myspoti.api.ItemKind
import app.myspoti.api.NowPlaying
import app.myspoti.api.PlayerState
import app.myspoti.api.Repeat
import app.myspoti.api.Uri

/**
 * How [SpotifyPlayer] keeps the repeat the user chose, and what it plays again, without the page.
 *
 * Reported (2.1.0 to 2.3.0): repeat one loops a song two or three times, then the next song
 * plays. An ad took the song's end (see AdBlock.withoutAds), a play or a reload started the page
 * on its own options (repeat off), and the page dropped repeat one at the loop. The choice is one
 * value, [SpotifyPlayer.setRepeat]'s: set again where the page forgot it, given up where the
 * page's repeat was changed from another device.
 */
object RepeatRules {
    /** What a state the page reports does to the repeat the user chose. */
    enum class Report {
        /** The page plays the repeat chosen, or is being told it (a set of ours not answered yet). */
        KEPT,
        /** Set again on the page: it starts a play of ours, or comes round the loop, with less. */
        SET_AGAIN,
        /** The page's repeat is the choice from now on: changed on another device, or from there. */
        THEIRS,
        /** Left as it is: the page only mirrors, its repeat is not the user's. */
        IGNORED,
        /** Would be set again, but where the page plays is not read yet: kept, judged at that read. */
        WAIT,
    }

    /**
     * [held]: our own set is not answered yet. [afterOwnPlay]: the state is the first of a play or
     * a page of ours (it starts on its own options). [atLoop]: the song (or the list) is coming
     * round again, see [atLoop]. [elsewhere]: another device plays, and nothing is ever set on it
     * from here; null while not known (the element not read yet since the app started), when
     * nothing is set either. [mirrored]: the page only mirrors.
     */
    fun onReport(wanted: Repeat, reported: Repeat, held: Boolean, afterOwnPlay: Boolean, atLoop: Boolean, elsewhere: Boolean?, mirrored: Boolean): Report = when {
        reported == wanted || held -> Report.KEPT
        elsewhere == true -> Report.THEIRS
        afterOwnPlay -> if (elsewhere == null) Report.WAIT else Report.SET_AGAIN
        mirrored -> Report.IGNORED
        // Less repeat away from the loop is a change from another device (or the page's own word
        // after a skip): theirs. At the loop it is the page forgetting it.
        atLoop && reported < wanted -> if (elsewhere == null) Report.WAIT else Report.SET_AGAIN
        else -> Report.THEIRS
    }

    /**
     * The song [s] reports is coming round again under [wanted]: its clock within [LOOP_WINDOW_MS]
     * of its end, or the page just went from a song at its end ([before]) to the start of the
     * same song (repeat one) or of any song (repeat all: the list's last song, then its first).
     */
    fun atLoop(wanted: Repeat, before: PlayerState?, s: PlayerState, now: Long): Boolean {
        val item = s.item ?: return false
        val at = s.positionAt(now)
        if (item.durationMs > 0 && at >= item.durationMs - LOOP_WINDOW_MS) return true
        val was = before?.item ?: return false
        val wasAtEnd = was.durationMs > 0 && before.positionAt(now) >= was.durationMs - LOOP_WINDOW_MS
        return wasAtEnd && at <= LOOP_RESTART_MS && (wanted != Repeat.TRACK || was.uri == item.uri)
    }

    /**
     * The list [s]'s song is played again in, or null to play it alone. Reported (#135): a song
     * autoplay brought, played again in the list the page names, started that list from its
     * first song: the song is not in it. A station is made again on every play, the song with it.
     */
    fun replayList(s: PlayerState): Uri? =
        s.contextUri?.takeIf { s.item?.provider != "autoplay" && !isStation(it) }

    fun isStation(contextUri: Uri?): Boolean = contextUri?.startsWith("spotify:station:") == true

    /**
     * The order a station is played on in after our reload, from what came next on the page that
     * went ([nextUp]): [target] first, then the songs and episodes after it. Reported (#122): the
     * station played again without one was made again, and what came next was new. Null when
     * nothing comes after [target]. Given to that one play only: kept, it would turn the station
     * into a list that ends.
     */
    fun stationOrder(target: Uri, nextUp: List<NowPlaying>): List<Uri>? {
        val songs = nextUp.filter { it.kind == ItemKind.TRACK || it.kind == ItemKind.EPISODE }.map { it.uri }
        val after = songs.indexOf(target).let { at -> if (at >= 0) songs.drop(at + 1) else songs }.filter { it != target }
        return if (after.isEmpty()) null else listOf(target) + after
    }

    /** The page's clock this close to the end: the song was ending (the page reports every few seconds). */
    const val LOOP_WINDOW_MS = 5_000L
    /** A song the page shows this close to 0:00 started again. */
    const val LOOP_RESTART_MS = 10_000L
    /** Our own set of the repeat: the page's older reports are not taken for its answer this long. */
    const val REPEAT_HOLD_MS = 5_000L
}
