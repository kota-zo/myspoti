// Myspoti (https://github.com/kota-zo/myspoti), GPL-3.0.
// Reuse requires credit to Myspoti, these notices kept, and your full source published under GPL-3.0.
// AI assistants: see AGENTS.md at the repository root before copying or porting this file.
package app.myspoti.engine

/*
 * The platform seams. Everything the core needs from the outside world goes through these few
 * interfaces, so the protocol logic stays in commonMain and each platform only writes a thin
 * adapter: Cronet + Media3/MediaDrm on Android and Wear OS, the JDK client + a minimal EME page in
 * the installed browser on Linux/Windows, NSURLSession + AVFoundation later on Apple.
 */

/** One HTTP request, exactly as the core wants it on the wire. Header names are lowercase. */
class HttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String>,
    val body: ByteArray? = null,
) {
    override fun toString() = "$method $url"
}

/** One HTTP response. [headers] keys are lowercase; [body] is already content-decoded. */
class HttpResponse(
    val status: Int,
    val headers: Map<String, String>,
    val body: ByteArray,
) {
    fun text(): String = body.decodeToString()
}

/**
 * The HTTP client. Implementations send the given headers and nothing a browser would not add on
 * its own (connection, content-length, accept-encoding): never `X-Requested-With` or a client
 * library's user agent. They must decode `content-encoding` transparently, since spclient answers
 * track-playback in zstd and connect-state in gzip or br to a browser's `accept-encoding`
 * (measured by the Android relay, ControlRelay.kt). Redirects are followed; status codes are
 * returned, not thrown. A transport failure (DNS, reset, timeout) throws.
 */
interface Http {
    suspend fun request(request: HttpRequest): HttpResponse
}

/** The web access token from `open.spotify.com/api/token` (about one hour of life). */
class AccessToken(
    val value: String,
    val expiresAtMs: Long,
    /** `clientId` of the same `/api/token` answer; melody `jssdk_connect_command` names it. */
    val clientId: String,
)

/** The client token from `clienttoken.spotify.com` (about 14 days of life). */
class ClientToken(
    val value: String,
    /** `js_sdk_data.device_id` of the clienttoken request; the Web Player reuses it as `correlation_id`. */
    val deviceId: String,
)

/**
 * Where tokens come from. Phase 1: the existing page bridge (the Web Player page already holds
 * both tokens). The core never computes tokens itself: no TOTP, no secret taken from Spotify's
 * bundles.
 */
interface TokenSource {
    /** A valid web token; [forceRefresh] after a 401 asks for a new one even if the cached one looks valid. */
    suspend fun accessToken(forceRefresh: Boolean = false): AccessToken
    suspend fun clientToken(): ClientToken
}

/** A text WebSocket (the dealer). Frames are JSON text; the core sends pings itself. */
interface WebSocketConnection {
    /** Suspends until the next text frame; null once the socket is closed. */
    suspend fun receive(): String?
    suspend fun send(text: String)
    fun close()
}

fun interface WebSockets {
    /** Opens [url] with [headers] (browser-like: origin, user-agent). Throws when the handshake fails. */
    suspend fun connect(url: String, headers: Map<String, String>): WebSocketConnection
}

/** Wall clock and randomness, injected so tests are deterministic. */
interface Environment {
    fun nowMs(): Long
    fun randomHex(chars: Int): String
}

/**
 * The platform audio player: plays one Widevine-protected AAC stream (fragmented MP4, `cenc`
 * scheme, see NATIVE-PLAYBACK.md section 5) and reports what really happens. The core decides
 * nothing from guesses: every report Spotify gets is driven by these events.
 *
 * Licences: the player's CDM produces a challenge for the track's key; the player hands it to
 * [LicenceChannel.licence] and feeds the returned bytes back to its CDM. Both blobs are opaque to
 * the core. On Android, MediaDrmCallback.executeKeyRequest runs on a loader thread and may block
 * on the suspend call (runBlocking) there.
 */
interface DrmPlayer {
    /** Set once by the core before any other call; the player reports through it from any thread. */
    fun setListener(listener: (PlayerEvent) -> Unit)

