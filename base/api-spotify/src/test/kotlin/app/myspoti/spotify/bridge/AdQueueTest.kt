package app.myspoti.spotify.bridge

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue

/** The two shapes of ad-bearing state machines seen on 20/10, with made-up names and ids. */
class AdQueueTest {
    private fun track(type: String, name: String) =
        """{"content_type":"$type","metadata":{"uri":"spotify:${type.lowercase()}:${name.replace(' ', '-')}","name":"$name"},"manifest":{}}"""

    private fun go(to: Int?) = if (to == null) "null" else """{"state_index":$to,"paused":false,"active_alias":null}"""

    private fun state(track: Int, advance: Int? = null, next: Int? = null, show: Int? = null) =
        """{"state_id":"s$track","track":$track,"transitions":{"skip_next":${go(next)},"skip_prev":null,"show_next":${go(show)},"show_prev":null,"advance":${go(advance)}}}"""

    private fun reply(tracks: List<String>, states: List<String>, current: Int) =
        """{"state_machine":{"state_machine_id":"m","tracks":[${tracks.joinToString(",")}],"states":[${states.joinToString(",")}],"attributes":{}},"updated_state_ref":{"state_index":$current,"paused":false}}"""

    private fun transitions(body: String, state: Int): JsonObject =
        Json.parseToJsonElement(body).jsonObject["state_machine"]!!.jsonObject["states"]!!.jsonArray[state].jsonObject["transitions"]!!.jsonObject

    private fun index(t: JsonObject, key: String) = t[key]!!.jsonObject["state_index"]!!.jsonPrimitive.int

    // First shape: the song's end goes to the ad, its skip to the next song.
    private val first = reply(
        listOf(track("AD", "An ad"), track("TRACK", "Before"), track("TRACK", "Playing"), track("TRACK", "After")),
        listOf(state(0), state(1), state(2, advance = 0, next = 4, show = 4), state(2), state(3)),
        current = 2,
    )

    // Second shape, the try after a left-out ad: skip and end both go to the ad, only the shown
    // next song does not.
    private val second = reply(
        listOf(track("AD", "An ad"), track("TRACK", "After"), track("TRACK", "Before"), track("TRACK", "Playing")),
        listOf(state(0), state(0), state(3, advance = 0, next = 1, show = 4), state(0), state(1), state(2)),
        current = 2,
    )

    @Test fun `the end of the song before an ad goes to the song after it`() {
        val (body, notes) = AdBlock.withoutAds(first)!!
        val t = transitions(body, 2)
        assertEquals(4, index(t, "advance"))
        assertEquals(4, index(t, "skip_next"))
        assertEquals(listOf("advance after Playing: After instead of an ad"), notes)
    }

    @Test fun `when the skip goes to the ad too, the reply is left as it is`() =
        // show_next is a display-only state: the server refuses a page sent there (502).
        assertNull(AdBlock.withoutAds(second))

    @Test fun `everything else in the reply is kept`() {
        val (body, _) = AdBlock.withoutAds(first)!!
        val root = Json.parseToJsonElement(body).jsonObject
        assertEquals(2, root["updated_state_ref"]!!.jsonObject["state_index"]!!.jsonPrimitive.int)
        assertEquals(4, root["state_machine"]!!.jsonObject["tracks"]!!.jsonArray.size)
        assertTrue("\"AD\"" in body, "the ad stays listed, it is only never reached")
    }

    @Test fun `an ad on both ways on names the song to play before the end`() {
        val a = AdBlock.adAhead(second)!!
        assertEquals("spotify:track:Playing", a.songUri)
        assertEquals("spotify:track:After", a.nextUri)
    }

    @Test fun `no play before the end when the end can be sent to the next song`() {
        assertNull(AdBlock.adAhead(first))
        assertNull(AdBlock.adAhead(AdBlock.withoutAds(first)!!.first))
    }

    // Repeat one (captured 21/10): the song's advance is its own repeat, its skip the next song with repeat off.
    private fun repeatState(track: Int, repeating: Boolean, advance: Int? = null, next: Int? = null, show: Int? = null) =
        state(track, advance, next, show).dropLast(1) + ""","options":{"repeating_track":$repeating}}"""

    private val repeated = reply(
        listOf(track("AD", "An ad"), track("TRACK", "Playing"), track("TRACK", "After")),
        listOf(repeatState(0, true), repeatState(1, true, advance = 0, next = 3, show = 4), repeatState(1, true, show = 4),
            repeatState(2, false, show = 4), repeatState(2, false)),
        current = 1,
    )

