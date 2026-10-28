package com.music.spotui.data.offline

import java.io.IOException
import com.music.spotui.extensions.MediaHttp
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask

/**
 * An MPEG-DASH manifest of FLAC in fragmented MP4 (an `audio-source` answer),
 * played live as one file: the init segment, a `sidx` index written here from the manifest's
 * segment durations and sizes (so the player can seek), then the media segments in order. Only a
 * segment being read is fetched; nothing is kept on disk.
 */
internal class DashLive(
    private val head: ByteArray,
    private val segmentUrls: List<String>,
    private val segmentSizes: LongArray,
    /** Each segment fetched: its index, its length and how long it took (the debug log). */
    private val fetched: (index: Int, bytes: Int, ms: Long) -> Unit = { _, _, _ -> },
    private val fetch: (url: String) -> ByteArray,
) {
    /** The whole virtual file's length. */
    val length: Long = head.size + segmentSizes.sum()

    private val starts = LongArray(segmentSizes.size).also { s ->
        var at = head.size.toLong()
        for (i in segmentSizes.indices) { s[i] = at; at += segmentSizes[i] }
    }
    /** Guards [cachedIndex], [cached] and [loading]; never held during a fetch. */
    private val lock = Any()
    private var cachedIndex = -1
    private var cached: ByteArray = ByteArray(0)
    /** The segments being fetched: a read of one waits for that fetch instead of starting another. */
    private val loading = HashMap<Int, FutureTask<ByteArray>>()

    /** Fetches the first segment ahead, so the song starts without waiting for it. */
    fun warm() {
        if (segmentUrls.isNotEmpty() && synchronized(lock) { cachedIndex < 0 }) segment(0)
    }

    /**
     * Segment [i]'s bytes: from memory, else fetched (outside the lock, so a read is never held
     * behind another segment's fetch, the next song's warm-up included), shared with a
     * concurrent ask for the same segment.
     */
    private fun segment(i: Int): ByteArray {
        val task = synchronized(lock) {
            if (cachedIndex == i) return cached
            loading.getOrPut(i) { FutureTask { fetchChecked(i) } }
        }
        task.run() // nothing when another thread runs or ran it: then only waits for it below
        val bytes = try {
            task.get()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw java.io.InterruptedIOException("segment ${i + 1} interrupted")
        } catch (e: java.util.concurrent.ExecutionException) {
            synchronized(lock) { loading.remove(i, task) }
            throw (e.cause as? IOException) ?: IOException(e.cause)
        }
        synchronized(lock) {
            loading.remove(i, task)
            cached = bytes
            cachedIndex = i
        }
        return bytes
    }

    private fun fetchChecked(i: Int): ByteArray {
        val t0 = System.nanoTime()
        val bytes = fetch(segmentUrls[i])
        fetched(i, bytes.size, (System.nanoTime() - t0) / 1_000_000)
        if (bytes.size.toLong() != segmentSizes[i]) throw IOException("segment ${i + 1} changed size")
        return bytes
    }

    /** Copies up to [length] bytes from [position] into [buffer]; -1 at the end. */
    fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        if (position >= this.length) return -1
        if (position < head.size) {
            val n = minOf(length.toLong(), head.size - position).toInt()
            System.arraycopy(head, position.toInt(), buffer, offset, n)
            return n
        }
        var i = starts.binarySearch(position).let { if (it >= 0) it else -it - 2 }
        if (i < 0) i = 0
        val seg = segment(i)
        val inSeg = (position - starts[i]).toInt()
        val n = minOf(length, seg.size - inSeg)
        System.arraycopy(seg, inSeg, buffer, offset, n)
        return n
    }

    /** What the manifest says: where the init and the segments are, and each segment's length. */
    data class Plan(val initUrl: String, val segmentUrls: List<String>, val timescale: Long, val durations: LongArray)

    companion object {
        // Segment sizes are asked all at once (same CDN host, kept-alive connections): ~4 rounds for a song.
        private const val PARALLEL = 16
        private val cache = object : LinkedHashMap<String, Pair<Long, DashLive>>(8, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Long, DashLive>>?) = size > 4
        }
        private const val KEEP_MS = 10 * 60_000L
        private const val LOG = app.myspoti.host.android.WebPlayerHost.LOG

        /** The live file of [manifestB64] (kept a few minutes: the player opens it again on every seek). */
        fun open(manifestB64: String, headers: Map<String, String>, userAgent: String): DashLive {
            synchronized(cache) {
                cache[manifestB64]?.takeIf { System.currentTimeMillis() - it.first < KEEP_MS }?.let { return it.second }
            }
            val mpd = String(java.util.Base64.getMimeDecoder().decode(manifestB64))
            val plan = parse(mpd) ?: throw IOException("not a segmented DASH manifest")
            val get = { url: String -> httpBytes(url, headers, userAgent) }
            val pool = Executors.newFixedThreadPool(PARALLEL)
            val live = try {
                val init = pool.submit(Callable { get(plan.initUrl) })
                val sizes = plan.segmentUrls.map { u -> pool.submit(Callable { size(u, headers, userAgent) }) }
                val initBytes = init.get()
                val segmentSizes = LongArray(sizes.size) { sizes[it].get() }
                DashLive(initBytes + sidx(plan.timescale, plan.durations, segmentSizes), plan.segmentUrls, segmentSizes, fetched = { i, n, ms ->
                    android.util.Log.d(LOG, "dash: segment ${i + 1}/${plan.segmentUrls.size} fetched, $n bytes in $ms ms")
                }, fetch = get)
            } catch (e: java.util.concurrent.ExecutionException) {
                throw (e.cause as? IOException) ?: IOException(e.cause)
            } finally {
                pool.shutdownNow()
            }
            synchronized(cache) { cache[manifestB64] = System.currentTimeMillis() to live }
            android.util.Log.d(LOG, "dash: opened, ${live.length} bytes in ${plan.segmentUrls.size} segments")
            return live
        }

        /** A SegmentTemplate with a SegmentTimeline (`<S d= r=/>`, r repeats), absolute addresses. */
        fun parse(mpd: String): Plan? {
            fun attr(name: String) = Regex("\\b$name=\"([^\"]+)\"").find(mpd)?.groupValues?.get(1)?.replace("&amp;", "&")
            val init = attr("initialization") ?: return null
            val media = attr("media") ?: return null
            if (!init.startsWith("http") || !media.startsWith("http")) return null
            val timescale = attr("timescale")?.toLongOrNull() ?: 1L
            val start = attr("startNumber")?.toIntOrNull() ?: 1
            val durations = ArrayList<Long>()
            for (m in Regex("<S\\b[^>]*/?>").findAll(mpd)) {
                val d = Regex("\\bd=\"(\\d+)\"").find(m.value)?.groupValues?.get(1)?.toLongOrNull() ?: return null
                val r = Regex("\\br=\"(\\d+)\"").find(m.value)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                repeat(r + 1) { durations += d }
            }
            if (durations.isEmpty()) return null
            val urls = durations.indices.map { media.replace("\$Number\$", (start + it).toString()) }
            return Plan(init, urls, timescale, durations.toLongArray())
        }

        /** An ISO-BMFF `sidx` (version 0): one reference per segment, each starting with a sync sample. */
        fun sidx(timescale: Long, durations: LongArray, sizes: LongArray): ByteArray {
            val n = sizes.size
            val out = java.nio.ByteBuffer.allocate(32 + 12 * n)
            out.putInt(32 + 12 * n).put("sidx".toByteArray()).putInt(0) // size, type, version 0 + flags
            out.putInt(1).putInt(timescale.toInt()).putInt(0).putInt(0) // reference_ID, timescale, earliest time, first offset
            out.putShort(0).putShort(n.toShort())
            for (i in 0 until n) {
                out.putInt((sizes[i] and 0x7FFFFFFF).toInt()) // reference_type 0 (media) + size
                out.putInt(durations[i].toInt())
                out.putInt(0x90000000.toInt()) // starts_with_SAP, SAP type 1
            }
            return out.array()
        }

        /** Through the safe client (see [MediaHttp]): the extension's headers go to its own host only, the app's User-Agent and Range anywhere. */
        private fun connect(url: String, method: String, headers: Map<String, String>, userAgent: String, range: String? = null) =
            MediaHttp.open(url, method, headers, buildMap { put("User-Agent", userAgent); range?.let { put("Range", it) } })

        private fun httpBytes(url: String, headers: Map<String, String>, userAgent: String): ByteArray =
            connect(url, "GET", headers, userAgent).use { c ->
                if (c.response.code !in 200..299) throw IOException("segment HTTP ${c.response.code}")
                c.response.body.bytes()
            }

        /** A segment's length: HEAD, else the total of a one-byte range. */
        private fun size(url: String, headers: Map<String, String>, userAgent: String): Long {
            connect(url, "HEAD", headers, userAgent).use { h ->
                val n = h.response.header("Content-Length")?.toLongOrNull() ?: -1L
                if (h.response.code in 200..299 && n > 0) return n
            }
            connect(url, "GET", headers, userAgent, range = "bytes=0-0").use { r ->
                val total = r.response.header("Content-Range")?.substringAfter('/')?.toLongOrNull()
                return total ?: throw IOException("segment size unknown (HTTP ${r.response.code})")
            }
        }
    }
}
