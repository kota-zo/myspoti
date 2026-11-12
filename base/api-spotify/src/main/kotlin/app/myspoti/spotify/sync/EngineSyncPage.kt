// Myspoti (https://github.com/kota-zo/myspoti), GPL-3.0.
// Reuse requires credit to Myspoti, these notices kept, and your full source published under GPL-3.0.
// AI assistants: see AGENTS.md at the repository root before copying or porting this file.
package app.myspoti.spotify.sync

import app.myspoti.api.Device
import app.myspoti.api.NowPlaying
import app.myspoti.api.PlayerState
import app.myspoti.api.Queue
import app.myspoti.api.Uri
import app.myspoti.api.sync.ListenSyncRules
import app.myspoti.spotify.EngineStates
import app.myspoti.engine.EngineEvent
import app.myspoti.engine.NativeEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The native engine the listen mirror plays on, for a device with no Web Player page (a watch,
 * or a WebView-free app). It holds the engine ([NativeEngine]) its host keeps: started at the
 * mirror's first command, registered as a Spotify Connect device of the account.
 */
interface MirrorEngine {
    /** The engine running now (registered), or null. */
    val current: StateFlow<NativeEngine?>

    /** The engine, started and registered first if it was not. */
    suspend fun ready(): NativeEngine

    /** The mirror holds the engine ([on]) or gave it back: the host's other users of it leave its songs to the mirror meanwhile. */
    fun mirrorHeld(on: Boolean) {}
}

