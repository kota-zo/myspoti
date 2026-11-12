package app.myspoti.pass

import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * A standing link between two devices of the user's that share a [secret] (32 random bytes, given
 * once over a channel already private: the watch's Data Layer, a QR code): one asks, the other
 * answers, straight over the local network. Every question and answer is sealed (AES-256-GCM with a
 * key drawn from the secret); a question is taken once and only within a minute of when it was
 * asked, so one heard on the network cannot be played again.
 *
 * Frames: 4 bytes of length, then the 12-byte nonce and the sealed bytes. A question seals
 * `at \n path \n body` (at: its time in ms); its answer is sealed with the question's nonce as
 * associated data, so it cannot be given to another question.
 */
object PassLink {
    const val SECRET_BYTES = 32
    private const val NONCE = 12
    private const val MAX_FRAME = 64 * 1024 * 1024
    const val WINDOW_MS = 60_000L

    internal fun key(secret: ByteArray): SecretKeySpec {
        require(secret.size == SECRET_BYTES) { "the link's secret is 32 bytes" }
        val k = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(secret, "HmacSHA256")) }.doFinal("orpheon-link-v1".encodeToByteArray())
        return SecretKeySpec(k, "AES")
    }

    internal fun seal(key: SecretKeySpec, aad: ByteArray, plain: ByteArray): ByteArray {
        val nonce = PassCrypto.randomBytes(NONCE)
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce)); updateAAD(aad) }
        return nonce + c.doFinal(plain)
    }

    internal fun open(key: SecretKeySpec, aad: ByteArray, frame: ByteArray): ByteArray {
        require(frame.size > NONCE) { "frame too short" }
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, frame, 0, NONCE)); updateAAD(aad) }
        return c.doFinal(frame, NONCE, frame.size - NONCE)
    }

    internal fun nonceOf(frame: ByteArray) = frame.copyOfRange(0, NONCE)

    internal fun write(out: DataOutputStream, frame: ByteArray) { out.writeInt(frame.size); out.write(frame); out.flush() }

    internal fun read(input: DataInputStream): ByteArray {
        val n = input.readInt()
        require(n in 1..MAX_FRAME) { "frame of $n bytes" }
        return ByteArray(n).also(input::readFully)
    }

    private val QUESTION = "q".encodeToByteArray()
    private fun answerAad(questionNonce: ByteArray) = "a".encodeToByteArray() + questionNonce

    /** Asks [path] with [body] of the device at [host]:[port]; its answer, or throws. */
    fun ask(host: String, port: Int, secret: ByteArray, path: String, body: String = "", timeoutMs: Int = 15_000): String {
        val key = key(secret)
        val question = seal(key, QUESTION, "${System.currentTimeMillis()}\n$path\n$body".encodeToByteArray())
        return Socket().use { s ->
            s.connect(InetSocketAddress(host, port), timeoutMs.coerceAtMost(5_000))
            s.soTimeout = timeoutMs
            write(DataOutputStream(s.getOutputStream().buffered()), question)
            open(key, answerAad(nonceOf(question)), read(DataInputStream(s.getInputStream().buffered()))).decodeToString()
        }
    }

    /**
     * Answers questions sealed with [secret] on [port] (0: one the system picks), from devices on
     * the local network only. [answer] gets the path and body and gives the answer (a thrown
     * error answers "error: …"). Runs until [close].
     */
    class Server(
        secret: ByteArray, port: Int = 0,
        /** Who may ask, by address: devices of the local network (tests also let the loopback in). */
        private val allowed: (String) -> Boolean = PassInvite::isPrivate,
        private val answer: (path: String, body: String) -> String,
    ) : AutoCloseable {
        private val key = key(secret)
        private val socket = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(InetAddress.getByName("0.0.0.0"), port)) }
        private val pool = Executors.newFixedThreadPool(3) { r -> Thread(r, "pass-link").apply { isDaemon = true } }
        private val seen = ConcurrentHashMap<String, Long>()
        @Volatile private var closed = false
        val port: Int get() = socket.localPort

        fun start(): Server {
            Thread({ while (!closed) { val s = runCatching { socket.accept() }.getOrNull() ?: continue; pool.execute { s.use(::handle) } } }, "pass-link-accept")
                .apply { isDaemon = true }.start()
            return this
        }

        private fun handle(s: Socket) = runCatching {
            // Only a device of the same network asks: an address from elsewhere is not answered.
            val from = s.inetAddress?.hostAddress.orEmpty().removePrefix("::ffff:")
            if (!allowed(from)) return@runCatching
            s.soTimeout = 15_000
            val question = read(DataInputStream(s.getInputStream().buffered()))
            val plain = open(key, QUESTION, question).decodeToString()
            val at = plain.substringBefore('\n').toLongOrNull() ?: return@runCatching
            val now = System.currentTimeMillis()
            if (kotlin.math.abs(now - at) > WINDOW_MS) return@runCatching
            // Each question once: a copy of it heard on the network is not answered again.
            seen.entries.removeIf { now - it.value > 2 * WINDOW_MS }
            if (seen.putIfAbsent(nonceOf(question).joinToString("") { "%02x".format(it) }, now) != null) return@runCatching
            val rest = plain.substringAfter('\n')
            val text = runCatching { answer(rest.substringBefore('\n'), rest.substringAfter('\n', "")) }.getOrElse { "error: ${it.message ?: it.javaClass.simpleName}" }
            write(DataOutputStream(s.getOutputStream().buffered()), seal(key, answerAad(nonceOf(question)), text.encodeToByteArray()))
        }

        override fun close() { closed = true; runCatching { socket.close() }; pool.shutdownNow() }
    }
}
