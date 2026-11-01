package app.myspoti.extensions

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/** A song as every hook and event receives it. */
@Serializable
data class TrackInfo(
    /** Spotify's track id ("4uLU6hMCjMI75M1A2tKUQC"), empty for a local file. */
    val id: String,
    val uri: String,
    val title: String,
    /** The first artist. */
    val artist: String,
    val artists: List<String>,
    val album: String,
    val durationMs: Long,
    val isrc: String? = null,
    val cover: String? = null,
) {
    fun toJson(): String = ExtJson.encodeToString(serializer(), this)
}

/**
 * An extension's lyrics, checked: at most [MAX_LINES] lines, times in milliseconds. A line may add
 * a [Line.translation] (in [translationLanguage]), a [Line.romanization], who sings it
 * ([Line.voice]) and backing vocals ([Line.background]); a wrong type for one of them is ignored,
 * never an error.
 */
data class LyricsResult(val provider: String, val synced: Boolean, val lines: List<Line>, val translationLanguage: String? = null) {
    data class Line(
        val startMs: Long, val endMs: Long?, val text: String, val words: List<Word>?,
        val translation: String? = null, val romanization: String? = null, val voice: Int? = null, val background: String? = null,
    )
    data class Word(val startMs: Long, val endMs: Long?, val text: String)

    val wordSynced: Boolean get() = synced && lines.any { !it.words.isNullOrEmpty() }

