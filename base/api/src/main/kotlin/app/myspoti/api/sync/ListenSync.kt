package app.myspoti.api.sync

import app.myspoti.api.NowPlaying
import app.myspoti.api.PlayerState
import app.myspoti.api.Uri

/**
 * The listen sync of an account provider (Spotify first; YouTube Music or Deezer can implement the
 * same contract): while the local player plays a song an extension streams live, the provider's own
 * player in the hidden page plays the same track, unheard and at its lowest quality, at the same
 * place, so the listen is a real play on the user's account (its history, its recommendations) and
 * the phone stays one device of the account's remote control (Spotify Connect) whatever makes the
 * sound. Remote commands from the provider's other apps (pause, seek, skip, a pick) reach that page,
 * and the sync has the local player follow them through [ListenSyncHost]; the phone's own controls
 * drive the local player, and the sync has the page follow it. The provider's queue is kept as the
 * local one while it runs. Only the page's own playback does this: the sync sends no playback
 * report of its own.
 *
 * Only live extension streams are synced: a download or a file of the phone plays alone. Everything
 * is called on the main thread; the implementation sends its page commands in the background.
 */
interface ListenSync {
    /** The provider's key, as in [SyncTrack.ids] (`"spotify"`). */
    val provider: String

    /** The sync holds the provider's page now: unheard, at its lowest quality, following and followed. */
    val running: Boolean

    /** Playback was handed to another device of the account and has not come back to play here yet. */
    val waitsBack: Boolean

    /** Starts following the page's own status (ads, reloads, another device taking playback). Once, at start. */
    fun attach()

    /** The local player's song or state changed. */
    fun onLocal(now: LocalNow)

    /** The local player played its song again from 0:00 (repeat): the page plays it anew too, a new play. */
    fun onLocalRepeat()

    /**
     * A state of the provider's page while the sync runs and the local player plays: another
     * device's command for the local player to follow, or the echo of the sync's own command.
     */
    fun onProviderState(s: PlayerState)

    /**
     * A state of the provider's page while no local player plays. [moved]: it started or changed
     * song; [intended]: the app asked for it. Playback back on this phone, or moved here by another
     * device, goes back through the extensions ([ListenSyncHost.playHereAgain]).
     */
    fun onProviderPlayback(s: PlayerState, moved: Boolean, intended: Boolean)

    /**
     * Ends the sync: the page is heard again, at the user's quality. [pauseRemote]: the song it played
     * is paused first (not when another device has playback, or a transfer moves it). True when it ran.
     */
    fun stop(reason: String, pauseRemote: Boolean = true): Boolean

    /** Waits until the last sync's end reached the page (see [stop]): a transport command waits for it. */
    suspend fun awaitEnded()

    /** The app moves playback to another device ([device]): the sync ends there (the transfer moves the song). */
    fun leaving(device: String)

    /**
     * The setting, the playback speed or a track's known id changed: the sync ends when it no
     * longer applies, or looks at the local player again (and may start).
     */
    fun refresh()

    /** What the sync does now, for the diagnostic. */
    fun status(): ListenSyncStatus

    /** The local player's queued songs (after the current one, in order) as published. */
    fun localQueueChanged(queued: List<SyncTrack>)

    /**
     * A local player starts on a list whose songs after the picked one are [localNext]: the
     * provider's queued tracks (the account's, maybe queued on another device) play right after the
     * picked song, as queued songs. For each queued track in order, the song of [localNext] that is
     * it already ([QueueTake.Local]: the matched ones are always the first of [localNext], in order),
     * or the track to add ([QueueTake.Remote]). Empty with the setting off or nothing queued there.
     * The app then reports what it took with [remoteQueueTaken].
     */
    fun adoptRemoteQueue(localNext: List<SyncTrack>): List<QueueTake>

    /** The songs taken from the provider's queue into the new local queue (see [adoptRemoteQueue]): the sync owns those. */
    fun remoteQueueTaken(taken: List<SyncTrack>)
}

/** One queued track of the provider, as a local player starting on a list takes it (see [ListenSync.adoptRemoteQueue]). */
sealed interface QueueTake {
    /** The list has it already, at [index] of the songs after the picked one. */
    data class Local(val index: Int) : QueueTake
    /** Added to the local queue, asked to the extensions when it plays as any queued song. */
    data class Remote(val item: NowPlaying) : QueueTake
}

/**
 * The local player as the app tells it to the sync. [playing]: it is to play (play when ready);
 * [buffering]: it waits for audio; [audible]: it really plays now. [knownLengthMs]: the length of
 * its audio as the player knows it (null: not known; the provider's length is not the audio's).
 * [rate]: its speed (the provider's page plays at 1× only). [repeatsCurrent]: the song plays again
 * at its end. [previous], [next]: its neighbours in the queue. [contextUri]: the list it plays from,
 * a provider uri or "" ([live] songs of a radio have none).
 */
data class LocalNow(
    val track: SyncTrack,
    val live: Boolean,
    val playing: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    val knownLengthMs: Long? = null,
    val buffering: Boolean = false,
    val audible: Boolean = playing,
    val rate: Float = 1f,
    val repeatsCurrent: Boolean = false,
    val previous: SyncTrack? = null,
    val next: SyncTrack? = null,
    val contextUri: Uri = "",
)

/**
 * The app's side of the sync: the local player read and commanded, the setting, the log. The
 * commands are those of the local player's own controls; the sync calls them to follow a remote
 * command seen on the page.
 */
interface ListenSyncHost {
    /** The local player now, or null when it plays nothing. */
    fun local(): LocalNow?

    /** The local player's queued songs after the current one, in order. */
    fun queued(): List<SyncTrack>

    /** The local player has the playback (it plays, or holds a paused song). */
    val localActive: Boolean

    /** A song is being resolved by the extensions: the local player's state is the one before it. */
    val routing: Boolean

    /** The user's setting ("Count extension plays on …"). */
    val enabled: Boolean

    /** The data saver: the provider's page streams nothing then. */
    val dataSaver: Boolean

    /** The app itself asked the provider's page for its last playback (not another device). */
    val engineIntended: Boolean

    fun pause()
    fun resume()
    fun seekTo(ms: Long)
    fun next()
    fun previous()
    fun stop()

    /** Another device picked [item] (in [contextUri], from [positionMs]): routed as a pick on the phone is. */
    fun playRemotePick(item: NowPlaying, contextUri: Uri?, positionMs: Long)

    /** Playback came back to this phone after the sync had handed it away: it goes back to the extensions. */
    fun playHereAgain()

    /**
     * The sync holds the page ([on]) or no longer does: the page asks the system for the audio focus
     * as it plays, so the local player must not yield it meanwhile, and an extension's own mute of the
     * page gives way.
     */
    fun syncRunning(on: Boolean)

    fun log(line: String)
}

/** For the diagnostic: what the sync does now. */
data class ListenSyncStatus(
    val provider: String,
    val on: Boolean,
    val running: Boolean,
    val uri: Uri?,
    val paused: Boolean?,
    val landed: Boolean,
    val remotePositionMs: Long?,
    val handedAway: Boolean,
    val failedUri: Uri?,
    /** The provider's other apps can command the page (Spotify Connect); false where none is documented. */
    val remoteControl: Boolean = true,
    /** Why the sync does not play now, when it can tell (signed out, the page pausing by itself...). */
    val note: String? = null,
)
