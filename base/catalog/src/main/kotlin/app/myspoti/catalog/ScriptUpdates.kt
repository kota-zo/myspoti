/* SPDX-License-Identifier: GPL-3.0-only */
package app.myspoti.catalog

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

// Metadata scripts that can be mended without a new version of the app. A set is a signed manifest and the scripts
// it lists. Nothing is run unless the signature and every hash hold, and what a script may reach (its hosts, its time)
// never comes from here: that stays in the app, in ScriptSpec and ScriptEngine.

/** The version of the contract between the app and the scripts (the functions and fields they answer). A set that needs more is ignored. */
const val CATALOG_API_VERSION = 1

/** The version of the scripts bundled in the resources. A downloaded set has to be newer. */
const val BUNDLED_SCRIPTS_VERSION = 1

/** Where the sets are published. The repository name is not final: change it here only. */
const val SCRIPTS_BASE_URL = "https://raw.githubusercontent.com/kota-zo/myspoti-metadata/main/"

/** ECDSA P-256 (X.509 SubjectPublicKeyInfo, base64). The private half is kept outside the repository. */
const val SCRIPTS_PUBLIC_KEY = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE7K1uWFA/4IEU1oT31UJQ6HHEj0IlD4xgyVmzojN4Z1xp8VfCo5M+W4wKXl1hpR0pGGFOMI0WskxnVkNjobqLbA=="

const val MAX_MANIFEST_BYTES = 64 * 1024
const val MAX_SCRIPT_BYTES = 256 * 1024
const val MAX_SCRIPTS = 16

private val SCRIPT_NAME = Regex("[a-z0-9_]{1,40}\\.js")

/** A set that cannot be trusted. */
class BadScripts(message: String) : Exception(message)

/** Verified scripts, by id (`deezer` for `deezer.js`). */
class ScriptSet(val version: Int, private val sources: Map<String, String>) {
    operator fun get(id: String): String? = sources[id]
    val ids: Set<String> get() = sources.keys
}

fun scriptsPublicKey(base64: String = SCRIPTS_PUBLIC_KEY): PublicKey =
    KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(base64)))

private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

/** Checks a signed set. The same check runs after a download and at every load from the cache. */
class ScriptVerifier(private val key: PublicKey, private val apiVersion: Int = CATALOG_API_VERSION) {
    /** The manifest, once its signature holds: version and the files it asks for. Throws [BadScripts]. */
    class Manifest(val version: Int, val files: List<Entry>)
    class Entry(val name: String, val sha256: String, val size: Long)

    fun manifest(manifest: ByteArray, signatureBase64: String): Manifest {
        if (manifest.size > MAX_MANIFEST_BYTES) throw BadScripts("manifest too large")
        val ok = try {
            val sig = Base64.getMimeDecoder().decode(signatureBase64.trim())
            Signature.getInstance("SHA256withECDSA").run {
                initVerify(key)
                update(manifest)
                verify(sig)
            }
        } catch (e: Exception) {
            false
        }
        if (!ok) throw BadScripts("bad signature")
        try {
            val o = Json.parseToJsonElement(String(manifest, StandardCharsets.UTF_8)).jsonObject
            if (o["manifest"]?.jsonPrimitive?.int != 1) throw BadScripts("unknown manifest format")
            val version = o["version"]!!.jsonPrimitive.int
            if (version < 1) throw BadScripts("bad version")
            if (o["minApi"]!!.jsonPrimitive.int > apiVersion) throw BadScripts("needs a newer app")
            val list = o["scripts"]!!.jsonArray
            if (list.size > MAX_SCRIPTS) throw BadScripts("too many scripts")
            val entries = list.map {
                val e = it.jsonObject
                val entry = Entry(e["name"]!!.jsonPrimitive.content, e["sha256"]!!.jsonPrimitive.content.lowercase(), e["size"]!!.jsonPrimitive.long)
                if (!SCRIPT_NAME.matches(entry.name)) throw BadScripts("bad file name")
                if (entry.size !in 1..MAX_SCRIPT_BYTES) throw BadScripts("${entry.name}: bad size")
                entry
            }
            if (entries.map { it.name }.toSet().size != entries.size) throw BadScripts("duplicate file")
            return Manifest(version, entries)
        } catch (e: BadScripts) {
            throw e
        } catch (e: Exception) {
            throw BadScripts("unreadable manifest")
        }
    }

    /** The set, with each file checked against the manifest. */
    fun set(m: Manifest, files: Map<String, ByteArray>): ScriptSet {
        val sources = HashMap<String, String>()
        for (e in m.files) {
            val bytes = files[e.name] ?: throw BadScripts("${e.name}: missing")
            if (bytes.size.toLong() != e.size) throw BadScripts("${e.name}: size differs")
            if (sha256(bytes) != e.sha256) throw BadScripts("${e.name}: hash differs")
            sources[e.name.removeSuffix(".js")] = String(bytes, StandardCharsets.UTF_8)
        }
        return ScriptSet(m.version, sources)
    }
}

