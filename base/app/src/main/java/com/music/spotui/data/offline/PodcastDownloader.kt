package com.music.spotui.data.offline

import android.util.Log
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshots.SnapshotStateMap
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.data.preferences.isDownloadsEnabled
import com.music.spotui.di.Graph
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/** Download keys of podcast episodes ("episode:<id>"): never a Spotify track id. */
internal const val EPISODE_PREFIX = "episode:"

internal fun isEpisodeKey(id: String) = id.startsWith(EPISODE_PREFIX)

/** The download key of an episode row, or null for a music track. */
internal fun episodeKeyOf(song: SongsModel) = song.url.takeIf { isEpisodeKey(it) }

/**
 * Downloads podcast episodes for offline listening — a separate path from
 * [OfflineDownloader], which is the songs' one (download-source extensions).
 *
 * Spotify never hands out an episode's audio in GraphQL: the Web Player asks
 * `soundfinder/v1/unauth/episode/<id>/com.widevine.alpha`, which returns either the
 * publisher's own clear file (`passthroughUrl`, an MP3/M4A behind podtrac/radiofrance
 * redirects) or only a Widevine-encrypted MP4. Only the clear file is usable here, so:
 *
 * - the URL is resolved right before the GET (the links are signed and expire),
 * - `passthrough = NONE` (Spotify-exclusive shows) is refused, with a message, and no
 *   DRM is ever attempted,
 * - the file is fetched with the same byte-range loop as music downloads, then checked
 *   (content type, magic bytes, size) before it is kept,
 * - it is encrypted in place and indexed with [OfflineTrack.KIND_PODCAST], in the same
 *   store as the songs, under an `episode:<id>` key.
 *
 * One worker only: episodes run 10–150 MB and three downloads starved the player.
 */
object PodcastDownloader {
    private const val TAG = "PodcastDl"
    /** Below this, what came back is an error page or a stub, not an episode. */
    internal const val MIN_BYTES = 100_000L

    data class Request(
        val trackId: String,
        val title: String,
        val artist: String,
        val album: String,
        val coverUrl: String,
        val durationMs: Long,
    ) {
        fun toJson(): JSONObject = JSONObject().put("id", trackId).put("title", title)
            .put("artist", artist).put("album", album).put("duration", durationMs).put("cover", coverUrl)

        companion object {
            fun of(s: SongsModel) = Request(
                trackId = s.url, title = s.title, artist = s.singer, album = s.album,
                coverUrl = s.coverUri, durationMs = s.durationMs.toLong(),
            )
            fun fromJson(o: JSONObject) = Request(
                o.getString("id"), o.optString("title"), o.optString("artist"), o.optString("album"),
                o.optString("cover"), o.optLong("duration"),
            )
        }
    }

    /** A refusal worth telling the user about; [messageRes] is the localized text. */
    private class Unavailable(val messageRes: Int, why: String) : Exception(why)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** Episode keys queued, downloading, or whose last attempt was refused. */
    val requests: SnapshotStateMap<String, Request> = mutableStateMapOf()
    private val queue = Channel<Request>(Channel.UNLIMITED)
    /** Waiting requests, in order (persisted in podcasts-pending.json: a restart resumes them). */
    private val pending = LinkedHashMap<String, Request>()
    private val running = ConcurrentHashMap<String, Job>()
    @Volatile private var started = false

    private fun start() {
        if (started) return
        synchronized(this) {
            if (started) return
            started = true
            scope.launch { for (r in queue) work(r) }
        }
    }

    /** Re-queues the episodes that were waiting when the app last stopped. */
    fun resumePending() {
        val saved = runCatching {
            val a = JSONArray(OfflineStore.podcastPendingFile.readText())
            (0 until a.length()).map { Request.fromJson(a.getJSONObject(it)) }
        }.getOrDefault(emptyList())
        if (saved.isNotEmpty()) enqueueAll(saved)
    }

