package app.myspoti.host.android

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import java.io.File

/**
 * How the app last ended, put back in the diagnostic report of the next run: the report lives in
 * memory and died with the process, so a crash or a freeze ("app isn't responding") left nothing
 * to read (reported: crashes while typing in a playlist's search, with an empty report after).
 *
 * - A Kotlin/Java crash: its stack, written to storage by the default handler before Android ends
 *   the app, read and deleted at the next start.
 * - A freeze Android ended (ANR) or a native crash: Android's own record of the exit (API 30+),
 *   with the main thread's stack for an ANR.
 *
 * Release builds rename the app's classes: the build's mapping file reads them back.
 */
object LastExit {
    private const val FILE = "last_crash.txt"
    private const val PREFS = "last_exit"
    private const val SEEN = "seen_at"

    /** Called first thing at start: records crashes from now on, reports the last exit. */
    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val text = buildString {
                    append("crash on ").append(thread.name).append(": ").append(error.toString()).append('\n')
                    error.stackTrace.take(40).forEach { append("  at ").append(it.toString()).append('\n') }
                    generateSequence(error.cause) { it.cause }.take(3).forEach { c ->
                        append("caused by ").append(c.toString()).append('\n')
                        c.stackTrace.take(15).forEach { append("  at ").append(it.toString()).append('\n') }
                    }
                }
                File(app.filesDir, FILE).writeText(text)
            }
            previous?.uncaughtException(thread, error)
        }
        runCatching { reportCrashFile(app) }
        runCatching { reportExitInfo(app) }
    }

    private fun reportCrashFile(context: Context) {
        val f = File(context.filesDir, FILE)
        if (!f.exists()) return
        Logbook.log("previous run crashed:")
        f.readLines().take(60).forEach { Logbook.log("  $it") }
        f.delete()
    }

    private fun reportExitInfo(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val am = context.getSystemService(ActivityManager::class.java) ?: return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val seen = prefs.getLong(SEEN, 0L)
        val exits = am.getHistoricalProcessExitReasons(context.packageName, 0, 5).filter { it.timestamp > seen }
        exits.maxOfOrNull { it.timestamp }?.let { prefs.edit().putLong(SEEN, it).apply() }
        for (e in exits.sortedBy { it.timestamp }) {
            val reason = when (e.reason) {
                ApplicationExitInfo.REASON_ANR -> "not responding (ANR)"
                ApplicationExitInfo.REASON_CRASH -> "crash"
                ApplicationExitInfo.REASON_CRASH_NATIVE -> "native crash"
                ApplicationExitInfo.REASON_LOW_MEMORY -> "low memory"
                ApplicationExitInfo.REASON_USER_REQUESTED, ApplicationExitInfo.REASON_USER_STOPPED -> continue
                ApplicationExitInfo.REASON_EXIT_SELF, ApplicationExitInfo.REASON_SIGNALED -> continue
                else -> "reason ${e.reason}"
            }
            val at = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date(e.timestamp))
            Logbook.log("previous exit at $at: $reason${e.description?.let { " ($it)" } ?: ""}")
            // An ANR's trace: the main thread's stack is what was holding it.
            if (e.reason == ApplicationExitInfo.REASON_ANR) runCatching {
                val trace = e.traceInputStream?.bufferedReader()?.use { it.readText() } ?: return@runCatching
                val main = trace.substringAfter("\"main\"", "").lineSequence().take(30).toList()
                main.forEach { Logbook.log("  $it") }
            }
            // A native crash's record is Android's tombstone (a protobuf): its readable strings, the
            // abort message, the signal and the stack's libraries and functions, say where it was
            // (dev 3135 crashed natively and the report had nothing but "native crash").
            if (e.reason == ApplicationExitInfo.REASON_CRASH_NATIVE) runCatching {
                val bytes = e.traceInputStream?.use { it.readBytes() } ?: return@runCatching
                tombstoneLines(bytes).forEach { Logbook.log("  $it") }
            }
        }
    }

    /**
     * The tombstone's printable strings worth reading: the abort message and signal first, then the
     * frames' libraries and functions (de-duplicated, 40 at most). No parsing of the protobuf: its
     * strings are stored as they are.
     */
    internal fun tombstoneLines(bytes: ByteArray): List<String> {
        val strings = ArrayList<String>()
        val cur = StringBuilder()
        fun flush() { if (cur.length >= 6) strings += cur.toString(); cur.setLength(0) }
        for (b in bytes) { val c = b.toInt() and 0xff; if (c in 0x20..0x7e) cur.append(c.toChar()) else flush() }
        flush()
        val wanted = Regex("abort|signal|SIG[A-Z]+|JNI|Exception|Error|\\.so\\b|\\.apk|java_|Java_|onnx|art::", RegexOption.IGNORE_CASE)
        return strings.filter { wanted.containsMatchIn(it) }.distinct().take(40).map { it.take(200) }
    }
}
