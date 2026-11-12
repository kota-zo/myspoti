package app.myspoti.wear

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import app.myspoti.engine.AccessToken
import app.myspoti.engine.ClientToken
import app.myspoti.engine.EngineConfig
import app.myspoti.engine.TokenSource
import app.myspoti.pass.PassLink
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.util.Base64
import kotlin.coroutines.resume

/** A playlist (or Liked Songs) of the account, as the phone lists it for the watch. */
class WatchList(val uri: String, val name: String, val subtitle: String)

/**
 * The phone's app: asked over the Wearable Data Layer (the link between the user's own phone and
 * watch: Bluetooth, the Wi-Fi or Google's servers), else straight over the Wi-Fi ([PassLink], with
 * the secret the phone gave over the Data Layer; the phone found again by its NSD announce when
 * its address changed). The watch has no browser to sign in with: the session is the phone's,
 * kept sealed on the watch ([WatchStore]) so a restart of the app needs no phone while it lasts.
 */
class PhoneLink(private val context: Context) : TokenSource {
    private val store = WatchStore(context)

    private suspend fun ask(path: String, body: String = ""): String {
        val viaLayer = runCatching { askLayer(path, body) }
        viaLayer.getOrNull()?.let { return it }
        val viaLan = runCatching { askLan(path, body) }
        viaLan.getOrNull()?.let { return it }
        Log.d(TAG, "phone not reached for $path: ${viaLayer.exceptionOrNull()?.message}; Wi-Fi: ${viaLan.exceptionOrNull()?.message}")
        throw viaLayer.exceptionOrNull() ?: IllegalStateException("no phone")
    }

    private suspend fun askLayer(path: String, body: String): String {
        val nodes = Wearable.getNodeClient(context).connectedNodes.await()
        val node = nodes.firstOrNull { it.isNearby } ?: nodes.firstOrNull() ?: throw IllegalStateException("no phone connected")
        val answer = withTimeout(ASK_MS) { Wearable.getMessageClient(context).sendRequest(node.id, path, body.encodeToByteArray()).await() }
        return answer.decodeToString().also { if (it.startsWith("error:")) throw IllegalStateException(it.removePrefix("error:").trim()) }
    }

    /** The phone over the Wi-Fi: at its last address, else where its announce says it is now. */
    private suspend fun askLan(path: String, body: String): String = withContext(Dispatchers.IO) {
        val link = store.secret(LINK)?.let { Json.parseToJsonElement(it).jsonObject } ?: throw IllegalStateException("no Wi-Fi link yet")
        val secret = Base64.getUrlDecoder().decode(link.text("secret"))
        val port = link.text("port").toIntOrNull() ?: 0
        fun at(host: String, p: Int) = PassLink.ask(host, p, secret, path, body).also { if (it.startsWith("error:")) throw IllegalStateException(it.removePrefix("error:").trim()) }
        val last = link.text("host")
        runCatching { if (last.isNotBlank() && port > 0) return@withContext at(last, port) }
        val found = find(link.text("id")) ?: throw IllegalStateException("the phone is not on this network")
        store.putSecret(LINK, JsonObject(link + mapOf("host" to kotlinx.serialization.json.JsonPrimitive(found.first), "port" to kotlinx.serialization.json.JsonPrimitive(found.second.toString()))).toString())
        at(found.first, found.second)
    }

    /** The phone's announce on this network (`Orpheon-<id>`): its address and port, or null within a few seconds. */
    private suspend fun find(id: String): Pair<String, Int>? {
        if (id.isBlank()) return null
        val nsd = context.getSystemService(NsdManager::class.java) ?: return null
        return withTimeoutOrNull(FIND_MS) {
            suspendCancellableCoroutine { cont ->
                val listener = object : NsdManager.DiscoveryListener {
                    override fun onServiceFound(info: NsdServiceInfo) {
                        if (info.serviceName != "Orpheon-$id") return
                        @Suppress("DEPRECATION")
                        nsd.resolveService(info, object : NsdManager.ResolveListener {
                            override fun onServiceResolved(r: NsdServiceInfo) {
                                @Suppress("DEPRECATION") val host = r.host?.hostAddress
                                if (host != null && cont.isActive) cont.resume(host to r.port)
                            }
                            override fun onResolveFailed(r: NsdServiceInfo, code: Int) {}
                        })
                    }
                    override fun onDiscoveryStarted(type: String) {}
                    override fun onDiscoveryStopped(type: String) {}
                    override fun onServiceLost(info: NsdServiceInfo) {}
                    override fun onStartDiscoveryFailed(type: String, code: Int) { if (cont.isActive) cont.resume(null) }
                    override fun onStopDiscoveryFailed(type: String, code: Int) {}
                }
                nsd.discoverServices("_orpheon._tcp", NsdManager.PROTOCOL_DNS_SD, listener)
                cont.invokeOnCancellation { runCatching { nsd.stopServiceDiscovery(listener) } }
            }
        }
    }

