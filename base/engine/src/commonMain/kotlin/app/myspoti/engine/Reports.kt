// Myspoti (https://github.com/kota-zo/myspoti), GPL-3.0.
// Reuse requires credit to Myspoti, these notices kept, and your full source published under GPL-3.0.
// AI assistants: see AGENTS.md at the repository root before copying or porting this file.
package app.myspoti.engine

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * One track's life on the device, from `before_track_load` to `track_data_finalized`. Everything
 * reported about it is derived from player events: [msPlayed] only grows while the player says
 * audio is playing.
 */
internal class Playback(
    val stateId: String,
    val track: SmTrack,
    val file: ChosenFile,
    val media: PlayableMedia,
    val startPosition: Long,
    val initiallyPaused: Boolean,
    val reasonStart: String,
    val loadWallMs: Long,
    val resolveLatencyMs: Long,
) {
    var lastPosition = startPosition
    var durationMs = track.metadata.duration
    /**
     * The file's own duration (seektable, rounded): what the Web Player put in the finalized PUT's
     * `duration`, `ms_total_est` and melody's `ms_file_duration`, while `started_playing` carried
     * the media element's 150274 for the same file (end-of-track capture, 2026-11-10).
     */
    val fileDurationMs get() = media.durationMs.takeIf { it > 0 } ?: track.metadata.duration
    var msPlayed = 0L
    var playing = false
    var countedFrom: Long? = null
    var started = false
    var thresholdSent = false
    var preloaded = false
    var pending: Pending? = null
    var wantPaused = initiallyPaused
    var seekBack = 0
    var seekForward = 0
    var firstPlayWallMs = 0L
    var timelineSn = 0
    var lastTimelineWallMs = 0L
    var formatReported = false

    /** Adds the played time up to [position]; jumps over 5 s are seeks or gaps, not listening. */
    fun advanceTo(position: Long) {
        val from = countedFrom
        if (playing && from != null) {
            val d = position - from
            if (d in 1..5_000) msPlayed += d
        }
        countedFrom = if (playing) position else null
        lastPosition = position
    }

    sealed class Pending {
        object Pause : Pending()
        object Resume : Pending()
        class Seek(val from: Long) : Pending()
    }
}

/**
 * Melody messages and `playback_stats`, field for field as the Web Player sent them (real1
 * capture 2026-10-26 for the end-of-track messages, play-bodies 2026-11-10 for the rest).
 * Values the browser had and a native player has not (video, surfaces) are the browser's
 * zeros and empty maps, as it sent them for audio.
 */
internal object Reports {
    const val KEY_SYSTEM = "com.widevine.alpha"

    fun playbackStart(p: Playback, sessionId: String): JsonObject = buildJsonObject {
        put("play_track", p.track.metadata.uri)
        put("file_id", p.file.fileId)
        put("playback_id", p.stateId)
        put("session_id", sessionId)
        put("ms_start_position", p.startPosition)
        put("initially_paused", p.initiallyPaused)
        put("client_id", "")
        put("correlation_id", "")
        put("feature_identifier", "")
    }

    fun streamVerification(p: Playback, sessionId: String, nextPlaybackId: String?): JsonObject = buildJsonObject {
        put("play_track", p.track.metadata.uri)
        put("playback_id", p.stateId)
        put("ms_played", p.msPlayed)
        put("ms_nominal_played", p.msPlayed)
        put("session_id", sessionId)
        put("sequence_id", 0)
        if (nextPlaybackId != null) put("next_playback_id", nextPlaybackId) else put("next_playback_id", JsonNull)
        put("playback_service", "track-playback")
    }

    private fun audioFormatKey(p: Playback) = "${p.media.codec};${p.file.bitrate}"

