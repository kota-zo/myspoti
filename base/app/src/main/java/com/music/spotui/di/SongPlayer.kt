package com.music.spotui.di

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import app.myspoti.api.ItemKind
import app.myspoti.api.MyspotiApi
import app.myspoti.api.PlayerState
import app.myspoti.api.Repeat
import app.myspoti.host.android.MyspotiRuntime
import app.myspoti.host.android.NowPlayingInfo
import app.myspoti.host.android.PlaybackService
import com.music.spotui.data.entity.SongsModel
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * The UI's player facade over [MyspotiApi.player].
 *
 * Screens call playSong / play / pause / seekTo / getCurrentPosition… as before; every
 * command goes to the Web Player through the API, and the state comes back from its
 * flows. Spotify owns the queue, so next/previous/shuffle/repeat are Spotify's.
 * Local Files play in [LocalPlayer]; the controls route there while it is active.
 */
object SongPlayer {
    private const val SPOTIFY_TRACK_PREFIX = "spotify:track:"
    private const val PENDING_MS = 6000L
    private const val TAG = "MspPlayer"
    private const val LIKED_CONTEXT = "spotify:collection:tracks"

    private val api: MyspotiApi get() = MyspotiRuntime.api
    private val scope get() = MyspotiRuntime.scope

    // ── Player state ──
    @Volatile private var playing = false
    private val bufferingState = mutableStateOf(false)
    /** Observable startup state: visual lookups wait for the audio transport. */
    val isBuffering: Boolean get() = bufferingState.value
    @Volatile private var durationMs = 0L
    @Volatile private var positionMs = 0L
    @Volatile private var positionAt = 0L
    @Volatile var currentTrackId: String = ""
        private set
    @Volatile var currentContextUri: String = ""
        private set
    @Volatile private var stateSeen = false

    // A track the user just picked: the player reports the previous one for a moment,
    // which must not overwrite the optimistic now-playing state.
    @Volatile private var pendingTrackId: String? = null
    @Volatile private var pendingItemUri: String? = null
    @Volatile private var pendingUntil = 0L

    // After play / pause the player reports the old state for a moment; keep the requested one.
    @Volatile private var wantPlayingUntil = 0L
    @Volatile private var wantPausedUntil = 0L

    private fun intendPlaying() { wantPlayingUntil = SystemClock.elapsedRealtime() + 4000; wantPausedUntil = 0 }
    private fun intendPaused() { wantPausedUntil = SystemClock.elapsedRealtime() + 1500; wantPlayingUntil = 0 }

