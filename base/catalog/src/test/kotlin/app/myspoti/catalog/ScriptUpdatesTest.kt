/* SPDX-License-Identifier: GPL-3.0-only */
package app.myspoti.catalog

import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.util.Base64
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScriptUpdatesTest {
    private fun keys(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
    private val signer = keys()
    private val verifier = ScriptVerifier(signer.public)
    private val base = "https://scripts.example/"

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
    private fun sign(key: KeyPair, bytes: ByteArray): String = Base64.getEncoder().encodeToString(
        Signature.getInstance("SHA256withECDSA").run { initSign(key.private); update(bytes); sign() },
    )

    private class Published(val answers: MutableMap<String, Response>) : Fetch {
        val asked = ArrayList<String>()
        override suspend fun get(url: String): Response {
            asked += url
            return answers[url] ?: throw IOException("no route")
        }
    }

    private val goodScript = "registerExtension({initialize:function(){return true;}});"

    /** A published set: manifest, signature and scripts, each of which a test may then spoil. */
    private fun publish(
        version: Int,
        scripts: Map<String, String> = mapOf("deezer.js" to goodScript),
        minApi: Int = 1,
        key: KeyPair = signer,
        sizeOf: (String, ByteArray) -> Long = { _, b -> b.size.toLong() },
        served: (String, String) -> String = { _, s -> s },
    ): Published {
        val entries = scripts.entries.joinToString(",") { (n, s) ->
            val b = s.toByteArray(StandardCharsets.UTF_8)
            """{"name":"$n","sha256":"${sha(b)}","size":${sizeOf(n, b)}}"""
        }
        val manifest = """{"manifest":1,"version":$version,"minApi":$minApi,"scripts":[$entries]}"""
        val answers = mutableMapOf(
            base + "manifest.json" to Response(200, manifest),
            base + "manifest.json.sig" to Response(200, sign(key, manifest.toByteArray(StandardCharsets.UTF_8))),
        )
        for ((n, s) in scripts) answers[base + "scripts/" + n] = Response(200, served(n, s))
        return Published(answers)
    }

    private fun dir(): File = Files.createTempDirectory("scripts").toFile().also { it.deleteOnExit() }
    private fun store(d: File, bundled: Int = 1) = ScriptStore(d, verifier, bundled)
    private fun update(p: Fetch, s: ScriptStore, url: String = base) = runBlocking { ScriptUpdater(p, s, verifier, url).update() }

    @Test fun aValidSetIsKeptAndLoaded() {
        val s = store(dir())
        assertEquals(UpdateResult.Updated(2), update(publish(2), s))
        val set = s.load()!!
        assertEquals(2, set.version)
        assertEquals(goodScript, set["deezer"])
        assertEquals(UpdateResult.UpToDate(2), update(publish(2), s))
    }

    @Test fun aBadSignatureIsRejected() {
        val s = store(dir())
        val r = update(publish(2, key = keys()), s)
        assertTrue(r.toString(), r is UpdateResult.Rejected)
        assertNull(s.load())
    }

    @Test fun aSpoiledScriptIsRejected() {
        val s = store(dir())
        val r = update(publish(2, served = { _, body -> body.replace("true", "false") }), s)
        assertTrue(r.toString(), r is UpdateResult.Rejected)
        assertNull(s.load())
    }

    @Test fun aManifestChangedAfterSigningIsRejected() {
        val p = publish(2)
        val m = p.answers.getValue(base + "manifest.json")
        p.answers[base + "manifest.json"] = Response(200, m.body.replace("\"version\":2", "\"version\":9"))
        assertTrue(update(p, store(dir())) is UpdateResult.Rejected)
    }

    @Test fun aRollbackIsRejected() {
        val d = dir()
        val s = store(d)
        update(publish(5), s)
        val older = update(publish(3), s)
        assertTrue(older.toString(), older is UpdateResult.Rejected)
        assertEquals(5, s.load()!!.version)
        // Nor can a set be no newer than the bundled scripts.
        assertTrue(update(publish(1), store(dir(), bundled = 1)) is UpdateResult.UpToDate)
        assertTrue(update(publish(2), store(dir(), bundled = 4)) is UpdateResult.Rejected)
    }

    @Test fun anOversizeScriptIsRejected() {
        val big = "//" + "x".repeat(MAX_SCRIPT_BYTES)
        val announced = update(publish(2, mapOf("deezer.js" to big)), store(dir()))
        assertTrue(announced.toString(), announced is UpdateResult.Rejected)
        // A script longer than the signed size.
        val longer = update(publish(2, sizeOf = { _, b -> b.size - 5L }), store(dir()))
        assertTrue(longer.toString(), longer is UpdateResult.Rejected)
    }

    @Test fun otherRefusals() {
        assertTrue(update(publish(2, minApi = CATALOG_API_VERSION + 1), store(dir())) is UpdateResult.Rejected)
        assertTrue(update(publish(2, mapOf("../evil.js" to goodScript)), store(dir())) is UpdateResult.Rejected)
        assertTrue(update(publish(2), store(dir()), "http://scripts.example/") is UpdateResult.Rejected)
        assertTrue(update(Published(mutableMapOf()), store(dir())) is UpdateResult.Unreachable)
    }

    @Test fun aCacheThatFailsVerificationIsIgnored() {
        val d = dir()
        val s = store(d)
        update(publish(2), s)
        assertNotNull(s.load())
        File(d, "set-2/deezer.js").writeText("registerExtension({});")
        assertNull(store(d).load())
        // Nor does a valid set of another key count.
        val d2 = dir()
        update(publish(2), store(d2))
        assertNull(ScriptStore(d2, ScriptVerifier(keys().public), 1).load())
        assertNull(store(dir()).load())
    }

    @Test fun theBundledScriptsApplyWithoutADownloadedSet() = runBlocking {
        val bundled = ScriptProvider(ScriptSpec.DEEZER, ScriptHttp { _, _, _, _ -> Response(404, "{}") }, "Test/1")
        assertTrue(runCatching { bundled.searchTracks("x") }.exceptionOrNull() !is IllegalStateException)
        // providerChain takes no set and the bundled source stays.
        val chain = providerChain(okhttp3.OkHttpClient(), "Test/1", "CH", scriptSet = null)
        assertTrue(chain.toString().isNotEmpty())
    }

    private class Web : ScriptHttp {
        val asked = ArrayList<String>()
        override fun request(method: String, url: String, headers: Map<String, String>, body: String?): Response {
            asked += url
            return Response(200, "ok")
        }
    }

    @Test fun aDownloadedScriptCannotWidenTheSandbox() = runBlocking {
        // The manifest and the script both claim more hosts; only the app's spec counts.
        val source = """
            var hosts = ["evil.example", "*"];
            registerExtension({initialize:function(s){return true;},
              getTrack:function(id){ return {id:"1", name: http.get("https://evil.example/x").error || "reached"}; },
              customSearch:function(){ return http.get("https://other.example/").error ? [] : [{}]; }});"""
        val p = publish(2, mapOf("deezer.js" to source))
        val s = store(dir())
        assertEquals(UpdateResult.Updated(2), update(p, s))
        val web = Web()
        val spec = ScriptSpec("deezer", setOf("api.deezer.com"), "track", "album", "artist")
        val provider = ScriptProvider(spec, web, "Test/1", s.load()!!["deezer"]!!)
        assertEquals("host not allowed", provider.track("1").track.name)
        assertTrue(web.asked.isEmpty())
    }
}