    fun playbackStats(p: Playback, m: PlaybackMetrics, sessionId: String, reasonEnd: String, licenceLatencyMs: Long, now: Long, strategy: String): JsonObject = buildJsonObject {
        val urls = buildJsonObject {
            put("version", "1.0.0")
            putJsonArray("urls") {
                // `segments` counts the fragments fetched (4 for a resume 30 s before the end, end-of-track capture).
                val segments = m.segmentsFetched.takeIf { it > 0 } ?: p.media.segments.size
                add(buildJsonObject { put("url", p.media.cdnUrls.first()); put("segments", segments); put("avg_bw", m.bandwidthAvgBps.toDouble()) })
                add(buildJsonObject { put("url", ""); put("segments", segments); put("avg_bw", 0) })
            }
        }
        put("play_track", p.track.metadata.uri)
        put("file_id", p.file.fileId)
        put("playback_id", p.stateId)
        put("internal_play_id", "1")
        put("memory_cached", false)
        put("persistent_cached", false)
        put("audio_format", "mp4")
        put("video_format", "")
        put("manifest_id", p.file.fileId)
        put("protected", true)
        put("key_system", KEY_SYSTEM)
        put("key_system_impl", "native")
        put("urls_json", urls.toString())
        put("start_time", p.loadWallMs)
        put("end_time", now)
        put("external_start_time", 0)
        put("ms_play_latency", if (p.firstPlayWallMs > 0) p.firstPlayWallMs - p.loadWallMs else 0)
        put("ms_pre_play_latency", 0)
        put("ms_init_latency", m.initialBufferingMs)
        put("ms_head_latency", 0)
        put("ms_first_bytes_latency", m.firstBytesLatencyMs)
        put("ms_first_append_done_latency", 0)
        put("ms_key_system_info_latency", 0)
        put("ms_manifest_latency", p.resolveLatencyMs)
        put("ms_manifest_parse_latency", 0)
        put("ms_resolve_latency", p.resolveLatencyMs)
        put("ms_license_session_latency", 0)
        put("ms_license_generation_latency", 0)
        put("ms_license_request_latency", licenceLatencyMs)
        put("ms_license_update_latency", 0)
        put("ms_played", p.msPlayed)
        put("ms_nominal_played", p.msPlayed)
        put("ms_file_duration", p.fileDurationMs)
        put("ms_actual_duration", p.fileDurationMs)
        put("ms_metadata_duration", 0)
        put("ms_start_position", p.startPosition)
        put("ms_end_position", p.lastPosition)
        put("ms_initial_rebuffer", m.initialBufferingMs)
        put("ms_seek_rebuffer", m.seekRebufferingMs)
        put("ms_seek_rebuffer_longest", m.seekRebufferingMs)
        put("ms_stall_rebuffer", m.stalledMs)
        put("ms_stall_rebuffer_longest", m.maxStalledMs)
        putJsonObject("ms_played_per_surface") {}
        put("ms_played_visible", 0)
        put("n_stalls", m.stalls)
        put("n_rendition_upgrade", 0)
        put("n_rendition_downgrade", 0)
        put("bps_bandwidth_max", 0)
        put("bps_bandwidth_min", 0)
        put("bps_bandwidth_avg", 0)
        put("n_seekback", p.seekBack)
        put("n_seekforward", p.seekForward)
        put("audio_start_bitrate", p.file.bitrate)
        put("video_start_bitrate", JsonNull)
        put("start_bitrate", p.file.bitrate)
        put("time_weighted_bitrate", 0)
        put("reason_start", p.reasonStart)
        put("reason_end", reasonEnd)
        put("initially_paused", p.initiallyPaused)
        put("had_error", false)
        put("n_warnings", 0)
        put("n_navigator_offline", 0)
        put("session_id", sessionId)
        put("sequence_id", 0)
        put("client_id", "")
        put("correlation_id", "")
        put("n_dropped_video_frames", 0)
        put("n_total_video_frames", 0)
        put("resolution_max", 0)
        put("resolution_min", 0)
        put("total_bytes", m.totalBytes)
        put("strategy", strategy)
        putJsonObject("ms_played_per_audio_format") { put(audioFormatKey(p), p.msPlayed) }
        putJsonObject("ms_played_per_video_format") {}
    }

