package com.music.spotui.data.offline

/*
 * Ported from Spotui (https://github.com/Spotui/Spotui), GPL-3.0.
 */

import android.util.Log

/**
 * Downloads an MPEG-DASH stream of FLAC in fragmented MP4 (FLAC-in-ISOBMFF) and remuxes it
 * into a real single `.flac` file, no ffmpeg:
 *   1. take STREAMINFO from the init segment's `dfLa` box,
 *   2. concatenate every media segment's `mdat` payload (the FLAC frames),
 *   3. write `fLaC` + STREAMINFO metadata block + frames.
 * A plain container operation: the manifest is a download-source extension's `dash-flac` file
 * (one `SegmentTemplate` with `initialization`, `media` with `$Number$`, and a
 * `SegmentTimeline`), the segments are fetched with the extension's headers.
 */
internal object DashFlacRemux {
    private const val TAG = "DashFlacRemux"
    private val UA get() = OfflineDownloader.DownloadUa
    private const val PARALLEL_SEGMENTS = 3

    /** [streamUrl] is either a `data:application/dash+xml;base64,…` URI or the MPD text itself. */
    fun download(streamUrl: String, outFile: java.io.File, headers: Map<String, String> = emptyMap()): Boolean = runCatching {
        val mpd = mpdFromUrl(streamUrl) ?: return false
        val initUrl = htmlUnescape(Regex("initialization=\"([^\"]+)\"").find(mpd)?.groupValues?.get(1) ?: return false)
        val mediaTmpl = htmlUnescape(Regex("media=\"([^\"]+)\"").find(mpd)?.groupValues?.get(1) ?: return false)
        val startNumber = Regex("startNumber=\"(\\d+)\"").find(mpd)?.groupValues?.get(1)?.toIntOrNull() ?: 1
        val segCount = segmentCount(mpd)
        if (segCount <= 0) return false

        val initBytes = httpBytes(initUrl, headers) ?: return false
        val streamInfo = flacStreamInfoFromInit(initBytes) ?: return false

        java.io.BufferedOutputStream(java.io.FileOutputStream(outFile)).use { out ->
            out.write(byteArrayOf(0x66, 0x4c, 0x61, 0x43)) // "fLaC"
            // metadata block header: last-block(0x80) | STREAMINFO(0) + 24-bit length
            out.write(0x80)
            out.write((streamInfo.size ushr 16) and 0xFF)
            out.write((streamInfo.size ushr 8) and 0xFF)
            out.write(streamInfo.size and 0xFF)
            out.write(streamInfo)
            // Three segments in flight at once, written in order.
            val pool = java.util.concurrent.Executors.newFixedThreadPool(PARALLEL_SEGMENTS)
            try {
                val numbers = (startNumber until startNumber + segCount).toList()
                for (batch in numbers.chunked(PARALLEL_SEGMENTS * 2)) {
                    val parts = batch.map { n -> pool.submit<ByteArray?> { httpBytes(mediaTmpl.replace("\$Number$", n.toString()), headers) } }
                    for (p in parts) writeMdatPayloads(p.get() ?: return false, out)
                }
            } finally {
                pool.shutdownNow()
            }
        }
        Log.d(TAG, "DASH remuxed to FLAC ($segCount segments)")
        true
    }.getOrElse { Log.w(TAG, "DASH remux failed: ${it.message}"); runCatching { outFile.delete() }; false }

    private fun mpdFromUrl(url: String): String? = when {
        url.startsWith("data:application/dash+xml;base64,") ->
            runCatching { String(java.util.Base64.getMimeDecoder().decode(url.substringAfter("base64,"))) }.getOrNull()
        url.contains("<MPD") -> url
        else -> null
    }

    /** Segment count from a SegmentTimeline (`<S d= r=/>`; r is a repeat count). */
    private fun segmentCount(mpd: String): Int {
        var total = 0
        for (m in Regex("<S\\b[^>]*/?>").findAll(mpd)) {
            total += 1 + (Regex("\\br=\"(\\d+)\"").find(m.value)?.groupValues?.get(1)?.toIntOrNull() ?: 0)
        }
        return total
    }

    /** Pull the 34-byte STREAMINFO out of an init segment's `dfLa` box. */
    private fun flacStreamInfoFromInit(init: ByteArray): ByteArray? {
        val tag = byteArrayOf(0x64, 0x66, 0x4c, 0x61) // "dfLa"
        val k = indexOf(init, tag)
        if (k < 0 || k + 12 > init.size) return null
        // after dfLa(4) + FullBox(4): metadata block header(4) then STREAMINFO
        val len = ((init[k + 9].toInt() and 0xFF) shl 16) or
            ((init[k + 10].toInt() and 0xFF) shl 8) or (init[k + 11].toInt() and 0xFF)
        if (len <= 0 || k + 12 + len > init.size) return null
        return init.copyOfRange(k + 12, k + 12 + len)
    }

    /** Append every `mdat` box payload (the FLAC frames) from an MP4 fragment. */
    private fun writeMdatPayloads(seg: ByteArray, out: java.io.OutputStream) {
        var i = 0
        while (i + 8 <= seg.size) {
            var size = ((seg[i].toLong() and 0xFF) shl 24) or ((seg[i + 1].toLong() and 0xFF) shl 16) or
                ((seg[i + 2].toLong() and 0xFF) shl 8) or (seg[i + 3].toLong() and 0xFF)
            var hdr = 8
            if (size == 1L) {
                if (i + 16 > seg.size) break
                size = (0..7).fold(0L) { acc, b -> (acc shl 8) or (seg[i + 8 + b].toLong() and 0xFF) }
                hdr = 16
            } else if (size == 0L) {
                size = (seg.size - i).toLong()
            }
            if (size < hdr) break
            if (seg[i + 4] == 0x6D.toByte() && seg[i + 5] == 0x64.toByte() &&
                seg[i + 6] == 0x61.toByte() && seg[i + 7] == 0x74.toByte() // "mdat"
            ) {
                out.write(seg, i + hdr, (size - hdr).toInt())
            }
            i += size.toInt()
        }
    }

    private fun httpBytes(url: String, headers: Map<String, String>): ByteArray? = runCatching {
        // The safe client (see MediaHttp): no local address, redirects re-checked, the extension's headers kept on its own host only.
        com.music.spotui.extensions.MediaHttp.open(url, headers = headers, fixed = mapOf("User-Agent" to UA), timeoutMs = 30_000).use { c ->
            if (c.response.code !in 200..299) null else c.response.body.bytes()
        }
    }.getOrNull()

    private fun htmlUnescape(s: String): String =
        s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }
}
