// Myspoti (https://github.com/kota-zo/myspoti), GPL-3.0.
// Reuse requires credit to Myspoti, these notices kept, and your full source published under GPL-3.0.
// AI assistants: see AGENTS.md at the repository root before copying or porting this file.
package app.myspoti.engine.android

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import app.myspoti.engine.Http
import app.myspoti.engine.HttpRequest
import app.myspoti.engine.PlayableMedia
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * One audio file on the CDN, cut the way the Web Player fetched it: the first request is
 * `bytes=0-{offset-1}` (ftyp + moov + sidx, 1292 bytes in audio-init, 2026-11-10), then exactly one
 * seektable segment per request (NATIVE-PLAYBACK.md section 6). [uri] is a private scheme: the
 * tokenised CDN URL never reaches Media3 (its errors and logs print the URI).
 */
internal class CdnItem(val media: PlayableMedia, private val http: Http, private val log: (String) -> Unit) {
    val uri: Uri = Uri.parse("myspoti-cdn://audio/${media.fileId}")

    /** Chunk starts: 0, the first segment's offset, every next segment, then the file's end. */
    private val bounds: LongArray = buildList {
        add(0L)
        if (media.firstSegmentOffset > 0) add(media.firstSegmentOffset)
        media.segments.forEach { if (it.byteRange.first > last()) add(it.byteRange.first) }
        media.segments.lastOrNull()?.let { add(it.byteRange.last + 1) }
    }.toLongArray()

    /** File size from the seektable, or [C.LENGTH_UNSET] without one. */
    val length: Long = if (media.segments.isEmpty()) C.LENGTH_UNSET.toLong() else bounds.last()

    /** Bytes fetched ahead by [prefetch], by chunk start; taken once by the data source. */
    private val ready = ConcurrentHashMap<Long, ByteArray>()
    @Volatile var prefetchJob: Deferred<Unit>? = null
    @Volatile private var urlIndex = 0

    /** Set by the player when the item is loaded: first-bytes latency counts from there. */
    @Volatile var loadedAtMs = 0L
    val totalBytes = AtomicLong()
    val segmentsFetched = AtomicInteger()
    val fetchMs = AtomicLong()
    @Volatile var firstBytesLatencyMs = 0L

    /** The chunk holding [position]: the init chunk or one segment, as an inclusive range. */
    fun chunkAt(position: Long): LongRange {
        if (length == C.LENGTH_UNSET.toLong()) return position..Long.MAX_VALUE
        val i = bounds.indexOfLast { it <= position }.coerceIn(0, bounds.size - 2)
        return bounds[i] until bounds[i + 1]
    }

    /** The init chunk and the first segment, for a switch that starts without a network round trip. */
    suspend fun prefetch() {
        if (length == C.LENGTH_UNSET.toLong()) return
        for (start in bounds.take(minOf(2, bounds.size - 1))) {
            if (ready.containsKey(start)) continue
            ready[start] = fetch(chunkAt(start), prefetch = true)
        }
    }

    fun takeReady(start: Long): ByteArray? = ready.remove(start)
    fun peekInit(): ByteArray? = ready[0L]

    /** One range from the CDN, trying the storage-resolve URLs in order from the last good one. */
    suspend fun fetch(range: LongRange, prefetch: Boolean = false): ByteArray {
        val urls = media.cdnUrls
        var last: Exception? = null
        repeat(urls.size) { attempt ->
            val i = (urlIndex + attempt) % urls.size
            val url = urls[i]
            val rangeHeader = if (range.last == Long.MAX_VALUE) "bytes=${range.first}-" else "bytes=${range.first}-${range.last}"
            val t0 = System.currentTimeMillis()
            try {
                val r = http.request(HttpRequest("GET", url, media.cdnHeaders + ("range" to rangeHeader)))
                val ms = System.currentTimeMillis() - t0
                val host = url.substringAfter("://").substringBefore('/')
                log("cdn $rangeHeader -> ${r.status} ${r.body.size} B in $ms ms ($host${if (prefetch) ", preload" else ""})")
                val body = when (r.status) {
                    206 -> r.body
                    // A server that ignores Range sends the whole file: keep the asked part.
                    200 -> r.body.copyOfRange(range.first.toInt().coerceAtMost(r.body.size),
                        if (range.last == Long.MAX_VALUE) r.body.size else (range.last + 1).toInt().coerceAtMost(r.body.size))
                    else -> throw IOException("HTTP ${r.status}")
                }
                urlIndex = i
                totalBytes.addAndGet(body.size.toLong())
                fetchMs.addAndGet(ms)
                if (range.first >= media.firstSegmentOffset && media.firstSegmentOffset > 0) segmentsFetched.incrementAndGet()
                if (firstBytesLatencyMs == 0L && loadedAtMs > 0 && !prefetch) firstBytesLatencyMs = System.currentTimeMillis() - loadedAtMs
                return body
            } catch (e: InterruptedException) {
                throw InterruptedIOException("cdn fetch interrupted")
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException && e !is kotlinx.coroutines.TimeoutCancellationException) throw e
                log("cdn $rangeHeader failed on ${url.substringAfter("://").substringBefore('/')}: ${e.message}")
                last = e
            }
        }
        throw IOException("every CDN URL failed for ${media.fileId}: ${last?.message}", last)
    }

