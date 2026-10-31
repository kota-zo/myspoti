package app.myspoti.spotify

import app.myspoti.api.MyspotiApi
import app.myspoti.spotify.bridge.Bridge
import app.myspoti.spotify.bridge.BridgeHost
import app.myspoti.spotify.bridge.service
import app.myspoti.spotify.gql.BridgeGql
import app.myspoti.spotify.gql.Gql
import app.myspoti.spotify.gql.GqlOps
import app.myspoti.spotify.gql.HttpGql
import app.myspoti.spotify.gql.OpsSource
import app.myspoti.spotify.gql.SpotifyHttp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient

/**
 * [MyspotiApi] backed by the Spotify Web Player.
 *
 * Reads go straight to Spotify over HTTP (fast, parallel); playback, library writes, Connect,
 * Jam and the web token go through the page. The host must call [onPageLoaded] after every
 * page load.
 *
 * [raw] reaches every Web Player capability, typed or not.
 */
class SpotifyApi(
    host: BridgeHost,
    private val scope: CoroutineScope,
    http: OkHttpClient = OkHttpClient(),
    language: String = "en",
    val ops: GqlOps = GqlOps.bundled(),
    /** The client's device id, kept across launches by the host (see SpotifyHttp.deviceId). */
    deviceId: String? = null,
    /** The browser this install presents to Spotify (see [SpotifyHttp.browserUserAgent]); null keeps the constant. */
    userAgent: String? = null,
) : MyspotiApi {
    val bridge = Bridge(host, scope)
    val http = SpotifyHttp(http, bridge, language, userAgent, deviceId ?: java.util.UUID.randomUUID().toString())
    @Volatile private var currentOps: GqlOps = ops
    /** The operation table in use; [updateOps] swaps it when Spotify ships a new build. */
    val opsInUse: GqlOps get() = currentOps
    private val liveOps = object : OpsSource { override val ops: GqlOps get() = currentOps }
    val httpGql = HttpGql(this.http, liveOps, fallback = BridgeGql(bridge, liveOps))
    private val gql: Gql = httpGql

    fun updateOps(newOps: GqlOps) { currentOps = newOps }

    override val ready: StateFlow<Boolean> = bridge.ready
    override val player = SpotifyPlayer(bridge, scope)
    override val search = SpotifySearch(gql)
    override val catalog = SpotifyCatalog(gql, this.http)
    override val library = SpotifyLibrary(bridge, gql)
    override val playlists = SpotifyPlaylists(gql, bridge, this.http)
    override val home = SpotifyHome(gql)
    override val podcasts = SpotifyPodcasts(gql, bridge, this.http)
    override val users = SpotifyUsers(gql, bridge, this.http)
    override val extras = SpotifyTrackExtras(gql, bridge, this.http)
    override val devices = SpotifyDevices(bridge)
    override val jam = SpotifyJam(bridge)
    override val blends = SpotifyBlends(this.http)
    override val recents = SpotifyRecents(gql)
    override val messages = SpotifyMessages(gql, this.http, users)

    /** Escape hatch to the 179 GraphQL operations and 89 page services, for what is not typed yet. */
    val raw = Raw()

    inner class Raw {
        suspend fun gql(operation: String, variables: JsonObject): JsonElement = gql.query(operation, variables)
        val operations: Set<String> get() = currentOps.names
        suspend fun service(service: String, method: String, vararg args: JsonElement): JsonElement = bridge.service(service, method, *args)
        suspend fun get(url: String): JsonElement? = http.getJson(url)
        /** Any method on a Spotify address ([app.myspoti.spotify.gql.SpotifyHttp.sendRaw]). */
        suspend fun request(method: String, url: String, body: ByteArray?, headers: Map<String, String>) = http.sendRaw(method, url, body, headers)
        suspend fun proto(method: String, url: String, body: ByteArray?) = http.sendProto(method, url, body)
    }

    init {
        scope.launch {
            // Each page load may be another account (logout, then a new sign-in): the next
            // direct call asks the page for its current token.
            ready.filter { it }.collect {
                this@SpotifyApi.http.forgetWebToken()
                users.forgetUsername()
                try {
                    player.refresh()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // No state now: the page's next state event brings it anyway.
                }
            }
        }
    }

    suspend fun onPageLoaded() = bridge.inject()

    /** The host calls this when a Web Player page load starts (reload, renderer back). */
    fun onPageStarting() = bridge.pageGone()

    /** True when the page's token belongs to a signed-in user (the cached one is reused). */
    suspend fun isSignedIn(): Boolean = http.isSignedIn()
    /** What the token in hand says, without asking the page (null: none yet); for logs. */
    fun signedInHint(): Boolean? = http.signedInHint()

    suspend fun awaitReady() { ready.first { it } }
}
