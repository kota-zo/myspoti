package app.myspoti.spotify.bridge

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * Where the Web Player page runs: an Android WebView or desktop Chrome over CDP.
 *
 * A host loads open.spotify.com with the user's session, injects [BridgeScript] after each
 * page load, blocks ad audio ([AdBlock.blockedHosts]) and relays the page's messages.
 */
interface BridgeHost {
    /** Runs [script] in the page. Fire and forget: answers come back through [messages]. */
    suspend fun evaluate(script: String)

    /**
     * Runs [script] like [evaluate] and returns the JSON of its value once the page ran it, or
     * null when this host cannot tell. Throws when the script could not be handed to the page,
     * so a call fails at once instead of waiting for an answer that cannot come.
     */
    suspend fun evaluateValue(script: String): String? { evaluate(script); return null }

    /** JSON strings posted by the bridge (`MyspotiHost.postMessage` / `__mspHostPost`). */
    val messages: Flow<String>
}

/** The bridge's source, bundled as a resource of this module. */
object BridgeScript {
    val source: String by lazy {
        BridgeScript::class.java.getResourceAsStream("/bridge/player-bridge.js")!!
            .bufferedReader().use { it.readText() }
    }
}

/**
 * Audio ads. Spotify's server puts them in the player's queue: the track-playback state machine
 * it sends to the page about three minutes ahead, where the song before the ad `advance`s to an
 * ad state at its end. [withoutAds] sends that end to the song after the ad instead, before the
 * page sees the machine, so the page never reaches an ad. The ad audio host is refused as well,
 * and [app.myspoti.spotify.SpotifyPlayer] reloads the page if an ad still becomes current.
 */
object AdBlock {
    /**
     * Only ad media and third-party ad servers are refused. Spotify's own ad endpoints (ad
     * config, ad events, sponsored recommendations) are left alone on purpose: a client that
     * never calls them stands out. Ads that come anyway are muted and skipped by the player,
     * and sponsored content is dropped from the screens.
     */
    val blockedHosts = listOf(
        // The ad audio files.
        "adstudio-assets.scdn.co",
        // Google's ad servers (audio and video ads served in other markets).
        "pubads.g.doubleclick.net",
        "securepubads.g.doubleclick.net",
        "googleads.g.doubleclick.net",
        "pagead2.googlesyndication.com",
        "imasdk.googleapis.com",
        // DoubleClick's ad media CDN (host list credit: coldMEW/Hush, GPL-3.0).
        "s0.2mdn.net",
        // Third-party audience trackers the page loads (nothing it needs to play).
        "googletagmanager.com",
        "google-analytics.com",
        "scorecardresearch.com",
        "fastly-insights.com",
    )

    /** Paths refused on Spotify's api hosts; none, see [blockedHosts]. */
    val blockedPaths = emptyList<String>()

    fun isBlocked(url: String): Boolean {
        val rest = url.substringAfter("://")
        val host = rest.substringBefore('/').substringBefore('?').substringBefore(':').lowercase()
        if (blockedHosts.any { host == it || host.endsWith(".$it") }) return true
        if (!host.endsWith(".spotify.com") || "spclient" !in host) return false
        val path = "/" + rest.substringAfter('/', "").substringBefore('?')
        return blockedPaths.any { path.startsWith(it) }
    }

    /**
     * A track-playback reply (a state machine) with its ads left out, or null when it leads to no
     * ad. Measured on 20/10 (desktop, free account): a refused ad now stays current for good, and
     * every skip or play is refused while it is ("ad_disallow"); only a reload left it. Spotify's
     * state machine ties the ad to the song before it: that song's `advance` (its natural end)
     * goes to the ad, and then `advance` is sent where its `skip_next` goes, so the page plays the
     * next song at the end of this one, as after a skip. Measured: the next song started 60 ms
     * after the end, and Spotify accepted every state the page reported (200). When `skip_next`
     * leads to the ad as well (an ad already left out once), nothing here can help and the reply
     * is left as it is; an ad that is already the current state cannot be left out either.
     */
    fun withoutAds(body: String): Pair<String, List<String>>? {
        if (!AD_HINT.containsMatchIn(body)) return null
        val root = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        val notes = mutableListOf<String>()
        val out = root.toMutableMap()
        for (key in listOf("state_machine", "previous_state_machine")) {
            val machine = root[key] as? JsonObject ?: continue
            machineWithoutAds(machine, notes)?.let { out[key] = it }
        }
        if (out == root) return null
        return JsonObject(out).toString() to notes
    }

