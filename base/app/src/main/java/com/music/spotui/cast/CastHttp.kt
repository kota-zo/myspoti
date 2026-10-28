package com.music.spotui.cast

/** What part of a song a Cast device asked the phone's server for (its `Range` header). */
sealed class ByteRange {
    /** The whole file: no Range, one the server ignores (several ranges, a length not known), or a bad one. */
    object Full : ByteRange()
    /** Bytes [start] to [end], both included. */
    data class Part(val start: Long, val end: Long) : ByteRange() { val length get() = end - start + 1 }
    /** Starts past the end of the file: 416. */
    object Unsatisfiable : ByteRange()

    companion object {
        /**
         * The range [header] asks of a file of [total] bytes (negative: not known, then only the
         * whole file is served). `bytes=a-b`, `bytes=a-` and `bytes=-n` (the last n bytes).
         */
        fun parse(header: String?, total: Long): ByteRange {
            val h = header?.trim() ?: return Full
            if (!h.startsWith("bytes=", ignoreCase = true)) return Full
            val spec = h.substring(6).trim()
            if (spec.contains(',')) return Full
            val dash = spec.indexOf('-')
            if (dash < 0) return Full
            val a = spec.substring(0, dash).trim()
            val b = spec.substring(dash + 1).trim()
            if (total < 0) return Full
            if (a.isEmpty()) {
                val n = b.toLongOrNull()?.takeIf { it >= 0 } ?: return Full
                if (n == 0L || total == 0L) return Unsatisfiable
                return Part((total - n).coerceAtLeast(0), total - 1)
            }
            val start = a.toLongOrNull()?.takeIf { it >= 0 } ?: return Full
            val end = if (b.isEmpty()) total - 1 else (b.toLongOrNull()?.takeIf { it >= start } ?: return Full).coerceAtMost(total - 1)
            if (start >= total) return Unsatisfiable
            return Part(start, end)
        }
    }
}

/** The audio type of a song from its first bytes, as the Cast device needs it in the load. */
object CastFormats {
    /** Bytes to read for [mime]. */
    const val HEAD = 16

    /** The size of an ID3v2 tag at the start of [head] (its 10-byte header included), or 0 without one. */
    fun id3Size(head: ByteArray): Long {
        if (head.size < 10 || head[0] != 'I'.code.toByte() || head[1] != 'D'.code.toByte() || head[2] != '3'.code.toByte()) return 0
        var n = 0L
        for (k in 6..9) n = (n shl 7) or (head[k].toLong() and 0x7F)
        val footer = if (head[5].toInt() and 0x10 != 0) 10 else 0
        return 10 + n + footer
    }

    /** The MIME type of audio starting with [head], or null when not known (see [byName]). */
    fun mime(head: ByteArray): String? {
        fun at(i: Int, s: String) = head.size >= i + s.length && s.indices.all { head[i + it] == s[it].code.toByte() }
        val b0 = head.getOrNull(0)?.toInt()?.and(0xFF) ?: return null
        val b1 = head.getOrNull(1)?.toInt()?.and(0xFF) ?: 0
        return when {
            at(0, "fLaC") -> "audio/flac"
            at(0, "OggS") -> "audio/ogg"
            at(4, "ftyp") -> "audio/mp4"
            at(0, "RIFF") && at(8, "WAVE") -> "audio/wav"
            b0 == 0x1A && b1 == 0x45 && head.getOrNull(2) == 0xDF.toByte() && head.getOrNull(3) == 0xA3.toByte() -> "audio/webm"
            // ADTS (AAC): sync, then layer 00.
            b0 == 0xFF && (b1 and 0xF6) == 0xF0 -> "audio/aac"
            // MPEG audio frame sync (MP3).
            b0 == 0xFF && (b1 and 0xE0) == 0xE0 -> "audio/mpeg"
            at(0, "ID3") -> "audio/mpeg"
            else -> null
        }
    }

    /** The MIME type a file name says, the last resort. */
    fun byName(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "flac" -> "audio/flac"
        "ogg", "oga", "opus" -> "audio/ogg"
        "m4a", "mp4", "aac" -> "audio/mp4"
        "wav" -> "audio/wav"
        "webm", "weba" -> "audio/webm"
        else -> "audio/mpeg"
    }

    /** The image type of a cover from its first bytes. */
    fun imageMime(head: ByteArray): String = when {
        head.size >= 2 && head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte() -> "image/jpeg"
        head.size >= 4 && head[0] == 0x89.toByte() && head[1] == 'P'.code.toByte() -> "image/png"
        head.size >= 12 && String(head, 8, 4, Charsets.US_ASCII) == "WEBP" -> "image/webp"
        else -> "image/jpeg"
    }
}