    /** The session, asked again once its web token is about to end or after a refusal ([fresh]). */
    private suspend fun session(fresh: Boolean = false): JsonObject = lock.withLock {
        val now = System.currentTimeMillis()
        if (!fresh) {
            cached?.takeIf { now < it.expires() - EARLY_MS }?.let { return it }
            store.secret(SESSION_FILE)?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() }
                ?.takeIf { now < it.expires() - EARLY_MS }?.let { cached = it; return it }
        }
        val read = Json.parseToJsonElement(ask(SESSION, if (fresh) "fresh" else "")).jsonObject
        // The Wi-Fi link's secret and where the phone is, kept apart from the session.
        if (read.text("lanSecret").isNotBlank()) store.putSecret(LINK, JsonObject(mapOf(
            "secret" to read["lanSecret"]!!, "port" to kotlinx.serialization.json.JsonPrimitive(read.text("lanPort")),
            "id" to (read["lanId"] ?: kotlinx.serialization.json.JsonPrimitive("")), "host" to (read["lanHost"] ?: kotlinx.serialization.json.JsonPrimitive("")),
        )).toString())
        val session = JsonObject(read.filterKeys { !it.startsWith("lan") })
        store.putSecret(SESSION_FILE, session.toString())
        cached = session
        session
    }

    /** The session read (from the phone, or kept here): it also says whether the listen mirror is on. */
    suspend fun refreshSession() { session() }

    private fun JsonObject.expires() = this["accessExpiresAtMs"]?.jsonPrimitive?.long ?: 0L
    private fun JsonObject.text(key: String) = this[key]?.jsonPrimitive?.content.orEmpty()

    override suspend fun accessToken(forceRefresh: Boolean): AccessToken =
        session(forceRefresh).let { AccessToken(it.text("accessToken"), it.expires(), it.text("clientId")) }

    override suspend fun clientToken(): ClientToken = session().let { ClientToken(it.text("clientToken"), it.text("clientDeviceId")) }

    /** The identity the phone's install already shows the service: the watch's engine presents the same. */
    suspend fun config(): EngineConfig = session().let { s ->
        EngineConfig(
            appVersion = s.text("appVersion"), browserName = "Chrome", browserVersion = s.text("browserVersion"), os = "linux",
            userAgent = s.text("userAgent"), acceptLanguage = s.text("acceptLanguage"),
            clientHints = linkedMapOf("sec-ch-ua" to s.text("secChUa"), "sec-ch-ua-mobile" to "?0", "sec-ch-ua-platform" to "\"Linux\""),
        )
    }

    /** The playlists, from the phone; the last ones read when it is not there. */
    suspend fun library(): List<WatchList> {
        val text = runCatching { ask(LIBRARY).also { store.put("library", it) } }.getOrElse { e -> store.get("library") ?: throw e }
        return Json.parseToJsonElement(text).jsonArray.map { e -> e.jsonObject.let { WatchList(it.text("uri"), it.text("name"), it.text("subtitle")) } }
    }

    /** The songs of the list [uri], from the phone; the last ones read when it is not there. */
    suspend fun tracks(uri: String): List<WatchTrack> {
        val text = runCatching { ask(TRACKS, uri).also { store.put("tracks-$uri", it) } }.getOrElse { e -> store.get("tracks-$uri") ?: throw e }
        return Json.parseToJsonElement(text).jsonArray.map { e ->
            val o = e.jsonObject
            WatchTrack(o.text("uri"), o.text("name"), o["artists"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty(), o.text("album"),
                o["durationMs"]?.jsonPrimitive?.long ?: 0L, o.text("cover").ifBlank { null })
        }
    }

    /**
     * What the watch plays from its extensions, told to the phone ([body]: the song and its state, or
     * `{"stopped":true}`): with "Count extension plays" on there, the phone's account plays it unheard.
     */
    suspend fun listen(body: String) { ask(LISTEN, body) }

    /** The phone's extensions (packages and state, as the pass carries them): through a Data Layer channel, else the Wi-Fi. */
    suspend fun extensions(): String = runCatching {
        val nodes = Wearable.getNodeClient(context).connectedNodes.await()
        val node = nodes.firstOrNull { it.isNearby } ?: nodes.first()
        val client = Wearable.getChannelClient(context)
        val channel = client.openChannel(node.id, EXTENSIONS).await()
        try {
            withTimeout(EXTENSIONS_MS) { withContext(Dispatchers.IO) { client.getInputStream(channel).await().use { it.readBytes().decodeToString() } } }
        } finally { runCatching { client.close(channel).await() } }
    }.getOrElse { askLan(EXTENSIONS, "") }.also { if (it.startsWith("error:")) throw IllegalStateException(it.removePrefix("error:").trim()) }

    companion object {
        const val SESSION = "/orpheon/session"
        const val LIBRARY = "/orpheon/library"
        const val TRACKS = "/orpheon/tracks"
        const val EXTENSIONS = "/orpheon/extensions"
        const val LISTEN = "/orpheon/listen"
        private const val TAG = "Orpheon"
        private const val SESSION_FILE = "session"
        private const val LINK = "link"
        private const val ASK_MS = 20_000L
        private const val FIND_MS = 5_000L
        private const val EXTENSIONS_MS = 60_000L
        private const val EARLY_MS = 60_000L
        // One session for the whole app: the engine and the screens share it.
        private val lock = Mutex()
        @Volatile private var cached: JsonObject? = null
        /**
         * The phone's "Count extension plays on your account" (Spotify chosen), as the last session
         * read said: the watch's own listen mirror ([WatchSync]) runs only with it.
         */
        val listenSync: Boolean get() = cached?.get("listenSync")?.jsonPrimitive?.content == "true"
    }
}

/** A song of a list, as the phone gives it for the watch's extensions. */
class WatchTrack(val uri: String, val name: String, val artists: List<String>, val album: String, val durationMs: Long, val cover: String?)
