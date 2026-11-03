package app.myspoti.spotify

import app.myspoti.api.MediaKind
import app.myspoti.spotify.bridge.Bridge
import app.myspoti.spotify.gql.Gql
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** A browseCategory page (`browsePage`, as the Podcasts and Documentaries categories open) keeps its shows and episodes. */
class BrowseCategoryTest {
    private class PageGql(val answer: JsonElement) : Gql {
        override suspend fun query(name: String, variables: JsonObject): JsonElement = answer
    }

    // Sanitised shape: a podcast category holds show and episode cards only.
    private val answer = Bridge.json.parseToJsonElement("""
        {"data":{"browse":{"__typename":"BrowseSectionContainer","uri":"spotify:page:test",
          "header":{"title":{"transformedLabel":"Podcasts"}},
          "sections":{"items":[
            {"__typename":"BrowseSection","uri":"spotify:section:shows",
             "data":{"__typename":"BrowseGenericSectionData","title":{"transformedLabel":"Popular podcasts"}},
             "sectionItems":{"totalCount":2,"items":[
               {"content":{"__typename":"PodcastResponseWrapper","data":{"__typename":"Podcast",
                 "name":"Show A","uri":"spotify:show:AAAAAAAAAAAAAAAAAAAAAA","publisher":{"name":"Publisher A"}}}},
               {"content":{"__typename":"PodcastResponseWrapper","data":{"__typename":"Podcast",
                 "name":"Show B","uri":"spotify:show:BBBBBBBBBBBBBBBBBBBBBB","publisher":{"name":"Publisher B"}}}}
             ]}},
            {"__typename":"BrowseSection","uri":"spotify:section:episodes",
             "data":{"__typename":"BrowseGenericSectionData","title":{"transformedLabel":"Latest episodes"}},
             "sectionItems":{"items":[
               {"content":{"__typename":"EpisodeResponseWrapper","data":{"__typename":"Episode",
                 "name":"Episode 1","uri":"spotify:episode:EEEEEEEEEEEEEEEEEEEEEE",
                 "podcastV2":{"__typename":"PodcastResponseWrapper","data":{"__typename":"Podcast","name":"Show A"}}}}}
             ]}}
          ]}}}}
    """.trimIndent())

    @Test
    fun `podcast category keeps its shows and episodes`() = runBlocking {
        val page = SpotifyHome(PageGql(answer)).category("spotify:page:test")
        val cards = page.shelves.flatMap { it.items }
        assertEquals(listOf("Show A", "Show B", "Episode 1"), cards.map { it.title })
        assertEquals(listOf(MediaKind.SHOW, MediaKind.SHOW, MediaKind.EPISODE), cards.map { it.kind })
        assertEquals("Show A", cards[2].subtitle)
    }
}
