package app.myspoti.spotify.bridge

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether the page's playback device has joined Spotify Connect since the page last loaded. A new
 * page says "ready" before that: a command sent then is refused (log seen: a play 0.3 s after
 * "ready", answered DEVICE_NOT_FOUND, the device's join coming 0.3 s later). The host reports the
 * join (the page's connect-state PUT answered); the player and the bridge report a page going away.
 */
object PageDevice {
    private val _joined = MutableStateFlow(false)
    /** The current page's device has joined. */
    val joined: StateFlow<Boolean> = _joined.asStateFlow()

    /** A join was reported once in this process: the host reports them, so [joined] can be waited for. */
    @Volatile var reported = false
        private set

    /** The page's device joined Spotify Connect (its connect-state PUT was answered 2xx). */
    fun joined() { reported = true; _joined.value = true }

    /**
     * When a playback command was last answered DEVICE_NOT_FOUND (0: never). The page's own call
     * answers "ok" before its Connect command goes out, so the refusal reaches only the host's relay.
     */
    @Volatile var commandLostAt = 0L
        private set

    /** A playback command the page sent was answered 404 DEVICE_NOT_FOUND. */
    fun commandLost() { commandLostAt = System.currentTimeMillis() }

    /** The page is going away (a reload asked for or started): its device is not one to command. */
    fun pageGone() { _joined.value = false }
}
