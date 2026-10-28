package com.music.spotui.cast

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream

/**
 * One message of the Cast protocol (CastV2): the `CastMessage` protobuf of Chromium's
 * `cast_channel.proto` (BSD-3-Clause, The Chromium Authors), coded by hand here, since it has
 * only seven fields. On the wire each message is a 4-byte big-endian length and the message.
 *
 * Fields: 1 protocol_version (enum, CASTV2_1_0 = 0), 2 source_id, 3 destination_id,
 * 4 namespace, 5 payload_type (STRING = 0, BINARY = 1), 6 payload_utf8, 7 payload_binary.
 */
data class CastMessage(
    val source: String,
    val destination: String,
    val namespace: String,
    val payload: String = "",
    val binary: ByteArray? = null,
) {
    fun encode(): ByteArray {
        val out = ByteArrayOutputStream(64 + payload.length)
        // proto2 "required" fields are written even when they hold their default.
        out.tag(1, 0); out.varint(0)
        out.text(2, source)
        out.text(3, destination)
        out.text(4, namespace)
        out.tag(5, 0); out.varint(if (binary != null) 1 else 0)
        if (binary != null) { out.tag(7, 2); out.varint(binary.size.toLong()); out.write(binary) } else out.text(6, payload)
        return out.toByteArray()
    }

    override fun equals(other: Any?): Boolean = other is CastMessage && source == other.source &&
        destination == other.destination && namespace == other.namespace && payload == other.payload &&
        (binary?.contentEquals(other.binary ?: return false) ?: (other.binary == null))

    override fun hashCode(): Int = ((source.hashCode() * 31 + destination.hashCode()) * 31 + namespace.hashCode()) * 31 + payload.hashCode()

    companion object {
        /** The protocol's limit for one message. */
        const val MAX_SIZE = 64 * 1024

        fun decode(bytes: ByteArray): CastMessage {
            var i = 0
            fun varint(): Long {
                var shift = 0; var v = 0L
                while (true) {
                    if (i >= bytes.size) throw IOException("cast: truncated varint")
                    val b = bytes[i++].toInt() and 0xFF
                    v = v or ((b and 0x7F).toLong() shl shift)
                    if (b < 0x80) return v
                    shift += 7
                    if (shift > 63) throw IOException("cast: varint too long")
                }
            }
            fun chunk(): ByteArray {
                val n = varint()
                if (n < 0 || i + n > bytes.size) throw IOException("cast: truncated field")
                return bytes.copyOfRange(i, (i + n).toInt()).also { i += n.toInt() }
            }
            var source = ""; var destination = ""; var namespace = ""; var payload = ""; var binary: ByteArray? = null
            while (i < bytes.size) {
                val key = varint()
                val field = (key ushr 3).toInt()
                when (val wire = (key and 7).toInt()) {
                    0 -> varint()
                    1 -> i += 8
                    5 -> i += 4
                    2 -> {
                        val b = chunk()
                        when (field) {
                            2 -> source = String(b, Charsets.UTF_8)
                            3 -> destination = String(b, Charsets.UTF_8)
                            4 -> namespace = String(b, Charsets.UTF_8)
                            6 -> payload = String(b, Charsets.UTF_8)
                            7 -> binary = b
                        }
                    }
                    else -> throw IOException("cast: wire type $wire")
                }
            }
            if (i > bytes.size) throw IOException("cast: truncated field")
            return CastMessage(source, destination, namespace, payload, binary)
        }

        /** Writes [m] with its length before it. */
        fun write(out: OutputStream, m: CastMessage) {
            val body = m.encode()
            val head = byteArrayOf((body.size ushr 24).toByte(), (body.size ushr 16).toByte(), (body.size ushr 8).toByte(), body.size.toByte())
            out.write(head + body)
            out.flush()
        }

        /** Reads the next message (blocks); an end of stream or a length past [MAX_SIZE] is an [IOException]. */
        fun read(input: DataInputStream): CastMessage {
            val n = input.readInt()
            if (n < 0 || n > MAX_SIZE) throw IOException("cast: message of $n bytes")
            val body = ByteArray(n)
            input.readFully(body)
            return decode(body)
        }

        private fun OutputStream.varint(value: Long) {
            var v = value
            while (v and 0x7FL.inv() != 0L) { write(((v and 0x7F) or 0x80).toInt()); v = v ushr 7 }
            write(v.toInt())
        }
        private fun OutputStream.tag(field: Int, wire: Int) = varint(((field shl 3) or wire).toLong())
        private fun OutputStream.text(field: Int, s: String) {
            val b = s.toByteArray(Charsets.UTF_8)
            tag(field, 2); varint(b.size.toLong()); write(b)
        }
    }
}
