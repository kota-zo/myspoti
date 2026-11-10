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

    // Sorted inclusive [start, end] ranges matching Unicode 15 LATIN, COMMON, and INHERITED scripts
    // so Android API 23 (where Character.UnicodeScript is unavailable) behaves identically to JVM 17.
    private val NEUTRAL_RANGES = intArrayOf(
        0x0, 0x2E9, 0x2EC, 0x36F, 0x374, 0x374, 0x37E, 0x37E, 0x385, 0x385, 0x387, 0x387,
        0x485, 0x486, 0x605, 0x605, 0x60C, 0x60C, 0x61B, 0x61B, 0x61F, 0x61F, 0x640, 0x640,
        0x64B, 0x655, 0x670, 0x670, 0x6DD, 0x6DD, 0x8E2, 0x8E2, 0x951, 0x954, 0x964, 0x965,
        0xE3F, 0xE3F, 0xFD5, 0xFD8, 0x10FB, 0x10FB, 0x16EB, 0x16ED, 0x1735, 0x1736, 0x1802, 0x1803,
        0x1805, 0x1805, 0x1AB0, 0x1ACE, 0x1CD0, 0x1CFA, 0x1D00, 0x1D25, 0x1D2C, 0x1D5C, 0x1D62, 0x1D65,
        0x1D6B, 0x1D77, 0x1D79, 0x1DBE, 0x1DC0, 0x1EFF, 0x2000, 0x2064, 0x2066, 0x2071, 0x2074, 0x208E,
        0x2090, 0x209C, 0x20A0, 0x20C0, 0x20D0, 0x20F0, 0x2100, 0x2125, 0x2127, 0x218B, 0x2190, 0x2426,
        0x2440, 0x244A, 0x2460, 0x27FF, 0x2900, 0x2B73, 0x2B76, 0x2B95, 0x2B97, 0x2BFF, 0x2C60, 0x2C7F,
        0x2E00, 0x2E5D, 0x2FF0, 0x2FFB, 0x3000, 0x3004, 0x3006, 0x3006, 0x3008, 0x3020, 0x302A, 0x302D,
        0x3030, 0x3037, 0x303C, 0x303F, 0x3099, 0x309C, 0x30A0, 0x30A0, 0x30FB, 0x30FC, 0x3190, 0x319F,
        0x31C0, 0x31E3, 0x3220, 0x325F, 0x327F, 0x32CF, 0x32FF, 0x32FF, 0x3358, 0x33FF, 0x4DC0, 0x4DFF,
        0xA700, 0xA7CA, 0xA7D0, 0xA7D1, 0xA7D3, 0xA7D3, 0xA7D5, 0xA7D9, 0xA7F2, 0xA7FF, 0xA830, 0xA839,
        0xA92E, 0xA92E, 0xA9CF, 0xA9CF, 0xAB30, 0xAB64, 0xAB66, 0xAB6B, 0xFB00, 0xFB06, 0xFD3E, 0xFD3F,
        0xFE00, 0xFE19, 0xFE20, 0xFE2D, 0xFE30, 0xFE52, 0xFE54, 0xFE66, 0xFE68, 0xFE6B, 0xFEFF, 0xFEFF,
        0xFF01, 0xFF65, 0xFF70, 0xFF70, 0xFF9E, 0xFF9F, 0xFFE0, 0xFFE6, 0xFFE8, 0xFFEE, 0xFFF9, 0xFFFD,
        0x10100, 0x10102, 0x10107, 0x10133, 0x10137, 0x1013F, 0x10190, 0x1019C, 0x101D0, 0x101FD, 0x102E0, 0x102FB,
        0x10780, 0x10785, 0x10787, 0x107B0, 0x107B2, 0x107BA, 0x1133B, 0x1133B, 0x1BCA0, 0x1BCA3, 0x1CF00, 0x1CF2D,
        0x1CF30, 0x1CF46, 0x1CF50, 0x1CFC3, 0x1D000, 0x1D0F5, 0x1D100, 0x1D126, 0x1D129, 0x1D1EA, 0x1D2C0, 0x1D2D3,
        0x1D2E0, 0x1D2F3, 0x1D300, 0x1D356, 0x1D360, 0x1D378, 0x1D400, 0x1D454, 0x1D456, 0x1D49C, 0x1D49E, 0x1D49F,
        0x1D4A2, 0x1D4A2, 0x1D4A5, 0x1D4A6, 0x1D4A9, 0x1D4AC, 0x1D4AE, 0x1D4B9, 0x1D4BB, 0x1D4BB, 0x1D4BD, 0x1D4C3,
        0x1D4C5, 0x1D505, 0x1D507, 0x1D50A, 0x1D50D, 0x1D514, 0x1D516, 0x1D51C, 0x1D51E, 0x1D539, 0x1D53B, 0x1D53E,
        0x1D540, 0x1D544, 0x1D546, 0x1D546, 0x1D54A, 0x1D550, 0x1D552, 0x1D6A5, 0x1D6A8, 0x1D7CB, 0x1D7CE, 0x1D7FF,
        0x1DF00, 0x1DF1E, 0x1DF25, 0x1DF2A, 0x1EC71, 0x1ECB4, 0x1ED01, 0x1ED3D, 0x1F000, 0x1F02B, 0x1F030, 0x1F093,
        0x1F0A0, 0x1F0AE, 0x1F0B1, 0x1F0BF, 0x1F0C1, 0x1F0CF, 0x1F0D1, 0x1F0F5, 0x1F100, 0x1F1AD, 0x1F1E6, 0x1F1FF,
        0x1F201, 0x1F202, 0x1F210, 0x1F23B, 0x1F240, 0x1F248, 0x1F250, 0x1F251, 0x1F260, 0x1F265, 0x1F300, 0x1F6D7,
        0x1F6DC, 0x1F6EC, 0x1F6F0, 0x1F6FC, 0x1F700, 0x1F776, 0x1F77B, 0x1F7D9, 0x1F7E0, 0x1F7EB, 0x1F7F0, 0x1F7F0,
        0x1F800, 0x1F80B, 0x1F810, 0x1F847, 0x1F850, 0x1F859, 0x1F860, 0x1F887, 0x1F890, 0x1F8AD, 0x1F8B0, 0x1F8B1,
        0x1F900, 0x1FA53, 0x1FA60, 0x1FA6D, 0x1FA70, 0x1FA7C, 0x1FA80, 0x1FA88, 0x1FA90, 0x1FABD, 0x1FABF, 0x1FAC5,
        0x1FACE, 0x1FADB, 0x1FAE0, 0x1FAE8, 0x1FAF0, 0x1FAF8, 0x1FB00, 0x1FB92, 0x1FB94, 0x1FBCA, 0x1FBF0, 0x1FBF9,
        0xE0001, 0xE0001, 0xE0020, 0xE007F, 0xE0100, 0xE01EF
    )

    private fun isNeutralScript(cp: Int): Boolean {
        var lo = 0
        var hi = (NEUTRAL_RANGES.size ushr 1) - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val s = NEUTRAL_RANGES[mid shl 1]
            val e = NEUTRAL_RANGES[(mid shl 1) + 1]
            if (cp < s) hi = mid - 1
            else if (cp > e) lo = mid + 1
            else return true
        }
        return false
    }

    /** Whether [text] has a letter of another script than Latin: what a romanization is for. */
    fun needs(text: String): Boolean {
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            if (Character.isLetter(cp) && !isNeutralScript(cp)) return true
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
