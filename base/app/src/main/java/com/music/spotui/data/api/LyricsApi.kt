package com.music.spotui.data.api

import android.util.Log
import com.music.spotui.data.entity.LyricLine
import com.music.spotui.data.entity.LyricWord
import com.music.spotui.data.entity.Lyrics
import com.music.spotui.data.entity.LyricsColors
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Lyrics resolver, in the order of the spec (SPEC-V1 §5), with the provider map of
 * LiriQo / Syncanto (MIT, AlFarrizi-Studio / alfzcore) as reference:
 *
 * 1. Spotify's own color-lyrics, by track id: exactly what the official app shows,
 *    with its colours and credits.
 * 2. LRCLIB (lrclib.net, open API for third-party apps), line-synced or plain.
 * 3. Opt-in ("sources supplémentaires"): NetEase Cloud Music (line-synced LRC) and
 *    Genius (plain text). Off by default.
 *
 * Fallback results are only kept when title, artist and length match the track.
 * LRCLIB asks clients to send a descriptive User-Agent; we oblige.
 */
object LyricsApi {
    private const val LRCLIB = "https://lrclib.net/api"
    private const val UA = "Myspoti (https://github.com/)"

    /** What the resolver needs to know about a track. */
    data class Track(
        val trackId: String,
        val title: String,
        val artist: String,
        val album: String,
        val durationMs: Long,
        val coverUrl: String = "",
    ) {
        val key: String get() = trackId.ifBlank { "${title.lowercase()}|${artist.substringBefore(",").trim().lowercase()}" }
    }

