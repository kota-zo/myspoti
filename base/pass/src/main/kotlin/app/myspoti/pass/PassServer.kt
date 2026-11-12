package app.myspoti.pass

import java.io.BufferedInputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The receiving side: listens on the local network for one transfer, for [lifetimeMs] at most,
 * and shows [invite] as a QR code meanwhile. A request that does not open with this invitation's
 * key and token is refused; the first good one ends it. [onPayload] applies what came and answers
 * whether it worked; [onDone] is told once, with the payload or null (stopped, timed out).
 */
class PassServer(
    private val deviceName: String,
    private val onPayload: (PassPayload) -> Boolean,
    private val lifetimeMs: Long = 10 * 60_000L,
    private val onDone: (PassPayload?) -> Unit = {},
    host: String? = PassInvite.localAddress(),
) {
    private val keys = PassCrypto.keyPair()
    private val token = PassCrypto.randomBytes(16)
    private val address = host ?: throw IllegalStateException("no local network")
    private val server = ServerSocket().apply { reuseAddress = true; soTimeout = 1_000; bind(InetSocketAddress(InetAddress.getByName(address), 0)) }
    private val over = AtomicBoolean(false)

    val invite = PassInvite(address, server.localPort, keys.public.encoded, token, deviceName)

    fun start(): PassServer {
        Thread({ serve() }, "pass-server").apply { isDaemon = true; start() }
        return this
    }

    fun stop() { if (over.compareAndSet(false, true)) { runCatching { server.close() }; onDone(null) } }

    private fun serve() {
        val until = System.currentTimeMillis() + lifetimeMs
        while (!over.get() && System.currentTimeMillis() < until) {
            val s = runCatching { server.accept() }.getOrNull() ?: continue
            s.use { handle(it) }
        }
        stop()
    }

    private fun handle(s: Socket) {
        s.soTimeout = 30_000
        val input = BufferedInputStream(s.getInputStream())
        val out = s.getOutputStream()
        fun answer(code: Int, text: String) {
            val body = text.encodeToByteArray()
            out.write("HTTP/1.1 $code ${if (code == 200) "OK" else "Error"}\r\nContent-Type: text/plain\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".encodeToByteArray())
            out.write(body); out.flush()
        }
        val request = readLine(input) ?: return
        val headers = generateSequence { readLine(input)?.takeIf { it.isNotEmpty() } }.associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }
        if (!request.startsWith("POST /pass ")) return answer(404, "not here")
        val length = headers["content-length"]?.toIntOrNull()?.takeIf { it in 1..MAX_BODY } ?: return answer(413, "too large")
        val body = ByteArray(length).also { var read = 0; while (read < length) { val n = input.read(it, read, length - read); if (n < 0) return; read += n } }
        val payload = runCatching {
            val parts = body.decodeToString().split('.')
            val d = Base64.getUrlDecoder()
            PassPayload.of(PassCrypto.open(keys, token, PassCrypto.Sealed(d.decode(parts[0]), d.decode(parts[1]), d.decode(parts[2]))))
        }.getOrNull() ?: return answer(403, "refused")
        val ok = runCatching { onPayload(payload) }.getOrDefault(false)
        answer(if (ok) 200 else 500, if (ok) "ok" else "not applied")
        if (ok && over.compareAndSet(false, true)) { runCatching { server.close() }; onDone(payload) }
    }

    private fun readLine(input: InputStream): String? {
        val b = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (b.isEmpty()) null else b.toString()
            if (c == '\n'.code) return b.toString().trimEnd('\r')
            if (b.length > 8_192) return null
            b.append(c.toChar())
        }
    }

    companion object {
        /** Settings, a few extensions and a session: well under this. */
        const val MAX_BODY = 48 * 1024 * 1024
    }
}

/** The sending side: seals [payload] for [invite] and posts it. Throws with the reason it failed. */
object PassClient {
    fun send(invite: PassInvite, payload: PassPayload) {
        val sealed = PassCrypto.seal(invite.key, invite.token, payload.toBytes())
        val e = Base64.getUrlEncoder().withoutPadding()
        val body = "${e.encodeToString(sealed.senderKey)}.${e.encodeToString(sealed.nonce)}.${e.encodeToString(sealed.box)}".encodeToByteArray()
        // A plain socket: Android's cleartext rule (no http:// to an address without TLS) is for
        // its HTTP clients; the transfer is encrypted end to end on its own.
        val code = Socket().use { s ->
            s.connect(InetSocketAddress(invite.host, invite.port), 8_000)
            s.soTimeout = 60_000
            val out = s.getOutputStream()
            out.write("POST /pass HTTP/1.1\r\nHost: ${invite.host}:${invite.port}\r\nContent-Type: application/octet-stream\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".encodeToByteArray())
            out.write(body); out.flush()
            val status = s.getInputStream().bufferedReader().readLine().orEmpty()
            status.split(' ').getOrNull(1)?.toIntOrNull() ?: 0
        }
        if (code != 200) throw java.io.IOException(when (code) { 403 -> "the other device refused it (its code changed?)"; 500 -> "the other device could not apply it"; else -> "HTTP $code" })
    }
}
