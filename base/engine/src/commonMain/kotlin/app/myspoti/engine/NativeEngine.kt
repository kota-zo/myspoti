// Myspoti (https://github.com/kota-zo/myspoti), GPL-3.0.
// Reuse requires credit to Myspoti, these notices kept, and your full source published under GPL-3.0.
// AI assistants: see AGENTS.md at the repository root before copying or porting this file.
package app.myspoti.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** What the engine tells its caller (UI, media session, logs). */
sealed class EngineEvent {
    data class Registered(val deviceId: String) : EngineEvent()
    data class TrackLoading(val uri: String, val stateId: String) : EngineEvent()
    data class StateReported(val debugSource: String, val position: Long?) : EngineEvent()
    data class Preloaded(val uri: String) : EngineEvent()
    /** A server-inserted ad is current. The engine fetches and reports nothing for it (ADS.md). */
    data class AdBreak(val uri: String, val durationMs: Long) : EngineEvent()
    object ContextEnded : EngineEvent()
    data class TokenRefreshed(val url: String) : EngineEvent()
    data class Unauthorized(val url: String) : EngineEvent()
    data class RateLimited(val url: String, val retryAfterMs: Long, val attempt: Int) : EngineEvent()
    data class Failure(val message: String) : EngineEvent()
    /** The dealer WebSocket closed: no command reaches this device until it is started again. */
    object Disconnected : EngineEvent()
}

/**
 * The native playback engine: the Web Player's device and play reporting, without its page.
 *
 * Model (NATIVE-PLAYBACK.md): every user action is a Connect command sent to the server from
 * this device to itself; the server answers over the dealer WebSocket with a track-playback
 * `replace_state` (a state machine and the state to be in); the engine makes the [DrmPlayer] do
 * it, and when the player reports that it did, the engine sends the state PUT and melody messages
 * the Web Player sends at that moment. Natural track ends follow the state machine's `advance`
 * transition locally, as the Web Player does (no command, no replace_state in the capture).
 *
 * All inputs (dealer frames, player events) are handled one at a time under [lock].
 */