    @Test fun `the end of a repeated song before an ad goes to its repeat, not to the next song`() {
        val (body, notes) = AdBlock.withoutAds(repeated)!!
        val t = transitions(body, 1)
        assertEquals(2, index(t, "advance"))
        assertEquals(3, index(t, "skip_next"))
        assertEquals(listOf("advance after Playing: repeated instead of an ad"), notes)
    }

    @Test fun `a repeated song with no state of its repeat falls back to the skip's target`() {
        val noRepeat = reply(
            listOf(track("AD", "An ad"), track("TRACK", "Playing"), track("TRACK", "After")),
            listOf(repeatState(0, true), repeatState(1, true, advance = 0, next = 2, show = 3), repeatState(2, false, show = 3), repeatState(2, false)),
            current = 1,
        )
        assertEquals(2, index(transitions(AdBlock.withoutAds(noRepeat)!!.first, 1), "advance"))
    }

    @Test fun `a reply without an ad is left alone`() {
        val plain = reply(listOf(track("TRACK", "A"), track("TRACK", "B")), listOf(state(0, advance = 1, next = 1), state(1)), current = 0)
        assertNull(AdBlock.withoutAds(plain))
    }

    @Test fun `an ad already playing is not touched`() {
        val playing = reply(listOf(track("AD", "An ad"), track("TRACK", "B")), listOf(state(0), state(1)), current = 0)
        assertNull(AdBlock.withoutAds(playing))
    }

    @Test fun `an unreadable reply is left alone`() = assertNull(AdBlock.withoutAds("""{"x":"AD" """))

    // ── Ads flagged by their metadata only, and songs that merely look like ads ──

    private fun raw(json: String) = json

    /** A track typed TRACK whose metadata alone says what it is. */
    private fun flagged(name: String, meta: String) =
        """{"content_type":"TRACK","metadata":{"uri":"spotify:track:${name.replace(' ', '-')}","name":"$name"$meta},"manifest":{}}"""

    private fun firstShapeWith(ad: String) = reply(
        listOf(raw(ad), track("TRACK", "Before"), track("TRACK", "Playing"), track("TRACK", "After")),
        listOf(state(0), state(1), state(2, advance = 0, next = 4, show = 4), state(2), state(3)),
        current = 2,
    )

    @Test fun `an ad flagged only by is_advertisement, an ad uri or an ad_id is left out too`() {
        val marked = listOf(
            flagged("Promo", ""","is_advertisement":"true""""),
            """{"content_type":"TRACK","metadata":{"uri":"spotify:ad:123","name":"Promo"},"manifest":{}}""",
            flagged("Promo", ""","ad_id":"abc123""""),
        )
        for (ad in marked) {
            val (body, notes) = AdBlock.withoutAds(firstShapeWith(ad))!!
            assertEquals(4, index(transitions(body, 2), "advance"), ad)
            assertEquals(listOf("advance after Playing: After instead of an ad"), notes)
        }
    }

    @Test fun `a song that only looks like an ad is never left out`() {
        val songs = listOf(
            flagged("AD", ""),
            flagged("Bad Ad", ""","album_title":"Ad Astra""""),
            flagged("Ads", ""","is_advertisement":"false""""),
            flagged("Adagio", ""","ad_id":"""""),
            """{"content_type":"TRACK","metadata":{"uri":"spotify:track:adadadad","name":"spotify:ad: in a title"},"manifest":{}}""",
        )
        for (song in songs) {
            val body = firstShapeWith(song)
            assertNull(AdBlock.withoutAds(body), song)
            assertNull(AdBlock.adAhead(body), song)
            assertTrue(!AdBlock.isAdTrack(Json.parseToJsonElement(song).jsonObject), song)
        }
    }

    @Test fun `why an ad was kept is told`() {
        val playing = reply(listOf(track("AD", "An ad"), track("TRACK", "B")), listOf(state(0), state(1)), current = 0)
        assertEquals("AD: the ad is the current song", AdBlock.whyKept(playing))
        assertEquals("AD: the song before the ad has no other way on (its skip leads to the ad too)", AdBlock.whyKept(second))
        val notAnAd = firstShapeWith(flagged("Ads", ""","is_advertisement":"false""""))
        assertEquals("is_advertisement: no ad track (the marker is elsewhere in the reply)", AdBlock.whyKept(notAnAd))
        assertNull(AdBlock.whyKept(reply(listOf(track("TRACK", "A")), listOf(state(0)), current = 0)))
    }
}
