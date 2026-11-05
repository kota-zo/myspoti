package app.myspoti.spotify

import app.myspoti.api.WhatsNewItem
import app.myspoti.api.WhatsNewState
import app.myspoti.api.whatsNewSections
import app.myspoti.spotify.bridge.Bridge
import app.myspoti.spotify.gql.Gql
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Instant

/** The "What's New" feed (`queryWhatsNewFeed`) and the page's New / Earlier split. */
class WhatsNewTest {
    private fun json(s: String) = Bridge.json.parseToJsonElement(s)

    // The fields the Web Player's page reads (dwp-whats-new-feed, 2026-11-03), trimmed.
    private val feed = json("""
        {"items":[
          {"id":"n1","state":{"state":"NEW","timestamp":null},
           "content":{"__typename":"AlbumResponseWrapper","data":{"__typename":"Album","uri":"spotify:album:a1","name":"Single One",
             "type":"ALBUM","albumType":"SINGLE","date":{"isoString":"2026-11-01T00:00:00Z"},
             "artists":{"items":[{"uri":"spotify:artist:x","profile":{"name":"Artist X"}}]},
             "coverArt":{"sources":[{"url":"https://i.scdn.co/image/c1","width":300,"height":300}]}}}},
          {"id":"s1","state":{"state":"SEEN","timestamp":{"isoString":"2026-10-20T08:00:00Z"}},
           "content":{"__typename":"EpisodeOrChapterResponseWrapper","data":{"__typename":"Episode","uri":"spotify:episode:e1","name":"Episode One",
             "description":"About it","duration":{"totalMilliseconds":1800000},"releaseDate":{"isoString":"2026-10-19T05:00:00Z"},
             "podcastV2":{"data":{"__typename":"Podcast","uri":"spotify:show:p","name":"The Show"}},
             "playability":{"playable":true}}}},
          {"id":"c1","state":{"state":"SEEN"},
           "content":{"__typename":"EpisodeOrChapterResponseWrapper","data":{"__typename":"Chapter","uri":"spotify:episode:ch"}}}
        ],"totalCount":3}
    """)

    @Test fun `feed rows map to albums and episodes, chapters dropped`() {
        val page = page(feed, 0, ::whatsNewItem)
        assertEquals(listOf("n1", "s1"), page.items.map { it.id })

        val album = page.items[0]
        assertEquals(WhatsNewState.NEW, album.state)
        assertNull(album.stateAt)
        assertEquals("SINGLE", album.album!!.type)
        assertEquals(listOf("Artist X"), album.album!!.artists.map { it.name })
        assertEquals("spotify:album:a1", album.uri)
        assertEquals("2026-11-01T00:00:00Z", album.releaseDate)

        val episode = page.items[1]
        assertNull(episode.album)
        assertEquals("The Show", episode.episode!!.show!!.name)
        assertEquals(1_800_000, episode.episode!!.durationMs)
        assertEquals("2026-10-20T08:00:00Z", episode.stateAt)
        // Raw rows count for paging: the dropped chapter still holds its position.
        assertNull(page.nextOffset)
    }

    @Test fun `a row without an id or content is skipped`() {
        assertNull(whatsNewItem(json("""{"state":{"state":"NEW"},"content":{"data":{"__typename":"Album","uri":"spotify:album:a"}}}""")))
        assertNull(whatsNewItem(json("""{"id":"x","state":{"state":"NEW"},"content":{"data":{"__typename":"NotFound"}}}""")))
        assertNotNull(whatsNewItem(json("""{"id":"x","state":{"state":"ODD"},"content":{"data":{"__typename":"Album","uri":"spotify:album:a"}}}""")))
    }

    private val now = Instant.parse("2026-11-03T12:00:00Z").toEpochMilli()

    private fun item(id: String, state: WhatsNewState, stateAt: String? = null, released: String?) = WhatsNewItem(
        id, state, stateAt,
        album = app.myspoti.api.Album("spotify:album:$id", id, emptyList(), emptyList(), "SINGLE", released, 1),
    )

    @Test fun `new is the leading run of unseen or just-seen entries`() {
        val sections = whatsNewSections(listOf(
            item("unseen", WhatsNewState.NEW, released = "2026-11-02T00:00:00Z"),
            // Seen 30 minutes ago: still new, as on the Web Player.
            item("justSeen", WhatsNewState.SEEN, "2026-11-03T11:30:00Z", released = "2026-11-03T00:00:00Z"),
            item("old", WhatsNewState.SEEN, "2026-11-01T11:00:00Z", released = "2026-10-30T00:00:00Z"),
            // After the first older entry everything is earlier, even an unseen one.
            item("late", WhatsNewState.NEW, released = "2026-10-31T00:00:00Z"),
        ), now)
        assertEquals(listOf("justSeen", "unseen"), sections.fresh.map { it.id })
        assertEquals(listOf("late", "old"), sections.earlier.map { it.id })
    }

    @Test fun `each section runs newest first, undated entries last`() {
        val sections = whatsNewSections(listOf(
            item("b", WhatsNewState.SEEN, "2026-10-01T00:00:00Z", released = "2026-10-02"),
            item("none", WhatsNewState.SEEN, "2026-10-01T00:00:00Z", released = null),
            item("c", WhatsNewState.SEEN, "2026-10-01T00:00:00Z", released = "2026-10-05T10:00:00+02:00"),
            item("a", WhatsNewState.SEEN, "2026-10-01T00:00:00Z", released = "2026-09-30T00:00:00Z"),
        ), now)
        assertEquals(emptyList<WhatsNewItem>(), sections.fresh)
        assertEquals(listOf("c", "b", "a", "none"), sections.earlier.map { it.id })
    }
    @Test fun `marking seen sends the list inside the items input object`() = runBlocking {
        val sent = mutableListOf<Pair<String, JsonObject>>()
        val gql = object : Gql {
            override suspend fun query(name: String, variables: JsonObject) =
                json("""{"data":{"setItemsStateInWhatsNewFeed":{"items":[]}}}""").also { sent += name to variables }
        }
        SpotifyHome(gql).markWhatsNewSeen(listOf("music_release:a", "podcast_release:b"))
        SpotifyHome(gql).markWhatsNewSeen(emptyList())
        // Shape accepted live (2026-11-03); a bare list answered 400 VALIDATION_INVALID_TYPE_VARIABLE.
        assertEquals(listOf("SetItemsStateInWhatsNewFeed" to json(
            """{"items":{"items":[{"id":"music_release:a","state":"SEEN"},{"id":"podcast_release:b","state":"SEEN"}]}}""")), sent)
    }
}
