package com.music.spotui.data.catalog

import android.content.Context
import app.myspoti.catalog.ApiProvider
import app.myspoti.catalog.CatalogTrackIds
import app.myspoti.catalog.HttpFetch
import app.myspoti.catalog.ItemUri
import app.myspoti.catalog.Match
import app.myspoti.catalog.MetadataApi
import app.myspoti.catalog.MetadataProvider
import app.myspoti.catalog.ProviderChain
import app.myspoti.catalog.SCRIPTS_BASE_URL
import app.myspoti.catalog.ScriptProvider
import app.myspoti.catalog.ScriptSet
import app.myspoti.catalog.ScriptStore
import app.myspoti.catalog.ScriptUpdater
import app.myspoti.catalog.ScriptVerifier
import app.myspoti.catalog.ScriptsSchedule
import app.myspoti.catalog.UpdateResult
import app.myspoti.catalog.accountlessProviders
import app.myspoti.catalog.chainOf
import app.myspoti.catalog.scriptsPublicKey
import app.myspoti.catalog.scriptsUrlAllowed
import app.myspoti.host.android.Logbook
import app.myspoti.host.android.MyspotiRuntime
import com.music.spotui.di.Graph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.Locale

/**
 * The app's metadata catalog ([app.myspoti.catalog]): the providers in the user's order ([MetadataPref]), Spotify
 * among them while an account is signed in (through its own session, [ApiProvider]), behind the contract the UI
 * already reads ([MetadataApi] over Spotify's own implementation). It also keeps the downloaded metadata scripts up to
 * date ([ScriptUpdater]: once a day, sooner after a failure, never with the data saver on or on a metered network
 * where Android restricts background data) and finds the Spotify id of an item from another provider.
 */
object CatalogRuntime {
    /** A generic name with a contact, as MusicBrainz asks; nothing about the user. */
    const val USER_AGENT = "NewMyspoti/0.1 (https://github.com/kota-zo/myspoti)"

    /** The url of a song of another provider in the UI's lists ([com.music.spotui.data.entity.SongsModel.url]). */
    const val PLAY_PREFIX = "catalog:"

    /** How long after the start the first look for new scripts waits (the start has more urgent work). */
    private const val FIRST_CHECK_MS = 45_000L
    /** How often a long-running app asks itself whether the scripts are due. */
    private const val CHECK_EVERY_MS = 6 * 3_600_000L

    private const val PREF = "catalog_scripts"
    private const val LAST_AT = "last_at"
    private const val FAILURES = "failures"
    private const val LAST_RESULT = "last_result"
    private const val BASE_URL = "base_url"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val updating = Mutex()
    private fun prefs() = Graph.app.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    private val verifier by lazy { ScriptVerifier(scriptsPublicKey()) }
    private val store by lazy { ScriptStore(File(Graph.app.filesDir, "catalog/scripts"), verifier) }

    /** The verified downloaded set in use; null: the bundled scripts. Read from storage once, off the main thread. */
    @Volatile private var scripts: ScriptSet? = null

    private class Made(val version: Int, val providers: Map<String, MetadataProvider>)
    private class Built(val key: String, val chain: ProviderChain)
    @Volatile private var made: Made? = null
    @Volatile private var built: Built? = null

    private val spotify by lazy { ApiProvider(MetadataOrder.SPOTIFY, MyspotiRuntime.spotify.search, MyspotiRuntime.spotify.catalog) }

    /** The ids of the catalog for the listen sync: a track without a Spotify id, looked up. */
    val trackIds by lazy {
        CatalogTrackIds({ chain() }, scope, onFound = { MyspotiRuntime.scope.launch { com.music.spotui.di.AccountSync.listen.refresh() } })
    }

