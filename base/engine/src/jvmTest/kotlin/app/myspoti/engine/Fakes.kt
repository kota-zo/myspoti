package app.myspoti.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Anonymised fixtures built from the play-bodies (2026-11-10) and real1 (2026-10-26) captures. */
object Fixtures {
    fun bytes(name: String): ByteArray =
        Fixtures::class.java.getResourceAsStream("/fixtures/$name")?.readBytes() ?: error("missing fixture $name")

    fun text(name: String) = bytes(name).decodeToString()
    fun json(name: String): JsonObject = Json.parseToJsonElement(text(name)).jsonObject
}

/** Recursive key paths of a JSON value: what "field for field" means in these tests. */
fun keyPaths(e: JsonElement, prefix: String = ""): Set<String> = when (e) {
    is JsonObject -> e.flatMap { (k, v) -> setOf("$prefix$k") + keyPaths(v, "$prefix$k.") }.toSet()
    is JsonArray -> e.firstOrNull()?.let { keyPaths(it, "$prefix[].") } ?: emptySet()
    else -> emptySet()
}

class FakeEnv(var now: Long = 1_794_268_055_580L) : Environment {
    private var counter = 0
    override fun nowMs() = now
    override fun randomHex(chars: Int): String {
        counter++
        return counter.toString(16).padStart(chars, 'a').take(chars)
    }
}

class FakeTokens : TokenSource {
    var refreshes = 0
    var generation = 1
    override suspend fun accessToken(forceRefresh: Boolean): AccessToken {
        if (forceRefresh) { refreshes++; generation++ }
        return AccessToken("test-token-$generation", 4_000_000_000_000L, "0123456789abcdef0123456789abcdef")
    }
    override suspend fun clientToken() = ClientToken("test-client-token", "00000000-0000-4000-a000-000000000000")
}

class FakeSocket : WebSocketConnection {
    val incoming = Channel<String>(Channel.UNLIMITED)
    val sent = ArrayList<String>()
    override suspend fun receive(): String? = incoming.receiveCatching().getOrNull()
    override suspend fun send(text: String) { sent += text }
    override fun close() { incoming.close() }
}

class FakeSockets : WebSockets {
    val socket = FakeSocket().also { it.incoming.trySend(Fixtures.text("dealer-connection.json")) }
    var url = ""
    override suspend fun connect(url: String, headers: Map<String, String>): WebSocketConnection { this.url = url; return socket }
}

class FakePlayer : DrmPlayer {
    val calls = ArrayList<String>()
    var onEvent: ((PlayerEvent) -> Unit)? = null
    var loaded: PlayableMedia? = null
    val preloaded = ArrayList<PlayableMedia>()
    var licence: LicenceChannel? = null
    override fun setListener(listener: (PlayerEvent) -> Unit) { onEvent = listener }
    override fun load(media: PlayableMedia, positionMs: Long, playWhenReady: Boolean, licence: LicenceChannel) {
        calls += "load ${media.trackUri} @$positionMs play=$playWhenReady"
        loaded = media
        this.licence = licence
    }
    override fun preload(media: PlayableMedia, licence: LicenceChannel) { calls += "preload ${media.trackUri}"; preloaded += media; this.licence = licence }
    override fun pause() { calls += "pause" }
    override fun resume() { calls += "resume" }
    override fun seekTo(positionMs: Long) { calls += "seek $positionMs" }
    override fun stop() { calls += "stop" }
    override fun setVolume(volume: Int) { calls += "volume $volume" }
    override fun setMuted(muted: Boolean) { calls += "muted $muted" }
    override fun metrics() = PlaybackMetrics(totalBytes = 2_542_290, firstBytesLatencyMs = 110)
}

/**
 * Spotify as seen by the engine: answers every endpoint with the fixtures, and each state PUT
 * with the state machine of the last dealer push under a new id (the server's behaviour in the
 * captures). [script] lets a test answer a URL differently (401, 429) a given number of times.
 */
