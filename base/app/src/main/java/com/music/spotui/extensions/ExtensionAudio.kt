package com.music.spotui.extensions

import android.net.Uri
import app.myspoti.extensions.AudioStream
import app.myspoti.extensions.TrackInfo
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.data.local.LocalTrack
import com.music.spotui.data.offline.OfflineStore
import com.music.spotui.di.LocalPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * The `audio-source` hook: a list's songs play in Myspoti's own player, like downloads, from
 * where an extension says their audio is (docs/extensions/API.md §3). Each song is a
 * `myspoti-ext://track/<id>?…` item; the player's data source ([resolve]) asks the extensions
 * when it opens it, on its loading thread. A song no extension has audio for is played by Spotify.
 */
object ExtensionAudio {
    const val SCHEME = "myspoti-ext"
    /**
     * An answer is reused for seeks and replays a while, then asked again (addresses expire): at
     * most this long, less when the extension says when it expires ([AudioStream.expiresInMs]).
     */
    private const val KEEP_MS = 10 * 60_000L

    private class Known(val stream: AudioStream, val at: Long = System.currentTimeMillis()) {
        val fresh get() = System.currentTimeMillis() - at < stream.keepMs(KEEP_MS)
    }
    private val known = ConcurrentHashMap<String, Known>()

    /** Drops every remembered answer (an extension was removed: none of them may keep playing from it). */
    fun forgetAll() = known.clear()

    fun uri(s: SongsModel): Uri = Uri.Builder().scheme(SCHEME).authority("track").appendPath(s.spotifyTrackId)
        .appendQueryParameter("title", s.title).appendQueryParameter("artist", s.singer)
        .appendQueryParameter("album", s.album).appendQueryParameter("duration", s.durationMs.toString())
        .appendQueryParameter("cover", s.coverUri).build()

    private fun track(u: Uri): TrackInfo = Extensions.trackInfo(
        u.lastPathSegment.orEmpty(), u.getQueryParameter("title").orEmpty(), u.getQueryParameter("artist").orEmpty(),
        u.getQueryParameter("album").orEmpty(), u.getQueryParameter("duration")?.toLongOrNull() ?: 0, u.getQueryParameter("cover"),
    )

    /**
     * Where the sound of [t] comes from, for the player: "<extension> · FLAC · 24-bit · 96 kHz" for
     * a stream (the name in its manifest, then the quality it announced, nothing it did not say),
     * "Downloaded · <FLAC, MP3…> · <source> · <extension>" for a download; "" for the phone's own files and while
     * the stream's answer is not known yet.
     */
    fun sourceLabel(t: LocalTrack, downloaded: String): String {
        OfflineStore.byLocalId(t.id)?.takeIf { t.uri.scheme == "file" }?.let { o ->
            return listOfNotNull(downloaded, o.file.substringAfterLast('.', "").uppercase().ifBlank { null }, o.source.ifBlank { null }, extensionName(o.providerId)).distinct().joinToString(" · ")
        }
        if (t.uri.scheme != SCHEME) return ""
        val s = known[t.uri.lastPathSegment.orEmpty()]?.stream ?: return ""
        return listOfNotNull(extensionName(s.providerId), s.qualityLabel()).joinToString(" · ")
    }

    /** The name (from its manifest) of the extension streaming [t], or null (a file, not answered yet). */
    fun streamedBy(t: LocalTrack): String? =
        t.uri.takeIf { it.scheme == SCHEME }?.let { known[it.lastPathSegment.orEmpty()]?.stream?.providerId }?.let(::extensionName)

    private fun extensionName(id: String?): String? = id?.let { Extensions.registry.value.find(it)?.name }

    /** Bumped with each answer kept: the player's source line reads it to show an answer that came after the song changed. */
    val answers = androidx.compose.runtime.mutableIntStateOf(0)

    private fun keep(trackId: String, stream: AudioStream) {
        known[trackId] = Known(stream)
        answers.intValue++
    }

    private val warming = ConcurrentHashMap.newKeySet<String>()

