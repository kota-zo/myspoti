package app.myspoti.spotify.bridge

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Where the Web Player page runs: an Android WebView or desktop Chrome over CDP.
 *
 * A host loads open.spotify.com with the user's session, injects [BridgeScript] after each
 * page load, refuses what [AdBlock.isBlocked] says and relays the page's messages.
 */
interface BridgeHost {
    /** Runs [script] in the page. Fire and forget: answers come back through [messages]. */
    suspend fun evaluate(script: String)

    /**
     * Runs [script] like [evaluate] and returns the JSON of its value once the page ran it, or
     * null when this host cannot tell. Throws when the script could not be handed to the page,
     * so a call fails at once instead of waiting for an answer that cannot come.
     */
    suspend fun evaluateValue(script: String): String? { evaluate(script); return null }

    /** JSON strings posted by the bridge (`MyspotiHost.postMessage` / `__mspHostPost`). */
    val messages: Flow<String>

    /**
     * A command is about to be sent to the page (not a poll): a host that lets an idle page be
     * throttled wakes it now, so the work behind the command runs at full speed. Nothing by default.
     */
    fun commandComing() {}
}

/** The bridge's source, bundled as a resource of this module. */
object BridgeScript {
    val source: String by lazy {
        BridgeScript::class.java.getResourceAsStream("/bridge/player-bridge.js")!!
            .bufferedReader().use { it.readText() }
    }
}

/**
 * The media manifests Spotify sent the player: each song of a track-playback reply's state
 * machines, its `manifest` (formats and file ids) as it came. Read by extensions
 * (`spotify.media`), never changed; the last [MAX] songs are kept.
 */
object MediaManifests {
    private const val MAX = 200
    private val byUri = object : LinkedHashMap<String, JsonObject>(MAX, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, JsonObject>?) = size > MAX
    }

    /** Keeps the manifests of a track-playback reply's songs. */
    fun record(body: String) {
        if ("\"manifest\"" !in body) return
        val root = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return
        for (key in listOf("state_machine", "previous_state_machine")) {
            val tracks = (root[key] as? JsonObject)?.get("tracks") as? JsonArray ?: continue
            tracks.forEach { t ->
                val track = t as? JsonObject ?: return@forEach
                val uri = ((track["metadata"] as? JsonObject)?.get("uri") as? JsonPrimitive)?.contentOrNull ?: return@forEach
                val manifest = track["manifest"] as? JsonObject ?: return@forEach
                if (uri.startsWith("spotify:track:") && manifest.isNotEmpty()) synchronized(byUri) { byUri[uri] = manifest }
            }
        }
    }

    fun of(trackUri: String): JsonObject? = synchronized(byUri) { byUri[trackUri] }
}