    /** Downloads [song], which must be an episode row (`url = "episode:<id>"`). */
    fun enqueue(song: SongsModel) {
        // A track row has no episode url: it belongs to OfflineDownloader, never here.
        if (episodeKeyOf(song) == null) return
        val req = Request.of(song)
        // "Downloads" off in Settings means no downloads at all, podcasts included.
        if (!isDownloadsEnabled(Graph.app)) {
            toast(app.myspoti.engine.R.string.downloads_off_toast)
            return
        }
        // Spotify itself marks some episodes unplayable (region, removed, paywall).
        if (!song.playable) {
            toast(app.myspoti.engine.R.string.podcast_download_unavailable)
            return
        }
        if (com.music.spotui.data.DataSaver.metered) toast(app.myspoti.engine.R.string.download_on_mobile_data)
        enqueueAll(listOf(req))
    }

    private fun enqueueAll(list: List<Request>) {
        val added = synchronized(pending) {
            list.filter { r ->
                isEpisodeKey(r.trackId) && !OfflineStore.isDownloaded(r.trackId) &&
                    pending.putIfAbsent(r.trackId, r) == null
            }.also { if (it.isNotEmpty()) savePending() }
        }
        if (added.isEmpty()) return
        scope.launch(Dispatchers.Main) {
            added.forEach { requests[it.trackId] = it; OfflineStore.status[it.trackId] = OfflineStatus.Queued }
        }
        start()
        added.forEach { queue.trySend(it) }
    }

    /** Stops a queued or running download (and forgets a refused one). */
    /**
     * The account's downloads are put away (sign-out, another account signing in): the queue is
     * dropped from memory only (its file goes with the account's folder) and the running downloads
     * stop, waited for a moment so none finishes into the next account's folder.
     */
    internal suspend fun forgetSession() {
        synchronized(pending) { pending.clear() }
        val jobs = running.values.toList()
        running.clear()
        jobs.forEach { it.cancel() }
        kotlinx.coroutines.withTimeoutOrNull(5_000) { jobs.forEach { it.join() } }
        withContext(Dispatchers.Main) { requests.clear() }
    }

    fun cancel(trackId: String) {
        synchronized(pending) { if (pending.remove(trackId) != null) savePending() }
        running.remove(trackId)?.cancel()
        scope.launch(Dispatchers.Main) { OfflineStore.status.remove(trackId); requests.remove(trackId) }
    }

    /** Deletes a downloaded episode (or stops its download). */
    fun remove(trackId: String) {
        cancel(trackId)
        scope.launch { OfflineStore.remove(trackId) }
    }

    /** Tries a refused or failed episode again (the URL is resolved again, so a retry can succeed). */
    fun retry(trackId: String) {
        val r = requests[trackId] ?: return
        enqueueAll(listOf(r))
    }

    private fun savePending() {
        runCatching {
            val a = JSONArray()
            pending.values.forEach { a.put(it.toJson()) }
            com.music.spotui.data.writeTextSafely(OfflineStore.podcastPendingFile, a.toString())
        }
    }

    private suspend fun setStatus(id: String, s: OfflineStatus?) = withContext(Dispatchers.Main) {
        if (s == null) OfflineStore.status.remove(id) else OfflineStore.status[id] = s
    }

    private fun toast(res: Int) = scope.launch(Dispatchers.Main) {
        android.widget.Toast.makeText(Graph.app, res, android.widget.Toast.LENGTH_LONG).show()
    }

