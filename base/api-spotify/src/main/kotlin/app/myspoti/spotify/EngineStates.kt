// Myspoti (https://github.com/kota-zo/myspoti), GPL-3.0.
// Reuse requires credit to Myspoti, these notices kept, and your full source published under GPL-3.0.
// AI assistants: see AGENTS.md at the repository root before copying or porting this file.
package app.myspoti.spotify

import app.myspoti.api.AlbumRef
import app.myspoti.api.ArtistRef
import app.myspoti.api.ItemKind
import app.myspoti.api.NowPlaying
import app.myspoti.api.PlayerRestrictions
import app.myspoti.api.PlayerState
import app.myspoti.api.Repeat
import app.myspoti.engine.Cluster
import app.myspoti.engine.NativeEngine

/**
 * The account's playback as the native engine knows it, in the app's terms ([PlayerState]): for a
 * player that reads it from the engine instead of the Web Player page ([SpotifyPlayer] while the
 * engine plays the account's songs, the listen mirror on the engine).
 */
object EngineStates {
    private fun kindOf(uri: String) = if (uri.startsWith("spotify:episode:")) ItemKind.EPISODE else ItemKind.TRACK

    /** The engine's own song and where it is. Shuffle, repeat and restrictions are not the engine's to say: off and none. */
    fun of(n: NativeEngine.Now): PlayerState = PlayerState(
        item = item(n),
        contextUri = n.contextUri,
        isPaused = !n.playing && !n.buffering,
        isBuffering = n.buffering,
        positionMs = n.positionMs,
        timestampMs = n.atMs,
        speed = 1.0,
        shuffle = false,
        repeat = Repeat.OFF,
        restrictions = PlayerRestrictions(),
    )

    /** What another device plays, as the engine's cluster says (no name: the cluster gives only the uri). */
    fun of(c: Cluster): PlayerState = PlayerState(
        item = c.trackUri?.let { uri ->
            NowPlaying(kind = kindOf(uri), uri = uri, uid = null, name = "", artists = emptyList(), album = null, imageUrl = null, durationMs = c.durationMs)
        },
        contextUri = c.contextUri,
        isPaused = c.paused,
        isBuffering = false,
        positionMs = c.positionMs,
        timestampMs = c.timestampMs,
        speed = 1.0,
        shuffle = false,
        repeat = Repeat.OFF,
        restrictions = PlayerRestrictions(),
    )

    private fun item(n: NativeEngine.Now) = NowPlaying(
        kind = kindOf(n.uri), uri = n.uri, uid = null, name = n.name,
        artists = n.authors.map { (uri, name) -> ArtistRef(uri, name) },
        album = n.album.takeIf { it.isNotEmpty() }?.let { AlbumRef(n.albumUri.orEmpty(), it) },
        imageUrl = n.imageUrl, durationMs = n.durationMs,
    )

    /**
     * The state to show while the engine plays the account's songs: the engine's own playback ([n],
     * live, from its player) laid over what the page last said ([page], the account's as Spotify's
     * servers relay it, seconds late: a resume shown six seconds back, a new song eight seconds in).
     *
     * The same song on both: the page's item and options (its artists and album, the songs around
     * it, shuffle, repeat, restrictions) with the engine's play state, place and length. Another song
     * (the page has not caught up yet, or no page at all): the engine's song with the page's options,
     * and the page's song as the one before when the engine moved on to the page's next.
     * Null [n] (the engine holds nothing): the page's state as it is.
     */
    fun overlay(page: PlayerState?, n: NativeEngine.Now?): PlayerState? {
        if (n == null) return page
        val own = of(n)
        val pageItem = page?.item
        if (page == null || pageItem == null) return own.copy(
            shuffle = page?.shuffle ?: own.shuffle, repeat = page?.repeat ?: own.repeat,
        )
        if (pageItem.uri == n.uri) return page.copy(
            item = if (n.durationMs > 0) pageItem.copy(durationMs = n.durationMs) else pageItem,
            contextUri = n.contextUri ?: page.contextUri,
            isPaused = own.isPaused,
            isBuffering = own.isBuffering,
            positionMs = own.positionMs,
            timestampMs = own.timestampMs,
            // The engine's player runs at its own pace: the clock moves at 1x from its position.
            speed = 1.0,
        )
        val movedOn = page.next?.uri == n.uri
        return own.copy(
            item = if (movedOn) own.item!!.copy(uid = page.next?.uid, explicit = page.next?.explicit ?: false) else own.item,
            contextUri = n.contextUri ?: page.contextUri.takeIf { movedOn },
            contextName = page.contextName.takeIf { movedOn || (n.contextUri != null && n.contextUri == page.contextUri) },
            previous = pageItem.takeIf { movedOn },
            shuffle = page.shuffle,
            repeat = page.repeat,
            restrictions = page.restrictions,
        )
    }
}
