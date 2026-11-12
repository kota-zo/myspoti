/* SPDX-License-Identifier: GPL-3.0-only */
package app.myspoti.catalog

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.math.abs

/** One word (or syllable) of timed lyrics. */
data class TimedWord(val startMs: Long, val endMs: Long, val text: String)

/** One line: when it starts ([startMs], 0 for plain lyrics) and, timed word by word, its [words]. */
data class TimedLine(val startMs: Long, val text: String, val words: List<TimedWord>? = null)

data class TimedLyrics(val lines: List<TimedLine>, val synced: Boolean, val provider: String) {
    val wordSynced: Boolean get() = lines.any { !it.words.isNullOrEmpty() }
}

/**
 * Lyrics from open sources, with no music account: the AMLL TTML database (hand-timed syllable by
 * syllable by its community, CC0, by Spotify id), LyricsPlus (a public index of several catalogues'
 * word-timed lyrics, Apple Music's among them), NetEase Cloud Music's word-timed lyrics, then
 * LRCLIB's line-timed ones. Timed word by word first.
 */
class OpenLyrics(private val fetch: Fetch) {
    private val lrclib = LrcLib(fetch)

    /** The best of the open sources for the song: word-timed first, then line-timed, then plain. */
    suspend fun find(spotifyId: String?, title: String, artist: String, album: String?, durationMs: Long): TimedLyrics? = coroutineScope {
        val amll = async { runCatching { spotifyId?.let { amll(it) } }.getOrNull() }
        val netease = async { runCatching { netease(title, artist, durationMs) }.getOrNull() }
        val plus = async { runCatching { lyricsPlus(title, artist, durationMs) }.getOrNull() }
        val line = async { runCatching { lrclib.lyrics(title, artist, album, durationMs)?.let(::fromLrclib) }.getOrNull() }
        amll.await()?.takeIf { it.wordSynced } ?: plus.await()?.takeIf { it.wordSynced } ?: netease.await()?.takeIf { it.wordSynced }
            ?: line.await()?.takeIf { it.synced } ?: netease.await() ?: line.await()
    }

    /** The song's file in the AMLL database, by its Spotify id. */
    suspend fun amll(spotifyId: String): TimedLyrics? {
        if (spotifyId.isBlank() || !spotifyId.all { it.isLetterOrDigit() }) return null
        val r = fetch.get("$AMLL/spotify-lyrics/$spotifyId.ttml")
        if (r.status != 200) return null
        return parseTtml(r.body)?.let { TimedLyrics(it, true, "AMLL TTML DB") }
    }

    /** LyricsPlus's answer for the song (its own match checked against ours: title, artist, length). */
    suspend fun lyricsPlus(title: String, artist: String, durationMs: Long): TimedLyrics? {
        for (base in LYRICS_PLUS) {
            val r = runCatching { fetch.get("$base/v2/lyrics/get?title=${title.q()}&artist=${artist.q()}&source=apple,lyricsplus,musixmatch-word,qq,deezer,musixmatch" + if (durationMs > 0) "&duration=${durationMs / 1000}" else "") }.getOrNull() ?: continue
            if (r.status != 200) continue
            val o = runCatching { parseObject(NAME, r.body) }.getOrNull() ?: continue
            val meta = o["metadata"] as? JsonObject
            if (meta != null) {
                val d = clock(meta.str("totalDuration"))
                if (!same(meta.str("title").orEmpty(), title) || !same(meta.str("artist").orEmpty(), artist) || (d > 0 && durationMs > 0 && abs(d - durationMs) > MOST_OFF_MS)) return null
            }
            val lines = (o["lyrics"] as? JsonArray).objects().mapNotNull { l ->
                val words = (l["syllabus"] as? JsonArray).objects()
                    .filter { (it["isBackground"] as? JsonPrimitive)?.contentOrNull != "true" }
                    .map { w -> val t = w.long("time") ?: 0; TimedWord(t, t + (w.long("duration") ?: 0), w.str("text").orEmpty()) }
                    .takeIf { it.isNotEmpty() }
                val text = (words?.joinToString("") { it.text } ?: l.str("text").orEmpty()).replace(Regex("\\s+"), " ").trim()
                if (text.isEmpty()) null else TimedLine(l.long("time") ?: 0, text, words)
            }
            if (lines.isEmpty()) continue
            val source = meta?.str("source").orEmpty()
            return TimedLyrics(lines, true, if (source.contains("apple", true)) "Apple Music · LyricsPlus" else "LyricsPlus")
        }
        return null
    }

    /** NetEase's word-timed lyrics of the song it finds for [title] by [artist] near [durationMs]. */
    suspend fun netease(title: String, artist: String, durationMs: Long): TimedLyrics? {
        val s = fetch.get("https://music.163.com/api/search/get?s=${"$title $artist".q()}&type=1&limit=6")
        if (s.status != 200) return null
        val songs = ((parseObject(NAME, s.body)["result"] as? JsonObject)?.get("songs") as? JsonArray).objects()
        val song = songs.firstOrNull { o ->
            val d = o.long("duration") ?: return@firstOrNull false
            val a = (o["artists"] as? JsonArray).objects().firstOrNull()?.str("name").orEmpty()
            abs(d - durationMs) <= MOST_OFF_MS && same(o.str("name").orEmpty(), title) && same(a, artist)
        } ?: return null
        val r = fetch.get("https://music.163.com/api/song/lyric/v1?id=${song.long("id")}&lv=-1&yv=-1&tv=-1&rv=-1")
        if (r.status != 200) return null
        val o = parseObject(NAME, r.body)
        val yrc = ((o["yrc"] as? JsonObject)?.get("lyric") as? JsonPrimitive)?.contentOrNull.orEmpty()
        parseYrc(yrc).takeIf { it.isNotEmpty() }?.let { return TimedLyrics(it, true, "NetEase Cloud Music") }
        val lrc = ((o["lrc"] as? JsonObject)?.get("lyric") as? JsonPrimitive)?.contentOrNull.orEmpty()
        return parseLrc(lrc).filter { !it.text.contains(" : ") }.takeIf { it.isNotEmpty() }?.let { TimedLyrics(it, true, "NetEase Cloud Music") }
    }

