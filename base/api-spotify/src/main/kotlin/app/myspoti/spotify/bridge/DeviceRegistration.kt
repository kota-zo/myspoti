package app.myspoti.spotify.bridge

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** The page's player registration, as the host relays it. */
object DeviceRegistration {
    /** The player's registration (POST track-playback/v1/devices), to rewrite with [withDeviceName]. */
    fun isDeviceRegistration(method: String, url: String): Boolean =
        method.equals("POST", ignoreCase = true) && url.substringBefore('?').endsWith("/track-playback/v1/devices")

    /**
     * The name the player registers itself under is the name Spotify shows on every other device.
     * The Web Player's own ("Web Player (Chrome)") is replaced by the name the phone already has,
     * so this client appears as the phone it runs on; **nothing else is touched** — capabilities,
     * platform, versions stay byte-for-byte what the real Web Player sends.
     *
     * An earlier version also removed `manifest_urls_audio_ad` from the manifest formats, which made
     * this registration the only one of its kind (the real one declares it twice) and was the prime
     * suspect for the `402 COMMAND_FAILED` refusals: this client never lies about what it can play.
     */
    fun withDeviceName(body: String, name: String): String = runCatching {
        val device = Json.parseToJsonElement(body).jsonObject["device"]?.jsonObject ?: return body
        val raw = device["name"] as? JsonPrimitive ?: return body
        if (raw is kotlinx.serialization.json.JsonNull) return body
        val old = raw.content
        if (old.isBlank() || old == name || name.isBlank()) return body
        // A plain string swap, not a re-serialisation: the registration keeps its exact shape,
        // duplicate array entries included.
        val quoted = name.replace("\\", "\\\\").replace("\"", "\\\"")
        body.replace("\"$old\"", "\"$quoted\"")
    }.getOrDefault(body)
}