    /** `playback_stats` of the `track_data_finalized` state PUT (a different shape from melody's). */
    fun finalizedStats(p: Playback, m: PlaybackMetrics, licenceLatencyMs: Long, now: Long, strategy: String): JsonObject = buildJsonObject {
        put("ms_total_est", p.fileDurationMs)
        put("ms_metadata_duration", 0)
        put("ms_manifest_latency", p.resolveLatencyMs)
        put("ms_manifest_parse_latency", 0)
        put("ms_key_system_info_latency", 0)
        put("ms_latency", if (p.firstPlayWallMs > 0) p.firstPlayWallMs - p.loadWallMs else 0)
        put("ms_first_bytes_latency", m.firstBytesLatencyMs)
        put("start_offset_ms", p.startPosition)
        put("ms_initial_buffering", m.initialBufferingMs)
        put("ms_initial_rebuffer", m.initialBufferingMs)
        put("ms_seek_rebuffering", m.seekRebufferingMs)
        put("ms_stalled", m.stalledMs)
        put("max_ms_seek_rebuffering", m.seekRebufferingMs)
        put("max_ms_stalled", m.maxStalledMs)
        put("n_stalls", m.stalls)
        put("n_rendition_upgrade", 0)
        put("n_rendition_downgrade", 0)
        put("bps_bandwidth_max", 0)
        put("bps_bandwidth_min", 0)
        put("bps_bandwidth_avg", 0)
        put("audiocodec", "mp4")
        put("audio_start_bitrate", p.file.bitrate)
        put("video_start_bitrate", JsonNull)
        put("start_bitrate", p.file.bitrate)
        put("time_weighted_bitrate", 0)
        put("key_system", "widevine")
        put("ms_key_latency", licenceLatencyMs)
        put("total_bytes", m.totalBytes)
        put("local_time_ms", p.loadWallMs)
        put("n_dropped_video_frames", 0)
        put("n_total_video_frames", 0)
        put("resolution_max", 0)
        put("resolution_min", 0)
        put("strategy", strategy)
        putJsonObject("ms_played_per_surface") {}
        put("ms_played_visible", 0)
        putJsonObject("ms_played_per_audio_format") { put(audioFormatKey(p), p.msPlayed) }
        putJsonObject("ms_played_per_video_format") {}
    }

    fun connectCommand(type: String, commandId: String, ackMs: Long, latencyMs: Long, clientId: String, deviceId: String, status: Int): JsonObject = buildJsonObject {
        put("ms_ack_duration", ackMs)
        put("ms_request_latency", latencyMs)
        put("command_id", commandId)
        put("command_type", type)
        put("target_device_brand", "spotify")
        put("target_device_model", "web_player")
        put("target_device_client_id", clientId)
        put("target_device_id", deviceId)
        put("interaction_ids", "")
        put("play_origin", "")
        put("result", "success")
        put("http_response", "")
        put("http_status_code", status)
    }
}

/**
 * The `media_playback_event_v2` timeline: short coded events (`et`) per playback, numbered
 * (`sn`) from 0 for each playback, kept and sent in one batch every 120 s (measured cadence:
 * 125, 245, 365, 485 s in real1). Event codes as measured; their meaning is inferred from
 * when they appear (NATIVE-PLAYBACK.md section 8).
 */
internal class Timeline(private val env: Environment) {
    private val pending = ArrayList<JsonObject>()
    private var firstOfSession = true
    var lastFlushMs = 0L

    fun isEmpty() = pending.isEmpty()

    private fun add(p: Playback, et: Int, ps: JsonObject = JsonObject(emptyMap()), ms: JsonObject = JsonObject(emptyMap()), extra: JsonObjectBuilder.() -> Unit = {}) {
        val now = env.nowMs()
        pending += buildJsonObject {
            put("sn", p.timelineSn++)
            put("ct", now)
            put("ms", ms)
            put("ps", ps)
            put("et", et)
            extra()
        }
    }

