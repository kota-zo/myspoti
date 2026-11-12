package app.myspoti.host.android

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock

/**
 * Says in the diagnostic report what held the main thread when the app froze for a moment
 * (reported: "micro hangs" on opening the app or switching tasks, with nothing in the log to
 * say why). A background thread asks the main thread for a sign of life every [PING_MS]; when
 * none comes for [STALL_MS], the main thread's stack is read a few times until it answers, and
 * the frames seen most are logged with the length of the stall.
 *
 * Runs only while the app is on screen (and [TAIL_MS] after, for the stall of leaving it).
 */
object MainThreadWatch {
    /**
     * Ten pings a second woke both threads all the time the app was on screen (beta and debug
     * builds); a stall of [STALL_MS] or more is still caught, its start known to half a second.
     */
    private const val PING_MS = 500L
    private const val STALL_MS = 150L
    private const val TAIL_MS = 3_000L
    private const val FRAMES = 6

    private val main = Handler(Looper.getMainLooper())
    private val thread by lazy { HandlerThread("main-watch").also { it.start() } }
    private val watch by lazy { Handler(thread.looper) }

    @Volatile private var askedAt = 0L
    @Volatile private var answered = true
    @Volatile private var answeredAt = 0L
    @Volatile private var runUntil = 0L
    @Volatile private var running = false
    private var stalled = false
    private val seen = HashMap<String, Int>()

    private val pong = Runnable { answeredAt = SystemClock.uptimeMillis(); answered = true }

    private val tick: Runnable = object : Runnable {
        override fun run() {
            val now = SystemClock.uptimeMillis()
            if (!answered) {
                // No answer yet: past STALL_MS, the main thread is read until it answers.
                if (now - askedAt >= STALL_MS) { stalled = true; sample() }
                watch.postDelayed(this, 50L)
                return
            }
            if (stalled) report(answeredAt - askedAt)
            if (now > runUntil) { running = false; return }
            askedAt = now; answered = false
            main.post(pong)
            watch.postDelayed(this, PING_MS)
        }
    }

    /** The app came on screen: watched until [stop] and a little after. */
    fun start() {
        runUntil = Long.MAX_VALUE
        if (running) return
        running = true
        answered = true
        watch.post(tick)
    }

    /** The app left the screen: still watched for [TAIL_MS]. */
    fun stop() { runUntil = SystemClock.uptimeMillis() + TAIL_MS }

    private fun sample() {
        val stack = Looper.getMainLooper().thread.stackTrace
        // What the thread is in (often the framework: a lock, a file, the WebView), then the app's
        // own frames that led there. Release builds rename the app's classes: the build's mapping
        // file reads them back.
        val inner = stack.take(3)
        val own = stack.drop(3).filterNot { f -> FRAMEWORK.any { f.className.startsWith(it) } }.take(FRAMES)
        val key = (inner + own).joinToString(" < ") { "${it.className}.${it.methodName}:${it.lineNumber}" }
        seen[key] = (seen[key] ?: 0) + 1
    }

    private val FRAMEWORK = listOf("android.", "androidx.", "java.", "javax.", "kotlin.", "kotlinx.", "com.android.", "dalvik.", "libcore.", "sun.", "org.chromium.", "com.google.")

    private fun report(ms: Long) {
        val top = seen.maxByOrNull { it.value }?.key
        stalled = false
        seen.clear()
        if (top != null) Logbook.log("main thread held $ms ms: $top")
    }
}
