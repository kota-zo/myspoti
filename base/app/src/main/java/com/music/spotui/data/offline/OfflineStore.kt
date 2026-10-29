package com.music.spotui.data.offline

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshots.SnapshotStateMap
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.data.local.LocalTrack
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A song kept for offline listening, in the app's private storage. */
data class OfflineTrack(
    val trackId: String,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: String,
    val durationMs: Long,
    val coverUrl: String,
    /** Audio file name in [OfflineStore]'s audio folder. */
    val file: String,
    /** Cover file name in the covers folder, empty when it could not be fetched. */
    val cover: String,
    /** Where the audio came from (the download-source extension's provider, or "podcast"). */
    val source: String,
    /** The extension found the file by the song's ISRC (older downloads: checked against a preview). */
    val verified: Boolean,
    val bytes: Long,
    val addedAt: Long,
    /**
     * Music or podcast episode. Entries written before this field existed are music, so an
     * older index stays valid (and its files are not swept as strays).
     */
    val kind: String = KIND_MUSIC,
    /**
     * The id of the download-source extension that supplied the file. Null when unknown: a
     * podcast episode, or a download made before this field existed.
     */
    val providerId: String? = null,
) {
    val isEpisode: Boolean get() = kind == KIND_PODCAST

    fun toJson(): JSONObject = JSONObject()
        .put("id", trackId).put("title", title).put("artist", artist).put("album", album)
        .put("albumId", albumId).put("duration", durationMs).put("coverUrl", coverUrl)
        .put("file", file).put("cover", cover).put("source", source).put("verified", verified)
        .put("bytes", bytes).put("added", addedAt).put("kind", kind)
        .apply { providerId?.let { put("providerId", it) } }

    companion object {
        const val KIND_MUSIC = "MUSIC"
        const val KIND_PODCAST = "PODCAST"

        fun fromJson(o: JSONObject) = OfflineTrack(
            trackId = o.getString("id"), title = o.optString("title"), artist = o.optString("artist"),
            album = o.optString("album"), albumId = o.optString("albumId"), durationMs = o.optLong("duration"),
            coverUrl = o.optString("coverUrl"), file = o.getString("file"), cover = o.optString("cover"),
            source = o.optString("source"), verified = o.optBoolean("verified"), bytes = o.optLong("bytes"),
            addedAt = o.optLong("added"),
            // Absent in an index written before podcasts: those entries are all music.
            kind = o.optString("kind").ifBlank { KIND_MUSIC },
            // Absent in an index written before it was kept: unknown.
            providerId = o.optString("providerId").ifBlank { null },
        )
    }
}

/** Where a download stands, for the badges and buttons. */
sealed interface OfflineStatus {
    data object Queued : OfflineStatus
    /** [progress] 0..1, or -1 while the source is looked up / checked. */
    data class Downloading(val progress: Float) : OfflineStatus
    data object Done : OfflineStatus
    /** No recording that matches the Spotify track was found. */
    data object Unavailable : OfflineStatus
    /** Network or storage error: can be tried again. */
    data object Failed : OfflineStatus
}

/**
 * Downloaded songs. Files live in the app's private storage (filesDir/offline), so
 * only Myspoti plays them: they are not in the phone's Music folder or MediaStore.
 */
object OfflineStore {
    private lateinit var root: File
    private val lock = Any()

    /** Downloaded songs by Spotify track id; every screen reads this. */
    val tracks: SnapshotStateMap<String, OfflineTrack> = mutableStateMapOf()
    /** Songs in the download queue, or whose last attempt did not succeed. */
    val status: SnapshotStateMap<String, OfflineStatus> = mutableStateMapOf()

    fun init(context: Context) {
        if (::root.isInitialized) return
        val plain = load(File(context.filesDir, "offline"))
        // Songs downloaded before encryption existed: encrypted once, in the background.
        if (plain.isNotEmpty()) Thread {
            plain.forEach { f -> runCatching { OfflineCrypto.encryptInPlace(context.applicationContext, f) }
                .onFailure { android.util.Log.w("Offline", "could not encrypt ${f.name}: ${it.message}") } }
        }.apply { name = "offline-encrypt"; priority = Thread.MIN_PRIORITY }.start()
    }

    /** Reads the downloads folder again (main thread): another account's downloads were put in its place. */
    fun reopen(context: Context) {
        status.clear()
        load(File(context.filesDir, "offline"))
    }

    /** Reads the downloads in [dir] into [tracks]; returns the audio files of the songs found. */
    internal fun load(dir: File): List<File> {
        root = dir
        tracks.clear()
        audioDir.mkdirs(); coverDir.mkdirs()
        // The index lists only finished files; anything else in the folders is a leftover.
        // An index that cannot be read fully is never trusted to delete anything: the last
        // good copy is used instead, and if that fails too the files are all kept.
        val read = readIndex(indexFile) ?: run {
            if (indexFile.exists()) {
                indexFile.copyTo(File(root, "index.corrupt-${System.currentTimeMillis()}.json"), overwrite = true)
                runCatching { android.util.Log.w("Offline", "download index unreadable; using the backup, no file deleted") }
            }
            readIndex(backupFile)
        }
        val list = read?.tracks.orEmpty()
        val kept = list.filter { File(audioDir, it.file).isFile }
        kept.forEach { tracks[it.trackId] = it }
        if (read != null && read.complete) {
            if (kept.size != list.size || read.file == backupFile) writeIndex()
            val names = kept.map { it.file }.toSet()
            audioDir.listFiles()?.forEach { if (it.name !in names) it.delete() }
            val covers = kept.map { it.cover }.toSet()
            coverDir.listFiles()?.forEach { if (it.name !in covers) it.delete() }
        }
        return kept.map { File(audioDir, it.file) }
    }