class FakeHttp : Http {
    val requests = ArrayList<HttpRequest>()
    var machine: JsonObject = Fixtures.json("dealer-replace-state.json")["payloads"]!!.jsonArray[0].jsonObject["state_machine"]!!.jsonObject
    private val script = ArrayList<Pair<Regex, HttpResponse>>()
    private var putCount = 0

    fun failNext(urlPart: String, status: Int, headers: Map<String, String> = emptyMap(), times: Int = 1) =
        repeat(times) { script += Regex(Regex.escape(urlPart)) to HttpResponse(status, headers, "{}".encodeToByteArray()) }

    /** Holds the answer to a state PUT whose debug_source is the key until the gate opens (a slow server). */
    val gates = HashMap<String, kotlinx.coroutines.CompletableDeferred<Unit>>()
    /** The requests in the order their answers came back. */
    val answered = ArrayList<HttpRequest>()

    override suspend fun request(request: HttpRequest): HttpResponse {
        requests += request
        if (request.method == "PUT" && request.url.endsWith("/state")) {
            val source = json(request)["debug_source"]?.jsonPrimitive?.content
            gates[source]?.await()
        }
        return answer(request).also { answered += request }
    }

    private fun answer(request: HttpRequest): HttpResponse {
        script.firstOrNull { it.first.containsMatchIn(request.url) }?.let { script.remove(it); return it.second }
        val u = request.url
        fun ok(name: String) = HttpResponse(200, emptyMap(), Fixtures.bytes(name))
        return when {
            u.startsWith("https://apresolve.spotify.com/") -> ok("apresolve.json")
            u.endsWith("/track-playback/v1/devices") -> ok("register-response.json")
            "/connect-state/v1/devices/hobs_" in u -> HttpResponse(200, emptyMap(), "{}".encodeToByteArray())
            u.endsWith("/state") -> stateAnswer(request)
            u.endsWith("/volume") -> HttpResponse(204, emptyMap(), ByteArray(0))
            "/storage-resolve/" in u -> ok("storage-resolve.json")
            u.startsWith(EngineConfig.SEEKTABLES) -> ok("seektable.json")
            "/melody/v1/license_url" in u -> ok("license-url.json")
            "/widevine-license/v1/application-certificate" in u -> ok("certificate.bin")
            "/widevine-license/v1/audio/license" in u -> ok("licence-response.bin")
            "/melody/v1/msg/batch" in u -> HttpResponse(202, emptyMap(), """{"status":202}""".encodeToByteArray())
            "/connect-state/v1/player/command/" in u || "/connect-state/v1/connect/transfer/" in u ->
                HttpResponse(200, emptyMap(), """{"ack_id":"test-ack"}""".encodeToByteArray())
            "/track-playback/v1/media/" in u -> ok("media-response.json")
            else -> HttpResponse(404, emptyMap(), ByteArray(0))
        }
    }

    private fun stateAnswer(r: HttpRequest): HttpResponse {
        val body = Json.parseToJsonElement(r.body!!.decodeToString()).jsonObject
        val stateId = (body["state_ref"] as? JsonObject)?.get("state_id")?.jsonPrimitive?.content
        val states = machine["states"]!!.jsonArray
        val index = states.indexOfFirst { it.jsonObject["state_id"]!!.jsonPrimitive.content == stateId }
        // The server's transitions follow the reported play state (paused in the answer to a paused
        // report, playing otherwise: play-bodies PUT answers 1 and 2).
        val paused = (body["state_ref"] as? JsonObject)?.get("paused")?.jsonPrimitive?.content == "true"
        val sm = JsonObject(
            machine + mapOf(
                "state_machine_id" to JsonPrimitive("ChTresponse${++putCount}"),
                "states" to JsonArray(states.map { st -> withTransitionsPaused(st.jsonObject, paused) }),
            ),
        )
        val answer = buildJsonObject {
            put("state_machine", sm)
            put("updated_state_ref", if (index < 0) JsonNull else buildJsonObject { put("state_index", index); put("paused", false) })
        }
        return HttpResponse(200, emptyMap(), answer.toString().encodeToByteArray())
    }