    private suspend fun work(r: Request) {
        // Cancelled while it waited in the queue.
        if (synchronized(pending) { !pending.containsKey(r.trackId) }) return
        // Data saver on mobile data: like music downloads, wait for Wi-Fi rather than spend data.
        while (com.music.spotui.data.DataSaver.active) {
            delay(5_000)
            if (synchronized(pending) { !pending.containsKey(r.trackId) }) return
        }
        val job = scope.launch {
            val outcome: OfflineStatus? = try {
                setStatus(r.trackId, OfflineStatus.Downloading(-1f))
                val t = download(r)
                OfflineStore.add(t)
                withContext(Dispatchers.Main) { requests.remove(r.trackId) }
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Unavailable) {
                Log.i(TAG, "${r.title}: ${e.message}")
                toast(e.messageRes)
                OfflineStatus.Unavailable
            } catch (t: Throwable) {
                Log.w(TAG, "${r.title} failed", t)
                toast(app.myspoti.engine.R.string.podcast_download_failed)
                OfflineStatus.Failed
            }
            synchronized(pending) { pending.remove(r.trackId); savePending() }
            if (outcome != null) setStatus(r.trackId, outcome)
        }
        running[r.trackId] = job
        job.join()
        running.remove(r.trackId, job)
    }

    private suspend fun download(r: Request): OfflineTrack {
        val episodeId = r.trackId.removePrefix(EPISODE_PREFIX)
        // Resolved here and not at queue time: a signed publisher URL is only good for hours.
        val audio = app.myspoti.host.android.MyspotiRuntime.api.podcasts.audioUrl("spotify:episode:$episodeId")
        if (audio.passthrough.equals("NONE", ignoreCase = true)) {
            // Spotify-exclusive show: its only copy is Widevine-encrypted. Refuse, never try DRM.
            throw Unavailable(app.myspoti.engine.R.string.podcast_download_protected, "no publisher file (passthrough NONE, ${audio.format})")
        }
        val url = audio.url?.takeIf { it.startsWith("http") }
            ?: throw Unavailable(app.myspoti.engine.R.string.podcast_download_unavailable, "passthrough ${audio.passthrough} without a url")
        Log.i(TAG, "${r.title}: publisher file (passthrough ${audio.passthrough}, ${audio.format})")

        // What the server says it is before a byte is fetched: an expired link answers with a
        // login page or an error page, and that must not end up in the index as an episode.
        val probe = probe(url)
        if (!contentTypeOk(probe.type)) {
            throw Unavailable(app.myspoti.engine.R.string.podcast_download_unavailable, "the server sent ${probe.type}, not audio")
        }

        val base = safeName(r.trackId)
        val tmp = File(OfflineStore.tmpDir, "$base.part")
        val out: File
        try {
            val total = probe.bytes
            val watcher = if (total > 0) scope.launch {
                while (isActive) {
                    setStatus(r.trackId, OfflineStatus.Downloading((tmp.length().toFloat() / total).coerceIn(0f, 1f)))
                    delay(400)
                }
            } else null
            try {
                runInterruptible(Dispatchers.IO) { OfflineDownloader.rangedDownload(url, tmp, total) }
            } finally {
                watcher?.cancel()
                setStatus(r.trackId, OfflineStatus.Downloading(-1f))
            }
            currentCoroutineContext().ensureActive()

            if (tmp.length() <= MIN_BYTES) throw Unavailable(app.myspoti.engine.R.string.podcast_download_failed, "only ${tmp.length()} bytes")
            val head = readHead(tmp)
            if (!audioMagicOk(head)) {
                throw Unavailable(app.myspoti.engine.R.string.podcast_download_failed, "not an audio file (starts with ${hex(head, 4)})")
            }
            out = File(OfflineStore.audioDir, "$base.${audioExtension(url, head)}")
            if (out.exists()) out.delete()
            if (!tmp.renameTo(out)) throw IOException("could not move the file")
        } finally {
            tmp.delete()
        }

        // Complete on disk from here on: "stop" must not leave a file outside the index
        // (it would be swept as a stray at the next start).
        return withContext(NonCancellable) {
            // Kept encrypted, like every download: it only plays in Myspoti, on this phone.
            runInterruptible(Dispatchers.IO) { OfflineCrypto.encryptInPlace(Graph.app, out) }
            val cover = runCatching {
                val c = File(OfflineStore.coverDir, "$base.jpg")
                fetchCover(r.coverUrl, c)
                c.name
            }.getOrDefault("")
            OfflineTrack(
                trackId = r.trackId, title = r.title, artist = r.artist, album = r.album,
                albumId = "", durationMs = r.durationMs, coverUrl = r.coverUrl,
                file = out.name, cover = cover, source = "podcast", verified = false,
                bytes = out.length(), addedAt = System.currentTimeMillis(),
                kind = OfflineTrack.KIND_PODCAST,
            )
        }
    }

