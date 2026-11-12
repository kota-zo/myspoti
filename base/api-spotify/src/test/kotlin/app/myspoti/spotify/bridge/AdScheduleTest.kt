package app.myspoti.spotify.bridge

import app.myspoti.spotify.AccountPlan
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Where the next ad comes, read from the two shapes of ad-bearing state machines seen on 20/10 (made-up names and ids). */
class AdScheduleTest {
    @AfterEach fun forget() { AdSchedule.clear(); AccountPlan.set(null) }

    private fun track(type: String, name: String) =
        """{"content_type":"$type","metadata":{"uri":"spotify:${type.lowercase()}:${name.replace(' ', '-')}","name":"$name"},"manifest":{}}"""

    private fun go(to: Int?) = if (to == null) "null" else """{"state_index":$to,"paused":false,"active_alias":null}"""

    private fun state(track: Int, advance: Int? = null, next: Int? = null, show: Int? = null, uid: String? = null) =
        """{"state_id":"s$track",${uid?.let { "\"track_uid\":\"$it\"," } ?: ""}"track":$track,"transitions":{"skip_next":${go(next)},"skip_prev":null,"show_next":${go(show)},"show_prev":null,"advance":${go(advance)}}}"""

    private fun reply(tracks: List<String>, states: List<String>, current: Int) =
        """{"state_machine":{"state_machine_id":"m","tracks":[${tracks.joinToString(",")}],"states":[${states.joinToString(",")}],"attributes":{}},"updated_state_ref":{"state_index":$current,"paused":false}}"""

    // First shape: the song's end goes to the ad, its skip to the next song.
    private val first = reply(
        listOf(track("AD", "An ad"), track("TRACK", "Before"), track("TRACK", "Playing"), track("TRACK", "After")),
        listOf(state(0), state(1), state(2, advance = 0, next = 4, show = 4, uid = "u2"), state(2), state(3)),
        current = 2,
    )

    // Second shape: skip and end both go to the ad, the ad's end to the song after it.
    private val second = reply(
        listOf(track("AD", "An ad"), track("TRACK", "After"), track("TRACK", "Before"), track("TRACK", "Playing")),
        listOf(state(0, advance = 4), state(0), state(3, advance = 0, next = 1, show = 4), state(0), state(1), state(2)),
        current = 2,
    )

    @Test fun `an ad at the end of the song playing is due, with the song after the break`() {
        assertEquals(AdSchedule.Due("u2", "spotify:track:Playing", "spotify:track:After"), AdSchedule.dueIn(first))
        assertEquals(AdSchedule.Due(null, "spotify:track:Playing", "spotify:track:After"), AdSchedule.dueIn(second))
    }

    @Test fun `no ad due when the song's end leads to a song, or when the ad is already on`() {
        val plain = reply(listOf(track("AD", "An ad"), track("TRACK", "A"), track("TRACK", "B")), listOf(state(0), state(1, advance = 2), state(2)), current = 1)
        assertNull(AdSchedule.dueIn(plain))
        val onAir = reply(listOf(track("AD", "An ad"), track("TRACK", "B")), listOf(state(0, advance = 1), state(1)), current = 0)
        assertNull(AdSchedule.dueIn(onAir))
        assertNull(AdSchedule.dueIn("""{"x":"AD" """))
    }

    @Test fun `the reply is read, never changed, and one without a state machine keeps what was due`() {
        AdSchedule.record(first)
        assertEquals("spotify:track:Playing", AdSchedule.due.value?.songUri)
        AdSchedule.record("""{"ack":true}""")
        assertEquals("spotify:track:Playing", AdSchedule.due.value?.songUri)
        AdSchedule.record(reply(listOf(track("TRACK", "A")), listOf(state(0)), current = 0))
        assertNull(AdSchedule.due.value)
    }

    @Test fun `a Premium account has no ad due`() {
        AccountPlan.set(true)
        AdSchedule.record(first)
        assertNull(AdSchedule.due.value)
    }

    // ── Ads flagged by their metadata only, and songs that merely look like ads ──

    /** A track typed TRACK whose metadata alone says what it is. */
    private fun flagged(name: String, meta: String) =
        """{"content_type":"TRACK","metadata":{"uri":"spotify:track:${name.replace(' ', '-')}","name":"$name"$meta},"manifest":{}}"""

    private fun firstShapeWith(ad: String) = reply(
        listOf(ad, track("TRACK", "Before"), track("TRACK", "Playing"), track("TRACK", "After")),
        listOf(state(0), state(1), state(2, advance = 0, next = 4, show = 4), state(2), state(3)),
        current = 2,
    )

    @Test fun `an ad flagged only by is_advertisement, an ad uri or an ad_id is an ad too`() {
        val marked = listOf(
            flagged("Promo", ""","is_advertisement":"true""""),
            """{"content_type":"TRACK","metadata":{"uri":"spotify:ad:123","name":"Promo"},"manifest":{}}""",
            flagged("Promo", ""","ad_id":"abc123""""),
        )
        for (ad in marked) assertEquals("spotify:track:Playing", AdSchedule.dueIn(firstShapeWith(ad))?.songUri, ad)
    }

    @Test fun `a song that only looks like an ad is no ad`() {
        val songs = listOf(
            flagged("AD", ""),
            flagged("Bad Ad", ""","album_title":"Ad Astra""""),
            flagged("Ads", ""","is_advertisement":"false""""),
            flagged("Adagio", ""","ad_id":"""""),
            """{"content_type":"TRACK","metadata":{"uri":"spotify:track:adadadad","name":"spotify:ad: in a title"},"manifest":{}}""",
        )
        for (song in songs) {
            assertNull(AdSchedule.dueIn(firstShapeWith(song)), song)
            assertTrue(!AdSchedule.isAdTrack(Json.parseToJsonElement(song).jsonObject), song)
        }
    }
}