    val audioDir: File get() = File(root, "audio")
    val coverDir: File get() = File(root, "covers")
    private val indexFile: File get() = File(root, "index.json")
    /** The index before the last write, the fallback when [indexFile] is damaged. */
    private val backupFile: File get() = File(root, "index.json.bak")
    internal val pendingFile: File get() = File(root, "pending.json")
    /** The podcast queue: kept apart so a restart resumes episodes without touching music. */
    internal val podcastPendingFile: File get() = File(root, "podcasts-pending.json")
    val tmpDir: File get() = File(root, "tmp").apply { mkdirs() }

    fun isDownloaded(trackId: String) = trackId.isNotBlank() && tracks.containsKey(trackId)

    /** What the UI shows for a track: downloaded, in the queue, failed… or nothing. */
    fun statusOf(trackId: String): OfflineStatus? =
        if (isDownloaded(trackId)) OfflineStatus.Done else status[trackId]

    fun add(t: OfflineTrack) {
        synchronized(lock) {
            tracks[t.trackId] = t
            status.remove(t.trackId)
            writeIndex()
        }
    }

    fun remove(trackId: String) {
        synchronized(lock) {
            val t = tracks.remove(trackId) ?: return
            File(audioDir, t.file).delete()
            if (t.cover.isNotBlank()) File(coverDir, t.cover).delete()
            writeIndex()
        }
    }

    fun removeAll() {
        synchronized(lock) {
            tracks.clear()
            status.clear()
            audioDir.listFiles()?.forEach { it.delete() }
            coverDir.listFiles()?.forEach { it.delete() }
            writeIndex()
        }
    }

    fun totalBytes(): Long = tracks.values.sumOf { it.bytes }

    /** A working internet connection (else downloaded songs play from the phone). */
    fun isOnline(context: Context): Boolean {
        // The data saver on mobile data: the app behaves as offline.
        if (com.music.spotui.data.DataSaver.active) return false
        val cm = context.getSystemService(android.net.ConnectivityManager::class.java) ?: return true
        val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) } ?: return false
        // Not "validated" is not offline: some VPNs, private DNS and Wi-Fi networks never pass
        // Android's check, and treating them as offline played only the downloaded songs of a
        // list, skipping all the others (issue #10).
        return caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /** Downloaded songs, newest first. */
    fun list(): List<OfflineTrack> = tracks.values.sortedByDescending { it.addedAt }

    fun audioFile(t: OfflineTrack) = File(audioDir, t.file)
    fun coverFile(t: OfflineTrack): File? = t.cover.takeIf { it.isNotBlank() }?.let { File(coverDir, it) }?.takeIf { it.isFile }

    /** For the local player (player, mini player, notification…). */
    fun toLocalTrack(t: OfflineTrack) = LocalTrack(
        id = localIdOf(t.trackId),
        uri = Uri.fromFile(audioFile(t)),
        title = t.title,
        artist = t.artist,
        album = t.album,
        durationMs = t.durationMs,
        artUri = coverFile(t)?.let { Uri.fromFile(it) } ?: Uri.parse(app.myspoti.host.android.Covers.large(t.coverUrl)),
    )

    fun toSong(t: OfflineTrack) = SongsModel(
        id = "track:${t.trackId}".hashCode() and 0x7fffffff,
        title = t.title, album = t.album, singer = t.artist,
        coverUri = coverFile(t)?.let { Uri.fromFile(it).toString() } ?: t.coverUrl,
        // An episode is keyed "episode:<id>" and must keep that url (playback, download state);
        // it has no Spotify track id, and giving it one would send it down the track paths.
        url = if (t.isEpisode) t.trackId else "spotify:track:${t.trackId}",
        spotifyTrackId = if (t.isEpisode) "" else t.trackId,
        durationMs = t.durationMs.toInt(), albumId = t.albumId,
    )

    /** Negative, so it never collides with a MediaStore id (Local Files). */
    fun localIdOf(trackId: String): Long = -1L - (trackId.hashCode().toLong() and 0x7fffffffL)

    /** The downloaded track a local id plays, or null for files outside the downloads. */
    fun byLocalId(id: Long): OfflineTrack? =
        tracks.values.firstOrNull { localIdOf(it.trackId) == id }

    /** An index read from [file]; [complete] is false when some entries could not be read. */
    internal class IndexRead(val file: File, val tracks: List<OfflineTrack>, val complete: Boolean)

    /** [file] read, an empty index when it does not exist, null when it cannot be parsed. */
    internal fun readIndex(file: File): IndexRead? {
        if (!file.exists()) return if (file == indexFile && !backupFile.exists()) IndexRead(file, emptyList(), true) else null
        return runCatching {
            val a = JSONArray(file.readText())
            val all = (0 until a.length()).map { runCatching { OfflineTrack.fromJson(a.getJSONObject(it)) }.getOrNull() }
            IndexRead(file, all.filterNotNull(), all.none { it == null })
        }.getOrNull()
    }

    private fun writeIndex() {
        val a = JSONArray()
        tracks.values.forEach { a.put(it.toJson()) }
        if (indexFile.exists() && readIndex(indexFile)?.complete == true) indexFile.copyTo(backupFile, overwrite = true)
        // A failed write keeps the last good index (and its backup) rather than a half-moved one.
        runCatching { com.music.spotui.data.writeTextSafely(indexFile, a.toString()) }
            .onFailure { app.myspoti.host.android.Logbook.log("offline: index not saved: ${it.message}") }
    }
}
