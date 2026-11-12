package app.myspoti.wear

import android.content.Context
import android.content.Intent
import android.util.Log
import app.myspoti.engine.EngineEvent
import app.myspoti.engine.NativeEngine
import app.myspoti.engine.android.AndroidEnvironment
import app.myspoti.engine.android.ChromiumHttp
import app.myspoti.engine.android.Media3DrmPlayer
import app.myspoti.engine.android.OkHttpWebSockets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The watch's player: the native engine with its Android player (Media3 and the watch's own
 * Widevine), started at the first play, its tokens asked to the phone ([PhoneLink]). It registers
 * a device of its own on the account, so the phone and the other apps see the watch play.
 */
object WatchEngine : app.myspoti.spotify.sync.MirrorEngine {
    private const val TAG = "Orpheon"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Mutex()
    private var engine: NativeEngine? = null
    private var player: Media3DrmPlayer? = null

    private val _now = MutableStateFlow<NativeEngine.Now?>(null)
    /** The song the watch plays and where it is; null with nothing loaded. */
    val now: StateFlow<NativeEngine.Now?> = _now
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private val _current = MutableStateFlow<NativeEngine?>(null)
    /** The engine registered on the account now, or null (the listen mirror's device, [WatchSync]). */
    override val current: StateFlow<NativeEngine?> = _current

    private lateinit var appContext: Context
    /** The app's context, for a start that comes from the listen mirror. Once, early. */
    fun init(context: Context) { if (!::appContext.isInitialized) appContext = context.applicationContext }

    /** When the watch itself last asked the engine for something (a remote command is not one). */
    @Volatile private var askedAt = 0L
    /** The engine plays what the watch asked for lately (a play, a resume, a skip from its screen). */
    val intended: Boolean get() = android.os.SystemClock.elapsedRealtime() - askedAt < INTENDED_MS
    private const val INTENDED_MS = 8_000L

    /** When the engine last failed to start here (the mirror leaves the song to the phone for a while then). */
    @Volatile var startFailedAt = 0L
        private set

    override suspend fun ready(): NativeEngine = ready(appContext)

    private suspend fun ready(context: Context): NativeEngine = lock.withLock {
        engine?.let { return it }
        val app = context.applicationContext
        init(app)
        val link = PhoneLink(app)
        val http = ChromiumHttp(app)
        val p = Media3DrmPlayer(app, http, log = { Log.d(TAG, "native: $it") }).also { player = it }
        val e = NativeEngine(http, link, OkHttpWebSockets(), p, AndroidEnvironment, link.config(), scope)
        // A pause the player made itself (the headphones gone, another sound) is told to the account.
        p.onPlayerPause = { scope.launch { runCatching { e.pause() } } }
        scope.launch { e.now.collect { n -> _now.value = n; WatchPlaybackService.follow(app, n?.playing == true) } }
        scope.launch {
            e.events.collect { ev ->
                if (ev is EngineEvent.Failure) { Log.w(TAG, "native: $ev"); if (ev.message.startsWith("player:")) _error.value = ev.message }
                // The dealer closed: no command reaches the watch; the next use starts the engine again.
                if (ev is EngineEvent.Disconnected) dropped(e)
            }
        }
        try {
            e.start()
        } catch (t: Throwable) {
            startFailedAt = System.currentTimeMillis()
            throw t
        }
        engine = e
        _current.value = e
        e
    }

    private fun dropped(e: NativeEngine) {
        scope.launch {
            lock.withLock {
                if (engine !== e) return@withLock
                Log.w(TAG, "native: the dealer closed, the engine leaves the account")
                runCatching { e.close() }
                engine = null; _current.value = null; _now.value = null
            }
        }
    }

    private fun run(context: Context, block: suspend (NativeEngine) -> Unit) {
        scope.launch {
            _error.value = null
            askedAt = android.os.SystemClock.elapsedRealtime()
            runCatching { block(ready(context)) }.onFailure { Log.w(TAG, "native: ${it.message}"); _error.value = it.message ?: it.javaClass.simpleName }
        }
    }

    fun play(context: Context, uri: String) = run(context) { it.play(uri) }
    fun toggle(context: Context) = run(context) { if (_now.value?.playing == true) it.pause() else it.resume() }
    fun next(context: Context) = run(context) { it.skipNext() }
    fun previous(context: Context) = run(context) { it.previous() }
    fun pause() { scope.launch { runCatching { engine?.pause() } } }

    /** Leaves the account's devices and frees the player (the service's end). */
    fun stop() {
        scope.launch {
            lock.withLock {
                engine?.let { runCatching { it.close() } }
                player?.release()
                engine = null; player = null; _now.value = null; _current.value = null
            }
        }
    }
}

/** Keeps the process and the sound going while the watch plays (a media playback foreground service). */
class WatchPlaybackService : android.app.Service() {
    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_PAUSE) { WatchEngine.pause(); WatchLocal.pause(); return START_NOT_STICKY }
        val manager = getSystemService(android.app.NotificationManager::class.java)
        manager.createNotificationChannel(android.app.NotificationChannel(CHANNEL, getString(R.string.wear_playing_channel), android.app.NotificationManager.IMPORTANCE_LOW))
        val local = WatchLocal.now.value
        val engine = WatchEngine.now.value
        val open = android.app.PendingIntent.getActivity(this, 0, Intent(this, WatchActivity::class.java), android.app.PendingIntent.FLAG_IMMUTABLE)
        val notification = android.app.Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(local?.name ?: engine?.name ?: getString(R.string.app_name)).setContentText(local?.artists ?: engine?.artists.orEmpty())
            .setContentIntent(open).setOngoing(true).build()
        startForeground(1, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        return START_NOT_STICKY
    }

    companion object {
        private const val CHANNEL = "playback"
        const val ACTION_PAUSE = "app.myspoti.wear.PAUSE"

        /** Foreground while [playing]; gone a moment after it stops, with the engine's device. */
        fun follow(context: Context, playing: Boolean) {
            val intent = Intent(context, WatchPlaybackService::class.java)
            // Either player playing keeps it: the extensions' one or the account's.
            val any = playing || WatchLocal.now.value?.playing == true || WatchEngine.now.value?.playing == true
            if (any) runCatching { context.startForegroundService(intent) } else context.stopService(intent)
        }
    }
}