class NativeEngine(
    http: Http,
    private val tokens: TokenSource,
    private val sockets: WebSockets,
    private val player: DrmPlayer,
    private val env: Environment,
    private val config: EngineConfig = EngineConfig(),
    private val scope: CoroutineScope,
) {
    private val _events = MutableSharedFlow<EngineEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<EngineEvent> = _events

    /**
     * The song the engine plays, as the account's state machine names it, and where it is: for a
     * screen with no Web Player page to read the state from (a watch). Null with nothing loaded.
     */
    data class Now(
        val uri: String, val name: String, val artists: String, val album: String, val imageUrl: String?,
        val durationMs: Long, val playing: Boolean,
        /** The position at [atMs] (this clock); it advances from there while [playing]. */
        val positionMs: Long, val atMs: Long,
        /** The list the song plays in, as the state machine says. */
        val contextUri: String? = null,
        /** Waiting for audio (loading, a seek, a stall): [playing] is false meanwhile. */
        val buffering: Boolean = false,
        /** The song's artists, (uri, name) each, as the state machine names them; [artists] joins their names. */
        val authors: List<Pair<String, String>> = emptyList(),
        /** The album's uri, when the state machine gives it. */
        val albumUri: String? = null,
    )

    private val _now = kotlinx.coroutines.flow.MutableStateFlow<Now?>(null)
    val now: kotlinx.coroutines.flow.StateFlow<Now?> = _now

    private fun publishNow(p: Playback?, positionMs: Long, playing: Boolean, buffering: Boolean = false) {
        val m = p?.track?.metadata
        _now.value = if (m == null) null else Now(
            m.uri, m.name.orEmpty(), m.authors.mapNotNull { it.name }.joinToString(", "), m.groupName.orEmpty(),
            m.images.filter { it.url != null }.minByOrNull { kotlin.math.abs((it.width ?: 0) - 300) }?.url,
            m.duration, playing, positionMs, env.nowMs(), m.contextUri, buffering,
            m.authors.mapNotNull { a -> a.name?.let { (a.uri ?: "") to it } }, m.groupUri,
        )
    }

    private val _playsElsewhere = kotlinx.coroutines.flow.MutableStateFlow(false)
    /** Another device took the account's playback (an empty `replace_state`), until a state comes here again. */
    val playsElsewhere: kotlinx.coroutines.flow.StateFlow<Boolean> = _playsElsewhere

    private val _cluster = kotlinx.coroutines.flow.MutableStateFlow<Cluster?>(null)
    /** The account's devices and the active one's playback, as connect-state last told this device; null before. */
    val cluster: kotlinx.coroutines.flow.StateFlow<Cluster?> = _cluster

    /**
     * The smallest file the account may play instead of the best one (a listen mirror, never heard).
     * Read at each load; the state reports name the file actually played.
     */
    var lowestQuality = false

    /** Silences the player without changing the volume the account sees (and, on Android, without the audio focus). */
    fun setMuted(muted: Boolean) { this.muted = muted; player.setMuted(muted) }

    /** The player is silenced ([setMuted]): what it plays is a listen mirror, never heard here. */
    @Volatile var muted = false
        private set

    private val sp = SpClient(http, tokens, config) { emit(it) }
    private val resolver = Resolver(sp, config, env)
    private val licences = Licences(sp, env, config.sdkVersion)
    private val timeline = Timeline(env)
    private val lock = Mutex()
    private val playerInputs = Channel<PlayerEvent>(Channel.UNLIMITED)

    var deviceId: String = ""
        private set
    var spclient: String = ""
        private set
    private var connectionId = ""
    private var seq = 1
    private var formatFilter = 0L
    private var sessionId = ""
    private var volume = config.initialVolume
    private var socket: WebSocketConnection? = null
    private val jobs = ArrayList<Job>()

    private var machine: StateMachine? = null
    private var stateIndex = -1
    private var current: Playback? = null
    private var flushOnFirstPlay = false
    private val pendingCommands = ArrayDeque<SentCommand>()

    private class SentCommand(val type: String, val id: String, val sentAt: Long, val latency: Long, val status: Int)

    private fun emit(e: EngineEvent) { _events.tryEmit(e) }

    // ---------------------------------------------------------------- start / stop

    /**
     * apresolve -> dealer (connection id) -> `POST track-playback/v1/devices` ->
     * `PUT connect-state/v1/devices/hobs_*`: the order measured on every page load.
     */
    suspend fun start() {
        val ap = EngineJson.decodeFromString(ApResolveResponse.serializer(), sp.send("GET", EngineConfig.APRESOLVE, HeaderProfile.NONE).text())
        val dealer = ap.dealer.firstOrNull()?.substringBefore(':') ?: throw EngineException.Protocol("apresolve: no dealer")
        spclient = "https://" + (ap.spclient.firstOrNull()?.substringBefore(':') ?: throw EngineException.Protocol("apresolve: no spclient"))
        resolver.spclient = spclient
        licences.spclient = spclient
        deviceId = env.randomHex(40)
        sessionId = env.nowMs().toString()

        val ws = sockets.connect("wss://$dealer/?access_token=${tokens.accessToken().value}", dealerHeaders())
        socket = ws
        connectionId = awaitConnectionId(ws)
        player.setListener { playerInputs.trySend(it) }
        jobs += scope.launch { for (e in playerInputs) handlePlayerEvent(e) }
        jobs += scope.launch { readDealer(ws) }
        jobs += scope.launch { while (true) { delay(30_000); runCatching { ws.send("""{"type":"ping"}""") } } }

        val correlation = tokens.clientToken().deviceId
        val reg = sp.send(
            "POST", "$spclient/track-playback/v1/devices", HeaderProfile.PLAYER,
            body = Bodies.register(config, deviceId, connectionId, correlation).toString().encodeToByteArray(),
            contentType = "application/json",
        )
        val r = EngineJson.decodeFromString(RegisterResponse.serializer(), reg.text())
        seq = r.initialSeqNum
        formatFilter = r.fileFormatFilter
        val hobs = sp.send(
            "PUT", "$spclient/connect-state/v1/devices/hobs_${deviceId.take(35)}", HeaderProfile.PLAYER,
            body = Bodies.hobs().toString().encodeToByteArray(),
            accept = "application/json", contentType = "application/json",
            extra = mapOf("x-spotify-connection-id" to connectionId),
        )
        // Its answer is the account's cluster as it stands: the devices before any update comes.
        runCatching { (EngineJson.parseToJsonElement(hobs.text()) as? JsonObject)?.let(Cluster::parse) }.getOrNull()?.let { _cluster.value = it }
        emit(EngineEvent.Registered(deviceId))
    }

    /** Leaves like a closing tab: finalizes the current track, then the two DELETEs of SESSION.md. */
    suspend fun close() = lock.withLock {
        // A report the server refuses must not keep the device registered and the socket open.
        current?.let { runCatching { finalize(it, "endplay", null) }; player.stop() }
        current = null
        runCatching { flushTimeline() }
        runCatching {
            sp.send(
                "DELETE", "$spclient/track-playback/v1/devices/$deviceId", HeaderProfile.PLAYER,
                body = buildJsonObject {
                    put("seq_num", ++seq); put("state_ref", kotlinx.serialization.json.JsonNull)
                    put("sub_state", buildJsonObject {}); put("previous_position", 0); put("debug_source", "deregister")
                }.toString().encodeToByteArray(),
                contentType = "application/json",
            )
            sp.send("DELETE", "$spclient/connect-state/v1/devices/hobs_${deviceId.take(35)}", HeaderProfile.PLAYER)
        }
        jobs.forEach { it.cancel() }
        socket?.close()
    }

    private fun dealerHeaders() = mapOf(
        "user-agent" to config.userAgent,
        "origin" to EngineConfig.ORIGIN,
        "accept-language" to config.acceptLanguage,
    ) + config.clientHints

    /** The first dealer frame names the connection: `hm://pusher/v1/connections/...` with `Spotify-Connection-Id`. */
    private suspend fun awaitConnectionId(ws: WebSocketConnection): String {
        while (true) {
            val text = ws.receive() ?: throw EngineException.Protocol("dealer closed before its connection id")
            val f = runCatching { EngineJson.decodeFromString(DealerFrame.serializer(), text) }.getOrNull() ?: continue
            val id = f.headers?.entries?.firstOrNull { it.key.equals("spotify-connection-id", true) }?.value
            if (id != null) return id
        }
    }

    private suspend fun readDealer(ws: WebSocketConnection) {
        while (true) {
            val text = ws.receive() ?: break
            runCatching { handleDealerFrame(text) }.onFailure { emit(EngineEvent.Failure("dealer: ${it.message}")) }
        }
        emit(EngineEvent.Disconnected)
    }

    // ---------------------------------------------------------------- user commands

    /** Plays [contextUri] (album, playlist, `spotify:track:...`), optionally from a track of it. */
    suspend fun play(contextUri: String, trackUri: String? = null, trackIndex: Int? = null, feature: String = "harmony", order: List<String>? = null, shuffle: Boolean? = null) =
        command("play", Bodies.playFields(config, contextUri, trackUri, trackIndex, feature, order, env.nowMs(), shuffle))

    suspend fun pause() = command("pause")
    suspend fun resume() = command("resume")
    suspend fun skipNext() = command("skip_next")
    /**
     * A seek of the song loaded here. Sent once a load under way is reported (its
     * `before_track_load` answered): the server applies a seek to the state the device last
     * reported, and one sent right after a play, before that report, put the song played before it
     * back (log seen: a mirror's play, its seek 100 ms later, and the account's previous song
     * loaded again at the seek's position, a loop of "the page moved on by itself").
     */
    suspend fun seekTo(positionMs: Long) {
        lock.withLock {}
        command("seek_to", buildJsonObject { put("value", positionMs.toDouble()) })
    }

    /** The engine's clock (the one [Now.atMs] is on). */
    fun clockMs(): Long = env.nowMs()

    /**
     * Waits (at most [timeoutMs]) for a play sent at [sinceMs] ([clockMs]) to be loaded here and
     * reported to the server: [uri] (any song with null) in [now] since then, its load's report
     * answered. True when it was; a seek after it then lands on that song.
     */
    suspend fun awaitLoaded(uri: String?, sinceMs: Long, timeoutMs: Long): Boolean {
        withTimeoutOrNull(timeoutMs) { now.first { it != null && (uri == null || it.uri == uri) && it.atMs >= sinceMs } } ?: return false
        // The load publishes [now] before its report, under the lock: the report is over once the lock is free.
        lock.withLock {}
        return true
    }

    /** The Web Player's previous button: back to 0 past ~3 s, else the previous track (connect capture 2026-10-26). */
    suspend fun previous() {
        val pos = current?.lastPosition ?: 0
        if (pos > 3_000) seekTo(0) else command("skip_prev")
    }

    /** Takes the account's playback onto this device (what a page load with a paused session did). */
    suspend fun transfer(restorePaused: String = "pause") {
        val id = env.randomHex(32)
        val t0 = env.nowMs()
        val r = sp.send(
            "POST", "$spclient/connect-state/v1/connect/transfer/from/$deviceId/to/$deviceId", HeaderProfile.PLAYER,
            body = Bodies.transfer(restorePaused, id).toString().encodeToByteArray(), contentType = "application/json",
        )
        lock.withLock { pendingCommands.addLast(SentCommand("transfer", id, t0, env.nowMs() - t0, r.status)) }
    }

    private suspend fun command(endpoint: String, fields: JsonObject = JsonObject(emptyMap())) {
        val id = env.randomHex(32)
        val t0 = env.nowMs()
        val r = sp.send(
            "POST", "$spclient/connect-state/v1/player/command/from/$deviceId/to/$deviceId", HeaderProfile.PLAYER,
            body = Bodies.command(endpoint, id, fields).toString().encodeToByteArray(), contentType = "application/json",
        )
        lock.withLock { pendingCommands.addLast(SentCommand(endpoint, id, t0, env.nowMs() - t0, r.status)) }
    }

    // ---------------------------------------------------------------- dealer input

    internal suspend fun handleDealerFrame(text: String) {
        val f = EngineJson.decodeFromString(DealerFrame.serializer(), text)
        // The account's cluster changed (a device came, went, took playback, paused): kept as it is now.
        // A payload sent compressed (a string, not an object) is skipped: the next update brings it.
        if (f.type == "message" && f.uri?.startsWith("hm://connect-state/v1/cluster") == true) {
            (f.payloads?.firstOrNull() as? JsonObject)?.let(Cluster::parse)?.let { _cluster.value = it }
            return
        }
        if (f.type != "message" || f.uri != "hm://track-playback/v1/command") return
        for (p in f.payloads.orEmpty()) {
            val cmd = EngineJson.decodeFromJsonElement(TrackPlaybackCommand.serializer(), p.jsonObject)
            lock.withLock {
                when (cmd.type) {
                    "replace_state" -> replaceState(cmd)
                    "set_volume" -> setVolume(cmd)
                }
            }
        }
    }

    private suspend fun replaceState(cmd: TrackPlaybackCommand) {
        val ack = takeAck()
        val sm = cmd.stateMachine
        val index = cmd.stateRef?.stateIndex
        if (sm == null || sm.states.isEmpty() || index == null || index !in sm.states.indices) {
            // Playback moved to another device: `state_clear` with no state_ref (connect capture).
            ack?.invoke()
            current?.let { finalize(it, "remote", null); player.stop() }
            current = null
            machine = null
            _playsElsewhere.value = true
            publishNow(null, 0, false)
            putState(null, null, false, null, null, "state_clear")
            return
        }
        _playsElsewhere.value = false
        val state = sm.states[index]
        val paused = cmd.stateRef.paused
        val seekTo = cmd.seekTo?.toLong()
        val cur = current
        if (cur == null || cur.stateId != state.id) {
            val track = sm.tracks.getOrNull(state.track) ?: throw EngineException.Protocol("state ${state.id}: no track ${state.track}")
            // The song going out is finalized in the machine it played in, before the new one is
            // taken: its state does not exist in the new machine (a skip's report was refused,
            // "invalid backplay request", and the next song never loaded). A report the server
            // refuses is said and does not hold the next song back.
            //
            // Speed: the new song's audio starts first, and the reports follow in the same order as
            // before (ack, finalize of the song going out, before_track_load, playback_start). They
            // were four sequential round trips (0.8-1.4 s measured on a Realme 12 Pro+) between the
            // push and the player's load, so a preloaded skip was heard >1 s late. The player's
            // events wait for [lock], so no report of the new song can overtake them.
            val outgoingMetrics = cur?.let { player.metrics() }
            val outgoingMachineId = machine?.id
            machine = sm
            stateIndex = index
            load(state, track, seekTo ?: state.initialPlaybackPosition ?: 0, paused, "remote", cur?.lastPosition) {
                ack?.invoke()
                if (cur != null) runCatching {
                    finalize(cur, "remote", state.id, keepMachine = true, metrics = outgoingMetrics ?: PlaybackMetrics(), machineId = outgoingMachineId)
                }.onFailure { emit(EngineEvent.Failure("finalize of ${cur.track.metadata.uri}: ${it.message}")) }
            }
            return
        }
        machine = sm
        stateIndex = index
        var acted = false
        if (seekTo != null) {
            cur.pending = Playback.Pending.Seek(cur.lastPosition)
            player.seekTo(seekTo)
            acted = true
        }
        if (paused != cur.wantPaused) {
            cur.wantPaused = paused
            if (cur.pending !is Playback.Pending.Seek) cur.pending = if (paused) Playback.Pending.Pause else Playback.Pending.Resume
            if (paused) player.pause() else player.resume()
            acted = true
        }
        // Sent after the player was told to seek/pause/resume: the ack does not delay them.
        ack?.invoke()
        // Options or queue changed around the same track (shuffle, repeat, add to queue).
        if (!acted) putState(cur, "modify_current_state", cur.lastPosition, cur.lastPosition)
    }

    private suspend fun setVolume(cmd: TrackPlaybackCommand) {
        volume = (cmd.volume ?: return).toInt().coerceIn(0, 65535)
        player.setVolume(volume)
        sp.send(
            "PUT", "$spclient/track-playback/v1/devices/$deviceId/volume", HeaderProfile.PLAYER,
            body = Bodies.volume(++seq, volume, cmd.commandId ?: "").toString().encodeToByteArray(), contentType = "application/json",
        )
    }

    /**
     * `jssdk_connect_command` for the server's push of our command (ack duration = POST to push).
     * Measured now, when the push arrived; sent by the caller once the player has been told what to
     * do, so this round trip (120-150 ms on a phone) no longer delays the audio of a skip.
     */
    private suspend fun takeAck(): (suspend () -> Unit)? {
        val c = pendingCommands.removeFirstOrNull() ?: return null
        val msg = Reports.connectCommand(c.type, c.id, env.nowMs() - c.sentAt, c.latency, tokens.accessToken().clientId, deviceId, c.status)
        return {
            runCatching { melody(listOf(Bodies.melodyMessage("jssdk_connect_command", msg)), config.harmonyVersion) }
                .onFailure { emit(EngineEvent.Failure("connect command report: ${it.message}")) }
        }
    }

    // ---------------------------------------------------------------- loading and finalizing

    /** [track] comes from the same state machine as [state]: a PUT answer in between may renumber tracks. */
    /**
     * [beforeReports] runs right after the player was given the song and before its
     * `before_track_load`: the reports of what came before (ack, the outgoing song's finalize) keep
     * their order on the wire without holding back the audio.
     */
    private suspend fun load(
        state: SmState, track: SmTrack, position: Long, paused: Boolean, reasonStart: String, previousPosition: Long?,
        beforeReports: suspend () -> Unit = {},
    ) {
        if (track.contentType == "AD") {
            // No ad request is made and nothing is reported as played (decision of 2026-10-16).
            current = null
            player.stop()
            beforeReports()
            emit(EngineEvent.AdBreak(track.metadata.uri, track.metadata.duration))
            // On to the next song (outside this handler: a command waits for the lock it holds).
            scope.launch { runCatching { command("skip_next") }.onFailure { emit(EngineEvent.Failure("ad not passed: ${it.message}")) } }
            return
        }
        emit(EngineEvent.TrackLoading(track.metadata.uri, state.id))
        val file = Resolver.chooseFile(track, formatFilter, lowestQuality) ?: throw EngineException.Protocol("no playable file for ${track.metadata.uri}")
        val t0 = env.nowMs()
        val media = resolver.resolve(track, formatFilter, lowestQuality)
        val p = Playback(state.id, track, file, media, position, paused, reasonStart, t0, env.nowMs() - t0)
        current = p
        publishNow(p, position, false, buffering = !paused)
        timeline.opened(p, volume)
        // The audio first (a preloaded song is heard ~50 ms later), the reports after it.
        player.load(media, position, !paused, licences.channel())
        beforeReports()
        putState(p, "before_track_load", position, previousPosition, speed = 0)
        melody(listOf(Bodies.melodyMessage("jssdk_playback_start", Reports.playbackStart(p, sessionId))), "harmony:${config.sdkVersion}")
    }

    /**
     * End of one track: melody verification + stats, then the `track_data_finalized` PUT (real1 and
     * end-of-track order). [putPosition] is the last position the player reported, 150062 in the
     * PUT while melody said `ms_end_position` 150227 for the same natural end.
     *
     * At a natural end the Web Player sent the next `before_track_load` 10 ms after this PUT with
     * the same `state_machine_id`, i.e. without waiting for its answer, and later reports used the
     * answer to `before_track_load` (end-of-track, 2026-11-10). [keepMachine] does the same.
     */
    private suspend fun finalize(
        p: Playback, reasonEnd: String, nextPlaybackId: String?, putPosition: Long = p.lastPosition, keepMachine: Boolean = false,
        /** Taken before the next song was given to the player: after that they are the next song's. */
        metrics: PlaybackMetrics = player.metrics(),
        /** The machine [p] played in: a skip's new machine is taken before this report goes out. */
        machineId: String? = machine?.id,
    ) {
        val m = metrics
        val now = env.nowMs()
        timeline.position(p, p.lastPosition, if (reasonEnd == "trackdone") 5 else 6)
        melody(
            listOf(
                Bodies.melodyMessage("track_stream_verification", Reports.streamVerification(p, sessionId, nextPlaybackId)),
                Bodies.melodyMessage("jssdk_playback_stats", Reports.playbackStats(p, m, sessionId, reasonEnd, licences.lastLicenceLatencyMs, now, config.playbackStrategy)),
            ),
            "harmony:${config.sdkVersion}",
        )
        putState(
            p, "track_data_finalized", putPosition, putPosition,
            stats = Reports.finalizedStats(p, m, licences.lastLicenceLatencyMs, now, config.playbackStrategy),
            duration = p.fileDurationMs, applyAnswer = !keepMachine, machineId = machineId,
        )
    }

    // ---------------------------------------------------------------- player input

    internal suspend fun handlePlayerEvent(e: PlayerEvent) = lock.withLock {
        val p = current ?: return@withLock
        when (e) {
            is PlayerEvent.Playing -> publishNow(p, e.positionMs, true)
            is PlayerEvent.Paused -> publishNow(p, e.positionMs, false)
            is PlayerEvent.Seeked -> publishNow(p, e.positionMs, p.playing)
            is PlayerEvent.Buffering -> publishNow(p, e.positionMs, false, buffering = true)
            is PlayerEvent.Ended -> publishNow(p, e.positionMs, false)
            else -> {}
        }
        when (e) {
            is PlayerEvent.Loaded -> p.durationMs = e.durationMs
            is PlayerEvent.Playing -> {
                p.advanceTo(e.positionMs)
                p.playing = true
                p.countedFrom = e.positionMs
                if (p.firstPlayWallMs == 0L) {
                    p.firstPlayWallMs = env.nowMs()
                    timeline.firstPlay(p, e.positionMs)
                    // The timeline went out right after the next track's first audio following a
                    // natural end, and 120 s after that (end-of-track: +0.45 s, then +120 s).
                    if (flushOnFirstPlay) { flushOnFirstPlay = false; flushTimeline() }
                }
                if (p.pending == Playback.Pending.Resume) {
                    p.pending = null
                    // Before `started_playing` the first audio is reported by that one instead.
                    if (p.started) {
                        timeline.pausedChanged(p, false, e.positionMs)
                        putState(p, "resume", e.positionMs, e.positionMs)
                    }
                }
                progress(p, e.positionMs)
            }
            is PlayerEvent.Position -> {
                p.advanceTo(e.positionMs)
                progress(p, e.positionMs)
            }
            is PlayerEvent.Buffering -> p.advanceTo(e.positionMs)
            is PlayerEvent.Paused -> {
                p.advanceTo(e.positionMs)
                p.playing = false
                p.countedFrom = null
                if (p.pending == Playback.Pending.Pause) {
                    p.pending = null
                    timeline.pausedChanged(p, true, e.positionMs)
                    putState(p, "pause", e.positionMs, e.positionMs, speed = 0)
                }
            }
            is PlayerEvent.Seeked -> {
                val from = (p.pending as? Playback.Pending.Seek)?.from ?: p.lastPosition
                p.pending = null
                if (e.positionMs < from) p.seekBack++ else p.seekForward++
                p.lastPosition = e.positionMs
                p.countedFrom = if (p.playing) e.positionMs else null
                timeline.seek(p, from, e.positionMs)
                putState(p, "seek", e.positionMs, from, speed = if (p.playing) 1 else 0)
            }
            is PlayerEvent.Ended -> {
                val shown = p.lastPosition
                // Played time runs to the file's end: ms_played 29475 = 150227 - 120752 (end-of-track)
                // and 151238 = 153889 - 2651 (real1), although the last position report was earlier.
                // A player whose measured duration overshoots the file's (150274) is clamped to it.
                val end = if (p.fileDurationMs in shown..e.positionMs) p.fileDurationMs else e.positionMs
                p.advanceTo(end)
                p.playing = false
                trackEnded(p, shown)
            }
            is PlayerEvent.Error -> emit(EngineEvent.Failure("player: ${e.message} at ${e.positionMs} ms"))
        }
        if (timeline.lastFlushMs == 0L) timeline.lastFlushMs = env.nowMs()
        if (env.nowMs() - timeline.lastFlushMs >= 120_000) flushTimeline()
    }

    /** `started_playing` after ~1 s of real audio, `played_threshold_reached` at `ms_played_until_update`. */
    private suspend fun progress(p: Playback, position: Long) {
        if (p.playing) timeline.tick(p, position)
        if (!p.started && p.msPlayed >= STARTED_AFTER_MS) {
            p.started = true
            putState(p, "started_playing", position, position)
            if (config.preloadBeforeEndMs == null) preloadNext(p)
        }
        if (p.started && !p.thresholdSent && p.msPlayed >= p.track.msPlayedUntilUpdate) {
            p.thresholdSent = true
            putState(p, "played_threshold_reached", position, position)
        }
        val lead = config.preloadBeforeEndMs
        if (lead != null && p.started && p.durationMs - position <= lead) preloadNext(p)
    }

    /** The natural end: finalize, then follow `advance` of the current state like the Web Player. */
    private suspend fun trackEnded(p: Playback, shownPosition: Long) {
        val sm = machine
        val state = sm?.states?.getOrNull(stateIndex)?.takeIf { it.id == p.stateId } ?: sm?.states?.firstOrNull { it.id == p.stateId }
        val next = state?.transitions?.advance
        val nextState = next?.stateIndex?.let { sm?.states?.getOrNull(it) }
        val nextTrack = nextState?.let { sm?.tracks?.getOrNull(it.track) }
        val metrics = player.metrics()
        current = null
        if (next == null || nextState == null || nextTrack == null) {
            finalize(p, "trackdone", nextState?.id, shownPosition, keepMachine = true, metrics = metrics)
            player.stop()
            emit(EngineEvent.ContextEnded)
            return
        }
        stateIndex = machine?.states?.indexOfFirst { it.id == nextState.id } ?: -1
        flushOnFirstPlay = true
        // Gapless: the next song starts, then the finalize of this one goes out (same wire order).
        load(nextState, nextTrack, nextState.initialPlaybackPosition ?: 0, next.paused, "trackdone", shownPosition) {
            finalize(p, "trackdone", nextState.id, shownPosition, keepMachine = true, metrics = metrics)
        }
    }

    /** Resolves and licenses the track `advance` (else `skip_next`) leads to, so a skip starts at once. */
    private suspend fun preloadNext(p: Playback) {
        if (p.preloaded) return
        p.preloaded = true
        val sm = machine ?: return
        val state = sm.states.firstOrNull { it.id == p.stateId } ?: return
        val ref = state.transitions.advance ?: state.transitions.skipNext ?: return
        val next = ref.stateIndex?.let { sm.states.getOrNull(it) } ?: return
        val track = sm.tracks.getOrNull(next.track)?.takeIf { it.contentType != "AD" } ?: return
        runCatching {
            val media = resolver.resolve(track, formatFilter, lowestQuality)
            player.preload(media, licences.channel())
            emit(EngineEvent.Preloaded(track.metadata.uri))
        }.onFailure { emit(EngineEvent.Failure("preload: ${it.message}")) }
    }

    // ---------------------------------------------------------------- sending

    private suspend fun putState(
        p: Playback, debugSource: String, position: Long, previousPosition: Long?, speed: Int? = null,
        stats: JsonObject? = null, duration: Long = p.durationMs, applyAnswer: Boolean = true,
        machineId: String? = machine?.id,
    ) {
        val s = speed ?: if (p.wantPaused) 0 else 1
        val sub = Bodies.SubState(s, position, duration, p.file.bitrate, p.file.format, p.file.audioQuality)
        putState(machineId, p.stateId, p.wantPaused, sub, previousPosition, debugSource, stats, applyAnswer)
    }

    private suspend fun putState(
        machineId: String?, stateId: String?, paused: Boolean, sub: Bodies.SubState?, previousPosition: Long?,
        debugSource: String, stats: JsonObject? = null, applyAnswer: Boolean = true,
    ) {
        val body = Bodies.state(++seq, machineId, stateId, paused, sub, previousPosition, debugSource, stats)
        val r = sp.send(
            "PUT", "$spclient/track-playback/v1/devices/$deviceId/state", HeaderProfile.PLAYER,
            body = body.toString().encodeToByteArray(), contentType = "application/json",
        )
        emit(EngineEvent.StateReported(debugSource, sub?.position))
        if (!applyAnswer) return
        // The answer is the server's next state machine; later reports refer to it.
        val next = runCatching { EngineJson.decodeFromString(PutStateResponse.serializer(), r.text()) }.getOrNull() ?: return
        val sm = next.stateMachine ?: return
        machine = sm
        stateIndex = next.updatedStateRef?.stateIndex ?: sm.states.indexOfFirst { it.id == stateId }
    }

    private suspend fun melody(messages: List<JsonObject>, sdkId: String) {
        sp.send(
            "POST", "$spclient/melody/v1/msg/batch", HeaderProfile.AUTH,
            body = Bodies.melodyBatch(messages, sdkId, config).toString().encodeToByteArray(),
            contentType = "text/plain;charset=UTF-8",
        )
    }

    private suspend fun flushTimeline() {
        val msg = timeline.drain() ?: return
        melody(listOf(Bodies.melodyMessage("media_playback_event_v2", msg)), config.harmonyVersion)
    }

    internal val currentMachineId get() = machine?.id
    internal val seqNum get() = seq

    companion object {
        /** `started_playing` came 1066-1245 ms into real playback in every capture (four tracks). */
        const val STARTED_AFTER_MS = 1_000L
    }
}
