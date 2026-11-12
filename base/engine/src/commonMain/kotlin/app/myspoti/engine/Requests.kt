// Myspoti (https://github.com/kota-zo/myspoti), GPL-3.0.
// Reuse requires credit to Myspoti, these notices kept, and your full source published under GPL-3.0.
// AI assistants: see AGENTS.md at the repository root before copying or porting this file.
package app.myspoti.engine

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/*
 * Request bodies, field for field and in the order the Web Player wrote them (play-bodies
 * capture, Firefox, 2026-11-10; real1 capture, Chrome, 2026-10-26). Pure functions: the tests
 * compare them with the anonymised fixtures.
 */
internal object Bodies {

    /** `POST {spclient}/track-playback/v1/devices`. */
    fun register(config: EngineConfig, deviceId: String, connectionId: String, correlationId: String): JsonObject = buildJsonObject {
        putJsonObject("device") {
            put("brand", "spotify")
            putJsonObject("capabilities") {
                put("change_volume", true)
                put("enable_play_token", true)
                put("supports_file_media_type", true)
                put("play_token_lost_behavior", "pause")
                put("disable_connect", false)
                put("audio_podcasts", true)
                put("video_playback", true)
                putJsonArray("manifest_formats") { EngineConfig.MANIFEST_FORMATS.forEach { add(JsonPrimitive(it)) } }
                put("supports_preferred_media_type", true)
                put("supports_playback_offsets", true)
                put("supports_playback_speed", true)
            }
            put("device_id", deviceId)
            put("device_type", "computer")
            putJsonObject("metadata") {}
            put("model", "web_player")
            put("name", config.deviceName)
            put("platform_identifier", config.platformIdentifier)
            put("is_group", false)
            put("is_public", false)
            put("correlation_id", correlationId)
            put("client_version", config.harmonyVersion)
        }
        put("outro_endcontent_snooping", false)
        put("connection_id", connectionId)
        put("client_version", config.harmonyVersion)
        put("volume", config.initialVolume)
    }

    /** `PUT {spclient}/connect-state/v1/devices/hobs_{dev35}`: a hidden observer next to the player. */
    fun hobs(): JsonObject = buildJsonObject {
        put("member_type", "CONNECT_STATE")
        putJsonObject("device") {
            putJsonObject("device_info") {
                putJsonObject("capabilities") {
                    put("can_be_player", false)
                    put("hidden", true)
                    put("needs_full_player_state", true)
                }
            }
        }
    }

    class SubState(
        val speed: Int,
        val position: Long,
        val duration: Long,
        val bitrate: Int,
        val format: Int,
        val audioQuality: String,
    )

    /** `PUT {spclient}/track-playback/v1/devices/{dev}/state`. */
    fun state(
        seq: Int,
        machineId: String?,
        stateId: String?,
        paused: Boolean,
        sub: SubState?,
        previousPosition: Long?,
        debugSource: String,
        playbackStats: JsonObject? = null,
    ): JsonObject = buildJsonObject {
        put("seq_num", seq)
        if (machineId == null || stateId == null) put("state_ref", kotlinx.serialization.json.JsonNull)
        else putJsonObject("state_ref") {
            put("state_machine_id", machineId)
            put("state_id", stateId)
            put("paused", paused)
        }
        if (sub != null) putJsonObject("sub_state") {
            put("playback_speed", sub.speed)
            put("position", sub.position)
            put("duration", sub.duration)
            put("media_type", "AUDIO")
            put("bitrate", sub.bitrate)
            put("audio_quality", sub.audioQuality)
            put("format", sub.format)
            put("is_video_on", false)
        }
        if (previousPosition != null) put("previous_position", previousPosition)
        if (playbackStats != null) put("playback_stats", playbackStats)
        put("debug_source", debugSource)
    }

    /** `PUT {spclient}/track-playback/v1/devices/{dev}/volume` (shares the state PUTs' seq_num). */
    fun volume(seq: Int, volume: Int, commandId: String): JsonObject = buildJsonObject {
        put("seq_num", seq)
        put("volume", volume)
        put("command_id", commandId)
    }

    /** `POST connect-state/v1/player/command/from/{dev}/to/{dev}` envelope. */
    fun command(endpoint: String, commandId: String, fields: JsonObject = JsonObject(emptyMap())): JsonObject = buildJsonObject {
        putJsonObject("command") {
            fields.forEach { (k, v) -> put(k, v) }
            putJsonObject("logging_params") {
                putJsonArray("page_instance_ids") {}
                putJsonArray("interaction_ids") {}
                put("command_id", commandId)
            }
            put("endpoint", endpoint)
        }
    }

    /**
     * [order]: the context's songs in an order of the caller's (a sorted list, the app's own
     * shuffle), sent as the context's one page; the context then plays as that page, with the
     * account's shuffle off for it, instead of the list the server would read itself.
     * [shuffle]: the shuffle the play starts with (the user's, sent with it so a set_shuffling sent
     * just before is not undone by the play); null keeps the account's, off for an [order].
     */
    fun playFields(
        config: EngineConfig, contextUri: String, skipToUri: String?, skipToIndex: Int?, feature: String,
        order: List<String>? = null, orderStamp: Long = 0, shuffle: Boolean? = null,
    ): JsonObject = buildJsonObject {
        putJsonObject("context") {
            put("uri", contextUri)
            if (order.isNullOrEmpty()) put("url", "context://$contextUri")
            else {
                putJsonArray("pages") { add(buildJsonObject { putJsonArray("tracks") { order.forEach { u -> add(buildJsonObject { put("uri", u) }) } } }) }
            }
            putJsonObject("metadata") {}
        }
        putJsonObject("play_origin") {
            put("feature_identifier", feature)
            put("feature_version", config.buildVersion)
        }
        putJsonObject("options") {
            put("license", "tft")
            putJsonObject("skip_to") {
                if (skipToIndex != null) put("track_index", skipToIndex)
                if (skipToUri != null) put("track_uri", skipToUri)
            }
            putJsonObject("player_options_override") {
                when {
                    shuffle != null -> put("shuffling_context", shuffle)
                    !order.isNullOrEmpty() -> put("shuffling_context", false)
                }
            }
        }
    }

    /** `POST connect-state/v1/connect/transfer/from/{dev}/to/{dev}`. */
    fun transfer(restorePaused: String, commandId: String): JsonObject = buildJsonObject {
        putJsonObject("transfer_options") { put("restore_paused", restorePaused) }
        put("command_id", commandId)
    }

    /** One `melody/v1/msg/batch` body. */
    fun melodyBatch(messages: List<JsonObject>, sdkId: String, config: EngineConfig): JsonObject = buildJsonObject {
        put("messages", JsonArray(messages))
        put("sdk_id", sdkId)
        put("platform", config.platformIdentifier)
        put("client_version", "0.0.0")
    }

    fun melodyMessage(type: String, message: JsonObject): JsonObject = buildJsonObject {
        put("type", type)
        put("message", message)
    }

    fun emptyArray(): JsonArray = buildJsonArray {}
}
