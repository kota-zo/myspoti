package app.myspoti.host.android

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

class TlsCompatTest {
    @Test
    fun bundledCaRootsParseCleanlyAndIncludeModernRoots() {
        val pemFile = listOf(
            File("src/main/res/raw/ca_roots.pem"),
            File("host-android/src/main/res/raw/ca_roots.pem"),
        ).first { it.isFile }
        val pem = pemFile.readText(Charsets.UTF_8)
        val cf = CertificateFactory.getInstance("X.509")
        val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
        val beginMarker = "-----BEGIN CERTIFICATE-----"
        val endMarker = "-----END CERTIFICATE-----"
        var pos = 0
        var count = 0
        val certs = ArrayList<X509Certificate>()
        val subjects = ArrayList<String>()
        while (true) {
            val start = pem.indexOf(beginMarker, pos)
            if (start < 0) break
            val end = pem.indexOf(endMarker, start)
            if (end < 0) break
            val block = pem.substring(start, end + endMarker.length)
            pos = end + endMarker.length
            val cert = cf.generateCertificate(ByteArrayInputStream(block.toByteArray(Charsets.US_ASCII))) as X509Certificate
            ks.setCertificateEntry("ca-${count++}", cert)
            certs += cert
            subjects += cert.subjectX500Principal.name
        }
        assertTrue("expected modern root CAs, found $count", count >= 100)
        listOf(
            "ISRG Root X1",
            "ISRG Root X2",
            "GTS Root R1",
            "Certainly Root R1",
            "Certainly Root E1",
            "Starfield Root Certificate Authority - G2",
            "GlobalSign Root CA - R3",
            "DigiCert Global Root G2",
        ).forEach { expected ->
            assertTrue("missing bundled root $expected", subjects.any { it.contains(expected) })
        }
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(ks) }
        val tm = tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
        assertEquals(count, tm.acceptedIssuers.size)

        val leafA = TlsCompat.parseX509Certificate(certs[0].encoded)
        assertNotNull(leafA)
        assertTrue(TlsCompat.matchesLeafCertificate(leafA!!, arrayOf(certs[0])))
        assertFalse(TlsCompat.matchesLeafCertificate(leafA, arrayOf(certs[1])))
        assertFalse(TlsCompat.matchesLeafCertificate(leafA, emptyArray()))
    }

    @Test
    fun compositeTrustManagerExposes3ArgExtensionContract() {
        val cls = Class.forName("app.myspoti.host.android.TlsCompat\$CompositeTrustManager")
        val m = cls.getMethod(
            "checkServerTrusted",
            Array<X509Certificate>::class.java,
            String::class.java,
            String::class.java,
        )
        assertEquals(List::class.java, m.returnType)
        assertTrue(javax.net.ssl.X509TrustManager::class.java.isAssignableFrom(cls))
    }

    @Test
    fun applyIsNoOpWhenNotInstalledOnJvm() {
        val builder = OkHttpClient.Builder()
        val same = TlsCompat.apply(builder)
        assertTrue(same === builder)
    }
}
