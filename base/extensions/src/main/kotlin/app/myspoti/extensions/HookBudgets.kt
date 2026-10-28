package app.myspoti.extensions

/**
 * How long each hook call and event may run, in ms: one table for the app and the desktop runner
 * (`myspoti-ext`), so an extension tried on a computer meets the times it gets on the phone.
 */
object HookBudgets {
    /** A lyrics hook call. */
    const val LYRICS = 8_000L
    /** An audio-source hook call. */
    const val AUDIO = 15_000L
    /** A download-source hook call (an extension may try several places). */
    const val DOWNLOAD = 30_000L
    /** A block, tap or page-message hook call: the screen waits for it. */
    const val BLOCKS = 3_000L
    /** Its web page. */
    const val PAGE = 5_000L
    /** A `status` probe: it never holds a song's audio longer than this. */
    const val STATUS = 5_000L
    /** A `track` or `playback` event. */
    const val TRACK = 3_000L
    /** An `online` event: the extension sends what waited. */
    const val ONLINE = 30_000L
    /** A `setting` event: a button may pair again with a server. */
    const val SETTING = 30_000L
    /** An `auth` event. */
    const val AUTH = 10_000L

    /** The budget of a call to [hook]. */
    fun of(hook: Hook): Long = when (hook) {
        Hook.LYRICS -> LYRICS
        Hook.AUDIO_SOURCE -> AUDIO
        Hook.DOWNLOAD_SOURCE -> DOWNLOAD
        Hook.PAGE -> PAGE
        Hook.STATUS -> STATUS
        Hook.TRACK_INFO, Hook.HOME, Hook.SEARCH, Hook.MENU, Hook.PLAYER, Hook.MESSAGE, Hook.TAP -> BLOCKS
    }

    /** The budget of [event] (`ExtensionRuntime.EVENT_*`); an unknown one gets [TRACK]. */
    fun ofEvent(event: String): Long = when (event) {
        ExtensionRuntime.EVENT_ONLINE -> ONLINE
        ExtensionRuntime.EVENT_SETTING -> SETTING
        ExtensionRuntime.EVENT_AUTH -> AUTH
        else -> TRACK
    }
}
