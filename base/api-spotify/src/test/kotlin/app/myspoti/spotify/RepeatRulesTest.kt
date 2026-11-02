package app.myspoti.spotify

import app.myspoti.api.ItemKind
import app.myspoti.api.NowPlaying
import app.myspoti.api.PlayerRestrictions
import app.myspoti.api.PlayerState
import app.myspoti.api.Repeat
import app.myspoti.spotify.RepeatRules.Report
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RepeatRulesTest {
    private fun item(name: String, kind: ItemKind = ItemKind.TRACK, provider: String? = null, ms: Long = 200_000) =
        NowPlaying(kind, "spotify:${kind.name.lowercase()}:$name", "u$name", name, emptyList(), null, null, ms, provider = provider)

    private fun at(item: NowPlaying, pos: Long, ctx: String? = "spotify:playlist:C", now: Long = 1_000_000L) =
        PlayerState(item = item, contextUri = ctx, isPaused = false, isBuffering = false, positionMs = pos, timestampMs = now,
            speed = 1.0, shuffle = false, repeat = Repeat.OFF, restrictions = PlayerRestrictions())

    private fun report(wanted: Repeat, reported: Repeat, held: Boolean = false, afterOwnPlay: Boolean = false, atLoop: Boolean = false,
                       elsewhere: Boolean = false, mirrored: Boolean = false) =
        RepeatRules.onReport(wanted, reported, held, afterOwnPlay, atLoop, elsewhere, mirrored)

    @Test fun `less repeat at the loop is the page forgetting it, set again`() {
        assertEquals(Report.SET_AGAIN, report(Repeat.TRACK, Repeat.CONTEXT, atLoop = true))
        assertEquals(Report.SET_AGAIN, report(Repeat.TRACK, Repeat.OFF, atLoop = true))
        assertEquals(Report.SET_AGAIN, report(Repeat.CONTEXT, Repeat.OFF, atLoop = true))
        // A play of ours starts the page on its own options, wherever the song is.
        assertEquals(Report.SET_AGAIN, report(Repeat.TRACK, Repeat.OFF, afterOwnPlay = true))
    }

    @Test fun `less repeat away from the loop, or more anywhere, is the other device's and becomes the choice`() {
        // Seen in review of #139: repeat one turned off on another device came back at the next song's end.
        assertEquals(Report.THEIRS, report(Repeat.TRACK, Repeat.OFF))
        assertEquals(Report.THEIRS, report(Repeat.CONTEXT, Repeat.OFF))
        assertEquals(Report.THEIRS, report(Repeat.CONTEXT, Repeat.TRACK, atLoop = true))
    }

    @Test fun `another device playing is never given the repeat from here`() {
        assertEquals(Report.THEIRS, report(Repeat.TRACK, Repeat.OFF, atLoop = true, elsewhere = true))
        assertEquals(Report.THEIRS, report(Repeat.TRACK, Repeat.OFF, afterOwnPlay = true, elsewhere = true))
    }

    @Test fun `a set of ours not answered yet, the same repeat or a mirror leave the choice alone`() {
        assertEquals(Report.KEPT, report(Repeat.TRACK, Repeat.OFF, held = true))
        assertEquals(Report.KEPT, report(Repeat.TRACK, Repeat.OFF, held = true, elsewhere = true))
        assertEquals(Report.KEPT, report(Repeat.TRACK, Repeat.TRACK, atLoop = true))
        assertEquals(Report.IGNORED, report(Repeat.TRACK, Repeat.OFF, atLoop = true, mirrored = true))
        assertEquals(Report.IGNORED, report(Repeat.TRACK, Repeat.OFF, mirrored = true))
    }

    @Test fun `the loop is the song's end, or its start again just after it`() {
        val a = item("A")
        val now = 1_000_000L
        assertTrue(RepeatRules.atLoop(Repeat.TRACK, null, at(a, 197_000), now))
        assertFalse(RepeatRules.atLoop(Repeat.TRACK, null, at(a, 60_000), now))
        // The same song at its end, then near 0:00: repeat one came round.
        assertTrue(RepeatRules.atLoop(Repeat.TRACK, at(a, 199_000), at(a, 500), now))
        // Another song after the end is no loop of repeat one (the next song), but is one of repeat all.
        assertFalse(RepeatRules.atLoop(Repeat.TRACK, at(a, 199_000), at(item("B"), 500), now))
        assertTrue(RepeatRules.atLoop(Repeat.CONTEXT, at(a, 199_000), at(item("B"), 500), now))
        // A skip mid-song, or a report long into the song, is no loop.
        assertFalse(RepeatRules.atLoop(Repeat.TRACK, at(a, 60_000), at(a, 0), now))
        assertFalse(RepeatRules.atLoop(Repeat.TRACK, at(a, 199_000), at(a, 30_000), now))
    }

    @Test fun `a song autoplay brought, or a station's, is played again alone`() {
        assertEquals("spotify:playlist:C", RepeatRules.replayList(at(item("A"), 0)))
        assertNull(RepeatRules.replayList(at(item("A", provider = "autoplay"), 0)))
        assertNull(RepeatRules.replayList(at(item("A"), 0, ctx = "spotify:station:track:X")))
        assertNull(RepeatRules.replayList(at(item("A"), 0, ctx = null)))
    }

    @Test fun `a station goes on after a reload in the order it had, from the target`() {
        val up = listOf(item("B"), item("ad", ItemKind.AD), item("C"), item("E", ItemKind.EPISODE), item("D"))
        // The target first, once, then what came after it; no ad.
        assertEquals(listOf("spotify:track:B", "spotify:track:C", "spotify:episode:E", "spotify:track:D"), RepeatRules.stationOrder("spotify:track:B", up))
        assertEquals(listOf("spotify:track:C", "spotify:episode:E", "spotify:track:D"), RepeatRules.stationOrder("spotify:track:C", up))
        // A target not in the list (the state's own next): put before all of it.
        assertEquals(listOf("spotify:track:Z", "spotify:track:B", "spotify:track:C", "spotify:episode:E", "spotify:track:D"), RepeatRules.stationOrder("spotify:track:Z", up))
        // Nothing after the target: no order, the station is played as it is.
        assertNull(RepeatRules.stationOrder("spotify:track:D", up))
        assertNull(RepeatRules.stationOrder("spotify:track:B", emptyList()))
    }
}