    /**
     * A page just opened: the first of its [songs] are asked ahead, and a DASH answer's index and
     * first segment fetched, so a tap starts at once. 3 songs on Wi-Fi, 1 on mobile data; none
     * offline or with the data saver on. A song asked a moment ago is not asked again.
     */
    suspend fun warm(songs: List<SongsModel>) {
        if (!Extensions.hasAudioSource() || com.music.spotui.data.DataSaver.active || !OfflineStore.isOnline(com.music.spotui.di.Graph.app)) return
        if (com.music.spotui.di.SongPlayer.activeDevice.value != null) return
        val count = if (com.music.spotui.data.DataSaver.metered) 1 else 3
        songs.asSequence()
            .filter { it.spotifyTrackId.isNotBlank() && it.playable && !OfflineStore.isDownloaded(it.spotifyTrackId) }
            .filter { known[it.spotifyTrackId]?.fresh != true }
            .take(count).toList()
            .forEach { s ->
                if (!warming.add(s.spotifyTrackId)) return@forEach
                try {
                    val a = withContext(Dispatchers.IO) {
                        Extensions.audio(Extensions.trackInfo(s.spotifyTrackId, s.title, s.singer, s.album, s.durationMs.toLong(), s.coverUri))
                    } ?: return@forEach
                    keep(s.spotifyTrackId, a)
                    a.manifest?.let { m ->
                        withContext(Dispatchers.IO) {
                            runCatching { com.music.spotui.data.offline.DashLive.open(m, a.headers, com.music.spotui.data.offline.OfflineDownloader.DownloadUa).warm() }
                        }
                    }
                } finally {
                    warming.remove(s.spotifyTrackId)
                }
            }
    }

    /** The extension that gave the audio of the song with Spotify id [trackId] last, or null. */
    fun providerOf(trackId: String): String? = known[trackId]?.stream?.providerId

    /** The quality the extension announced for the song [trackId] ("FLAC · 24-bit · 96 kHz"), or null. */
    fun qualityOf(trackId: String): String? = known[trackId]?.stream?.qualityLabel()

    /**
     * For the player's data source: the audio of a `myspoti-ext:` item, or null. Blocks.
     * [again]: the answer kept is dropped and the extensions are asked anew (its address was refused).
     * [ahead]: asked before the song plays (the extension is told so).
     */
    fun resolve(u: Uri, again: Boolean = false, ahead: Boolean = false): AudioStream? {
        val key = u.lastPathSegment.orEmpty()
        if (again) known.remove(key)
        known[key]?.takeIf { it.fresh }?.let { return it.stream }
        // The song skipped to while its ask ahead is still out: that answer is waited for rather
        // than a second ask (which cost the source a request and the song seconds); only a miss
        // ahead is asked again, as the song now plays.
        if (!again) asking[key]?.let { pending ->
            runCatching { pending.get(Extensions.AUDIO_MS, java.util.concurrent.TimeUnit.MILLISECONDS) }.getOrNull()?.let { return it }
        }
        val mine = java.util.concurrent.CompletableFuture<AudioStream?>()
        asking[key] = mine
        return try {
            Extensions.audioBlocking(track(u), ahead)?.also { keep(key, it) }.also { mine.complete(it) }
        } catch (t: Throwable) {
            mine.complete(null); throw t
        } finally {
            asking.remove(key, mine)
        }
    }

    /** The asks out now, by song: a second ask of the same song waits for the first. */
    private val asking = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.CompletableFuture<AudioStream?>>()

    /**
     * Plays [songs] from [picked] in the local player when an extension has the picked song's
     * audio (asked first, so a miss falls back to Spotify at once); the rest of [songs] is the
     * queue (see [AudioRoute.queue]). Downloaded songs play from their file. False: no extension
     * had it, the caller plays through Spotify. [isLatest] false once the answer came: a newer
     * song was asked for meanwhile, nothing plays (see SongPlayer.playThroughExtensions).
     * [startMs]: where in the picked song it starts (a resume).
     */
    suspend fun playList(
        songs: List<SongsModel>, picked: SongsModel, context: String, shuffle: Boolean, contextUri: String = "",
        isLatest: () -> Boolean = { true }, startMs: Long = 0L,
    ): Boolean {
        if (picked.spotifyTrackId.isBlank() || !Extensions.hasAudioSource()) return false
        // Another Spotify Connect device plays: the song goes there, played by Spotify.
        if (com.music.spotui.di.SongPlayer.activeDevice.value != null) return false
        // A song asked ahead (the next one of the queue) is not asked again: the extension would
        // take a second ask for a refused address and resolve it anew.
        val first = known[picked.spotifyTrackId]?.takeIf { it.fresh }?.stream ?: withContext(Dispatchers.IO) {
            Extensions.audio(Extensions.trackInfo(picked.spotifyTrackId, picked.title, picked.singer, picked.album, picked.durationMs.toLong(), picked.coverUri))
        } ?: return false
        keep(picked.spotifyTrackId, first)
        val (list, at) = AudioRoute.queue(songs, picked)
        val tracks = list.map(::localTrack)
        return withContext(Dispatchers.Main) {
            isLatest().also {
                if (it) LocalPlayer.play(tracks, at, shuffle = shuffle, context = context, contextUri = contextUri, startMs = startMs, continues = true)
                else app.myspoti.host.android.Logbook.log("audio: ${picked.title} answered after a newer pick, not played")
            }
        }
    }

