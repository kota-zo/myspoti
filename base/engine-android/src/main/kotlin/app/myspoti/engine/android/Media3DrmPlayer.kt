// Myspoti (https://github.com/kota-zo/myspoti), GPL-3.0.
// Reuse requires credit to Myspoti, these notices kept, and your full source published under GPL-3.0.
// AI assistants: see AGENTS.md at the repository root before copying or porting this file.
package app.myspoti.engine.android

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.DrmInitData
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.drm.DrmSession
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.drm.DummyExoMediaDrm
import androidx.media3.exoplayer.drm.ExoMediaDrm
import androidx.media3.exoplayer.drm.FrameworkMediaDrm
import androidx.media3.exoplayer.drm.MediaDrmCallback
import androidx.media3.exoplayer.drm.MediaDrmCallbackException
import androidx.media3.exoplayer.drm.UnsupportedDrmException
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.mp4.FragmentedMp4Extractor
import androidx.media3.extractor.text.SubtitleParser
import app.myspoti.engine.DrmPlayer
import app.myspoti.engine.Http
import app.myspoti.engine.HttpRequest
import app.myspoti.engine.LicenceChannel
import app.myspoti.engine.PlayableMedia
import app.myspoti.engine.PlaybackMetrics
import app.myspoti.engine.PlayerEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

private val MUSIC: AudioAttributes = AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build()

/**
 * The engine's [DrmPlayer] on Media3 ExoPlayer: one Widevine-protected AAC file at a time, read from
 * the CDN through [SegmentDataSource] (the Web Player's range pattern) and FragmentedMp4Extractor
 * (the init chunk's `sidx` makes it seekable), decrypted by the phone's CDM.
 *
 * DRM: one [DefaultDrmSessionManager] in multi-session mode for the player's life. Its
 * [MediaDrmCallback] hands the CDM challenge to the engine's [LicenceChannel] and returns the raw
 * licence (blocking on Media3's DRM request thread, as the seam allows). Init data comes from the
 * file's moov `pssh`, which the extractor attaches to the format. Privacy mode as in the browser
 * (EME setServerCertificate): the application certificate is set on the CDM before its first
 * session, fetched once through the channel (the core caches it too).
 *
 * Preload: the next item's init chunk and first segment are fetched into memory, and a DRM session
 * is acquired with the moov's own `pssh` box (not the seektable's: the moov one carries an extra
 * field 1, algorithm AES-CTR, so the bytes differ and Media3 would not match them). When the item
 * is loaded, the manager finds that session by equal init data and reuses it: no licence round
 * trip, no CDN round trip for the first 10 s.
 *
 * Every call is posted, in order, to the player's own looper (a background thread unless one is
 * given), so the engine may call from any coroutine. Events go to the listener from that thread.
 */
