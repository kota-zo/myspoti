// Myspoti (https://github.com/kota-zo/myspoti), GPL-3.0.
// Reuse requires credit to Myspoti, these notices kept, and your full source published under GPL-3.0.
package app.myspoti.host.android

import android.content.Context
import android.net.Uri
import android.net.http.SslCertificate
import android.net.http.SslError
import android.net.http.X509TrustManagerExtensions
import android.os.Build
import okhttp3.OkHttpClient
import java.io.ByteArrayInputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.Certificate
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * TLS trust and protocol compatibility for Android 6–9 (API 23–28).
 *
 * Pre-Android 10 system stores lack modern Root CAs (e.g. Let's Encrypt ISRG Root X1/X2,
 * Google Trust Services R1–R4, newer DigiCert/Sectigo/GlobalSign/Amazon roots) and Android 6
 * ignores `networkSecurityConfig`. When running below API 29, [install] supplements the system
 * trust store with the bundled Mozilla root certificates (`res/raw/ca_roots.pem`) and ensures
 * TLSv1.2 is enabled on every socket. System verification (including user/device CAs) still runs
 * first; the bundled roots are only consulted if the system store rejects a chain.
 */
object TlsCompat {
    private class State(
        val socketFactory: SSLSocketFactory,
        val trustManager: X509TrustManager,
    )

    @Volatile
    private var state: State? = null
    private val verifiedWebCerts = ConcurrentHashMap<String, Long>()
    private const val WEB_CERT_CACHE_TTL_MS = 30 * 60_000L

    fun install(context: Context) {
        // Every Android version, not only 6-9: a television ships a system trust store and then
        // stops updating it (no root certificates after the ones it was built with), and the app's
        // own screens are then the ones that fail — the covers, loaded by a client that trusts the
        // system store, stayed grey on a TV while the page (its own network stack, its own bundled
        // roots) worked. The system store is still asked first; the bundled roots only answer where
        // it has nothing to say.
        if (state != null) return
        val built = runCatching { buildState(context.applicationContext) }.onFailure {
            Logbook.log("tls: compat setup failed: ${it.message}")
        }.getOrNull() ?: return
        state = built
        HttpsURLConnection.setDefaultSSLSocketFactory(built.socketFactory)
    }

    fun apply(builder: OkHttpClient.Builder): OkHttpClient.Builder {
        val current = state ?: return builder
        return builder.sslSocketFactory(current.socketFactory, current.trustManager)
    }

