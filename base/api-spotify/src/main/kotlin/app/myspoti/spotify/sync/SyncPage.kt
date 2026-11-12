package app.myspoti.spotify.sync

import app.myspoti.api.Device
import app.myspoti.api.NowPlaying
import app.myspoti.api.PlayerState
import app.myspoti.api.Queue
import app.myspoti.api.Uri
import app.myspoti.spotify.SpotifyApi
import app.myspoti.spotify.bridge.PageDevice
import kotlinx.coroutines.flow.StateFlow

/**
 * What [SpotifyListenSync] reads from and asks of the hidden Web Player page: its status, its
 * playback and queue, the account's devices, and the calls it makes there. [SpotifyApiPage] is the
 * real page; a test gives its own.
 */
interface SyncPage {
    /** The page is loaded and takes commands. */
    val ready: StateFlow<Boolean>
    /** An ad is on (the page plays Spotify's ad to its end and refuses commands meanwhile). */
    val adPlaying: StateFlow<Boolean>
    /** The page's song an ad is due at the end of, as Spotify's queue says (see AdSchedule), or null. */
    fun adDueAfter(): String?
    /** The page's device has joined Spotify Connect since the page last loaded. */
    val joined: StateFlow<Boolean>
    /** The host reports joins (so [joined] can be waited for). */
    val joinReported: Boolean
    /** Another device of the account is Spotify Connect's active one. */
    val playsElsewhere: StateFlow<Boolean>
    val state: StateFlow<PlayerState?>
    val queue: StateFlow<Queue?>
    /** The page is unheard now (its own audio muted on this phone). */
    val localMute: Boolean
    val devices: StateFlow<List<Device>>

    /**
     * Its [state] reaches the sync by itself ([SpotifyListenSync] reads it): a page the app does not
     * already read (the native engine, [EngineSyncPage]). False: the app hands the page's states over.
     */
    val ownStates: Boolean get() = false
    /** Its [queue] is kept as the local player's ([SpotifyQueueSync]); false where the queue is not read. */
    val keepsQueue: Boolean get() = true
    /**
     * The name this phone shows in Spotify's device lists (the one its owner gave it), when the
     * page's own device does not show it (the native engine registers as a web player): what tells
     * this phone's own Spotify app ([SpotifySyncRules.ownPhoneApp]). Null: the page's device's name.
     */
    val phoneName: String? get() = null

    /** Spotify limits this page's plays now (429): its states are not judged. */
    fun licenceLimited(): Boolean
    /** Spotify refused this page an audio licence within the last [ms]: the page skips songs by itself then. */
    fun licenceRefusedWithin(ms: Long): Boolean

    suspend fun refreshDevices()
    suspend fun pullHere()
    /** The page streams at its lowest quality and loads no next song ahead ([on]), or as the user set it. */
    suspend fun setMirroring(on: Boolean)
    suspend fun setLocalMute(on: Boolean)
    suspend fun play(contextUri: Uri, trackUri: Uri?, positionMs: Long)
    suspend fun pause()
    suspend fun resume()
    suspend fun seek(positionMs: Long)
    suspend fun skipTo(item: NowPlaying)
    suspend fun addToQueue(uris: List<Uri>)
    suspend fun insertInQueue(uris: List<Uri>, before: NowPlaying)
    suspend fun removeFromQueue(items: List<NowPlaying>)
}

/**
 * The real page: [api]'s player and devices, read when used (the sync can be made before the
 * account's session exists). [licenceLimited] and [licenceRefused] come from the host that runs
 * the page (it sees the page's licence answers).
 */
class SpotifyApiPage(
    private val api: () -> SpotifyApi,
    private val licenceLimited: () -> Boolean = { false },
    private val licenceRefused: (Long) -> Boolean = { false },
) : SyncPage {
    private val player get() = api().player
    override val ready get() = api().ready
    override val adPlaying get() = player.adPlaying
    override fun adDueAfter() = app.myspoti.spotify.bridge.AdSchedule.due.value?.songUri
    override val joined get() = PageDevice.joined
    override val joinReported get() = PageDevice.reported
    override val playsElsewhere get() = player.playsElsewhere
    /** The page's own: the engine is stopped while the page mirrors, nothing of it to lay over. */
    override val state get() = player.pageState
    override val queue get() = player.queue
    override val localMute get() = player.localMute
    override val devices get() = api().devices.devices

    override fun licenceLimited() = licenceLimited.invoke()
    override fun licenceRefusedWithin(ms: Long) = licenceRefused(ms)

    override suspend fun refreshDevices() = api().devices.refresh()
    override suspend fun pullHere() = api().devices.pullHere()
    override suspend fun setMirroring(on: Boolean) = player.setMirroring(on)
    override suspend fun setLocalMute(on: Boolean) = player.setLocalMute(on)
    override suspend fun play(contextUri: Uri, trackUri: Uri?, positionMs: Long) = player.play(contextUri, trackUri = trackUri, positionMs = positionMs)
    override suspend fun pause() = player.pause()
    override suspend fun resume() = player.resume()
    override suspend fun seek(positionMs: Long) = player.seek(positionMs)
    override suspend fun skipTo(item: NowPlaying) = player.skipTo(item)
    override suspend fun addToQueue(uris: List<Uri>) = player.addToQueue(uris)
    override suspend fun insertInQueue(uris: List<Uri>, before: NowPlaying) = player.insertInQueue(uris, before)
    override suspend fun removeFromQueue(items: List<NowPlaying>) = player.removeFromQueue(items)
}
