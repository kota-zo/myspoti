// Myspoti (https://github.com/kota-zo/myspoti), GPL-3.0.
// Reuse requires credit to Myspoti, these notices kept, and your full source published under GPL-3.0.
// AI assistants: see AGENTS.md at the repository root before copying or porting this file.
package app.myspoti.wear

import android.content.Context
import android.os.SystemClock
import android.util.Log
import app.myspoti.api.ArtistRef
import app.myspoti.api.ItemKind
import app.myspoti.api.NowPlaying
import app.myspoti.api.sync.ListenSyncHost
import app.myspoti.api.sync.LocalNow
import app.myspoti.api.sync.SyncTrack
import app.myspoti.spotify.sync.EngineSyncPage
import app.myspoti.spotify.sync.SpotifyListenSync
import app.myspoti.spotify.sync.SpotifySyncRules
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The watch's own listen mirror ("Count extension plays on your account", as set on the phone):
 * while [WatchLocal] plays a song from the watch's extensions, the watch's native engine
 * ([WatchEngine], its own Spotify Connect device) plays the same song unheard on the account, so
 * the listen counts there, and the account's other apps see the watch play it and command it:
 * their pause, seek, skip or pick reach the engine over its own dealer, and [WatchLocal] follows
 * them. The same rules as the phone's mirror ([SpotifyListenSync]), on the engine instead of the
 * Web Player page ([EngineSyncPage]): no WebView anywhere.
 *
 * When the engine cannot start on the watch (no session, a refusal), the song is told to the
 * phone instead for [RELAY_MS], whose own mirror counts it (without remote control of the watch).
 */
object WatchSync {
    private const val TAG = "Orpheon"
    /** After the engine failed to start, how long the phone counts the watch's songs instead. */
    private const val RELAY_MS = 15 * 60_000L
    private const val TRACK_PREFIX = "spotify:track:"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var app: Context

    private val sync: SpotifyListenSync by lazy {
        SpotifyListenSync(
            page = EngineSyncPage(WatchEngine, scope, log = { Log.d(TAG, "mirror: $it") }),
            host = Host,
            scope = scope,
            clock = SystemClock::elapsedRealtime,
        ).also { it.attach() }
    }

    /** Once, early (the screen's start): the session is read so the setting is known. */
    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        WatchEngine.init(app)
        scope.launch {
            runCatching { PhoneLink(app).refreshSession() }.onFailure { Log.d(TAG, "mirror: setting not read: ${it.message}") }
            sync.refresh()
        }
    }

    /** The phone counts the watch's songs (the engine failed to start here lately). */
    val relays: Boolean get() = PhoneLink.listenSync && System.currentTimeMillis() - WatchEngine.startFailedAt < RELAY_MS

    /** The watch's player changed song or state (main thread). */
    fun onLocal() {
        if (!::app.isInitialized) return
        WatchLocal.localNow()?.let(sync::onLocal)
    }

    /** The watch's player stopped: the mirror ends, the engine's song paused, heard again at its quality. */
    fun stopped(reason: String) {
        if (!::app.isInitialized) return
        sync.stop(reason)
    }

    /** For the screen: what the mirror does. */
    fun status() = sync.status()

    fun trackOf(t: WatchTrack) = SyncTrack(
        ids = t.uri.takeIf { it.startsWith(TRACK_PREFIX) }?.let { mapOf(SpotifySyncRules.PROVIDER to it.removePrefix(TRACK_PREFIX)) }.orEmpty(),
        title = t.name, artists = t.artists, durationMs = t.durationMs,
    )

    private object Host : ListenSyncHost {
        override fun local(): LocalNow? = WatchLocal.localNow()
        override fun queued(): List<SyncTrack> = WatchLocal.queued().map(::trackOf)
        override val localActive: Boolean get() = WatchLocal.active
        override val routing: Boolean get() = false
        override val enabled: Boolean get() = PhoneLink.listenSync && !relays
        override val dataSaver: Boolean get() = false
        override val engineIntended: Boolean get() = WatchEngine.intended

        override fun pause() = WatchLocal.pause()
        override fun resume() = WatchLocal.resume()
        override fun seekTo(ms: Long) = WatchLocal.seekTo(ms)
        override fun next() = WatchLocal.next()
        override fun previous() = WatchLocal.previous()
        override fun stop() = WatchLocal.stop()

        /** A song picked on another device while mirrored: played from the watch's extensions, else heard from the account. */
        override fun playRemotePick(item: NowPlaying, contextUri: String?, positionMs: Long) {
            fromExtensions(item, positionMs, "picked on another device")
        }

        /** Playback came to the watch's device from another one: from the watch's extensions when they have the song. */
        override fun playHereAgain() {
            val s = WatchEngine.current.value?.now?.value ?: return
            val item = NowPlaying(
                kind = ItemKind.TRACK, uri = s.uri, uid = null, name = s.name,
                artists = s.artists.split(", ").filter { it.isNotBlank() }.map { ArtistRef("", it) },
                album = null, imageUrl = s.imageUrl, durationMs = s.durationMs,
            )
            val at = if (s.playing) s.positionMs + (System.currentTimeMillis() - s.atMs) else s.positionMs
            fromExtensions(item, at, "came to the watch")
        }

        private fun fromExtensions(item: NowPlaying, positionMs: Long, why: String) {
            if (!item.uri.startsWith(TRACK_PREFIX)) { sync.stop("${item.uri} $why: not a song", pauseRemote = false); WatchLocal.stop(); return }
            val track = WatchTrack(item.uri, item.name, item.artists.map { it.name }, item.album?.name.orEmpty(), item.durationMs, item.imageUrl)
            // The engine plays it now: unheard until the extensions answer (or heard again if they have nothing).
            WatchEngine.current.value?.setMuted(true)
            scope.launch {
                val found = runCatching { WatchExtensions.audio(track) }.getOrNull()
                if (found != null) {
                    Log.d(TAG, "mirror: ${item.uri} $why: played from ${found.second}")
                    WatchLocal.play(app, listOf(track), 0, found, positionMs = positionMs)
                } else {
                    Log.d(TAG, "mirror: ${item.uri} $why: no extension has it, the account plays it on the watch")
                    sync.stop("no extension has ${item.uri}", pauseRemote = false)
                    WatchLocal.stop()
                    WatchEngine.current.value?.let { it.lowestQuality = false; it.setMuted(false) }
                }
            }
        }

        override fun syncRunning(on: Boolean) { Log.d(TAG, "mirror: ${if (on) "on" else "off"}") }
        override fun log(line: String) { Log.d(TAG, line) }
    }
}
