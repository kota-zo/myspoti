// Myspoti (https://github.com/kota-zo/myspoti), GPL-3.0.
// Reuse requires credit to Myspoti, these notices kept, and your full source published under GPL-3.0.
// AI assistants: see AGENTS.md at the repository root before copying or porting this file.
package app.myspoti.engine

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/*
 * The JSON answers the core reads, with only the fields it uses (every answer carries more; see
 * NATIVE-PLAYBACK.md for the full shapes). Names follow the wire.
 */

internal val EngineJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    coerceInputValues = true
    isLenient = true
}

/** `POST track-playback/v1/devices` answer. */
@Serializable
internal class RegisterResponse(
    @SerialName("initial_seq_num") val initialSeqNum: Int = 1,
    @SerialName("device_keep_alive_update_seconds") val keepAliveSeconds: Long = 0,
    /** Bit mask of allowed file formats (5160 = bits 3, 5, 10, 12 on a free account). */
    @SerialName("file_format_filter") val fileFormatFilter: Long = 0,
    @SerialName("effective_license") val effectiveLicense: String? = null,
)

/** The server's play queue as a state machine (dealer `replace_state` and every state PUT answer). */
@Serializable
class StateMachine(
    @SerialName("state_machine_id") val id: String,
    val tracks: List<SmTrack> = emptyList(),
    val states: List<SmState> = emptyList(),
)

@Serializable
class SmTrack(
    val metadata: TrackMetadata,
    /** Lists by manifest format name (`file_ids_mp4`, `file_ids_mp4_dual`, `manifest_ids_video`...). */
    val manifest: Map<String, List<ManifestFile>?> = emptyMap(),
    @SerialName("track_type") val trackType: String? = null,
    /** Played time after which the Web Player sends `played_threshold_reached` (30000 measured). */
    @SerialName("ms_played_until_update") val msPlayedUntilUpdate: Long = 30_000,
    /** `TRACK`, `EPISODE`, or `AD` for a server-inserted ad (ADS.md). */
    @SerialName("content_type") val contentType: String? = null,
)

@Serializable
class TrackMetadata(
    val uri: String,
    val name: String? = null,
    @SerialName("group_name") val groupName: String? = null,
    @SerialName("group_uri") val groupUri: String? = null,
    @SerialName("context_uri") val contextUri: String? = null,
    val duration: Long = 0,
    val authors: List<Named> = emptyList(),
    val images: List<Picture> = emptyList(),
)

@Serializable
class Named(val name: String? = null, val uri: String? = null)

@Serializable
class Picture(val url: String? = null, val height: Int? = null, val width: Int? = null)

@Serializable
class ManifestFile(
    val bitrate: Int? = null,
    @SerialName("file_id") val fileId: String? = null,
    @SerialName("file_url") val fileUrl: String? = null,
    @SerialName("track_type") val trackType: String? = null,
    val format: String? = null,
    @SerialName("audio_quality") val audioQuality: String? = null,
    @SerialName("gain_db") val gainDb: Double? = null,
)

@Serializable
class SmState(
    @SerialName("state_id") val id: String,
    /** Index into [StateMachine.tracks]. */
    val track: Int,
    @SerialName("disallow_seeking") val disallowSeeking: Boolean = false,
    @SerialName("initial_playback_position") val initialPlaybackPosition: Long? = null,
    val transitions: Transitions = Transitions(),
    @SerialName("track_provider") val trackProvider: String? = null,
)

@Serializable
class Transitions(
    @SerialName("skip_next") val skipNext: StateIndexRef? = null,
    @SerialName("skip_prev") val skipPrev: StateIndexRef? = null,
    /** Where the queue goes when the track ends by itself; null at the end of the context. */
    val advance: StateIndexRef? = null,
)

@Serializable
class StateIndexRef(
    @SerialName("state_index") val stateIndex: Int? = null,
    val paused: Boolean = false,
    @SerialName("ends_context") val endsContext: Boolean = false,
)

/** Dealer `hm://track-playback/v1/command` payload. */
@Serializable
internal class TrackPlaybackCommand(
    val type: String,
    @SerialName("state_machine") val stateMachine: StateMachine? = null,
    @SerialName("state_ref") val stateRef: StateIndexRef? = null,
    @SerialName("seek_to") val seekTo: Double? = null,
    val volume: Double? = null,
    @SerialName("command_id") val commandId: String? = null,
)

/** `PUT track-playback/v1/devices/{dev}/state` answer: the server's next state machine. */
@Serializable
internal class PutStateResponse(
    @SerialName("state_machine") val stateMachine: StateMachine? = null,
    @SerialName("updated_state_ref") val updatedStateRef: StateIndexRef? = null,
)

/** `storage-resolve/v2/files/audio/interactive/...` answer. */
@Serializable
internal class StorageResolveResponse(
    val result: String,
    @SerialName("cdnurl") val cdnUrls: List<String> = emptyList(),
    @SerialName("fileid") val fileId: String? = null,
    val ttl: Long = 0,
)

/** `seektables.spotifycdn.com/v1/seektable/{fileId}` answer. */
@Serializable
internal class SeektableResponse(
    val seektableVersion: Int = 0,
    /** Byte offset of the first fragment. */
    val offset: Long,
    val timescale: Int,
    val segments: List<SeekSegment>,
    val pssh: Map<String, String> = emptyMap(),
    val initRange: ByteRangeJson? = null,
) {
    @Serializable
    class SeekSegment(val size: Long, val duration: Long)

    @Serializable
    class ByteRangeJson(val start: Long, val end: Long)
}

/** `melody/v1/license_url` answer: a path relative to the spclient host and its expiry (epoch s). */
@Serializable
internal class LicenseUrlResponse(val uri: String, val expires: Long = 0)

/** `track-playback/v1/media/{uri}` answer. */
@Serializable
internal class MediaResponse(val media: Map<String, MediaEntry> = emptyMap()) {
    @Serializable
    class MediaEntry(
        val item: SmTrack,
        @SerialName("video_version_uri") val videoVersionUri: String? = null,
    )
}

/** apresolve answer: host:port lists, nearest first. */
@Serializable
internal class ApResolveResponse(
    @SerialName("dealer-g2") val dealer: List<String> = emptyList(),
    val spclient: List<String> = emptyList(),
)

/** A dealer frame (JSON text). */
@Serializable
internal class DealerFrame(
    val type: String,
    val uri: String? = null,
    val method: String? = null,
    val headers: Map<String, String>? = null,
    val payloads: List<kotlinx.serialization.json.JsonElement>? = null,
)

internal fun JsonObject.str(key: String): String? =
    (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