    // Cache by track: re-opening the lyrics (card + full screen ask for the same
    // track) is instant. A miss is cached too, but only for MISS_RETRY_MS — a
    // "not found" is often just a timeout, not a fact.
    private class Miss(val at: Long = System.currentTimeMillis())
    private const val MISS_RETRY_MS = 120_000L
    // Newest MEMORY_MAX tracks only; the older ones are on storage (LyricsDisk) or asked for again.
    private const val MEMORY_MAX = 100
    private val cache: MutableMap<String, Any> = java.util.Collections.synchronizedMap( // Lyrics | Miss
        object : LinkedHashMap<String, Any>(128, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Any>) = size > MEMORY_MAX
        }
    )
    private val inFlight = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Deferred<Lyrics?>>()
    private val scope =
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)

    /** Set by the settings: also ask NetEase and Genius when nothing better is found. */
    @Volatile var extraSources: Boolean = false

    /** Set by the settings: lyrics timed word by word first (karaoke), line by line otherwise. */
    @Volatile var wordSync: Boolean = true

    /** How long a word-synced source is waited for before the line-synced ones answer instead. */
    private const val WORD_WAIT_MS = 4_000L

    /** Lyrics to show while better ones are looked for (track key → Spotify's line-timed lyrics). */
    val early = kotlinx.coroutines.flow.MutableStateFlow<Pair<String, Lyrics>?>(null)


    fun cached(track: Track): Lyrics? = cache[track.key] as? Lyrics

    /** Warm the cache in the background (current track and the next one). */
    fun prefetch(track: Track) {
        if (track.title.isBlank() && track.trackId.isBlank()) return
        val c = cache[track.key]
        if (c is Lyrics) return
        if (c is Miss && System.currentTimeMillis() - c.at < MISS_RETRY_MS) return
        if (inFlight.containsKey(track.key)) return
        scope.launch { runCatching { fetch(track) } }
    }

    fun forget(track: Track) { cache.remove(track.key); scope.launch { LyricsDisk.drop(track.key) } }

    /** A setting changed what is looked for: every song is read again when next shown. */
    fun forgetAll() { cache.clear(); scope.launch { LyricsDisk.clear() } }

    suspend fun fetch(track: Track): Lyrics? {
        when (val c = cache[track.key]) {
            is Lyrics -> return c
            is Miss -> if (System.currentTimeMillis() - c.at < MISS_RETRY_MS) return null
        }
        // One request per track even when the card and the full screen ask together.
        val job = synchronized(inFlight) {
            inFlight[track.key] ?: scope.async(start = kotlinx.coroutines.CoroutineStart.LAZY) {
                // Read on an earlier run: from storage, not asked for again.
                LyricsDisk.read(track.key)?.also { cache[track.key] = it } ?: resolve(track)
            }.also { created ->
                inFlight[track.key] = created
                created.invokeOnCompletion { inFlight.remove(track.key, created) }
                created.start()
            }
        }
        // A cancelled card only stops waiting; the shared request owns cleanup.
        return job.await()
    }

    private suspend fun resolve(track: Track): Lyrics? = coroutineScope {
        // Extensions that provide lyrics are asked at the same time as the app's own sources
        // (each within its time limit); the better answer is kept (LyricsMerge), Spotify's own
        // complete lyrics first at the same level, and then the extensions are not waited for.
        val ext = if (extensionsAsked()) async {
            runCatching {
                com.music.spotui.extensions.Extensions.lyricsFor(track.trackId, track.title, track.artist, track.album, track.durationMs, track.coverUrl)
            }.getOrNull()
        } else null
        val own = if (wordSync) wordFirst(track) else fromSpotify(track) ?: lineFallbacks(track)
        val fromExt = when {
            ext == null -> null
            own != null && com.music.spotui.extensions.LyricsMerge.rank(own) == 3 && com.music.spotui.extensions.LyricsMerge.isSpotifyComplete(own) -> { ext.cancel(); null }
            else -> ext.await()
        }
        val result = com.music.spotui.extensions.LyricsMerge.choose(own, fromExt)
        cache[track.key] = result ?: Miss()
        result?.let { LyricsDisk.write(track.key, it) }
        Log.d("LyricsApi", "${track.title}: ${result?.let { "${it.lines.size} lines from ${it.provider}, synced=${it.synced}, words=${it.wordSynced}" } ?: "none"}")
        result
    }

    /**
     * The lyrics of the last [MAX_FILES] tracks, one file each in private storage, so a song heard
     * before is not looked up again after a restart. Only line- or word-synced lyrics are kept (a
     * plain text or a Free-cap preview may just be a fallback of the moment), for [MAX_AGE_MS].
     * The oldest file by last use goes first. Every call copes with there being no app (unit tests).
     */
    private object LyricsDisk {
        private const val MAX_FILES = 300
        private const val MAX_AGE_MS = 14 * 24 * 3_600_000L
        private val dir: java.io.File? by lazy {
            runCatching {
                val files = com.music.spotui.di.Graph.app.filesDir
                // lyrics_cache1 kept word timings moved by a wrong pairing (God's Plan off by 74 s): dropped once.
                java.io.File(files, "lyrics_cache1").takeIf { it.exists() }?.deleteRecursively()
                java.io.File(files, "lyrics_cache2").also { it.mkdirs() }
            }.getOrNull()
        }
        private var writes = 0

        private fun file(key: String): java.io.File? {
            val sha = java.security.MessageDigest.getInstance("SHA-1").digest(key.toByteArray())
            return dir?.let { java.io.File(it, sha.joinToString("") { b -> "%02x".format(b) } + ".json") }
        }

        fun read(key: String): Lyrics? = runCatching {
            val f = file(key)?.takeIf { it.exists() } ?: return null
            val o = JSONObject(f.readText())
            if (o.getString("key") != key) return null
            if (System.currentTimeMillis() - o.getLong("at") > MAX_AGE_MS) { f.delete(); return null }
            f.setLastModified(System.currentTimeMillis()) // used just now: it stays
            decode(o)
        }.onFailure { Log.w("LyricsApi", "unreadable lyrics file: ${it.javaClass.simpleName}") }.getOrNull()

        fun write(key: String, lyrics: Lyrics) {
            if (!lyrics.synced || !lyrics.uncapped || lyrics.isEmpty) return
            runCatching {
                val f = file(key) ?: return
                val o = encode(lyrics).put("key", key).put("at", System.currentTimeMillis())
                val tmp = java.io.File(f.parentFile, f.name + ".tmp")
                tmp.writeText(o.toString())
                if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
                if (++writes % 20 == 0) prune()
            }.onFailure { Log.w("LyricsApi", "could not keep lyrics: ${it.javaClass.simpleName}") }
        }

        fun drop(key: String) { runCatching { file(key)?.delete() } }

        fun clear() { runCatching { dir?.listFiles()?.forEach { it.delete() } } }

        private fun prune() {
            dir?.listFiles { f -> f.name.endsWith(".json") }
                ?.sortedByDescending { it.lastModified() }?.drop(MAX_FILES)?.forEach { it.delete() }
        }

        private fun encode(l: Lyrics) = JSONObject()
            .put("synced", l.synced).put("language", l.language).put("provider", l.provider)
            .put("colors", l.colors?.let { JSONArray(listOf(it.background, it.text, it.highlight)) })
            .put("lines", JSONArray().also { arr ->
                l.lines.forEach { line ->
                    arr.put(JSONObject().put("t", line.timeMs).put("x", line.text).also { lo ->
                        line.words?.let { ws -> lo.put("w", JSONArray().also { wa -> ws.forEach { w -> wa.put(JSONArray(listOf(w.startMs, w.endMs, w.text))) } }) }
                    })
                }
            })

        private fun decode(o: JSONObject): Lyrics {
            val colors = o.optJSONArray("colors")?.let { LyricsColors(it.getInt(0), it.getInt(1), it.getInt(2)) }
            val lines = o.getJSONArray("lines").let { arr ->
                List(arr.length()) { i ->
                    val lo = arr.getJSONObject(i)
                    val words = lo.optJSONArray("w")?.let { wa -> List(wa.length()) { j -> wa.getJSONArray(j).let { LyricWord(it.getLong(0), it.getLong(1), it.getString(2)) } } }
                    LyricLine(lo.getLong("t"), lo.getString("x"), words)
                }
            }
            return Lyrics(lines, o.getBoolean("synced"), o.optString("language").takeIf { it.isNotEmpty() && it != "null" },
                o.optString("provider").takeIf { it.isNotEmpty() && it != "null" }, colors)
        }
    }

    /** Whether lyrics extensions are installed and running (false in unit tests: no app). */
    private fun extensionsAsked(): Boolean = runCatching { com.music.spotui.extensions.Extensions.hasLyrics() }.getOrDefault(false)

    /**
     * "Word by word" (the setting): the sources with each word's time come first — Spotify's own
     * when it has them, then LyricsPlus, lrc.red and Kugou, asked together; the line-synced
     * chain after them. Spotify's colours are kept whoever gives the words.
     */
    private suspend fun wordFirst(track: Track): Lyrics? = coroutineScope {
        // The catalogues are asked with the full title: "Old Town Road - Remix" cut down to
        // "Old Town Road" found the original (1:53), refused for its length (reported: lyrics
        // not complete). The comparison still uses the title without its asides.
        val asked = track.title.trim()
        val title = cleanTitle(track.title)
        val artist = track.artist.substringBefore(",").trim()
        val durationMs = track.durationMs
        val spotify = async { fromSpotify(track) }
        val plus = async { kotlinx.coroutines.withTimeoutOrNull(WORD_WAIT_MS) { fromLyricsPlus(asked, title, artist, durationMs) } }
        val red = async { kotlinx.coroutines.withTimeoutOrNull(WORD_WAIT_MS) { fromLrcRed(asked, title, artist, durationMs) } }
        val kugou = async { kotlinx.coroutines.withTimeoutOrNull(WORD_WAIT_MS) { fromKugou(title, artist, durationMs) } }
        val sp = spotify.await()
        if (sp != null && sp.wordSynced && sp.uncapped) return@coroutineScope sp.also { plus.cancel(); red.cancel(); kugou.cancel() }
        // Shown at once while the word timings are looked for (reported: lyrics slow to come).
        if (sp != null) early.value = track.key to sp
        val words = (plus.await() ?: red.await() ?: kugou.await())?.takeIf { it.wordSynced }
        when {
            words != null -> alignTo(words, sp)?.copy(colors = sp?.colors) ?: sp
            sp != null -> sp
            else -> lineFallbacks(track)
        }
    }

    /**
     * Word timings come from another recording's catalogue (Apple Music, Kugou): its intro can be
     * a little longer or shorter than Spotify's (reported: "mal synchro"). When Spotify times the
     * same lines, the words are moved by the usual gap between the two (the median of the lines
     * found in both, in order), so they follow Spotify's own audio.
     *
     * Only lines that appear once on each side are paired: a chorus sung five times paired its
     * first copy with a later one (God's Plan was moved by 74 s on three such lines). The gaps
     * must agree and stay within an intro's length; otherwise the words are not trusted (null)
     * and Spotify's own lines are shown.
     */
    private fun alignTo(words: Lyrics, spotify: Lyrics?): Lyrics? {
        if (spotify == null || !spotify.synced) return words
        fun uniqueKeys(lines: List<com.music.spotui.data.entity.LyricLine>) =
            lines.map { matchKey(it.text) }.filter { it.length >= 6 }.groupingBy { it }.eachCount().filterValues { it == 1 }.keys
        val theirs = spotify.lines.filter { it.timeMs > 0 && matchKey(it.text).length >= 6 }
        val shared = uniqueKeys(theirs) intersect uniqueKeys(words.lines)
        val theirAt = theirs.associateBy { matchKey(it.text) }
        val gaps = words.lines.mapNotNull { l -> theirAt[matchKey(l.text)]?.takeIf { matchKey(l.text) in shared }?.let { it.timeMs - l.timeMs } }
        if (gaps.size < 3) {
            if (gaps.isEmpty()) return words
            Log.d("LyricsApi", "${words.provider}: only ${gaps.size} lines to compare with Spotify's timing, Spotify's lines kept")
            return null
        }
        val shift = gaps.sorted()[gaps.size / 2]
        val agreeing = gaps.count { kotlin.math.abs(it - shift) <= ALIGN_TOLERANCE_MS }
        if (agreeing < maxOf(3, (gaps.size * 0.6).toInt()) || kotlin.math.abs(shift) > MAX_ALIGN_SHIFT_MS) {
            Log.d("LyricsApi", "${words.provider}: timing off Spotify's by $shift ms, $agreeing of ${gaps.size} lines agree: Spotify's lines kept")
            return null
        }
        if (kotlin.math.abs(shift) < 60) return words
        Log.d("LyricsApi", "${words.provider}: moved by $shift ms to Spotify's timing ($agreeing of ${gaps.size} lines)")
        return words.copy(lines = words.lines.map { l ->
            l.copy(timeMs = (l.timeMs + shift).coerceAtLeast(0), words = l.words?.map { w ->
                w.copy(startMs = (w.startMs + shift).coerceAtLeast(0), endMs = if (w.endMs > 0) (w.endMs + shift).coerceAtLeast(0) else 0)
            })
        })
    }

    /** How far a paired line may sit from the usual gap and still agree with it. */
    private const val ALIGN_TOLERANCE_MS = 1_500L

    /** The most two recordings' intros are taken to differ by; more is another cut or a wrong pairing. */
    private const val MAX_ALIGN_SHIFT_MS = 20_000L

    /** Nothing from Spotify: LRCLIB, then (opt-in) NetEase and Genius, then Kugou. */
    private suspend fun lineFallbacks(track: Track): Lyrics? = coroutineScope {
            val title = cleanTitle(track.title)
            val artist = track.artist.substringBefore(",").trim()
            val durationSec = (track.durationMs / 1000).toInt()
            val netease = if (extraSources) async { fromNetEase(title, artist, durationSec) } else null
            val genius = if (extraSources) async { fromGenius(title, artist) } else null
            val exact = async { lrclibGet(title, artist, track.album, durationSec) }
            val fuzzy = async { lrclibSearch("$LRCLIB/search?track_name=${enc(title)}&artist_name=${enc(artist)}", title, artist, durationSec) }
            val lrclib = exact.await() ?: fuzzy.await()
                ?: lrclibSearch("$LRCLIB/search?track_name=${enc(title)}", title, artist, durationSec)
            val ne = netease?.await()
            // Synced beats plain.
            when {
                lrclib != null && lrclib.synced -> lrclib
                ne != null && ne.synced -> ne
                else -> fromKugou(title, artist, track.durationMs) ?: lrclib ?: ne ?: genius?.await()
            }
    }

    // ── 1. Spotify color-lyrics ──
    private suspend fun fromSpotify(track: Track): Lyrics? {
        if (track.trackId.isBlank()) return null
        val sp = runCatching { app.myspoti.host.android.MyspotiRuntime.api.extras.lyrics("spotify:track:${track.trackId}") }
            .onFailure { Log.d("LyricsApi", "spotify lyrics miss for ${track.trackId}: ${it.message}") }
            .getOrNull() ?: return null
        if (sp.lines.isEmpty()) return null
        val bg = sp.background
        val text = sp.text
        val hi = sp.highlight
        return Lyrics(
            lines = sp.lines.map { l -> LyricLine(l.startMs, l.text, l.words?.map { w -> LyricWord(w.startMs, w.startMs, w.text) }) },
            synced = sp.synced,
            language = sp.language,
            provider = sp.provider ?: "Spotify",
            colors = if (bg != null && text != null && hi != null) LyricsColors(bg, text, hi) else null,
        )
    }

    // ── 4. NetEase Cloud Music (line-synced LRC) ──
    private fun fromNetEase(title: String, artist: String, durationSec: Int): Lyrics? {
        val search = httpGet("https://music.163.com/api/search/get?s=${enc("$title $artist")}&type=1&limit=6", timeoutMs = 6000)
            ?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return null
        val songs = search.optJSONObject("result")?.optJSONArray("songs") ?: return null
        for (i in 0 until songs.length()) {
            val song = songs.optJSONObject(i) ?: continue
            val songArtist = song.optJSONArray("artists")?.optJSONObject(0)?.optString("name").orEmpty()
            if (!isPlausibleMatch(song.optString("name"), songArtist, (song.optLong("duration") / 1000).toInt(), title, artist, durationSec)) continue
            val o = httpGet("https://music.163.com/api/song/lyric?id=${song.optLong("id")}&lv=1&kv=1&tv=-1", timeoutMs = 6000)
                ?.let { runCatching { JSONObject(it) }.getOrNull() } ?: continue
            val lrc = o.optJSONObject("lrc")?.optString("lyric").orEmpty()
            // Credit lines ("作词 : …") come first in NetEase LRC.
            val lines = parseLrc(lrc).filter { it.text.isNotBlank() && !it.text.contains(" : ") }
            if (lines.isEmpty()) continue
            return Lyrics(lines = lines, synced = lines.any { it.timeMs > 0 }, provider = "NetEase Cloud Music")
        }
        return null
    }

    // ── 5. Genius (plain text from the song page) ──
    private fun fromGenius(title: String, artist: String): Lyrics? {
        val search = httpGet("https://genius.com/api/search/song?per_page=5&q=${enc("$title $artist")}", timeoutMs = 6000)
            ?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return null
        val hits = search.optJSONObject("response")?.optJSONArray("sections")?.optJSONObject(0)?.optJSONArray("hits") ?: return null
        for (i in 0 until hits.length()) {
            val r = hits.optJSONObject(i)?.optJSONObject("result") ?: continue
            if (!isPlausibleMatch(r.optString("title"), r.optString("artist_names"), 0, title, artist, 0)) continue
            val html = httpGet(r.optString("url"), timeoutMs = 8000) ?: continue
            val text = geniusText(html) ?: continue
            val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() && !(it.startsWith("[") && it.endsWith("]")) }
            if (lines.isEmpty()) continue
            return Lyrics(lines = lines.map { LyricLine(0, it) }, synced = false, provider = "Genius")
        }
        return null
    }

    private val geniusContainer = Regex("<div[^>]*data-lyrics-container=\"true\"[^>]*>", RegexOption.IGNORE_CASE)

    /** The lyrics containers of a Genius song page, as plain text (nested divs balanced). */
    internal fun geniusText(html: String): String? {
        val parts = geniusContainer.findAll(html).mapNotNull { m ->
            var depth = 1
            var i = m.range.last + 1
            val start = i
            while (i < html.length && depth > 0) {
                when {
                    html.startsWith("<div", i, ignoreCase = true) -> depth++
                    html.startsWith("</div", i, ignoreCase = true) -> { depth--; if (depth == 0) return@mapNotNull html.substring(start, i) }
                }
                i++
            }
            null
        }.toList()
        if (parts.isEmpty()) return null
        return parts.joinToString("\n") { part ->
            androidx.core.text.HtmlCompat.fromHtml(part.replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n"), androidx.core.text.HtmlCompat.FROM_HTML_MODE_LEGACY)
                .toString()
        }.trim().takeIf { it.isNotEmpty() }
    }

    // ── 2. LRCLIB ──
    private fun lrclibGet(title: String, artist: String, album: String, durationSec: Int): Lyrics? {
        val url = buildString {
            append("$LRCLIB/get?track_name=").append(enc(title))
            append("&artist_name=").append(enc(artist))
            if (album.isNotBlank()) append("&album_name=").append(enc(album))
            if (durationSec > 0) append("&duration=").append(durationSec)
        }
        val o = httpGet(url)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return null
        if (!isPlausibleMatch(o.optString("trackName"), o.optString("artistName"), o.optInt("duration"), title, artist, durationSec)) return null
        return parseLrclib(o)
    }

    private fun lrclibSearch(url: String, title: String, artist: String, durationSec: Int): Lyrics? {
        val arr = httpGet(url)?.let { runCatching { JSONArray(it) }.getOrNull() } ?: return null
        // Of the plausible candidates, prefer synced lyrics, then the closest duration.
        var best: JSONObject? = null
        var bestScore = Long.MAX_VALUE
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val dur = o.optInt("duration", 0)
            if (!isPlausibleMatch(o.optString("trackName"), o.optString("artistName"), dur, title, artist, durationSec)) continue
            val hasSynced = o.optString("syncedLyrics").isNotBlank() && !o.isNull("syncedLyrics")
            val diff = if (durationSec > 0 && dur > 0) kotlin.math.abs(dur - durationSec).toLong() else 0L
            val score = diff + (if (hasSynced) 0 else 100_000)
            if (score < bestScore) { bestScore = score; best = o }
        }
        return best?.let { parseLrclib(it) }
    }

    private fun parseLrclib(o: JSONObject): Lyrics? {
        val synced = o.optString("syncedLyrics").takeIf { it.isNotBlank() && !o.isNull("syncedLyrics") }
        if (synced != null) {
            val lines = parseLrc(synced)
            if (lines.isNotEmpty()) return Lyrics(lines, synced = true, provider = "LRCLIB")
        }
        val plain = o.optString("plainLyrics").takeIf { it.isNotBlank() && !o.isNull("plainLyrics") } ?: return null
        return Lyrics(plain.split("\n").map { LyricLine(0L, it) }, synced = false, provider = "LRCLIB")
    }

    // ── Word by word: LyricsPlus (Apple Music's timing, by title / artist / length) ──
    private val LYRICS_PLUS = listOf("https://lyricsplus.binimum.org", "https://lyricsplus.prjktla.my.id")

    private fun fromLyricsPlus(asked: String, title: String, artist: String, durationMs: Long): Lyrics? {
        val q = "title=${enc(asked)}&artist=${enc(artist)}" + if (durationMs > 0) "&duration=${durationMs / 1000}" else ""
        for (base in LYRICS_PLUS) {
            val body = httpGet("$base/v2/lyrics/get?$q", timeoutMs = 5000) ?: continue
            val o = runCatching { JSONObject(body) }.getOrNull() ?: continue
            val meta = o.optJSONObject("metadata")
            if (meta != null && !sameSong(meta.optString("title"), meta.optString("artist"), parseClock(meta.optString("totalDuration")), title, artist, durationMs)) return null
            val arr = o.optJSONArray("lyrics") ?: continue
            val lines = ArrayList<LyricLine>()
            for (i in 0 until arr.length()) {
                val l = arr.optJSONObject(i) ?: continue
                val syl = l.optJSONArray("syllabus")
                val words = syl?.let { s ->
                    (0 until s.length()).mapNotNull { j -> s.optJSONObject(j) }
                        .filterNot { it.optBoolean("isBackground") }
                        .map { w -> val t = w.optLong("time"); LyricWord(t, t + w.optLong("duration"), w.optString("text")) }
                }?.takeIf { it.isNotEmpty() }
                val text = words?.joinToString("") { it.text }?.trim() ?: l.optString("text").trim()
                if (text.isNotEmpty()) lines += LyricLine(l.optLong("time"), text, words)
            }
            if (lines.isEmpty()) continue
            val source = meta?.optString("source").orEmpty()
            return Lyrics(lines, synced = true, language = meta?.optString("language")?.takeIf { it.isNotBlank() },
                provider = if (source.contains("apple", true)) "Apple Music · LyricsPlus" else "LyricsPlus")
        }
        return null
    }

    // ── Word by word: lrc.red (Apple Music TTML, found by ISRC) ──
    private fun fromLrcRed(asked: String, title: String, artist: String, durationMs: Long): Lyrics? {
        val search = httpGet("https://lrc.red/search.json?q=${enc("$asked $artist")}", timeoutMs = 5000) ?: return null
        val hits = runCatching { JSONObject(search).optJSONArray("hits") }.getOrNull() ?: return null
        val hit = (0 until hits.length()).mapNotNull { hits.optJSONObject(it) }.firstOrNull {
            sameSong(it.optString("title"), it.optString("artist"), (it.optDouble("duration", 0.0) * 1000).toLong(), title, artist, durationMs)
        } ?: return null
        val isrc = hit.optString("isrc").takeIf { it.isNotBlank() } ?: return null
        val ttml = httpGet("https://lrc.red/s/${enc(isrc)}.ttml", timeoutMs = 6000) ?: return null
        val lines = parseTtml(ttml) ?: return null
        return Lyrics(lines, synced = true, provider = "Apple Music · lrc.red")
    }

    /** Apple Music's TTML: a <p> per line, a timed <span> per word (background voices left out). */
    internal fun parseTtml(xml: String): List<LyricLine>? = runCatching {
        val p = android.util.Xml.newPullParser()
        p.setFeature(org.xmlpull.v1.XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        p.setInput(java.io.StringReader(xml))
        val lines = ArrayList<LyricLine>()
        var lineStart = -1L
        var words: MutableList<LyricWord>? = null
        var plain = StringBuilder()
        var wordStart = -1L; var wordEnd = 0L; var word = StringBuilder()
        var skipDepth = 0
        var inBody = false
        while (true) {
            when (p.next()) {
                org.xmlpull.v1.XmlPullParser.END_DOCUMENT -> break
                org.xmlpull.v1.XmlPullParser.START_TAG -> when {
                    p.name == "body" -> inBody = true
                    !inBody -> {}
                    skipDepth > 0 -> skipDepth++
                    p.getAttributeValue(null, "ttm:role")?.startsWith("x-") == true -> skipDepth = 1
                    p.name == "p" -> { lineStart = clock(p.getAttributeValue(null, "begin")); words = ArrayList(); plain = StringBuilder() }
                    p.name == "span" && words != null -> {
                        wordStart = clock(p.getAttributeValue(null, "begin")); wordEnd = clock(p.getAttributeValue(null, "end")); word = StringBuilder()
                    }
                }
                org.xmlpull.v1.XmlPullParser.TEXT -> if (inBody && skipDepth == 0 && words != null) {
                    val t = p.text
                    if (wordStart >= 0) word.append(t)
                    // Text between two words (a space): it belongs to the word before it.
                    else if (words!!.isNotEmpty()) words!![words!!.lastIndex] = words!!.last().let { it.copy(text = it.text + t) }
                    plain.append(t)
                }
                org.xmlpull.v1.XmlPullParser.END_TAG -> when {
                    skipDepth > 0 -> skipDepth--
                    p.name == "span" && wordStart >= 0 -> { words?.add(LyricWord(wordStart, wordEnd, word.toString())); wordStart = -1 }
                    p.name == "p" && words != null -> {
                        val text = plain.toString().replace(Regex("\\s+"), " ").trim()
                        if (text.isNotEmpty() && lineStart >= 0) lines += LyricLine(lineStart, text, words!!.takeIf { it.isNotEmpty() })
                        words = null
                    }
                }
            }
        }
        lines.takeIf { it.isNotEmpty() }
    }.getOrNull()

    /** TTML times: "11.859", "1:02.345" or "00:01:02.345" → ms. */
    private fun clock(v: String?): Long {
        if (v.isNullOrBlank()) return -1
        val parts = v.trim().removeSuffix("s").split(':')
        var ms = 0.0
        for (part in parts) ms = ms * 60 + (part.toDoubleOrNull() ?: return -1)
        return (ms * 1000).toLong()
    }
    private fun parseClock(v: String?): Long = clock(v).coerceAtLeast(0)

    // ── Word by word: Kugou (its own catalogue, strong in Asian music; KRC, encrypted) ──
    private val KRC_KEY = intArrayOf(64, 71, 97, 119, 94, 50, 116, 71, 81, 54, 49, 45, 206, 210, 110, 105)
    private val krcLine = Regex("""^\[(\d+),(\d+)](.*)$""")
    private val krcWord = Regex("""<(\d+),(\d+),\d+>([^<]*)""")

    private fun fromKugou(title: String, artist: String, durationMs: Long): Lyrics? {
        val search = httpGet("https://lyrics.kugou.com/search?ver=1&man=yes&client=pc&keyword=${enc("$artist - $title")}" +
            (if (durationMs > 0) "&duration=$durationMs" else ""), timeoutMs = 5000) ?: return null
        val cands = runCatching { JSONObject(search).optJSONArray("candidates") }.getOrNull() ?: return null
        val c = (0 until cands.length()).mapNotNull { cands.optJSONObject(it) }.firstOrNull {
            sameSong(it.optString("song"), it.optString("singer"), it.optLong("duration"), title, artist, durationMs)
        } ?: return null
        val dl = httpGet("https://lyrics.kugou.com/download?ver=1&client=pc&id=${c.optString("id")}&accesskey=${c.optString("accesskey")}&fmt=krc&charset=utf8", timeoutMs = 5000) ?: return null
        val content = runCatching { JSONObject(dl).optString("content") }.getOrNull()?.takeIf { it.isNotBlank() } ?: return null
        val krc = runCatching {
            val raw = android.util.Base64.decode(content, android.util.Base64.DEFAULT)
            val x = ByteArray(raw.size - 4) { i -> (raw[i + 4].toInt() xor KRC_KEY[i % KRC_KEY.size]).toByte() }
            java.util.zip.InflaterInputStream(x.inputStream()).readBytes().decodeToString()
        }.getOrNull() ?: return null
        val lines = krc.lines().mapNotNull { raw ->
            val m = krcLine.find(raw.trim()) ?: return@mapNotNull null
            val start = m.groupValues[1].toLong()
            val words = krcWord.findAll(m.groupValues[3]).map { w ->
                val s = start + w.groupValues[1].toLong(); LyricWord(s, s + w.groupValues[2].toLong(), w.groupValues[3])
            }.toList()
            val text = words.joinToString("") { it.text }.trim()
            // Kugou opens with credit lines ("作词：…"): not lyrics.
            if (text.isEmpty() || text.contains('：')) null else LyricLine(start, text, words.takeIf { it.isNotEmpty() })
        }
        if (lines.size < 3) return null
        return Lyrics(lines, synced = true, provider = "Kugou")
    }

    /**
     * The same song for the word-timed sources: one of its artists is ours, the title is ours
     * (asides left out, either may be longer), and the length is within 4 s when both are known.
     */
    private fun sameSong(candTitle: String, candArtist: String, candDurationMs: Long, title: String, artist: String, durationMs: Long): Boolean {
        val a = matchKey(artist)
        if (a.isEmpty() || candArtist.split(',', '&', '、', '/').none { matchKey(it) == a }) return false
        val t = matchKey(title); val ct = matchKey(cleanTitle(candTitle))
        if (t.isEmpty() || !(ct == t || ct.startsWith(t) || t.startsWith(ct))) return false
        return durationMs <= 0 || candDurationMs <= 0 || kotlin.math.abs(candDurationMs - durationMs) <= 4_000
    }

    // ── Matching ──
    // Strip the noise Spotify puts in titles that other catalogues don't have:
    // "Song - 2011 Remaster", "Song (feat. X)", "Song [Bonus Track]".
    private val featTag = Regex("""\s*[(\[][^)\]]*(feat\.?|ft\.?|with )[^)\]]*[)\]]""", RegexOption.IGNORE_CASE)
    private val bracketTag = Regex("""\s*[(\[][^)\]]*(remaster|remastered|live|version|edit|mono|stereo|deluxe|bonus)[^)\]]*[)\]]""", RegexOption.IGNORE_CASE)
    private fun cleanTitle(title: String) =
        title.substringBefore(" - ").replace(featTag, "").replace(bracketTag, "").trim()

    private fun matchKey(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
    // Exact normalized identity is conservative: ambiguous fallbacks are rejected.
    private fun overlaps(a: String, b: String) = a.isNotEmpty() && a == b

    /**
     * Fallback hits are only used when they are the same song: same artist, same
     * title (either may carry extras), and a similar length when both are known.
     */
    internal fun isPlausibleMatch(
        candTitle: String, candArtist: String, candDurationSec: Int,
        title: String, artist: String, durationSec: Int,
    ): Boolean {
        if (!overlaps(matchKey(candArtist), matchKey(artist))) return false
        if (!overlaps(matchKey(cleanTitle(candTitle)), matchKey(title))) return false
        return durationSec <= 0 || candDurationSec <= 0 || kotlin.math.abs(candDurationSec - durationSec) <= 3
    }

    // LRC: each line is "[mm:ss.xx] text" (a line may carry multiple timestamps).
    private val lrcTag = Regex("""\[(\d{1,2}):(\d{2})(?:[.:](\d{1,3}))?]""")
    private fun parseLrc(lrc: String): List<LyricLine> {
        val out = ArrayList<LyricLine>()
        for (raw in lrc.split("\n")) {
            val tags = lrcTag.findAll(raw).toList()
            if (tags.isEmpty()) continue
            val text = raw.substring(tags.last().range.last + 1).trim().ifEmpty { "♪" }
            for (m in tags) {
                val frac = m.groupValues[3]
                val ms = when (frac.length) {
                    0 -> 0L
                    1 -> frac.toLong() * 100
                    2 -> frac.toLong() * 10
                    else -> frac.take(3).toLong()
                }
                out.add(LyricLine(m.groupValues[1].toLong() * 60_000 + m.groupValues[2].toLong() * 1_000 + ms, text))
            }
        }
        // Collapse runs of empty (instrumental) lines into one ♪.
        val sorted = out.sortedBy { it.timeMs }
        return sorted.filterIndexed { i, l -> !(l.text == "♪" && i > 0 && sorted[i - 1].text == "♪") }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun httpGet(spec: String, timeoutMs: Int = 5000): String? = runCatching {
        com.music.spotui.data.DataSaver.check()
        val conn = (URL(spec).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Accept", "application/json, text/html;q=0.9, */*;q=0.8")
        }
        try {
            if (conn.responseCode != 200) {
                Log.d("LyricsApi", "HTTP ${conn.responseCode} for $spec")
                return null
            }
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }.getOrElse { Log.d("LyricsApi", "request failed: ${it.message}"); null }
}