    private fun withTransitionsPaused(state: JsonObject, paused: Boolean): JsonObject {
        val t = state["transitions"]!!.jsonObject.mapValues { (_, v) ->
            if (v is JsonObject) JsonObject(v + ("paused" to JsonPrimitive(paused))) else v
        }
        return JsonObject(state + ("transitions" to JsonObject(t)))
    }

    // -- helpers for assertions
    fun json(r: HttpRequest): JsonObject = Json.parseToJsonElement(r.body!!.decodeToString()).jsonObject
    fun statePuts() = requests.filter { it.method == "PUT" && it.url.endsWith("/state") }.map { json(it) }
    fun debugSources() = statePuts().map { it["debug_source"]!!.jsonPrimitive.content }
    fun melodyTypes() = requests.filter { "/melody/v1/msg/batch" in it.url }
        .map { r -> json(r)["messages"]!!.jsonArray.map { it.jsonObject["type"]!!.jsonPrimitive.content } }
    fun count(urlPart: String) = requests.count { urlPart in it.url }
}

/** The engine wired to fakes, plus helpers that play the dealer and the player's part. */
class Rig(scope: CoroutineScope, config: EngineConfig = EngineConfig()) {
    val http = FakeHttp()
    val tokens = FakeTokens()
    val sockets = FakeSockets()
    val player = FakePlayer()
    val env = FakeEnv()
    val engine = NativeEngine(http, tokens, sockets, player, env, config, scope)
    val events = ArrayList<EngineEvent>()

    private val frame = Fixtures.json("dealer-replace-state.json")
    val payload: JsonObject = frame["payloads"]!!.jsonArray[0].jsonObject
    val machine: JsonObject = payload["state_machine"]!!.jsonObject
    val states: JsonArray = machine["states"]!!.jsonArray
    fun stateId(i: Int) = states[i].jsonObject["state_id"]!!.jsonPrimitive.content
    fun advanceOf(i: Int) = states[i].jsonObject["transitions"]!!.jsonObject["advance"]!!.jsonObject["state_index"]!!.jsonPrimitive.int
    fun trackUriOf(i: Int): String {
        val t = states[i].jsonObject["track"]!!.jsonPrimitive.int
        return machine["tracks"]!!.jsonArray[t].jsonObject["metadata"]!!.jsonObject["uri"]!!.jsonPrimitive.content
    }

    /** A dealer `replace_state` push of the fixture machine, at [index], [paused], with [seekTo]. */
    fun replaceState(index: Int, paused: Boolean, seekTo: Long? = null, machineId: String = "ChTdealer$index$paused$seekTo"): String {
        val sm = JsonObject(machine + ("state_machine_id" to JsonPrimitive(machineId)))
        val p = JsonObject(
            payload + mapOf(
                "state_machine" to sm,
                "state_ref" to buildJsonObject { put("state_index", index); put("paused", paused); put("active_alias", JsonNull) },
                "seek_to" to (seekTo?.let { JsonPrimitive(it) } ?: JsonNull),
            ),
        )
        return JsonObject(frame + ("payloads" to JsonArray(listOf(p)))).toString()
    }

    /** What the server pushes when playback moves to another device: empty machine, empty state_ref. */
    fun clearState(): String {
        val p = JsonObject(
            payload + mapOf(
                "state_machine" to buildJsonObject { put("state_machine_id", "ChTclear"); put("tracks", JsonArray(emptyList())); put("states", JsonArray(emptyList())) },
                "state_ref" to JsonObject(emptyMap()),
                "seek_to" to JsonNull,
            ),
        )
        return JsonObject(frame + ("payloads" to JsonArray(listOf(p)))).toString()
    }

    suspend fun start() = engine.start()

    /** The player plays from [from] to [to] in 500 ms steps of wall clock and position. */
    suspend fun playFor(from: Long, to: Long) {
        var pos = from
        while (pos < to) {
            pos = minOf(pos + 500, to)
            env.now += 500
            engine.handlePlayerEvent(PlayerEvent.Position(pos))
        }
    }
}
