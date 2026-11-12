package app.myspoti.api.sync

/**
 * A track as the listen sync sees it: the ids the app already knows for it, keyed by provider
 * ([ListenSync.provider], e.g. `"spotify"` → its base-62 track id), and what a resolver that has
 * to look the track up can match on (its ISRC, title, artists and length). A file of the phone
 * usually carries no id at all.
 */
data class SyncTrack(
    val ids: Map<String, String> = emptyMap(),
    val isrc: String? = null,
    val title: String = "",
    val artists: List<String> = emptyList(),
    val durationMs: Long = 0,
)

/**
 * Finds a provider's id for a track, which is what the provider's player is asked to play and
 * what its queue holds. Called on the main thread at every state of the local player, so it
 * answers from what it knows now and never waits on the network: null means no id is known yet,
 * and the track then plays alone, not synced.
 *
 * [KnownIds] passes through the id the track already carries (a stream an extension found for a
 * Spotify track keeps that track's id). A resolver that matches through the catalog (by ISRC, or by
 * title, artists and length) is chained after it with [chain]: it answers from the matches it has
 * made, starts a lookup for a track it has not met yet, and once a lookup ends asks the sync to look
 * at the local player again ([ListenSync.refresh]) so the track is synced from then on.
 */
fun interface TrackIdResolver {
    /** [provider]'s id of [track], or null when none is known now. */
    fun idFor(track: SyncTrack, provider: String): String?

    companion object {
        /** The id the track already carries for the provider; a blank one is no id. */
        val KnownIds = TrackIdResolver { track, provider -> track.ids[provider]?.takeIf { it.isNotBlank() } }

        /** The first id [resolvers] give, asked in order. */
        fun chain(vararg resolvers: TrackIdResolver) = TrackIdResolver { track, provider ->
            resolvers.firstNotNullOfOrNull { it.idFor(track, provider) }
        }
    }
}
