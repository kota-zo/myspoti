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
 * A provider failing in a row is set aside and a "no lyrics" answer is kept for a while
 * ([LyricsHealth]); [sources] and [fetchFrom] let the user pick one by hand.
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
    private val health = LyricsHealth()
    // Set while one provider is asked by hand ([fetchFrom]): it is asked even when set aside.
    private val bypass = ThreadLocal<Boolean>()
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

    fun forget(track: Track) { cache.remove(track.key); health.forgetTrack(track.key); scope.launch { LyricsDisk.drop(track.key) } }

    /** A setting changed what is looked for: every song is read again when next shown. */
    fun forgetAll() { cache.clear(); health.forgetAll(); scope.launch { LyricsDisk.clear() } }

    /** Bumped when the lyrics extensions changed and the cache was dropped: an open lyrics view reads its song again. */
    val sourcesTick = kotlinx.coroutines.flow.MutableStateFlow(0L)

    /**
     * The lyrics extensions changed (one installed, turned on or off, updated, removed): the songs
     * not found are asked again, and, but for [keepFound] (the ones on at launch just loaded), the
     * lyrics found too, from memory and storage, so the new set of sources answers. Off the main thread.
     */
    fun sourcesChanged(keepFound: Boolean) {
        if (keepFound) synchronized(cache) { cache.values.removeAll { it is Miss } }
        else { cache.clear(); LyricsDisk.clear() }
        health.forgetAll()
        sourcesTick.value++
    }

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

    /**
     * A provider that can be asked for a song by hand ("Try another source"). [id] goes to
     * [fetchFrom]; [name] is shown as it is (a brand, not translated); [words]: it can time the
     * words; [setAside]: why it is not asked by itself for now (a run of failures), null when it is.
     */
    data class Source(val id: String, val name: String, val words: Boolean = false, val setAside: String? = null)

    private fun allSetAside(vararg ids: String): String? = ids.map { health.reason(it) }.takeIf { r -> r.all { it != null } }?.first()

    private val WORD_SOURCES = setOf("lyricsplus", "lrcred", "kugou")

    /** The providers that can be tried for [track], in the order the resolver prefers them; the extensions giving lyrics come last (`ext:<id>`). */
    fun sources(track: Track): List<Source> = buildList {
        if (track.trackId.isNotBlank()) add(Source("spotify", "Spotify", words = true))
        add(Source("lyricsplus", "LyricsPlus", true, allSetAside(*LYRICS_PLUS.toTypedArray())))
        add(Source("lrcred", "lrc.red", true, allSetAside("lrcred")))
        add(Source("kugou", "Kugou", true, allSetAside("kugou")))
        add(Source("lrclib", "LRCLIB", false, allSetAside("lrclib")))
        add(Source("netease", "NetEase", false, allSetAside("netease")))
        add(Source("genius", "Genius", false, allSetAside("genius")))
        runCatching { com.music.spotui.extensions.Extensions.lyricsSources() }.getOrDefault(emptyList())
            .forEach { (id, name) -> add(Source("ext:$id", name)) }
    }

    /** The [Source.id] the shown [lyrics] came from, or null when it cannot tell (an extension's own provider name). */
    fun sourceOf(lyrics: Lyrics): String? {
        val p = lyrics.provider.orEmpty().lowercase()
        return when {
            "lyricsplus" in p -> "lyricsplus"
            "lrc.red" in p -> "lrcred"
            "kugou" in p -> "kugou"
            "lrclib" in p -> "lrclib"
            "netease" in p -> "netease"
            "genius" in p -> "genius"
            lyrics.colors != null -> "spotify"
            else -> null
        }
    }

    /**
     * The lyrics of [track] from the provider [sourceId] alone ([Source.id]), asked at once (a
     * set-aside provider, a cached "no lyrics" and the cache of the song do not count). What it gives
     * replaces the song's lyrics in memory and storage; null (nothing changes) when it has none.
     */
    suspend fun fetchFrom(track: Track, sourceId: String): Lyrics? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val asked = track.title.trim()
        val title = cleanTitle(track.title)
        val artist = track.artist.substringBefore(",").trim()
        val durationMs = track.durationMs
        val durationSec = (durationMs / 1000).toInt()
        fun <T> byHand(block: () -> T): T { bypass.set(true); try { return block() } finally { bypass.remove() } }
        val spotify = if (sourceId in WORD_SOURCES) runCatching { fromSpotify(track) }.getOrNull() else null
        val found = try {
            when {
                sourceId == "spotify" -> fromSpotify(track)
                sourceId.startsWith("ext:") -> com.music.spotui.extensions.Extensions.lyricsFromExtension(
                    sourceId.removePrefix("ext:"), track.trackId, track.title, track.artist, track.album, durationMs, track.coverUrl)
                sourceId == "lyricsplus" -> byHand { fromLyricsPlus(asked, title, artist, durationMs) }
                sourceId == "lrcred" -> byHand { fromLrcRed(asked, title, artist, durationMs) }
                sourceId == "kugou" -> byHand { fromKugou(title, artist, durationMs) }
                sourceId == "lrclib" -> byHand {
                    lrclibGet(title, artist, track.album, durationSec)
                        ?: lrclibSearch("$LRCLIB/search?track_name=${enc(title)}&artist_name=${enc(artist)}", title, artist, durationSec)
                        ?: lrclibSearch("$LRCLIB/search?track_name=${enc(title)}", title, artist, durationSec)
                }
                sourceId == "netease" -> byHand { fromNetEase(title, artist, durationSec) }
                sourceId == "genius" -> byHand { fromGenius(title, artist) }
                else -> null
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { Log.d("LyricsApi", "$sourceId by hand: ${e.javaClass.simpleName}"); null }
        val lyrics = found?.takeIf { !it.isEmpty } ?: return@withContext null
        // Words from another recording follow Spotify's own timing when that can be checked, and keep its colours.
        val chosen = if (lyrics.wordSynced && spotify != null) (alignTo(lyrics, spotify) ?: lyrics).copy(colors = spotify.colors) else lyrics
        cache[track.key] = chosen
        LyricsDisk.write(track.key, chosen)
        Log.d("LyricsApi", "${track.title}: ${chosen.lines.size} lines from ${chosen.provider} (chosen: $sourceId)")
        chosen
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
                com.music.spotui.data.writeTextSafely(f, o.toString(), sync = false)
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
            .put("trl", l.translationLanguage)
            .put("colors", l.colors?.let { JSONArray(listOf(it.background, it.text, it.highlight)) })
            .put("lines", JSONArray().also { arr ->
                l.lines.forEach { line ->
                    arr.put(JSONObject().put("t", line.timeMs).put("x", line.text).also { lo ->
                        line.words?.let { ws -> lo.put("w", wordsJson(ws)) }
                        line.translation?.let { lo.put("tr", it) }
                        line.romanization?.let { lo.put("ro", it) }
                        line.voice?.let { lo.put("v", it) }
                        line.background?.let { lo.put("bg", it) }
                        line.backgroundWords?.let { lo.put("bw", wordsJson(it)) }
                    })
                }
            })

        private fun wordsJson(ws: List<LyricWord>) = JSONArray().also { wa -> ws.forEach { w -> wa.put(JSONArray(listOf(w.startMs, w.endMs, w.text))) } }

        private fun wordsOf(wa: JSONArray?) = wa?.let { List(it.length()) { j -> it.getJSONArray(j).let { w -> LyricWord(w.getLong(0), w.getLong(1), w.getString(2)) } } }

        // Entries written before translations, voices and backing vocals existed lack their keys: they read as none.
        private fun decode(o: JSONObject): Lyrics {
            val colors = o.optJSONArray("colors")?.let { LyricsColors(it.getInt(0), it.getInt(1), it.getInt(2)) }
            val lines = o.getJSONArray("lines").let { arr ->
                List(arr.length()) { i ->
                    val lo = arr.getJSONObject(i)
                    LyricLine(lo.getLong("t"), lo.getString("x"), wordsOf(lo.optJSONArray("w")),
                        translation = lo.optString("tr").ifEmpty { null }, romanization = lo.optString("ro").ifEmpty { null },
                        voice = if (lo.has("v")) lo.optInt("v") else null, background = lo.optString("bg").ifEmpty { null },
                        backgroundWords = wordsOf(lo.optJSONArray("bw")))
                }
            }
            return Lyrics(lines, o.getBoolean("synced"), o.optString("language").takeIf { it.isNotEmpty() && it != "null" },
                o.optString("provider").takeIf { it.isNotEmpty() && it != "null" }, colors,
                translationLanguage = o.optString("trl").takeIf { it.isNotEmpty() && it != "null" })
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
        val plus = async { kotlinx.coroutines.withTimeoutOrNull(WORD_WAIT_MS) { asking("lyricsplus", track) { fromLyricsPlus(asked, title, artist, durationMs) } } }
        val red = async { kotlinx.coroutines.withTimeoutOrNull(WORD_WAIT_MS) { asking("lrcred", track) { fromLrcRed(asked, title, artist, durationMs) } } }
        val kugou = async { kotlinx.coroutines.withTimeoutOrNull(WORD_WAIT_MS) { asking("kugou", track) { fromKugou(title, artist, durationMs) } } }
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
            val netease = if (extraSources) async { asking("netease", track) { fromNetEase(title, artist, durationSec) } } else null
            val genius = if (extraSources) async { asking("genius", track) { fromGenius(title, artist) } } else null
            val lrclib = asking("lrclib", track) {
                val exact = async { lrclibGet(title, artist, track.album, durationSec) }
                val fuzzy = async { lrclibSearch("$LRCLIB/search?track_name=${enc(title)}&artist_name=${enc(artist)}", title, artist, durationSec) }
                exact.await() ?: fuzzy.await()
                    ?: lrclibSearch("$LRCLIB/search?track_name=${enc(title)}", title, artist, durationSec)
            }
            val ne = netease?.await()
            // Synced beats plain; NetEase's translation and romanization follow the lines of LRCLIB when it wins.
            when {
                lrclib != null && lrclib.synced -> LyricsExtras.carry(lrclib, ne)
                ne != null && ne.synced -> ne
                else -> asking("kugou", track) { fromKugou(title, artist, track.durationMs) } ?: lrclib ?: ne ?: genius?.await()
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
        val search = httpGet("https://music.163.com/api/search/get?s=${enc("$title $artist")}&type=1&limit=6", timeoutMs = 6000, provider = "netease")
            ?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return null
        val songs = search.optJSONObject("result")?.optJSONArray("songs") ?: return null
        for (i in 0 until songs.length()) {
            val song = songs.optJSONObject(i) ?: continue
            val songArtist = song.optJSONArray("artists")?.optJSONObject(0)?.optString("name").orEmpty()
            if (!isPlausibleMatch(song.optString("name"), songArtist, (song.optLong("duration") / 1000).toInt(), title, artist, durationSec)) continue
            // tlyric: a translation (in Chinese), romalrc: the romanization, both timed like the lyrics (rv=-1 asks for the latter).
            val o = httpGet("https://music.163.com/api/song/lyric?id=${song.optLong("id")}&lv=1&kv=1&tv=-1&rv=-1", timeoutMs = 6000, provider = "netease")
                ?.let { runCatching { JSONObject(it) }.getOrNull() } ?: continue
            val lrc = o.optJSONObject("lrc")?.optString("lyric").orEmpty()
            // Credit lines ("作词 : …") come first in NetEase LRC.
            val plain = parseLrc(lrc).filter { it.text.isNotBlank() && !it.text.contains(" : ") }
            if (plain.isEmpty()) continue
            val lines = LyricsExtras.attach(plain,
                parseLrc(o.optJSONObject("tlyric")?.optString("lyric").orEmpty()),
                parseLrc(o.optJSONObject("romalrc")?.optString("lyric").orEmpty()))
            return Lyrics(lines = lines, synced = lines.any { it.timeMs > 0 }, provider = "NetEase Cloud Music",
                translationLanguage = if (lines.any { it.translation != null }) "zh" else null)
        }
        return null
    }

    // ── 5. Genius (plain text from the song page) ──
    private fun fromGenius(title: String, artist: String): Lyrics? {
        val search = httpGet("https://genius.com/api/search/song?per_page=5&q=${enc("$title $artist")}", timeoutMs = 6000, provider = "genius")
            ?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return null
        val hits = search.optJSONObject("response")?.optJSONArray("sections")?.optJSONObject(0)?.optJSONArray("hits") ?: return null
        for (i in 0 until hits.length()) {
            val r = hits.optJSONObject(i)?.optJSONObject("result") ?: continue
            if (!isPlausibleMatch(r.optString("title"), r.optString("artist_names"), 0, title, artist, 0)) continue
            val html = httpGet(r.optString("url"), timeoutMs = 8000, provider = "genius") ?: continue
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
        val o = httpGet(url, provider = "lrclib")?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return null
        if (!isPlausibleMatch(o.optString("trackName"), o.optString("artistName"), o.optInt("duration"), title, artist, durationSec)) return null
        return parseLrclib(o)
    }

    private fun lrclibSearch(url: String, title: String, artist: String, durationSec: Int): Lyrics? {
        val arr = httpGet(url, provider = "lrclib")?.let { runCatching { JSONArray(it) }.getOrNull() } ?: return null
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
            val body = httpGet("$base/v2/lyrics/get?$q", timeoutMs = 5000, provider = base) ?: continue
            val o = runCatching { JSONObject(body) }.getOrNull() ?: continue
            val meta = o.optJSONObject("metadata")
            if (meta != null && !sameSong(meta.optString("title"), meta.optString("artist"), parseClock(meta.optString("totalDuration")), title, artist, durationMs)) return null
            return lyricsPlusLines(o) ?: continue
        }
        return null
    }

    /**
     * LyricsPlus's answer as lyrics. A line's backing vocals (`isBackground` syllables) are kept
     * apart from its words, `element.singer` ("v1", "v2") says who sings, and `translation` /
     * `transliteration` (a text, or an object with a `text`) are taken when a line has them.
     */
    internal fun lyricsPlusLines(o: JSONObject): Lyrics? {
        val meta = o.optJSONObject("metadata")
        val arr = o.optJSONArray("lyrics") ?: return null
        var translationLanguage: String? = null
        val lines = ArrayList<LyricLine>()
        for (i in 0 until arr.length()) {
            val l = arr.optJSONObject(i) ?: continue
            val syl = l.optJSONArray("syllabus")
            val all = syl?.let { s -> (0 until s.length()).mapNotNull { j -> s.optJSONObject(j) } }.orEmpty()
            fun word(w: JSONObject): LyricWord { val t = w.optLong("time"); return LyricWord(t, t + w.optLong("duration"), w.optString("text")) }
            var words = all.filterNot { it.optBoolean("isBackground") }.map(::word).takeIf { it.isNotEmpty() }
            var back = all.filter { it.optBoolean("isBackground") }.map(::word).takeIf { it.isNotEmpty() }
            // A line of backing vocals only is a line of its own.
            if (words == null && back != null) { words = back; back = null }
            val text = words?.joinToString("") { it.text }?.trim() ?: l.optString("text").trim()
            if (text.isEmpty()) continue
            val tr = l.opt("translation")
            val translation = textOf(tr)?.takeIf { LyricsMatch.key(it) != LyricsMatch.key(text) }
            if (translation != null && translationLanguage == null) translationLanguage = (tr as? JSONObject)?.optString("lang")?.ifBlank { null }
            val roman = textOf(l.opt("transliteration"))?.takeIf { LyricsMatch.key(it) != LyricsMatch.key(text) }
            lines += LyricLine(l.optLong("time"), text, words, translation, roman,
                voice = voiceOf(l.optJSONObject("element")?.optString("singer")),
                background = back?.joinToString("") { it.text }?.let(::backingText), backgroundWords = back)
        }
        if (lines.isEmpty()) return null
        val source = meta?.optString("source").orEmpty()
        return Lyrics(lines, synced = true, language = meta?.optString("language")?.takeIf { it.isNotBlank() },
            provider = if (source.contains("apple", true)) "Apple Music · LyricsPlus" else "LyricsPlus",
            translationLanguage = translationLanguage.takeIf { lines.any { it.translation != null } })
    }

    /** A string, or the `text` of an object: what a source gives as a translation or a romanization. */
    private fun textOf(v: Any?): String? =
        when (v) { is String -> v; is JSONObject -> v.optString("text"); else -> null }?.trim()?.ifEmpty { null }

    /** "v1" → 1, "v2" → 2; Apple's "v1000" (everyone) → 0; anything else is unknown. */
    internal fun voiceOf(agent: String?): Int? =
        agent?.trim()?.removePrefix("v")?.toIntOrNull()?.takeIf { it >= 0 }?.let { if (it >= 1000) 0 else it }

    /** Backing vocals as plain text: "(Woo-hoo)" → "Woo-hoo". */
    private fun backingText(s: String): String? = s.replace(Regex("\\s+"), " ").trim().removeSurrounding("(", ")").trim().ifEmpty { null }

    // ── Word by word: lrc.red (Apple Music TTML, found by ISRC) ──
    private fun fromLrcRed(asked: String, title: String, artist: String, durationMs: Long): Lyrics? {
        val search = httpGet("https://lrc.red/search.json?q=${enc("$asked $artist")}", timeoutMs = 5000, provider = "lrcred") ?: return null
        val hits = runCatching { JSONObject(search).optJSONArray("hits") }.getOrNull() ?: return null
        val hit = (0 until hits.length()).mapNotNull { hits.optJSONObject(it) }.firstOrNull {
            sameSong(it.optString("title"), it.optString("artist"), (it.optDouble("duration", 0.0) * 1000).toLong(), title, artist, durationMs)
        } ?: return null
        val isrc = hit.optString("isrc").takeIf { it.isNotBlank() } ?: return null
        val ttml = httpGet("https://lrc.red/s/${enc(isrc)}.ttml", timeoutMs = 6000, provider = "lrcred") ?: return null
        return parseTtml(ttml)?.copy(provider = "Apple Music · lrc.red")
    }

    /**
     * Apple Music's TTML: a <p> per line (its `ttm:agent` says who sings), a timed <span> per word,
     * backing vocals in a `ttm:role="x-bg"` span (kept apart from the line), and a translation and a
     * romanization either in the head (`translation` / `transliteration`, a `text for="L1"` per line,
     * the line's `*:key`) or inside the line (`x-translation` / `x-roman` spans). [newParser]: a pull
     * parser without namespace processing (tests bring their own).
     */
    internal fun parseTtml(xml: String, newParser: () -> org.xmlpull.v1.XmlPullParser = { android.util.Xml.newPullParser() }): Lyrics? = runCatching {
        val p = newParser()
        p.setFeature(org.xmlpull.v1.XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        p.setInput(java.io.StringReader(xml))
        fun attr(name: String) = p.getAttributeValue(null, name)
        fun lineKey() = (0 until p.attributeCount).firstOrNull { p.getAttributeName(it).let { n -> n == "key" || n.endsWith(":key") } }?.let { p.getAttributeValue(it) }
        fun tidy(sb: CharSequence) = sb.toString().replace(Regex("\\s+"), " ").trim()
        // Head: only the first translation and the first transliteration, text by line key.
        val headTranslation = HashMap<String, String>(); val headRoman = HashMap<String, String>()
        var translationLanguage: String? = null
        var headKind = 0; var headSeenTr = false; var headSeenRo = false
        var headKey: String? = null; var headText = StringBuilder(); var headSkip = 0
        // Body
        val lines = ArrayList<LyricLine>()
        var lineStart = -1L
        var words: MutableList<LyricWord>? = null
        var plain = StringBuilder()
        var wordStart = -1L; var wordEnd = 0L; var word = StringBuilder()
        var role: String? = null; var roleDepth = 0
        var key: String? = null; var voice: Int? = null
        var bgWords = ArrayList<LyricWord>(); var bgPlain = StringBuilder()
        var bgStart = -1L; var bgEnd = 0L; var bgWord = StringBuilder(); var bgSpan = longArrayOf(-1, 0)
        var inTr = StringBuilder(); var inRo = StringBuilder()
        var inBody = false
        while (true) {
            when (p.next()) {
                org.xmlpull.v1.XmlPullParser.END_DOCUMENT -> break
                org.xmlpull.v1.XmlPullParser.START_TAG -> when {
                    p.name == "body" -> inBody = true
                    !inBody -> when {
                        headKey != null -> if (headSkip > 0) headSkip++ else if (attr("ttm:role")?.startsWith("x-") == true) headSkip = 1
                        p.name == "translation" -> if (!headSeenTr) { headSeenTr = true; headKind = 1; translationLanguage = attr("xml:lang") } else headKind = -1
                        p.name == "transliteration" -> if (!headSeenRo) { headSeenRo = true; headKind = 2 } else headKind = -1
                        p.name == "text" && headKind > 0 -> { headKey = attr("for"); headText = StringBuilder(); headSkip = 0 }
                    }
                    role != null -> {
                        roleDepth++
                        if (role == "x-bg" && p.name == "span" && roleDepth == 2) { bgStart = clock(attr("begin")); bgEnd = clock(attr("end")); bgWord = StringBuilder() }
                    }
                    attr("ttm:role")?.startsWith("x-") == true -> {
                        role = attr("ttm:role"); roleDepth = 1
                        if (role == "x-bg") bgSpan = longArrayOf(clock(attr("begin")), clock(attr("end")))
                        if (role == "x-translation" && translationLanguage == null) translationLanguage = attr("xml:lang")
                    }
                    p.name == "p" -> {
                        lineStart = clock(attr("begin")); words = ArrayList(); plain = StringBuilder()
                        key = lineKey(); voice = voiceOf(attr("ttm:agent"))
                        bgWords = ArrayList(); bgPlain = StringBuilder(); bgStart = -1; inTr = StringBuilder(); inRo = StringBuilder()
                    }
                    p.name == "span" && words != null -> {
                        wordStart = clock(attr("begin")); wordEnd = clock(attr("end")); word = StringBuilder()
                    }
                }
                org.xmlpull.v1.XmlPullParser.TEXT -> {
                    val t = p.text
                    if (!inBody) { if (headKey != null && headSkip == 0) headText.append(t) }
                    else if (words != null) when (role) {
                        null -> {
                            if (wordStart >= 0) word.append(t)
                            // Text between two words (a space): it belongs to the word before it.
                            else if (words!!.isNotEmpty()) words!![words!!.lastIndex] = words!!.last().let { it.copy(text = it.text + t) }
                            plain.append(t)
                        }
                        "x-bg" -> {
                            bgPlain.append(t)
                            if (bgStart >= 0) bgWord.append(t)
                            else if (bgWords.isNotEmpty()) bgWords[bgWords.lastIndex] = bgWords.last().let { it.copy(text = it.text + t) }
                        }
                        "x-translation" -> inTr.append(t)
                        "x-roman" -> inRo.append(t)
                    }
                }
                org.xmlpull.v1.XmlPullParser.END_TAG -> when {
                    !inBody -> when {
                        headKey != null && headSkip > 0 -> headSkip--
                        headKey != null && p.name == "text" -> {
                            val text = tidy(headText).ifEmpty { null }
                            if (text != null) (if (headKind == 1) headTranslation else headRoman)[headKey!!] = text
                            headKey = null
                        }
                        p.name == "translation" || p.name == "transliteration" -> headKind = 0
                    }
                    role != null -> {
                        if (role == "x-bg" && roleDepth == 2 && p.name == "span" && bgStart >= 0) { bgWords.add(LyricWord(bgStart, bgEnd, bgWord.toString())); bgStart = -1 }
                        roleDepth--
                        if (roleDepth == 0) {
                            // One span of backing vocals with no span of words inside: it is the one word.
                            if (role == "x-bg" && bgWords.isEmpty() && bgSpan[0] >= 0 && bgPlain.isNotBlank()) bgWords.add(LyricWord(bgSpan[0], bgSpan[1], bgPlain.toString()))
                            role = null
                        }
                    }
                    p.name == "span" && wordStart >= 0 -> { words?.add(LyricWord(wordStart, wordEnd, word.toString())); wordStart = -1 }
                    p.name == "p" && words != null -> {
                        var text = tidy(plain)
                        var foreground: List<LyricWord>? = words!!.takeIf { it.isNotEmpty() }
                        var back: List<LyricWord>? = bgWords.takeIf { it.isNotEmpty() }
                        var backText = backingText(tidy(bgPlain))
                        // A line of backing vocals only is a line of its own.
                        if (text.isEmpty() && backText != null) { text = backText; foreground = back; back = null; backText = null }
                        if (text.isNotEmpty() && lineStart >= 0) {
                            val tr = tidy(inTr).ifEmpty { null } ?: key?.let(headTranslation::get)
                            val ro = tidy(inRo).ifEmpty { null } ?: key?.let(headRoman::get)
                            lines += LyricLine(lineStart, text, foreground,
                                translation = tr?.takeIf { LyricsMatch.key(it) != LyricsMatch.key(text) },
                                romanization = ro?.takeIf { LyricsMatch.key(it) != LyricsMatch.key(text) },
                                voice = voice, background = backText, backgroundWords = back)
                        }
                        words = null
                    }
                }
            }
        }
        if (lines.isEmpty()) null
        else Lyrics(lines, synced = true, translationLanguage = translationLanguage.takeIf { lines.any { it.translation != null } })
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
            (if (durationMs > 0) "&duration=$durationMs" else ""), timeoutMs = 5000, provider = "kugou") ?: return null
        val cands = runCatching { JSONObject(search).optJSONArray("candidates") }.getOrNull() ?: return null
        val c = (0 until cands.length()).mapNotNull { cands.optJSONObject(it) }.firstOrNull {
            sameSong(it.optString("song"), it.optString("singer"), it.optLong("duration"), title, artist, durationMs)
        } ?: return null
        val dl = httpGet("https://lyrics.kugou.com/download?ver=1&client=pc&id=${c.optString("id")}&accesskey=${c.optString("accesskey")}&fmt=krc&charset=utf8", timeoutMs = 5000, provider = "kugou") ?: return null
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
    internal fun sameSong(candTitle: String, candArtist: String, candDurationMs: Long, title: String, artist: String, durationMs: Long): Boolean {
        if (matchKey(artist).isEmpty() || (candArtist.split(',', '&', '、', '/') + candArtist).none { LyricsMatch.sameArtist(it, artist) }) return false
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

    // Letters and digits only, accents and case folded away ("Rosalía" is "rosalia").
    internal fun matchKey(s: String) = LyricsMatch.key(s)
    // Exact normalized identity is conservative: ambiguous fallbacks are rejected.
    private fun overlaps(a: String, b: String) = a.isNotEmpty() && a == b

    /**
     * Fallback hits are only used when they are the same song: same artist (accents and the
     * order of its words aside), same title (either may carry extras), and a similar length
     * when both are known.
     */
    internal fun isPlausibleMatch(
        candTitle: String, candArtist: String, candDurationSec: Int,
        title: String, artist: String, durationSec: Int,
    ): Boolean {
        if (!LyricsMatch.sameArtist(candArtist, artist)) return false
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

    /**
     * A GET, its body on a 200, else null. [provider] (the health id, see [LyricsHealth]): a 5xx, a
     * 429 or an exception counts against it, any other answer (a 404 too) is an answer; a provider
     * set aside is not asked at all, unless asked for by hand ([fetchFrom]).
     */
    private fun httpGet(spec: String, timeoutMs: Int = 5000, provider: String? = null): String? {
        if (provider != null && bypass.get() != true) health.reason(provider)?.let {
            Log.d("LyricsApi", "$provider set aside ($it)")
            health.skipped()
            return null
        }
        return runCatching {
            com.music.spotui.data.DataSaver.check()
            val conn = (URL(spec).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                setRequestProperty("User-Agent", UA)
                setRequestProperty("Accept", "application/json, text/html;q=0.9, */*;q=0.8")
            }
            try {
                val code = conn.responseCode
                if (code != 200) {
                    Log.d("LyricsApi", "HTTP $code for $spec")
                    if (provider == null) health.skipped()
                    else if (code >= 500 || code == 429 || code == 408) health.failed(provider)
                    else health.answered(provider)
                    return null
                }
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                provider?.let(health::answered)
                body
            } finally {
                conn.disconnect()
            }
        }.getOrElse {
            Log.d("LyricsApi", "request failed: ${it.message}")
            if (provider == null || it is com.music.spotui.data.DataSaver.Blocked) health.skipped() else health.failed(provider)
            null
        }
    }

    /**
     * [provider]'s answer for [track]: not asked again for [LyricsHealth.MISSING_MS] after it had
     * no lyrics (a failed or skipped request is not "no lyrics": it is asked again).
     */
    private inline fun asking(provider: String, track: Track, block: () -> Lyrics?): Lyrics? {
        if (health.isMissing(track.key, provider)) return null
        val lostBefore = health.lostCount()
        val found = block()
        if (found == null && health.lostCount() == lostBefore) health.noteMissing(track.key, provider)
        return found
    }
}
