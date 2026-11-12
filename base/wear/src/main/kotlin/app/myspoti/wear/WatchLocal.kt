package app.myspoti.wear

import android.content.Context
import app.myspoti.api.sync.LocalNow
import android.net.Uri
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import app.myspoti.extensions.AudioStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * A list played on the watch from its own extensions ([WatchExtensions]): each song's audio is
 * asked as it comes (the next one while the current one plays) and played by a plain player,
 * straight from the network. A song no extension has is passed; after a few in a row the list stops.
 */
object WatchLocal {
    private const val TAG = "Orpheon"
    private const val MAX_MISSES = 5

    class Now(val name: String, val artists: String, val playing: Boolean, val source: String, val cover: String? = null)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var player: ExoPlayer? = null
    private var tracks: List<WatchTrack> = emptyList()
    private var index = 0
    private var source = ""
    private var ahead: Deferred<Pair<AudioStream, String>?>? = null
    private var aheadIndex = -1
    private lateinit var app: Context

    private val _now = MutableStateFlow<Now?>(null)
    /** The song played from an extension; null when the watch plays from the account (or nothing). */
    val now: StateFlow<Now?> = _now
    val active: Boolean get() = _now.value != null

    /** The list being played ([play]'s), for the account's view of the song's context. */
    private var listUri = ""

    /** Plays [list] (the list [uri]) from [start], whose audio [first] was already found, from [positionMs]. */
    fun play(context: Context, list: List<WatchTrack>, start: Int, first: Pair<AudioStream, String>, uri: String = "", positionMs: Long = 0L) {
        app = context.applicationContext
        WatchSync.init(app)
        tracks = list; index = start; listUri = uri
        load(first, positionMs)
    }

