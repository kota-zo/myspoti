package app.myspoti.spotify.gql

import app.myspoti.spotify.bridge.Bridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.Base64

/**
 * Keeps the operation table in step with Spotify's releases, from public files only:
 * the open.spotify.com page names its build and lists its JS bundles; each bundle
 * defines operations as `new X("name","query","hash")`. Runs only when the build changed.
 */
class GqlOpsUpdater(private val http: OkHttpClient, private val cache: File) {

    /** The cached table if any, else the bundled one. */
    fun load(): GqlOps = runCatching { parse(cache.readText()) }.getOrNull() ?: GqlOps.bundled()

    /** When the build was last looked at (a file beside the table). */
    private val checked = File(cache.path + ".checked")

    /**
     * Whether a look at the current build is due: not within [minGapMs] of the last one. Every cold
     * start fetched open.spotify.com before (and every Spotify build its ~6 MB of bundles), next to
     * the page loading the same; Spotify ships a build about a day.
     */
    fun due(now: Long = System.currentTimeMillis(), minGapMs: Long = CHECK_GAP_MS): Boolean {
        val last = runCatching { checked.readText().trim().toLong() }.getOrNull() ?: return true
        return now - last !in 0 until minGapMs
    }

    /**
     * Fetches the current build; returns a new table when it changed, else null. Without
     * [bundlesAllowed] (a metered network) a changed build is only noted: its bundles are read on
     * the next look on Wi-Fi, the table in use meanwhile (an unknown operation falls back to the
     * page, see BridgeGql).
     */
    suspend fun refresh(current: GqlOps, bundlesAllowed: Boolean = true, now: Long = System.currentTimeMillis()): GqlOps? = withContext(Dispatchers.IO) {
        val html = get("https://open.spotify.com/")
        val build = script(html, "appServerConfig")?.let { (it["buildVersion"] as? JsonPrimitive)?.content }
        val urls = LinkedHashSet<String>()
        BUNDLE.findAll(html).forEach { urls += it.value }
        script(html, "__CDN_FILE_URLS__")?.values?.forEach { v ->
            (v as? JsonPrimitive)?.content?.takeIf { it.endsWith(".js") && it.contains("/web-player/") }?.let(urls::add)
        }
        if (urls.isEmpty()) return@withContext null
        // Without a parseable build id, the bundle set is the cache key: otherwise
        // build == null never matches and every launch re-downloads every bundle.
        val bundleKey = java.security.MessageDigest.getInstance("SHA-256")
            .digest(urls.sorted().joinToString("\n").toByteArray())
            .joinToString("") { "%02x".format(it) }
        val cached = runCatching { Bridge.json.parseToJsonElement(cache.readText()).jsonObject }.getOrNull()
        val cachedBuild = cached?.get("buildVersion")?.jsonPrimitive?.content
        if ((build != null && build == cachedBuild) || (build == null && bundleKey == cached?.get("bundleKey")?.jsonPrimitive?.content)) {
            markChecked(now)
            return@withContext null
        }
        // A new build on mobile data: not marked checked, so the next look (on Wi-Fi) reads it.
        if (!bundlesAllowed) return@withContext null
        val found = HashMap<String, String>()
        val gate = Semaphore(6)
        coroutineScope {
            urls.map { url -> async { gate.withPermit { runCatching { get(url) }.getOrNull() } } }.awaitAll()
        }.filterNotNull().forEach { body -> OP.findAll(body).forEach { m -> found[m.groupValues[1]] = m.groupValues[3] } }
        // A partial download must not drop operations: keep the known ones it did not see.
        if (found.size < current.names.size / 2) return@withContext null
        val merged = current.names.associateWith { current.hash(it) } + found
        val json = buildJsonObject {
            build?.let { put("buildVersion", it) }
            put("bundleKey", bundleKey)
            putJsonObject("operations") { merged.forEach { (name, hash) -> putJsonObject(name) { put("hash", hash) } } }
        }
        cache.parentFile?.mkdirs()
        cache.writeText(json.toString())
        markChecked(now)
        GqlOps(merged)
    }

    private fun markChecked(now: Long) { runCatching { cache.parentFile?.mkdirs(); checked.writeText(now.toString()) } }

    private fun parse(text: String): GqlOps {
        val ops = Bridge.json.parseToJsonElement(text).jsonObject["operations"] as? JsonObject ?: throw GqlException("ops", "no operations in the table")
        return GqlOps(ops.mapValues { (name, op) ->
            (op.jsonObject["hash"] as? JsonPrimitive)?.content ?: throw GqlException(name, "no hash in the table")
        })
    }

    private fun get(url: String): String =
        http.newCall(Request.Builder().url(url).header("user-agent", SpotifyHttp.USER_AGENT).build()).execute().use {
            check(it.isSuccessful) { "HTTP ${it.code} for $url" }
            it.body.string()
        }

    /** `<script id="...">base64 JSON</script>` blocks of the page. */
    private fun script(html: String, id: String): JsonObject? {
        val m = Regex("<script id=\"$id\"[^>]*>([^<]+)</script>").find(html) ?: return null
        val raw = m.groupValues[1].trim()
        return runCatching { Bridge.json.parseToJsonElement(String(Base64.getDecoder().decode(raw))).jsonObject }
            .recoverCatching { Bridge.json.parseToJsonElement(raw).jsonObject }.getOrNull()
    }

    companion object {
        /** The least time between two looks at Spotify's current build. */
        const val CHECK_GAP_MS = 6 * 3_600_000L

        private val BUNDLE = Regex("https://open\\.spotifycdn\\.com/cdn/build/web-player/[^\"']+?\\.js")
        private val OP = Regex("new [\\w$]+(?:\\.[\\w$]+)?\\(\"(\\w+)\",\"(query|mutation|subscription)\",\"([0-9a-f]{64})\"")
    }
}
