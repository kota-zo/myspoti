/* SPDX-License-Identifier: GPL-3.0-only */
package app.myspoti.catalog

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assume.assumeTrue
import org.junit.Test

/** The scripts against the real services, on demand only: CATALOG_LIVE=1. Each step on its own, so one failure hides nothing. */
class LiveScripts {
    private suspend fun step(tag: String, run: suspend () -> Any?) {
        val started = System.currentTimeMillis()
        val out = runCatching { run() }.fold({ "$it" }, { "FAILED ${it::class.simpleName}: ${it.message}" })
        println("LIVE $tag (${System.currentTimeMillis() - started} ms): ${out.take(300)}")
    }

    @Test fun theScriptsAnswer() = runBlocking {
        assumeTrue(System.getenv("CATALOG_LIVE") == "1")
        val query = System.getenv("CATALOG_QUERY") ?: "test"
        val isrc = System.getenv("CATALOG_ISRC") ?: "USUM71703861"
        val http = OkScripts()
        for (spec in listOf(ScriptSpec.DEEZER, ScriptSpec.TIDAL, ScriptSpec.YOUTUBE_MUSIC)) {
            val p = ScriptProvider(spec, http, "NewMyspoti/0.1 (https://github.com/kota-zo/myspoti)")
            val n = spec.id
            var hit: Hit? = null
            step("$n tracks") {
                val items = p.searchTracks(query, 0, 5).items
                hit = items.firstOrNull()
                items.map { "${it.track.uri} ${it.track.name} / ${it.track.artists.firstOrNull()?.name} / ${it.track.album?.name} ${it.track.durationMs} isrc=${it.isrc}" }
            }
            step("$n albums") { p.searchAlbums(query, 0, 3).items.map { "${it.uri} ${it.name} ${it.releaseDate} ${it.trackCount}" } }
            step("$n artists") { p.searchArtists(query, 0, 3).items.map { "${it.uri} ${it.name} img=${it.images.size}" } }
            step("$n isrc") { p.byIsrc(isrc).map { "${it.track.uri} ${it.track.name} ${it.isrc}" } }
            val h = hit ?: continue
            step("$n track") { p.track(ItemUri.parse(h.track.uri)!!.id).let { "${it.track.name} isrc=${it.isrc} album=${it.track.album?.uri}" } }
            h.track.album?.uri?.let(ItemUri::parse)?.let { a ->
                step("$n album") { p.album(a.id).let { "${it.name} ${it.type} ${it.trackCount} ${it.releaseDate} ${it.label}" } }
                step("$n album tracks") { p.albumTracks(a.id, 0, 3).items.map { "${it.track.name} isrc=${it.isrc}" } }
            }
            h.track.artists.firstOrNull()?.uri?.let(ItemUri::parse)?.let { a ->
                step("$n artist") { p.artist(a.id).let { "${it.name} img=${it.images.size}" } }
                step("$n top") { p.topTracks(a.id).take(3).map { it.track.name } }
                step("$n discography") { p.discography(a.id, 0, 3).let { d -> "${d.total}: ${d.items.map { it.name }}" } }
                step("$n related") { p.related(a.id).take(3).map { it.name } }
            }
        }
    }

    /** The same real songs through each provider and through the whole chain, for how often they are found. */
    @Test fun theChainFindsSongs() = runBlocking {
        assumeTrue(System.getenv("CATALOG_LIVE") == "1")
        val probe = (System.getenv("CATALOG_PROBE") ?: "").lines().filter { it.isNotBlank() }.map { l ->
            val (t, a, d) = l.split('\t')
            Triple(t, a, d.toLong())
        }
        val ua = "NewMyspoti/0.1 (https://github.com/kota-zo/myspoti)"
        val http = OkScripts()
        for (spec in listOf(ScriptSpec.DEEZER, ScriptSpec.TIDAL, ScriptSpec.YOUTUBE_MUSIC)) {
            val c = ProviderChain(listOf(ScriptProvider(spec, http, ua)), timeoutMs = 25_000)
            val missed = probe.filter { (t, a, d) -> runCatching { c.resolve(t, a, d) }.getOrNull() == null }.map { it.first }
            println("LIVE found by ${spec.id}: ${probe.size - missed.size}/${probe.size} missed=$missed")
        }
        val chain = providerChain(OkHttpClient(), ua, "CH", order = listOf("deezer", "ytmusic", "tidal"))
        for ((t, a, d) in probe.take(4)) {
            val started = System.currentTimeMillis()
            val h = chain.resolve(t, a, d) ?: continue
            val c = chain.counterparts(h)
            println("LIVE counterparts ${h.track.name} (${System.currentTimeMillis() - started} ms): ${c.mapValues { (_, v) -> v.track.uri + " " + v.track.durationMs }}")
        }
    }

    private fun OkScripts() = OkHttpScripts(OkHttpClient())
}
