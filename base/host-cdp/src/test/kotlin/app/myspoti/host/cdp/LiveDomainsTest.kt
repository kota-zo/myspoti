package app.myspoti.host.cdp

import app.myspoti.api.HomeFilter
import app.myspoti.api.JamAvailability
import app.myspoti.api.LibraryFilter
import app.myspoti.api.LibrarySort
import app.myspoti.api.MediaKind
import app.myspoti.api.TimeRange
import app.myspoti.api.WhatsNewContent
import app.myspoti.spotify.SpotifyApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.condition.EnabledIfSystemProperty

/** Every domain beyond playback, against the real Web Player. Writes are undone in the same test. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfSystemProperty(named = "myspoti.cdp", matches = ".+")
class LiveDomainsTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var host: CdpHost
    private lateinit var api: SpotifyApi

    private fun live(block: suspend () -> Any?) { runBlocking { withTimeout(120_000) { block() } } }

    private suspend fun <T> eventually(timeoutMs: Long = 15_000, check: suspend () -> T?): T = step("?", timeoutMs, check)

    private suspend fun <T> step(label: String, timeoutMs: Long = 15_000, check: suspend () -> T?): T {
        println("step: $label")
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            runCatching { check() }.getOrNull()?.let { return it }
            delay(500)
        }
        error("$label: not reached in $timeoutMs ms")
    }

    @BeforeAll
    fun connect() = live {
        host = CdpHost.connect(System.getProperty("myspoti.cdp"))
        api = SpotifyApi(host, scope, language = "fr")
        host.reload()
        host.awaitLoad()
        api.onPageLoaded()
        api.awaitReady()
    }

    @AfterAll
    fun close() {
        host.close()
        scope.cancel()
    }

    @Test
    fun `home feed, filters and categories`() = live {
        val feed = api.home.feed()
        assertTrue(feed.greeting.isNotBlank())
        assertTrue(feed.shelves.size >= 5 && feed.shelves.all { it.items.isNotEmpty() })
        assertTrue(HomeFilter.PODCASTS in feed.filters)
        val podcasts = api.home.feed(HomeFilter.PODCASTS)
        assertTrue(podcasts.shelves.flatMap { it.items }.any { it.kind == MediaKind.SHOW || it.kind == MediaKind.EPISODE })
        val categories = api.home.categories()
        assertTrue(categories.size > 20 && categories.all { it.title.isNotBlank() })
        val news = api.home.whatsNew(0, 10)
        assertTrue(news.items.all { it.uri.startsWith("spotify:album:") && it.name.isNotBlank() })
        val page = api.home.category(categories.first().uri)
        assertTrue(page.title.isNotBlank() && page.shelves.isNotEmpty())
    }

    // Reads only: the entries' seen state is left as it was (no markWhatsNewSeen).
    @Test
    fun `what's new feed holds albums and episodes, per pill`() = live {
        val all = api.home.whatsNewFeed(0, 20)
        assertTrue(all.items.all { it.id.isNotBlank() && (it.album != null) != (it.episode != null) })
        assertTrue(all.items.all { it.uri.startsWith("spotify:album:") || it.uri.startsWith("spotify:episode:") })
        assertTrue(api.home.whatsNewFeed(0, 20, WhatsNewContent.MUSIC).items.all { it.album != null })
        assertTrue(api.home.whatsNewFeed(0, 20, WhatsNewContent.PODCASTS).items.all { it.episode != null })
    }

    @Test
    fun `search every tab and recent searches`() = live {
        assertTrue(api.search.shows("france inter").items.first().kind == MediaKind.SHOW)
        assertTrue(api.search.episodes("france inter").items.first().durationMs > 0)
        assertTrue(api.search.audiobooks("harry potter").items.isNotEmpty())
        assertTrue(api.search.users("spotify").items.any { it.uri == "spotify:user:spotify" })
        assertTrue(api.search.genres("rap").items.isNotEmpty())
        api.search.recent()
    }

    @Test
    fun `artist pages beyond the overview`() = live {
        val artist = "spotify:artist:1McMsnEElThX1knmY4oliG"
        val disco = api.catalog.discography(artist, 0, 10)
        assertTrue(disco.items.size == 10 && disco.total > 10 && disco.items.all { it.uri.startsWith("spotify:album:") })
        assertTrue(api.catalog.relatedArtists(artist).size >= 10)
        assertTrue(api.catalog.appearsOn(artist).isNotEmpty())
        assertTrue(api.catalog.similarAlbums("spotify:track:4EoJ151oQ5jY48z4RhSE96").isNotEmpty())
    }

    @Test
    fun `library list, filters, liked songs and pins`() = live {
        val all = api.library.items()
        assertTrue(all.items.any { it.uri == "spotify:collection:tracks" })
        api.library.items(filter = LibraryFilter.PLAYLISTS, sort = LibrarySort.ALPHABETICAL)
        val liked = api.library.likedTracks()
        assertTrue(liked.items.all { it.track.uri.startsWith("spotify:track:") && it.track.durationMs > 0 })

        val target = all.items.first { it.uri != "spotify:collection:tracks" && !it.pinned }
        api.library.pin(target.uri)
        eventually { api.library.items().items.firstOrNull { it.uri == target.uri }?.takeIf { it.pinned } }
        api.library.unpin(target.uri)
        eventually { api.library.items().items.firstOrNull { it.uri == target.uri }?.takeIf { !it.pinned } }
    }

    @Test
    fun `folders and playlists are created, edited and removed`() = live {
        val folder = api.library.createFolder("Myspoti test folder")
        api.library.renameFolder(folder, "Myspoti test folder 2")
        step("folder renamed") { api.library.items().items.firstOrNull { it.uri == folder && it.name == "Myspoti test folder 2" } }

        val pl = api.playlists.create("Myspoti test playlist")
        step("playlist created") { api.playlists.mine().firstOrNull { it.uri == pl } }
        api.playlists.rename(pl, "Myspoti test playlist 2")
        api.playlists.setDescription(pl, "made by the live tests")
        step("playlist edited") { api.playlists.playlist(pl).takeIf { it.name == "Myspoti test playlist 2" && it.description == "made by the live tests" } }

        val songs = api.playlists.tracks(LiveApiTest.TOP_HITS, 0, 3).items.map { it.uri }
        api.playlists.addTracks(pl, songs)
        val rows = step("tracks added") { api.playlists.tracks(pl).items.takeIf { it.size == 3 } }
        api.playlists.moveTracks(pl, listOf(rows[2].uid!!), beforeUid = rows[0].uid)
        step("tracks moved") { api.playlists.tracks(pl).items.takeIf { it.first().uri == rows[2].uri } }
        api.playlists.removeTracks(pl, listOf(rows[1].uid!!))
        step("track removed") { api.playlists.tracks(pl).items.takeIf { it.size == 2 } }

        api.library.moveToFolder(pl, folder)
        step("moved to folder") { api.library.items(folderUri = folder).items.firstOrNull { it.uri == pl } }

        api.playlists.delete(pl)
        step("playlist deleted") { api.playlists.mine().none { it.uri == pl }.takeIf { it } }
        api.library.deleteFolder(folder)
        step("folder deleted") { api.library.items().items.none { it.uri == folder }.takeIf { it } }
    }

    @Test
    fun `podcasts shows and episodes`() = live {
        val show = api.podcasts.show("spotify:show:3vS7gWYpz4ry0yRnQ345hU")
        assertTrue(show.name.isNotBlank() && show.publisher.isNotBlank())
        val episodes = api.podcasts.episodes(show.uri, 0, 5)
        assertEquals(5, episodes.items.size)
        val ep = api.podcasts.episode(episodes.items.first().uri)
        assertTrue(ep.durationMs > 0 && ep.show?.uri == show.uri)
        val book = api.search.audiobooks("harry potter").items.first()
        val audiobook = api.podcasts.audiobook(book.uri)
        assertTrue(audiobook.name.isNotBlank())
        val chapters = api.podcasts.chapters(book.uri, 0, 3)
        assertTrue(chapters.items.isNotEmpty())

        val saved = api.library.contains(listOf(ep.uri)).single()
        if (!saved) api.library.add(listOf(ep.uri))
        eventually { api.podcasts.savedEpisodes().items.firstOrNull { it.uri == ep.uri } }
        if (!saved) {
            api.library.remove(listOf(ep.uri))
            eventually { api.podcasts.savedEpisodes().items.none { it.uri == ep.uri }.takeIf { it } }
        }
    }

    @Test
    fun `users, profile, top content and history`() = live {
        val me = api.users.me()
        assertTrue(me.uri.startsWith("spotify:user:") && !me.premium)
        val spotify = api.users.profile("spotify:user:spotify")
        assertTrue((spotify.followers ?: 0) > 1000 && spotify.publicPlaylists.isNotEmpty())
        api.users.top(TimeRange.SHORT_TERM)
        assertTrue(api.users.history().isNotEmpty())

        val was = api.users.isFollowing(listOf("spotify:user:spotify")).single()
        if (was) api.users.unfollow(listOf("spotify:user:spotify")) else api.users.follow(listOf("spotify:user:spotify"))
        eventually { api.users.isFollowing(listOf("spotify:user:spotify")).single().takeIf { it != was } }
        if (was) api.users.follow(listOf("spotify:user:spotify")) else api.users.unfollow(listOf("spotify:user:spotify"))
        eventually { api.users.isFollowing(listOf("spotify:user:spotify")).single().takeIf { it == was } }
        println("friend activity: " + api.users.friendActivity().size)
    }

    @Test
    fun `lyrics, canvas, credits and radio`() = live {
        val lyrics = api.extras.lyrics("spotify:track:4EoJ151oQ5jY48z4RhSE96")
        assertNotNull(lyrics)
        assertTrue(lyrics!!.synced && lyrics.lines.size > 10 && lyrics.background != null)
        assertNotNull(api.extras.canvas("spotify:track:55vPnuXfuzXNW75JpuSczF"))
        assertTrue(api.extras.credits("spotify:track:4EoJ151oQ5jY48z4RhSE96").roles.isNotEmpty())
        assertTrue(api.extras.radio("spotify:track:4EoJ151oQ5jY48z4RhSE96")!!.startsWith("spotify:playlist:"))
        assertTrue(api.extras.radio("spotify:artist:1McMsnEElThX1knmY4oliG")!!.startsWith("spotify:playlist:"))
    }

    @Test
    fun `devices and jam`() = live {
        api.devices.refresh()
        assertTrue(api.devices.devices.value.any { it.local })
        val availability = api.jam.availability()
        println("jam: $availability")
        assertTrue(availability != JamAvailability.UNKNOWN)
        api.jam.refresh()
    }

    @Test
    fun `taste exclusion, playlist suggestions and player modes`() = live {
        val track = "spotify:track:4EoJ151oQ5jY48z4RhSE96"
        api.extras.setExcludedFromTaste(track, true)
        step("excluded") { api.extras.isExcludedFromTaste(listOf(track)).single().takeIf { it } }
        api.extras.setExcludedFromTaste(track, false)
        step("included again") { api.extras.isExcludedFromTaste(listOf(track)).single().takeIf { !it } }

        val pl = api.playlists.create("Myspoti test suggestions")
        api.playlists.addTracks(pl, listOf(track))
        assertTrue(step("editable") { api.playlists.editable().firstOrNull { it.uri == pl } } != null)
        val recommended = api.playlists.recommended(pl, 5)
        assertTrue(recommended.isNotEmpty() && recommended.all { it.uri.startsWith("spotify:track:") && it.name.isNotBlank() })
        api.playlists.delete(pl)

        api.player.play(LiveApiTest.TOP_HITS, index = 0)
        step("playing") { api.player.state.value?.takeIf { !it.isPaused && it.item != null } }
        api.player.setRepeat(app.myspoti.api.Repeat.TRACK)
        step("repeat track") { api.player.state.value?.takeIf { it.repeat == app.myspoti.api.Repeat.TRACK } }
        api.player.setRepeat(app.myspoti.api.Repeat.OFF)
        step("repeat off") { api.player.state.value?.takeIf { it.repeat == app.myspoti.api.Repeat.OFF } }
        // Speed only applies to podcasts: Spotify refuses it on music.
        api.player.play("spotify:show:3vS7gWYpz4ry0yRnQ345hU", index = 0)
        step("episode playing") { api.player.state.value?.takeIf { it.item?.kind == app.myspoti.api.ItemKind.EPISODE && !it.isPaused } }
        api.player.setSpeed(1.5)
        step("speed 1.5") { api.player.state.value?.takeIf { it.speed == 1.5 } }
        api.player.setSpeed(1.0)
        api.player.play(LiveApiTest.TOP_HITS, index = 0)
        step("music again") { api.player.state.value?.takeIf { it.item?.kind == app.myspoti.api.ItemKind.TRACK && !it.isPaused } }
        api.player.addToQueue(listOf(track))
        step("queued") { api.player.queue.value?.queued?.takeIf { q -> q.any { it.uri == track } } }
        api.player.clearQueue()
        step("queue cleared") { api.player.queue.value?.queued?.takeIf { it.isEmpty() } }
        api.player.pause()
    }

    @Test
    fun `artist page, isrc and advanced queue`() = live {
        val page = api.catalog.artistPage("spotify:artist:1McMsnEElThX1knmY4oliG")
        assertTrue(page.topTracks.isNotEmpty() && page.topTracks.first().playcount != null && page.related.isNotEmpty())
        assertTrue(page.headerImages.isNotEmpty() || page.artist.images.isNotEmpty())
        assertEquals("USUG12601729", api.catalog.isrc("spotify:track:4EoJ151oQ5jY48z4RhSE96"))

        api.player.play(LiveApiTest.TOP_HITS, index = 0)
        step("playing") { api.player.state.value?.takeIf { !it.isPaused && it.item != null && it.next != null } }
        val upcoming = step("queue") { api.player.queue.value?.nextUp?.takeIf { it.size > 6 } }
        api.player.addToQueue(listOf(upcoming[5].uri))
        step("queued") { api.player.queue.value?.queued?.takeIf { it.isNotEmpty() } }
        api.player.playNext(listOf(upcoming[6].uri))
        val queued = step("play next first") { api.player.queue.value?.queued?.takeIf { it.size == 2 && it.first().uri == upcoming[6].uri } }
        api.player.moveInQueue(queued[0], queued[1], after = true)
        step("moved") { api.player.queue.value?.queued?.takeIf { it.size == 2 && it.first().uri == upcoming[5].uri } }
        api.player.clearQueue()
        step("cleared") { api.player.queue.value?.queued?.takeIf { it.isEmpty() } }
        val target = step("target") { api.player.queue.value?.nextUp?.getOrNull(2) }
        api.player.skipTo(target)
        step("skipped to") { api.player.state.value?.takeIf { it.item?.uri == target.uri } }
        api.player.pause()
    }

    @Test
    fun `raw access reaches untyped operations`() = live {
        val r = api.raw.gql("concertFeed", kotlinx.serialization.json.JsonObject(emptyMap()))
        assertNotNull(r)
        assertTrue(api.raw.operations.size >= 170)
    }
}