@OptIn(UnstableApi::class)
class Media3DrmPlayer(
    context: Context,
    private val http: Http,
    /** Where the player's steps go (the debug harness passes the Logbook). */
    private val log: (String) -> Unit = {},
    /** Sets the application certificate on the CDM, as the browser's EME did. */
    private val privacyMode: Boolean = true,
    /** Asks MediaDrm for L3 even on an L1 phone: a diagnostic for licences refused on L1. */
    private val forceL3: Boolean = false,
    looper: Looper? = null,
) : DrmPlayer {
    /**
     * The player paused itself (audio focus lost for good, headphones unplugged): the caller should
     * send a pause command so Spotify's state follows. Called on the player's thread.
     */
    var onPlayerPause: ((reason: String) -> Unit)? = null
    /** The caller wants sound (a load that plays, a resume): only then is a pause of the player's own told, and once. */
    @Volatile private var wantsPlay = false

    private val app = context.applicationContext
    private val thread = if (looper == null) HandlerThread("engine-player").apply { start() } else null
    private val handler = Handler(looper ?: thread!!.looper)
    private val appDispatcher = handler.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** Calls in the order the engine made them; a load may first wait for the certificate. */
    private val commands = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    @Volatile private var listener: (PlayerEvent) -> Unit = {}
    @Volatile private var licence: LicenceChannel? = null
    @Volatile private var certificate: ByteArray? = null
    private val items = ConcurrentHashMap<String, CdnItem>()

    // Player-thread state.
    private lateinit var exo: ExoPlayer
    private lateinit var drm: DefaultDrmSessionManager
    private lateinit var sources: ProgressiveMediaSource.Factory
    private var drmHeld = false
    private val preloads = HashMap<String, DrmSession>()
    @Volatile private var current: CdnItem? = null
    private var currentUri = ""
    private var loadedSent = false
    private var endedSent = false
    private var status: Status? = null
    private var seekPending = false
    private var loadAtMs = 0L
    private var volume = 1f
    private var muted = false
    /** The audio renderer, decoding or silent (see [MirrorAudioRenderer]); set when the player is built. */
    private var audioRenderer: MirrorAudioRenderer? = null

    // Metrics, read from the engine's thread.
    @Volatile private var initialBufferingMs = 0L
    @Volatile private var stallStartMs = 0L
    @Volatile private var stalls = 0
    @Volatile private var stalledMs = 0L
    @Volatile private var maxStalledMs = 0L
    @Volatile private var seekStartMs = 0L
    @Volatile private var seekRebufferingMs = 0L

    private enum class Status { PLAYING, PAUSED, BUFFERING }

    init {
        handler.post { build() }
        scope.launch { for (c in commands) runCatching { c() }.onFailure { log("player: ${it.message}") } }
    }

    private fun build() {
        val provider = ExoMediaDrm.Provider { uuid ->
            try {
                FrameworkMediaDrm.newInstance(uuid).also { d ->
                    if (forceL3) runCatching { d.setPropertyString("securityLevel", "L3") }
                        .onSuccess { log("widevine: L3 forced") }.onFailure { log("widevine: L3 refused: ${it.message}") }
                    if (privacyMode) certificate?.let { c ->
                        runCatching { d.setPropertyByteArray("serviceCertificate", c) }
                            .onSuccess { log("widevine: privacy mode on (${c.size} B certificate)") }
                            .onFailure { log("widevine: certificate refused by the CDM: ${it.message}") }
                    }
                }
            } catch (e: UnsupportedDrmException) {
                log("widevine: unsupported (${e.reason})")
                DummyExoMediaDrm()
            }
        }
        drm = DefaultDrmSessionManager.Builder()
            .setUuidAndExoMediaDrmProvider(C.WIDEVINE_UUID, provider)
            .setMultiSession(true)
            .build(LicenceCallback())
        sources = ProgressiveMediaSource.Factory(
            SegmentDataSource.Factory { items[it.toString()] },
            ExtractorsFactory { arrayOf(FragmentedMp4Extractor(SubtitleParser.Factory.UNSUPPORTED, 0)) },
        )
            .setDrmSessionManagerProvider(DrmSessionManagerProvider { drm })
            // About one segment (165 KB) between load-control checks instead of 1 MiB, so the CDN
            // sees one range at a time as from the Web Player rather than six back to back.
            .setContinueLoadingCheckIntervalBytes(128 * 1024)
        exo = ExoPlayer.Builder(app, Renderers())
            .setLooper(handler.looper)
            .setAudioAttributes(MUSIC, true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            // The Web Player kept 10-20 s ahead (two segments at start, then one every 10 s).
            .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(15_000, 25_000, 1_000, 2_000).build())
            .build()
        exo.addListener(Events())
    }

    /** Media3's renderers, the audio one wrapped so a muted mirror is played without being decoded. */
    private inner class Renderers : DefaultRenderersFactory(app) {
        override fun buildAudioRenderers(
            context: Context, extensionRendererMode: Int, mediaCodecSelector: MediaCodecSelector, enableDecoderFallback: Boolean,
            audioSink: AudioSink, eventHandler: Handler, eventListener: AudioRendererEventListener, out: ArrayList<Renderer>,
        ) {
            val built = ArrayList<Renderer>()
            super.buildAudioRenderers(context, extensionRendererMode, mediaCodecSelector, enableDecoderFallback, audioSink, eventHandler, eventListener, built)
            built.mapTo(out) { r -> if (r is MediaCodecAudioRenderer) MirrorAudioRenderer(r).also { audioRenderer = it } else r }
        }
    }

    /** Holds the DRM manager for the player's life (keep-alive sessions survive item switches). */
    private suspend fun holdDrm() {
        if (drmHeld) return
        drmHeld = true
        withContext(Handler(exo.playbackLooper).asCoroutineDispatcher()) {
            drm.setPlayer(exo.playbackLooper, PlayerId.UNSET)
            drm.prepare()
        }
    }

    private suspend fun ensureCertificate(channel: LicenceChannel) {
        if (!privacyMode || certificate != null) return
        runCatching { certificate = channel.serviceCertificate() }
            .onFailure { log("widevine: application certificate unavailable, privacy mode off: ${it.message}") }
    }

    private fun enqueue(block: suspend () -> Unit) { commands.trySend(block) }
    private fun onPlayer(block: () -> Unit) = enqueue { withContext(appDispatcher) { block() } }
    /**
     * Loads asked for and not yet given to the player (a load waits for the certificate and the
     * player's thread): meanwhile the song going out still plays and reports, and its positions
     * were taken for the new song's (log seen: a song finalized at 3:22 a second after it was
     * loaded, the old song's place). Only an error goes out then.
     */
    private val loadsPending = java.util.concurrent.atomic.AtomicInteger(0)
    private fun emit(e: PlayerEvent) { if (e is PlayerEvent.Error || loadsPending.get() == 0) listener(e) }
    private fun position() = exo.currentPosition.coerceAtLeast(0)

    override fun setListener(listener: (PlayerEvent) -> Unit) { this.listener = listener }

    override fun load(media: PlayableMedia, positionMs: Long, playWhenReady: Boolean, licence: LicenceChannel) {
        loadsPending.incrementAndGet()
        enqueue { try { loadNow(media, positionMs, playWhenReady, licence) } finally { loadsPending.decrementAndGet() } }
    }

    private suspend fun loadNow(media: PlayableMedia, positionMs: Long, playWhenReady: Boolean, licence: LicenceChannel) {
        this.licence = licence
        ensureCertificate(licence)
        withContext(appDispatcher) { holdDrm() }
        withContext(appDispatcher) {
            val item = items.getOrPut(uriOf(media)) { CdnItem(media, http, log) }
            val preloaded = preloads.containsKey(media.fileId)
            // Items neither current nor preloaded are dropped with their buffers.
            items.keys.retainAll(setOf(item.uri.toString()) + preloads.keys.mapNotNull { id -> items.values.firstOrNull { it.media.fileId == id }?.uri?.toString() })
            current = item
            currentUri = item.uri.toString()
            loadedSent = false; endedSent = false; status = null; seekPending = false
            initialBufferingMs = 0; stallStartMs = 0; stalls = 0; stalledMs = 0; maxStalledMs = 0; seekStartMs = 0; seekRebufferingMs = 0
            loadAtMs = System.currentTimeMillis()
            item.loadedAtMs = loadAtMs
            exo.setMediaSource(sources.createMediaSource(MediaItem.Builder().setUri(item.uri).setMediaId(media.fileId).build()), positionMs)
            exo.volume = if (muted) 0f else volume
            wantsPlay = playWhenReady
            exo.playWhenReady = playWhenReady
            exo.prepare()
            log("load ${media.trackUri} @$positionMs play=$playWhenReady${if (preloaded) " (preloaded: session and first segment ready)" else ""}")
            // The player acquires its own reference to the same session; the preload's is released
            // into the manager's keep-alive (5 min), which the held prepare() keeps across switches.
            preloads.remove(media.fileId)?.let { s -> releaseOnPlayback(s) }
        }
    }

    override fun preload(media: PlayableMedia, licence: LicenceChannel) {
        this.licence = licence
        scope.launch {
            val item = items.getOrPut(uriOf(media)) { CdnItem(media, http, log) }
            val job = async { item.prefetch() }
            item.prefetchJob = job
            runCatching { job.await() }.onFailure { log("preload ${media.trackUri}: CDN failed: ${it.message}") }
            ensureCertificate(licence)
            withContext(appDispatcher) { holdDrm() }
            val pssh = item.peekInit()?.let(::widevinePssh) ?: media.widevinePssh
            if (pssh == null) return@launch log("preload ${media.trackUri}: no pssh, licence at load")
            val format = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AAC)
                .setDrmInitData(DrmInitData(DrmInitData.SchemeData(C.WIDEVINE_UUID, MimeTypes.VIDEO_MP4, pssh))).build()
            withContext(appDispatcher) {
                if (preloads.containsKey(media.fileId)) return@withContext
                val session = withContext(Handler(exo.playbackLooper).asCoroutineDispatcher()) { drm.acquireSession(null, format) }
                if (session != null) preloads[media.fileId] = session
                log("preload ${media.trackUri}: first segment in memory, licence requested")
            }
        }
    }

    private fun releaseOnPlayback(s: DrmSession) { Handler(exo.playbackLooper).post { s.release(null) } }

    override fun pause() = onPlayer {
        wantsPlay = false
        // Already paused (by itself, or before the engine's command came back): confirm it.
        if (!exo.playWhenReady) { status = Status.PAUSED; emit(PlayerEvent.Paused(position())) } else exo.pause()
    }

    override fun resume() = onPlayer {
        wantsPlay = true
        if (exo.isPlaying) { status = Status.PLAYING; emit(PlayerEvent.Playing(position())) } else exo.play()
    }

    override fun seekTo(positionMs: Long) = onPlayer {
        seekPending = true
        seekStartMs = System.currentTimeMillis()
        endedSent = false
        exo.seekTo(positionMs)
    }

    override fun stop() = onPlayer {
        exo.stop()
        exo.clearMediaItems()
        current = null
        currentUri = ""
        status = null
        preloads.values.forEach(::releaseOnPlayback)
        preloads.clear()
        items.clear()
    }

    override fun setVolume(volume: Int) = onPlayer {
        this.volume = volume.coerceIn(0, 65535) / 65535f
        exo.volume = if (muted) 0f else this.volume
    }

    /**
     * Muted (a listen mirror: another player is heard): silent, without the audio focus, and an
     * unplugged headset does not pause it (the heard player decides). It still fetches the song,
     * asks its licence and plays it through at its pace, but decrypts and decodes nothing (see
     * [MirrorAudioRenderer]): that was about a sixth of a core for every song the app played itself.
     */
    override fun setMuted(muted: Boolean) = onPlayer {
        if (this.muted == muted) return@onPlayer
        this.muted = muted
        exo.setAudioAttributes(MUSIC, !muted)
        exo.setHandleAudioBecomingNoisy(!muted)
        exo.volume = if (muted) 0f else volume
        audioRenderer?.let { exo.createMessage(it).setType(MirrorAudioRenderer.MSG_SET_SILENT).setPayload(muted).send() }
        log("player: ${if (muted) "silent (mirror, not decoded)" else "decoded and heard"}")
    }

    override fun metrics(): PlaybackMetrics {
        val item = current ?: return PlaybackMetrics()
        val ongoing = stallStartMs.takeIf { it > 0 }?.let { System.currentTimeMillis() - it } ?: 0
        return PlaybackMetrics(
            totalBytes = item.totalBytes.get(),
            firstBytesLatencyMs = item.firstBytesLatencyMs,
            initialBufferingMs = initialBufferingMs,
            stalls = stalls,
            stalledMs = stalledMs + ongoing,
            maxStalledMs = maxOf(maxStalledMs, ongoing),
            seekRebufferingMs = seekRebufferingMs,
            bandwidthAvgBps = item.bandwidthBps(),
            segmentsFetched = item.segmentsFetched.get(),
        )
    }

    /** Frees the player and its thread; the object is unusable afterwards. */
    fun release() = enqueue {
        withContext(appDispatcher) {
            preloads.values.forEach(::releaseOnPlayback)
            preloads.clear()
            if (drmHeld) Handler(exo.playbackLooper).post { drm.release() }
            exo.release()
        }
        thread?.quitSafely()
        commands.close()
        scope.cancel()
    }

    private fun uriOf(media: PlayableMedia) = "myspoti-cdn://audio/${media.fileId}"

    // ------------------------------------------------------------------ events

    private val ticker = object : Runnable {
        override fun run() {
            if (current != null && exo.isPlaying) {
                emit(PlayerEvent.Position(position()))
                // A track that starts on time, at most 250 ms between reports (the core asks for <= 500).
                handler.postDelayed(this, 250)
            }
        }
    }

    private fun seeked() {
        if (!seekPending) return
        seekPending = false
        if (seekStartMs > 0) seekRebufferingMs += System.currentTimeMillis() - seekStartMs
        seekStartMs = 0
        emit(PlayerEvent.Seeked(position()))
    }

    /** One status from Media3's three flags: what Spotify is told depends on audio really playing. */
    private fun evaluate() {
        if (current == null || exo.playbackState == Player.STATE_IDLE || exo.playbackState == Player.STATE_ENDED) return
        val s = when {
            exo.isPlaying -> Status.PLAYING
            !exo.playWhenReady || exo.playbackSuppressionReason != Player.PLAYBACK_SUPPRESSION_REASON_NONE -> Status.PAUSED
            else -> Status.BUFFERING
        }
        if (s == status) return
        status = s
        val pos = position()
        when (s) {
            Status.PLAYING -> {
                if (loadAtMs > 0) {
                    log("first audio ${System.currentTimeMillis() - loadAtMs} ms after load, at $pos ms")
                    loadAtMs = 0
                }
                emit(PlayerEvent.Playing(pos))
                handler.removeCallbacks(ticker)
                handler.postDelayed(ticker, 250)
            }
            Status.PAUSED -> emit(PlayerEvent.Paused(pos))
            Status.BUFFERING -> emit(PlayerEvent.Buffering(pos))
        }
    }

    private inner class Events : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            if (current == null) return
            val now = System.currentTimeMillis()
            when (state) {
                Player.STATE_READY -> {
                    if (!loadedSent) {
                        loadedSent = true
                        current?.loadedAtMs?.let { initialBufferingMs = now - it }
                        emit(PlayerEvent.Loaded(exo.duration.takeIf { it != C.TIME_UNSET } ?: current!!.media.durationMs))
                    }
                    if (stallStartMs > 0) {
                        val d = now - stallStartMs
                        stalledMs += d
                        maxStalledMs = maxOf(maxStalledMs, d)
                        stallStartMs = 0
                    }
                    seeked()
                }
                Player.STATE_BUFFERING -> if (loadedSent && !seekPending && exo.playWhenReady) { stalls++; stallStartMs = now }
                Player.STATE_ENDED -> if (!endedSent) {
                    endedSent = true
                    handler.removeCallbacks(ticker)
                    status = null
                    val end = exo.duration.takeIf { it != C.TIME_UNSET } ?: position()
                    log("ended at $end ms")
                    emit(PlayerEvent.Ended(end))
                }
            }
            evaluate()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) = evaluate()

        override fun onPlaybackSuppressionReasonChanged(reason: Int) = evaluate()

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (!playWhenReady && current != null && wantsPlay) {
                if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS || reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY) wantsPlay = false
                when (reason) {
                    Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS -> onPlayerPause?.invoke("audio focus lost")
                    Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY -> onPlayerPause?.invoke("audio becoming noisy")
                }
            }
            evaluate()
        }

        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            // A seek inside buffered data may not leave READY: report it once the position moved.
            if (reason == Player.DISCONTINUITY_REASON_SEEK && exo.playbackState == Player.STATE_READY) handler.post {
                if (exo.playbackState == Player.STATE_READY) seeked()
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            val why = generateSequence(error.cause) { it.cause }.mapNotNull { it.message }.firstOrNull() ?: error.message
            log("error ${error.errorCodeName}: $why")
            handler.removeCallbacks(ticker)
            status = null
            emit(PlayerEvent.Error("${error.errorCodeName}: $why", position()))
        }
    }

    // ------------------------------------------------------------------ licences

    /** Media3's DRM requests, run on its DRM request thread: blocking there is what it expects. */
    private inner class LicenceCallback : MediaDrmCallback {
        override fun executeKeyRequest(uuid: java.util.UUID, request: ExoMediaDrm.KeyRequest): ByteArray {
            val channel = licence ?: throw failure("no licence channel", null)
            val t0 = System.currentTimeMillis()
            return try {
                runBlocking { channel.licence(request.data) }.also {
                    log("licence: challenge ${request.data.size} B -> licence ${it.size} B in ${System.currentTimeMillis() - t0} ms")
                }
            } catch (e: Exception) {
                log("licence refused after ${System.currentTimeMillis() - t0} ms: ${e.message}")
                throw failure("licence", e)
            }
        }

        /** Device provisioning goes to Google's server named by the CDM, as Media3's HttpMediaDrmCallback does it. */
        override fun executeProvisionRequest(uuid: java.util.UUID, request: ExoMediaDrm.ProvisionRequest): ByteArray {
            val url = request.defaultUrl + "&signedRequest=" + String(request.data, Charsets.UTF_8)
            log("widevine: provisioning the device")
            return try {
                val r = runBlocking { http.request(HttpRequest("POST", url, emptyMap())) }
                if (r.status !in 200..299) throw java.io.IOException("HTTP ${r.status}")
                r.body
            } catch (e: Exception) {
                log("widevine: provisioning failed: ${e.message}")
                throw failure("provisioning", e)
            }
        }

        private fun failure(what: String, cause: Throwable?) =
            MediaDrmCallbackException(DataSpec(Uri.EMPTY), Uri.EMPTY, emptyMap(), 0, cause ?: java.io.IOException(what))
    }
}
