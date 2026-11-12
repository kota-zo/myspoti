/* SPDX-License-Identifier: GPL-3.0-only */
package app.myspoti.catalog

import app.myspoti.api.sync.SyncTrack
import app.myspoti.api.sync.TrackIdResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * A provider's id for a track that carries none, found through the catalog ([ProviderChain.counterpartOn]): by its
 * ISRC when it has one, else by the match gate on its title, artist and length. It answers at once from what it found
 * before ([TrackIdResolver] never waits): a track met for the first time starts a lookup on [scope] and gets null;
 * once that lookup finds an id, [onFound] is told (the sync then looks at the local player again). What the provider
 * answered it does not have is not asked again for [missMs]; a lookup that could not be answered (the provider resting,
 * slow or failing) only for [retryMs]: it was kept as a miss for half an hour, and the song's plays were not mirrored
 * meanwhile. Only a provider in the chain is asked: one the user turned off never is.
 */
class CatalogTrackIds(
    private val chain: () -> ProviderChain?,
    private val scope: CoroutineScope,
    private val onFound: () -> Unit,
    private val missMs: Long = 30 * 60_000L,
    private val retryMs: Long = 60_000L,
    private val size: Int = 512,
    private val now: () -> Long = System::currentTimeMillis,
) : TrackIdResolver {
    private class Known(val id: String?, val at: Long, val sure: Boolean = true)

    private val lock = Any()
    private val known = object : LinkedHashMap<String, Known>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Known>?) = size > this@CatalogTrackIds.size
    }
    private val asking = HashSet<String>()

    /** What tells the track apart: its ISRC, else its title, first artist and length to the second. */
    private fun key(t: SyncTrack, provider: String): String? {
        Match.isrc(t.isrc)?.let { return "$provider|isrc|$it" }
        val title = Match.title(t.title)
        val artist = Match.name(t.artists.firstOrNull().orEmpty())
        if (title.isEmpty() || artist.isEmpty()) return null
        return "$provider|$title|$artist|${t.durationMs / 1000}"
    }

    override fun idFor(track: SyncTrack, provider: String): String? {
        val k = key(track, provider) ?: return null
        synchronized(lock) {
            known[k]?.let { if (it.id != null) return it.id; if (now() - it.at < if (it.sure) missMs else retryMs) return null }
            if (!asking.add(k)) return null
        }
        scope.launch {
            val answer = try {
                chain()?.counterpartAnswer(provider, track.title, track.artists.firstOrNull().orEmpty(), track.durationMs, track.isrc)
                    ?: Counterpart.Unknown("no chain")
            } finally {
                synchronized(lock) { asking.remove(k) }
            }
            val id = (answer as? Counterpart.Found)?.hit?.track?.uri
                ?.let { runCatching { ItemUri.parse(it) }.getOrNull() }
                ?.takeIf { it.provider == provider && it.kind == "track" }?.id
            synchronized(lock) { known[k] = Known(id, now(), sure = answer !is Counterpart.Unknown) }
            if (id != null) onFound()
        }
        return null
    }
}