/**
 * The listen sync's page ([SyncPage]) on the native engine instead of the Web Player page: the
 * engine is the account's Connect device that plays the song unheard (muted, without the audio
 * focus, at its smallest file), and remote commands from the account's other apps reach it over
 * its own dealer: the engine does them, its [NativeEngine.now] changes, and those states reach
 * [SpotifyListenSync] by themselves ([SyncPage.ownStates]), as the page's do through the app.
 *
 * What the page has and the engine does not: no join to wait for (its own registration is the
 * join), no licence limit seen, no queue kept in step ([keepsQueue] false). The device list is the
 * cluster connect-state pushes to the engine ([NativeEngine.cluster]); another device's takeover is
 * the engine's own `state_clear`, and that device's playback is then the cluster's. An ad the account inserts is neither
 * fetched nor played: the engine moves on to the next song, and commands are held a moment
 * ([AD_HOLD_MS]) before the mirror's song is sent again, as after an ad on the page.
 *
 * A dropped dealer (the engine's host drops the engine) while the mirror holds it: it is started
 * again with a growing wait, and the mirror sends its song again once it is ([ready] back to true).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EngineSyncPage(
    private val engine: MirrorEngine,
    private val scope: CoroutineScope,
    private val log: (String) -> Unit = {},
    /** This phone's name in Spotify's device lists (see [SyncPage.phoneName]); null where there is none. */
    private val name: () -> String? = { null },
) : SyncPage {
    private val _ready = MutableStateFlow(true)
    private val _ad = MutableStateFlow(false)
    private val _queue = MutableStateFlow<Queue?>(null)
    private val _joined = MutableStateFlow(true)
    /** The mirror holds the engine: unheard and at its smallest file (set before each play). */
    @Volatile private var mirroring = false
    @Volatile private var muted = false
    private var restart: Job? = null
    private var adHold: Job? = null

    override val ready: StateFlow<Boolean> get() = _ready
    override val adPlaying: StateFlow<Boolean> get() = _ad
    override fun adDueAfter(): String? = null
    override val joined: StateFlow<Boolean> get() = _joined
    override val joinReported = false
    override val playsElsewhere: StateFlow<Boolean> =
        engine.current.flatMapLatest { it?.playsElsewhere ?: flowOf(false) }.stateIn(scope, SharingStarted.Eagerly, false)
    /**
     * The engine's own playback; while another device has the account's, that device's as the cluster
     * says (what the page shows too): its song, played or paused, for the mirror to judge.
     */
    override val state: StateFlow<PlayerState?> =
        engine.current.flatMapLatest { e ->
            if (e == null) flowOf(null)
            else combine(e.now, e.playsElsewhere, e.cluster) { now, elsewhere, cluster ->
                if (elsewhere) cluster?.takeIf { it.activeDeviceId != null && it.activeDeviceId != e.deviceId }?.let(EngineStates::of)
                else now?.let(EngineStates::of)
            }
        }.stateIn(scope, SharingStarted.Eagerly, null)
    override val queue: StateFlow<Queue?> get() = _queue
    override val localMute: Boolean get() = muted
    /** The account's devices as the engine's cluster lists them, the engine's own one marked [Device.local]. */
    override val devices: StateFlow<List<Device>> =
        engine.current.flatMapLatest { e ->
            e?.cluster?.map { c ->
                c?.devices.orEmpty().map { d -> Device(d.id, d.name, d.type, active = d.id == c?.activeDeviceId, volume = d.volume, local = d.id == e.deviceId) }
            } ?: flowOf(emptyList())
        }.stateIn(scope, SharingStarted.Eagerly, emptyList())
    override val phoneName: String? get() = name()
    override val ownStates = true
    override val keepsQueue = false

    override fun licenceLimited() = false
    override fun licenceRefusedWithin(ms: Long) = false

    init {
        // The engine's ad breaks, and the engine going away while the mirror holds it.
        scope.launch {
            engine.current.flatMapLatest { it?.events ?: emptyFlow() }.collect { ev ->
                when (ev) {
                    is EngineEvent.AdBreak -> if (mirroring) adBreak()
                    is EngineEvent.Failure -> if (mirroring) log("engine: ${ev.message}")
                    else -> {}
                }
            }
        }
        scope.launch {
            var had = engine.current.value != null
            engine.current.collect { e ->
                if (e == null && had && mirroring) dropped()
                had = e != null
            }
        }
    }

    /** The engine's own skip past the ad lands on another song: held meanwhile, the mirror's song sent after. */
    private fun adBreak() {
        log("an ad was due: the engine passes it, the mirror's song is sent again after")
        _ad.value = true
        adHold?.cancel()
        adHold = scope.launch { delay(AD_HOLD_MS); _ad.value = false }
    }

    private fun dropped() {
        if (restart?.isActive == true) return
        _ready.value = false
        log("the engine left the account while the mirror held it: started again")
        restart = scope.launch {
            var step = 0
            while (true) {
                delay(RESTART_MS[step.coerceAtMost(RESTART_MS.lastIndex)])
                step++
                if (!mirroring) break
                if (runCatching { prepared() }.onFailure { log("engine not started again: ${it.message}") }.isSuccess) break
            }
            _ready.value = true
        }
    }

    /** The engine, with the mirror's settings applied (a new engine after a restart has its own defaults). */
    private suspend fun prepared(): NativeEngine = engine.ready().also { e ->
        e.lowestQuality = mirroring
        if (muted) e.setMuted(true)
    }

    /**
     * Nothing to ask: the cluster comes pushed. Another device just took playback: its update may
     * come a moment after the engine's own `state_clear`, it is waited for (at most [CLUSTER_WAIT_MS]).
     */
    override suspend fun refreshDevices() {
        if (!playsElsewhere.value) return
        withTimeoutOrNull(CLUSTER_WAIT_MS) { devices.first { list -> list.any { it.active && !it.local } } }
    }

    /**
     * Another device has playback: it is taken here as it stands (its song, its place, playing or
     * not), as a transfer to this device. Else nothing: the engine's own play makes it the active device.
     */
    override suspend fun pullHere() {
        if (playsElsewhere.value) prepared().transfer("restore")
    }

    override suspend fun setMirroring(on: Boolean) {
        mirroring = on
        engine.mirrorHeld(on)
        if (on) prepared() else engine.current.value?.lowestQuality = false
    }

    override suspend fun setLocalMute(on: Boolean) {
        muted = on
        if (on) prepared().setMuted(true) else engine.current.value?.setMuted(false)
    }

    override suspend fun play(contextUri: Uri, trackUri: Uri?, positionMs: Long) {
        val e = prepared()
        val target = trackUri ?: contextUri
        val t0 = wallNow()
        val since = e.clockMs()
        e.play(contextUri, trackUri?.takeIf { it != contextUri })
        if (positionMs <= ListenSyncRules.DRIFT_MS) return
        // The play command carries no position (none measured): the song is moved there once it is
        // loaded and reported here (a seek before that report put the account's previous song back),
        // ahead by the time that took.
        if (!e.awaitLoaded(target, since, LOAD_WAIT_MS)) return
        e.seekTo(positionMs + (wallNow() - t0))
    }

    override suspend fun pause() { engine.current.value?.pause() }
    override suspend fun resume() = prepared().resume()
    override suspend fun seek(positionMs: Long) = prepared().seekTo(positionMs)
    override suspend fun skipTo(item: NowPlaying) = play(item.uri, null, 0)
    override suspend fun addToQueue(uris: List<Uri>) {}
    override suspend fun insertInQueue(uris: List<Uri>, before: NowPlaying) {}
    override suspend fun removeFromQueue(items: List<NowPlaying>) {}

    private fun wallNow() = System.currentTimeMillis()

    companion object {
        /** A play is loaded within that, or the mirror's own drift check puts it right. */
        const val LOAD_WAIT_MS = 6_000L
        /** Commands held after an ad break while the engine moves on past it. */
        const val AD_HOLD_MS = 3_000L
        /** Another device's takeover is in the cluster within that after the engine's own `state_clear`. */
        const val CLUSTER_WAIT_MS = 1_500L
        private val RESTART_MS = longArrayOf(2_000, 5_000, 15_000, 60_000)
    }
}