    private class Probe(val bytes: Long, val type: String)

    /** HEAD with redirects followed: the content type and size, or -1 / "" when unknown. */
    private fun probe(url: String): Probe = runCatching {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "HEAD"
            connectTimeout = 20_000
            readTimeout = 20_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", OfflineDownloader.DownloadUa)
            setRequestProperty("Accept", "*/*")
        }
        try {
            Probe(conn.contentLengthLong.takeIf { it > 0 } ?: -1L, conn.contentType.orEmpty())
        } finally {
            conn.disconnect()
        }
    }.onFailure { Log.w(TAG, "HEAD failed: ${it.message}") }.getOrDefault(Probe(-1L, ""))

    /** The cover, when the publisher has one; a missing cover must not fail the download. */
    private fun fetchCover(url: String, out: File) {
        if (!url.startsWith("http")) return
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", OfflineDownloader.DownloadUa)
        }
        try {
            if (conn.responseCode !in 200..299) return
            conn.inputStream.use { input -> out.outputStream().use { input.copyTo(it) } }
        } finally {
            conn.disconnect()
        }
    }

    private fun readHead(file: File): ByteArray {
        val head = ByteArray(12)
        file.inputStream().use { s ->
            var off = 0
            while (off < head.size) {
                val n = s.read(head, off, head.size - off)
                if (n < 0) break
                off += n
            }
        }
        return head
    }

    private fun hex(bytes: ByteArray, n: Int) =
        bytes.take(n).joinToString(" ") { "%02x".format(it) }

    // ── Pure helpers (unit-tested) ──

    /**
     * A publisher file is any `audio` type, an opaque `…octet-stream`, or an MP4 container; anything
     * else (an HTML or JSON error page served with 200) is a broken link, not an episode.
     * An unknown type is left to the magic-byte check.
     */
    internal fun contentTypeOk(type: String): Boolean {
        val t = type.substringBefore(';').trim().lowercase()
        return t.isEmpty() || t.startsWith("audio/") || t.endsWith("octet-stream") ||
            t == "application/mp4" || t == "video/mp4"
    }

    /**
     * An MP3 with an ID3 tag, a bare MPEG frame sync (`0xFF 0xE?`: 0xFB/0xF3/0xF2…), or an
     * ISO base media file (`ftyp`, an M4A/AAC episode).
     */
    internal fun audioMagicOk(head: ByteArray): Boolean {
        if (head.size >= 3 && head[0] == 'I'.code.toByte() && head[1] == 'D'.code.toByte() && head[2] == '3'.code.toByte()) return true
        if (head.size >= 2 && (head[0].toInt() and 0xFF) == 0xFF && (head[1].toInt() and 0xE0) == 0xE0) return true
        return head.size >= 8 && String(head, 4, 4, Charsets.ISO_8859_1) == "ftyp"
    }

    /** The container decides the extension first: a `.mp3` URL can serve an MP4 (and vice versa). */
    internal fun audioExtension(url: String, head: ByteArray): String {
        if (head.size >= 8 && String(head, 4, 4, Charsets.ISO_8859_1) == "ftyp") return "m4a"
        val fromUrl = url.substringBefore('?').substringAfterLast('/').substringAfterLast('.', "").lowercase()
        return fromUrl.takeIf { it == "mp3" || it == "m4a" || it == "aac" } ?: "mp3"
    }

    /** "episode:abc" → "episode_abc": a colon is legal in the index key, not in a file name. */
    internal fun safeName(trackId: String) = trackId.replace(Regex("[^A-Za-z0-9._-]"), "_")
}
