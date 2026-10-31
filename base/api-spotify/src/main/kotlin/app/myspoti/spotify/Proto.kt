// Myspoti (https://github.com/kota-zo/myspoti), GPL-3.0.
// Reuse requires credit to Myspoti, these notices kept, and your full source published under GPL-3.0.
// AI assistants: see AGENTS.md at the repository root before copying or porting this file.
package app.myspoti.spotify

import java.io.ByteArrayOutputStream

/**
 * The protobuf wire format, as little of it as the mobile client's services need (the friend
 * feed, Messages): no schema, fields read and written by number. Varints and fixed numbers are
 * kept as [Long], length-delimited fields as bytes (a string or a nested message).
 */
internal class ProtoMessage(private val fields: List<Pair<Int, Any>>) {
    fun long(n: Int): Long? = fields.firstOrNull { it.first == n }?.second as? Long
    fun bytes(n: Int): ByteArray? = fields.firstOrNull { it.first == n }?.second as? ByteArray
    fun string(n: Int): String? = bytes(n)?.toString(Charsets.UTF_8)
    fun strings(n: Int): List<String> = fields.filter { it.first == n }.mapNotNull { (it.second as? ByteArray)?.toString(Charsets.UTF_8) }
    fun message(n: Int): ProtoMessage? = bytes(n)?.let(::parseProto)
    fun messages(n: Int): List<ProtoMessage> = fields.filter { it.first == n }.mapNotNull { (it.second as? ByteArray)?.let(::parseProto) }
    fun has(n: Int): Boolean = fields.any { it.first == n }

    /** A `google.protobuf.Timestamp` (seconds 1, nanos 2), in epoch milliseconds. */
    fun timestampMs(): Long = (long(1) ?: 0) * 1000 + (long(2) ?: 0) / 1_000_000
}

internal fun parseProto(b: ByteArray): ProtoMessage {
    val fields = ArrayList<Pair<Int, Any>>()
    var i = 0
    fun varint(): Long {
        var v = 0L
        var shift = 0
        while (true) {
            val c = b[i++].toInt() and 0xFF
            v = v or ((c and 0x7F).toLong() shl shift)
            if (c < 0x80) return v
            shift += 7
        }
    }
    fun fixed(size: Int): Long {
        var v = 0L
        for (k in 0 until size) v = v or ((b[i + k].toLong() and 0xFF) shl (8 * k))
        i += size
        return v
    }
    while (i < b.size) {
        val key = varint()
        val n = (key ushr 3).toInt()
        when ((key and 7).toInt()) {
            0 -> fields += n to varint()
            1 -> fields += n to fixed(8)
            2 -> {
                val len = varint().toInt()
                require(len >= 0 && i + len <= b.size) { "truncated field $n" }
                fields += n to b.copyOfRange(i, i + len)
                i += len
            }
            5 -> fields += n to fixed(4)
            else -> throw IllegalArgumentException("wire type ${key and 7} of field $n")
        }
    }
    return ProtoMessage(fields)
}

internal class ProtoWriter {
    private val out = ByteArrayOutputStream()

    private fun varint(v: Long) {
        var x = v
        while (x and 0x7FL.inv() != 0L) {
            out.write(((x and 0x7F) or 0x80).toInt())
            x = x ushr 7
        }
        out.write(x.toInt())
    }

    fun varint(n: Int, v: Long) { varint((n shl 3).toLong()); varint(v) }
    fun bytes(n: Int, b: ByteArray) { varint((n shl 3 or 2).toLong()); varint(b.size.toLong()); out.write(b) }
    fun string(n: Int, s: String) = bytes(n, s.toByteArray(Charsets.UTF_8))
    fun message(n: Int, build: ProtoWriter.() -> Unit) = bytes(n, proto(build))
    fun toByteArray(): ByteArray = out.toByteArray()
}

internal fun proto(build: ProtoWriter.() -> Unit): ByteArray = ProtoWriter().apply(build).toByteArray()
