package app.myspoti.host.android

/**
 * Which locks the playback service holds, from the states it is given ([PlaybackService]).
 *
 * - The CPU lock while this phone makes sound (or is between two songs, [decide]'s `sounding`):
 *   with the screen off the players' timers stall without it. Never while another device plays
 *   through Spotify Connect: the phone is only a remote then, the Connect messages wake it when
 *   that device changes something, and the session extrapolates the position meanwhile.
 * - The Wi-Fi lock (Wi-Fi out of power save) only while a network stream loads: a song starting,
 *   a seek, a buffering spell, the next song fetched ahead ([noteLoad]), and [wifiHoldMs] after
 *   each. The audio itself is buffered well ahead, and power save adds only its wake-up latency
 *   (about 100 ms) to a download; out of power save the radio cost about 80 to 200 mW for every
 *   hour of listening. Never for a file of the phone or a download ([NowPlayingInfo.streamed]).
 *
 * Pure (the clock is given): unit-tested in PlaybackLocksTest.
 */
internal class PlaybackLocks(private val wifiHoldMs: Long = WIFI_HOLD_MS) {
    /** What to hold now; [recheckInMs]: when to decide again with no new state (the Wi-Fi lock's end). */
    data class Held(val cpu: Boolean, val wifi: Boolean, val recheckInMs: Long? = null)

    /** When a load was last seen (a song's start, a seek, buffering, a fetch ahead). */
    private var loadAt = NEVER
    private var lastId: String? = null
    private var lastBuffering = false
    private var lastPositionMs = 0L
    private var lastAt = 0L
    private var lastRunning = false

    /** A fetch over the network the states do not show (the next song loaded ahead): the Wi-Fi lock covers it. */
    fun noteLoad(now: Long) { loadAt = now }

    /**
     * [sounding]: the phone makes sound, or is in the pause between two songs that keeps the
     * service in the foreground. [remote]: another device plays through Spotify Connect.
     */
    fun decide(info: NowPlayingInfo?, sounding: Boolean, remote: Boolean, now: Long): Held {
        if (info == null || !sounding || remote) {
            // Whatever plays next (a resume, the phone taking playback back) starts loading again.
            lastId = null
            lastBuffering = false
            return Held(cpu = false, wifi = false)
        }
        val expected = lastPositionMs + if (lastRunning) now - lastAt else 0L
        val jumped = lastId == info.id && kotlin.math.abs(info.positionMs - expected) > SEEK_JUMP_MS
        if (info.id != lastId || jumped || info.buffering || lastBuffering) loadAt = now
        lastId = info.id
        lastBuffering = info.buffering
        lastPositionMs = info.positionMs
        lastAt = now
        lastRunning = info.playing && !info.buffering
        if (!info.streamed) return Held(cpu = true, wifi = false)
        // Buffering: held until a state says it is over, then [wifiHoldMs] more.
        if (info.buffering) return Held(cpu = true, wifi = true)
        val left = loadAt + wifiHoldMs - now
        return if (left > 0) Held(cpu = true, wifi = true, recheckInMs = left) else Held(cpu = true, wifi = false)
    }

    companion object {
        /** The Wi-Fi lock stays this long after the last sign of a load: the player fills its buffer meanwhile. */
        const val WIFI_HOLD_MS = 20_000L
        /** A position this far from where the clock had it is a seek (or a restart): the stream loads again. */
        const val SEEK_JUMP_MS = 2_000L
        private const val NEVER = Long.MIN_VALUE / 4
    }
}