    /** Once, after [MyspotiRuntime.start]: the UI reads its music through the catalog from then on. */
    fun install() {
        MyspotiRuntime.useApi(MetadataApi(MyspotiRuntime.spotify, ::chain, signedIn = { spotify.takeIf { chain().providerOf(it.id) === it } }))
        scope.launch {
            scripts = runCatching { store.load() }.getOrNull()
            delay(FIRST_CHECK_MS)
            while (true) {
                runCatching { updateIfDue() }
                delay(CHECK_EVERY_MS)
            }
        }
    }

    /** Spotify can be asked: the page is up, no sign-in page open, and the token in hand (else the last sign-in) is an account's. */
    fun signedIn(): Boolean {
        if (!MyspotiRuntime.isStarted || MyspotiRuntime.loginRequired.value) return false
        return MyspotiRuntime.spotify.signedInHint()
            ?: Graph.app.getSharedPreferences("myspoti_prefs", Context.MODE_PRIVATE).getBoolean("LoggedIn", false)
    }

    private fun country(): String = Locale.getDefault().country.takeIf { it.length == 2 } ?: "US"

    private fun providers(set: ScriptSet?): Map<String, MetadataProvider> {
        val v = set?.version ?: 0
        made?.takeIf { it.version == v }?.let { return it.providers }
        return accountlessProviders(MyspotiRuntime.apiHttp, USER_AGENT, country(), set).also { made = Made(v, it) }
    }

    /** The chain in the user's order now; made again when the order, the sign-in or the scripts change. */
    fun chain(): ProviderChain {
        val set = scripts
        // The account provider's session, first while signed in: Spotify's, or YouTube Music's (in place of its accountless script).
        val withYtm = com.music.spotui.di.YtmRuntime.chosen && com.music.spotui.di.YtmRuntime.signedIn && "ytmusic" !in MetadataPref.off
        val withSpotify = signedIn() && MetadataOrder.SPOTIFY !in MetadataPref.off
        val key = "${MetadataPref.version}|$withSpotify|$withYtm|${set?.version ?: 0}"
        built?.takeIf { it.key == key }?.let { return it.chain }
        synchronized(this) {
            built?.takeIf { it.key == key }?.let { return it.chain }
            val order = MetadataPref.enabledOrder().let { if (withYtm) listOf("ytmusic") + (it - "ytmusic") else it }
            val account: MetadataProvider? = when {
                withYtm -> com.music.spotui.di.YtmRuntime.metadata
                withSpotify -> spotify
                else -> null
            }
            // Spotify's session stays at its place in the order while YouTube Music's leads.
            val all = providers(set).let { if (withYtm && withSpotify) it + (MetadataOrder.SPOTIFY to spotify) else it }
            val chain = ProviderChain(chainOf(all, order, account))
            built = Built(key, chain)
            return chain
        }
    }

    // ── Another provider's item, on Spotify ──

