/* SPDX-License-Identifier: GPL-3.0-only */
package app.myspoti.catalog

import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.util.Base64
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * A set served from the computer, as UPDATES.md says to try one before publishing it (`python3 -m http.server` and
 * `adb reverse`): fetched over plain HTTP from the loopback address with the debug override, refused without it, and
 * verified the same. With CATALOG_PUBLISHED_DIR, the folder prepared for publishing, against the app's own key.
 */
class LocalServeTest {
    private fun serve(dir: File): HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { ex ->
            val f = File(dir, ex.requestURI.path.removePrefix("/")).canonicalFile
            val ok = f.isFile && f.path.startsWith(dir.canonicalPath)
            val body = if (ok) f.readBytes() else ByteArray(0)
            ex.sendResponseHeaders(if (ok) 200 else 404, if (body.isEmpty()) -1 else body.size.toLong())
            if (body.isNotEmpty()) ex.responseBody.use { it.write(body) } else ex.close()
        }
        start()
    }

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    /** What tools/sign_scripts.py writes, signed with a key made for the test. */
    private fun signedFolder(version: Int): Pair<File, java.security.PublicKey> {
        val keys = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val dir = Files.createTempDirectory("published").toFile().also { it.deleteOnExit() }
        val script = bundledScript("deezer").toByteArray()
        File(dir, "scripts").mkdirs()
        File(dir, "scripts/deezer.js").writeBytes(script)
        val manifest = """{"manifest": 1, "version": $version, "minApi": 1, "scripts": [{"name": "deezer.js", "sha256": "${sha(script)}", "size": ${script.size}}]}""" + "\n"
        File(dir, "manifest.json").writeText(manifest)
        val sig = Signature.getInstance("SHA256withECDSA").run { initSign(keys.private); update(manifest.toByteArray()); sign() }
        File(dir, "manifest.json.sig").writeText(Base64.getEncoder().encodeToString(sig) + "\n")
        return dir to keys.public
    }

    private fun updateFrom(dir: File, key: java.security.PublicKey, allowLocalhost: Boolean): Pair<UpdateResult, ScriptStore> {
        val server = serve(dir)
        try {
            val verifier = ScriptVerifier(key)
            val store = ScriptStore(Files.createTempDirectory("cache").toFile().also { it.deleteOnExit() }, verifier)
            val fetch = HttpFetch(OkHttpClient(), "Test/1", 0)
            val url = "http://localhost:${server.address.port}/"
            return runBlocking { ScriptUpdater(fetch, store, verifier, url, allowLocalhost).update() } to store
        } finally {
            server.stop(0)
        }
    }

    @Test fun aSetServedFromTheComputerIsTakenInDebugOnly() {
        val (dir, key) = signedFolder(2)
        val (refused, _) = updateFrom(dir, key, allowLocalhost = false)
        assertEquals(UpdateResult.Rejected("not HTTPS"), refused)
        val (taken, store) = updateFrom(dir, key, allowLocalhost = true)
        assertEquals(UpdateResult.Updated(2), taken)
        assertEquals(2, store.load()?.version)
        assertNotNull(store.load()?.get("deezer"))
    }

    @Test fun aSetSignedWithAnotherKeyIsRefusedFromTheComputerToo() {
        val (dir, _) = signedFolder(2)
        val other = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair().public
        val (r, store) = updateFrom(dir, other, allowLocalhost = true)
        assertTrue(r.toString(), r is UpdateResult.Rejected)
        assertEquals(null, store.load())
    }

    @Test fun theFolderPreparedForPublishingVerifiesWithTheAppsKey() {
        val path = System.getenv("CATALOG_PUBLISHED_DIR")
        assumeTrue(!path.isNullOrBlank())
        val (r, store) = updateFrom(File(path!!), scriptsPublicKey(), allowLocalhost = true)
        assertTrue(r.toString(), r is UpdateResult.Updated)
        val set = store.load()!!
        println("PUBLISHED version=${set.version} scripts=${set.ids.sorted()}")
    }
}
