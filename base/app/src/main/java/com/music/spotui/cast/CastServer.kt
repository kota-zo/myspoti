package com.music.spotui.cast

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import com.music.spotui.di.Graph
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet6Address
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * The phone's small HTTP server a Cast device fetches the song from while it casts: device files,
 * downloaded songs (decrypted on the fly from the asked offset) and extension streams (read with
 * their headers, DASH answers as one file), all through the player's own data source
 * ([com.music.spotui.data.offline.OfflineCrypto.Factory]), so it reads exactly what the phone plays.
 *
 * It listens only on the address the phone reaches the Cast device from, on a random port, and
 * answers only paths under a random token made for each cast session. GET and HEAD, one range
 * per request (206), Connection: close.
 */
@OptIn(UnstableApi::class)
object CastServer {
    /** A song (or cover) the device may fetch: [length] -1 when the source does not say it. */
    class Entry(val uri: Uri, val mime: String, val length: Long, val cover: Boolean = false)

    private var server: ServerSocket? = null
    @Volatile private var token = ""
    private val entries = ConcurrentHashMap<Int, Entry>()
    private val nextId = AtomicInteger()
    private val pool = Executors.newCachedThreadPool { Thread(it, "cast-http").apply { isDaemon = true } }
    /** Requests being served, at most this many at once (a device asks two or three ranges at a time). */
    private val busy = AtomicInteger()
    private const val MAX_BUSY = 8

    /**
     * Listens on [address] (a new token and port when it was not listening there: true, the
     * addresses given before are dead). Blocks briefly.
     */
    @Synchronized
    fun start(address: InetAddress): Boolean {
        val before = server
        before?.let { if (!it.isClosed && it.inetAddress == address) return false; runCatching { it.close() } }
        token = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        entries.clear()
        val s = ServerSocket(0, 16, address)
        server = s
        log("server listening on port ${s.localPort}")
        Thread({ accept(s) }, "cast-http-accept").apply { isDaemon = true }.start()
        return before != null
    }

    @Synchronized
    fun stop() {
        server?.let { runCatching { it.close() }; log("server stopped") }
        server = null
        token = ""
        entries.clear()
    }

    /** The address of [e] on this server (the older entries are forgotten: their address dies). */
    fun publish(e: Entry): String {
        val s = server ?: throw IOException("cast server not started")
        val id = nextId.incrementAndGet()
        entries[id] = e
        entries.keys.filter { it <= id - 8 }.forEach { entries.remove(it) }
        val a = s.inetAddress
        val host = if (a is Inet6Address) "[${a.hostAddress?.substringBefore('%')}]" else a.hostAddress
        return "http://$host:${s.localPort}/$token/${if (e.cover) "c" else "a"}/$id"
    }

    /**
     * Opens [uri] as the phone's player would and reads its first bytes: the audio type and the
     * length the Cast device is told. Blocks (an extension may be asked for the stream).
     */
    fun probe(uri: Uri, fallbackName: String): Entry {
        val head = ByteArray(CastFormats.HEAD)
        val length: Long
        var mime: String?
        source().let { src ->
            try {
                val n = src.open(DataSpec.Builder().setUri(uri).build())
                length = if (n == C.LENGTH_UNSET.toLong()) -1 else n
                val got = readFully(src, head)
                mime = CastFormats.mime(head.copyOf(got))
            } finally { runCatching { src.close() } }
        }
        // An ID3 tag first: the type is that of what follows it.
        val tag = CastFormats.id3Size(head)
        if (tag > 0 && (length < 0 || tag < length)) runCatching {
            val src = source()
            try {
                src.open(DataSpec.Builder().setUri(uri).setPosition(tag).build())
                val after = ByteArray(CastFormats.HEAD)
                CastFormats.mime(after.copyOf(readFully(src, after)))?.let { mime = it }
            } finally { runCatching { src.close() } }
        }
        return Entry(uri, mime ?: CastFormats.byName(fallbackName), length)
    }

    private fun source(): DataSource = com.music.spotui.data.offline.OfflineCrypto.Factory(Graph.app).createDataSource()

    private fun readFully(src: DataSource, into: ByteArray): Int {
        var got = 0
        while (got < into.size) {
            val n = src.read(into, got, into.size - got)
            if (n == C.RESULT_END_OF_INPUT) break
            got += n
        }
        return got
    }

    private fun accept(s: ServerSocket) {
        while (!s.isClosed) {
            val c = try { s.accept() } catch (e: IOException) { break }
            if (busy.get() >= MAX_BUSY) { runCatching { c.close() }; continue }
            busy.incrementAndGet()
            pool.execute { try { serve(c) } finally { busy.decrementAndGet() } }
        }
    }

