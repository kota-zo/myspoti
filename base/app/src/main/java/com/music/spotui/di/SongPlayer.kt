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
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock
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
    /** The end-of-track timer pauses this close to the end (before the next item starts). */
    private const val END_OF_TRACK_MARGIN_MS = 600L
    /** How often the state is read again while another device plays. */
    private const val REMOTE_REFRESH_MS = 3_000L
    /** How often the state is read again while this phone plays (the page pushes its changes). */
    private const val LOCAL_REFRESH_MS = 15_000L
    /** A song this far past its end with no new state from the page: the state is read at once. */
    private const val SONG_END_GRACE_MS = 1_500L
    /** A transport command that has not answered by then is given up (the next one runs). */
    private const val TRANSPORT_TIMEOUT_MS = 15_000L
    /**
     * A transport command still running this long when a newer one comes is stuck (a dead
     * connection, a page that does not answer): it is given up at once rather than holding the
     * newer one for the rest of [TRANSPORT_TIMEOUT_MS]. A skip normally answers well within it.
     */
    private const val TRANSPORT_STUCK_MS = 3_000L
    /**
     * The longest a skip is shown ahead of the page: its burst gap, its turn behind a stuck command
     * and its own timeout. Once it is answered, the page has [SKIP_ANSWERED_MS] (see [skipDone]).
     */
    private const val SKIP_PENDING_MAX_MS = TRANSPORT_TIMEOUT_MS + 5_000L
    /**
     * An answered skip whose song has not come by then: the old song is heard again (the engine
     * brings its sound back 2.5 s after the skip, SpotifyPlayer.FADE_BACK_MAX_MS), so the screen
     * shows it again too. Reported: the next song's cover on screen, the old song playing on.
     */
    private const val SKIP_ANSWERED_MS = 3_000L
    /** How long a play has to show up in the page's state before it is sent again. */
    private const val PLAY_CHECK_MS = 4_000L
    /** A resume of an episode that the page did not take is asked for again after that. */
    private const val EPISODE_RESUME_CHECK_MS = 2_500L
    /** "Next" taps closer than this are sent as one jump. */
    private const val SKIP_BURST_MS = 350L
    /** Skip commands are at least this far apart; taps in between add up (see [next]). */
    private const val MIN_SKIP_GAP_MS = 2_000L
    /** An ad break shorter than this is never shown (see the adPlaying collector). */
    private const val AD_SHOW_AFTER_MS = 2_000L
    /** After a local file starts, engine states this recent still predate the pause. */
    private const val LOCAL_GRACE_MS = 2500L
    /** An answered command's state reaches the app within this (see [reconcile]). */
    private const val COMMAND_SETTLE_MS = 1_500L
    /** A hold's end is checked this long after it, so the hold is over by then (see [reconcile]). */
    private const val RECONCILE_MARGIN_MS = 100L
    /** The page's state is read again that long at most before the screen is put right. */
    private const val RECONCILE_READ_MS = 3_000L
    /** A seek is sent within this, or given up for this try (see [seekTo]). */
    private const val SEEK_SEND_MS = 5_000L
    /** How long a seek has to show up in the page's state before it is sent again. */
    private const val SEEK_CHECK_MS = 3_000L
    /** The longest a seek holds the clock against the page (both tries and their sends). */
    private const val SEEK_HOLD_MS = 2 * (SEEK_SEND_MS + SEEK_CHECK_MS)
    /** A page position this close to a seek's target (or past it by the time played) is the seek. */
    private const val SEEK_NEAR_MS = 2_000L
    private const val TAG = "MspPlayer"
    private const val LIKED_CONTEXT = "spotify:collection:tracks"

    private val api: MyspotiApi get() = MyspotiRuntime.api
    private val scope get() = MyspotiRuntime.scope

    // ── Player state ──
    @Volatile private var playing = false
    private val bufferingState = mutableStateOf(false)
    /** Observable startup state: visual lookups wait for the audio transport. */
    val isBuffering: Boolean get() = bufferingState.value

    /**
     * An ad break is on (T4). The page publishes no track while one plays — the song before it
     * stays in the state, buffering — so the screens show this instead of that stale song.
     */
    private val adState = mutableStateOf(false)
    val adPlaying: Boolean get() = adState.value
    @Volatile private var durationMs = 0L
    @Volatile private var positionMs = 0L
    @Volatile private var positionAt = 0L
    /** The page says it plays, but nothing is heard (see [onSoundStalled]): the clock waits. */
    @Volatile private var soundStalled = false
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

    private fun intendPlaying() { wantPlayingUntil = SystemClock.elapsedRealtime() + 4000; wantPausedUntil = 0; reconcileAfter(intentEnded, 4000) }
    private fun intendPaused() { wantPausedUntil = SystemClock.elapsedRealtime() + 1500; wantPlayingUntil = 0; reconcileAfter(intentEnded, 1500) }

    /**
     * Puts the screen back on what the page last said once a hold is over (a play or pause
     * wanted, a song shown before it plays). The page pushes changes only, and a state read again
     * that did not change is no news either: a play the page never took left the clock counting
     * over silence, and a skip that never landed left the next song on screen while the old one
     * played on (reported).
     */
    private fun reconcile() {
        // A command still on its way: its own end puts the screen right (see [transport]).
        fun leftAlone() = !acceptsSessionUpdates() || LocalPlayer.active.value || transportsInFlight.get() > 0
        if (leftAlone()) return
        scope.launch {
            // Read again first: the page's last push can be lost on the way (its events buffer
            // drops the oldest when its main thread stalls), and the screen must not go back to
            // "paused" over a song that plays.
            kotlinx.coroutines.withTimeoutOrNull(RECONCILE_READ_MS) { runCatching { MyspotiRuntime.spotify.player.refresh() } }
            if (leftAlone()) return@launch
            val s = api.player.state.value
            if (s != null) { applyState(s); return@launch }
            // The page never reported anything (it did not load): nothing plays, whatever was tapped.
            if (playing) {
                playing = false
                bufferingState.value = false
                boundState?.updatePlayingState(false)
                pushNotification(null, boundState?.likeState?.value ?: false)
            }
        }
    }
    private val intentEnded = Runnable { reconcile() }
    private val pendingEnded = Runnable { reconcile() }
    private val commandSettled = Runnable { reconcile() }
    private fun reconcileAfter(check: Runnable, ms: Long) {
        main.removeCallbacks(check)
        main.postDelayed(check, ms.coerceAtLeast(0L) + RECONCILE_MARGIN_MS)
    }

    /**
     * Not heard yet: the page buffers, its sound stands still, or a play is wanted that the page
     * has not started. The clock waits and the screens show the song loading.
     */
    private fun waiting(s: PlayerState) = s.isBuffering || soundStalled || (playing && s.isPaused)

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

    /** Queue items of the local player (downloads, Local Files). */
    private const val LOCAL = "local"

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
        com.music.spotui.data.api.LyricsApi.wordSync =
            com.music.spotui.data.preferences.isLyricsWordSync(Graph.app)
        restoreLastTrack(state)
        scope.launch { api.player.state.collect { s -> if (acceptsSessionUpdates()) applyState(s) } }
        // Another device plays (the phone as a remote): the state is read again every few seconds
        // so the notification, the mini player and the slider keep up. Reported: the notification
        // stopped refreshing in the background — this loop waited for _activeDevice, which only a
        // pull-back sets, so an app started in the background never ran it.
        // This phone plays: the page pushes every change itself (it is never throttled, see
        // AwakeWebView), so reading the whole state and queue every 3 s only kept both processes
        // awake, screen off. It is read every LOCAL_REFRESH_MS as a safety net, and at once when
        // the song should have ended without the page saying what came next.
        scope.launch {
            var lastRead = 0L
            while (true) {
                kotlinx.coroutines.delay(REMOTE_REFRESH_MS)
                val remote = _activeDevice.value != null
                if (!remote && !PlaybackService.isRunning) continue
                val now = SystemClock.elapsedRealtime()
                val overdue = playing && !isBuffering && durationMs > 0 &&
                    positionMs + (now - positionAt) > durationMs + SONG_END_GRACE_MS
                if (!remote && !overdue && now - lastRead < LOCAL_REFRESH_MS) continue
                lastRead = now
                runCatching { MyspotiRuntime.spotify.player.refresh() }
            }
        }
        scope.launch { api.player.queue.collect { q -> if (acceptsSessionUpdates()) applyQueue(q) } }
        scope.launch { MyspotiRuntime.spotify.player.stalled.collect { on -> onSoundStalled(on) } }
        // Smart shuffle plays a mixed order as the list and leaves Spotify's own shuffle off, so
        // turning it off sends no shuffle command at all (the button finds shuffle already off):
        // the list's own order comes back here.
        scope.launch {
            var was = com.music.spotui.ui.screens.SmartShuffle.on.value
            androidx.compose.runtime.snapshotFlow { com.music.spotui.ui.screens.SmartShuffle.on.value }.collect { now ->
                val ended = was.takeIf { now == null }
                was = now
                if (ended != null) endSmartShuffle(ended)
            }
        }
        scope.launch { api.library.changes.collect { refreshCurrentLiked() } }
        // T4: the player keeps the ad out of [api.player.state] on purpose (the last song stays
        // there, buffering); its own flag is collected here so the UI can say an ad is on. Only an
        // ad that is still on after AD_SHOW_AFTER_MS is shown: a blocked ad is gone within a second
        // (its audio is refused, the page moves on), and showing it only made the screens blink.
        scope.launch {
            MyspotiRuntime.spotify.player.adPlaying.collectLatest { on ->
                if (on) {
                    kotlinx.coroutines.delay(AD_SHOW_AFTER_MS)
                    // Spotify limiting playback holds every repair, the ad's too: the limit is what
                    // the user is told (its toast), not an ad for as long as it lasts (50 s seen).
                    while (MyspotiRuntime.host.licenseLimited()) kotlinx.coroutines.delay(1_000)
                }
                onAdPlaying(on)
            }
        }
        scope.launch {
            MyspotiRuntime.spotify.player.refused.collect {
                // No Widevine in this WebView: the refusal is the WebView's, not the account's.
                val message = if (MyspotiRuntime.spotify.player.drmMissing.value) app.myspoti.engine.R.string.drm_missing else app.myspoti.engine.R.string.play_refused
                android.widget.Toast.makeText(Graph.app, message, android.widget.Toast.LENGTH_LONG).show()
            }
        }
        // Said once the page has asked its WebView: a device that cannot decrypt Spotify's streams
        // plays nothing (no sound, 0:00), and nothing else in the app says why.
        scope.launch {
            MyspotiRuntime.spotify.player.drmMissing.first { it }
            android.widget.Toast.makeText(Graph.app, app.myspoti.engine.R.string.drm_missing, android.widget.Toast.LENGTH_LONG).show()
        }
        scope.launch {
            MyspotiRuntime.host.licenseLimitedUntil.collect { until ->
                if (until > SystemClock.elapsedRealtime()) main.post {
                    playing = false
                    boundState?.updatePlayingState(false)
                    android.widget.Toast.makeText(Graph.app, app.myspoti.engine.R.string.spotify_limited, android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }
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
        // Its own end puts nothing back on screen: the seq it ends with is no longer the last.
        seekSeq++; seekJob?.cancel(); seekUntil = 0L; seekUri = ""
        stateSeen = false
        playing = false; bufferingState.value = false; adState.value = false; durationMs = 0L; positionMs = 0L
        currentTrackId = ""; currentContextUri = ""
        contextRegistry.clear(); contextNames.clear(); library.clear()
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

    /**
     * The page says it plays but its sound stands still (a slow network), or plays again. The page's
     * own clock goes on over the silence (reported: "the time was counting, the song not playing"):
     * the screen's clock stops where it is and goes on from there once the sound does.
     */
    private fun onSoundStalled(on: Boolean) {
        if (on == soundStalled) return
        // Read before the flag changes: the running clock as it stands, or the held one.
        positionMs = getCurrentPosition()
        positionAt = SystemClock.elapsedRealtime()
        soundStalled = on
        // A song shown before the page plays it is waiting already (see [markPending]).
        if (LocalPlayer.active.value || pendingTrackId != null) return
        val s = api.player.state.value ?: return
        bufferingState.value = waiting(s)
        pushNotification(null, boundState?.likeState?.value ?: false)
        com.music.spotui.video.MusicVideoController.onAudioBuffering(isBuffering, currentTrackId)
    }

    /** An ad break starts or ends: the notification, the widget and the screens follow (T4). */
    private fun onAdPlaying(on: Boolean) {
        if (adState.value == on) return
        adState.value = on
        pushNotification(null, boundState?.likeState?.value ?: false)
    }

    /** Notification, lock screen, Bluetooth and Android Auto. */
    private fun pushNotification(song: SongsModel?, liked: Boolean) {
        val st = boundState
        // T4: during an ad break the page reports no track (the song before it would sit there,
        // buffering), so the notification and the widget say an ad is on instead of that song.
        val ad = adState.value
        val title = if (ad) Graph.app.getString(app.myspoti.engine.R.string.ad_playing) else song?.title ?: st?.title?.value.orEmpty()
        val artist = if (ad) "" else song?.singer ?: st?.singer?.value.orEmpty()
        val cover = if (ad) null else song?.coverUri ?: st?.coverUri?.value
        PlaybackService.update(Graph.app, NowPlayingInfo(
            id = if (ad) "spotify:ad" else _nowPlaying.value.uri,
            title = title,
            artist = artist,
            album = if (ad) "" else song?.album ?: st?.album?.value.orEmpty(),
            coverUrl = cover,
            durationMs = if (ad) 0L else getDuration(),
            positionMs = if (ad) 0L else getCurrentPosition(),
            playing = isPlaying() && !ad,
            buffering = isBuffering && !ad,
            liked = !ad && liked,
            shuffle = st?.shuffle?.value == true,
        ))
        com.music.spotui.widget.NowPlayingWidget.show(Graph.app,
            title = title,
            artist = artist,
            coverUrl = cover,
            playing = isPlaying() && !ad)
    }

    /** The autoplay item already paused on, so it is paused once, not on every state. */
    private var autoplayHandledUri = ""

    private fun applyState(s: PlayerState?) {
        if (s == null) return
        stateSeen = true
        if (LocalPlayer.active.value && engineWhileLocal(s)) return
        if (resumeWhenReady) {
            resumeWhenReady = false
            // The Web Player restored the last session paused: play what was tapped.
            if (s.isPaused) transport { api.player.resume() }
        }
        val item = s.item
        val uri = item?.uri.orEmpty()
        if (uri.isNotEmpty()) lastPageUri = uri
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
        playing = resolvePlaying(s.isPaused)
        bufferingState.value = waiting(s)
        SpeakerKeepAlive.set(playing && _activeDevice.value == null)
        durationMs = item?.durationMs ?: 0L
        val reported = s.positionAt(System.currentTimeMillis())
        // A seek on its way: the page's old position does not take the clock back (see [seekTo]).
        if (seekHeld(uri) && seekLanded(s)) seekUntil = 0L
        val seeking = seekHeld(uri)
        // Keep the running clock when the player only nudges it (< 1 s).
        if (!seeking && !(wasPlaying && playing && !wasBuffering && !isBuffering && kotlin.math.abs(reported - predicted) < 1000)) {
            positionMs = reported
            positionAt = SystemClock.elapsedRealtime()
        }
        val trackChanged = uri != _nowPlaying.value.uri
        // Autoplay off: stop once, when the player moves on to a track it picked itself.
        // Tracks only: this is the music setting (similar songs). An episode the page rolls on
        // to after the previous one belongs to the show, not to that recommendation autoplay —
        // pausing it there is how a podcast "stopped by itself" (B11).
        if (item != null && item.provider == "autoplay" && uri.startsWith(SPOTIFY_TRACK_PREFIX) &&
            uri != autoplayHandledUri && !com.music.spotui.data.preferences.AutoplayPref.on) {
            autoplayHandledUri = uri
            if (!s.isPaused) pauseSilently()
        }
        currentContextUri = s.contextUri.orEmpty()
        if (item != null) { checkEndOfTrackTimer(id); currentTrackId = id }
        _nowPlaying.value = NowPlaying(
            contextUri = s.contextUri.orEmpty(),
            contextName = s.contextName.orEmpty(),
            provider = item?.provider.orEmpty(),
            uri = uri,
            explicit = item?.explicit == true,
            albumUri = item?.album?.uri.orEmpty(),
            prev = s.previous?.let(::songOf),
            next = s.next?.let(::songOf),
        )
        val state = boundState ?: return
        val holding = SystemClock.elapsedRealtime() < shuffleWantedUntil
        if (holding && s.shuffle == shuffleWanted) shuffleWantedUntil = 0L
        if (!holding || s.shuffle == shuffleWanted) state.shuffle.value = s.shuffle
        // The page reports a speed other than 1 only for episodes: music stays at 1.
        _speed.value = s.speed.takeIf { it > 0.0 } ?: 1.0
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
        // Clip lookup only where it can pay off: video wanted, or the player screen
        // is up (toggle then is instant). Looking up every track by default cost
        // searches and full-audio alignment downloads for users who never watch.
        if (!isBuffering && playing && mv.inForeground && (mv.wantVideo || mv.hasPlayerScreen) && song.spotifyTrackId.isNotBlank())
            com.music.spotui.video.MusicVideoController.prepare(song.spotifyTrackId, song.title, song.singer, durationMs)
        if (!isBuffering && playing && mv.inForeground && app.myspoti.power.EnergySettings.state.value.policy.allowBackgroundLookups) {
            com.music.spotui.data.api.LyricsApi.prefetch(lyricsTrack(song))
            _nowPlaying.value.next?.let { com.music.spotui.data.api.LyricsApi.prefetch(lyricsTrack(it)) }
            // The next song's clip is looked up now, so its button is there when it starts. The
            // controller itself stays off under the data saver; a Spotify limit holds it too, as
            // it holds every other request. Only for someone who has the player open or wants
            // the clip: a lookup downloads ~145 s of audio, too much data and battery to spend on
            // every song for a user who never looks at the player.
            if ((mv.wantVideo || mv.hasPlayerScreen) && !MyspotiRuntime.host.licenseLimited()) _nowPlaying.value.next?.takeIf { it.spotifyTrackId.isNotBlank() && it.durationMs > 0 }?.let {
                // Without the song's length the match could not be checked, so nothing is cached for it.
                mv.prefetch(it.spotifyTrackId, it.title, it.singer, it.durationMs.toLong())
            }
        }
    }

    private fun refreshCurrentLiked() {
        val uri = _nowPlaying.value.uri.takeIf { it.startsWith(SPOTIFY_TRACK_PREFIX) || it.startsWith("spotify:episode:") } ?: return
        scope.launch {
            // Through the shared, cached request path: a state Spotify confirmed less than
            // [SAVED_FRESH_MS] ago is not asked for again (its row asked for it already).
            libraryContains(listOf(uri))
            library[uri]?.let { onLibraryItem(uri, it) }
        }
    }

    /** Back from the local player: the Spotify queue again. */
    fun restoreEngineQueue() = main.post { _queue.value = EngineQueue(); applyQueue(apiQueue) }

    private fun applyQueue(q: app.myspoti.api.Queue?) {
        apiQueue = q
        // While a downloaded or local file plays, the queue shown is the local player's.
        if (q == null || LocalPlayer.active.value) return
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

    private val transportLock = kotlinx.coroutines.sync.Mutex()
    private val transportGen = java.util.concurrent.atomic.AtomicInteger()

    /**
     * Play, pause, skip: sent one at a time and only the newest one counts. Taps sent all
     * at once raced in the Web Player (a skip and a play crossing, a player stuck on one
     * song until restarted); one still waiting when a newer one comes is dropped.
     */
    /** [pullBack]: playback taken by another device comes back here first (not for a pause). */
    /**
     * [waitReady]: wait for the page when it is loading. Skips do not once a state was seen:
     * the player keeps them for after the reload (the song the taps point at plays then).
     */
    /**
     * [checkAfter]: when this phone was the one playing, the device check runs after the command
     * instead of before it, so the tap does not wait on a server round trip. A takeover it finds
     * then is pulled back as before; with another device known to be active, the check still
     * comes first, since the pull-back has to happen before the command.
     */
    /**
     * [done]: told once, on the main thread, whether the command was answered (true) or not
     * (failed, timed out, dropped for a newer one or for a page that never loaded).
     */
    private fun transport(
        pullBack: Boolean = true,
        waitReady: Boolean = true,
        checkAfter: Boolean = false,
        done: ((ok: Boolean) -> Unit)? = null,
        block: suspend (isLatest: () -> Boolean) -> Unit,
    ) {
        val gen = transportGen.incrementAndGet()
        runningTransport?.let { job ->
            val held = SystemClock.elapsedRealtime() - runningSince
            if (job.isActive && held >= TRANSPORT_STUCK_MS) {
                Log.w(TAG, "transport stuck for $held ms: given up for the newer one")
                job.cancel()
            }
        }
        scope.launch {
            val self = coroutineContext[kotlinx.coroutines.Job]
            var told = false
            fun tell(ok: Boolean) {
                if (told) return
                told = true
                done?.invoke(ok)
            }
            var answered = false
            // Counted from inside: a coroutine cancelled before it starts never runs its finally.
            transportsInFlight.incrementAndGet()
            try {
                transportLock.withLock {
                    if (gen != transportGen.get()) return@withLock
                    runningTransport = self
                    runningSince = SystemClock.elapsedRealtime()
                    // A tap while the page loads (cold start, a reload): sent once it is there,
                    // instead of failing at once with "not ready".
                    if (waitReady && !api.ready.value && kotlinx.coroutines.withTimeoutOrNull(READY_WAIT_MS) { api.ready.first { it } } == null) {
                        Log.w(TAG, "transport dropped: the player page is not ready")
                        return@withLock
                    }
                    if (gen != transportGen.get()) return@withLock
                    val checkLater = pullBack && checkAfter && _activeDevice.value == null
                    if (pullBack && !checkLater && api.ready.value) takeBackPlayback()
                    // A cancellation of the caller is never swallowed (it must unwind); only the
                    // transport's own timeout and a real failure are logged here.
                    try {
                        kotlinx.coroutines.withTimeout(TRANSPORT_TIMEOUT_MS) { block { gen == transportGen.get() } }
                        answered = true
                        tell(true)
                    } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                        Log.w(TAG, "transport timed out after ${TRANSPORT_TIMEOUT_MS} ms")
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "transport failed: ${e.message}")
                    }
                    if (checkLater && gen == transportGen.get() && api.ready.value) takeBackPlayback()
                }
            } finally {
                // A newer transport may hold the lock already: only this one's own mark is cleared.
                if (runningTransport === self) runningTransport = null
                tell(false)
                transportsInFlight.decrementAndGet()
                // An answered play or pause: the page gets a moment to say so before the screen
                // is put back on what it says (a slow network answers late, its state later).
                // One that failed is put right then too, whatever was wanted.
                if (answered) {
                    val until = SystemClock.elapsedRealtime() + COMMAND_SETTLE_MS
                    if (wantPlayingUntil != 0L) wantPlayingUntil = maxOf(wantPlayingUntil, until)
                    if (wantPausedUntil != 0L) wantPausedUntil = maxOf(wantPausedUntil, until)
                }
                reconcileAfter(commandSettled, COMMAND_SETTLE_MS)
            }
        }
    }

    /** Transports started and not over yet: [reconcile] leaves the screen to them meanwhile. */
    private val transportsInFlight = java.util.concurrent.atomic.AtomicInteger()

    /** The transport holding [transportLock], and since when (see [TRANSPORT_STUCK_MS]). */
    @Volatile private var runningTransport: kotlinx.coroutines.Job? = null
    @Volatile private var runningSince = 0L

    /**
     * Plays [itemUri], then checks that the page really moved to it; a play the page lost
     * (busy, reloading) is sent once more instead of leaving the old song playing.
     */
    private suspend fun playChecked(itemUri: String, isLatest: () -> Boolean, play: suspend () -> Unit) {
        play()
        repeat(2) { attempt ->
            val moved = kotlinx.coroutines.withTimeoutOrNull(PLAY_CHECK_MS) {
                api.player.state.first { it?.item?.uri == itemUri || !isLatest() }
            }
            if (moved != null || !isLatest()) return
            if (attempt == 0) { Log.w(TAG, "play of $itemUri not taken: sent again"); play() }
        }
    }

    /** Remember the Spotify context the given play keys belong to. */
    fun registerContext(urls: List<String>, contextUri: String) {
        urls.forEach { contextRegistry[it] = contextUri }
    }

    // Names of the lists playback started from, as the app's own pages name them. Spotify's
    // state carries a context label, but it comes back blank for some lists, and the player
    // then showed the album name instead of the playlist's (B26). A context the page does not
    // name is shown with the name the app played it from.
    private val contextNames = ConcurrentHashMap<String, String>()

    /** [name] of the list at [contextUri], for the player header and the queue sheet. */
    fun registerContextName(contextUri: String, name: String) {
        if (contextUri.isNotBlank() && name.isNotBlank()) contextNames[contextUri] = name
    }

    /** The app's name for [contextUri], "" when the app never played from it (B26). */
    fun knownContextName(contextUri: String): String = contextNames[contextUri].orEmpty()

    /** Liked Songs plays as its own context: Spotify maps it to the account's liked list. */
    fun registerLikedSongs(urls: List<String>) = registerContext(urls, LIKED_CONTEXT)

    /** [holdMs]: how long states of another song are ignored, at most, while this one comes. */
    private fun markPending(
        trackId: String,
        itemUri: String? = trackId.takeIf { it.isNotBlank() }?.let { SPOTIFY_TRACK_PREFIX + it },
        holdMs: Long = PENDING_MS,
    ) {
        pendingSkip = 0
        pendingItemUri = itemUri
        bufferingState.value = true
        itemUri?.let { boundState?.likeState?.value = library[it] ?: false }
        intendPlaying()
        pendingTrackId = trackId
        pendingUntil = SystemClock.elapsedRealtime() + holdMs
        reconcileAfter(pendingEnded, holdMs)
        playing = true
        positionMs = 0
        positionAt = SystemClock.elapsedRealtime()
    }

    /**
     * Play [song] (a play key "spotify:track:<id>|<text>" or "episode:<id>"). [contextUri] is
     * the list it was picked from; when null, the last list that registered this track is used.
     */
    /** [order]: the context's track uris in the order to play them (a sorted playlist). */
    fun playSong(song: String, context: Context? = null, contextUri: String? = null, order: List<String>? = null, positionMs: Long? = null) {
        // Data saver on mobile data: Spotify can't stream, only downloads and Local Files play.
        if (com.music.spotui.data.DataSaver.active) {
            main.post {
                android.widget.Toast.makeText(Graph.app, app.myspoti.engine.R.string.data_saver_blocked, android.widget.Toast.LENGTH_SHORT).show()
            }
            return
        }
        LocalPlayer.stop()
        cancelBurst()
        val episodeId = song.removePrefix("episode:").takeIf { song.startsWith("episode:") }
        val trackId = episodeId ?: spotifyTrackIdForPlayback(song) ?: return
        val itemUri = if (episodeId != null) "spotify:episode:$episodeId" else SPOTIFY_TRACK_PREFIX + trackId
        val ctx = contextUri ?: contextRegistry[song]
        // What a smart shuffle ending brings back: the order of the last play that was not one.
        if (ctx != null) {
            val sent = order?.takeIf { ctx == contextUri }
            playedOrder = ctx to sent
            if (com.music.spotui.ui.screens.SmartShuffle.on.value != ctx) { plainOrder = ctx to sent; smartPlayed = null }
            else if (sent != null) smartPlayed = ctx
        }
        markPending(if (episodeId != null) "" else trackId, itemUri)
        transport { isLatest ->
            playChecked(itemUri, isLatest) {
                Log.d(TAG, "playing $itemUri in ${ctx ?: "no context"}: ${queueTag()}")
                if (ctx != null) api.player.play(ctx, trackUri = itemUri, positionMs = positionMs, order = order?.takeIf { ctx == contextUri })
                else api.player.play(itemUri)
            }
        }
    }

    fun play() {
        if (LocalPlayer.active.value) { Haptics.click(); LocalPlayer.resume(); return }
        // Smart shuffle was turned off while paused: the list's order comes back as it plays on.
        plainOrderDue?.let { if (restorePlainOrder(it)) return }
        Haptics.click(); intendPlaying(); playing = true; positionAt = SystemClock.elapsedRealtime()
        // Loading until the page plays: the clock ran from the tap before, over silence when the
        // resume came late or never (reported on a slow network).
        if (api.player.state.value?.isPaused != false) bufferingState.value = true
        // Tapped on the restored mini player while the Web Player is still loading.
        if (!stateSeen) resumeWhenReady = true
        val uri = _nowPlaying.value.uri
        transport(checkAfter = true) { api.player.resume() }
        // An episode ignored by a plain resume (the page's element stays paused, which is what
        // "the podcast stopped and the play button does nothing" was — B11) is asked for again
        // by its uri, where it was. Music keeps the plain resume: it never showed that.
        if (!uri.startsWith("spotify:episode:")) return
        scope.launch {
            kotlinx.coroutines.delay(EPISODE_RESUME_CHECK_MS)
            if (!playing || LocalPlayer.active.value) return@launch
            val s = api.player.state.value
            if (s?.item?.uri == uri && s.isPaused) {
                Log.i(TAG, "episode resume not taken: playing $uri again (${queueTag()})")
                // In its show: played alone, Spotify goes on with its own autoplay after the episode.
                val ctx = _nowPlaying.value.contextUri.ifBlank { uri }
                runCatching { api.player.play(ctx, trackUri = uri.takeIf { ctx != it }, positionMs = getCurrentPosition()) }
            }
        }
    }

    fun pause() { Haptics.click(); cancelBurst(); pauseSilently() }

    private fun pauseSilently() {
        if (LocalPlayer.active.value) { LocalPlayer.pause(); return }
        intendPaused()
        positionMs = getCurrentPosition(); positionAt = SystemClock.elapsedRealtime(); playing = false
        transport(pullBack = false) { api.player.pause() }
    }

    fun stop() = pause()

    /**
     * Next, from any control: the player, the mini player, the notification, headphones, the car,
     * the widget. The song it lands on is shown at once ([showSkipTarget]) and stays shown until the
     * page plays it or the skip fails. Only the in-app buttons showed it before, for [PENDING_MS] at
     * most: on a slow network the skip took longer and the screen went back to the old song
     * (reported: "Next does not move").
     */
    fun next() {
        Log.d(TAG, "next tapped")
        Haptics.click()
        if (LocalPlayer.active.value) { LocalPlayer.next(); return }
        // Spotify is refusing licences: every skip asks for one more and makes the limit last
        // (a tester's log: 183 refusals in 8 min of fast skipping, the page then skipping on its
        // own through songs it could not play). Nothing is sent until the limit is over.
        if (MyspotiRuntime.host.licenseLimited()) {
            android.widget.Toast.makeText(Graph.app, app.myspoti.engine.R.string.spotify_limited, android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        intendPlaying()
        tapSeq++
        // The first tap skips at once. Taps that follow it within SKIP_BURST_MS, or within
        // MIN_SKIP_GAP_MS of the last skip sent, become one jump to the song they add up to: a
        // burst of "next" commands left the Web Player out of step (state conflicts, then a
        // player stuck until reloaded), and each song skipped through asks for a licence.
        val now = SystemClock.elapsedRealtime()
        val sinceSent = now - skipSentAt
        if (now - burstAt > SKIP_BURST_MS && sinceSent >= MIN_SKIP_GAP_MS) {
            burstAt = now
            burstUpcoming = apiQueue?.let { it.queued + it.nextUp }.orEmpty()
            burstTaps = 1
            pendingSkips = 0
            skipSentAt = now
            val shown = showSkipTarget()
            // The device check after the skip, as for a resume: before it, a slow network held
            // every skip for the device list's round trip (up to DEVICES_WAIT_MS).
            transport(waitReady = !stateSeen, checkAfter = true, done = { ok -> skipDone(shown, ok) }) { api.player.next() }
            return
        }
        burstAt = now
        // A burst whose first tap was held (it came too soon after the last skip sent, e.g. a
        // previous): its songs are counted from the one playing now, none of its taps sent yet.
        if (burstTaps == 0 || burstUpcoming.isEmpty()) {
            burstUpcoming = apiQueue?.let { it.queued + it.nextUp }.orEmpty()
            burstTaps = 0
        }
        burstTaps++
        pendingSkips++
        showSkipTarget()
        main.removeCallbacks(sendSkips)
        main.postDelayed(sendSkips, maxOf(SKIP_BURST_MS, MIN_SKIP_GAP_MS - sinceSent))
    }

    /** Hands out the tokens of [showSkipTarget]; 0 is never one. */
    private var skipShownSeq = 0
    /** The skip whose song is pending on screen; 0 when the pending song is not a skip's (a pick). */
    private var pendingSkip = 0

    /**
     * Shows the song the taps so far land on, before the page plays it: the song after the one
     * playing at the first tap, or [burstTaps] songs after it. -> the token [skipDone] is told
     * with, 0 when the target is unknown (the page's own states then show where it lands).
     */
    private fun showSkipTarget(): Int {
        val token = ++skipShownSeq
        // Reported (beta.9): each fast tap showed `nowPlaying.next` — the same second song every
        // time, held as pending, so the screen stayed on it for seconds while the page went on to
        // the fifth. A burst shows the song its taps add up to.
        val n = if (burstTaps <= 1) _nowPlaying.value.next
            else burstUpcoming.getOrNull(burstTaps - 1)?.let(::songOf)
        if (n == null) {
            pendingTrackId = null
            pendingItemUri = null
            pendingSkip = 0
            return 0
        }
        markPending(n.spotifyTrackId, holdMs = SKIP_PENDING_MAX_MS)
        pendingSkip = token
        boundState?.updateSongState(n.coverUri, n.title, n.singer, true, n.id, 0, n.album)
        durationMs = n.durationMs.toLong()
        pushNotification(n, false)
        return token
    }

    /**
     * The skip shown as [token] was answered ([ok]): the page now has [SKIP_ANSWERED_MS] to report the
     * song. Not answered (failed, timed out, dropped): the screen goes back at once to what the
     * page really plays, instead of showing a song nothing is going to play. A newer skip or a
     * pick shown since owns the screen, and is left alone.
     */
    private fun skipDone(token: Int, ok: Boolean) {
        if (token == 0 || token != pendingSkip || pendingTrackId == null) return
        if (ok) {
            val now = SystemClock.elapsedRealtime()
            pendingUntil = minOf(pendingUntil, now + SKIP_ANSWERED_MS)
            reconcileAfter(pendingEnded, pendingUntil - now)
            return
        }
        Log.w(TAG, "skip not taken by the page: the screen shows what it plays")
        pendingSkip = 0
        pendingTrackId = null
        pendingItemUri = null
        applyState(api.player.state.value)
    }

    /** When the last skip command left (see [next]). */
    private var skipSentAt = 0L

    /** When the last "next" tap came, the queue as it was at the first one, and the taps since. */
    private var burstAt = 0L
    private var burstUpcoming: List<app.myspoti.api.NowPlaying> = emptyList()
    private var burstTaps = 0
    private var pendingSkips = 0
    private val sendSkips = Runnable {
        if (pendingSkips <= 0) return@Runnable
        pendingSkips = 0
        skipSentAt = SystemClock.elapsedRealtime()
        // The song [burstTaps] after the one playing at the first tap.
        val target = burstUpcoming.getOrNull(burstTaps - 1)
        val shown = pendingSkip
        transport(waitReady = !stateSeen, checkAfter = true, done = { ok -> skipDone(shown, ok) }) {
            if (target == null) api.player.next() else api.player.skipTo(target)
        }
        if (target != null) {
            burstTarget = target
            burstContext = _nowPlaying.value.contextUri
            burstSeq++
            verifyTap = tapSeq
            main.removeCallbacks(verifyBurst)
            main.postDelayed(verifyBurst, BURST_VERIFY_MS)
        }
    }

    /*
     * Measured on the test phone: the burst's first "next" is acknowledged by the page, applied
     * a second late, after the jump to the target — the page then lands one song past it (the
     * screen showed the target, the speaker the song after). Where the burst landed is checked,
     * and the target played by name when it is not there.
     */
    private var burstTarget: app.myspoti.api.NowPlaying? = null
    private var burstContext = ""
    private var burstSeq = 0
    private var lastPageUri = ""

    /** Bumped by every user transport action (tap or pick); [verifyBurst] only runs if it did not move since it was scheduled. */
    private var tapSeq = 0
    private var verifyTap = 0
    private const val BURST_VERIFY_MS = 2_500L
    private val verifyBurst = Runnable {
        val t = burstTarget ?: return@Runnable
        burstTarget = null
        val seq = burstSeq
        val tap = verifyTap
        // A newer tap, pick or pause: the user moved on (a reported log: a previous pressed
        // 1 s after the burst was undone by this check, which played the burst's song again).
        if (tap != tapSeq || SystemClock.elapsedRealtime() - burstAt < BURST_VERIFY_MS || !playing) return@Runnable
        if (lastPageUri == t.uri || burstContext.isBlank()) return@Runnable
        Log.d(TAG, "skips landed on $lastPageUri, not ${t.name}: playing it (${queueTag()})")
        // In the order it was played in (a sorted list, a smart shuffle), and a song the user
        // queued is not in the list: asking for it there would restart the list.
        if (t.provider == "queue") return@Runnable
        val order = playedOrder?.takeIf { it.first == burstContext }?.second
        transport { if (seq == burstSeq && tap == tapSeq) api.player.play(burstContext, t.uri, order = order) }
    }

    /**
     * The user does something else than "next" (a pick, a pause, a seek, a previous): whatever
     * the burst still had to send or check is dropped, or it would undo that action (reported:
     * a previous after two nexts was overridden by the burst's jump, then by its check).
     */
    private fun cancelBurst() {
        tapSeq++
        pendingSkips = 0
        burstTarget = null
        burstTaps = 0
        burstAt = 0L
        burstUpcoming = emptyList()
        main.removeCallbacks(sendSkips)
        main.removeCallbacks(verifyBurst)
    }

    /** "shuffle=…, next=[…]": the page's shuffle flag and the first upcoming songs, for the play logs. */
    private fun queueTag(): String {
        val up = apiQueue?.let { it.queued + it.nextUp }.orEmpty().take(5).joinToString { it.name.take(30) }
        return "shuffle=${api.player.state.value?.shuffle} next=[$up]"
    }

    fun previous() {
        Haptics.click()
        if (LocalPlayer.active.value) { LocalPlayer.previous(); return }
        intendPlaying()
        // A previous counts as -1 in the burst. Skips still waiting to be sent (nexts tapped
        // within SKIP_BURST_MS) just lose one: next, next, previous is one next, and nothing
        // is sent for the previous. Once the burst has left (or there is none), previous is a
        // plain one, and the burst is forgotten so it cannot send or verify anything later.
        if (pendingSkips > 0) {
            pendingSkips--
            burstTaps--
            tapSeq++
            burstAt = SystemClock.elapsedRealtime()
            if (pendingSkips == 0) main.removeCallbacks(sendSkips)
            return
        }
        cancelBurst()
        skipSentAt = SystemClock.elapsedRealtime()
        transport(waitReady = !stateSeen, checkAfter = true) { api.player.previous() }
    }

    /** Tracks shufflePlay plays without a context: the first one, plus up to 49 queued. */
    private const val SHUFFLE_QUEUE_MAX = 50
    /** After pulling playback back, the page needs a moment before it takes commands. */
    private const val PULL_BACK_SETTLE_MS = 600L
    private const val PULL_BACK_WAIT_MS = 6_000L
    private const val DEVICES_WAIT_MS = 2_000L

    /**
     * How long the device list read by [takeBackPlayback] is trusted. Long enough that a burst of
     * taps pays for it once, short enough that a device taking playback in between is noticed.
     */
    private const val DEVICES_FRESH_MS = 10_000L
    /** A transport command waits that long for the page to load before it is dropped. */
    private const val READY_WAIT_MS = 30_000L

    /** Shuffle-play a list: Spotify picks the first track of its own shuffled context. */
    fun shufflePlay(songs: List<SongsModel>) {
        if (songs.isEmpty()) return
        LocalPlayer.stop()
        val ctx = contextRegistry[songs.first().url]
        cancelBurst()
        intendPlaying()
        transport {
            api.player.setShuffle(true)
            Log.d(TAG, "shuffle-playing ${ctx ?: "a shuffled list"}: ${queueTag()}")
            if (ctx == null) {
                // No registered context: play the list in a shuffled order (first
                // track now, the rest queued), not one random song alone. Capped at
                // SHUFFLE_QUEUE_MAX tracks: the queue is the user's Spotify queue,
                // which outlives this list and follows them to every device, so
                // queuing all of Liked Songs would bury whatever they play next.
                val order = songs.shuffled().take(SHUFFLE_QUEUE_MAX)
                api.player.play(order.first().url)
                if (order.size > 1) api.player.addToQueue(order.drop(1).map { it.url })
            } else api.player.play(ctx)
            // A play command may reset the options: make sure shuffle sticks.
            kotlinx.coroutines.delay(1500)
            api.player.setShuffle(true)
        }
    }

    /** The list and the order of the last play that was not a smart shuffle's (null: the list's own). */
    @Volatile private var plainOrder: Pair<String, List<String>?>? = null
    /** The list a smart shuffle is playing as its own mixed order. */
    @Volatile private var smartPlayed: String? = null
    /** Smart shuffle ended on this list while paused: its order comes back at the next play. */
    @Volatile private var plainOrderDue: String? = null

    /** The list and order of the last play, kept for the replays that name a song only. */
    @Volatile private var playedOrder: Pair<String, List<String>?>? = null

    private fun endSmartShuffle(ctx: String) {
        if (smartPlayed != ctx) return
        smartPlayed = null
        if (_nowPlaying.value.contextUri != ctx) return
        if (playing) restorePlainOrder(ctx) else plainOrderDue = ctx
    }

    /** Plays the song now playing again in [ctx] as it was before the smart shuffle, where it is. */
    private fun restorePlainOrder(ctx: String): Boolean {
        plainOrderDue = null
        val uri = _nowPlaying.value.uri
        if (_nowPlaying.value.contextUri != ctx || uri.isBlank() || LocalPlayer.active.value) return false
        val song = if (uri.startsWith("spotify:episode:")) "episode:" + uri.substringAfterLast(':') else uri
        Log.d(TAG, "smart shuffle off: $ctx back in the list's order from ${_nowPlaying.value.uri}")
        playSong(song, contextUri = ctx, order = plainOrder?.takeIf { it.first == ctx }?.second, positionMs = getCurrentPosition())
        return true
    }

    fun setShuffle(on: Boolean) {
        Haptics.toggle(on)
        if (LocalPlayer.active.value) { LocalPlayer.setShuffle(on); return }
        shuffleWanted = on
        shuffleWantedUntil = SystemClock.elapsedRealtime() + SHUFFLE_HOLD_MS
        command { api.player.setShuffle(on) }
    }

    // Reported: shuffle out of step between the list and the player. A state the page sent
    // before it took the tap flipped the button back, and the two screens then disagreed; the
    // value asked for holds until the page reports it (or for SHUFFLE_HOLD_MS).
    @Volatile private var shuffleWanted = false
    @Volatile private var shuffleWantedUntil = 0L
    private const val SHUFFLE_HOLD_MS = 2_500L

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
    fun setSpeed(speed: Double) {
        _speed.value = speed
        command { api.player.setSpeed(speed) }
    }

    private val _speed = mutableStateOf(1.0)
    /** The speed Spotify reports for what plays (podcasts only); 1.0 for music. */
    val speed: State<Double> get() = _speed

    /** Lands within the episode when skipping: Spotify refuses a position past the end. */
    fun skipBy(deltaMs: Long) {
        val duration = getDuration()
        val target = (getCurrentPosition() + deltaMs).coerceAtLeast(0L)
        seekTo(if (duration > 0) target.coerceAtMost(duration - 1_000L) else target)
    }

    /** "Add to queue" for a podcast episode (its uri, not a track id). */
    fun addEpisodeToQueue(url: String) {
        val id = url.removePrefix("episode:").takeIf { url.startsWith("episode:") } ?: return
        Haptics.confirm()
        command { api.player.addToQueue(listOf("spotify:episode:$id")) }
    }

    /**
     * What plays is a podcast episode: the player shows its own controls (30-second-free
     * skips, speed, no shuffle), not the music ones.
     */
    val isEpisode: Boolean get() = _nowPlaying.value.uri.startsWith("spotify:episode:")

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

    /** Confirmed by Spotify less than that long ago: not asked for again (see [libraryContains]). */
    private const val SAVED_FRESH_MS = 60_000L

    /** When Spotify (or the user's own tap) last settled each uri. */
    private val savedAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** One request in flight per uri list, so two callers asking share it. */
    private val savedFlights =
        java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Deferred<List<Boolean>?>>()
    private val savedFlightLock = kotlinx.coroutines.sync.Mutex()

    // The user's last like/unlike wins briefly over states that were already in flight.
    @Volatile private var intentUri = ""
    @Volatile private var intentValue = false
    @Volatile private var intentUntil = 0L

    private fun likedFor(uri: String, reported: Boolean): Boolean =
        if (uri == intentUri && SystemClock.elapsedRealtime() < intentUntil) intentValue else reported

    private fun onLibraryItem(uri: String, saved: Boolean) {
        if (uri == intentUri && SystemClock.elapsedRealtime() < intentUntil && saved != intentValue) return
        library[uri] = saved
        savedAt[uri] = SystemClock.elapsedRealtime()
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

    /** The in-app swipe and button: [next], which shows the song it lands on at once. */
    fun swipeNext() = next()

    /**
     * A seek the page has not shown yet: the song, the position asked for, when it was asked and
     * until when it holds. States of that song from before the seek (the old position) do not move
     * the clock back meanwhile. Reported: the bar dragged to a new place jumped back, the song
     * playing on from where it was — a state still on the old position, taken over the seek.
     */
    @Volatile private var seekUri = ""
    @Volatile private var seekTarget = 0L
    @Volatile private var seekAt = 0L
    @Volatile private var seekUntil = 0L
    private var seekJob: kotlinx.coroutines.Job? = null
    private var seekSeq = 0
    private val seekEnded = Runnable { reconcile() }

    /** [s] is the song a seek was asked on, somewhere it can have got to since. */
    private fun seekLanded(s: PlayerState): Boolean {
        if (s.item?.uri != seekUri) return false
        val at = s.positionAt(System.currentTimeMillis())
        val played = SystemClock.elapsedRealtime() - seekAt
        return at >= seekTarget - SEEK_NEAR_MS && at <= seekTarget + played + SEEK_NEAR_MS
    }

    private fun seekHeld(uri: String) = uri == seekUri && SystemClock.elapsedRealtime() < seekUntil

    fun seekTo(ms: Long) {
        if (LocalPlayer.active.value) { LocalPlayer.seekTo(ms); return }
        cancelBurst()
        val now = SystemClock.elapsedRealtime()
        positionMs = ms
        positionAt = now
        seekUri = _nowPlaying.value.uri
        seekTarget = ms
        seekAt = now
        seekUntil = now + SEEK_HOLD_MS
        reconcileAfter(seekEnded, SEEK_HOLD_MS)
        // The newest seek only: a drag that ends while the last one is still on its way replaces it.
        val seq = ++seekSeq
        seekJob?.cancel()
        seekJob = scope.launch {
            try {
                // Sent once more when the page does not show it (busy, a slow network); after that
                // the page's word stands, wherever it is.
                repeat(2) { attempt ->
                    runCatching { kotlinx.coroutines.withTimeoutOrNull(SEEK_SEND_MS) { api.player.seek(ms) } }
                        .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it; Log.w(TAG, "seek failed: ${it.message}") }
                    val landed = kotlinx.coroutines.withTimeoutOrNull(SEEK_CHECK_MS) {
                        api.player.state.first { it != null && (it.item?.uri != seekUri || seekLanded(it)) }
                    }
                    if (landed != null) return@launch
                    Log.w(TAG, "seek to $ms not taken by the page${if (attempt == 0) ": sent again" else ""}")
                }
            } finally {
                if (seq == seekSeq) {
                    seekUntil = 0L
                    main.removeCallbacks(seekEnded)
                    main.post { reconcile() }
                }
            }
        }
    }

    /** Which of [uris] are saved in the user's library (Liked Songs / saved albums…). */
    suspend fun libraryContains(uris: List<String>): Set<String> {
        if (uris.isEmpty()) return emptySet()
        val now = SystemClock.elapsedRealtime()
        // A page prefetch and the page opening ask for the same list within a second, and the
        // rows of a list ask again for what the list just confirmed: only what is unknown (or
        // old enough to have changed elsewhere) goes to Spotify.
        val (known, unknown) = uris.partition { library.containsKey(it) && now - (savedAt[it] ?: 0L) < SAVED_FRESH_MS }
        val out = known.filterTo(mutableSetOf()) { library[it] == true }
        if (unknown.isEmpty()) return out
        val flags = savedFlags(unknown) ?: return out
        val saved = unknown.filterIndexed { i, _ -> flags.getOrNull(i) == true }.toSet()
        main.post {
            for (u in unknown) {
                if (u == intentUri && SystemClock.elapsedRealtime() < intentUntil) continue
                library[u] = u in saved
                savedAt[u] = now
            }
        }
        return out + saved
    }

    /** [uris] in one request; two callers asking the same list share that one request. */
    private suspend fun savedFlags(uris: List<String>): List<Boolean>? {
        val key = uris.sorted().joinToString("\n")
        val flight = savedFlightLock.withLock {
            savedFlights[key] ?: scope.async {
                try {
                    api.library.contains(uris)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
            }.also { d ->
                savedFlights[key] = d
                d.invokeOnCompletion { savedFlights.remove(key, d) }
            }
        }
        return try {
            flight.await()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    /** Bumped at every like / unlike of a track Spotify took: the Liked Songs page refreshes its top. */
    val likedChanges = kotlinx.coroutines.flow.MutableStateFlow(0)

    /** Every like / unlike of a track the moment it is tapped (and its undo): track id, liked. */
    val likedTaps = kotlinx.coroutines.flow.MutableSharedFlow<Pair<String, Boolean>>(extraBufferCapacity = 16)

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
        if (track) likedTaps.tryEmit(trackIdOf(uri) to on)
        val write = libraryWrites.incrementAndGet()
        scope.launch {
            // One write at a time; a refused one is undone on screen if no newer tap replaced it.
            libraryLock.withLock {
                runCatching {
                    if (uri.startsWith("spotify:playlist:")) {
                        if (on) api.playlists.follow(uri) else api.playlists.unfollow(uri)
                    } else {
                        if (on) api.library.add(listOf(uri)) else api.library.remove(listOf(uri))
                    }
                }.onSuccess {
                    if (track) likedChanges.value++
                }.onFailure {
                    Log.w(TAG, "library change failed: ${it.message}")
                    if (write == libraryWrites.get()) {
                        intentUntil = 0
                        onLibraryItem(uri, !on)
                        if (track) likedTaps.tryEmit(trackIdOf(uri) to !on)
                        android.widget.Toast.makeText(Graph.app, app.myspoti.engine.R.string.library_change_failed, android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }
    private val libraryLock = kotlinx.coroutines.sync.Mutex()
    private val libraryWrites = java.util.concurrent.atomic.AtomicInteger()

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

    /** The other device picked in Myspoti to play on; null: this phone. */
    @Volatile private var chosenRemote: String? = null

    /**
     * Another device took playback without being picked here (the Spotify app opened on the
     * same account, for instance): Myspoti's play, skip and previous then drove that device,
     * where a free account's commands are refused. The music comes back to this phone first.
     */
    /** When [takeBackPlayback] last read the device list; 0 = never read (read it now). */
    @Volatile private var devicesFreshUntil = 0L

    private suspend fun takeBackPlayback() {
        // The device list as Spotify has it now: the one kept here is only refreshed on a song
        // change, and after a cold start it does not know a device that took playback before.
        // But this used to run on EVERY tap, i.e. a server round trip before the song was even
        // asked for: measured 963 ms from tap to sound against 554 ms for a skip, which takes the
        // same playback path without this call. The list is read again only once the entry is
        // older than [DEVICES_FRESH_MS] — a takeover within those seconds is still caught by the
        // play check (the page does not move, the play is sent again) and by the next read.
        // Measured (15/10 run): this refresh answers from the page's cache in 8-36 ms, so it is not
        // what makes a tap slower than a skip (963 vs 554 ms then) — keeping it unconditional keeps
        // "a device that took playback is pulled back at once" (beta.7 behaviour). The timing stays
        // in the log because the day it becomes slow, that line says so.
        devicesFreshUntil = 0L
        val started = System.currentTimeMillis()
        kotlinx.coroutines.withTimeoutOrNull(DEVICES_WAIT_MS) { runCatching { api.devices.refresh() } }
        Log.i(TAG, "device list read in ${System.currentTimeMillis() - started} ms")
        val active = api.devices.devices.value.firstOrNull { it.active }
        _activeDevice.value = active?.takeIf { !it.local }?.let { ConnectDevice(it.id, it.name, it.type, false, true) }
        val remote = _activeDevice.value ?: return
        if (remote.id == chosenRemote) return
        Log.i(TAG, "playback taken by ${remote.name}: pulled back here")
        // A pull-back can land on this phone at 0:00 (reported: "sometimes on Connect the sound
        // starts over"). The position is remembered across the pull and restored only when the same
        // item really rolled back — a new play right after this call asks for its own position.
        val before = api.player.state.value?.let { s -> s.item?.uri?.let { uri -> uri to s.positionAt(System.currentTimeMillis()) } }
        // Bounded: a transfer from a device that went away can leave the page's promise
        // pending for ever, and every later command waiting behind it.
        val pulled = kotlinx.coroutines.withTimeoutOrNull(PULL_BACK_WAIT_MS) { runCatching { api.devices.pullHere() } }
        // Whatever happened, the list just changed under us: read it again next time.
        devicesFreshUntil = 0L
        if (pulled?.isSuccess != true) Log.w(TAG, "pull here failed: ${pulled?.exceptionOrNull()?.message ?: "no answer"}")
        // No answer: the page's Connect layer waits on that device and would hold every later
        // command. A new page claims playback for itself at the first command.
        if (pulled == null) {
            Log.w(TAG, "pull here hangs: player page reloaded")
            MyspotiRuntime.spotify.player.reloadPage()
        }
        _activeDevice.value = null
        PlaybackService.setPlaybackDevice(false, remoteVolume)
        kotlinx.coroutines.delay(PULL_BACK_SETTLE_MS)
        // Same item but an earlier position: the page restarted it. Put the listener back where they
        // were. Anything else (a different item, a position that did not move back) is left alone.
        before?.let { (uri, pos) ->
            val now = api.player.state.value
            val at = now?.positionAt(System.currentTimeMillis()) ?: return@let
            if (now.item?.uri == uri && pos > 3_000 && at < pos - 3_000) {
                Log.i(TAG, "playback came back at $at ms on the same item: back to $pos ms")
                runCatching { api.player.seek(pos) }
            } else {
                Log.i(TAG, "playback came back at $at ms (item ${now.item?.uri})")
            }
        }
    }

    /** Move playback to [device] (Spotify Connect transfer). */
    fun transferTo(device: ConnectDevice) {
        Haptics.confirm()
        chosenRemote = if (device.isSelf) null else device.id
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
        main.removeCallbacks(endOfTrackWatch)
        sleepTrackId = null
        when {
            minutes == null -> _sleepEndsAt.value = null
            minutes == 0 -> {
                // Any source (song, episode, local file, repeat one): the uri and the clock.
                sleepTrackId = _nowPlaying.value.uri.ifBlank { currentTrackId }
                _sleepEndsAt.value = -1L
                main.removeCallbacks(endOfTrackWatch); main.post(endOfTrackWatch)
            }
            else -> {
                _sleepEndsAt.value = SystemClock.elapsedRealtime() + minutes * 60_000L
                main.postDelayed(sleepRunnable, minutes * 60_000L)
            }
        }
    }

    private fun checkEndOfTrackTimer(@Suppress("UNUSED_PARAMETER") newTrackId: String) {
        // Done by [endOfTrackWatch], which pauses before the next item starts.
    }

    /** "End of track": pauses just before the item ends, or as soon as another one plays. */
    private val endOfTrackWatch = object : Runnable {
        override fun run() {
            val t = sleepTrackId ?: return
            val uri = _nowPlaying.value.uri.ifBlank { currentTrackId }
            val duration = getDuration()
            val moved = uri.isNotBlank() && uri != t
            val atEnd = duration > 0 && isPlaying() && getCurrentPosition() >= duration - END_OF_TRACK_MARGIN_MS
            if (moved || atEnd) {
                sleepTrackId = null
                _sleepEndsAt.value = null
                pauseSilently()
                return
            }
            main.postDelayed(this, 250)
        }
    }

    // ── Queue (the API pushes every change, nothing to poll) ──
    fun watchQueue(on: Boolean) {}
    fun requestQueue() {}

    /**
     * The queue row [item] stands for in Spotify's latest queue: by its row id, else by its song
     * (a row moved a moment ago got a new id, and the list on screen may still show the old one).
     */
    private fun apiItem(item: QueueItem): app.myspoti.api.NowPlaying? =
        apiQueue?.let { q ->
            val all = q.queued + q.nextUp
            all.firstOrNull { it.uri == item.uri && (item.uid.isBlank() || it.uid == item.uid) } ?: all.firstOrNull { it.uri == item.uri }
        }

    /**
     * Queue edits, one at a time and each after Spotify has shown the one before. Sent together
     * they raced in the Web Player: "move up into the queue" (add + remove) lost its remove and
     * left the song twice, and that copy could then not be moved or taken out (reported).
     */
    private val queueLock = kotlinx.coroutines.sync.Mutex()
    private fun queueCommand(block: suspend () -> Unit) {
        scope.launch { queueLock.withLock { runCatching { block() }.onFailure { Log.w(TAG, "queue edit failed: ${it.message}") } } }
    }

    private const val QUEUE_ECHO_MS = 2_500L

    /** Waits (at most [QUEUE_ECHO_MS]) for Spotify's queue to show [done]; the queue then, or null. */
    private suspend fun awaitQueue(done: (app.myspoti.api.Queue) -> Boolean): app.myspoti.api.Queue? =
        kotlinx.coroutines.withTimeoutOrNull(QUEUE_ECHO_MS) { api.player.queue.first { it != null && done(it) } }

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
        if (item.provider == LOCAL) { item.uid.toIntOrNull()?.let(LocalPlayer::skipTo); return }
        cancelBurst()
        val id = trackIdOf(item.uri)
        if (id.isNotBlank()) markPending(id)
        boundState?.updateSongState(item.song.coverUri, item.song.title, item.song.singer, true, item.song.id, 0, item.song.album)
        durationMs = item.song.durationMs.toLong()
        pushNotification(item.song, false)
        val target = apiItem(item) ?: return
        transport(waitReady = !stateSeen) { api.player.skipTo(target) }
    }

    /** "Clear queue": drops the tracks the user queued (not the context's next ones). */
    fun clearQueue() {
        _queue.value = _queue.value.copy(queued = emptyList())
        command { api.player.clearQueue() }
    }

    /** Move [item] just before / after [target] (same section of the queue). */
    fun reorder(item: QueueItem, target: QueueItem, after: Boolean) {
        if (item.provider == LOCAL) {
            val from = item.uid.toIntOrNull() ?: return
            val t = target.uid.toIntOrNull() ?: return
            LocalPlayer.move(from, if (after) (if (from < t) t else t + 1) else (if (from < t) t - 1 else t))
            return
        }
        // Read when the edit runs, after the ones before it: never a row id that has gone stale.
        queueCommand {
            val a = apiItem(item) ?: return@queueCommand
            val b = apiItem(target) ?: return@queueCommand
            val before = api.player.queue.value
            api.player.moveInQueue(a, b, after)
            awaitQueue { it !== before }
        }
    }

    /** Move a "next up" track into the queue, right after the current one. */
    fun moveToQueueFront(item: QueueItem) = moveIntoQueue(item, null, after = false)

    /**
     * A "next up" track moved up into the queue, before / after [target] (a queued row; null: first).
     * As the official app: out of "next up" first, then queued — in that order and one after the
     * other (both at once left it in both places), then placed where it was dropped.
     */
    fun moveIntoQueue(item: QueueItem, target: QueueItem?, after: Boolean) {
        if (item.provider == LOCAL) { item.uid.toIntOrNull()?.let(LocalPlayer::moveNext); return }
        queueCommand {
            val a = apiItem(item) ?: return@queueCommand
            val first = apiQueue?.queued?.firstOrNull()
            api.player.removeFromQueue(listOf(a))
            awaitQueue { q -> q.nextUp.none { it.uid == a.uid } }
            api.player.addToQueue(listOf(a.uri))
            val added = awaitQueue { q -> q.queued.any { it.uri == a.uri } }?.queued?.lastOrNull { it.uri == a.uri } ?: return@queueCommand
            // Queued at the end: moved to the place it was dropped (the front when no target).
            val t = target?.let(::apiItem) ?: first?.takeIf { it.uid != added.uid } ?: return@queueCommand
            val before = api.player.queue.value
            api.player.moveInQueue(added, t, if (target == null) false else after)
            awaitQueue { it !== before }
        }
    }

    fun removeFromQueue(item: QueueItem) {
        if (item.provider == LOCAL) { item.uid.toIntOrNull()?.let(LocalPlayer::remove); return }
        _queue.value = _queue.value.let { q -> q.copy(queued = q.queued - item, nextUp = q.nextUp - item) }
        queueCommand {
            val a = apiItem(item) ?: return@queueCommand
            api.player.removeFromQueue(listOf(a))
            awaitQueue { q -> (q.queued + q.nextUp).none { it.uid == a.uid && it.uri == a.uri } }
        }
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
        localStartedAt = SystemClock.elapsedRealtime()
        engineUriBeforeLocal = _nowPlaying.value.uri
        engineRepaused = false
        if (playing) command { api.player.pause() }
        playing = false
        // The local player keeps the output open itself; the engine's states, which
        // would close the silent stream, are ignored while it plays.
        SpeakerKeepAlive.set(false)
    }

    private var localStartedAt = 0L
    /** What the engine played when the local file started (to tell a pause that did not take from a takeover). */
    private var engineUriBeforeLocal = ""
    private var engineRepaused = false

    /**
     * The engine reports playing while a local file plays. Two streams must never play
     * at once: a pause that did not take (the engine was not ready) is sent once more;
     * anything else is Spotify taking over (a Connect "play on this phone", a resume from
     * another device), and the local player yields. True when the state is to be ignored.
     */
    private fun engineWhileLocal(s: PlayerState): Boolean {
        if (s.isPaused) return true
        // The page's last states from before the pause reached it.
        if (SystemClock.elapsedRealtime() - localStartedAt < LOCAL_GRACE_MS) return true
        if (s.item?.uri == engineUriBeforeLocal && !engineRepaused) {
            engineRepaused = true
            Log.i(TAG, "engine still playing under a local file: pausing it again")
            command { api.player.pause() }
            return true
        }
        Log.i(TAG, "engine started playing: the local player yields")
        LocalPlayer.stop()
        return false
    }

    /** The local player's track / state, shown like any other (player, mini player, notification). */
    fun onLocalState(t: com.music.spotui.data.local.LocalTrack, isPlaying: Boolean, pos: Long, dur: Long) {
        main.post {
            currentTrackId = ""
            playing = isPlaying; positionMs = pos; positionAt = SystemClock.elapsedRealtime(); durationMs = dur
            bufferingState.value = false
            val uri = "local:${t.id}"
            if (_nowPlaying.value.uri != uri) _nowPlaying.value = NowPlaying(uri = uri, contextName = LocalPlayer.contextName)
            val song = localSong(t, dur)
            boundState?.let { st ->
                st.likeState.value = false
                st.updateSongState(song.coverUri, song.title, song.singer, isPlaying, song.id, 0, song.album)
            }
            pushNotification(song, false)
            val source = if (t.uri.scheme == "file") com.music.spotui.data.local.ListenHistory.Source.DOWNLOAD
                else com.music.spotui.data.local.ListenHistory.Source.LOCAL
            trackListen(uri, song, source, isPlaying)
        }
    }

    private fun localSong(t: com.music.spotui.data.local.LocalTrack, dur: Long = t.durationMs) =
        SongsModel(id = ("local:" + t.id).hashCode() and 0x7fffffff, title = t.title, album = t.album,
            singer = t.artist, coverUri = t.artUri.toString(), url = "local:${t.uri}", durationMs = dur.toInt())

    /** The local player's queue: [upcoming] are the songs after [current], with their list index as uid. */
    fun onLocalQueue(current: com.music.spotui.data.local.LocalTrack, upcoming: List<Pair<Int, com.music.spotui.data.local.LocalTrack>>) {
        main.post {
            if (!LocalPlayer.active.value) return@post
            _queue.value = EngineQueue(
                current = localSong(current),
                nextUp = upcoming.map { (i, t) -> QueueItem(localSong(t), "local:${t.id}", i.toString(), LOCAL) },
            )
        }
    }

    /** True while a crossfade is fading this song out, or the next one in (see [SpotifyPlayer]). */
    fun isCrossfadeActive(): Boolean =
        MyspotiRuntime.isStarted && MyspotiRuntime.spotify.player.isCrossfading()
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