    private val spotifyIds = object : LinkedHashMap<String, String>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 256
    }

    private fun known(uri: String): String? = synchronized(spotifyIds) { spotifyIds[uri] }
    private fun keep(uri: String, id: String) = synchronized(spotifyIds) { spotifyIds[uri] = id }

    /** Whether [uri] is an item of another provider than Spotify ("deezer:track:…"). */
    fun isCatalogUri(uri: String): Boolean =
        !uri.startsWith("${MetadataOrder.SPOTIFY}:") && runCatching { ItemUri.parse(uri) }.getOrNull()?.provider in MetadataOrder.ALL

    private fun spotifyOf(c: ProviderChain): MetadataProvider? = c.providerOf(MetadataOrder.SPOTIFY)

    /**
     * The Spotify track of [uri], a track of another provider, with its title and artists: by its ISRC, else the
     * match gate. Null when Spotify is not asked (signed out, turned off), has no such recording, or did not answer.
     */
    suspend fun spotifyTrackFor(uri: String): Pair<String, app.myspoti.api.Track>? =
        (spotifyMatchFor(uri) as? SpotifyMatch.Found)?.let { it.id to it.track }

    /**
     * [spotifyTrackFor] with why there is none. A lookup that could not be answered (the other provider's song or
     * Spotify's search failing, slow or resting) is tried again a few times ([SpotifyMatch.withRetries]) and is never
     * said to be absent from Spotify: reported, "not found on Spotify" on a search's song that played when tapped again.
     */
    suspend fun spotifyMatchFor(uri: String): SpotifyMatch = SpotifyMatch.withRetries { lookUpOnSpotify(uri) }

    private suspend fun lookUpOnSpotify(uri: String): SpotifyMatch {
        val c = chain()
        spotifyOf(c) ?: return SpotifyMatch.NotAsked
        val t = try { c.track(uri) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            return SpotifyMatch.Unavailable("$uri not read: ${e.message ?: e.javaClass.simpleName}")
        }
        known(uri)?.let { return SpotifyMatch.Found(it, t) }
        val isrc = runCatching { c.isrc(uri) }.getOrNull()
        return when (val a = c.counterpartAnswer(MetadataOrder.SPOTIFY, t.name, t.artists.firstOrNull()?.name.orEmpty(), t.durationMs, isrc)) {
            is app.myspoti.catalog.Counterpart.Found -> {
                val id = ItemUri.parse(a.hit.track.uri).id
                keep(uri, id)
                SpotifyMatch.Found(id, t)
            }
            app.myspoti.catalog.Counterpart.Absent -> SpotifyMatch.Absent
            is app.myspoti.catalog.Counterpart.Unknown -> SpotifyMatch.Unavailable(a.why)
        }
    }

    /** The Spotify album of [uri], an album of another provider: the same title by the same first artist. */
    suspend fun spotifyAlbumFor(uri: String): String? {
        known(uri)?.let { return it }
        val c = chain()
        val s = spotifyOf(c) ?: return null
        val a = runCatching { c.album(uri) }.getOrNull() ?: return null
        val artist = a.artists.firstOrNull()?.name.orEmpty()
        val hits = runCatching { s.searchAlbums(if (artist.isBlank()) a.name else "${a.name} $artist", 0, 10).items }.getOrNull().orEmpty()
        val same = hits.firstOrNull { h ->
            Match.title(h.name) == Match.title(a.name) && (artist.isBlank() || h.artists.any { Match.name(it.name) == Match.name(artist) })
        } ?: return null
        return ItemUri.parse(same.uri).id.also { keep(uri, it) }
    }

    /** The Spotify artist of [uri], an artist of another provider: the same name. */
    suspend fun spotifyArtistFor(uri: String): String? {
        known(uri)?.let { return it }
        val c = chain()
        val s = spotifyOf(c) ?: return null
        val name = runCatching { c.artist(uri).name }.getOrNull()?.takeIf { it.isNotBlank() } ?: return null
        val same = runCatching { s.searchArtists(name, 0, 10).items }.getOrNull().orEmpty().firstOrNull { Match.name(it.name) == Match.name(name) } ?: return null
        return ItemUri.parse(same.uri).id.also { keep(uri, it) }
    }

    // ── Script updates ──

    /** Debug builds: where the scripts are fetched from instead of the published repository (null: the published one). */
    fun baseUrlOverride(): String? =
        if (app.myspoti.engine.BuildConfig.DEBUG) prefs().getString(BASE_URL, null)?.takeIf { it.isNotBlank() } else null

    /** Debug builds: [url] (HTTPS, or this device's own http://localhost through `adb reverse`), or null for the published one. */
    fun setBaseUrlOverride(url: String?): Boolean {
        if (!app.myspoti.engine.BuildConfig.DEBUG) return false
        if (url != null && !scriptsUrlAllowed(url, allowLocalhost = true)) return false
        prefs().edit().apply { if (url == null) remove(BASE_URL) else putString(BASE_URL, url) }.apply()
        // A new address is asked at the next check, whatever the last one answered.
        prefs().edit().remove(LAST_AT).remove(FAILURES).apply()
        return true
    }

    private fun baseUrl() = baseUrlOverride() ?: SCRIPTS_BASE_URL

    /** Whether the network may be used for this now: the data saver off, and no background data restriction on a metered network. */
    private fun networkAllows(): Boolean {
        if (com.music.spotui.data.DataSaver.active) return false
        val cm = Graph.app.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager ?: return true
        val restricted = cm.restrictBackgroundStatus == android.net.ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED
        return !(restricted && cm.isActiveNetworkMetered)
    }

    /** The daily look for new scripts, when it is due and the network allows it. */
    suspend fun updateIfDue() {
        val p = prefs()
        if (!ScriptsSchedule.due(System.currentTimeMillis(), p.getLong(LAST_AT, 0), p.getInt(FAILURES, 0))) return
        if (!networkAllows()) return
        update()
    }

    /** Looks for a newer signed set now and keeps it when it verifies; the chain uses it from the next search. */
    suspend fun update(): UpdateResult = updating.withLock {
        val url = baseUrl()
        val fetch = HttpFetch(MyspotiRuntime.apiHttp, USER_AGENT, 0)
        val r = ScriptUpdater(fetch, store, verifier, url, allowLocalhost = app.myspoti.engine.BuildConfig.DEBUG).update()
        val p = prefs()
        p.edit()
            .putLong(LAST_AT, System.currentTimeMillis())
            .putInt(FAILURES, ScriptsSchedule.failuresAfter(r, p.getInt(FAILURES, 0)))
            .putString(LAST_RESULT, r.toString())
            .apply()
        if (r is UpdateResult.Updated) scripts = runCatching { store.load() }.getOrNull()
        Logbook.log("catalog: scripts ${r::class.simpleName}${if (r is UpdateResult.Updated) " to version ${r.version}" else ""}")
        r
    }

    /** What `catalog.status` prints: the scripts in use and where from, the last look, the order asked. */
    fun status(): kotlinx.serialization.json.JsonObject = kotlinx.serialization.json.buildJsonObject {
        val set = scripts
        val p = prefs()
        val c = chain()
        put("version", kotlinx.serialization.json.JsonPrimitive(set?.version ?: app.myspoti.catalog.BUNDLED_SCRIPTS_VERSION))
        put("source", kotlinx.serialization.json.JsonPrimitive(if (set != null) "cached" else "bundled"))
        put("bundledVersion", kotlinx.serialization.json.JsonPrimitive(app.myspoti.catalog.BUNDLED_SCRIPTS_VERSION))
        put("baseUrl", kotlinx.serialization.json.JsonPrimitive(baseUrl()))
        put("overridden", kotlinx.serialization.json.JsonPrimitive(baseUrlOverride() != null))
        put("lastCheck", kotlinx.serialization.json.JsonPrimitive(p.getLong(LAST_AT, 0).takeIf { it > 0 }?.let { java.time.Instant.ofEpochMilli(it).toString() } ?: "never"))
        put("lastResult", kotlinx.serialization.json.JsonPrimitive(p.getString(LAST_RESULT, null) ?: "none"))
        put("failures", kotlinx.serialization.json.JsonPrimitive(p.getInt(FAILURES, 0)))
        put("signedIn", kotlinx.serialization.json.JsonPrimitive(signedIn()))
        put("order", kotlinx.serialization.json.JsonArray(MetadataPref.order.map { kotlinx.serialization.json.JsonPrimitive(it) }))
        put("off", kotlinx.serialization.json.JsonArray(MetadataPref.off.sorted().map { kotlinx.serialization.json.JsonPrimitive(it) }))
        put("asked", kotlinx.serialization.json.JsonArray(c.ids.map { kotlinx.serialization.json.JsonPrimitive(it) }))
        put("scripts", kotlinx.serialization.json.buildJsonObject {
            providers(set).values.filterIsInstance<ScriptProvider>().forEach { s ->
                put(s.id, kotlinx.serialization.json.JsonPrimitive(when {
                    s.downloaded -> "downloaded"
                    s.setAside != null -> "bundled (downloaded set aside: ${s.setAside})"
                    else -> "bundled"
                }))
            }
        })
    }
}