    private fun resolvePlaying(paused: Boolean): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now < wantPlayingUntil) {
            if (!paused) wantPlayingUntil = 0
            return true
        }
        if (now < wantPausedUntil) {
            if (paused) wantPausedUntil = 0
            return false
        }
        return !paused
    }

    // Context (album/playlist/artist/liked) a track was listed in, so playing it keeps
    // Spotify's own queue and autoplay for that context.
    private val contextRegistry = ConcurrentHashMap<String, String>()

    @Volatile var currentSource: String = "Spotify"
    @Volatile var currentQuality: String = ""

    /** Spotify's real queue. */
    data class QueueItem(val song: SongsModel, val uri: String, val uid: String, val provider: String)
    data class EngineQueue(
        val current: SongsModel? = null,
        val queued: List<QueueItem> = emptyList(),
        val nextUp: List<QueueItem> = emptyList(),
    )
    private val _queue = mutableStateOf(EngineQueue())
    private var apiQueue: app.myspoti.api.Queue? = null

    /** What the now-playing screen needs beyond the track itself. */
    data class NowPlaying(
        val contextUri: String = "",
        val contextName: String = "",
        val provider: String = "",
        val uri: String = "",
        val explicit: Boolean = false,
        val albumUri: String = "",
        val prev: SongsModel? = null,
        val next: SongsModel? = null,
    )
    private val _nowPlaying = mutableStateOf(NowPlaying())
    val nowPlaying: State<NowPlaying> get() = _nowPlaying
    val engineQueue: State<EngineQueue> get() = _queue

    private val main = Handler(Looper.getMainLooper())
    private var boundState: CurrentSongState? = null

    /** Wires the API's flows into the UI's now-playing state. Call once, on the main thread. */
    fun attachEngine(state: CurrentSongState) {
        boundState = state
        com.music.spotui.data.api.LyricsApi.extraSources =
            com.music.spotui.data.preferences.isLyricsExtraSources(Graph.app)
        restoreLastTrack(state)
        scope.launch { api.player.state.collect { s -> if (acceptsSessionUpdates()) applyState(s) } }
        scope.launch { api.player.queue.collect { q -> if (acceptsSessionUpdates()) applyQueue(q) } }
        scope.launch { api.library.changes.collect { refreshCurrentLiked() } }
        PlaybackService.controls = Controls
    }

    /** System controls (notification, headset, car) land here. */
    private object Controls : app.myspoti.host.android.PlaybackControls {
        override fun play() = SongPlayer.play()
        override fun pause() = SongPlayer.pause()
        override fun next() = SongPlayer.next()
        override fun previous() = SongPlayer.previous()
        override fun seekTo(ms: Long) = SongPlayer.seekTo(ms)
        override fun setLiked(liked: Boolean) = SongPlayer.setLiked(liked)
        override fun playFromMediaId(mediaId: String) = AutoBrowser.play(mediaId)
        override fun playFromSearch(query: String) { scope.launch { AutoBrowser.playFromSearch(query) } }
        override fun setRemoteVolume(volume: Int) = SongPlayer.setRemoteVolume(volume)
        override fun setShuffle(on: Boolean) = SongPlayer.setShuffle(on)
    }

    // ── Last track: shown at launch (mini player) before the Web Player reports ──

    private fun lastTrackPrefs() = Graph.app.getSharedPreferences("myspoti_last_track", Context.MODE_PRIVATE)
    private var savedLast = ""
    @Volatile private var resumeWhenReady = false

    private fun restoreLastTrack(state: CurrentSongState) {
        if (state.title.value.isNotBlank() || !acceptsSessionUpdates()) return
        val p = lastTrackPrefs()
        val uri = p.getString("uri", "").orEmpty()
        val title = p.getString("title", "").orEmpty()
        if (uri.isBlank() || title.isBlank()) return
        val id = trackIdOf(uri)
        currentTrackId = id
        durationMs = p.getLong("duration", 0L)
        positionMs = p.getLong("position", 0L)
        positionAt = SystemClock.elapsedRealtime()
        playing = false
        _nowPlaying.value = NowPlaying(uri = uri)
        state.updateSongState(
            coverUri = p.getString("cover", "").orEmpty(),
            title = title,
            singer = p.getString("singer", "").orEmpty(),
            playingState = false,
            songId = songIdFor(id, uri),
            songIndex = 0,
            album = p.getString("album", "").orEmpty(),
        )
        state.likeState.value = p.getBoolean("liked", false)
    }

    /** Saved on track change and on play/pause (not on every state). */
    private fun saveLastTrack(song: SongsModel, uri: String, liked: Boolean) {
        val key = "$uri|$playing|$liked"
        if (uri.isBlank() || key == savedLast) return
        savedLast = key
        lastTrackPrefs().edit()
            .putString("uri", uri).putString("title", song.title).putString("singer", song.singer)
            .putString("album", song.album).putString("cover", song.coverUri)
            .putLong("duration", durationMs).putLong("position", getCurrentPosition())
            .putBoolean("liked", liked)
            .apply()
    }

    private fun acceptsSessionUpdates() = Graph.app.getSharedPreferences("myspoti_prefs", Context.MODE_PRIVATE)
        .getBoolean("LoggedIn", false)

    /** Call on the main thread before navigating to logout. */
    fun clearSession() {
        main.removeCallbacksAndMessages(null)
        pendingTrackId = null
        pendingItemUri = null
        pendingUntil = 0L
        intentUri = ""; intentUntil = 0L
        wantPlayingUntil = 0L; wantPausedUntil = 0L
        stateSeen = false
        playing = false; bufferingState.value = false; durationMs = 0L; positionMs = 0L
        currentTrackId = ""; currentContextUri = ""
        contextRegistry.clear(); library.clear()
        synchronized(wantedSaved) { wantedSaved.clear() }
        _queue.value = EngineQueue(); apiQueue = null; _nowPlaying.value = NowPlaying()
        lastTrackPrefs().edit().clear().apply(); savedLast = ""; resumeWhenReady = false
        _activeDevice.value = null; _sleepEndsAt.value = null; sleepTrackId = null
        _video.value = VideoState(); videoFullscreen.value = false
        boundState?.let { state ->
            state.updateQueue(emptyList())
            state.updateSongState("", "", "", false, 0, 0, "")
            state.likeState.value = false
        }
    }

    private fun trackIdOf(uri: String) = uri.removePrefix(SPOTIFY_TRACK_PREFIX).takeIf { uri.startsWith(SPOTIFY_TRACK_PREFIX) }.orEmpty()

    private fun songIdFor(trackId: String, fallback: String) =
        (if (trackId.isNotBlank()) "track:$trackId" else "uri:$fallback").hashCode() and 0x7fffffff

    private fun songOf(n: app.myspoti.api.NowPlaying): SongsModel {
        val id = trackIdOf(n.uri)
        val artists = n.artists.joinToString(", ") { it.name }
        return SongsModel(
            id = songIdFor(id, n.uri),
            title = n.name,
            album = n.album?.name.orEmpty(),
            singer = artists,
            coverUri = n.imageUrl.orEmpty(),
            url = if (n.kind == ItemKind.EPISODE) "episode:" + n.uri.substringAfterLast(':') else buildSpotifyPlayQuery(id, n.name, artists),
            spotifyTrackId = id,
            explicit = n.explicit,
            durationMs = n.durationMs.toInt(),
            albumId = n.album?.uri?.substringAfterLast(':').orEmpty(),
        )
    }

    /** Notification, lock screen, Bluetooth and Android Auto. */
    private fun pushNotification(song: SongsModel?, liked: Boolean) {
        val st = boundState
        PlaybackService.update(Graph.app, NowPlayingInfo(
            id = _nowPlaying.value.uri,
            title = song?.title ?: st?.title?.value.orEmpty(),
            artist = song?.singer ?: st?.singer?.value.orEmpty(),
            album = song?.album ?: st?.album?.value.orEmpty(),
            coverUrl = song?.coverUri ?: st?.coverUri?.value,
            durationMs = getDuration(),
            positionMs = getCurrentPosition(),
            playing = isPlaying(),
            buffering = isBuffering,
            liked = liked,
            shuffle = st?.shuffle?.value == true,
        ))
        com.music.spotui.widget.NowPlayingWidget.show(Graph.app,
            title = song?.title ?: st?.title?.value.orEmpty(),
            artist = song?.singer ?: st?.singer?.value.orEmpty(),
            coverUrl = song?.coverUri ?: st?.coverUri?.value,
            playing = isPlaying())
    }

    private fun applyState(s: PlayerState?) {
        if (s == null) return
        stateSeen = true
        if (LocalPlayer.active.value) return
        if (resumeWhenReady) {
            resumeWhenReady = false
            // The Web Player restored the last session paused: play what was tapped.
            if (s.isPaused) command { api.player.resume() }
        }
        val item = s.item
        val uri = item?.uri.orEmpty()
        val id = trackIdOf(uri)
        val pending = pendingTrackId
        if (pending != null) {
            // Rapid A -> B -> C picks can report B while C is pending: keep C until it shows up.
            if (id == pending || pendingItemUri == uri || SystemClock.elapsedRealtime() > pendingUntil) {
                pendingTrackId = null
                pendingItemUri = null
            } else return
        }
        val wasPlaying = playing
        val wasBuffering = isBuffering
        val predicted = getCurrentPosition()
        bufferingState.value = s.isBuffering
        playing = resolvePlaying(s.isPaused)
        SpeakerKeepAlive.set(playing && _activeDevice.value == null)
        durationMs = item?.durationMs ?: 0L
        val reported = s.positionAt(System.currentTimeMillis())
        // Keep the running clock when the player only nudges it (< 1 s).
        if (!(wasPlaying && playing && !wasBuffering && !isBuffering && kotlin.math.abs(reported - predicted) < 1000)) {
            positionMs = reported
            positionAt = SystemClock.elapsedRealtime()
        }
        val trackChanged = uri != _nowPlaying.value.uri
        currentContextUri = s.contextUri.orEmpty()
        if (item != null) { checkEndOfTrackTimer(id); currentTrackId = id }
        TrashBin.onItem(item)
        _nowPlaying.value = NowPlaying(
            contextUri = s.contextUri.orEmpty(),
            contextName = s.contextName.orEmpty(),
            provider = "",
            uri = uri,
            explicit = item?.explicit == true,
            albumUri = item?.album?.uri.orEmpty(),
            prev = s.previous?.let(::songOf),
            next = s.next?.let(::songOf),
        )
        val state = boundState ?: return
        state.shuffle.value = s.shuffle
        _repeatMode.value = s.repeat.ordinal
        state.repeat.value = s.repeat != Repeat.OFF
        if (item == null) {
            state.updatePlayingState(playing)
            pushNotification(null, state.likeState.value)
            return
        }
        val liked = likedFor(uri, library[uri] ?: false)
        state.likeState.value = liked
        val song = songOf(item)
        state.updateSongState(
            coverUri = song.coverUri,
            title = song.title,
            singer = song.singer,
            playingState = playing,
            songId = song.id,
            songIndex = state.queue.value.indexOfFirst { it.id == song.id }.coerceAtLeast(0),
            album = song.album,
        )
        pushNotification(song, liked)
        saveLastTrack(song, uri, liked)
        trackListen(uri, song, com.music.spotui.data.local.ListenHistory.Source.SPOTIFY, playing)
        if (trackChanged) {
            refreshCurrentLiked()
            refreshDevice()
        }
        val mv = com.music.spotui.video.MusicVideoController
        mv.onAudioBuffering(isBuffering, song.spotifyTrackId)
        if (!isBuffering && playing && mv.inForeground && (app.myspoti.power.EnergySettings.state.value.policy.allowBackgroundLookups || mv.wantVideo) && song.spotifyTrackId.isNotBlank())
            com.music.spotui.video.MusicVideoController.prepare(song.spotifyTrackId, song.title, song.singer, durationMs)
        if (!isBuffering && playing && mv.inForeground && app.myspoti.power.EnergySettings.state.value.policy.allowBackgroundLookups) {
            com.music.spotui.data.api.LyricsApi.prefetch(lyricsTrack(song))
            _nowPlaying.value.next?.let { com.music.spotui.data.api.LyricsApi.prefetch(lyricsTrack(it)) }
        }
    }

    private fun refreshCurrentLiked() {
        val uri = _nowPlaying.value.uri.takeIf { it.startsWith(SPOTIFY_TRACK_PREFIX) || it.startsWith("spotify:episode:") } ?: return
        scope.launch {
            val saved = runCatching { api.library.contains(listOf(uri)).single() }.getOrNull() ?: return@launch
            onLibraryItem(uri, saved)
        }
    }

    private fun applyQueue(q: app.myspoti.api.Queue?) {
        apiQueue = q
        if (q == null) return
        fun items(list: List<app.myspoti.api.NowPlaying>) = list
            .filter { it.uri.startsWith("spotify:track:") || it.uri.startsWith("spotify:episode:") }
            .map { QueueItem(songOf(it), it.uri, it.uid.orEmpty(), "") }
        _queue.value = EngineQueue(
            current = q.current?.let(::songOf),
            queued = items(q.queued),
            nextUp = items(q.nextUp),
        )
    }

    // ── Listen history (in-app recap) ──
    private var listenKey = ""
    private var listenSong: SongsModel? = null
    private var listenSource = com.music.spotui.data.local.ListenHistory.Source.SPOTIFY
    private var listenPlayedMs = 0L
    private var listenTickAt = 0L
    private var listenWasPlaying = false

    /** Adds the time played since the last call; a new [key] closes the previous listen. */
    private fun trackListen(key: String, song: SongsModel?, source: com.music.spotui.data.local.ListenHistory.Source, isPlaying: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (listenWasPlaying && listenTickAt > 0) listenPlayedMs += now - listenTickAt
        if (key != listenKey) {
            listenSong?.let { s ->
                com.music.spotui.data.local.ListenHistory.record(com.music.spotui.data.local.ListenHistory.Listen(
                    System.currentTimeMillis(), listenKey, s.title, s.singer, s.album, s.coverUri, listenPlayedMs, listenSource,
                ))
            }
            listenKey = key; listenSong = song; listenSource = source; listenPlayedMs = 0
        }
        listenTickAt = now
        listenWasPlaying = isPlaying
    }

    // ── Commands ──

    /** Runs an API call off the caller, logging failures instead of crashing the UI. */
    private fun command(block: suspend () -> Unit) {
        scope.launch {
            runCatching { block() }.onFailure { Log.w(TAG, "command failed: ${it.message}") }
        }
    }

    /** Remember the Spotify context the given play keys belong to. */
    fun registerContext(urls: List<String>, contextUri: String) {
        urls.forEach { contextRegistry[it] = contextUri }
    }

    /** Liked Songs plays as its own context: Spotify maps it to the account's liked list. */
    fun registerLikedSongs(urls: List<String>) = registerContext(urls, LIKED_CONTEXT)

    private fun markPending(trackId: String, itemUri: String? = trackId.takeIf { it.isNotBlank() }?.let { SPOTIFY_TRACK_PREFIX + it }) {
        pendingItemUri = itemUri
        bufferingState.value = true
        itemUri?.let { boundState?.likeState?.value = library[it] ?: false }
        intendPlaying()
        pendingTrackId = trackId
        pendingUntil = SystemClock.elapsedRealtime() + PENDING_MS
        playing = true
        positionMs = 0
        positionAt = SystemClock.elapsedRealtime()
    }

    /**
     * Play [song] (a play key "spotify:track:<id>|<text>" or "episode:<id>"). [contextUri] is
     * the list it was picked from; when null, the last list that registered this track is used.
     */
    /** [order]: the context's track uris in the order to play them (a sorted playlist). */
    fun playSong(song: String, context: Context? = null, contextUri: String? = null, order: List<String>? = null) {
        // Data saver on mobile data: Spotify can't stream, only downloads and Local Files play.
        if (com.music.spotui.data.DataSaver.active) {
            main.post {
                android.widget.Toast.makeText(Graph.app, app.myspoti.engine.R.string.data_saver_blocked, android.widget.Toast.LENGTH_SHORT).show()
            }
            return
        }
        LocalPlayer.stop()
        val episodeId = song.removePrefix("episode:").takeIf { song.startsWith("episode:") }
        val trackId = episodeId ?: spotifyTrackIdForPlayback(song) ?: return
        val itemUri = if (episodeId != null) "spotify:episode:$episodeId" else SPOTIFY_TRACK_PREFIX + trackId
        val ctx = contextUri ?: contextRegistry[song]
        markPending(if (episodeId != null) "" else trackId, itemUri)
        command {
            if (ctx != null) api.player.play(ctx, trackUri = itemUri, order = order?.takeIf { ctx == contextUri })
            else api.player.play(itemUri)
        }
    }

    fun play() {
        if (LocalPlayer.active.value) { Haptics.click(); LocalPlayer.resume(); return }
        Haptics.click(); intendPlaying(); playing = true; positionAt = SystemClock.elapsedRealtime()
        // Tapped on the restored mini player while the Web Player is still loading.
        if (!stateSeen) resumeWhenReady = true
        command { api.player.resume() }
    }

    fun pause() { Haptics.click(); pauseSilently() }

    private fun pauseSilently() {
        if (LocalPlayer.active.value) { LocalPlayer.pause(); return }
        intendPaused()
        positionMs = getCurrentPosition(); positionAt = SystemClock.elapsedRealtime(); playing = false
        command { api.player.pause() }
    }

    fun stop() = pause()
    fun next() { Haptics.click(); if (LocalPlayer.active.value) { LocalPlayer.next(); return }; intendPlaying(); command { api.player.next() } }
    /** A hidden song came up: next, without the button's vibration (the phone may be in a pocket). */
    fun skipHidden() { if (LocalPlayer.active.value) return; intendPlaying(); command { api.player.next() } }
    fun previous() { Haptics.click(); if (LocalPlayer.active.value) { LocalPlayer.previous(); return }; intendPlaying(); command { api.player.previous() } }

    /** Shuffle-play a list: Spotify picks the first track of its own shuffled context. */
    fun shufflePlay(songs: List<SongsModel>) {
        if (songs.isEmpty()) return
        LocalPlayer.stop()
        val ctx = contextRegistry[songs.first().url]
        intendPlaying()
        command {
            api.player.setShuffle(true)
            if (ctx == null) playSong(songs.random().url) else api.player.play(ctx)
            // A play command may reset the options: make sure shuffle sticks.
            kotlinx.coroutines.delay(1500)
            api.player.setShuffle(true)
        }
    }

    fun setShuffle(on: Boolean) { Haptics.toggle(on); if (LocalPlayer.active.value) { LocalPlayer.setShuffle(on); return }; command { api.player.setShuffle(on) } }

    /** 0 = off, 1 = context, 2 = track. */
    private val _repeatMode = mutableStateOf(0)
    val repeatMode: State<Int> get() = _repeatMode

    fun setRepeat(on: Boolean) = setRepeatMode(if (on) 1 else 0)

    fun setRepeatMode(mode: Int) {
        if (LocalPlayer.active.value) { _repeatMode.value = mode; LocalPlayer.setRepeatMode(mode); return }
        Haptics.toggle(mode != 0)
        _repeatMode.value = mode
        boundState?.repeat?.value = mode != 0
        command { api.player.setRepeat(Repeat.entries[mode.coerceIn(0, 2)]) }
    }

    fun cycleRepeat() = setRepeatMode((_repeatMode.value + 1) % 3)

    /** Podcast playback speed (Spotify refuses it on music). */
    fun setSpeed(speed: Double) = command { api.player.setSpeed(speed) }

    fun lyricsTrack(s: SongsModel) = com.music.spotui.data.api.LyricsApi.Track(
        trackId = s.spotifyTrackId, title = s.title, artist = s.singer, album = s.album,
        durationMs = s.durationMs.toLong().takeIf { it > 0 } ?: durationMs, coverUrl = s.coverUri,
    )

    /** The lyrics request for what is playing now. */
    fun currentLyricsTrack(): com.music.spotui.data.api.LyricsApi.Track? {
        val st = boundState ?: return null
        val title = st.title.value
        if (title.isBlank()) return null
        return com.music.spotui.data.api.LyricsApi.Track(
            trackId = currentTrackId, title = title, artist = st.singer.value, album = st.album.value,
            durationMs = durationMs, coverUrl = st.coverUri.value,
        )
    }

    /** Save / remove the current track in Liked Songs. */
    fun setLiked(on: Boolean) {
        if (LocalPlayer.active.value) return // a local file is not in the Spotify library
        Haptics.toggle(on)
        val uri = pendingItemUri ?: _nowPlaying.value.uri.ifBlank { if (currentTrackId.isNotBlank()) SPOTIFY_TRACK_PREFIX + currentTrackId else "" }
        if (uri.isBlank()) { boundState?.likeState?.value = on; return }
        setSaved(uri, on)
    }

    // ── Library state (Liked Songs, saved albums / playlists, followed artists) ──
    /** What Spotify's library says about each uri seen so far; every screen reads this. */
    val library: androidx.compose.runtime.snapshots.SnapshotStateMap<String, Boolean> =
        androidx.compose.runtime.mutableStateMapOf()

    // The user's last like/unlike wins briefly over states that were already in flight.
    @Volatile private var intentUri = ""
    @Volatile private var intentValue = false
    @Volatile private var intentUntil = 0L

    private fun likedFor(uri: String, reported: Boolean): Boolean =
        if (uri == intentUri && SystemClock.elapsedRealtime() < intentUntil) intentValue else reported

    private fun onLibraryItem(uri: String, saved: Boolean) {
        if (uri == intentUri && SystemClock.elapsedRealtime() < intentUntil && saved != intentValue) return
        library[uri] = saved
        if (uri == (pendingItemUri ?: _nowPlaying.value.uri)) {
            boundState?.likeState?.value = saved
            pushNotification(null, saved)
        }
        if (!saved && uri.startsWith(SPOTIFY_TRACK_PREFIX)) {
            val id = trackIdOf(uri)
            com.music.spotui.data.api.Api.LikedCache.tracks =
                com.music.spotui.data.api.Api.LikedCache.tracks?.filterNot { it.spotifyTrackId == id }
        }
    }

    // Rows ask for their saved state one by one; ask Spotify in one batch per frame.
    private val wantedSaved = java.util.Collections.synchronizedSet(LinkedHashSet<String>())
    private val flushSaved = Runnable {
        val uris = synchronized(wantedSaved) { wantedSaved.toList().also { wantedSaved.clear() } }
        if (uris.isNotEmpty()) scope.launch { uris.chunked(50).forEach { libraryContains(it) } }
    }

    /** Make sure [uri]'s saved state ends up in [library]. */
    fun requestSaved(uri: String) {
        if (uri.isBlank() || library.containsKey(uri)) return
        if (wantedSaved.add(uri)) { main.removeCallbacks(flushSaved); main.postDelayed(flushSaved, 32) }
    }

    /** Swipe to the next track: show it right away, then skip. */
    fun swipeNext() {
        if (LocalPlayer.active.value) { next(); return }
        _nowPlaying.value.next?.let { n ->
            markPending(n.spotifyTrackId)
            boundState?.updateSongState(n.coverUri, n.title, n.singer, true, n.id, 0, n.album)
            durationMs = n.durationMs.toLong()
            pushNotification(n, false)
        }
        next()
    }

    fun seekTo(ms: Long) {
        if (LocalPlayer.active.value) { LocalPlayer.seekTo(ms); return }
        positionMs = ms
        positionAt = SystemClock.elapsedRealtime()
        command { api.player.seek(ms) }
    }

    /** Which of [uris] are saved in the user's library (Liked Songs / saved albums…). */
    suspend fun libraryContains(uris: List<String>): Set<String> {
        if (uris.isEmpty()) return emptySet()
        val flags = runCatching { api.library.contains(uris) }.getOrNull() ?: return emptySet()
        val saved = uris.filterIndexed { i, _ -> flags.getOrNull(i) == true }.toSet()
        main.post {
            for (u in uris) {
                if (u == intentUri && SystemClock.elapsedRealtime() < intentUntil) continue
                library[u] = u in saved
            }
        }
        return saved
    }

    /** Bumped at every like / unlike of a track: the Liked Songs page refreshes its top. */
    val likedChanges = kotlinx.coroutines.flow.MutableStateFlow(0)

    /** Like / unlike a track by id. */
    fun setTrackSaved(trackId: String, on: Boolean) {
        if (trackId.isBlank()) return
        Haptics.toggle(on)
        setSaved(SPOTIFY_TRACK_PREFIX + trackId, on)
    }

    /** Save / remove any item (album, playlist, track, artist, show, episode) in the user's library. */
    fun setSaved(uri: String, on: Boolean) {
        intentUri = uri
        intentValue = on
        intentUntil = SystemClock.elapsedRealtime() + 3000
        onLibraryItem(uri, on)
        val track = uri.startsWith(SPOTIFY_TRACK_PREFIX)
        command {
            if (uri.startsWith("spotify:playlist:")) {
                if (on) api.playlists.follow(uri) else api.playlists.unfollow(uri)
            } else {
                if (on) api.library.add(listOf(uri)) else api.library.remove(listOf(uri))
            }
            if (track) likedChanges.value++
        }
    }

    // ── Music video: Spotify's own clips are not played by this engine; the YouTube
    // clips (MusicVideoController) are. These keep the player screen's contract. ──
    data class VideoState(val available: Boolean = false, val on: Boolean = false, val ready: Boolean = false, val aspect: Float = 0f, val placed: Boolean = false)
    private val _video = mutableStateOf(VideoState())
    val video: State<VideoState> get() = _video

    data class VideoSlot(val left: Float, val top: Float, val width: Float, val height: Float, val visible: android.graphics.RectF)
    val videoSlot = mutableStateOf<VideoSlot?>(null)
    @Volatile var videoSlotOwner: Any? = null
    val videoFullscreen = mutableStateOf(false)

    fun setVideo(on: Boolean) {
        _video.value = _video.value.copy(on = false, ready = false)
        videoFullscreen.value = false
    }

    val engineBounds = mutableStateOf<IntArray?>(null)

    fun setVideoLayout(mode: String?, x: Float = 0f, y: Float = 0f, w: Float = 0f, h: Float = 0f, viewW: Float = 0f) {}

    // ── Spotify Connect ──
    data class ConnectDevice(val id: String, val name: String, val type: String, val isSelf: Boolean, val isActive: Boolean)

    private val _activeDevice = mutableStateOf<ConnectDevice?>(null)
    /** The device currently playing, when known and not this one. */
    val activeDevice: State<ConnectDevice?> get() = _activeDevice

    private fun refreshDevice() {
        scope.launch {
            runCatching { api.devices.refresh() }
            val active = api.devices.devices.value.firstOrNull { it.active }
            _activeDevice.value = active?.takeIf { !it.local }?.let { ConnectDevice(it.id, it.name, it.type, false, true) }
            active?.volume?.let { if (SystemClock.elapsedRealtime() > volumeHoldUntil) remoteVolume = (it * 100).toInt().coerceIn(0, 100) }
            PlaybackService.setPlaybackDevice(_activeDevice.value != null, remoteVolume)
        }
    }

    /** Devices of the account, this phone first. */
    suspend fun devices(): List<ConnectDevice> {
        runCatching { api.devices.refresh() }
        val list = api.devices.devices.value.map { ConnectDevice(it.id, it.name, it.type, it.local, it.active) }
            .sortedWith(compareByDescending<ConnectDevice> { it.isSelf }.thenBy { it.name })
        _activeDevice.value = list.firstOrNull { it.isActive && !it.isSelf }
        return list
    }

    /** Move playback to [device] (Spotify Connect transfer). */
    fun transferTo(device: ConnectDevice) {
        Haptics.confirm()
        _activeDevice.value = if (device.isSelf) null else device
        command { if (device.isSelf) api.devices.pullHere() else api.devices.transfer(device.id) }
    }

    /** Volume (0..100) of the device that plays, as last reported. */
    @Volatile var remoteVolume = 100
        private set
    @Volatile private var volumeHoldUntil = 0L

    /** Set the volume of the device that plays (volume keys, system slider). */
    fun setRemoteVolume(volume: Int) {
        remoteVolume = volume.coerceIn(0, 100)
        volumeHoldUntil = SystemClock.elapsedRealtime() + 1500
        command { api.player.setVolume(remoteVolume / 100.0) }
        PlaybackService.setPlaybackDevice(_activeDevice.value != null, remoteVolume)
    }

    // ── Sleep timer ──
    private val _sleepEndsAt = mutableStateOf<Long?>(null)   // elapsedRealtime, or -1 = end of track
    val sleepEndsAt: State<Long?> get() = _sleepEndsAt
    private val sleepRunnable = Runnable { _sleepEndsAt.value = null; pauseSilently() }
    private var sleepTrackId: String? = null

    /** [minutes] null = off, 0 = at the end of the current track. */
    fun setSleepTimer(minutes: Int?) {
        main.removeCallbacks(sleepRunnable)
        sleepTrackId = null
        when {
            minutes == null -> _sleepEndsAt.value = null
            minutes == 0 -> { sleepTrackId = currentTrackId; _sleepEndsAt.value = -1L }
            else -> {
                _sleepEndsAt.value = SystemClock.elapsedRealtime() + minutes * 60_000L
                main.postDelayed(sleepRunnable, minutes * 60_000L)
            }
        }
    }

    private fun checkEndOfTrackTimer(newTrackId: String) {
        val t = sleepTrackId ?: return
        if (newTrackId.isNotBlank() && newTrackId != t) {
            sleepTrackId = null
            _sleepEndsAt.value = null
            pauseSilently()
        }
    }

    // ── Queue (the API pushes every change, nothing to poll) ──
    fun watchQueue(on: Boolean) {}
    fun requestQueue() {}

    private fun apiItem(item: QueueItem): app.myspoti.api.NowPlaying? =
        apiQueue?.let { q -> (q.queued + q.nextUp).firstOrNull { it.uri == item.uri && (item.uid.isBlank() || it.uid == item.uid) } }

    /** "Play next": first in the queue, right after the current track. */
    fun playNext(trackId: String) {
        if (trackId.isBlank()) return
        Haptics.confirm()
        command { api.player.playNext(listOf(SPOTIFY_TRACK_PREFIX + trackId)) }
    }

    fun addToQueue(trackId: String) {
        if (trackId.isBlank()) return
        Haptics.confirm()
        command { api.player.addToQueue(listOf(SPOTIFY_TRACK_PREFIX + trackId)) }
    }

    fun skipTo(item: QueueItem) {
        val id = trackIdOf(item.uri)
        if (id.isNotBlank()) markPending(id)
        boundState?.updateSongState(item.song.coverUri, item.song.title, item.song.singer, true, item.song.id, 0, item.song.album)
        durationMs = item.song.durationMs.toLong()
        pushNotification(item.song, false)
        val target = apiItem(item) ?: return
        command { api.player.skipTo(target) }
    }

    /** "Clear queue": drops the tracks the user queued (not the context's next ones). */
    fun clearQueue() {
        _queue.value = _queue.value.copy(queued = emptyList())
        command { api.player.clearQueue() }
    }

    /** Move [item] just before / after [target] (same section of the queue). */
    fun reorder(item: QueueItem, target: QueueItem, after: Boolean) {
        val a = apiItem(item) ?: return
        val b = apiItem(target) ?: return
        command { api.player.moveInQueue(a, b, after) }
    }

    /** Move a "next up" track into the queue, right after the current one. */
    fun moveToQueueFront(item: QueueItem) {
        val a = apiItem(item) ?: return
        command {
            api.player.addToQueue(listOf(a.uri))
            api.player.removeFromQueue(listOf(a))
        }
    }

    fun removeFromQueue(item: QueueItem) {
        _queue.value = _queue.value.let { q -> q.copy(queued = q.queued - item, nextUp = q.nextUp - item) }
        val a = apiItem(item) ?: return
        command { api.player.removeFromQueue(listOf(a)) }
    }

    fun isPlaying(): Boolean = if (LocalPlayer.active.value) LocalPlayer.isPlaying() else playing
    fun getDuration(): Long = if (LocalPlayer.active.value) LocalPlayer.duration() else durationMs

    fun getCurrentPosition(): Long {
        if (LocalPlayer.active.value) return LocalPlayer.position()
        val base = positionMs
        if (!playing || isBuffering) return base
        val now = base + (SystemClock.elapsedRealtime() - positionAt)
        return if (durationMs > 0) now.coerceAtMost(durationMs) else now
    }

    /** A local file starts: Spotify pauses (wherever it plays). */
    fun pauseEngineForLocal() {
        if (playing) command { api.player.pause() }
        playing = false
    }

    /** The local player's track / state, shown like any other (player, mini player, notification). */
    fun onLocalState(t: com.music.spotui.data.local.LocalTrack, isPlaying: Boolean, pos: Long, dur: Long) {
        main.post {
            currentTrackId = ""
            playing = isPlaying; positionMs = pos; positionAt = SystemClock.elapsedRealtime(); durationMs = dur
            bufferingState.value = false
            val uri = "local:${t.id}"
            if (_nowPlaying.value.uri != uri) _nowPlaying.value = NowPlaying(uri = uri, contextName = LocalPlayer.contextName)
            val song = SongsModel(id = ("local:" + t.id).hashCode() and 0x7fffffff, title = t.title, album = t.album,
                singer = t.artist, coverUri = t.artUri.toString(), url = "local:${t.uri}", durationMs = dur.toInt())
            boundState?.let { st ->
                st.likeState.value = false
                st.updateSongState(song.coverUri, song.title, song.singer, isPlaying, song.id, 0, song.album)
            }
            pushNotification(song, false)
            val source = if (t.uri.scheme == "file") com.music.spotui.data.local.ListenHistory.Source.DOWNLOAD
                else com.music.spotui.data.local.ListenHistory.Source.LOCAL
            trackListen(uri, song, source, isPlaying)
            // The offline report: a downloaded song this app really played gets its
            // play counted on Spotify's side (Wrapped), now or once back online.
            com.music.spotui.data.offline.WrappedSync.onLocalState(t, isPlaying, pos, dur)
        }
    }

    fun isCrossfadeActive(): Boolean = false
    fun release() {}

    // ── Play-key helpers (url = "spotify:track:<id>|<search text>") ──

    private val featSearchPattern = Regex("""\s*[\(\[]\s*(feat|ft)\..*?[\)\]]""", RegexOption.IGNORE_CASE)

    fun buildSpotifyPlayQuery(spotifyTrackId: String, title: String, artist: String): String {
        val searchText = listOf(title.replace(featSearchPattern, "").trim(), artist)
            .filter { it.isNotBlank() }
            .joinToString(" ")
        return if (spotifyTrackId.isBlank()) searchText else "$SPOTIFY_TRACK_PREFIX$spotifyTrackId|$searchText"
    }

    private fun spotifyTrackIdForPlayback(song: String): String? =
        if (song.startsWith(SPOTIFY_TRACK_PREFIX)) {
            song.removePrefix(SPOTIFY_TRACK_PREFIX).substringBefore('|').takeIf { it.isNotBlank() }
        } else {
            null
        }

    // Media cache for the looping Canvas video on the now-playing screen.
    @Volatile private var mediaCache: androidx.media3.datasource.cache.SimpleCache? = null

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    internal fun mediaCache(context: Context): androidx.media3.datasource.cache.SimpleCache =
        mediaCache ?: synchronized(this) {
            mediaCache ?: androidx.media3.datasource.cache.SimpleCache(
                java.io.File(context.cacheDir, "media"),
                androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor(64L * 1024 * 1024),
                androidx.media3.database.StandaloneDatabaseProvider(context),
            ).also { mediaCache = it }
        }
}
