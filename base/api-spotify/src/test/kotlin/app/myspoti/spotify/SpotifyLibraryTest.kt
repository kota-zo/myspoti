package app.myspoti.spotify

import app.myspoti.api.LibraryFilter
import app.myspoti.api.LibrarySort
import app.myspoti.api.MediaKind
import app.myspoti.spotify.bridge.Bridge
import app.myspoti.spotify.bridge.BridgeHost
import app.myspoti.spotify.gql.Gql
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** A host that never answers: the library reads do not go through the bridge. */
private class SilentHost : BridgeHost {
    override val messages: Flow<String> = MutableSharedFlow(extraBufferCapacity = 1)
    override suspend fun evaluate(script: String) {}
}

/** Records each GraphQL request's variables and answers with [respond]. */
private class LibraryGql(val respond: (JsonObject) -> JsonElement) : Gql {
    val filters = mutableListOf<List<String>>()
    override suspend fun query(name: String, variables: JsonObject): JsonElement {
        filters += variables["filters"]!!.jsonArray.map { it.jsonPrimitive.content }
        return respond(variables)
    }
}

/**
 * A libraryV3 answer: [offered] are the account's filter ids; [shows] the show rows, unless the
 * request asked for a filter other than [acceptedFilter] (then the server answers no rows at all).
 */
private fun libraryAnswer(variables: JsonObject, offered: List<String>, acceptedFilter: String?, shows: List<String>): JsonElement {
    val filters = variables["filters"]!!.jsonArray.map { it.jsonPrimitive.content }
    val asked = filters.firstOrNull()
    val answersRows = asked == null || asked == acceptedFilter
    val offeredJson = offered.joinToString(",") { """{"id":"$it","name":"$it"}""" }
    val rows = if (!answersRows) "" else shows.joinToString(",") { uri ->
        """{"addedAt":{"isoString":"2026-01-01T00:00:00Z"},"depth":0,"pinned":false,"item":{"__typename":"PodcastResponseWrapper",
          "_uri":"$uri","data":{"__typename":"Podcast","uri":"$uri","name":"Show ${uri.substringAfterLast(':')}",
          "publisher":{"name":"Publisher"},"coverArt":{"sources":[]}}}}"""
    }
    return Bridge.json.parseToJsonElement("""{"data":{"me":{"libraryV3":{"__typename":"LibraryPage",
        "availableFilters":[$offeredJson],"items":[$rows],"totalCount":${if (answersRows) shows.size else 0}}}}}""")
}

class SpotifyLibraryTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterEach fun stop() = scope.cancel()

    @Test fun `the shows filter is the id the account is offered`() {
        assertEquals("Podcasts", podcastFilterId(listOf("Playlists", "Artists", "Albums")))
        assertEquals("Podcasts & Shows", podcastFilterId(listOf("Playlists", "Podcasts & Shows", "Artists")))
        assertEquals("Shows", podcastFilterId(listOf("Playlists", "Shows")))
        assertEquals("Podcasts", podcastFilterId(emptyList()))
    }

    @Test fun `the podcasts chip reads the shows under the offered id`() {
        val gql = LibraryGql { v -> libraryAnswer(v, listOf("Playlists", "Podcasts & Shows"), "Podcasts & Shows", listOf("spotify:show:s1", "spotify:show:s2")) }
        val library = SpotifyLibrary(Bridge(SilentHost(), scope), gql)
        val page = kotlinx.coroutines.runBlocking {
            library.items(LibraryFilter.PODCASTS, LibrarySort.RECENTS, null, 0, 50)
        }
        // One short read to learn the offered ids, then the filtered list.
        assertEquals(listOf(emptyList(), listOf("Podcasts & Shows")), gql.filters)
        assertEquals(listOf("spotify:show:s1", "spotify:show:s2"), page.items.map { it.uri })
        assertEquals(setOf(MediaKind.SHOW), page.items.map { it.kind }.toSet())
    }

    @Test fun `an account without a shows filter keeps the default id`() {
        val gql = LibraryGql { v -> libraryAnswer(v, listOf("Playlists", "Artists"), "Podcasts", emptyList()) }
        val library = SpotifyLibrary(Bridge(SilentHost(), scope), gql)
        kotlinx.coroutines.runBlocking { library.items(LibraryFilter.PODCASTS, LibrarySort.RECENTS, null, 0, 50) }
        assertEquals(listOf(emptyList(), listOf("Podcasts")), gql.filters)
    }
}
