package app.myspoti.extensions

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * One try to play a song through the extensions, for "Why this source?" and the diagnostic report:
 * the sources asked and how each ended, the one chosen, how its address opened, the quality it
 * announced and the one the player detected, and the fallback taken.
 */
data class PlayAttempt(
    val id: Long,
    val at: Long,
    val trackId: String,
    val title: String,
    /** The quality asked, when the app asks one (download-source; audio-source asks none yet). */
    val requestedQuality: String? = null,
    /** Asked before the song plays (the next one of the queue). */
    val ahead: Boolean = false,
    val asked: List<Ask> = emptyList(),
    /** The extension whose answer plays, or null (none had it). */
    val chosen: String? = null,
    /** What the chosen one announced ("FLAC · 24-bit · 96 kHz"), or null. */
    val announced: String? = null,
    /** How its address opened in the player (the last try). */
    val open: Open? = null,
    /** What the player's decoder reported ("audio/flac · 44.1 kHz · 2 ch"), or null. */
    val detected: String? = null,
    /** What the app fell back to ("asked again: HTTP 403", "Spotify"), or null. */
    val fallback: String? = null,
    /** The last error the player reported for it. */
    val error: String? = null,
) {
    /** One source: [outcome] ([Outcome.key]) after [ms]; [skippedReason] when it was not asked. */
    data class Ask(val extension: String, val outcome: String, val ms: Long, val skippedReason: String? = null, val detail: String? = null)

    /** Opening the chosen address: [url] is kept without its query (it may carry a token). */
    data class Open(val ok: Boolean, val ms: Long, val httpStatus: Int? = null, val error: String? = null, val url: String? = null)

    /** As JSON, every address without user info, query or fragment, and every text with its addresses masked. */
    fun toJson(): JsonObject = JsonObject(buildMap {
        put("id", JsonPrimitive(id))
        put("at", JsonPrimitive(at))
        put("track", JsonPrimitive(trackId))
        put("title", JsonPrimitive(title))
        requestedQuality?.let { put("requestedQuality", JsonPrimitive(it)) }
        if (ahead) put("ahead", JsonPrimitive(true))
        put("asked", JsonArray(asked.map { a ->
            JsonObject(buildMap {
                put("extension", JsonPrimitive(a.extension))
                put("outcome", JsonPrimitive(a.outcome))
                put("ms", JsonPrimitive(a.ms))
                a.skippedReason?.let { put("skippedReason", JsonPrimitive(maskText(it))) }
                a.detail?.let { put("detail", JsonPrimitive(maskText(it))) }
            })
        }))
        put("chosen", chosen?.let(::JsonPrimitive) ?: JsonNull)
        announced?.let { put("announced", JsonPrimitive(it)) }
        open?.let { o ->
            put("open", JsonObject(buildMap<String, JsonElement> {
                put("ok", JsonPrimitive(o.ok))
                put("ms", JsonPrimitive(o.ms))
                o.httpStatus?.let { put("httpStatus", JsonPrimitive(it)) }
                o.error?.let { put("error", JsonPrimitive(maskText(it))) }
                o.url?.let { put("url", JsonPrimitive(maskUrl(it))) }
            }))
        }
        detected?.let { put("detected", JsonPrimitive(it)) }
        fallback?.let { put("fallback", JsonPrimitive(maskText(it))) }
        error?.let { put("error", JsonPrimitive(maskText(it))) }
    })

    /** One masked line, for the diagnostic report's header. */
    fun summary(): String = buildString {
        append("#$id ${title.take(60)}")
        if (ahead) append(" (ahead)")
        requestedQuality?.let { append(" wanted $it") }
        append(": ")
        append(asked.joinToString { a -> "${a.extension}=${a.outcome}" + (a.skippedReason?.let { " (${maskText(it)})" } ?: " ${a.ms} ms") }.ifEmpty { "none asked" })
        append(chosen?.let { " -> $it" } ?: " -> none")
        open?.let { o -> append("; open ${if (o.ok) "ok" else "failed"} ${o.ms} ms" + (o.httpStatus?.let { " HTTP $it" } ?: "") + (o.error?.let { " ${maskText(it).take(120)}" } ?: "")) }
        announced?.let { append("; announced $it") }
        detected?.let { append("; detected $it") }
        fallback?.let { append("; fallback ${maskText(it)}") }
        error?.let { append("; error ${maskText(it).take(160)}") }
    }

    companion object {
        private val URL = Regex("""(?i)\b(?:https?|wss?)://[^\s"'<>]+""")

        /** [url] without user info, query or fragment ([SafeNet.redact]); not an address: its part before `?`/`#`. */
        fun maskUrl(url: String): String =
            url.toHttpUrlOrNull()?.let(SafeNet::redact) ?: url.substringBefore('?').substringBefore('#')

        /** [text] with each address in it masked ([maskUrl]). */
        fun maskText(text: String): String = URL.replace(text) { maskUrl(it.value) }
    }
}

/** The last [capacity] [PlayAttempt]s, newest last. Thread-safe. */
class PlayAttempts(private val capacity: Int = 20, private val clock: () -> Long = System::currentTimeMillis) {
    private val ring = ArrayDeque<PlayAttempt>()
    private var nextId = 1L

    /** A new attempt for [trackId], its id. */
    @Synchronized
    fun start(trackId: String, title: String, ahead: Boolean = false, requestedQuality: String? = null): Long {
        val id = nextId++
        ring.addLast(PlayAttempt(id, clock(), trackId, title, requestedQuality, ahead))
        while (ring.size > capacity) ring.removeFirst()
        return id
    }

    /** Changes the attempt [id] (gone from the ring: nothing). */
    @Synchronized
    fun update(id: Long, change: (PlayAttempt) -> PlayAttempt) {
        val i = ring.indexOfFirst { it.id == id }
        if (i >= 0) ring[i] = change(ring[i])
    }

    /** Changes the latest attempt of [trackId] that chose a source, else its latest (none: nothing). */
    @Synchronized
    fun updateLatest(trackId: String, change: (PlayAttempt) -> PlayAttempt) {
        val i = ring.indexOfLast { it.trackId == trackId && it.chosen != null }.takeIf { it >= 0 }
            ?: ring.indexOfLast { it.trackId == trackId }.takeIf { it >= 0 } ?: return
        ring[i] = change(ring[i])
    }

    /** The latest attempt for [trackId] (one that chose a source first), or null. */
    @Synchronized
    fun latest(trackId: String): PlayAttempt? = ring.lastOrNull { it.trackId == trackId && it.chosen != null } ?: ring.lastOrNull { it.trackId == trackId }

    /** The last [n] attempts, newest first. */
    @Synchronized
    fun last(n: Int = capacity): List<PlayAttempt> = ring.toList().asReversed().take(n.coerceAtLeast(0))

    /** The last [n] as a masked JSON array, newest first. */
    fun toJson(n: Int = capacity): JsonArray = JsonArray(last(n).map(PlayAttempt::toJson))

    @Synchronized
    fun clear() = ring.clear()
}