    private fun ensurePlayer(): ExoPlayer = player ?: ExoPlayer.Builder(app).build().also { p ->
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) { if (state == Player.STATE_ENDED) move(1) else WatchSync.onLocal() }
            override fun onIsPlayingChanged(isPlaying: Boolean) { publish(); report(); WatchSync.onLocal() }
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) { WatchSync.onLocal() }
            override fun onPositionDiscontinuity(old: Player.PositionInfo, new: Player.PositionInfo, reason: Int) {
                if (reason == Player.DISCONTINUITY_REASON_SEEK) { report(); WatchSync.onLocal() }
            }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) { Log.w(TAG, "watch player: ${error.message}"); move(1) }
        })
        player = p
    }

    private fun load(found: Pair<AudioStream, String>, positionMs: Long = 0L) {
        val (stream, name) = found
        source = stream.qualityLabel()?.let { "$name · $it" } ?: name
        val p = ensurePlayer()
        p.setMediaSource(sourceOf(stream), positionMs)
        p.prepare(); p.play()
        publish(playing = true)
        report()
        WatchSync.onLocal()
        WatchPlaybackService.follow(app, true)
        // The next song's audio, asked while this one plays.
        aheadIndex = index + 1
        ahead = tracks.getOrNull(aheadIndex)?.let { t -> scope.async(Dispatchers.IO) { WatchExtensions.audio(t, ahead = true) } }
    }

    private fun sourceOf(s: AudioStream): MediaSource {
        val http = DefaultHttpDataSource.Factory().setDefaultRequestProperties(s.headers).setAllowCrossProtocolRedirects(true)
        val data = DefaultDataSource.Factory(app, http)
        return if (s.manifest != null) {
            // A DASH manifest the extension gave whole: read from a data: URI.
            DashMediaSource.Factory(data).createMediaSource(
                MediaItem.Builder().setUri(Uri.parse("data:application/dash+xml;base64," + s.manifest)).setMimeType(MimeTypes.APPLICATION_MPD).build())
        } else ProgressiveMediaSource.Factory(data).createMediaSource(MediaItem.fromUri(s.url))
    }

    /** To the song [by] places away whose audio an extension has (passing those none has). */
    private fun move(by: Int) {
        scope.launch {
            var misses = 0
            var i = index + by
            while (i in tracks.indices && misses < MAX_MISSES) {
                val found = if (i == aheadIndex) ahead?.await() else WatchExtensions.audio(tracks[i])
                if (found != null) { index = i; load(found); return@launch }
                misses++; i += if (by < 0) -1 else 1
            }
            stop()
        }
    }

    private fun publish(playing: Boolean = player?.isPlaying == true) {
        val t = tracks.getOrNull(index) ?: return
        _now.value = Now(t.name, t.artists.joinToString(", "), playing, source, t.cover)
    }

    fun toggle() { player?.let { if (it.playWhenReady) it.pause() else it.play() }; publish() }
    fun pause() { player?.pause(); publish(false) }
    fun resume() { player?.play(); publish() }
    fun seekTo(ms: Long) { player?.seekTo(ms) }
    fun next() = move(1)
    fun previous() { val p = player; if (p != null && p.currentPosition > 3_000) p.seekTo(0) else move(-1) }

    fun stop() {
        player?.release(); player = null
        ahead?.cancel(); ahead = null
        if (_now.value != null) {
            _now.value = null
            if (::app.isInitialized) WatchPlaybackService.follow(app, false)
            report(stopped = true)
            WatchSync.stopped("the watch's player stopped")
        }
    }

    /** The song and its state as the listen mirror sees it ([WatchSync]); null with nothing loaded. */
    fun localNow(): LocalNow? {
        val p = player ?: return null
        val t = tracks.getOrNull(index) ?: return null
        val duration = p.duration.takeIf { it > 0 } ?: t.durationMs
        return LocalNow(
            track = WatchSync.trackOf(t), live = true,
            playing = p.playWhenReady, positionMs = p.currentPosition, durationMs = duration,
            knownLengthMs = p.duration.takeIf { it > 0 }, buffering = p.playbackState == Player.STATE_BUFFERING, audible = p.isPlaying,
            previous = tracks.getOrNull(index - 1)?.let(WatchSync::trackOf), next = tracks.getOrNull(index + 1)?.let(WatchSync::trackOf),
            contextUri = listUri,
        )
    }

    /** The songs after the current one, in order. */
    fun queued(): List<WatchTrack> = tracks.drop(index + 1)

    /**
     * The song and its state told to the phone, on each change only (the latest wins; a phone out of
     * reach is simply not told): the phone's listen mirror counts it on the account when it is on.
     */
    private fun report(stopped: Boolean = false) {
        if (!::app.isInitialized) return
        // The watch counts its songs itself ([WatchSync]); the phone only while the engine cannot.
        val relay = WatchSync.relays
        if (!relay && !relayed) return
        // Relayed before and not now: the phone is told the song stopped, once.
        val stop = stopped || !relay
        relayed = !stop
        val p = player
        val t = tracks.getOrNull(index)
        val body = if (stop || p == null || t == null) "{\"stopped\":true}" else kotlinx.serialization.json.buildJsonObject {
            put("uri", kotlinx.serialization.json.JsonPrimitive(t.uri)); put("name", kotlinx.serialization.json.JsonPrimitive(t.name))
            put("artists", kotlinx.serialization.json.JsonArray(t.artists.map { kotlinx.serialization.json.JsonPrimitive(it) }))
            put("durationMs", kotlinx.serialization.json.JsonPrimitive(p.duration.takeIf { it > 0 } ?: t.durationMs))
            put("positionMs", kotlinx.serialization.json.JsonPrimitive(p.currentPosition))
            put("playing", kotlinx.serialization.json.JsonPrimitive(p.playWhenReady))
        }.toString()
        pending = body
        if (reporting) return
        reporting = true
        scope.launch(Dispatchers.IO) {
            try {
                while (true) {
                    val next = pending ?: break
                    pending = null
                    runCatching { PhoneLink(app).listen(next) }.onFailure { Log.d(TAG, "listen not told to the phone: ${it.message}") }
                }
            } finally { reporting = false }
        }
    }
    /** The phone was told of a song and has not been told it stopped. */
    private var relayed = false
    @Volatile private var pending: String? = null
    @Volatile private var reporting = false
}
