package app.myspoti.host.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch

/**
 * The screen going off and on, in the log, while a song plays: music that stops "30-40 s after the
 * lock" (Samsung, One UI) could not be told from a frozen app, a cut Wi-Fi or a service Android
 * never promoted, because nothing said what the app held at that moment. Registered by
 * [PlaybackService] only while it plays; it logs, it never pauses or restarts anything.
 */
internal object ScreenWatch {
    /** How long after the screen goes off the playback is looked at once more. */
    private const val CHECK_MS = 30_000L

    private val handler = Handler(Looper.getMainLooper())
    private var receiver: BroadcastReceiver? = null
    private var offAt = 0L
    private var positionAtOff = 0L

    fun start(context: Context) {
        if (receiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                if (intent.action == Intent.ACTION_SCREEN_OFF) off() else on()
            }
        }
        receiver = r
        ContextCompat.registerReceiver(
            context.applicationContext, r,
            IntentFilter().apply { addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON) },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    fun stop(context: Context) {
        val r = receiver ?: return
        receiver = null
        handler.removeCallbacks(check)
        offAt = 0L
        runCatching { context.applicationContext.unregisterReceiver(r) }
    }

    private fun off() {
        offAt = SystemClock.elapsedRealtime()
        positionAtOff = PlaybackService.statusPosition()
        Logbook.log("screen off: ${PlaybackService.statusLine()}")
        handler.removeCallbacks(check)
        handler.postDelayed(check, CHECK_MS)
    }

    private fun on() {
        handler.removeCallbacks(check)
        val away = if (offAt > 0) " after ${(SystemClock.elapsedRealtime() - offAt) / 1000} s off" else ""
        offAt = 0L
        Logbook.log("screen on$away: ${PlaybackService.statusLine()}")
    }

    /** Position moving (the notification's), the sound standing still (the watcher's) and the page answering, 30 s into the lock. */
    private val check = Runnable {
        val moved = PlaybackService.statusPosition() - positionAtOff
        val line = "screen off for ${CHECK_MS / 1000} s: ${PlaybackService.statusLine()} position moved ${moved} ms since"
        if (!MyspotiRuntime.isStarted) { Logbook.log("$line, page not started"); return@Runnable }
        MyspotiRuntime.scope.launch {
            val started = SystemClock.elapsedRealtime()
            val answers = runCatching { MyspotiRuntime.spotify.bridge.ping(PING_MS) }.getOrDefault(false)
            val still = runCatching { MyspotiRuntime.spotify.player.stalled.value }.getOrNull()
            val page = if (answers) "answers in ${SystemClock.elapsedRealtime() - started} ms" else "does not answer"
            Logbook.log("$line, sound standing still=$still, Spotify page $page")
        }
    }

    private const val PING_MS = 3_000L
}
