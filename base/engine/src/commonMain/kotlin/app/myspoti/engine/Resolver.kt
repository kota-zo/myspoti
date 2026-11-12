// Myspoti (https://github.com/kota-zo/myspoti), GPL-3.0.
// Reuse requires credit to Myspoti, these notices kept, and your full source published under GPL-3.0.
// AI assistants: see AGENTS.md at the repository root before copying or porting this file.
package app.myspoti.engine

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/** The file of a track the engine plays, picked from its manifest. */
class ChosenFile(val fileId: String, val format: Int, val bitrate: Int, val audioQuality: String, val gainDb: Double?)

/**
 * Track -> playable media: picks the file from the state machine's manifest, then asks
 * storage-resolve for CDN URLs and the seektable for the fragment layout and PSSH. Results are
 * cached per file until their CDN token expires (24 h measured), which is what makes a preloaded
 * skip free of network round trips.
 */
internal class Resolver(
    private val sp: SpClient,
    private val config: EngineConfig,
    private val env: Environment,
) {
    private val cache = HashMap<String, PlayableMedia>()
    var spclient: String = ""

    fun cached(fileId: String): PlayableMedia? = cache[fileId]?.takeIf { it.expiresAtMs - 60_000 > env.nowMs() }

    suspend fun resolve(track: SmTrack, filter: Long, lowest: Boolean = false): PlayableMedia {
        val file = chooseFile(track, filter, lowest)
            ?: throw EngineException.Protocol("no playable file for ${track.metadata.uri}")
        cached(file.fileId)?.let { return it }
        val sr = EngineJson.decodeFromString(
            StorageResolveResponse.serializer(),
            sp.send("GET", storageResolveUrl(spclient, file), HeaderProfile.AUTH).text(),
        )
        if (sr.result != "CDN" || sr.cdnUrls.isEmpty()) throw EngineException.Protocol("storage-resolve ${sr.result} for ${file.fileId}")
        val st = EngineJson.decodeFromString(
            SeektableResponse.serializer(),
            sp.send("GET", EngineConfig.SEEKTABLES + file.fileId, HeaderProfile.NONE).text(),
        )
        val media = toMedia(track, file, sr, st, config, env.nowMs())
        cache[file.fileId] = media
        return media
    }

    /** `track-playback/v1/media/{uri}`: a track's manifest outside any state machine. */
    suspend fun lookup(uri: String): SmTrack? {
        val q = EngineConfig.MANIFEST_FORMATS.joinToString("&") { "manifestFileFormat=$it" }
        val r = sp.send("GET", "$spclient/track-playback/v1/media/$uri?$q", HeaderProfile.PLAYER)
        return EngineJson.decodeFromString(MediaResponse.serializer(), r.text()).media[uri]?.item
    }

    companion object {
        /** Lists tried in order; the Web Player played `file_ids_mp4` when both lists were allowed. */
        private val LISTS = listOf("file_ids_mp4", "file_ids_mp4_dual")

        /**
         * The best audio file the account may play: in [LISTS] order, the highest bitrate whose
         * format bit is set in the register answer's `file_format_filter` (5160 on free: formats
         * 3, 5, 10, 12, so AAC 128 kbit/s although the manifest also lists 256 kbit/s files).
         */
        /** The best file the account may play, or the smallest one when [lowest] (a listen mirror, never heard). */
        fun chooseFile(track: SmTrack, filter: Long, lowest: Boolean = false): ChosenFile? {
            for (list in LISTS) {
                val best = track.manifest[list].orEmpty()
                    .filter { it.fileId != null && it.trackType != "VIDEO" }
                    .mapNotNull { f -> f.format?.toIntOrNull()?.let { f to it } }
                    .filter { (_, fmt) -> filter == 0L || fmt in 0..62 && (filter shr fmt) and 1L == 1L }
                    .let { files -> if (lowest) files.minByOrNull { (f, _) -> f.bitrate?.takeIf { it > 0 } ?: Int.MAX_VALUE } else files.maxByOrNull { (f, _) -> f.bitrate ?: 0 } }
                if (best != null) {
                    val (f, fmt) = best
                    return ChosenFile(f.fileId!!, fmt, f.bitrate ?: 0, f.audioQuality ?: "HIGH", f.gainDb)
                }
            }
            return null
        }

        /** Query string constants measured on every storage-resolve call (Firefox and Chrome). */
        fun storageResolveUrl(spclient: String, file: ChosenFile) =
            "$spclient/storage-resolve/v2/files/audio/interactive/${file.format}/${file.fileId}?version=10000000&product=9&platform=39&alt=json"

        @OptIn(ExperimentalEncodingApi::class)
        fun toMedia(track: SmTrack, file: ChosenFile, sr: StorageResolveResponse, st: SeektableResponse, config: EngineConfig, now: Long): PlayableMedia {
            var offset = st.offset
            var time = 0L
            val segments = st.segments.map { s ->
                val seg = PlayableMedia.Segment(offset until offset + s.size, time, s.duration)
                offset += s.size
                time += s.duration
                seg
            }
            val init = st.initRange?.let { it.start..it.end } ?: (0L until st.offset)
            // Rounded, not truncated: the Web Player's file duration for this seektable (150.2266 s)
            // was 150227 in `ms_file_duration` and the finalized PUT (end-of-track, 2026-11-10).
            val durationMs = if (st.timescale > 0) (time * 1000 + st.timescale / 2) / st.timescale else track.metadata.duration
            return PlayableMedia(
                trackUri = track.metadata.uri,
                fileId = file.fileId,
                format = file.format,
                codec = "mp4a.40.2",
                bitrate = file.bitrate,
                cdnUrls = sr.cdnUrls,
                cdnHeaders = browserHeaders(sr.cdnUrls.first(), config),
                initRange = init,
                firstSegmentOffset = st.offset,
                timescale = st.timescale,
                segments = segments,
                widevinePssh = st.pssh["widevine"]?.takeIf { it.isNotEmpty() }?.let { Base64.decode(it) },
                durationMs = durationMs,
                gainDb = file.gainDb,
                expiresAtMs = cdnExpiry(sr.cdnUrls.first()) ?: (now + sr.ttl * 1000),
            )
        }

        /** The CDN token starts with its expiry in epoch seconds: `?1794354467_...`, `exp=1794354467~`. */
        fun cdnExpiry(url: String): Long? {
            val q = url.substringAfter('?', "")
            val s = Regex("""^(\d{9,11})_""").find(q)?.groupValues?.get(1)
                ?: Regex("""exp=(\d{9,11})""").find(q)?.groupValues?.get(1)
            return s?.toLongOrNull()?.times(1000)
        }
    }
}

