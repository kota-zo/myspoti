package com.music.spotui.di

import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * How late the sound is heard after the player plays it: Bluetooth delays it (~150-250 ms with
 * SBC / AAC / LDAC). What is shown in time with the song (clip, lyrics) follows what is heard.
 */
object OutputLag {
    private const val CHECK_MS = 3_000L
    /** One figure for every Bluetooth codec: close enough for lines and words. */
    private const val BLUETOOTH_MS = 200L

    @Volatile private var lag = 0L
    @Volatile private var askedAt = 0L
    private val asking = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Called from the lyrics' frame path and the clip's sync loop: the route inquiry (audio
     * service, a binder call) runs on a worker, never on the frame that asked for it.
     */
    fun ms(): Long {
        val now = SystemClock.elapsedRealtime()
        if (now - askedAt > CHECK_MS && asking.compareAndSet(false, true)) {
            askedAt = now
            app.myspoti.host.android.MyspotiRuntime.scope.launch(Dispatchers.IO) {
                try {
                    val route = com.music.spotui.ui.components.AudioRoute.current(Graph.app)
                    val l = if (route.kind == com.music.spotui.ui.components.AudioRoute.Kind.BLUETOOTH) BLUETOOTH_MS else 0L
                    if (l != lag) app.myspoti.host.android.Logbook.log("sound heard $l ms late (${route.kind.name.lowercase()}): clip and lyrics follow")
                    lag = l
                } finally {
                    asking.set(false)
                }
            }
        }
        return lag
    }
}
