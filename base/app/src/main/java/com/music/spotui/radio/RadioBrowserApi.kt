package com.music.spotui.radio

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * One station, as the public radio-browser.info directory (https://www.radio-browser.info,
 * API at https://api.radio-browser.info) describes it. Only the fields this module actually
 * shows are kept; the server sends many more.
 */
data class RadioStation(
    val stationUuid: String,
    val name: String,
    val streamUrl: String,
    val homepage: String,
    val favicon: String,
    val tags: List<String>,
    val country: String,
    val countryCode: String,
    val language: String,
    val codec: String,
    val bitrateKbps: Int,
    val votes: Int,
    val clickCount: Int,
    val lastCheckOk: Boolean,
)

data class RadioTag(val name: String, val stationCount: Int)

/**
 * A thin client for the radio-browser.info directory: a free, community-run index of public
 * internet radio streams (no account, no key). This module is the only place in the app that
 * talks to it, so it stays easy to replace or remove.
 *
 * The directory has no single server: a pool of community-hosted mirrors answers under
 * `all.api.radio-browser.info` (round-robin DNS). Resolving a hostname needs no extra
 * permission the app doesn't already have (INTERNET is granted), so the mirror list is read
 * once, shuffled, and tried in order; the first one that answers is kept for the session.
 */
object RadioBrowserApi {
    private const val DISCOVERY_HOST = "all.api.radio-browser.info"
    private const val MIRROR_SUFFIX = ".api.radio-browser.info"
    /** Used only if the DNS discovery above fails (a captive portal, a DNS block, …). */
    private val FALLBACK_HOSTS = listOf(
        "https://de1.api.radio-browser.info",
        "https://nl1.api.radio-browser.info",
        "https://at1.api.radio-browser.info",
    )
    private const val CURATED_PREFIX = "curated-"
    private const val USER_AGENT = "Myspoti/1.0 (Live Radio module; +https://github.com/kota-zo/myspoti)"

    private val json = Json { ignoreUnknownKeys = true }
    private val http by lazy { app.myspoti.host.android.TlsCompat.apply(OkHttpClient.Builder()).callTimeout(12, TimeUnit.SECONDS).build() }

    @Volatile private var resolvedHost: String? = null
    private val hostLock = Any()

    private suspend fun host(): String {
        resolvedHost?.let { return it }
        return withContext(Dispatchers.IO) {
            synchronized(hostLock) {
                resolvedHost?.let { return@withContext it }
                // DISCOVERY_HOST's round-robin DNS hands out bare IPs. A mirror's TLS cert is
                // issued for its real hostname (e.g. "de1.api.radio-browser.info"), not for that
                // IP, so calling "https://<ip>" directly fails hostname verification on every
                // request. A reverse (PTR) lookup on each IP recovers the real hostname first.
                // Only a name of the directory itself is kept: a reverse lookup answers whatever
                // its DNS says, and the app is not to be sent to some other https host by it.
                val found = runCatching { java.net.InetAddress.getAllByName(DISCOVERY_HOST) }
                    .getOrNull()
                    ?.mapNotNull { addr ->
                        val canonical = addr.canonicalHostName.trimEnd('.')
                        if (canonical.endsWith(MIRROR_SUFFIX) && canonical != addr.hostAddress) "https://$canonical" else null
                    }
                    ?.distinct()?.shuffled()
                val chosen = found?.firstOrNull() ?: FALLBACK_HOSTS.random()
                resolvedHost = chosen
                chosen
            }
        }
    }

    /** A mirror turned out unreachable: the next call resolves again instead of retrying it. */
    private fun forgetHost() { resolvedHost = null }

    /**
     * Stations matching [query] (name search) and/or [tag]; [limit] at most, ordered by vote
     * count (the directory's own measure of a station's popularity), broken streams and the
     * ones this app cannot play (see [playable]) left out. An empty [query] with a [tag] browses
     * that tag. Throws an [java.io.IOException] when no mirror of the directory answers.
     */
    suspend fun search(
        query: String = "",
        tag: String = "",
        limit: Int = 40,
        order: String = "votes",
    ): List<RadioStation> = withContext(Dispatchers.IO) {
        val params = buildString {
            append("hidebroken=true&is_https=true&limit=$limit&order=${enc(order)}&reverse=true")
            if (query.isNotBlank()) append("&name=${enc(query)}")
            if (tag.isNotBlank()) append("&tag=${enc(tag)}")
        }
        stations(getArray("/json/stations/search?$params"))
    }

    /** The directory's most-voted stations overall, for a starting screen with nothing searched yet. */
    suspend fun topStations(limit: Int = 30): List<RadioStation> = search(limit = limit, order = "votes")

    /** Every tag (genre, mood, format…) the directory knows, most used first. */
    suspend fun tags(limit: Int = 60): List<RadioTag> = withContext(Dispatchers.IO) {
        getArray("/json/tags?order=stationcount&reverse=true&limit=$limit").map {
            val o = it.jsonObject
            RadioTag(o.str("name").orEmpty(), o.int("stationcount") ?: 0)
        }.filter { it.name.isNotBlank() }
    }

    /**
     * Tells the directory a station was played (its "click count", how it ranks stations by
     * popularity). Best-effort: a station someone actually listens to should count even if this
     * single call fails, so errors are swallowed. The curated stations are not in the directory.
     */
    suspend fun reportClick(stationUuid: String) {
        if (stationUuid.isBlank() || stationUuid.startsWith(CURATED_PREFIX)) return
        withContext(Dispatchers.IO) { runCatching { get("/json/url/${enc(stationUuid)}") } }
    }

    /**
     * Whether this app can play [streamUrl]: https only (the app allows no cleartext traffic,
     * and a station is no reason to relax that), and no HLS, which the player is built without
     * (no media3-exoplayer-hls): an .m3u8 address would throw before it even starts.
     */
    internal fun playable(streamUrl: String, hls: Boolean = false): Boolean {
        if (hls) return false
        val uri = runCatching { java.net.URI(streamUrl.trim()) }.getOrNull() ?: return false
        return uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank() &&
            !uri.path.orEmpty().endsWith(".m3u8", ignoreCase = true)
    }

    /** The directory's station list, without the ones [playable] turns down. */
    internal fun stations(array: JsonArray): List<RadioStation> = array.mapNotNull(::station)

    private fun station(e: JsonElement): RadioStation? {
        val o = e as? JsonObject ?: return null
        // url_resolved follows redirects already; url is the directory's raw entry.
        val streamUrl = o.str("url_resolved")?.takeIf { it.isNotBlank() } ?: o.str("url").orEmpty()
        if (!playable(streamUrl, hls = o.bool("hls") == true)) return null
        return RadioStation(
            stationUuid = o.str("stationuuid").orEmpty(),
            name = o.str("name").orEmpty().ifBlank { "?" },
            streamUrl = streamUrl,
            homepage = o.str("homepage").orEmpty(),
            favicon = o.str("favicon").orEmpty(),
            tags = o.str("tags").orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() },
            country = o.str("country").orEmpty(),
            countryCode = o.str("countrycode").orEmpty(),
            language = o.str("language").orEmpty(),
            codec = o.str("codec").orEmpty(),
            bitrateKbps = o.int("bitrate") ?: 0,
            votes = o.int("votes") ?: 0,
            clickCount = o.int("clickcount") ?: 0,
            lastCheckOk = o.bool("lastcheckok") ?: true,
        )
    }

    private suspend fun getArray(path: String): JsonArray =
        get(path) as? JsonArray ?: throw java.io.IOException("radio-browser.info: $path is not a list")

    /**
     * One GET, tried once more against a freshly resolved mirror if the first one fails; throws
     * the last failure when neither answers, so a screen can say the directory is unreachable.
     */
    private suspend fun get(path: String): JsonElement {
        var failure: Exception? = null
        repeat(2) {
            val base = host()
            val req = Request.Builder().url(base + path)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .build()
            try {
                http.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) throw java.io.IOException("HTTP ${r.code}")
                    return json.parseToJsonElement(r.body.string())
                }
            } catch (e: java.io.IOException) {
                failure = e
            } catch (e: IllegalArgumentException) {
                // Not JSON (a captive portal's page): kotlinx' SerializationException is one.
                failure = e
            }
            forgetHost()
        }
        throw java.io.IOException("radio-browser.info did not answer", failure)
    }

    /**
     * A handful of well-known public-broadcaster stations, shown on "Popular" before (and
     * regardless of) any directory call: the screen has something to show the instant it opens,
     * and stays useful even if the directory or this device's network has a bad moment. Public
     * broadcaster streams rather than commercial ones, since their URLs change far less often.
     */
    val curatedPopular: List<RadioStation> = listOf(
        RadioStation(
            stationUuid = "${CURATED_PREFIX}de-bayern3", name = "Bayern 3",
            streamUrl = "https://dispatcher.rndfnk.com/br/br3/live/mp3/128/stream.mp3",
            homepage = "https://www.br.de/radio/bayern-3/", favicon = "", tags = listOf("pop"),
            country = "Germany", countryCode = "DE", language = "german", codec = "MP3",
            bitrateKbps = 128, votes = 0, clickCount = 0, lastCheckOk = true,
        ),
        RadioStation(
            stationUuid = "${CURATED_PREFIX}de-wdr2", name = "WDR 2",
            streamUrl = "https://wdr-wdr2-rheinland.icecastssl.wdr.de/wdr/wdr2/rheinland/mp3/128/stream.mp3",
            homepage = "https://www1.wdr.de/radio/wdr2/", favicon = "", tags = listOf("pop"),
            country = "Germany", countryCode = "DE", language = "german", codec = "MP3",
            bitrateKbps = 128, votes = 0, clickCount = 0, lastCheckOk = true,
        ),
        RadioStation(
            stationUuid = "${CURATED_PREFIX}de-ndr2", name = "NDR 2",
            streamUrl = "https://ndr-ndr2-niedersachsen.cast.addradio.de/ndr/ndr2/niedersachsen/mp3/128/stream.mp3",
            homepage = "https://www.ndr.de/ndr2/", favicon = "", tags = listOf("pop"),
            country = "Germany", countryCode = "DE", language = "german", codec = "MP3",
            bitrateKbps = 128, votes = 0, clickCount = 0, lastCheckOk = true,
        ),
        RadioStation(
            stationUuid = "${CURATED_PREFIX}nl-radio2", name = "NPO Radio 2",
            streamUrl = "https://icecast.omroep.nl/radio2-bb-mp3",
            homepage = "https://www.nporadio2.nl/", favicon = "", tags = listOf("pop"),
            country = "Netherlands", countryCode = "NL", language = "dutch", codec = "MP3",
            bitrateKbps = 128, votes = 0, clickCount = 0, lastCheckOk = true,
        ),
        RadioStation(
            stationUuid = "${CURATED_PREFIX}nl-3fm", name = "NPO 3FM",
            streamUrl = "https://icecast.omroep.nl/3fm-bb-mp3",
            homepage = "https://www.3fm.nl/", favicon = "", tags = listOf("alternative"),
            country = "Netherlands", countryCode = "NL", language = "dutch", codec = "MP3",
            bitrateKbps = 128, votes = 0, clickCount = 0, lastCheckOk = true,
        ),
        RadioStation(
            stationUuid = "${CURATED_PREFIX}nl-radio1", name = "NPO Radio 1",
            streamUrl = "https://icecast.omroep.nl/radio1-bb-mp3",
            homepage = "https://www.nporadio1.nl/", favicon = "", tags = listOf("news"),
            country = "Netherlands", countryCode = "NL", language = "dutch", codec = "MP3",
            bitrateKbps = 128, votes = 0, clickCount = 0, lastCheckOk = true,
        ),
    )

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")
    private fun JsonObject.str(name: String): String? = (this[name] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
    private fun JsonObject.int(name: String): Int? = (this[name] as? kotlinx.serialization.json.JsonPrimitive)?.let {
        it.intOrNull ?: it.contentOrNull?.toIntOrNull()
    }
    private fun JsonObject.bool(name: String): Boolean? = (this[name] as? kotlinx.serialization.json.JsonPrimitive)?.let {
        it.booleanOrNull ?: it.contentOrNull?.let { s -> s == "true" || s == "1" }
    }
}
