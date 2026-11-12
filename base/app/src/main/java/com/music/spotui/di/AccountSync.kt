package com.music.spotui.di

import android.os.SystemClock
import app.myspoti.api.NowPlaying
import app.myspoti.api.PlayerState
import app.myspoti.api.account.AccountProviders
import app.myspoti.api.sync.ListenSyncStatus
import app.myspoti.api.sync.ListenSync
import app.myspoti.api.sync.ListenSyncHost
import app.myspoti.api.sync.LocalNow
import app.myspoti.api.sync.QueueTake
import app.myspoti.api.sync.SyncTrack
import app.myspoti.api.sync.TrackIdResolver
import app.myspoti.host.android.MyspotiRuntime
import app.myspoti.spotify.sync.EngineSyncPage
import app.myspoti.spotify.sync.SpotifyApiPage
import app.myspoti.spotify.sync.SpotifyListenSync
import app.myspoti.spotify.sync.SpotifySyncRules
import com.music.spotui.data.local.LocalTrack
import com.music.spotui.data.preferences.AccountProviderPref
import com.music.spotui.data.preferences.ConnectMirrorPref
import com.music.spotui.extensions.ExtensionAudio

/**
 * The app's side of the account provider's listen sync ([ListenSync], "Count extension plays on your
 * account"): it makes the sync of the provider the user chose ([AccountProviderPref]: Spotify's on the
 * native engine while it plays the account's songs, else on the hidden Web Player page; YouTube Music's
 * on the official web app's page; none) and gives it the local
 * player ([LocalPlayer], [SongPlayer]) through [ListenSyncHost]. The players call [listen] for every
 * event; this only translates the app's tracks and commands. One provider at a time: changing it ends
 * the running sync ([providerChanged]).
 */
object AccountSync {
    /** The providers' syncs made so far (each on first use; Spotify's reads its page only once the session runs). */
    private val made = LinkedHashMap<String, ListenSync>()
    private var attachedAll = false

    /** Spotify's mirror on the native engine ([NativeAudio], [EngineSyncPage]): no page in it. */
    private const val SPOTIFY_ENGINE = "spotify:engine"

    /**
     * The sync to use now: Spotify's on the native engine while the engine plays the account's songs
     * (its setting on, not left aside after a failure), else on the page; the provider's otherwise.
     */
    private fun key(): String = AccountProviderPref.id.let { id ->
        if (id != AccountProviders.YTMUSIC && id != AccountProviders.NONE && NativeAudio.on) SPOTIFY_ENGINE else id
    }
    private var lastKey: String? = null

    /** Whether the local player gives up the audio focus while the sync [key] runs ([on]): only for a page that plays. */
    internal fun mirrorYieldsFocus(on: Boolean, key: String?): Boolean = on && key != SPOTIFY_ENGINE

    private fun make(provider: String): ListenSync = when (provider) {
        AccountProviders.YTMUSIC -> YtmRuntime.account.listenSync(
            Host, MyspotiRuntime.scope, SystemClock::elapsedRealtime, TrackIdResolver.chain(TrackIdResolver.KnownIds, CatalogIds),
        )
        AccountProviders.NONE -> NoSync
        SPOTIFY_ENGINE -> SpotifyListenSync(
            page = EngineSyncPage(
                NativeAudio, MyspotiRuntime.scope,
                log = { app.myspoti.host.android.Logbook.log("mirror: engine: $it") },
                name = { app.myspoti.host.android.PhoneName.of(Graph.app) },
            ),
            host = Host,
            scope = MyspotiRuntime.scope,
            clock = SystemClock::elapsedRealtime,
            resolver = TrackIdResolver.chain(TrackIdResolver.KnownIds, CatalogIds),
        )
        else -> SpotifyListenSync(
            page = SpotifyApiPage(
                api = { MyspotiRuntime.spotify },
                licenceLimited = { MyspotiRuntime.host.licenseLimited() },
                licenceRefused = { MyspotiRuntime.host.licenceRefusedWithin(it) },
            ),
            host = Host,
            scope = MyspotiRuntime.scope,
            clock = SystemClock::elapsedRealtime,
            // The id the track carries, else the catalog's match (by ISRC, else title, artist and length), looked up once.
            resolver = TrackIdResolver.chain(TrackIdResolver.KnownIds, CatalogIds),
        )
    }

