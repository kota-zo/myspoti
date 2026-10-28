package com.music.spotui.cast

import org.json.JSONArray
import org.json.JSONObject

/**
 * The JSON messages Myspoti sends a Cast device and reads from it (CastV2 namespaces), for the
 * Default Media Receiver (no registration needed, plays what an address gives).
 */
object CastProtocol {
    const val NS_CONNECTION = "urn:x-cast:com.google.cast.tp.connection"
    const val NS_HEARTBEAT = "urn:x-cast:com.google.cast.tp.heartbeat"
    const val NS_RECEIVER = "urn:x-cast:com.google.cast.receiver"
    const val NS_MEDIA = "urn:x-cast:com.google.cast.media"
    const val RECEIVER = "receiver-0"
    /** Google's Default Media Receiver. */
    const val DEFAULT_MEDIA_RECEIVER = "CC1AD845"

    fun type(t: String) = JSONObject().put("type", t)
    fun connect() = type("CONNECT").put("userAgent", "Myspoti").put("origin", JSONObject())
    fun launch(requestId: Int) = type("LAUNCH").put("appId", DEFAULT_MEDIA_RECEIVER).put("requestId", requestId)
    fun stopApp(requestId: Int, sessionId: String) = type("STOP").put("sessionId", sessionId).put("requestId", requestId)
    fun setVolume(requestId: Int, level: Double) =
        type("SET_VOLUME").put("volume", JSONObject().put("level", level.coerceIn(0.0, 1.0))).put("requestId", requestId)
    fun media(t: String, requestId: Int, mediaSessionId: Int) = type(t).put("mediaSessionId", mediaSessionId).put("requestId", requestId)
    fun seek(requestId: Int, mediaSessionId: Int, ms: Long) = media("SEEK", requestId, mediaSessionId).put("currentTime", ms / 1000.0)

    /** What to play: [url] of type [mime], with the song's names and cover, from [startMs]. */
    fun load(requestId: Int, sessionId: String, url: String, mime: String, durationMs: Long,
             title: String, artist: String, album: String, cover: String?, startMs: Long, autoplay: Boolean): JSONObject {
        val meta = JSONObject().put("metadataType", 3).put("title", title).put("artist", artist).put("albumName", album)
        if (!cover.isNullOrBlank()) meta.put("images", JSONArray().put(JSONObject().put("url", cover)))
        val media = JSONObject().put("contentId", url).put("contentUrl", url).put("streamType", "BUFFERED")
            .put("contentType", mime).put("metadata", meta)
        if (durationMs > 0) media.put("duration", durationMs / 1000.0)
        return type("LOAD").put("requestId", requestId).put("sessionId", sessionId).put("media", media)
            .put("autoplay", autoplay).put("currentTime", startMs.coerceAtLeast(0) / 1000.0)
    }

    /** The Default Media Receiver running on the device, from a RECEIVER_STATUS. */
    data class App(val sessionId: String, val transportId: String)
    data class Receiver(val app: App?, val volume: Double?, val muted: Boolean)

    fun receiver(status: JSONObject): Receiver {
        val s = status.optJSONObject("status") ?: JSONObject()
        val apps = s.optJSONArray("applications")
        var app: App? = null
        if (apps != null) for (k in 0 until apps.length()) {
            val a = apps.optJSONObject(k) ?: continue
            if (a.optString("appId") == DEFAULT_MEDIA_RECEIVER) app = App(a.optString("sessionId"), a.optString("transportId"))
        }
        val v = s.optJSONObject("volume")
        return Receiver(app, v?.optDouble("level")?.takeIf { !it.isNaN() }, v?.optBoolean("muted") == true)
    }

    /** One media session's state, from a MEDIA_STATUS. */
    data class Media(val mediaSessionId: Int, val playerState: String, val positionMs: Long, val idleReason: String?, val contentId: String?)

    /** The first media status of a MEDIA_STATUS, or null when the receiver has none (nothing loaded). */
    fun media(message: JSONObject): Media? {
        val s = message.optJSONArray("status")?.optJSONObject(0) ?: return null
        val pos = s.optDouble("currentTime", 0.0).let { if (it.isNaN()) 0L else (it * 1000).toLong() }
        return Media(
            s.optInt("mediaSessionId", -1), s.optString("playerState", "IDLE"), pos,
            s.optString("idleReason", "").ifBlank { null },
            s.optJSONObject("media")?.optString("contentId")?.ifBlank { null },
        )
    }
}