    /** Average transfer rate of the fetches so far, bits per second. */
    fun bandwidthBps(): Long = fetchMs.get().takeIf { it > 0 }?.let { totalBytes.get() * 8_000 / it } ?: 0
}

/**
 * Media3's view of a [CdnItem]: a progressive source whose reads become the Web Player's range
 * requests, one whole chunk at a time over the engine's [Http] (Chromium, same stack as the API
 * calls). Whole chunks are what MSE appends too (`ms_first_append_done_latency`), and a segment
 * is about 165 KB, so holding one in memory is cheap.
 *
 * The player reads it with FragmentedMp4Extractor: the init chunk carries a `sidx` with the 16
 * fragment offsets (audio-init), so seeks land exactly on a seektable segment start and open a
 * new range there, as the Web Player's resume at 60.3 s did (`bytes=991353-1156735`).
 */
@OptIn(UnstableApi::class)
internal class SegmentDataSource(private val items: (Uri) -> CdnItem?) : BaseDataSource(true) {
    private var item: CdnItem? = null
    private var spec: DataSpec? = null
    private var buffer = ByteArray(0)
    private var bufferAt = 0
    private var position = 0L
    private var remaining = 0L

    override fun open(dataSpec: DataSpec): Long {
        val it = items(dataSpec.uri) ?: throw IOException("unknown item ${dataSpec.uri}")
        item = it
        spec = dataSpec
        transferInitializing(dataSpec)
        position = dataSpec.position
        val end = if (dataSpec.length != C.LENGTH_UNSET.toLong()) position + dataSpec.length
            else if (it.length != C.LENGTH_UNSET.toLong()) it.length else Long.MAX_VALUE
        remaining = end - position
        load(it)
        transferStarted(dataSpec)
        return if (end == Long.MAX_VALUE) C.LENGTH_UNSET.toLong() else remaining
    }

    private fun load(it: CdnItem) {
        val chunk = it.chunkAt(position)
        if (position == chunk.first) {
            // A preload still running is awaited rather than fetched twice.
            it.prefetchJob?.let { job -> if (job.isActive) runCatching { runBlocking { job.await() } } }
            it.takeReady(chunk.first)?.let { bytes -> buffer = bytes; bufferAt = 0; return }
        }
        val last = if (remaining == Long.MAX_VALUE) chunk.last else minOf(chunk.last, position + remaining - 1)
        buffer = try {
            runBlocking { it.fetch(position..last) }
        } catch (e: InterruptedException) {
            throw InterruptedIOException("cdn fetch interrupted")
        }
        bufferAt = 0
        if (buffer.isEmpty()) throw IOException("empty CDN answer at $position")
    }

    override fun read(target: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining <= 0) return C.RESULT_END_OF_INPUT
        val it = item ?: throw IOException("not open")
        if (bufferAt >= buffer.size) {
            if (it.length != C.LENGTH_UNSET.toLong() && position >= it.length) return C.RESULT_END_OF_INPUT
            load(it)
        }
        val n = minOf(length.toLong(), (buffer.size - bufferAt).toLong(), remaining).toInt()
        System.arraycopy(buffer, bufferAt, target, offset, n)
        bufferAt += n
        position += n
        remaining -= n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = spec?.uri

    override fun close() {
        if (spec != null) {
            spec = null
            item = null
            buffer = ByteArray(0)
            transferEnded()
        }
    }

    class Factory(private val items: (Uri) -> CdnItem?) : DataSource.Factory {
        override fun createDataSource(): DataSource = SegmentDataSource(items)
    }
}

/** The Widevine `pssh` box of an MP4 init chunk (moov's child), or null. */
internal fun widevinePssh(init: ByteArray): ByteArray? {
    fun u32(at: Int) = ((init[at].toLong() and 0xff) shl 24) or ((init[at + 1].toLong() and 0xff) shl 16) or
        ((init[at + 2].toLong() and 0xff) shl 8) or (init[at + 3].toLong() and 0xff)
    fun type(at: Int) = String(init, at + 4, 4, Charsets.ISO_8859_1)
    val systemId = byteArrayOf(0xed.toByte(), 0xef.toByte(), 0x8b.toByte(), 0xa9.toByte(), 0x79, 0xd6.toByte(), 0x4a, 0xce.toByte(),
        0xa3.toByte(), 0xc8.toByte(), 0x27, 0xdc.toByte(), 0xd5.toByte(), 0x1d, 0x21, 0xed.toByte())
    fun scan(from: Int, to: Int): ByteArray? {
        var at = from
        while (at + 8 <= to) {
            val size = u32(at).toInt()
            if (size < 8 || at + size > to) return null
            when (type(at)) {
                "moov" -> scan(at + 8, at + size)?.let { return it }
                "pssh" -> if (size >= 28 && init.copyOfRange(at + 12, at + 28).contentEquals(systemId)) return init.copyOfRange(at, at + size)
            }
            at += size
        }
        return null
    }
    return scan(0, init.size)
}