    private fun fromLrclib(f: FoundLyrics): TimedLyrics? = when {
        f.synced != null -> parseLrc(f.synced).takeIf { it.isNotEmpty() }?.let { TimedLyrics(it, true, "LRCLIB") }
        f.plain != null -> TimedLyrics(f.plain.lines().filter { it.isNotBlank() }.map { TimedLine(0, it.trim()) }, false, "LRCLIB")
        else -> null
    }

    companion object {
        private const val NAME = "lyrics"
        private const val AMLL = "https://raw.githubusercontent.com/amll-dev/amll-ttml-db/main"
        private const val MOST_OFF_MS = 3_000L
        private val LYRICS_PLUS = listOf("https://lyricsplus.binimum.org", "https://lyricsplus.prjktla.my.id")
        private fun String.q() = java.net.URLEncoder.encode(this, "UTF-8")

        private fun key(s: String) = s.lowercase().replace(Regex("""\s*[(\[].*?[)\]]"""), "").filter { it.isLetterOrDigit() }
        /** The same title or artist, give or take its asides ("(Remastered)") and punctuation. */
        internal fun same(a: String, b: String): Boolean { val x = key(a); val y = key(b); return x.isNotEmpty() && (x == y || x.startsWith(y) || y.startsWith(x)) }

        private val yrcLine = Regex("""^\[(\d+),(\d+)]""")
        private val yrcWord = Regex("""\((\d+),(\d+),\d+\)([^(]*)""")

        /** NetEase's YRC: `[start,length]` then `(start,length,0)word` per word; JSON rows (credits) left out. */
        fun parseYrc(yrc: String): List<TimedLine> = yrc.lineSequence().mapNotNull { row ->
            val head = yrcLine.find(row) ?: return@mapNotNull null
            val words = yrcWord.findAll(row, head.range.last + 1).map { m ->
                val start = m.groupValues[1].toLong()
                TimedWord(start, start + m.groupValues[2].toLong(), m.groupValues[3])
            }.filter { it.text.isNotEmpty() }.toList()
            val text = words.joinToString("") { it.text }.replace(Regex("\\s+"), " ").trim()
            if (text.isEmpty() || text.contains(" : ")) null else TimedLine(head.groupValues[1].toLong(), text, words)
        }.toList()

        private val lrcTag = Regex("""\[(\d+):(\d+(?:\.\d+)?)]""")

        /** LRC: one or more `[mm:ss.xx]` before each line. */
        fun parseLrc(lrc: String): List<TimedLine> = lrc.lineSequence().flatMap { row ->
            val tags = lrcTag.findAll(row).toList()
            val text = row.substring(tags.lastOrNull()?.range?.last?.plus(1) ?: 0).trim()
            tags.map { t -> TimedLine((t.groupValues[1].toLong() * 60_000 + t.groupValues[2].toDouble() * 1000).toLong(), text) }
        }.filter { it.text.isNotEmpty() }.sortedBy { it.startMs }.toList()

        /** A TTML clock ("01:02.345", "62.345", "1:02:03.4") in ms; -1 when it is not one. */
        internal fun clock(v: String?): Long {
            if (v.isNullOrBlank()) return -1
            val parts = v.trim().removeSuffix("s").split(':')
            return runCatching { parts.fold(0.0) { acc, p -> acc * 60 + p.toDouble() }.times(1000).toLong() }.getOrDefault(-1)
        }

        /**
         * The lines of a TTML file: a <p> per line, a timed <span> per word or syllable (the text
         * between two spans, a space, goes with the word before it). Spans with a role (backing
         * vocals, translation, romanization) are left out.
         */
        fun parseTtml(xml: String): List<TimedLine>? = runCatching {
            val f = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply { isNamespaceAware = false }
            // No outside entity is read.
            runCatching { f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            val doc = f.newDocumentBuilder().parse(org.xml.sax.InputSource(java.io.StringReader(xml)))
            val ps = doc.getElementsByTagName("p")
            (0 until ps.length).mapNotNull { i ->
                val p = ps.item(i) as org.w3c.dom.Element
                val words = ArrayList<TimedWord>()
                val kids = p.childNodes
                for (k in 0 until kids.length) {
                    val n = kids.item(k)
                    when {
                        n is org.w3c.dom.Element && n.tagName == "span" && n.getAttribute("ttm:role").isEmpty() ->
                            words += TimedWord(clock(n.getAttribute("begin")), clock(n.getAttribute("end")), n.textContent)
                        n.nodeType == org.w3c.dom.Node.TEXT_NODE && words.isNotEmpty() ->
                            words[words.lastIndex] = words.last().let { it.copy(text = it.text + n.textContent) }
                    }
                }
                val text = words.joinToString("") { it.text }.replace(Regex("\\s+"), " ").trim()
                val start = clock(p.getAttribute("begin"))
                if (text.isEmpty() || start < 0) null else TimedLine(start, text, words.filter { it.startMs >= 0 }.takeIf { it.isNotEmpty() })
            }.takeIf { it.isNotEmpty() }
        }.getOrNull()
    }
}