    /**
     * A song whose two ways on — its end and its skip — both lead to an ad: Spotify does that once
     * an ad was left out by [withoutAds]. [nextUri] (the song the page shows as next) is then
     * played just before the song ends: a plain play command, which Spotify answers with a new
     * queue the ad is no longer reached from. Measured on 20/10 (desktop, free account): played
     * 1 s before the end, the next song started at once, every report was answered 200, and the
     * songs after it went on without an ad.
     */
    data class AdAhead(val songUid: String?, val songUri: String?, val nextUri: String)

    /** The last [AdAhead] the host saw in Spotify's replies; the player plays on and clears it. */
    val ahead = MutableStateFlow<AdAhead?>(null)

    /** The [AdAhead] in a track-playback reply (after [withoutAds]), or null. */
    fun adAhead(body: String): AdAhead? = runCatching {
        if (!AD_HINT.containsMatchIn(body)) return null
        val root = Json.parseToJsonElement(body).jsonObject
        val machine = root["state_machine"] as? JsonObject ?: return null
        val tracks = machine["tracks"] as? JsonArray ?: return null
        val states = machine["states"] as? JsonArray ?: return null
        fun index(o: JsonObject?, key: String) = (o?.get(key) as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
        fun trackOf(state: Int) = index(states.getOrNull(state) as? JsonObject, "track")?.let { tracks.getOrNull(it) as? JsonObject }
        fun isAd(state: Int) = isAdTrack(trackOf(state))
        fun uriOf(state: Int) = ((trackOf(state)?.get("metadata") as? JsonObject)?.get("uri") as? JsonPrimitive)?.contentOrNull
        val current = index(root["updated_state_ref"] as? JsonObject, "state_index") ?: return null
        if (isAd(current)) return null
        val state = states.getOrNull(current) as? JsonObject ?: return null
        val transitions = state["transitions"] as? JsonObject ?: return null
        fun to(key: String) = index(transitions[key] as? JsonObject, "state_index")
        val end = to("advance") ?: return null
        if (!isAd(end) || to("skip_next")?.let { !isAd(it) } == true) return null
        val next = to("show_next")?.takeIf { !isAd(it) }?.let(::uriOf) ?: return null
        AdAhead((state["track_uid"] as? JsonPrimitive)?.contentOrNull, uriOf(current), next)
    }.getOrNull()

    /**
     * Something in a reply that may be an ad: Spotify's `content_type` "AD", or the markers its
     * metadata carries (the same ones the host's relay and the bridge's `isAd` look for). Only a
     * pre-check: [isAdTrack] decides, on the track itself.
     */
    private val AD_HINT = Regex("\"AD\"|spotify:ad:|\"is_advertisement\"|\"ad_id\"")

    /**
     * A track of a state machine that is an ad: `content_type` "AD", or metadata flagging it
     * (`is_advertisement` true, an `spotify:ad:` uri, an `ad_id`). Seen in every capture so far:
     * all four on each ad, none on a song; a song whose name or album merely says "ad" is no ad.
     */
    fun isAdTrack(track: JsonObject?): Boolean {
        track ?: return false
        if ((track["content_type"] as? JsonPrimitive)?.contentOrNull == "AD") return true
        val metadata = track["metadata"] as? JsonObject ?: return false
        val flag = (metadata["is_advertisement"] as? JsonPrimitive)?.contentOrNull
        if (flag.equals("true", ignoreCase = true)) return true
        if ((metadata["uri"] as? JsonPrimitive)?.contentOrNull?.startsWith("spotify:ad:") == true) return true
        return !(metadata["ad_id"] as? JsonPrimitive)?.contentOrNull.isNullOrBlank()
    }

    /**
     * Why [withoutAds] left a reply that looks like it holds an ad as it was, for the log: what
     * marked it, and what kept it (the ad is the song now, no song to send its way to, no ad track
     * at all). Null when it does not look like one.
     */
    fun whyKept(body: String): String? = runCatching {
        val marker = AD_HINT.find(body)?.value?.trim('"') ?: return null
        val root = Json.parseToJsonElement(body).jsonObject
        val machine = root["state_machine"] as? JsonObject ?: return "$marker: no state machine in the reply"
        val tracks = machine["tracks"] as? JsonArray ?: return "$marker: no tracks"
        val states = machine["states"] as? JsonArray ?: return "$marker: no states"
        fun index(o: JsonObject?, key: String) = (o?.get(key) as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
        fun isAd(state: Int) = isAdTrack(index(states.getOrNull(state) as? JsonObject, "track")?.let { tracks.getOrNull(it) as? JsonObject })
        if (tracks.none { isAdTrack(it as? JsonObject) }) return "$marker: no ad track (the marker is elsewhere in the reply)"
        index(root["updated_state_ref"] as? JsonObject, "state_index")?.let { if (isAd(it)) return "$marker: the ad is the current song" }
        val blocked = states.indices.any { i ->
            val t = (states[i] as? JsonObject)?.get("transitions") as? JsonObject ?: return@any false
            !isAd(i) && listOf("advance", "skip_next").all { k -> index(t[k] as? JsonObject, "state_index")?.let(::isAd) == true }
        }
        if (blocked) "$marker: the song before the ad has no other way on (its skip leads to the ad too)"
        else "$marker: no song leads to the ad"
    }.getOrElse { "not readable: ${it.javaClass.simpleName}" }

    private fun machineWithoutAds(machine: JsonObject, notes: MutableList<String>): JsonObject? {
        val tracks = machine["tracks"] as? JsonArray ?: return null
        val states = machine["states"] as? JsonArray ?: return null
        fun trackOf(state: Int) = ((states.getOrNull(state) as? JsonObject)?.get("track") as? JsonPrimitive)
            ?.contentOrNull?.toIntOrNull()?.let { tracks.getOrNull(it) as? JsonObject }
        fun isAd(state: Int) = isAdTrack(trackOf(state))
        fun nameOf(state: Int) = ((trackOf(state)?.get("metadata") as? JsonObject)?.get("name") as? JsonPrimitive)?.contentOrNull
        fun indexOf(transition: JsonObject) = (transition["state_index"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
        fun uriOf(state: Int) = (trackOf(state)?.get("metadata") as? JsonObject)?.get("uri")?.let { (it as? JsonPrimitive)?.contentOrNull }
        fun repeatsTrack(state: Int) = ((states.getOrNull(state) as? JsonObject)?.get("options") as? JsonObject)
            ?.get("repeating_track")?.let { (it as? JsonPrimitive)?.contentOrNull } == "true"
        // Repeat one: the song's `advance` is its own repeat (the same song, still repeating), and its
        // `skip_next` is the next song with repeat off (captured 21/10). An ad in between leaves the
        // song's end going to the ad: sent to the skip's target it ended the repeat after a few loops
        // (reported: "loops 2-3 times then plays the next song"). The repeat's own state is the playable
        // one of the same song (it has a show_next; the display-only ones have none).
        fun repeatOf(i: Int): Int? = states.indices.firstOrNull { j ->
            j != i && !isAd(j) && repeatsTrack(j) && uriOf(j) != null && uriOf(j) == uriOf(i) &&
                ((states[j] as? JsonObject)?.get("transitions") as? JsonObject)?.get("show_next") is JsonObject
        }
        var changed = false
        val rewritten = states.mapIndexed { i, element ->
            val state = element as? JsonObject ?: return@mapIndexed element
            val transitions = state["transitions"] as? JsonObject ?: return@mapIndexed element
            if (isAd(i)) return@mapIndexed element
            // Only a skip is a state the server knows: `show_next` points at a display-only state,
            // and a page sent there had every report refused (502 unknown_state_ref) and stopped.
            val target = (transitions["skip_next"] as? JsonObject)?.takeIf { t -> indexOf(t)?.let { !isAd(it) } == true }
            val next = transitions.toMutableMap()
            for (key in listOf("advance", "skip_next")) {
                val t = transitions[key] as? JsonObject ?: continue
                if (indexOf(t)?.let(::isAd) != true) continue
                val again = if (key == "advance" && repeatsTrack(i)) repeatOf(i) else null
                if (again != null) {
                    next[key] = JsonObject(t.toMutableMap().apply { put("state_index", JsonPrimitive(again)) })
                    notes += "$key after ${nameOf(i)}: repeated instead of an ad"
                    continue
                }
                if (target == null) { notes += "$key after ${nameOf(i)}: an ad, and no song to send it to"; continue }
                next[key] = JsonObject(target.toMutableMap().apply { t["paused"]?.let { put("paused", it) } })
                notes += "$key after ${nameOf(i)}: ${indexOf(target)?.let(::nameOf)} instead of an ad"
            }
            if (next == transitions) element else { changed = true; JsonObject(state.toMutableMap().apply { put("transitions", JsonObject(next)) }) }
        }
        return if (changed) JsonObject(machine.toMutableMap().apply { put("states", JsonArray(rewritten)) }) else null
    }

    /** The player's registration (POST track-playback/v1/devices), to rewrite with [withDeviceName]. */
    fun isDeviceRegistration(method: String, url: String): Boolean =
        method.equals("POST", ignoreCase = true) && url.substringBefore('?').endsWith("/track-playback/v1/devices")

    /**
     * The name the player registers itself under is the name Spotify shows on every other device.
     * The Web Player's own ("Web Player (Chrome)") is replaced by the name the phone already has,
     * so this client appears as the phone it runs on; **nothing else is touched** — capabilities,
     * platform, versions stay byte-for-byte what the real Web Player sends.
     *
     * An earlier version also removed `manifest_urls_audio_ad` from the manifest formats, which made
     * this registration the only one of its kind (the real one declares it twice) and was the prime
     * suspect for the `402 COMMAND_FAILED` refusals. Ads are blocked at the URL level instead
     * ([blockedHosts]), not by lying about what this client can play.
     */
    fun withDeviceName(body: String, name: String): String = runCatching {
        val device = Json.parseToJsonElement(body).jsonObject["device"]?.jsonObject ?: return body
        val raw = device["name"] as? kotlinx.serialization.json.JsonPrimitive ?: return body
        if (raw is kotlinx.serialization.json.JsonNull) return body
        val old = raw.content
        if (old.isBlank() || old == name || name.isBlank()) return body
        // A plain string swap, not a re-serialisation: the registration keeps its exact shape,
        // duplicate array entries included.
        val quoted = name.replace("\\", "\\\\").replace("\"", "\\\"")
        body.replace("\"$old\"", "\"$quoted\"")
    }.getOrDefault(body)
}

/**
 * The media manifests Spotify sent the player: each song of a track-playback reply's state
 * machines, its `manifest` (formats and file ids) as it came. Read by extensions
 * (`spotify.media`), never changed; the last [MAX] songs are kept.
 */
object MediaManifests {
    private const val MAX = 200
    private val byUri = object : LinkedHashMap<String, JsonObject>(MAX, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, JsonObject>?) = size > MAX
    }

    /** Keeps the manifests of a track-playback reply's songs. */
    fun record(body: String) {
        if ("\"manifest\"" !in body) return
        val root = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return
        for (key in listOf("state_machine", "previous_state_machine")) {
            val tracks = (root[key] as? JsonObject)?.get("tracks") as? JsonArray ?: continue
            tracks.forEach { t ->
                val track = t as? JsonObject ?: return@forEach
                val uri = ((track["metadata"] as? JsonObject)?.get("uri") as? JsonPrimitive)?.contentOrNull ?: return@forEach
                val manifest = track["manifest"] as? JsonObject ?: return@forEach
                if (uri.startsWith("spotify:track:") && manifest.isNotEmpty()) synchronized(byUri) { byUri[uri] = manifest }
            }
        }
    }

    fun of(trackUri: String): JsonObject? = synchronized(byUri) { byUri[trackUri] }
}
