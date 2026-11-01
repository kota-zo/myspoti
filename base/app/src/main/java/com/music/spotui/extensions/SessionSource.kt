package com.music.spotui.extensions

/**
 * The audio source the user put first from the player's quality sheet, for this session only: kept
 * in memory (gone when the app's process ends), never in the registry, so the order set in
 * Settings → Playback stays as it was. While it lasts, the audio sources are asked in the
 * [effective] order: that source, then the user's order without it. Spotify first: no extension
 * is asked. An extension turned off, removed or no longer a source leaves it ([valid]).
 */
object SessionSource {
    /** [saved] (the user's audio order, [SPOTIFY_SOURCE] among it) with [lead] first; [saved] as it is without one, or with one not in it. */
    fun effective(saved: List<String>, lead: String?): List<String> =
        if (lead == null || lead !in saved) saved else listOf(lead) + saved.filter { it != lead }

    /** [lead] while it can still lead: Spotify, or an extension of [saved] that is on; null otherwise. */
    fun valid(lead: String?, saved: List<String>, enabled: (String) -> Boolean): String? =
        lead?.takeIf { it in saved && (it == SPOTIFY_SOURCE || enabled(it)) }

    /**
     * The lead [id] picked in the player gives: [id], or null when it is the source the user's own
     * order asks first already (the session is then on the user's order, nothing is temporary).
     */
    fun pick(id: String, saved: List<String>, enabled: (String) -> Boolean): String? =
        valid(id, saved, enabled)?.takeIf { it != SectionSettings.leader(saved, enabled) }

    /**
     * Spotify was picked while the song an extension streams, [dueId], was paused: it stayed paused
     * there, and the user's next play plays it on Spotify from where it is, as long as the local
     * player is still on it ([currentId]) and Spotify is still the first source asked ([first], the
     * order asked now: "Back to my order" meanwhile keeps the song where it is).
     */
    fun spotifyDue(dueId: String?, currentId: String?, first: String?): Boolean =
        dueId != null && dueId.isNotBlank() && dueId == currentId && first == SPOTIFY_SOURCE

    /** What the quality sheet offers: Spotify and the extensions on, in the [effective] order. */
    fun choices(saved: List<String>, lead: String?, enabled: (String) -> Boolean): List<String> =
        effective(saved, valid(lead, saved, enabled)).filter { it == SPOTIFY_SOURCE || enabled(it) }
}
