package com.music.spotui.data.local

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** An audio file on this device (MediaStore), played by [com.music.spotui.di.LocalPlayer]. */
data class LocalTrack(
    val id: Long,
    val uri: Uri,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val artUri: Uri,
    /** The file's folder on the phone, e.g. "Music/Album/" (MediaStore relative path). */
    val folder: String = "",
    /**
     * A Live Radio station (radio/ module) wrapped as a one-item "queue", not a file on this
     * phone: [id] is negative (MediaStore's own ids are never < 0, so it can't collide with a
     * real one) and [uri] is the stream's address. This is how Radio shows in the mini player,
     * the full player, the notification and the lock screen exactly as a Local Files song does,
     * with no separate player or session of its own.
     */
    val isRadio: Boolean = false,
)

/** The device's music files, read through MediaStore (no file access beyond audio). */
object LocalMusic {
    /** Audio read permission for this Android version. */
    val permission: String =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE

    fun hasPermission(context: Context) =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    @Volatile private var cache: List<LocalTrack>? = null

    /** The tracks shown in Local Files: all of them but those of the folders left out ([LocalFolders]). */
    suspend fun tracks(context: Context, refresh: Boolean = false): List<LocalTrack> {
        val all = allTracks(context, refresh)
        val out = LocalFolders.excluded(context, all.map { it.folder }.toSet())
        return if (out.isEmpty()) all else all.filter { it.folder !in out }
    }

    /** Every folder holding music, with its number of tracks, by name. */
    suspend fun folders(context: Context): List<Pair<String, Int>> =
        allTracks(context).groupingBy { it.folder }.eachCount().toList().sortedBy { it.first.lowercase() }

    private suspend fun allTracks(context: Context, refresh: Boolean = false): List<LocalTrack> = withContext(Dispatchers.IO) {
        if (!hasPermission(context)) return@withContext emptyList()
        cache?.takeIf { !refresh }?.let { return@withContext it }
        val collection = if (Build.VERSION.SDK_INT >= 29) MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            else MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.RELATIVE_PATH,
        )
        val out = ArrayList<LocalTrack>()
        // Android's index decides what is "music": a phone full of recorded or downloaded audio can
        // come back empty while another player lists those very files (reported: 29 tracks with a
        // third-party player, none here). Anything long enough to be a song is therefore listed too,
        // minus the system folders whose sounds are not music.
        val notSystem = listOf("Ringtones", "Notifications", "Alarms", "Android")
            .joinToString(" AND ") { "${MediaStore.Audio.Media.RELATIVE_PATH} NOT LIKE '$it/%'" }
        val selection = "(${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} >= 30000)" +
            " OR (${MediaStore.Audio.Media.DURATION} >= 20000" +
            " AND ${MediaStore.Audio.Media.RELATIVE_PATH} IS NOT NULL AND $notSystem)"
        context.contentResolver.query(
            collection, projection,
            selection,
            null, "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC",
        )?.use { c ->
            val iId = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val iTitle = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val iArtist = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val iAlbum = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val iDur = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val iAlbumId = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val iPath = c.getColumnIndex(MediaStore.Audio.Media.RELATIVE_PATH)
            while (c.moveToNext()) {
                val id = c.getLong(iId)
                val artist = c.getString(iArtist)?.takeIf { it != MediaStore.UNKNOWN_STRING }.orEmpty()
                out += LocalTrack(
                    id = id,
                    uri = ContentUris.withAppendedId(collection, id),
                    title = c.getString(iTitle).orEmpty(),
                    artist = artist,
                    album = c.getString(iAlbum).orEmpty(),
                    durationMs = c.getLong(iDur),
                    artUri = ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), c.getLong(iAlbumId)),
                    folder = if (iPath >= 0) c.getString(iPath).orEmpty() else "",
                )
            }
        }
        cache = out
        out
    }
}


/**
 * Folders left out of Local Files (WhatsApp voice notes and audio, recordings…), chosen in
 * Local Files. Messaging and recording folders start left out; the user decides afterwards.
 */
object LocalFolders {
    private const val PREFS = "local_folders"
    private const val KEY_OUT = "excluded"
    private const val KEY_SEEN = "seen"
    private const val KEY_RULES = "rules"
    /**
     * Messaging, recorder and system-sound folders, matched on a whole folder name: matched
     * anywhere in the path, "call", "voice" or "signal" also hid music folders ("Calling You",
     * "Voices", "Signal Fire" — tester report after beta.9).
     */
    private val defaultOut = Regex("""(?i)whatsapp.*|telegram.*|signal|messenger|recordings?|enregistrements|voice ?(notes|recorder|memos|recordings?)?|notifications|ringtones|alarms|call ?(recordings?|recorder)?|callrecord(ing|ings)?""")
    /** The earlier rule, kept only to take back what it hid on its own. */
    private val oldDefaultOut = Regex("""(?i)whatsapp|telegram|signal|messenger|recordings?|enregistrements|voice|notifications|ringtones|alarms|call""")

    private fun autoOut(folder: String) = folder.split('/').any { defaultOut.matches(it.trim()) }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The folders left out; new messaging / recording folders are added the first time they are seen. */
    fun excluded(context: Context, known: Set<String>): Set<String> {
        val p = prefs(context)
        val out = p.getStringSet(KEY_OUT, emptySet()).orEmpty().toMutableSet()
        // Once: the folders the old rule hid on its own and the new one would not are shown again.
        if (p.getInt(KEY_RULES, 1) < 2) {
            out.removeAll { oldDefaultOut.containsMatchIn(it) && !autoOut(it) }
            p.edit().putStringSet(KEY_OUT, out).putInt(KEY_RULES, 2).apply()
        }
        val seen = p.getStringSet(KEY_SEEN, emptySet()).orEmpty()
        val fresh = known - seen
        if (fresh.isNotEmpty()) {
            out += fresh.filter { autoOut(it) }
            p.edit().putStringSet(KEY_OUT, out).putStringSet(KEY_SEEN, seen + fresh).apply()
        }
        return out
    }

    fun isExcluded(context: Context, folder: String) = folder in prefs(context).getStringSet(KEY_OUT, emptySet()).orEmpty()

    fun set(context: Context, folder: String, shown: Boolean) {
        val p = prefs(context)
        val out = p.getStringSet(KEY_OUT, emptySet()).orEmpty().toMutableSet()
        if (shown) out -= folder else out += folder
        p.edit().putStringSet(KEY_OUT, out).apply()
    }
}
