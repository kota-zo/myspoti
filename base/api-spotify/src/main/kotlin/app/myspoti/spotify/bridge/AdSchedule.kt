package app.myspoti.spotify.bridge

import app.myspoti.spotify.AccountPlan
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Where the next ad comes, read from what Spotify sends the page: its server puts audio ads in
 * the player's queue, the track-playback state machine it sends about three minutes ahead, where
 * the song before the ad `advance`s to an ad state at its end. Read only, never changed: the
 * listen sync holds its mirror over the break and puts the local player's song back on the page
 * the moment the break is over (see `SpotifyListenSync`).
 */
object AdSchedule {
    /**
     * An ad comes at the end of the song [songUri] (its queue entry [songUid] when known), the page's
     * song as the reply was sent; [nextUri]: the song after the break, when the machine says.
     */
    data class Due(val songUid: String?, val songUri: String?, val nextUri: String?)

    private val _due = MutableStateFlow<Due?>(null)

    /** The ad the page's last state machine puts after its song, or null (none, or not known). */
    val due: StateFlow<Due?> = _due.asStateFlow()

    /** A track-playback reply the page got: [due] follows its state machine (a reply without one changes nothing). */
    fun record(body: String) {
        if (AccountPlan.isPremium) { _due.value = null; return }
        if ("\"state_machine\"" !in body || "\"updated_state_ref\"" !in body) return
        _due.value = dueIn(body)
    }

    /** The page went away: its queue with it. */
    fun clear() { _due.value = null }

    /** The [Due] in a track-playback reply: the ad its current song's end leads to, or null. */
    fun dueIn(body: String): Due? = runCatching {
        if (!AD_HINT.containsMatchIn(body)) return null
        val root = Json.parseToJsonElement(body).jsonObject
        val machine = root["state_machine"] as? JsonObject ?: return null
        val tracks = machine["tracks"] as? JsonArray ?: return null
        val states = machine["states"] as? JsonArray ?: return null
        fun index(o: JsonObject?, key: String) = (o?.get(key) as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
        fun stateAt(i: Int) = states.getOrNull(i) as? JsonObject
        fun trackOf(i: Int) = index(stateAt(i), "track")?.let { tracks.getOrNull(it) as? JsonObject }
        fun isAd(i: Int) = isAdTrack(trackOf(i))
        fun uriOf(i: Int) = ((trackOf(i)?.get("metadata") as? JsonObject)?.get("uri") as? JsonPrimitive)?.contentOrNull
        fun way(i: Int, key: String) = index((stateAt(i)?.get("transitions") as? JsonObject)?.get(key) as? JsonObject, "state_index")
        val current = index(root["updated_state_ref"] as? JsonObject, "state_index") ?: return null
        if (isAd(current)) return null
        val end = way(current, "advance") ?: return null
        if (!isAd(end)) return null
        // Through the break (an ad or two) to the first song after it; else the song the page
        // shows as next (its skip or its shown next, when not the ad).
        var after: Int? = end
        var hops = 0
        while (after != null && isAd(after) && hops++ < MAX_ADS_IN_A_ROW) after = way(after, "advance")
        val next = after?.takeIf { !isAd(it) } ?: listOf("skip_next", "show_next").firstNotNullOfOrNull { k -> way(current, k)?.takeIf { !isAd(it) } }
        Due((stateAt(current)?.get("track_uid") as? JsonPrimitive)?.contentOrNull, uriOf(current), next?.let(::uriOf))
    }.getOrNull()

    /**
     * Something in a reply that may be an ad: Spotify's `content_type` "AD", or the markers its
     * metadata carries (the same ones the bridge's `isAd` looks for). Only a pre-check:
     * [isAdTrack] decides, on the track itself.
     */
    private val AD_HINT = Regex("\"AD\"|spotify:ad:|\"is_advertisement\"|\"ad_id\"")

    /** More ads than this in one break are not followed (a machine that loops). */
    private const val MAX_ADS_IN_A_ROW = 8

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
}