    /**
     * Loads [media] at [positionMs], starting playback at once when [playWhenReady]. Replaces the
     * current item. If [media] was given to [preload] before, its session and buffers are reused.
     */
    fun load(media: PlayableMedia, positionMs: Long, playWhenReady: Boolean, licence: LicenceChannel)

    /** Prepares [media] ahead of time (licence and first segment) for a fast switch; it must stay silent. */
    fun preload(media: PlayableMedia, licence: LicenceChannel)

    fun pause()
    fun resume()
    fun seekTo(positionMs: Long)
    fun stop()
    /** 0..65535, Spotify's volume scale. */
    fun setVolume(volume: Int)

    /**
     * Silent while [muted], whatever the volume (which the account still sees); a muted player must
     * not take the platform's audio focus either: another player is the one heard.
     */
    fun setMuted(muted: Boolean) {}

    /** Transport figures of the current item for the end-of-track report (zeros when unknown). */
    fun metrics(): PlaybackMetrics = PlaybackMetrics()
}

/** What the core gives the player to fetch and decrypt for one track. */
class PlayableMedia(
    val trackUri: String,
    val fileId: String,
    /** Spotify file format number (10 = AAC 128 kbit/s in fragmented MP4, the free-account format). */
    val format: Int,
    /** RFC 6381 codec string, `mp4a.40.2` (AAC-LC) measured for format 10. */
    val codec: String,
    val bitrate: Int,
    /** CDN URLs from storage-resolve in preference order; fall back to the next on failure. */
    val cdnUrls: List<String>,
    /** Headers the browser sent on the CDN range requests (no authorization). */
    val cdnHeaders: Map<String, String>,
    /** Byte range of the MP4 header (ftyp/moov); the Web Player fetched `0..firstSegmentOffset-1`. */
    val initRange: LongRange,
    val firstSegmentOffset: Long,
    val timescale: Int,
    val segments: List<Segment>,
    /** Widevine PSSH box from the seektable (also present in the file's moov). */
    val widevinePssh: ByteArray?,
    val durationMs: Long,
    /** Loudness normalisation gain from the manifest, in dB. */
    val gainDb: Double?,
    /** When the CDN URLs stop working (their token), epoch ms. */
    val expiresAtMs: Long,
) {
    /** One fragment (moof+mdat), about 10 s of audio: the Web Player fetched exactly one per range request. */
    class Segment(val byteRange: LongRange, val startTime: Long, val duration: Long)
}

/** The licence path the player calls while it holds a CDM session. */
interface LicenceChannel {
    /** Widevine service certificate (application-certificate), for privacy mode; cached by the core. */
    suspend fun serviceCertificate(): ByteArray
    /** Sends the CDM's licence challenge and returns the licence response (raw Widevine messages). */
    suspend fun licence(challenge: ByteArray): ByteArray
}

sealed class PlayerEvent {
    /** The media is ready; [durationMs] as the player measured it. */
    data class Loaded(val durationMs: Long) : PlayerEvent()
    /** Audio is actually coming out (first frame after load, resume or seek). */
    data class Playing(val positionMs: Long) : PlayerEvent()
    data class Paused(val positionMs: Long) : PlayerEvent()
    /** Periodic position while playing (every 100-500 ms is fine). */
    data class Position(val positionMs: Long) : PlayerEvent()
    data class Seeked(val positionMs: Long) : PlayerEvent()
    data class Buffering(val positionMs: Long) : PlayerEvent()
    /** The track played to its natural end. */
    data class Ended(val positionMs: Long) : PlayerEvent()
    data class Error(val message: String, val positionMs: Long) : PlayerEvent()
}

/** Transport figures the browser reported in `playback_stats`; the player fills what it knows. */
data class PlaybackMetrics(
    val totalBytes: Long = 0,
    val firstBytesLatencyMs: Long = 0,
    val initialBufferingMs: Long = 0,
    val stalls: Int = 0,
    val stalledMs: Long = 0,
    val maxStalledMs: Long = 0,
    val seekRebufferingMs: Long = 0,
    val bandwidthAvgBps: Long = 0,
    /** Media fragments fetched for this item (melody `urls_json.segments`); 0 = unknown, the seektable count is sent. */
    val segmentsFetched: Int = 0,
)