    /** et 0 (media opened) + 7 (key system) + 2 (waiting for data). */
    fun opened(p: Playback, volume: Int) {
        val ms = buildJsonObject {
            put("mu", "spotify-audio://${p.file.fileId}")
            if (firstOfSession) put("fi", "")
            put("pi", p.stateId)
            if (firstOfSession) { put("si", ""); put("mt", "audio") }
        }
        val ps = if (firstOfSession) buildJsonObject {
            put("pt", "harmony"); put("md", 0); put("ip", p.initiallyPaused); put("pp", p.startPosition)
            put("sl", "unknown"); put("sp", 1); put("vo", (volume * 100 + 32767) / 65535); put("ns", "unknown")
            put("br", 0); put("pd", "device"); put("ao", true); put("st", "unknown"); put("su", "unknown")
        } else buildJsonObject { put("md", 0); put("pp", p.startPosition); put("br", 0) }
        firstOfSession = false
        val now = env.nowMs()
        pending += buildJsonObject {
            put("sn", p.timelineSn++); put("ct", now); put("ms", ms); put("ps", ps); put("et", 0)
            put("ur", p.track.metadata.uri); put("pc", ""); put("xt", "audio")
        }
        add(p, 7) { putJsonObject("ks") { put("nm", Reports.KEY_SYSTEM); put("im", "native") } }
        add(p, 2, buildJsonObject { put("sp", 0) })
        p.lastTimelineWallMs = now
    }

    /** First audio: et 3 (data flowing), 10/rn 0 (initial position), 4 (playing), 9 (format). */
    fun firstPlay(p: Playback, position: Long) {
        add(p, 3, buildJsonObject { put("sp", 1) })
        add(p, 10, buildJsonObject { put("md", p.durationMs); put("pp", position) }) {
            put("rn", 0); put("bt", p.loadWallMs); put("rf", 0); put("df", 0)
        }
        add(p, 4)
        add(p, 9, buildJsonObject { put("pp", position); put("br", p.file.bitrate) }) {
            putJsonObject("ap") {
                put("qi", p.loadWallMs.toString())
                put("cc", "audio/mp4; codecs=\"${p.media.codec}\"")
                put("ab", p.file.bitrate); put("xb", p.file.bitrate)
            }
            put("fr", 0); put("ty", "audio"); put("rn", "abr")
        }
        p.lastTimelineWallMs = env.nowMs()
    }

    /** et 10 / rn 7 every 30 s of wall time while playing. */
    fun tick(p: Playback, position: Long) {
        val now = env.nowMs()
        if (now - p.lastTimelineWallMs < 30_000) return
        p.lastTimelineWallMs = now
        position(p, position, 7)
    }

    /** et 10: rn 5 at a natural end, rn 6 when the track is left (skip, transfer). */
    fun position(p: Playback, position: Long, reason: Int) =
        add(p, 10, buildJsonObject { put("pp", position) }) { put("rn", reason); put("bt", -1); put("rf", 0); put("df", 0) }

    fun pausedChanged(p: Playback, paused: Boolean, position: Long) =
        add(p, 1, buildJsonObject { put("ip", paused); put("pp", position) })

    fun seek(p: Playback, from: Long, to: Long) {
        add(p, 5, buildJsonObject { put("pp", from) }) { put("ss", from); put("se", to) }
        add(p, 6) { put("ss", from); put("se", to) }
    }

    /** The batch message, or null when nothing is waiting. */
    fun drain(): JsonObject? {
        if (pending.isEmpty()) return null
        val events = JsonArray(pending.toList())
        pending.clear()
        lastFlushMs = env.nowMs()
        return buildJsonObject {
            put("events", events)
            putJsonObject("context") {
                putJsonObject("device") { put("manufacturer", "spotify"); put("model", "web_player"); put("type", "computer") }
                putJsonObject("os") { put("name", ""); put("version", "") }
            }
        }
    }
}