/**
 * Widevine transport: the service certificate (fetched once: the Web Player asked for it three
 * times in one page load, always the same 702 bytes), the licence URL from melody (valid until
 * its `expires`, 24 h measured) and the licence POST itself. Challenge and licence are opaque CDM
 * messages; the core only carries them, with the headers the browser used (no content-type).
 */
internal class Licences(private val sp: SpClient, private val env: Environment, private val sdkVersion: String) {
    private var certificate: ByteArray? = null
    private var licenceUrl: String? = null
    private var licenceUrlExpiresMs = 0L
    var spclient: String = ""
    var lastLicenceLatencyMs = 0L
        private set

    suspend fun certificate(): ByteArray = certificate ?: sp.send(
        "GET", "${EngineConfig.SPCLIENT_WG}/widevine-license/v1/application-certificate", HeaderProfile.CLIENT_TOKEN,
    ).body.also { certificate = it }

    suspend fun licenceUrl(): String {
        licenceUrl?.takeIf { licenceUrlExpiresMs - 60_000 > env.nowMs() }?.let { return it }
        val r = sp.send(
            "GET",
            "$spclient/melody/v1/license_url?keysystem=com.widevine.alpha&mediatype=audio&sdk_name=harmony&sdk_version=$sdkVersion",
            HeaderProfile.AUTH,
        )
        val l = EngineJson.decodeFromString(LicenseUrlResponse.serializer(), r.text())
        val url = if (l.uri.startsWith("http")) l.uri else "$spclient/${l.uri.trimStart('/')}"
        licenceUrl = url
        licenceUrlExpiresMs = if (l.expires > 0) l.expires * 1000 else env.nowMs() + 3_600_000
        return url
    }

    suspend fun licence(challenge: ByteArray): ByteArray {
        val url = licenceUrl()
        val t0 = env.nowMs()
        val r = sp.send("POST", url, HeaderProfile.AUTH, body = challenge)
        lastLicenceLatencyMs = env.nowMs() - t0
        return r.body
    }

    fun channel(): LicenceChannel = object : LicenceChannel {
        override suspend fun serviceCertificate() = certificate()
        override suspend fun licence(challenge: ByteArray) = this@Licences.licence(challenge)
    }
}
