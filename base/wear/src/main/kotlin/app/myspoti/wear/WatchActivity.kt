package app.myspoti.wear

import android.content.Context
import app.myspoti.api.sync.LocalNow
import android.media.AudioManager
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.google.android.horologist.audio.ui.VolumeUiState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch

/**
 * The watch's two pages: what plays (its name, its artists, previous, play or pause, next, the
 * volume), then, a swipe away, the account's playlists as the phone lists them; a tap plays one
 * on the watch, from its own extensions when one has the songs ([WatchPlay]), else from the account.
 */
class WatchActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The listen mirror's setting and the engine's context, before anything plays.
        WatchSync.init(applicationContext)
        setContent { WatchTheme { WatchApp() } }
    }
}

/** The pages fed from the players, the phone and the extensions ([WatchPager] draws them). */
@Composable
internal fun WatchApp() {
    val context = LocalContext.current
    val engineNow by WatchEngine.now.collectAsState()
    val localNow by WatchLocal.now.collectAsState()
    val error by WatchEngine.error.collectAsState()
    val extensions by WatchExtensions.installed.collectAsState()
    val ui = rememberCoroutineScope()
    var syncNote by remember { mutableStateOf<String?>(null) }
    remember { WatchExtensions.init(context.applicationContext) }
    // The extensions' player gives no position on its own: read every second while it has a song.
    val local = localNow
    var localPosition by remember { mutableStateOf<LocalNow?>(null) }
    LaunchedEffect(local != null) {
        while (local != null) { localPosition = WatchLocal.localNow(); delay(1_000) }
        localPosition = null
    }
    // One "now" for both players: the extensions' one while it plays a list.
    val playing = local?.let {
        val p = localPosition
        WatchPlaying(it.name, it.artists, it.playing, it.source, it.cover, p?.positionMs ?: 0L, p?.durationMs ?: 0L, SystemClock.elapsedRealtime(), p?.buffering == true)
    } ?: engineNow?.let {
        // The engine's position is at its wall clock time; the ring predicts on elapsedRealtime.
        val at = SystemClock.elapsedRealtime() - (System.currentTimeMillis() - it.atMs)
        WatchPlaying(it.name, it.artists, it.playing, null, it.imageUrl, it.positionMs, it.durationMs, at, it.buffering)
    }
    var attempt by remember { mutableStateOf(0) }
    // null: being read; a failure: the phone is not there or not signed in.
    val lists by produceState<Result<List<WatchList>>?>(null, attempt) { value = runCatching { PhoneLink(context.applicationContext).library() } }

    WatchPager(
        playing = playing,
        error = error,
        onPrevious = { if (WatchLocal.active) WatchLocal.previous() else WatchEngine.previous(context) },
        onToggle = { if (WatchLocal.active) WatchLocal.toggle() else WatchEngine.toggle(context) },
        onNext = { if (WatchLocal.active) WatchLocal.next() else WatchEngine.next(context) },
        volume = rememberWatchVolume(),
        lists = lists,
        onPlay = { WatchPlay.play(context, it.uri) },
        onRetry = { attempt++ },
        extensionsNote = syncNote ?: stringResource(R.string.wear_extensions_count, extensions.size),
        onSyncExtensions = {
            syncNote = context.getString(R.string.wear_loading)
            ui.launch {
                syncNote = runCatching { WatchExtensions.sync(context.applicationContext) }
                    .fold({ n -> context.getString(R.string.wear_extensions_synced, n) }, { e -> context.getString(R.string.wear_failed, e.message ?: "?") })
            }
        },
    )
}

/** The media volume: the crown sets it (its level shown a moment), the button opens the system's volume panel. */
@Composable
private fun rememberWatchVolume(): WatchVolumeUi {
    val context = LocalContext.current
    return remember(context) {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        fun read() = VolumeUiState(audio.getStreamVolume(AudioManager.STREAM_MUSIC), audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
        var level by mutableStateOf(read())
        val shown = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        WatchVolumeUi(
            state = { level },
            shown = shown,
            set = { v -> audio.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0); level = read(); shown.tryEmit(Unit) },
            open = { audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_SAME, AudioManager.FLAG_SHOW_UI); level = read() },
        )
    }
}

/**
 * A list tapped on the watch: from the watch's own extensions when one has its songs (the songs
 * from the phone, or the last ones read), else from the account through the engine.
 */
object WatchPlay {
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main)

    fun play(context: android.content.Context, uri: String) {
        val app = context.applicationContext
        scope.launch {
            WatchExtensions.init(app)
            val fromExtensions = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                if (!WatchExtensions.hasAudio()) return@withContext null
                val tracks = runCatching { PhoneLink(app).tracks(uri) }.getOrNull().orEmpty()
                // The first of the first few songs an extension has.
                tracks.indices.take(5).firstNotNullOfOrNull { i -> WatchExtensions.audio(tracks[i])?.let { Triple(tracks, i, it) } }
            }
            if (fromExtensions != null) {
                WatchEngine.pause()
                WatchLocal.play(app, fromExtensions.first, fromExtensions.second, fromExtensions.third, uri = uri)
            } else {
                WatchLocal.stop()
                WatchEngine.play(app, uri)
            }
        }
    }
}