    companion object {
        const val MAX_LINES = 3_000

        /**
         * `{ provider?, synced?, translationLanguage?, lines: [{ time?, end?, text, words?: [{ time, end?, text }],
         * translation?, romanization?, voice?, background? }] }`, or `{ provider?, plain: "text" }`.
         * Null when there is nothing to show.
         */
        fun parse(json: String, fallbackProvider: String): LyricsResult? {
            val o = runCatching { ExtJson.parseToJsonElement(json).jsonObject }.getOrNull() ?: throw ExtensionException("lyrics: not an object")
            val provider = (o["provider"] as? JsonPrimitive)?.contentOrNull?.take(40)?.ifBlank { null } ?: fallbackProvider
            val plain = (o["plain"] as? JsonPrimitive)?.contentOrNull
            if (o["lines"] == null && plain != null) {
                val lines = plain.lines().take(MAX_LINES).map { Line(0, null, it.take(500), null) }
                return LyricsResult(provider, false, lines).takeIf { lines.any { it.text.isNotBlank() } }
            }
            val raw = (o["lines"] as? JsonArray) ?: throw ExtensionException("lyrics: \"lines\" (an array) or \"plain\" (a text)")
            if (raw.size > MAX_LINES) throw ExtensionException("lyrics: more than $MAX_LINES lines")
            val lines = raw.map { e ->
                val l = e as? JsonObject ?: throw ExtensionException("lyrics: each line is an object { time, text }")
                val words = (l["words"] as? JsonArray)?.take(200)?.map { w ->
                    val wo = w.jsonObject
                    Word(ms(wo["time"]) ?: throw ExtensionException("lyrics: a word without its time"), ms(wo["end"]), text(wo["text"]))
                }
                Line(
                    ms(l["time"]) ?: 0, ms(l["end"]), text(l["text"]), words?.takeIf { it.isNotEmpty() },
                    translation = optText(l["translation"]), romanization = optText(l["romanization"]),
                    voice = voice(l["voice"]), background = optText(l["background"]),
                )
            }
            val synced = (o["synced"] as? JsonPrimitive)?.booleanOrNull ?: raw.any { (it as JsonObject)["time"] != null }
            val language = (o["translationLanguage"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { LANGUAGE.matches(it) }
            return LyricsResult(provider, synced, lines, language).takeIf { lines.any { it.text.isNotBlank() } }
        }

        private val LANGUAGE = Regex("[A-Za-z]{2,3}([-_][A-Za-z0-9]{2,8}){0,2}")

        /** An optional text: a string, trimmed, 500 characters at most; anything else is none. */
        private fun optText(e: kotlinx.serialization.json.JsonElement?): String? =
            (e as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.take(500)?.ifBlank { null }

        /** Who sings: 1, 2... (0: together) as a number, "2" or "v2"; anything else is unknown. */
        private fun voice(e: kotlinx.serialization.json.JsonElement?): Int? {
            val p = e as? JsonPrimitive ?: return null
            val n = p.longOrNull ?: p.contentOrNull?.trim()?.removePrefix("v")?.toLongOrNull() ?: return null
            return n.takeIf { it in 0..99 }?.toInt()
        }

        private fun ms(e: kotlinx.serialization.json.JsonElement?): Long? =
            (e as? JsonPrimitive)?.let { p -> p.longOrNull ?: p.contentOrNull?.toDoubleOrNull()?.toLong() }?.coerceAtLeast(0)

        private fun text(e: kotlinx.serialization.json.JsonElement?): String = (e as? JsonPrimitive)?.contentOrNull?.take(500).orEmpty()
    }
}

/** Where a song's audio is, as an audio-source extension gives it. */
data class AudioStream(
    val url: String,
    val headers: Map<String, String>,
    val mimeType: String?,
    val durationMs: Long?,
    /** The id of the extension that answered (set by the app, never read from the answer). */
    val providerId: String? = null,
    /**
     * Instead of [url] (then empty): an MPEG-DASH manifest, base64, of FLAC in fragmented MP4
     * (as `dash-flac` downloads); the app plays its segments in order, seekable.
     */
    val manifest: String? = null,
    /** How long the answer stays good, ms (its address expires): the app asks again after it. */
    val expiresInMs: Long? = null,
    /** The quality the extension announces, shown as it is: bits per sample, Hz, codec ("flac"). */
    val bitDepth: Int? = null,
    val sampleRate: Int? = null,
    val codec: String? = null,
    /**
     * Free text the player shows of the audio when the fields above say nothing ("FLAC · 16-bit ·
     * 44.1 kHz"), 80 characters at most.
     */
    val source: String? = null,
    val transform: FileTransform? = null,
) {
    /** How long the app keeps this answer: [expiresInMs], at most [maxMs]. */
    fun keepMs(maxMs: Long): Long = expiresInMs?.coerceIn(0, maxMs) ?: maxMs

    /**
     * "FLAC · 24-bit · 96 kHz" from what the extension announced (its fields, else its [source]
     * text); null when it said nothing.
     */
    fun qualityLabel(): String? = listOfNotNull(
        codec?.uppercase(),
        bitDepth?.let { "$it-bit" },
        sampleRate?.let { hz -> (if (hz % 1000 == 0) "${hz / 1000}" else "%.1f".format(java.util.Locale.ROOT, hz / 1000.0)) + " kHz" },
    ).takeIf { it.isNotEmpty() }?.joinToString(" · ") ?: source

    companion object {
        private const val MAX_MANIFEST = 1024 * 1024
        private const val MAX_SOURCE = 80

        /**
         * `{ url | manifest, headers?, mimeType?, durationMs?, expiresInMs?, bitDepth?, sampleRate?, codec?, source? }`;
         * null when the extension has no audio for it.
         */
        fun parse(json: String): AudioStream? {
            val o = runCatching { ExtJson.parseToJsonElement(json).jsonObject }.getOrNull() ?: throw ExtensionException("audio-source: not an object")
            fun long(k: String) = (o[k] as? JsonPrimitive)?.let { p -> p.longOrNull ?: p.contentOrNull?.toDoubleOrNull()?.toLong() }
            val mime = (o["mimeType"] as? JsonPrimitive)?.contentOrNull
            val duration = (o["durationMs"] as? JsonPrimitive)?.longOrNull
            val source = (o["source"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.replace(Regex("\\s+"), " ")?.trim()
                ?.take(MAX_SOURCE)?.ifBlank { null }
            val base = AudioStream(
                "", headersOf(o["headers"]), mime, duration, source = source,
                expiresInMs = long("expiresInMs")?.coerceAtLeast(0),
                bitDepth = long("bitDepth")?.toInt()?.takeIf { it in 8..32 },
                sampleRate = long("sampleRate")?.toInt()?.takeIf { it in 8_000..768_000 },
                codec = (o["codec"] as? JsonPrimitive)?.contentOrNull?.lowercase()?.takeIf { it.matches(Regex("[a-z0-9.+-]{1,16}")) },
            )
            val manifest = (o["manifest"] as? JsonPrimitive)?.contentOrNull
            if (manifest != null) {
                if (o["transform"] != null) throw ExtensionException("transform: not for DASH")
                if (manifest.length > MAX_MANIFEST) throw ExtensionException("audio-source: manifest over 1 MB")
                val mpd = runCatching { String(java.util.Base64.getMimeDecoder().decode(manifest)) }.getOrNull()
                if (mpd == null || !mpd.contains("<MPD")) throw ExtensionException("audio-source: manifest is not a base64 MPEG-DASH manifest")
                Regex("(?:initialization|media)=\"([^\"]+)\"").findAll(mpd).forEach { checkUrl("audio-source", it.groupValues[1].replace("&amp;", "&")) }
                return base.copy(manifest = manifest)
            }
            val url = (o["url"] as? JsonPrimitive)?.contentOrNull ?: return null
            checkUrl("audio-source", url)
            return base.copy(url = url, transform = parseFileTransform(o["transform"]))
        }
    }
}

/**
 * What an `audio-source` extension is asked: the song, and `ahead` (true when the app asks before
 * the song plays, to have it ready: the answer is kept and played as given when the song comes).
 */
object AudioQuery {
    fun toJson(track: TrackInfo, ahead: Boolean): String {
        val o = ExtJson.encodeToJsonElement(TrackInfo.serializer(), track).jsonObject
        return JsonObject(o + ("ahead" to JsonPrimitive(ahead))).toString()
    }
}

private fun parseFileTransform(raw: kotlinx.serialization.json.JsonElement?): FileTransform? = raw?.let {
    val t = it as? JsonObject ?: throw ExtensionException("transform: must be an object")
    fun text(k: String) = (t[k] as? JsonPrimitive)?.contentOrNull
    fun number(k: String, default: Int) = if (k !in t) default else
        text(k)?.toIntOrNull() ?: throw ExtensionException("transform: invalid $k")
    if (text("algorithm") != "blowfish-cbc-pattern") throw ExtensionException("transform: unsupported algorithm")
    FileTransform(text("keyHex") ?: "", text("ivHex") ?: "", number("segmentBytes",2048),
        number("every",3), number("offset",0)).checked()
}

/** A public http(s) address, or the hook's error. */
internal fun checkUrl(hook: String, url: String) {
    val scheme = url.substringBefore(':').lowercase()
    if (scheme != "https" && scheme != "http") throw ExtensionException("$hook: the url must be http(s)")
    val host = runCatching { java.net.URI(url).host?.lowercase() }.getOrNull() ?: throw ExtensionException("$hook: not a URL")
    if (ExtensionRuntime.isPrivate(host)) throw ExtensionException("$hook: $host is a local address")
}

/** Headers an extension may not set on a media request (the app or the transport sets them). */
private val MEDIA_FORBIDDEN_HEADERS = setOf("host", "content-length", "connection", "cookie", "set-cookie", "transfer-encoding", "proxy-authorization", "upgrade", "x-forwarded-for")

/** At most 20 headers, without the ones the app sets itself, values of at most 2000 characters on one line. */
internal fun headersOf(e: kotlinx.serialization.json.JsonElement?): Map<String, String> =
    (e as? JsonObject)?.entries?.take(20)?.associate { (k, v) ->
        k to ((v as? JsonPrimitive)?.contentOrNull ?: v.toString())
    }?.filter { (k, v) -> k.lowercase() !in MEDIA_FORBIDDEN_HEADERS && k.length <= 64 && v.length <= 2_000 && k.none { it.isISOControl() || it == ':' } && v.none { it == '\r' || it == '\n' } }.orEmpty()

/**
 * What a `download-source` extension is asked: the song, and the quality the user wants
 * ([QUALITIES]: `hi-res` = the best there is, `cd` = 16-bit lossless, `small` = a small lossy file).
 */
object DownloadQuery {
    val QUALITIES = listOf("hi-res", "cd", "small")

    fun toJson(track: TrackInfo, quality: String): String {
        require(quality in QUALITIES) { "quality $quality" }
        val o = ExtJson.encodeToJsonElement(TrackInfo.serializer(), track).jsonObject
        return JsonObject(o + ("quality" to JsonPrimitive(quality))).toString()
    }
}

/**
 * One file a `download-source` extension offers for a song. Either [url] (a file to fetch) or,
 * for `dash-flac`, [manifest] (an MPEG-DASH manifest, base64, of FLAC in fragmented MP4: the app
 * joins the segments into one FLAC file). Finding and matching the file is the extension's job;
 * the app only checks that it is real audio of the song's length.
 */
data class DownloadFile(
    val url: String?,
    val manifest: String?,
    val headers: Map<String, String>,
    /** One of [FORMATS]. */
    val format: String,
    val bitDepth: Int?,
    val sampleRate: Int?,
    /** Bytes, as the extension expects them (for the progress bar only). */
    val sizeHint: Long?,
    /** Shown in the Downloaded list ("where it came from"), 40 characters. */
    val provider: String?,
    /** The extension found this file by the song's ISRC (the exact recording). */
    val isrcMatched: Boolean,
    val transform: FileTransform? = null,
) {
    val lossless get() = format == "flac" || format == "dash-flac"

    companion object {
        val FORMATS = setOf("flac", "dash-flac", "m4a", "mp3")
        const val MAX_FILES = 10
        private const val MAX_MANIFEST = 1024 * 1024

        /**
         * `[file, ...]`, `{ files: [file, ...] }` or one `file`, each
         * `{ url | manifest, format, headers?, bitDepth?, sampleRate?, sizeHint?, provider?, isrcMatched? }`.
         * In the extension's order (best first); empty when it has nothing.
         */
        fun parseList(json: String): List<DownloadFile> {
            val root = runCatching { ExtJson.parseToJsonElement(json) }.getOrNull() ?: throw ExtensionException("download-source: not JSON")
            val items = when (root) {
                is JsonArray -> root
                is JsonObject -> (root["files"] as? JsonArray) ?: JsonArray(listOf(root))
                else -> throw ExtensionException("download-source: a list of files")
            }
            if (items.size > MAX_FILES) throw ExtensionException("download-source: more than $MAX_FILES files")
            return items.mapNotNull { e ->
                val o = e as? JsonObject ?: throw ExtensionException("download-source: each file is an object { url, format }")
                parse(o)
            }
        }

        private fun parse(o: JsonObject): DownloadFile? {
            fun str(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
            fun int(k: String) = (o[k] as? JsonPrimitive)?.longOrNull
            val format = str("format")?.lowercase() ?: throw ExtensionException("download-source: \"format\" (${FORMATS.joinToString()})")
            if (format !in FORMATS) throw ExtensionException("download-source: format \"$format\" unknown (${FORMATS.joinToString()})")
            val url = str("url")?.takeIf { it.isNotBlank() }
            val manifest = str("manifest")?.takeIf { it.isNotBlank() }
            if (format == "dash-flac") {
                if (manifest == null) throw ExtensionException("download-source: dash-flac needs \"manifest\" (base64)")
                if (manifest.length > MAX_MANIFEST) throw ExtensionException("download-source: manifest over 1 MB")
            } else {
                url ?: return null
                checkUrl("download-source", url)
            }
            val transform = parseFileTransform(o["transform"])
            if (transform != null && format == "dash-flac") throw ExtensionException("transform: not for DASH")
            return DownloadFile(
                url = if (format == "dash-flac") null else url,
                manifest = if (format == "dash-flac") manifest else null,
                headers = headersOf(o["headers"]),
                format = format,
                bitDepth = int("bitDepth")?.toInt()?.takeIf { it in 8..32 },
                sampleRate = int("sampleRate")?.toInt()?.takeIf { it in 8_000..768_000 },
                sizeHint = int("sizeHint")?.takeIf { it > 0 },
                provider = str("provider")?.take(40)?.ifBlank { null },
                isrcMatched = (o["isrcMatched"] as? JsonPrimitive)?.booleanOrNull ?: false,
                transform = transform,
            )
        }
    }
}

/**
 * One `playback` event (docs/extensions/API.md §3): what happened to the song playing, in
 * Myspoti's own player (`source = "local"`: downloads, local files, extensions' audio) or the
 * Spotify Web Player (`"spotify"`).
 */
@Serializable
data class PlaybackEvent(
    /** One of [TYPES]. */
    val type: String,
    val track: TrackInfo,
    /** Where in the song, ms. */
    val positionMs: Long,
    val durationMs: Long,
    /** When it happened, ms since 1970 (UTC). */
    val timestamp: Long,
    val source: String,
    /** The list, album or artist it plays from; empty when unknown (a local file, a single song). */
    val contextUri: String = "",
    val shuffle: Boolean = false,
    /** See [TYPES]; null for progress, pause and resume. */
    val reason: String? = null,
    /** The time actually played of this song so far (seeks and pauses excluded), ms. */
    val playedMs: Long = 0,
    /** Where the audio comes from: one of [ORIGINS]. */
    val origin: String = ORIGIN_SPOTIFY,
    /** The id of the extension that supplied the file or the stream; null for Spotify (or unknown). */
    val providerId: String? = null,
) {
    /** Every field present (`reason: null`, `shuffle: false`, `playedMs: 0`), as documented. */
    fun toJson(): String = FULL.encodeToString(serializer(), this)

    companion object {
        private val FULL = kotlinx.serialization.json.Json { encodeDefaults = true; explicitNulls = true }

        /**
         * start (reason: `play` the first song, `next` after one ended, `skip` after one was skipped),
         * progress (every 10 s played), pause, resume, seek (reason `forward` / `backward`),
         * skip (reason `forward` / `backward` / `other`: the song left before its end),
         * end (reason `finished`: played to the end, `stop`: playback stopped or switched to another player).
         * A song that started leaves with exactly one `skip` or `end`.
         */
        val TYPES = listOf("start", "progress", "pause", "resume", "seek", "skip", "end")
        const val LOCAL = "local"
        const val SPOTIFY = "spotify"

        /** Spotify's own stream (the Web Player, or Spotify Connect). */
        const val ORIGIN_SPOTIFY = "spotify"
        /** A song downloaded with a download-source extension. */
        const val ORIGIN_DOWNLOAD = "download"
        /** A stream an audio-source extension gave. */
        const val ORIGIN_AUDIO_SOURCE = "audio-source"
        /** A file on the phone (the device's music, not a download). */
        const val ORIGIN_LOCAL_FILE = "local-file"
        val ORIGINS = listOf(ORIGIN_SPOTIFY, ORIGIN_DOWNLOAD, ORIGIN_AUDIO_SOURCE, ORIGIN_LOCAL_FILE)

        /**
         * The `track` event: the song's fields ([TrackInfo]) and where its audio comes from,
         * `origin` and `providerId` (always present, `providerId: null` for Spotify).
         */
        fun trackEventJson(track: TrackInfo, origin: String, providerId: String?): String {
            val o = FULL.encodeToJsonElement(TrackInfo.serializer(), track).jsonObject
            return JsonObject(o + mapOf("origin" to JsonPrimitive(origin), "providerId" to JsonPrimitive(providerId))).toString()
        }
    }
}
