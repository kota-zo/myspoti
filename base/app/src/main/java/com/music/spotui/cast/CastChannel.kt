package com.music.spotui.cast

import android.annotation.SuppressLint
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.cert.X509Certificate
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/**
 * One TLS connection to a Cast device (port 8009 as a rule), carrying [CastMessage]s: the
 * virtual connection to the device, the heartbeat (a PING every 5 s, PONG to its own), and
 * whatever the caller sends. [onMessage] and [onClosed] run on the reading thread.
 *
 * Cast devices present a self-signed certificate: it is accepted without checks. The
 * optional device authentication (deviceauth namespace) is not used, as for other open senders.
 */
class CastChannel(
    private val host: InetAddress,
    private val port: Int,
    private val onMessage: (CastMessage, JSONObject) -> Unit,
    private val onClosed: (Throwable?) -> Unit,
) {
    /** The id Myspoti speaks under on this connection. */
    val sender = "sender-myspoti-" + Integer.toHexString(java.security.SecureRandom().nextInt())
    private lateinit var socket: SSLSocket
    private lateinit var out: BufferedOutputStream
    @Volatile private var closed = false
    @Volatile private var lastHeard = 0L
    private val beat = Executors.newSingleThreadScheduledExecutor { Thread(it, "cast-heartbeat").apply { isDaemon = true } }
    private var beating: ScheduledFuture<*>? = null

    /** The phone's own address on the network the device is on (the media server listens there). */
    val localAddress: InetAddress get() = socket.localAddress

    /** Connects (blocks, at most [timeoutMs]), says CONNECT to the device and starts reading and the heartbeat. */
    fun open(timeoutMs: Int = 5000) {
        val ctx = SSLContext.getInstance("TLS").apply { init(null, arrayOf(AnyCertificate), java.security.SecureRandom()) }
        val s = ctx.socketFactory.createSocket() as SSLSocket
        try {
            s.connect(InetSocketAddress(host, port), timeoutMs)
            s.soTimeout = 0
            s.tcpNoDelay = true
            s.startHandshake()
        } catch (e: Exception) {
            // Not connected: the socket is closed here, nothing else holds it.
            runCatching { s.close() }
            throw e
        }
        socket = s
        out = BufferedOutputStream(s.outputStream)
        lastHeard = System.currentTimeMillis()
        send(CastProtocol.RECEIVER, CastProtocol.NS_CONNECTION, CastProtocol.connect())
        Thread({ readLoop(DataInputStream(s.inputStream.buffered())) }, "cast-read").apply { isDaemon = true }.start()
        beating = beat.scheduleWithFixedDelay({
            if (closed) return@scheduleWithFixedDelay
            // Nothing heard for long (each PING is answered): the device or the Wi-Fi is gone.
            if (System.currentTimeMillis() - lastHeard > 20_000) { close(java.io.IOException("no answer for 20 s")); return@scheduleWithFixedDelay }
            runCatching { send(CastProtocol.RECEIVER, CastProtocol.NS_HEARTBEAT, CastProtocol.type("PING")) }
        }, 5, 5, TimeUnit.SECONDS)
    }

    fun send(destination: String, namespace: String, json: JSONObject) {
        if (closed) throw java.io.IOException("cast: connection closed")
        val m = CastMessage(sender, destination, namespace, json.toString())
        synchronized(this) { CastMessage.write(out, m) }
    }

    private fun readLoop(input: DataInputStream) {
        try {
            while (!closed) {
                val m = CastMessage.read(input)
                lastHeard = System.currentTimeMillis()
                if (m.binary != null) continue
                val json = runCatching { JSONObject(m.payload) }.getOrNull() ?: continue
                if (m.namespace == CastProtocol.NS_HEARTBEAT) {
                    if (json.optString("type") == "PING") runCatching { send(m.source, CastProtocol.NS_HEARTBEAT, CastProtocol.type("PONG")) }
                    continue
                }
                onMessage(m, json)
            }
        } catch (t: Throwable) {
            close(t)
        }
    }

    /** Closes the connection; [onClosed] hears it once ([why] null: asked by Myspoti). */
    fun close(why: Throwable? = null) {
        if (closed) return
        closed = true
        beating?.cancel(false)
        beat.shutdown()
        if (why == null) runCatching { synchronized(this) { CastMessage.write(out, CastMessage(sender, CastProtocol.RECEIVER, CastProtocol.NS_CONNECTION, CastProtocol.type("CLOSE").toString())) } }
        runCatching { socket.close() }
        onClosed(why)
    }

    @SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager")
    private object AnyCertificate : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
}