    /**
     * On Android 6–9 (API 23–28), WebView uses the system root store (missing ISRG Root X1,
     * GTS Roots, Certainly Root R1/E1, newer DigiCert/GlobalSign roots) and ignores OkHttp's
     * custom TrustManager. When WebView calls `onReceivedSslError`, this performs a full TLS
     * handshake to the target host using our bundled Mozilla root store (through the active VPN)
     * and only returns `true` if the chain is trusted, the hostname matches, and the peer leaf
     * certificate matches the certificate presented to WebView. Must be called off the main thread.
     */
    fun verifyWebViewSslError(sslError: SslError): Boolean {
        val current = state ?: return false
        val uri = runCatching { Uri.parse(sslError.url) }.getOrNull() ?: return false
        if (!uri.scheme.equals("https", ignoreCase = true)) return false
        val host = uri.host?.takeIf { it.isNotBlank() } ?: return false
        val port = uri.port.takeIf { it in 1..65535 } ?: 443
        val leafCert = extractX509(sslError.certificate) ?: return false
        runCatching { leafCert.checkValidity() }.onFailure { return false }

        val cacheKey = "$host:$port:${sha256Hex(leafCert.encoded)}"
        val now = System.currentTimeMillis()
        verifiedWebCerts[cacheKey]?.let { exp ->
            if (now < exp) return true
            verifiedWebCerts.remove(cacheKey)
        }

        return runCatching {
            Socket().use { raw ->
                raw.connect(InetSocketAddress(host, port), 8_000)
                raw.soTimeout = 8_000
                (current.socketFactory.createSocket(raw, host, port, true) as SSLSocket).use { ssl ->
                    ssl.soTimeout = 8_000
                    // Enable SNI explicitly for Android 6–8 wrapped sockets
                    val params = ssl.sslParameters
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        params.serverNames = listOf(javax.net.ssl.SNIHostName(host))
                    }
                    ssl.sslParameters = params
                    ssl.startHandshake()
                    val session = ssl.session
                    if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, session)) {
                        return@runCatching false
                    }
                    val ok = matchesLeafCertificate(leafCert, session.peerCertificates)
                    if (ok) verifiedWebCerts[cacheKey] = now + WEB_CERT_CACHE_TTL_MS
                    ok
                }
            }
        }.getOrDefault(false)
    }

    internal fun parseX509Certificate(der: ByteArray): X509Certificate? = runCatching {
        CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(der)) as? X509Certificate
    }.getOrNull()

    internal fun matchesLeafCertificate(presentedLeaf: X509Certificate, peerChain: Array<out Certificate>): Boolean {
        val peerLeaf = peerChain.firstOrNull() as? X509Certificate ?: return false
        if (presentedLeaf.encoded.contentEquals(peerLeaf.encoded)) return true
        return presentedLeaf.subjectX500Principal == peerLeaf.subjectX500Principal &&
            presentedLeaf.publicKey == peerLeaf.publicKey
    }

    private fun extractX509(cert: SslCertificate?): X509Certificate? {
        if (cert == null) return null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            cert.x509Certificate?.let { return it }
        }
        val der = SslCertificate.saveState(cert)?.getByteArray("x509-certificate") ?: return null
        return parseX509Certificate(der)
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun buildState(context: Context): State {
        val systemTmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
            init(null as KeyStore?)
        }
        val systemTm = systemTmf.trustManagers.filterIsInstance<X509TrustManager>().first()

        val combinedKs = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
        var idx = 0
        for (issuer in systemTm.acceptedIssuers) {
            runCatching { combinedKs.setCertificateEntry("sys-${idx++}", issuer) }
        }
        val cf = CertificateFactory.getInstance("X.509")
        val pem = context.resources.openRawResource(R.raw.ca_roots).bufferedReader(Charsets.UTF_8).use { it.readText() }
        val beginMarker = "-----BEGIN CERTIFICATE-----"
        val endMarker = "-----END CERTIFICATE-----"
        var pos = 0
        while (true) {
            val start = pem.indexOf(beginMarker, pos)
            if (start < 0) break
            val end = pem.indexOf(endMarker, start)
            if (end < 0) break
            val block = pem.substring(start, end + endMarker.length)
            pos = end + endMarker.length
            runCatching {
                val cert = cf.generateCertificate(ByteArrayInputStream(block.toByteArray(Charsets.US_ASCII))) as X509Certificate
                combinedKs.setCertificateEntry("ca-${idx++}", cert)
            }
        }
        val combinedTmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
            init(combinedKs)
        }
        val combinedTm = combinedTmf.trustManagers.filterIsInstance<X509TrustManager>().first()

        val composite = CompositeTrustManager(systemTm, combinedTm)
        val ssl = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(composite), null)
        }
        return State(Tls12SocketFactory(ssl.socketFactory), composite)
    }

    private class CompositeTrustManager(
        private val systemTm: X509TrustManager,
        private val combinedTm: X509TrustManager,
    ) : X509TrustManager {
        private val systemExt = runCatching { X509TrustManagerExtensions(systemTm) }.getOrNull()
        private val combinedExt = runCatching { X509TrustManagerExtensions(combinedTm) }.getOrNull()
        private val mergedIssuers: Array<X509Certificate> = run {
            val seen = HashSet<String>()
            val out = ArrayList<X509Certificate>()
            for (cert in systemTm.acceptedIssuers) {
                val key = cert.subjectX500Principal.name + "#" + cert.serialNumber.toString(16)
                if (seen.add(key)) out.add(cert)
            }
            for (cert in combinedTm.acceptedIssuers) {
                val key = cert.subjectX500Principal.name + "#" + cert.serialNumber.toString(16)
                if (seen.add(key)) out.add(cert)
            }
            out.toTypedArray()
        }

        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
            try {
                systemTm.checkClientTrusted(chain, authType)
            } catch (_: CertificateException) {
                combinedTm.checkClientTrusted(chain, authType)
            }
        }

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
            try {
                systemTm.checkServerTrusted(chain, authType)
            } catch (_: CertificateException) {
                combinedTm.checkServerTrusted(chain, authType)
            }
        }

        /**
         * Invoked reflectively by Android's [X509TrustManagerExtensions] and OkHttp's
         * `AndroidCertificateChainCleaner`. Must remain public with exact JVM signature.
         */
        @androidx.annotation.Keep
        @Throws(CertificateException::class)
        @Suppress("unused")
        fun checkServerTrusted(
            chain: Array<X509Certificate>,
            authType: String,
            host: String,
        ): List<X509Certificate> {
            return try {
                val ext = systemExt
                if (ext != null) ext.checkServerTrusted(chain, authType, host)
                else {
                    systemTm.checkServerTrusted(chain, authType)
                    chain.toList()
                }
            } catch (_: CertificateException) {
                val ext = combinedExt
                if (ext != null) ext.checkServerTrusted(chain, authType, host)
                else {
                    combinedTm.checkServerTrusted(chain, authType)
                    chain.toList()
                }
            }
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = mergedIssuers.copyOf()
    }

    private class Tls12SocketFactory(private val delegate: SSLSocketFactory) : SSLSocketFactory() {
        override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites
        override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites

        override fun createSocket(s: Socket, host: String, port: Int, autoClose: Boolean): Socket =
            patch(delegate.createSocket(s, host, port, autoClose))

        override fun createSocket(host: String, port: Int): Socket =
            patch(delegate.createSocket(host, port))

        override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
            patch(delegate.createSocket(host, port, localHost, localPort))

        override fun createSocket(host: InetAddress, port: Int): Socket =
            patch(delegate.createSocket(host, port))

        override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
            patch(delegate.createSocket(address, port, localAddress, localPort))

        private fun patch(socket: Socket): Socket {
            if (socket is SSLSocket) {
                val supported = socket.supportedProtocols
                val current = socket.enabledProtocols.toMutableList()
                if ("TLSv1.2" in supported && "TLSv1.2" !in current) {
                    current.add("TLSv1.2")
                    socket.enabledProtocols = current.toTypedArray()
                }
            }
            return socket
        }
    }
}
