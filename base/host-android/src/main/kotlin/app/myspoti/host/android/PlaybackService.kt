package app.myspoti.host.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.media.MediaBrowserServiceCompat
import androidx.media.VolumeProviderCompat
import androidx.media.app.NotificationCompat.MediaStyle
import androidx.media.session.MediaButtonReceiver
import android.util.LruCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** What the notification, lock screen, Bluetooth and Android Auto show. */
data class NowPlayingInfo(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val coverUrl: String?,
    val durationMs: Long,
    val positionMs: Long,
    val playing: Boolean,
    val buffering: Boolean = false,
    val liked: Boolean = false,
    val shuffle: Boolean = false,
    /** A podcast episode: the notification offers 15 s skips, the headset next / previous skip 15 s. */
    val episode: Boolean = false,
) {
    /**
     * Audio goes on, or is about to: a song plays or loads, or an ad break runs. An ad is shown
     * "paused" ([playing] false, for the screens) but the page plays it (muted) and the app is busy
     * getting past it: the service keeps the foreground and its locks then, or a break over a
     * minute let Android freeze the app (cached apps freezer, Android 14+) and, on Android 17,
     * silence a player with no foreground service (background audio hardening).
     */
    val makesSound: Boolean get() = playing || buffering || id == AD_ID

    /** Everything but the position: a moving position alone is not a new state. */
    fun sameAs(other: NowPlayingInfo) = id == other.id && title == other.title && artist == other.artist &&
        album == other.album && coverUrl == other.coverUrl && durationMs == other.durationMs &&
        playing == other.playing && buffering == other.buffering && liked == other.liked && shuffle == other.shuffle && episode == other.episode

    companion object {
        /** The [id] the app gives an ad break (the screens then say "Advertisement"). */
        const val AD_ID = "spotify:ad"
    }
}

/**
 * A row of the queue the car shows (Android Auto, some lock screens). [id] is all the car hands back
 * on a tap ([PlaybackControls.skipToQueueItem]); [iconUri] a content:// URI, the car loads no web image.
 */
data class QueueEntry(val id: Long, val title: String, val artist: String, val iconUri: String?)

/** Commands from the system (notification buttons, headset, car, lock screen). */
interface PlaybackControls {
    fun play()
    fun pause()
    fun next()
    fun previous()
    fun seekTo(ms: Long)
    fun setLiked(liked: Boolean)
    fun playFromMediaId(mediaId: String)
    fun playFromSearch(query: String)
    fun setRemoteVolume(volume: Int)
    fun setShuffle(on: Boolean) {}
    /** What plays is a podcast episode. */
    val isEpisode: Boolean get() = false
    /** Skips [deltaMs] within the episode; at its end, goes on to the next item. */
    fun skipBy(deltaMs: Long) {}
    /** A row of the car's queue was tapped: [id] is its [QueueEntry.id] (see [PlaybackService.updateQueue]). */
    fun skipToQueueItem(id: Long) {}
}

/** Android Auto / media browser content. */
interface MediaLibrary {
    suspend fun children(context: Context, parentId: String): List<MediaBrowserCompat.MediaItem>
    suspend fun search(query: String): List<MediaBrowserCompat.MediaItem>
}

/**
 * Media session + notification + browser service. The app pushes the state with [update]
 * and receives commands through [controls]; the service never talks to Spotify itself.
 */