    /**
     * The chosen provider's listen sync (main thread). Spotify's moves between the engine and the
     * page when the engine is turned on or off or left aside: the other one's run ends first.
     */
    val listen: ListenSync
        get() {
            val key = key()
            val previous = lastKey
            lastKey = key
            if (previous != null && previous != key) made[previous]?.stop(
                if (key == SPOTIFY_ENGINE) "the mirror moves to the native engine" else "the mirror moves to the page (the native engine is off or left aside)",
            )
            val sync = made[key] ?: make(key).also { sync -> made[key] = sync; if (attachedAll) sync.attach() }
            // Moved in the middle of a song: this one looks at the local player now, not at its next event.
            if (previous != null && previous != key) sync.refresh()
            return sync
        }

    /** The provider whose ids the sync looks for now. */
    private val provider: String get() = AccountProviderPref.id

    /**
     * The sync holds Spotify's page (Spotify is the provider and its page's sync runs): the page's
     * states are the sync's then, and Spotify's page must not be paused or unmuted by the app meanwhile.
     */
    val holdsSpotifyPage: Boolean get() = provider == AccountProviders.SPOTIFY && made[AccountProviders.SPOTIFY]?.running == true

    /**
     * The sync holds the account's Spotify playback, on the page or on the native engine: what the
     * account plays is the mirror's then, never paused for the local player nor taken for a takeover.
     * On the engine, the page's states are the engine's (another device to the page) and are not
     * the sync's: it reads the engine's own.
     */
    val holdsSpotify: Boolean get() = holdsSpotifyPage ||
        (provider == AccountProviders.SPOTIFY && made[SPOTIFY_ENGINE]?.running == true)

    /** The user chose another provider: the others' syncs end (one at a time), the chosen one looks at the local player. */
    fun providerChanged() {
        val key = key()
        for ((id, sync) in made) if (id != key) sync.stop("the account provider changed")
        listen.refresh()
    }

    /** The songs an extension streams (or will), as the sync sees them: only those are looked up in the catalog. */
    private val live = LinkedHashSet<SyncTrack>()
    private const val LIVE_KEPT = 64

    private fun noteLive(t: SyncTrack) = synchronized(live) {
        live.remove(t)
        live.add(t)
        while (live.size > LIVE_KEPT) live.remove(live.first())
    }

    /**
     * A song without a Spotify id that an extension streams, looked up in the catalog while the sync is on (never a
     * download or a file of the phone, never with the data saver on); null until the lookup found it.
     */
    private object CatalogIds : TrackIdResolver {
        override fun idFor(track: SyncTrack, provider: String): String? {
            if (!ConnectMirrorPref.on || com.music.spotui.data.DataSaver.active) return null
            if (synchronized(live) { track !in live }) return null
            return com.music.spotui.data.catalog.CatalogRuntime.trackIds.idFor(track, provider)
        }
    }

    /** Once, at start (SongPlayer's init). */
    fun attach() {
        // Read at start: a new install stores its default (off) before an update could take it for an older install.
        ConnectMirrorPref.on
        attachedAll = true
        listen.attach()
    }

    /** [t] as the sync sees it: its Spotify id (an extension's stream keeps the track's, a download its own), title, length. */
    fun trackOf(t: LocalTrack): SyncTrack = SyncTrack(
        ids = LocalPlayer.spotifyIdOf(t).takeIf { it.isNotBlank() }?.let { mapOf(SpotifySyncRules.PROVIDER to it) }.orEmpty(),
        title = t.title, artists = listOf(t.artist).filter { it.isNotBlank() }, durationMs = t.durationMs,
    ).also { if (it.ids[provider].isNullOrBlank() && t.uri.scheme == ExtensionAudio.SCHEME) noteLive(it) }

    private fun idTrack(spotifyId: String): SyncTrack? =
        spotifyId.takeIf { it.isNotBlank() }?.let { SyncTrack(ids = mapOf(SpotifySyncRules.PROVIDER to it)) }

    /** The local player on [t], whose state is [playing] (play when ready), [positionMs], [durationMs]; the rest read now. */
    fun localNow(t: LocalTrack, playing: Boolean, positionMs: Long, durationMs: Long): LocalNow {
        val (previous, next) = LocalPlayer.neighbourIds()
        return LocalNow(
            track = trackOf(t), live = t.uri.scheme == ExtensionAudio.SCHEME,
            playing = playing, positionMs = positionMs, durationMs = durationMs,
            knownLengthMs = LocalPlayer.knownLength(), buffering = LocalPlayer.isBuffering(), audible = LocalPlayer.isPlaying(),
            rate = LocalPlayer.rate(), repeatsCurrent = LocalPlayer.repeatsCurrent(),
            previous = idTrack(previous), next = idTrack(next), contextUri = LocalPlayer.contextUri,
        )
    }

