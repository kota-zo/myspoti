package com.music.spotui.video

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.util.Locale
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.io.IOException
import java.util.concurrent.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runInterruptible

/**
 * YouTube side of music videos, through NewPipeExtractor (as Spotui / NewPipe do):
 * find the track's YouTube Music "song" (same master as Spotify) and its official
 * music video, and resolve their streams.
 */
object YouTubeSource {
    private const val TAG = "MusicVideo"
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0"

    val http: OkHttpClient by lazy {
        app.myspoti.host.android.TlsCompat.apply(OkHttpClient.Builder())
            .addInterceptor(com.music.spotui.data.DataSaver.interceptor)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    @Volatile private var ready = false
    @Volatile private var extractorLocale = ""
    private val infoCache = SingleFlightCache<String, StreamInfo>(24, 30 * 60_000L)
    private val typeCache = SingleFlightCache<String, String>(128, 6 * 3_600_000L)
    private val staticCache = SingleFlightCache<String, Boolean>(64, 6 * 3_600_000L)
    private val searchCache = SingleFlightCache<String, List<Hit>>(24, 2 * 60_000L)
    private class Undecidable : IOException("Video classification unavailable")

    private fun <T> optionalExtraction(name: String, block: () -> T): T? = try {
        checkInterrupted()
        block()
    } catch (t: Throwable) {
        if (t is CancellationException) throw t
        if (t is InterruptedException || Thread.currentThread().isInterrupted) {
            Thread.currentThread().interrupt()
            if (t is InterruptedException) throw t
            throw InterruptedException("Video metadata extraction interrupted").apply { initCause(t) }
        }
        Log.w(TAG, "$name failed: ${t.javaClass.simpleName}")
        null
    }

    private fun init() {
        if (ready) return
        synchronized(this) {
            if (ready) return
            val loc = Locale.getDefault()
            extractorLocale = loc.toLanguageTag()
            NewPipe.init(OkDownloader, Localization(loc.language, loc.country.ifBlank { null }), ContentCountry(loc.country.ifBlank { "US" }))
            ready = true
        }
    }

    private object OkDownloader : Downloader() {
        override fun execute(request: Request): Response {
            val body = request.dataToSend()?.toRequestBody()
            val b = okhttp3.Request.Builder()
                .url(request.url())
                .method(request.httpMethod(), body)
                .header("User-Agent", UA)
            request.headers().forEach { (k, values) ->
                b.removeHeader(k)
                values.forEach { b.addHeader(k, it) }
            }
            return InterruptibleHttp.read(http.newCall(b.build())) { r ->
                Response(
                    r.code, r.message, r.headers.toMultimap(),
                    r.body?.string(), r.request.url.toString(),
                )
            }
        }
    }

    data class Hit(val url: String, val name: String, val uploader: String, val durationSec: Long)

    // "$" read as "s" in names: "Bu$hi" is the channel "BUSHI", "A$AP" is "ASAP".
    private fun folded(s: String) = java.text.Normalizer.normalize(s.replace('$', 's'), java.text.Normalizer.Form.NFD)
        .replace(Regex("""\p{M}+"""), "").lowercase(Locale.ROOT)

    private fun norm(s: String) = folded(s)
        .replace(Regex("""\(.*?\)|\[.*?]"""), " ")
        .replace(Regex("""\b(?:feat|ft)\.?(?=\s|$)|\b(?:official|music|video|audio|clip|lyrics?)\b"""), " ")
        .filter { it.isLetterOrDigit() }

    private fun search(query: String, filter: String): List<Hit> {
        checkInterrupted()
        init()
        val key = "$filter\u0000$query\u0000$extractorLocale"
        return searchCache.get(key) {
            // Extractors are mutable and never shared; only a completed list of immutable Hits is cached.
            val ext = ServiceList.YouTube.getSearchExtractor(query, listOf(filter), "")
            ext.fetchPage()
            checkInterrupted()
            ext.initialPage.items.filterIsInstance<StreamInfoItem>().map {
                Hit(it.url.replace("://music.youtube.com/", "://www.youtube.com/"), it.name, it.uploaderName.orEmpty(), it.duration)
            }
        }
    }

    /** Plain YouTube videos for [query] (a podcast episode's upload, for SponsorBlock); blocking, call off the main thread. */
    fun searchVideos(query: String): List<Hit> = search(query, YoutubeSearchQueryHandlerFactory.VIDEOS)

    private fun elided(s: String) = s.replace(Regex("""(?<=\p{L})['’](?=\p{L})"""), "")

    /** The YouTube Music "song" of the track (Topic / art track), duration within 3 s. */
    fun findSong(title: String, artist: String, durationMs: Long): Hit? {
        val main = artist.substringBefore(",").trim()
        val hits = search("$title $main", YoutubeSearchQueryHandlerFactory.MUSIC_SONGS)
        return hits.firstOrNull { h ->
            val nameOk = titleMatches(h.name, title)
            val durOk = durationMs <= 0 || kotlin.math.abs(h.durationSec * 1000 - durationMs) <= 3000
            nameOk && durOk && sameArtist(h, main)
        }
    }

    private val excluded = Regex("""(?i)\b(lyrics?|paroles|cover|remix|reaction|karaok[eé]|slowed|sped ?up|nightcore|8d|instrumentale?|extended|tutorial|piano|guitar)\b""")

    /** Title words that must appear in a candidate's name (most of them). */
    private fun titleMatches(candidate: String, title: String): Boolean {
        // An elided word stays one word ("J'SAVAIS" is "jsavais", not "savais", which
        // "Si tu savais" also holds).
        val words = elided(folded(title)).replace(Regex("""\(.*?\)|\[.*?]|\b(?:feat|ft)\.?\s+.*"""), " ")
            .split(Regex("""[^\p{L}\p{N}]+""")).filter { it.length > 1 }
        if (words.isEmpty()) return norm(candidate).contains(norm(title))
        val candidateWords = elided(folded(candidate)).split(Regex("""[^\p{L}\p{N}]+""")).toSet()
        return words.count { it in candidateWords } >= (words.size * 0.6).coerceAtLeast(1.0)
    }

    /**
     * Possible music videos for the track, best first: YouTube Music's videos, then a
     * plain YouTube search (clips posted by a label, a radio, a collective…). The
     * channel is not trusted; [MusicVideoController] keeps the one whose soundtrack
     * really aligns with the song.
     */
    suspend fun videoCandidates(title: String, artist: String, durationMs: Long): List<Hit> = coroutineScope {
        val main = artist.substringBefore(",").trim()
        val music = async(Dispatchers.IO) { runInterruptible { search("$title $main", YoutubeSearchQueryHandlerFactory.MUSIC_VIDEOS) } }
        val plain = async(Dispatchers.IO) { runInterruptible { search("$title $main clip officiel official video", YoutubeSearchQueryHandlerFactory.VIDEOS) } }
        val pool = (music.await().take(6) + plain.await().take(8)).distinctBy { it.url }
        // Artist, title, no other version, length near the track's; the official clips first (MusicVideoMatch).
        MusicVideoMatch.rank(title, artist, durationMs, pool.map { MusicVideoMatch.Entry(it.name, it.uploader, it.durationSec) })
            .map { pool[it] }
    }

    /** A YouTube upload of the plain song (official audio) when YouTube Music has none. */
    fun findAudio(title: String, artist: String, durationMs: Long): Hit? {
        val main = artist.substringBefore(",").trim()
        val hits = search("$title $main audio", YoutubeSearchQueryHandlerFactory.VIDEOS)
        return hits.firstOrNull { h ->
            titleMatches(h.name, title) && !excluded.containsMatchIn(h.name) &&
                durationMs > 0 && kotlin.math.abs(h.durationSec * 1000 - durationMs) <= 2500
        }
    }

    /** Completed metadata is shared read-only; never mutate its streams or the returned StreamInfo. */
    fun info(url: String, fresh: Boolean = false): StreamInfo {
        checkInterrupted()
        init()
        val canonical = YouTubeUrlKey.canonicalUrl(url)
        return infoCache.get(YouTubeUrlKey.cacheKey(url, extractorLocale), fresh) {
            StreamInfo.getInfo(ServiceList.YouTube, canonical)
        }
    }

    fun videoId(url: String): String? = YouTubeUrlKey.videoId(url)

    /**
     * YouTube Music's own classification of a video: MUSIC_VIDEO_TYPE_OMV (official
     * music video), _ATV (the song with its cover: no clip), _UGC (any other upload)…
     */
    fun musicVideoType(url: String): String? = optionalExtraction("video type") {
        val id = videoId(url) ?: throw Undecidable()
        val loc = Locale.getDefault()
        typeCache.get(YouTubeUrlKey.cacheKey(url, loc.toLanguageTag())) {
            val body = JSONObject().put("context", JSONObject().put("client", JSONObject()
                .put("clientName", "WEB_REMIX").put("clientVersion", "1.20250929.01.00")
                .put("hl", loc.language).put("gl", loc.country.ifBlank { "US" })))
                .put("videoId", id).toString()
            val req = okhttp3.Request.Builder().url("https://music.youtube.com/youtubei/v1/next?prettyPrint=false")
                .post(body.toRequestBody())
                .header("Content-Type", "application/json").header("User-Agent", UA)
                .header("Origin", "https://music.youtube.com").build()
            val text = InterruptibleHttp.read(http.newCall(req)) {
                if (!it.isSuccessful) throw IOException("Video type HTTP ${it.code}")
                it.body?.string().orEmpty()
            }
            val i = text.indexOf("\"videoId\":\"$id\"")
            if (i < 0) throw Undecidable()
            // Preserve the existing OMV/ATV/UGC classification; unknown responses are never cached.
            Regex("""\"musicVideoType\":\"(MUSIC_VIDEO_TYPE_\w+)\"""")
                .find(text.substring(i, minOf(text.length, i + 3000)))?.groupValues?.get(1) ?: throw Undecidable()
        }
    }

    /**
     * True when the video is a still image (the song over its cover): the storyboard
     * thumbnails YouTube makes for the seek bar barely change from frame to frame.
     */
    fun isStatic(info: StreamInfo): Boolean? = optionalExtraction("storyboard") {
        staticCache.get(YouTubeUrlKey.cacheKey(info.url, extractorLocale)) {
            val fs = info.previewFrames.minByOrNull { it.frameWidth } ?: throw Undecidable()
            val urls = fs.urls
            if (urls.isEmpty()) throw Undecidable()
            // A full sheet from the middle (intros are often a title card; the last sheet
            // can hold just a few frames of a single shot).
            val perPageAll = fs.framesPerPageX * fs.framesPerPageY
            val fullSheets = maxOf(1, fs.totalCount / maxOf(1, perPageAll))
            val sheet = minOf(urls.size / 2, fullSheets - 1).coerceIn(0, urls.size - 1)
            val sheetUrl = urls[sheet]
            val perPage = fs.framesPerPageX * fs.framesPerPageY
            // The last sheet is only partly filled: empty (black) cells are not frames.
            val inSheet = minOf(perPage, fs.totalCount - sheet * perPage)
            val bytes = InterruptibleHttp.read(http.newCall(okhttp3.Request.Builder().url(sheetUrl).header("User-Agent", UA).build())) {
                if (!it.isSuccessful) throw IOException("Storyboard HTTP ${it.code}")
                it.body?.bytes()
            } ?: throw Undecidable()
            val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: throw Undecidable()
            try {
                val w = fs.frameWidth; val h = fs.frameHeight
                val frames = ArrayList<IntArray>()
                for (y in 0 until fs.framesPerPageY) for (x in 0 until fs.framesPerPageX) {
                    checkInterrupted()
                    if (y * fs.framesPerPageX + x >= inSheet) continue
                    if ((x + 1) * w > bmp.width || (y + 1) * h > bmp.height) continue
                    val px = IntArray(w * h)
                    bmp.getPixels(px, 0, w, x * w, y * h, w, h)
                    frames.add(px)
                }
                if (frames.size < 12) throw Undecidable()
                // Share of pixels that clearly change between neighbouring thumbnails. A still
                // cover only shows JPEG noise (max ~0.3 %); real clips, even dark ones, change
                // far more (measured: 19–77 % at their cuts).
                var maxChanged = 0.0
                for (f in 1 until frames.size) {
                    checkInterrupted()
                    val a = frames[f - 1]; val b = frames[f]
                    var changed = 0
                    for (p in a.indices) {
                        val la = (a[p] shr 16 and 255) + (a[p] shr 8 and 255) + (a[p] and 255)
                        val lb = (b[p] shr 16 and 255) + (b[p] shr 8 and 255) + (b[p] and 255)
                        if (kotlin.math.abs(la - lb) > 90) changed++
                    }
                    maxChanged = maxOf(maxChanged, changed.toDouble() / a.size)
                }
                Log.i(TAG, "storyboard ${info.name}: ${frames.size} frames ${w}x$h (sheet ${bmp.width}x${bmp.height}), max changed ${"%.1f".format(maxChanged * 100)} %")
                maxChanged < 0.02
            } finally { bmp.recycle() }
        }
    }

    /** Uploads that are not a clip even when YouTube Music files them as one. */
    private val notAClip = Regex("""(?i)\b(audio officiel|official audio|lyric video|paroles\s*/\s*lyrics|letra|testo|sped up|reverb)\b""")

    /**
     * Whether the real YouTube upload behind a candidate can be the artist's clip.
     * YouTube Music rewrites a matched upload's channel to the artist and its title to the
     * song's (a fan's "melodrama (Paroles/Lyrics)" shows as "melodrama" by "disiz et
     * Theodora", typed OMV), so [info] (read from youtube.com) is the one to check: the real
     * title must not be a lyric / audio / edited upload, and the real channel must be one of
     * the artists' own (or their VEVO / Topic), or a verified channel (label, radio)
     * whose title says it is a clip.
     */
    fun isTrustedClip(info: StreamInfo, artist: String, spotifyHasVideo: Boolean = false): Boolean {
        val name = info.name.orEmpty()
        if (excluded.containsMatchIn(name) || notAClip.containsMatchIn(name)) return false
        // Spotify itself has the clip: a label's channel (unverified, its upload filed as an
        // ordinary video) can be the one; the soundtrack check still decides.
        if (spotifyHasVideo) return true
        val hit = Hit(info.url, name, info.uploaderName.orEmpty(), info.duration)
        val own = artist.split(",").map { it.trim() }.filter { it.isNotEmpty() }.any { sameArtist(hit, it) }
        return own || (info.isUploaderVerified && looksLikeClip(name))
    }

    /** A title that says it is a clip (official / music video / clip officiel…). */
    fun looksLikeClip(name: String): Boolean =
        Regex("""(?i)\b(clip|official (music )?video|music video|vid[eé]o officielle|vid[eé]oclip|videoclip|official visualizer)\b""").containsMatchIn(name)

    fun sameArtist(h: Hit, artist: String): Boolean {
        val a = norm(artist.substringBefore(",").trim())
        val channel = folded(h.uploader).replace(Regex("""\s*-\s*topic$|(?<=.)vevo$"""), "")
        val u = norm(channel)
        return a.isNotEmpty() && u == a
    }
}
