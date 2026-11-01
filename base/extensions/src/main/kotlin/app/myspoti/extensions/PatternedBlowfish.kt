package app.myspoti.extensions

import java.io.InputStream
import java.io.OutputStream
import java.io.InterruptedIOException
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import javax.crypto.spec.IvParameterSpec

/** Bounded, provider-neutral download transform. Keys are supplied by the extension. */
data class FileTransform(
    val keyHex: String,
    val ivHex: String,
    val segmentBytes: Int = 2048,
    val every: Int = 3,
    val offset: Int = 0,
) {
    fun checked(): FileTransform {
        require(keyHex.length in 8..112 && keyHex.length % 2 == 0 && keyHex.matches(Regex("[0-9a-fA-F]+"))) { "transform: invalid Blowfish key" }
        require(ivHex.matches(Regex("[0-9a-fA-F]{16}"))) { "transform: invalid CBC IV" }
        require(segmentBytes in 8..65536 && segmentBytes % 8 == 0) { "transform: invalid segment size" }
        require(every in 1..1024 && offset in 0 until every) { "transform: invalid pattern" }
        return this
    }
}

object PatternedBlowfish {
    /** Lazy, seekable decoding: upstream starts at an absolute segment-aligned byte offset. */
    fun open(input: InputStream, spec: FileTransform, position: Long = 0): InputStream {
        spec.checked()
        require(position >= 0 && position % spec.segmentBytes == 0L) { "transform: unaligned range" }
        val key = SecretKeySpec(spec.keyHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray(), "Blowfish")
        val iv = IvParameterSpec(spec.ivHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray())
        val cipher = Cipher.getInstance("Blowfish/CBC/NoPadding")
        return object : InputStream() {
            var index = position / spec.segmentBytes
            var block = ByteArray(0)
            var at = 0
            var ended = false
            fun fill(): Boolean {
                if (at < block.size) return true
                if (ended) return false
                val next = ByteArray(spec.segmentBytes)
                var n = 0
                while (n < next.size) {
                    if (Thread.currentThread().isInterrupted) throw InterruptedIOException("transform cancelled")
                    val got = input.read(next,n,next.size-n)
                    if (got < 0) { ended = true; break }
                    if (got == 0) {
                        val one = input.read()
                        if (one < 0) { ended = true; break }
                        next[n++] = one.toByte()
                    } else n += got
                }
                block = if (n == next.size && index % spec.every == spec.offset.toLong()) {
                    cipher.init(Cipher.DECRYPT_MODE,key,iv)
                    cipher.doFinal(next)
                } else next.copyOf(n)
                at = 0; index++
                return n > 0
            }
            override fun read(): Int = if (fill()) block[at++].toInt() and 255 else -1
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                require(off >= 0 && len >= 0 && off <= b.size-len)
                if (len == 0) return 0
                if (!fill()) return -1
                val n = minOf(len,block.size-at)
                block.copyInto(b,off,at,at+n); at += n
                return n
            }
            override fun close() { block.fill(0); ended = true; input.close() }
        }
    }

    /** Full-file processing uses the same decoder as progressive playback. Streams remain caller-owned. */
    fun transform(input: InputStream, output: OutputStream, spec: FileTransform) {
        open(input, spec).copyTo(output)
    }
}