    /** The local player's song or state changed (SongPlayer.onLocalState, main thread). */
    fun onLocal(t: LocalTrack, playing: Boolean, positionMs: Long, durationMs: Long) {
        listen.onLocal(localNow(t, playing, positionMs, durationMs))
    }

    /** The local player's queued songs as published (main thread). */
    fun localQueueChanged() { listen.localQueueChanged(LocalPlayer.queuedTracks().map(::trackOf)) }

    /**
     * A local player starts on [list] at [at] (main thread): the provider's queued songs (the
     * account's, maybe queued on another device or by another install of the app) play right after
     * the picked song, as queued songs, then the list goes on (see [ListenSync.adoptRemoteQueue]);
     * those not in the list are asked to the extensions when they play, as any queued song. Returns
     * the list to play and the songs to mark queued; unchanged with the setting off or nothing queued.
     */
    fun withRemoteQueue(list: List<LocalTrack>, at: Int): Pair<List<LocalTrack>, List<LocalTrack>> {
        val next = list.drop(at + 1)
        val takes = listen.adoptRemoteQueue(next.map(::trackOf))
        val taken = takes.mapNotNull { take ->
            when (take) {
                is QueueTake.Local -> next[take.index]
                is QueueTake.Remote -> ExtensionAudio.queueTrack(SongPlayer.songOf(take.item))
            }
        }
        if (taken.isEmpty()) return list to emptyList()
        val matched = takes.count { it is QueueTake.Local }
        listen.remoteQueueTaken(taken.map(::trackOf))
        return (list.take(at + 1) + taken + next.drop(matched)) to taken
    }

    // --- The watch ---------------------------------------------------------------------------

    /** What the watch plays from its own extensions, as it last said, and when (elapsed realtime). */
    private class Watch(val now: LocalNow, val at: Long)
    @Volatile private var watch: Watch? = null
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private val watchGone = Runnable { if (watch != null) { watch = null; listen.stop("the watch said nothing past its song's end") } }

    /** The watch plays (from its extensions) and the phone's own local player does not: the sync follows the watch. */
    val watchActive: Boolean get() = watch != null && !LocalPlayer.active.value

    /** The watch's song now, its position carried on by the clock while it plays. */
    private fun watchNow(): LocalNow? = watch?.let { w ->
        if (!w.now.playing) w.now
        else w.now.copy(positionMs = (w.now.positionMs + (SystemClock.elapsedRealtime() - w.at)).coerceAtMost(w.now.durationMs.takeIf { it > 0 } ?: Long.MAX_VALUE))
    }

    /**
     * The watch told what it plays from its extensions ([body]: the song and its state, or stopped):
     * with "Count extension plays" on, this phone's account plays it unheard, as the phone's own
     * extension plays are (never while the phone's local player plays its own songs).
     */
    fun watchReport(body: String) {
        val o = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(body) as kotlinx.serialization.json.JsonObject }.getOrNull() ?: return
        fun text(k: String) = (o[k] as? kotlinx.serialization.json.JsonPrimitive)?.content
        main.post {
            main.removeCallbacks(watchGone)
            if (text("stopped") == "true" || text("uri").isNullOrBlank()) {
                if (watch != null) { watch = null; if (!LocalPlayer.active.value) listen.stop("the watch stopped") }
                return@post
            }
            val uri = text("uri")!!
            val duration = text("durationMs")?.toLongOrNull() ?: 0L
            val position = text("positionMs")?.toLongOrNull() ?: 0L
            val playing = text("playing") == "true"
            val artists = (o["artists"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }.orEmpty()
            val track = SyncTrack(
                ids = uri.takeIf { it.startsWith("spotify:track:") }?.let { mapOf(SpotifySyncRules.PROVIDER to it.substringAfterLast(':')) }.orEmpty(),
                title = text("name").orEmpty(), artists = artists, durationMs = duration,
            )
            watch = Watch(LocalNow(track = track, live = true, playing = playing, positionMs = position, durationMs = duration), SystemClock.elapsedRealtime())
            app.myspoti.host.android.Logbook.log("watch plays ${text("name")} (${if (playing) "playing" else "paused"} at ${position / 1000} s)")
            if (LocalPlayer.active.value) return@post
            listen.onLocal(watchNow()!!)
            // Nothing heard from the watch past the song's end (out of reach, switched off): the mirror ends.
            if (playing && duration > 0) main.postDelayed(watchGone, (duration - position).coerceAtLeast(0) + WATCH_GRACE_MS)
        }
    }
    private const val WATCH_GRACE_MS = 60_000L

