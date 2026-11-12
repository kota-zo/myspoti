/* SPDX-License-Identifier: GPL-3.0-only */
package app.myspoti.catalog

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assume.assumeTrue
import org.junit.Test

/** The real services, on demand only: CATALOG_LIVE=1. Prints what came back, never any lyrics. */
class LiveCheck {
    @Test fun theRealServicesAnswer() = runBlocking {
        assumeTrue(System.getenv("CATALOG_LIVE") == "1")
        val ua = "NewMyspoti/0.1 (https://github.com/kota-zo/myspoti)"
        val http = OkHttpClient()
        val mb = MusicBrainz(ReliableFetch(HttpFetch(http, ua, 1_100), "musicbrainz"), ReliableFetch(HttpFetch(http, ua, 500), "listenbrainz"))
        val dz = Deezer(ReliableFetch(HttpFetch(http, ua, 120), "deezer"))
        // About 20 calls a minute.
        val it = ITunes(ReliableFetch(HttpFetch(http, ua, 3_100), "itunes"), "CH")
        val chain = ProviderChain(listOf(dz, it, mb), timeoutMs = 20_000)
        fun show(tag: String, s: Any?) = println("LIVE $tag: $s")

        val byIsrc = chain.resolve("x", "y", 0, System.getenv("CATALOG_ISRC") ?: "USUM71703861")
        show("isrc", byIsrc?.track?.let { "${it.uri} ${it.name} / ${it.artists.firstOrNull()?.name} / ${it.album?.name}" })
        val byText = chain.resolve(System.getenv("CATALOG_TITLE") ?: "Test Song", System.getenv("CATALOG_ARTIST") ?: "Test Artist", 320_000)
        show("text", byText?.track?.let { "${it.uri} ${it.name} ${it.durationMs}" })
        show("refuses live", chain.resolve("Test Song (Live)", System.getenv("CATALOG_ARTIST") ?: "Test Artist", 320_000)?.track?.name)
        val mbOnly = ProviderChain(listOf(mb), timeoutMs = 20_000)
        val rec = mbOnly.resolve(System.getenv("CATALOG_TITLE") ?: "Test Song", System.getenv("CATALOG_ARTIST") ?: "Test Artist", 320_000)
        show("mb text", rec?.track?.let { "${it.uri} ${it.name} ${it.durationMs} / ${it.album?.name}" })
        show("merged search", chain.tracks(System.getenv("CATALOG_QUERY") ?: "test", 0, 5).items.map { "${it.uri.substringBefore(':')}: ${it.name} - ${it.artists.firstOrNull()?.name}" })
        val albums = chain.albums(System.getenv("CATALOG_QUERY") ?: "test", 0, 3).items
        show("albums", albums.map { "${it.uri} ${it.name} ${it.releaseDate} ${it.trackCount}" })
        val g = System.getenv("CATALOG_GROUP") ?: return@runBlocking
        show("mb album", mb.album(g).let { "${it.name} ${it.type} ${it.trackCount} ${it.releaseDate}" })
        show("mb album tracks", mb.albumTracks(g, 0, 3).items.map { "${it.track.name} ${it.isrc}" })
        val artistUri = "musicbrainz:artist:" + (System.getenv("CATALOG_ARTIST_ID") ?: return@runBlocking)
        val page = chain.artistPage(artistUri)
        show("artist page", "${page.artist.name} images=${page.artist.images.size} top=${page.topTracks.size} albums=${page.popularReleases.size} related=${page.related.size}")
        byIsrc?.let { h -> show("counterparts", chain.counterparts(h).mapValues { (_, v) -> "${v.track.uri} ${v.track.name} ${v.track.durationMs}" }) }
        val itOnly = ProviderChain(listOf(it), timeoutMs = 20_000)
        val fromIt = itOnly.resolve(System.getenv("CATALOG_TITLE") ?: "Test Song", System.getenv("CATALOG_ARTIST") ?: "Test Artist", 320_000)
        show("itunes text", fromIt?.track?.let { t -> "${t.uri} ${t.name} ${t.durationMs} / ${t.album?.name} art=${t.album?.images?.size}" })
        fromIt?.track?.album?.uri?.let { u -> show("itunes album", itOnly.albumTracks(u, 0, 3).items.map { t -> t.name }) }
        // The same real songs through each provider, for how often each one finds them.
        val probe = (System.getenv("CATALOG_PROBE") ?: "").lines().filter { it.isNotBlank() }.map { l ->
            val (t, a, d) = l.split('\t')
            Triple(t, a, d.toLong())
        }
        for ((name, p) in listOf("deezer" to dz, "itunes" to it, "musicbrainz" to mb)) {
            val c = ProviderChain(listOf(p), timeoutMs = 25_000)
            val missed = probe.filter { (t, a, d) -> runCatching { c.resolve(t, a, d) }.getOrNull() == null }.map { it.first }
            show("found by $name", "${probe.size - missed.size}/${probe.size} missed=$missed")
        }
        val lyrics = LrcLib(ReliableFetch(HttpFetch(http, ua, 300), "lrclib")).lyrics(System.getenv("CATALOG_TITLE") ?: "Test Song", System.getenv("CATALOG_ARTIST") ?: "Test Artist", null, 320_000)
        show("lyrics", "synced=${lyrics?.synced != null} plain=${lyrics?.plain != null}")
    }
}