    /** The local player's item for [s]: its file when downloaded, else asked to the extensions when it plays. */
    fun localTrack(s: SongsModel): LocalTrack =
        OfflineStore.tracks[s.spotifyTrackId]?.takeIf { OfflineStore.isDownloaded(s.spotifyTrackId) }?.let(OfflineStore::toLocalTrack) ?: LocalTrack(
            id = OfflineStore.localIdOf(s.spotifyTrackId), uri = uri(s), title = s.title, artist = s.singer,
            album = s.album, durationMs = s.durationMs.toLong(), artUri = Uri.parse(s.coverUri),
        )

    /**
     * Any play of the song [trackId] (SongPlayer.playSong): [songs] when the caller has the list
     * or the song, else the song as Spotify describes it ([shown] once known), then [playList].
     * False: Spotify plays.
     */
    suspend fun route(
        trackId: String, songs: List<SongsModel>?, context: String, shuffle: Boolean, contextUri: String,
        isLatest: () -> Boolean = { true }, shown: (SongsModel) -> Unit = {}, startMs: Long = 0L,
    ): Boolean {
        val list = AudioRoute.songsFor(trackId, songs) { lookup(trackId) }
            ?: run { app.myspoti.host.android.Logbook.log("audio: $trackId not found on Spotify, the extensions are not asked"); return false }
        val picked = list.first { it.spotifyTrackId == trackId }
        if (songs?.any { it.spotifyTrackId == trackId } != true) shown(picked)
        return playList(list, picked, context, shuffle, contextUri, isLatest, startMs)
    }

    /**
     * "Play next" / "Add to queue" while the local player plays: the song joins its queue (its
     * download, else the extension's stream). False: neither has it, Spotify's queue takes it.
     */
    suspend fun enqueue(trackId: String, next: Boolean): Boolean {
        val downloaded = OfflineStore.tracks[trackId]?.takeIf { OfflineStore.isDownloaded(trackId) }?.let(OfflineStore::toLocalTrack)
        val track = downloaded ?: if (Extensions.hasAudioSource()) lookup(trackId)?.let(::localTrack) else null
        track ?: return false
        withContext(Dispatchers.Main) { LocalPlayer.enqueue(track, next) }
        return true
    }

    /**
     * [enqueue] for a song the caller already has (a list being queued): no look-up. Its download,
     * else the extension's stream; null when neither has it, Spotify's queue takes it then.
     */
    fun queueTrack(s: SongsModel): LocalTrack? =
        OfflineStore.tracks[s.spotifyTrackId]?.takeIf { OfflineStore.isDownloaded(s.spotifyTrackId) }?.let(OfflineStore::toLocalTrack)
            ?: if (Extensions.hasAudioSource()) localTrack(s) else null

    /** The song's title, artists, album and length from Spotify, for a play that knows only its uri. */
    private suspend fun lookup(trackId: String): SongsModel? = runCatching {
        kotlinx.coroutines.withTimeout(LOOKUP_MS) {
            val api = app.myspoti.host.android.MyspotiRuntime.api
            api.ready.first { it }
            com.music.spotui.di.Graph.api.songOf(api.catalog.track("spotify:track:$trackId"))
        }
    }.getOrNull()

    private const val LOOKUP_MS = 10_000L
}