    private fun serve(c: Socket) {
        c.use { socket ->
            socket.soTimeout = 20_000
            val input = BufferedInputStream(socket.getInputStream())
            val out = BufferedOutputStream(socket.getOutputStream(), 64 * 1024)
            val head = try { readHead(input) } catch (e: IOException) { return } ?: return
            val lines = head.split("\r\n")
            val parts = lines.first().split(' ')
            if (parts.size < 3) return status(out, 400, "Bad Request")
            val method = parts[0]
            val headers = lines.drop(1).mapNotNull { l -> l.indexOf(':').takeIf { it > 0 }?.let { l.substring(0, it).trim().lowercase() to l.substring(it + 1).trim() } }.toMap()
            if (method == "OPTIONS") return write(out, 204, "No Content", listOf(
                "Access-Control-Allow-Origin" to "*", "Access-Control-Allow-Methods" to "GET, HEAD, OPTIONS",
                "Access-Control-Allow-Headers" to "Range, Content-Type", "Content-Length" to "0"))
            if (method != "GET" && method != "HEAD") return status(out, 405, "Method Not Allowed")
            val path = parts[1].substringBefore('?').trim('/').split('/')
            val entry = path.takeIf { it.size == 3 && sameToken(it[0]) }?.let { p -> p[2].toIntOrNull()?.let { entries[it] } }
            if (entry == null || (path[1] == "c") != entry.cover) return status(out, 404, "Not Found")
            try {
                if (entry.cover) cover(out, entry, method == "HEAD") else audio(out, entry, headers["range"], method == "HEAD")
            } catch (e: IOException) {
                // The device closes a request it no longer needs (a seek): nothing to say.
            } catch (t: Throwable) {
                log("serving failed: ${t.javaClass.simpleName}")
            }
        }
    }

    private fun audio(out: OutputStream, e: Entry, rangeHeader: String?, headOnly: Boolean) {
        val total = e.length
        val range = ByteRange.parse(rangeHeader, total)
        if (range is ByteRange.Unsatisfiable) {
            log("asked $rangeHeader of $total bytes: 416")
            return write(out, 416, "Range Not Satisfiable", listOf("Content-Range" to "bytes */$total", "Content-Length" to "0"))
        }
        val start = (range as? ByteRange.Part)?.start ?: 0L
        val length = (range as? ByteRange.Part)?.length ?: total
        val headers = mutableListOf("Content-Type" to e.mime, "Access-Control-Allow-Origin" to "*")
        if (total >= 0) headers += "Accept-Ranges" to "bytes"
        if (length >= 0) headers += "Content-Length" to length.toString()
        if (range is ByteRange.Part) headers += "Content-Range" to "bytes ${range.start}-${range.end}/$total"
        val code = if (range is ByteRange.Part) 206 else 200
        log("${if (headOnly) "HEAD" else "GET"} ${rangeHeader ?: "whole"}: $code, ${if (length >= 0) "$length bytes" else "length unknown"}")
        if (headOnly) return write(out, code, if (code == 206) "Partial Content" else "OK", headers)
        val src = source()
        try {
            src.open(DataSpec.Builder().setUri(e.uri).setPosition(start).setLength(if (length >= 0) length else C.LENGTH_UNSET.toLong()).build())
            write(out, code, if (code == 206) "Partial Content" else "OK", headers, flush = false)
            val buf = ByteArray(64 * 1024)
            var left = if (length >= 0) length else Long.MAX_VALUE
            while (left > 0) {
                val n = src.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (n == C.RESULT_END_OF_INPUT) break
                out.write(buf, 0, n)
                left -= n
            }
            out.flush()
        } finally {
            runCatching { src.close() }
        }
    }

    private fun cover(out: OutputStream, e: Entry, headOnly: Boolean) {
        val bytes = Graph.app.contentResolver.openInputStream(e.uri)?.use { it.readBytes() } ?: return status(out, 404, "Not Found")
        val headers = listOf("Content-Type" to CastFormats.imageMime(bytes.copyOf(minOf(bytes.size, 16))),
            "Content-Length" to bytes.size.toString(), "Access-Control-Allow-Origin" to "*")
        write(out, 200, "OK", headers, flush = headOnly)
        if (!headOnly) { out.write(bytes); out.flush() }
    }

    private fun sameToken(t: String): Boolean {
        val mine = token
        return mine.isNotEmpty() && MessageDigest.isEqual(mine.toByteArray(), t.toByteArray())
    }

    /** The request line and headers, up to the blank line (null past 8 KB or at the end of input). */
    private fun readHead(input: InputStream): String? {
        val b = StringBuilder()
        while (b.length < 8192) {
            val c = input.read()
            if (c < 0) return null
            b.append(c.toChar())
            if (b.endsWith("\r\n\r\n")) return b.substring(0, b.length - 4)
        }
        return null
    }

    private fun status(out: OutputStream, code: Int, reason: String) = write(out, code, reason, listOf("Content-Length" to "0"))

    private fun write(out: OutputStream, code: Int, reason: String, headers: List<Pair<String, String>>, flush: Boolean = true) {
        val text = buildString {
            append("HTTP/1.1 $code $reason\r\n")
            headers.forEach { (k, v) -> append(k).append(": ").append(v).append("\r\n") }
            append("Connection: close\r\n\r\n")
        }
        out.write(text.toByteArray(Charsets.ISO_8859_1))
        if (flush) out.flush()
    }

    private fun log(line: String) = app.myspoti.host.android.Logbook.log("cast: $line")
}
