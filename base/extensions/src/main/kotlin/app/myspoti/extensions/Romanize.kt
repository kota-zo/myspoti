package app.myspoti.extensions

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * The `lyrics-romanize` hook: the lines of a song's lyrics written in another script than Latin
 * (Japanese, Korean, Cyrillic...) that their source left without a romanization are sent by
 * index, and the extension answers the romanizations it has. A romanization the source gave
 * (Apple Music, NetEase) is never asked for nor replaced: it was written by people.
 */
object Romanize {
    /** At most this many characters in a romanization, as in a line of lyrics. */
    const val MAX_TEXT = 500

    // Digits, punctuation, symbols ("♪") and combining marks belong to no script of their own.
    private val NEUTRAL = setOf(Character.UnicodeScript.LATIN, Character.UnicodeScript.COMMON, Character.UnicodeScript.INHERITED)

    /** Whether [text] has a letter of another script than Latin: what a romanization is for. */
    fun needs(text: String): Boolean {
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            if (Character.isLetter(cp) && Character.UnicodeScript.of(cp) !in NEUTRAL) return true
            i += Character.charCount(cp)
        }
        return false
    }

    /**
     * The lines a romanization is asked for, by their index in [lines]: the ones not [romanized]
     * yet whose [text] [needs] one, in order.
     */
    fun <T> wanted(lines: List<T>, text: (T) -> String, romanized: (T) -> Boolean): Map<Int, String> = buildMap {
        lines.forEachIndexed { i, line -> if (!romanized(line) && needs(text(line))) put(i, text(line)) }
    }

    /** The hook's input: `{ track, language?, lines: [{ index, text }] }`, [language] as the lyrics' source gives it. */
    fun request(track: TrackInfo, language: String?, lines: Map<Int, String>): String = buildJsonObject {
        put("track", ExtJson.encodeToJsonElement(TrackInfo.serializer(), track))
        language?.let { put("language", it) }
        putJsonArray("lines") { lines.forEach { (index, text) -> addJsonObject { put("index", index); put("text", text) } } }
    }.toString()

    /** The lines a [request] asks for, index → text: what the desktop runner prints the answers against. */
    fun asked(request: String): Map<Int, String> {
        val lines = runCatching { ExtJson.parseToJsonElement(request).jsonObject["lines"] as? JsonArray }.getOrNull() ?: return emptyMap()
        return buildMap {
            for (e in lines) {
                val l = e as? JsonObject ?: continue
                val index = (l["index"] as? JsonPrimitive)?.intOrNull ?: continue
                put(index, (l["text"] as? JsonPrimitive)?.contentOrNull.orEmpty())
            }
        }
    }

    /**
     * `{ lines: [{ index, romanization }] }` → index → romanization, trimmed, [MAX_TEXT] characters
     * at most. A line that is not an object, an index that is not a whole number, a romanization
     * that is not a text or is blank, and an index already answered are ignored, never an error;
     * whether an index was asked is the caller's to check. The answer itself must be an object
     * with `lines`.
     */
    fun parse(json: String): Map<Int, String> {
        val o = runCatching { ExtJson.parseToJsonElement(json).jsonObject }.getOrNull()
            ?: throw ExtensionException("lyrics-romanize: not an object")
        val raw = o["lines"] as? JsonArray
            ?: throw ExtensionException("lyrics-romanize: \"lines\" (an array of { index, romanization })")
        val out = LinkedHashMap<Int, String>()
        for (e in raw) {
            val l = e as? JsonObject ?: continue
            val index = (l["index"] as? JsonPrimitive)?.intOrNull ?: continue
            val text = (l["romanization"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.take(MAX_TEXT)?.ifBlank { null } ?: continue
            out.putIfAbsent(index, text)
        }
        return out
    }
}
