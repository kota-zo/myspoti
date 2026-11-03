package com.music.spotui.di

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper

/**
 * The "bop" between two songs on the phone's speaker: the Web Player closes its audio
 * stream at the end of a song and opens a new one for the next, and the phone powers
 * its amplifier down and up again in between. A silent stream of our own, kept open
 * while the music plays here (and a little after it stops), keeps the output awake
 * across the change. It asks for no audio focus and plays nothing audible.
 */
object SpeakerKeepAlive {
    private const val RATE = 48_000
    /** Pauses and song changes shorter than this keep the stream (and the output) open. */
    private const val LINGER_MS = 15_000L
    /** A stream that failed is opened again after this long at the soonest. */
    private const val RETRY_MS = 10_000L

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var track: AudioTrack? = null
    @Volatile private var feeder: Thread? = null
    private val release = Runnable { stop() }
    /** When the last stream failed (see [start]); 0: none. */
    @Volatile private var failedAt = 0L

    /** [playingHere]: music plays on this phone (not on a Connect device). */
    fun set(playingHere: Boolean) = main.post {
        if (playingHere) {
            main.removeCallbacks(release)
            start()
        } else if (track != null) {
            main.removeCallbacks(release)
            main.postDelayed(release, LINGER_MS)
        }
    }

    private fun start() {
        if (track != null) return
        // A stream that just failed is not rebuilt on every state the page reports.
        if (failedAt != 0L && android.os.SystemClock.elapsedRealtime() - failedAt < RETRY_MS) return
        // A second of buffer, written half at a time: two wakes a second instead of 25 to 50 with the
        // minimum buffer (silence needs no low latency).
        val size = maxOf(AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT), RATE * 2)
        val t = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build())
                .setAudioFormat(AudioFormat.Builder()
                    .setSampleRate(RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build())
                .setBufferSizeInBytes(size)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_POWER_SAVING)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        }.getOrNull() ?: return
        track = t
        runCatching { t.play() }
        feeder = Thread({
            val zeros = ByteArray(size / 2)
            // Blocking writes pace themselves on the output: no busy loop.
            while (track === t) if (t.write(zeros, 0, zeros.size) < 0) break
            // The stream failed (the audio server restarted, the output went away): it is let go,
            // and the next song playing opens a new one. Kept, it held its place for the rest of
            // the session while feeding nothing, and nothing held the output open any more.
            if (track === t) main.post { if (track === t) { failedAt = android.os.SystemClock.elapsedRealtime(); stop() } }
        }, "speaker-keepalive").apply { isDaemon = true; start() }
    }

    private fun stop() {
        val t = track ?: return
        track = null
        feeder = null
        runCatching { t.pause(); t.flush(); t.stop() }
        t.release()
    }
}