/** The verified set kept in [dir]. Written whole, then switched on by one atomic rename. */
class ScriptStore(
    private val dir: File,
    private val verifier: ScriptVerifier,
    private val bundledVersion: Int = BUNDLED_SCRIPTS_VERSION,
) {
    private val pointer get() = File(dir, "current")

    /** The cached set when it still verifies and is newer than the bundled one, else null: the bundled scripts then apply. */
    fun load(): ScriptSet? = try {
        val name = pointer.readText().trim()
        if (!Regex("set-\\d{1,9}").matches(name)) null else {
            val d = File(dir, name)
            val manifest = File(d, "manifest.json").readBytes()
            val m = verifier.manifest(manifest, File(d, "manifest.sig").readText())
            val set = verifier.set(m, m.files.associate { it.name to File(d, it.name).readBytes() })
            set.takeIf { it.version > bundledVersion && name == "set-${it.version}" }
        }
    } catch (e: Exception) {
        null
    }

    fun version(): Int = maxOf(bundledVersion, load()?.version ?: 0)

    /** Keeps a set already verified by the caller. */
    fun install(manifest: ByteArray, signature: String, m: ScriptVerifier.Manifest, files: Map<String, ByteArray>) {
        dir.mkdirs()
        val name = "set-${m.version}"
        val tmp = File(dir, "$name.tmp")
        tmp.deleteRecursively()
        tmp.mkdirs()
        File(tmp, "manifest.json").writeBytes(manifest)
        File(tmp, "manifest.sig").writeText(signature)
        for (e in m.files) File(tmp, e.name).writeBytes(files.getValue(e.name))
        val final = File(dir, name)
        final.deleteRecursively()
        Files.move(tmp.toPath(), final.toPath(), StandardCopyOption.ATOMIC_MOVE)
        val ptmp = File(dir, "current.tmp")
        ptmp.writeText(name)
        Files.move(ptmp.toPath(), pointer.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        dir.listFiles()?.filter { it.name.startsWith("set-") && it.name != name }?.forEach { it.deleteRecursively() }
    }
}

sealed interface UpdateResult {
    data class Updated(val version: Int) : UpdateResult
    data class UpToDate(val version: Int) : UpdateResult
    /** The published set cannot be trusted or is older: nothing changed. */
    data class Rejected(val reason: String) : UpdateResult
    /** The network did not answer: nothing changed. */
    data class Unreachable(val reason: String) : UpdateResult
}

/**
 * Whether [url] may serve the scripts: HTTPS, or, with [allowLocalhost] (debug builds, to try a set served from the
 * computer through `adb reverse`), plain HTTP to this device's own loopback address only. The signature is checked
 * the same either way.
 */
fun scriptsUrlAllowed(url: String, allowLocalhost: Boolean = false): Boolean {
    val u = runCatching { java.net.URI(url) }.getOrNull() ?: return false
    if (u.userInfo != null || u.host.isNullOrEmpty()) return false
    return when (u.scheme) {
        "https" -> true
        "http" -> allowLocalhost && u.host.lowercase() in setOf("localhost", "127.0.0.1", "[::1]", "::1")
        else -> false
    }
}

/** Fetches the manifest and its signature, then the scripts, and keeps them only if everything holds. */
class ScriptUpdater(
    private val fetch: Fetch,
    private val store: ScriptStore,
    private val verifier: ScriptVerifier,
    private val baseUrl: String = SCRIPTS_BASE_URL,
    private val allowLocalhost: Boolean = false,
) {
    suspend fun update(): UpdateResult {
        val base = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        if (!scriptsUrlAllowed(base, allowLocalhost)) return UpdateResult.Rejected(if (base.startsWith("http://")) "not HTTPS" else "not a scripts address")
        try {
            val have = store.version()
            val mr = fetch.get(base + "manifest.json")
            val sr = fetch.get(base + "manifest.json.sig")
            if (mr.status != 200 || sr.status != 200) return UpdateResult.Unreachable("HTTP ${mr.status}/${sr.status}")
            val manifest = mr.body.toByteArray(StandardCharsets.UTF_8)
            val m = verifier.manifest(manifest, sr.body)
            if (m.version == have) return UpdateResult.UpToDate(have)
            if (m.version < have) return UpdateResult.Rejected("older than version $have")
            val files = HashMap<String, ByteArray>()
            for (e in m.files) {
                val r = fetch.get(base + "scripts/" + e.name)
                if (r.status != 200) return UpdateResult.Unreachable("${e.name}: HTTP ${r.status}")
                files[e.name] = r.body.toByteArray(StandardCharsets.UTF_8)
            }
            verifier.set(m, files)
            store.install(manifest, sr.body, m, files)
            return UpdateResult.Updated(m.version)
        } catch (e: BadScripts) {
            return UpdateResult.Rejected(e.message ?: "rejected")
        } catch (e: CancellationException) {
            throw e
        } catch (e: ProviderDown) {
            return UpdateResult.Unreachable(e.message ?: "down")
        } catch (e: java.io.IOException) {
            return UpdateResult.Unreachable(e.message ?: "network error")
        } catch (e: IllegalArgumentException) {
            return UpdateResult.Rejected(e.message ?: "bad address")
        }
    }
}

/**
 * When the scripts are looked for again: a day after the last answer, sooner after a failure to reach them (an hour,
 * then twice as long each time, a day at most). A clock moved back makes it due.
 */
object ScriptsSchedule {
    const val DAY_MS = 24 * 3_600_000L
    const val FIRST_RETRY_MS = 3_600_000L

    fun due(now: Long, lastAt: Long, failures: Int): Boolean {
        if (lastAt <= 0 || now < lastAt) return true
        val wait = if (failures <= 0) DAY_MS else minOf(DAY_MS, FIRST_RETRY_MS shl (failures - 1).coerceAtMost(5))
        return now - lastAt >= wait
    }

    /** The failures counted after [result]: an answer (even a refused set) starts over, no answer counts one more. */
    fun failuresAfter(result: UpdateResult, failures: Int): Int = if (result is UpdateResult.Unreachable) failures + 1 else 0
}