    private object Host : ListenSyncHost {
        override fun local(): LocalNow? =
            LocalPlayer.current()?.let { localNow(it, LocalPlayer.playWhenReady(), LocalPlayer.position(), LocalPlayer.duration()) }
                ?: watchNow()

        override fun queued(): List<SyncTrack> = LocalPlayer.queuedTracks().map(::trackOf)

        override val localActive: Boolean get() = LocalPlayer.active.value || watch != null
        override val routing: Boolean get() = SongPlayer.routing
        /** The setting, for the chosen provider while its account is signed in (none: never). */
        override val enabled: Boolean get() = ConnectMirrorPref.on && when (provider) {
            AccountProviders.YTMUSIC -> YtmRuntime.signedIn
            AccountProviders.NONE -> false
            else -> true
        }
        override val dataSaver: Boolean get() = com.music.spotui.data.DataSaver.active
        override val engineIntended: Boolean get() = SongPlayer.engineIntended()

        override fun pause() { LocalPlayer.pause() }
        override fun resume() { LocalPlayer.resume() }
        override fun seekTo(ms: Long) { LocalPlayer.seekTo(ms) }
        override fun next() { LocalPlayer.next() }
        override fun previous() { LocalPlayer.previousItem() }
        override fun stop() { LocalPlayer.stop() }

        override fun playRemotePick(item: NowPlaying, contextUri: String?, positionMs: Long) {
            SongPlayer.followRemotePlay(item, contextUri, positionMs)
        }

        override fun playHereAgain() { SongPlayer.backThroughExtensions() }

        override fun syncRunning(on: Boolean) {
            // An extension's own mute of Spotify's page (playback.mirror) gives way: two would fight over the page.
            if (on && provider == AccountProviders.SPOTIFY) com.music.spotui.extensions.Extensions.mirrorReset("Myspoti plays the song on Spotify itself")
            // The mirror is the page's (the engine off or left aside): a running engine leaves the
            // account's devices first, or the page's play landed on it (Connect's active device),
            // heard, and it took the audio focus from the extension's player (the song stopped and
            // started again in a loop). On the engine, it plays muted and never asks for the focus.
            if (on && provider == AccountProviders.SPOTIFY && lastKey != SPOTIFY_ENGINE) NativeAudio.leaveForMirror()
            // The page asks Android for the audio focus as it plays: the local player must not yield it
            // to the page. The engine's mirror is muted and asks for no focus: the local player keeps
            // its own then, and pauses for a call or another app as without a mirror (it held none
            // during every engine mirror: another app's sound played over the song).
            LocalPlayer.setMirrorFocus(mirrorYieldsFocus(on, lastKey))
        }

        override fun log(line: String) { app.myspoti.host.android.Logbook.log(line) }
    }
}

/** No account provider: nothing is synced, nothing is read. */
private object NoSync : ListenSync {
    override val provider: String = AccountProviders.NONE
    override val running = false
    override val waitsBack = false
    override fun attach() {}
    override fun onLocal(now: LocalNow) {}
    override fun onLocalRepeat() {}
    override fun onProviderState(s: PlayerState) {}
    override fun onProviderPlayback(s: PlayerState, moved: Boolean, intended: Boolean) {}
    override fun stop(reason: String, pauseRemote: Boolean) = false
    override suspend fun awaitEnded() {}
    override fun leaving(device: String) {}
    override fun refresh() {}
    override fun status() = ListenSyncStatus(provider, false, false, null, null, false, null, false, null, remoteControl = false, note = "no account provider chosen")
    override fun localQueueChanged(queued: List<SyncTrack>) {}
    override fun adoptRemoteQueue(localNext: List<SyncTrack>): List<QueueTake> = emptyList()
    override fun remoteQueueTaken(taken: List<SyncTrack>) {}
}