class PlaybackService : MediaBrowserServiceCompat() {
    private lateinit var session: MediaSessionCompat
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var foreground = false
    private var lastCoverUrl: String? = null
    private var cover: Bitmap? = null
    private var coverJob: Job? = null
    /**
     * The last covers: a song heard again, or the one after an ad, shows its artwork at once
     * instead of leaving the notification bare until the download ends. Bitmaps are never
     * recycled by hand: the session, the notification and this cache may all hold one.
     */
    private val covers = object : LruCache<String, Bitmap>(COVER_CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        session = MediaSessionCompat(this, "Myspoti").apply {
            setCallback(callback)
            setSessionActivity(launchIntent())
            // Android Auto binds on a cold start, before the app has pushed any state: a
            // session without a playback state gives the car no play button, and no command
            // is ever sent (issue #41). Paused with the full action set: the car can play.
            setPlaybackState(PlaybackStateCompat.Builder()
                .setActions(ACTIONS)
                .setState(PlaybackStateCompat.STATE_PAUSED, 0L, 0f)
                .build())
            isActive = true
        }
        sessionToken = session.sessionToken
        createChannel()
        current?.let { apply(it) }
        applyQueue()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Every start may be a startForegroundService() (playback, a headset button or the
        // notification's dismissal through MediaButtonReceiver, another app): Android kills the
        // app unless startForeground() follows, paused or not. A paused player then leaves the
        // foreground after PAUSE_GRACE_MS, as after any pause.
        val info = current
        startForegroundSafely(build(info), always = true)
        MediaButtonReceiver.handleIntent(session, intent)
        if (info != null) show(info)
        // The paused notification was swiped away: leave the foreground with it.
        if (info?.playing != true && isStop(intent)) {
            handler.removeCallbacks(leaveForeground)
            holdLocks(false)
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            foreground = false
        }
        return START_NOT_STICKY
    }

    private fun isStop(intent: Intent?): Boolean =
        intent?.action == Intent.ACTION_MEDIA_BUTTON &&
            androidx.core.content.IntentCompat.getParcelableExtra(intent, Intent.EXTRA_KEY_EVENT, android.view.KeyEvent::class.java)
                ?.keyCode == android.view.KeyEvent.KEYCODE_MEDIA_STOP

    override fun onDestroy() {
        instance = null
        holdLocks(false)
        session.release()
        scope.cancel()
        super.onDestroy()
    }

    /**
     * The app closed from the recent apps. The music stops with it (asked by users: it went on
     * playing with nothing on screen), unless the setting keeps it playing ([keepsPlayingOnClose]);
     * a Spotify Connect device that plays is left playing. The pause fades out, then the
     * notification goes with the service.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        // An ad or a song loading counts as playing: closing the app then stopped the service under it.
        if (current?.makesSound == true && !playingRemote && !keepsPlayingOnClose(this)) {
            controls?.pause()
            handler.postDelayed({
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                foreground = false
                stopSelf()
            }, CLOSE_STOP_MS)
            return
        }
        if (current?.makesSound != true) stopSelf()
    }

    /** A Spotify Connect device plays (the session's volume is that device's). */
    private var playingRemote = false

    /**
     * The car (Android Auto, Bluetooth) binds to this service without ever starting it. A bound-only
     * service cannot always promote itself to the foreground from the background (Android 12+), and
     * the app is then just a cached process the system freezes a few seconds into a song: playback
     * paused by itself and the car's buttons went dead. Every command that makes sound starts the
     * service for real (the media session's callback is allowed to, and [onStartCommand] goes
     * foreground at once), so it also outlives the car's binding.
     */
    private fun ensureStarted() {
        if (foreground) return
        runCatching { androidx.core.content.ContextCompat.startForegroundService(this, Intent(this, PlaybackService::class.java)) }
            .onFailure { Logbook.log("service not started from a car command: ${it.javaClass.simpleName} ${it.message?.take(80)}") }
    }

    /** Runs a system command on the app's player; a command with nobody to take it is logged, not lost silently. */
    private inline fun command(name: String, wake: Boolean = true, run: PlaybackControls.() -> Unit) {
        Logbook.log("media session: $name")
        if (wake) ensureStarted()
        val c = controls
        if (c == null) android.util.Log.w("Myspoti", "media session: $name dropped, the player is not attached yet") else c.run()
    }

    /** A transport command from the system: on a podcast episode the earbuds skip 15 s (see [HeadsetButtons]). */
    private fun headset(cmd: HeadsetButtons.Command) {
        val episode = controls?.isEpisode == true
        when (val action = HeadsetButtons.map(episode, skipsOnEpisodes(this), cmd)) {
            HeadsetButtons.Action.NEXT -> command("next") { next() }
            HeadsetButtons.Action.PREVIOUS -> command("previous") { previous() }
            HeadsetButtons.Action.SEEK_FORWARD, HeadsetButtons.Action.SEEK_BACK -> {
                val delta = if (action == HeadsetButtons.Action.SEEK_FORWARD) HeadsetButtons.SKIP_MS else -HeadsetButtons.SKIP_MS
                command(HeadsetButtons.logLine(cmd, episode, action)) { skipBy(delta) }
            }
            HeadsetButtons.Action.NONE -> Logbook.log("media session: " + HeadsetButtons.logLine(cmd, episode, action))
        }
    }

    private val callback = object : MediaSessionCompat.Callback() {
        override fun onPlay() = command("play") { play() }
        override fun onPrepare() { Logbook.log("media session: prepare") }
        override fun onPause() = command("pause", wake = false) { pause() }
        override fun onSkipToNext() = headset(HeadsetButtons.Command.NEXT)
        override fun onSkipToPrevious() = headset(HeadsetButtons.Command.PREVIOUS)
        override fun onFastForward() = headset(HeadsetButtons.Command.FAST_FORWARD)
        override fun onRewind() = headset(HeadsetButtons.Command.REWIND)
        override fun onMediaButtonEvent(mediaButtonEvent: Intent): Boolean {
            // Skip forward / backward keys (some earbuds, cars) have no callback of their own.
            val event = androidx.core.content.IntentCompat.getParcelableExtra(mediaButtonEvent, Intent.EXTRA_KEY_EVENT, android.view.KeyEvent::class.java)
            val mapped = event?.let { HeadsetButtons.commandOf(it.keyCode) }
            if (event != null && mapped != null) {
                if (event.action == android.view.KeyEvent.ACTION_DOWN && event.repeatCount == 0) headset(mapped)
                return true
            }
            return super.onMediaButtonEvent(mediaButtonEvent)
        }
        override fun onSeekTo(pos: Long) = command("seek", wake = false) { seekTo(pos) }
        override fun onStop() = command("stop", wake = false) { pause() }
        override fun onPlayFromMediaId(mediaId: String, extras: Bundle?) {
            // The "recent" item of the resumption root (see onGetRoot): play on where the last session stopped.
            if (mediaId == RESUME_ID) command("resume") { play() } else command("play $mediaId") { playFromMediaId(mediaId) }
        }
        // The car's own shuffle button (Android Auto greys it out without the action).
        override fun onSetShuffleMode(shuffleMode: Int) = command("shuffle mode $shuffleMode", wake = false) {
            setShuffle(shuffleMode != PlaybackStateCompat.SHUFFLE_MODE_NONE)
        }
        override fun onSkipToQueueItem(id: Long) = command("queue row $id") { skipToQueueItem(id) }
        override fun onPlayFromSearch(query: String, extras: Bundle?) = command("play from search") {
            // "Play music" with no words (Assistant, the car's voice button) is a plain play, not a search.
            if (query.isBlank()) play() else playFromSearch(query)
        }
        override fun onCustomAction(action: String, extras: Bundle?) {
            when (action) {
                ACTION_LIKE -> command("like", wake = false) { setLiked(!(current?.liked ?: false)) }
                ACTION_SHUFFLE -> command("shuffle", wake = false) { setShuffle(!(current?.shuffle ?: false)) }
            }
        }
    }

    private fun apply(raw: NowPlayingInfo) {
        // Every surface fed from here (notification, lock screen, car, Bluetooth) is large: the queue and
        // the lists hand over the 64/300 px id, whatever the caller did; also the key of the cover cache.
        val info = raw.coverUrl?.let { raw.copy(coverUrl = Covers.large(it)) } ?: raw
        // A bitmap recycled behind our back (issue #77: "cannot use a recycled source in createBitmap"
        // thrown by MediaMetadata.Builder.build) is dropped and fetched again, never handed to the session.
        if (cover?.isRecycled == true) { cover = null; lastCoverUrl = null }
        // Before the metadata: a cached cover is in it at once. A cover that failed (offline, Doze with the screen off, a 5xx) is asked again on the next
        // real state change: it used to stay bare until the next song.
        if (info.coverUrl != lastCoverUrl || (cover == null && !info.coverUrl.isNullOrBlank() && coverJob?.isActive != true)) {
            loadCover(info.coverUrl)
        }
        val metadata = fun(withArt: Boolean) = (MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, info.id)
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, info.title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, info.artist)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, info.album)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI, info.coverUrl)
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, info.durationMs)
            .apply {
                // Both keys: the lock screen, the shade, Bluetooth and the car do not read the same one.
                val art = cover.takeIf { withArt && info.coverUrl == lastCoverUrl && it?.isRecycled == false }
                putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, art)
                putBitmap(MediaMetadataCompat.METADATA_KEY_ART, art)
            }
            .build())
        runCatching { session.setMetadata(metadata(true)) }.onFailure {
            android.util.Log.w("Myspoti", "session art rejected, metadata without it: $it")
            session.setMetadata(metadata(false))
        }
        val state = when {
            info.buffering && info.playing -> PlaybackStateCompat.STATE_BUFFERING
            info.playing -> PlaybackStateCompat.STATE_PLAYING
            else -> PlaybackStateCompat.STATE_PAUSED
        }
        session.setShuffleMode(if (info.shuffle) PlaybackStateCompat.SHUFFLE_MODE_ALL else PlaybackStateCompat.SHUFFLE_MODE_NONE)
        session.setPlaybackState(PlaybackStateCompat.Builder()
            .setActions(if (info.episode) ACTIONS or SKIP_ACTIONS else ACTIONS)
            // Like on the left of previous / play / next, shuffle on the right: five buttons, so
            // play / pause sits in the middle (lock screen, Samsung Now Bar, Android 13+ shade).
            .addCustomAction(ACTION_LIKE, getString(if (info.liked) R.string.msp_unlike else R.string.msp_like),
                if (info.liked) R.drawable.msp_ic_liked else R.drawable.msp_ic_like)
            .addCustomAction(ACTION_SHUFFLE, getString(if (info.shuffle) R.string.msp_shuffle_off else R.string.msp_shuffle_on),
                if (info.shuffle) R.drawable.msp_ic_shuffle_on else R.drawable.msp_ic_shuffle)
            .setState(state, info.positionMs, if (info.playing && !info.buffering) 1f else 0f)
            .setActiveQueueItemId(QueueSync.activeRow(queue, activeQueueId, info.title, info.artist))
            .build())
    }

    /** The pushed queue ([updateQueue]) to the session; the row now playing is marked in the playback state. */
    private fun applyQueue() {
        session.setQueueTitle(queueTitle)
        session.setQueue(queue.map { e ->
            MediaSessionCompat.QueueItem(MediaDescriptionCompat.Builder()
                .setTitle(e.title)
                .setSubtitle(e.artist)
                .setIconUri(e.iconUri?.let(Uri::parse))
                .build(), e.id)
        }.takeIf { it.isNotEmpty() })
        // Only the marked row changes: the rest of the state (and its position clock) stays as it was.
        // A row that is not the song shown is not marked (see QueueSync: Bluetooth holds its updates).
        val state = session.controller.playbackState ?: return
        val active = QueueSync.activeRow(queue, activeQueueId, current?.title, current?.artist)
        if (state.activeQueueItemId != active) {
            session.setPlaybackState(PlaybackStateCompat.Builder(state).setActiveQueueItemId(active).build())
        }
    }

    private fun loadCover(url: String?) {
        coverJob?.cancel()
        lastCoverUrl = url
        cover = url?.let { covers.get(it) }?.takeIf { !it.isRecycled }
        if (url.isNullOrBlank() || cover != null) return
        coverJob = scope.launch {
            // Retried with a growing pause: a failed load used to be forgotten.
            var attempt = 0
            while (true) {
                val bitmap = withContext(Dispatchers.IO) { fetchCover(url) }
                if (bitmap != null) {
                    covers.put(url, bitmap)
                    if (url == lastCoverUrl) {
                        cover = bitmap
                        current?.let { apply(it); show(it) }
                        val art = session.controller.metadata?.getBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART)
                        android.util.Log.d("Myspoti", "cover $url -> ${bitmap.width}x${bitmap.height}, session art ${art?.width}x${art?.height}")
                    }
                    return@launch
                }
                delay(COVER_RETRY_MS.getOrNull(attempt++) ?: break)
            }
            android.util.Log.w("Myspoti", "cover not loaded: $url")
        }
    }

    /** The cover as a bitmap of at most [COVER_MAX_PX], from a Spotify image id, a URL or a local content URI; null on failure. */
    private fun fetchCover(url: String): Bitmap? {
        // The same ids the app resolves for Glide (SpotifyImages.kt): profile and playlist items give them.
        val resolved = when {
            url.startsWith("spotify:image:") -> "https://i.scdn.co/image/" + url.removePrefix("spotify:image:")
            url.startsWith("spotify:mosaic:") -> "https://mosaic.scdn.co/640/" + url.removePrefix("spotify:mosaic:").split(':').joinToString("")
            else -> url
        }
        // An album cover comes in 64, 300 and 640 px under the same id: ask for the 640 one first
        // (the queue and the next song may only carry the small one), the given size is the fallback.
        val candidates = listOfNotNull(
            Covers.large(resolved).takeIf { it != resolved },
            resolved,
        ).distinct()
        for (candidate in candidates) {
            val bytes = runCatching {
                if (candidate.startsWith("http")) {
                    http.newCall(Request.Builder().url(candidate).build()).execute().use { r ->
                        if (r.isSuccessful) r.body.bytes() else null.also { android.util.Log.w("Myspoti", "cover http ${r.code} $candidate") }
                    }
                } else contentResolver.openInputStream(Uri.parse(candidate))?.use { it.readBytes() }
            }.onFailure { android.util.Log.w("Myspoti", "cover $candidate: $it") }.getOrNull() ?: continue
            decode(bytes)?.let { return it }
        }
        return null
    }

    /** Never decodes more pixels than needed, never keeps more than [COVER_MAX_PX] a side. */
    private fun decode(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= COVER_MAX_PX) sample *= 2
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= COVER_MAX_PX) return bitmap
        val scale = COVER_MAX_PX.toFloat() / longest
        return Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1),
            (bitmap.height * scale).toInt().coerceAtLeast(1), true).also { if (it !== bitmap) bitmap.recycle() }
    }

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    /**
     * Leaves the foreground only after a real pause, never between two songs; the locks go with it
     * (see [show]).
     */
    private val leaveForeground = Runnable {
        if (current?.makesSound == true) return@Runnable
        holdLocks(false)
        if (foreground) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
            foreground = false
        }
    }

    private var cpuLock: android.os.PowerManager.WakeLock? = null
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null

    /**
     * Keeps the CPU and the Wi-Fi radio awake while a song plays: with the screen off the page's
     * timers and the audio downloads stalled in doze-like power saving. The timeout is only a
     * safety net against a lock leaked by a missed pause; every state refresh renews it.
     */
    @android.annotation.SuppressLint("WakelockTimeout")
    private fun holdLocks(on: Boolean) {
        runCatching {
            if (on) {
                val cpu = cpuLock ?: getSystemService(android.os.PowerManager::class.java)
                    .newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "Myspoti:playback")
                    .apply { setReferenceCounted(false) }.also { cpuLock = it }
                cpu.acquire(LOCK_TIMEOUT_MS)
                ScreenWatch.start(this)
                val wifi = wifiLock ?: applicationContext.getSystemService(android.net.wifi.WifiManager::class.java)
                    .createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Myspoti:playback")
                    .apply { setReferenceCounted(false) }.also { wifiLock = it }
                if (!wifi.isHeld) wifi.acquire()
            } else {
                ScreenWatch.stop(this)
                cpuLock?.takeIf { it.isHeld }?.release()
                wifiLock?.takeIf { it.isHeld }?.release()
            }
        }
    }

    private fun show(info: NowPlayingInfo) {
        val notification = build(info)
        if (info.makesSound) {
            holdLocks(true)
            handler.removeCallbacks(leaveForeground)
            startForegroundSafely(notification)
        } else {
            // The player is briefly "paused" while it loads the next song. Leaving the
            // foreground then let Android freeze the app with the screen off, and playback
            // stopped after one song: the service stays a minute in the foreground. The locks
            // stay for that minute too: in that gap the page has closed its audio stream, and a
            // CPU lock dropped right then left a phone with the screen off free to sleep before
            // the next song had loaded.
            if (foreground) {
                runCatching { NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification) }
                handler.removeCallbacks(leaveForeground)
                handler.postDelayed(leaveForeground, PAUSE_GRACE_MS)
            } else {
                holdLocks(false)
                runCatching { NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification) }
            }
        }
    }

    /** [always]: call startForeground() even when already in the foreground (a new foreground start). */
    private fun startForegroundSafely(notification: Notification, always: Boolean = false) {
        if (foreground && !always) {
            runCatching { NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification) }
            return
        }
        runCatching {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification,
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0)
            foreground = true
        }.onFailure { Logbook.logEvery("foreground-start", 10_000, "foreground not started: ${it.javaClass.simpleName} ${it.message?.take(80)}") }
    }

    private fun build(info: NowPlayingInfo?): Notification {
        val playing = info?.playing == true
        val episode = info?.episode == true
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.msp_ic_notification)
            .setContentTitle(info?.title ?: "")
            .setContentText(info?.artist ?: "")
            .setSubText(info?.album)
            .setLargeIcon(cover)
            .setContentIntent(launchIntent())
            .setDeleteIntent(MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_STOP))
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setOngoing(playing)
            .addAction(if (episode) R.drawable.msp_ic_replay_15 else R.drawable.msp_ic_previous,
                getString(if (episode) R.string.msp_back_15 else R.string.msp_previous),
                MediaButtonReceiver.buildMediaButtonPendingIntent(this,
                    if (episode) PlaybackStateCompat.ACTION_REWIND else PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS))
            .addAction(if (playing) R.drawable.msp_ic_pause else R.drawable.msp_ic_play, getString(if (playing) R.string.msp_pause else R.string.msp_play),
                MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_PLAY_PAUSE))
            .addAction(if (episode) R.drawable.msp_ic_forward_15 else R.drawable.msp_ic_next,
                getString(if (episode) R.string.msp_forward_15 else R.string.msp_next),
                MediaButtonReceiver.buildMediaButtonPendingIntent(this,
                    if (episode) PlaybackStateCompat.ACTION_FAST_FORWARD else PlaybackStateCompat.ACTION_SKIP_TO_NEXT))
            .setStyle(MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0, 1, 2))
            .build()
    }

    private fun launchIntent(): PendingIntent? {
        val intent = packageManager.getLaunchIntentForPackage(packageName) ?: return null
        return PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, getString(R.string.msp_channel_playback), NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            })
        }
    }

    /** Playback on a Connect device: the volume keys drive that device instead of the phone. */
    private fun setRemote(remote: Boolean, volume: Int) {
        playingRemote = remote
        if (!remote) {
            session.setPlaybackToLocal(android.media.AudioManager.STREAM_MUSIC)
            return
        }
        session.setPlaybackToRemote(object : VolumeProviderCompat(VOLUME_CONTROL_ABSOLUTE, 100, volume) {
            override fun onSetVolumeTo(volume: Int) { currentVolume = volume; controls?.setRemoteVolume(volume) }
            override fun onAdjustVolume(direction: Int) {
                val v = (currentVolume + direction * 5).coerceIn(0, 100)
                currentVolume = v
                controls?.setRemoteVolume(v)
            }
        })
    }

    // Media browser (Android Auto).
    override fun onGetRoot(clientPackageName: String, clientUid: Int, rootHints: Bundle?): BrowserRoot? {
        // The service is exported (Android Auto requires it), but the library —
        // playlists, Liked Songs, search — is only for Auto, Assistant, Bluetooth,
        // Wear, System UI, other system apps and the app itself. Any other app
        // gets an empty root: it can still connect and use the session's
        // play/pause controls, but browsing and search return nothing.
        val packages = runCatching { packageManager.getPackagesForUid(clientUid)?.toList() }.getOrNull().orEmpty()
        if (clientPackageName != packageName && clientPackageName !in packages) return null
        if (clientPackageName != packageName) Logbook.log("media browser connected: $clientPackageName (browse: ${if (mayBrowse(clientPackageName)) "yes" else "no"})")
        if (!mayBrowse(clientPackageName)) {
            android.util.Log.w("Myspoti", "media browser: empty root for $clientPackageName")
            return BrowserRoot(EMPTY_ROOT_ID, null)
        }
        // Media resumption (Android Auto on a new connection, the system's resume card): the system asks
        // for the "recent" root, whose only item is what played last. Without it a reconnected car has
        // nothing to restart (#142).
        if (rootHints?.getBoolean(BrowserRoot.EXTRA_RECENT) == true) {
            return BrowserRoot(RECENT_ROOT_ID, Bundle().apply { putBoolean(BrowserRoot.EXTRA_RECENT, true) })
        }
        return BrowserRoot(ROOT_ID, Bundle().apply {
            putBoolean("android.media.browse.SEARCH_SUPPORTED", true)
            // Without this the car ignores the list / grid hints of the folders (AutoBrowser).
            putBoolean("android.media.browse.CONTENT_STYLE_SUPPORTED", true)
            putInt("android.media.browse.CONTENT_STYLE_BROWSABLE_HINT", 1)
            putInt("android.media.browse.CONTENT_STYLE_PLAYABLE_HINT", 1)
        })
    }

    override fun onLoadChildren(parentId: String, result: Result<MutableList<MediaBrowserCompat.MediaItem>>) {
        // A client may subscribe to any id, not only the root it was given:
        // check the caller here too.
        if (parentId == EMPTY_ROOT_ID || !callerMayBrowse()) return result.sendResult(mutableListOf())
        if (parentId == RECENT_ROOT_ID) return result.sendResult(recentItems())
        val lib = library ?: return result.sendResult(mutableListOf())
        result.detach()
        scope.launch {
            val outcome = runCatching { lib.children(this@PlaybackService, parentId).toMutableList() }
            val items = outcome.getOrNull()
            if (items == null) {
                // The library waits for the player itself; reaching here means it never came
                // up. MediaBrowserCompat has no error reply on API 26+ (sendError throws), so
                // an empty list is the only answer: the log names the cause of a cold start
                // that failed (#41), and the folder loads on the next browse.
                android.util.Log.w("Myspoti", "browse $parentId: ${outcome.exceptionOrNull()?.message}")
            }
            result.sendResult(items ?: mutableListOf())
        }
    }

    override fun onSearch(query: String, extras: Bundle?, result: Result<MutableList<MediaBrowserCompat.MediaItem>>) {
        if (!callerMayBrowse()) return result.sendResult(mutableListOf())
        val lib = library ?: return result.sendResult(mutableListOf())
        result.detach()
        scope.launch {
            val outcome = runCatching { lib.search(query).toMutableList() }
            val items = outcome.getOrNull()
            if (items == null) android.util.Log.w("Myspoti", "search: ${outcome.exceptionOrNull()?.message}")
            result.sendResult(items ?: mutableListOf())
        }
    }

    /** The song played last, as the one playable item of the resumption root: a tap plays on ([RESUME_ID]). */
    private fun recentItems(): MutableList<MediaBrowserCompat.MediaItem> {
        val info = current?.takeIf { it.title.isNotBlank() } ?: return mutableListOf()
        val icon = info.coverUrl?.takeIf { it.startsWith("http") }
        return mutableListOf(MediaBrowserCompat.MediaItem(MediaDescriptionCompat.Builder()
            .setMediaId(RESUME_ID).setTitle(info.title).setSubtitle(info.artist).setIconUri(icon?.let(Uri::parse))
            .build(), MediaBrowserCompat.MediaItem.FLAG_PLAYABLE))
    }

    /** The app itself, the listed browsers, and system apps may browse the library. */
    private fun mayBrowse(clientPackageName: String): Boolean {
        if (clientPackageName == packageName || clientPackageName in ALLOWED_BROWSERS) return true
        val flags = runCatching { packageManager.getApplicationInfo(clientPackageName, 0).flags }.getOrDefault(0)
        return (flags and ApplicationInfo.FLAG_SYSTEM) != 0
    }

    /** Whether the client behind the current onLoadChildren/onSearch call may browse. */
    private fun callerMayBrowse(): Boolean {
        val caller = runCatching { currentBrowserInfo }.getOrNull() ?: return false
        val packages = runCatching { packageManager.getPackagesForUid(caller.uid)?.toList() }.getOrNull().orEmpty()
        return caller.packageName in packages && mayBrowse(caller.packageName)
    }

    companion object {
        /** Time left to the pause's fade-out before the notification goes. */
        private const val CLOSE_STOP_MS = 800L
        private const val CLOSE_PREFS = "playback_service"
        private const val LOCK_TIMEOUT_MS = 6 * 60 * 60 * 1000L
        private const val KEEP_PLAYING_KEY = "keep_playing_on_close"

        /** The music goes on when the app is closed from the recent apps (off: it stops). */
        fun keepsPlayingOnClose(context: Context): Boolean =
            context.getSharedPreferences(CLOSE_PREFS, Context.MODE_PRIVATE).getBoolean(KEEP_PLAYING_KEY, false)

        fun setKeepsPlayingOnClose(context: Context, on: Boolean) =
            context.getSharedPreferences(CLOSE_PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEEP_PLAYING_KEY, on).apply()

        private const val SKIP_KEY = "headset_skips_on_episodes"

        /** Earbud next / previous skip 15 s on a podcast episode (default); off: next / previous episode. */
        fun skipsOnEpisodes(context: Context): Boolean =
            context.getSharedPreferences(CLOSE_PREFS, Context.MODE_PRIVATE).getBoolean(SKIP_KEY, true)

        fun setSkipsOnEpisodes(context: Context, on: Boolean) =
            context.getSharedPreferences(CLOSE_PREFS, Context.MODE_PRIVATE).edit().putBoolean(SKIP_KEY, on).apply()

        const val ROOT_ID = "root"
        /** Root for callers that may control playback but not browse (see onGetRoot). */
        private const val EMPTY_ROOT_ID = "empty"
        /** Root asked for with EXTRA_RECENT: the last song, to resume it (see onGetRoot). */
        private const val RECENT_ROOT_ID = "recent"
        private const val RESUME_ID = "resume"
        private const val CHANNEL_ID = "playback"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_LIKE = "like"
        private const val ACTION_SHUFFLE = "shuffle"
        /** Everything the car, the lock screen and the notification may ask for. */
        private val ACTIONS = PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or PlaybackStateCompat.ACTION_PLAY_PAUSE or
            PlaybackStateCompat.ACTION_SKIP_TO_NEXT or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
            PlaybackStateCompat.ACTION_SEEK_TO or PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID or
            PlaybackStateCompat.ACTION_PLAY_FROM_SEARCH or PlaybackStateCompat.ACTION_SKIP_TO_QUEUE_ITEM or
            PlaybackStateCompat.ACTION_SET_SHUFFLE_MODE
        /** Added while an episode plays: the notification's 15 s buttons and the keys of the same name. */
        private val SKIP_ACTIONS = PlaybackStateCompat.ACTION_FAST_FORWARD or PlaybackStateCompat.ACTION_REWIND
        private val http: OkHttpClient by lazy { TlsCompat.apply(OkHttpClient.Builder()).build() }
        /** The largest side kept for artwork: what Android's media controls expect (640 is Spotify's biggest cover). */
        private const val COVER_MAX_PX = 640
        /** About four 640 px covers. */
        private const val COVER_CACHE_BYTES = 8 * 1024 * 1024
        /** Pause before each new try of a failed cover; after the last one the cover waits for the next state change. */
        private val COVER_RETRY_MS = longArrayOf(2_000L, 6_000L, 15_000L)

        @Volatile private var instance: PlaybackService? = null
        @Volatile private var current: NowPlayingInfo? = null
        /** The queue the car shows, kept like [current] until the session exists (see [updateQueue]). */
        @Volatile private var queue: List<QueueEntry> = emptyList()
        @Volatile private var queueTitle: String? = null
        @Volatile private var activeQueueId = MediaSessionCompat.QueueItem.UNKNOWN_ID.toLong()

        /** True while the service runs (its notification is up): the app may be in the background. */
        val isRunning: Boolean get() = instance != null

        /** What the service holds, for the screen off / on lines of the diagnostic ([ScreenWatch]). */
        internal fun statusLine(): String {
            val svc = instance
            val c = current
            val service = when { svc == null -> "none"; svc.foreground -> "foreground"; else -> "background" }
            return "service $service, cpu lock held=${svc?.cpuLock?.isHeld}, wifi lock held=${svc?.wifiLock?.isHeld}, " +
                "playing=${c?.playing} buffering=${c?.buffering}"
        }

        /** The position the notification was last given. */
        internal fun statusPosition(): Long = current?.positionMs ?: 0L

        private const val PAUSE_GRACE_MS = 60_000L
        /** A state that did not change is republished at most that often (see [update]). */
        private const val POSITION_REPUSH_MS = 10_000L

        /** Packages allowed to browse the library (see onGetRoot). */
        private val ALLOWED_BROWSERS = setOf(
            "com.google.android.projection.gearhead", // Android Auto (phone)
            "com.google.android.apps.automotive.templates.host", // Android Automotive OS
            "com.google.android.googlequicksearchbox", // Google Assistant
            "com.google.android.carassistant",
            "com.android.systemui",
            "com.android.bluetooth", // car stereos browsing over Bluetooth AVRCP
            "com.google.android.wearable.app", // Wear OS
            "com.android.car.media", // Android Automotive media center
        )

        /** Set once by the app. */
        @Volatile var controls: PlaybackControls? = null
        @Volatile var library: MediaLibrary? = null

        /** Pushes the now-playing state; starts the service when playback starts. */
        fun update(context: Context, info: NowPlayingInfo) {
            val before = current
            current = info
            val svc = instance
            if (svc == null) {
                // Loading counts: Android gives the audio only to a player in the foreground, and a
                // song an extension is still resolving starts right after.
                if (info.makesSound) runCatching {
                    androidx.core.content.ContextCompat.startForegroundService(context, Intent(context, PlaybackService::class.java))
                }.onFailure { Logbook.logEvery("service-start", 10_000, "service not started for playback: ${it.javaClass.simpleName} ${it.message?.take(80)}") }
                return
            }
            // The player reports several times per second (position, buffering): only a real
            // change reaches the session and the notification, the system extrapolates the
            // position in between (the known state is republished every POSITION_REPUSH_MS).
            if (before != null && before.sameAs(info) &&
                kotlin.math.abs(info.positionMs - before.positionMs) < POSITION_REPUSH_MS
            ) return
            svc.apply(info)
            svc.show(info)
            // Sound with no foreground service (bound by the car only, promotion refused): start it.
            if (info.makesSound && !svc.foreground) svc.ensureStarted()
        }

        /**
         * Pushes the queue the car shows: [items] in order, [activeId] the row now playing (none: null).
         * Call on the main thread and only when the queue changes, never with the position; an
         * unchanged queue is not sent again.
         */
        fun updateQueue(title: String?, items: List<QueueEntry>, activeId: Long?) {
            val active = activeId ?: MediaSessionCompat.QueueItem.UNKNOWN_ID.toLong()
            if (title == queueTitle && items == queue && active == activeQueueId) return
            queue = items
            queueTitle = title
            activeQueueId = active
            instance?.applyQueue()
        }

        /** Signed out: the notification and the media session go, nothing of the account stays. */
        fun end() {
            current = null
            queue = emptyList(); queueTitle = null; activeQueueId = MediaSessionCompat.QueueItem.UNKNOWN_ID.toLong()
            instance?.let { svc ->
                svc.applyQueue()
                svc.handler.removeCallbacksAndMessages(null)
                ServiceCompat.stopForeground(svc, ServiceCompat.STOP_FOREGROUND_REMOVE)
                svc.foreground = false
                svc.session.isActive = false
                svc.stopSelf()
            }
        }

        /** [remote] when a Connect device plays; [volume] 0..100. */
        fun setPlaybackDevice(remote: Boolean, volume: Int) { instance?.setRemote(remote, volume) }
    }
}
