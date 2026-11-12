package app.myspoti.spotify.sync

import app.myspoti.api.Device

/** The Spotify side of the listen sync's decisions: its ids, its uris, the list a play is sent in. */
object SpotifySyncRules {
    /** The provider key of Spotify's ids in [app.myspoti.api.sync.SyncTrack.ids]. */
    const val PROVIDER = "spotify"
    const val TRACK_PREFIX = "spotify:track:"

    private val TRACK_ID = Regex("[A-Za-z0-9]{22}")

    /** A Spotify track id (22 base-62 characters): only those are played on the page. */
    fun isTrackId(id: String?): Boolean = id != null && TRACK_ID.matches(id)

    /**
     * The list the page plays [trackUri] in: the local queue's Spotify list ([contextUri]), so
     * other devices see that queue after it, else the track alone. Never an artist: its context is
     * only the artist's top songs, a song of a list taken from an artist page (a mix, the radio,
     * other artists) is often not in it, and the page then plays the artist's first song instead.
     * Nor a list that already put the page on another song ([missed]).
     */
    fun playContext(contextUri: String, trackUri: String, missed: Set<String>): String =
        contextUri.takeIf { it.startsWith("spotify:") && !it.startsWith("spotify:artist:") && it !in missed } ?: trackUri

    /**
     * Spotify's own app on this very phone, when it is the device playing: active, not this app's
     * player, a phone, under the name this app's device shows (both take the name the owner gave the
     * phone, [phoneName] when given). Its notification, lock screen player or widget stays up while
     * it is closed and wakes it on the song mirrored here; a tap there makes it take playback (seen:
     * the play pressed in the phone's live notification went to it, and the extension's song
     * stopped). Never this app's previous page device, still listed after a reload: a web player is
     * a computer.
     */
    fun ownPhoneApp(devices: List<Device>, phoneName: String? = null): Device? {
        val name = phoneName?.takeIf { it.isNotBlank() } ?: devices.firstOrNull { it.local }?.name ?: return null
        return devices.firstOrNull { it.active && !it.local && it.type.equals("smartphone", ignoreCase = true) && it.name == name }
    }
}
