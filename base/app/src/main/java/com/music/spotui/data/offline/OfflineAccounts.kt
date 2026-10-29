package com.music.spotui.data.offline

import android.content.Context
import app.myspoti.host.android.Logbook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * Downloads belong to the Spotify account that made them. The downloads folder (`offline/`: songs,
 * episodes, lists, queues) is stamped with its account; on sign-out it is put aside under that
 * account (`offline-accounts/<key>/`) and the next account starts with none. Signing in again
 * brings that account's own folder back. Nothing is deleted: a phone shared by two accounts keeps
 * both sets, each seen only by its own account. Downloads made before the stamp existed go to the
 * first account seen signed in.
 */
object OfflineAccounts {
    private const val OWNER = "owner"
    private val lock = Mutex()

    private fun live(context: Context) = File(context.filesDir, "offline")
    private fun parked(context: Context, key: String) = File(context.filesDir, "offline-accounts/$key")
    private fun ownerOf(dir: File): String? = File(dir, OWNER).takeIf { it.isFile }?.readText()?.trim()?.ifBlank { null }

    /** A stable, opaque folder name for a Spotify user id (the id itself is not written down). */
    internal fun keyOf(userId: String): String =
        MessageDigest.getInstance("SHA-256").digest(userId.toByteArray()).take(12).joinToString("") { "%02x".format(it) }

    /** Signing out: this account's downloads go aside; [userId] names them when the folder has no stamp yet. */
    suspend fun park(context: Context, userId: String?) = lock.withLock {
        val dir = live(context)
        val owner = ownerOf(dir) ?: userId?.let(::keyOf)
        if (owner == null && !hasDownloads(dir)) return@withLock
        // Whose they are is unknown (never signed in online since): kept aside, never given to anyone.
        val key = owner ?: "unclaimed-${System.currentTimeMillis()}"
        stopDownloads(dir)
        val moved = withContext(Dispatchers.IO) { putAside(dir, parked(context, key)) }
        if (!moved) {
            // Still in place and still stamped with their account: the next sign-in puts them aside.
            Logbook.log("offline: downloads not put aside for sign-out")
            return@withLock
        }
        reopen(context)
        Logbook.log("offline: downloads put aside for sign-out")
    }

    /** [userId] is signed in: its downloads are the ones shown; downloads with no stamp yet become its own. */
    suspend fun claim(context: Context, userId: String) = lock.withLock {
        val key = keyOf(userId)
        val dir = live(context)
        val owner = ownerOf(dir)
        if (owner == key) return@withLock
        if (owner == null && hasDownloads(dir)) {
            stamp(dir, key)
            return@withLock
        }
        // Another account's downloads are in place (a sign-out that could not put them aside), or none.
        stopDownloads(dir)
        val ready = withContext(Dispatchers.IO) {
            // This account's stamp only goes on an emptied folder or its own: downloads that could not
            // be moved keep their own account's stamp, never this one.
            val cleared = if (owner != null) putAside(dir, parked(context, owner)) else dir.deleteRecursively()
            if (!cleared) return@withContext false
            val back = parked(context, key)
            if (back.isDirectory && !back.renameTo(dir)) Logbook.log("offline: this account's downloads could not be brought back")
            stamp(dir, key)
            true
        }
        if (!ready) {
            Logbook.log("offline: the other account's downloads could not be put aside")
            reopen(context)
            return@withLock
        }
        reopen(context)
        // Its queues came back with its folder: they go on where they stopped.
        OfflineDownloader.resumePending()
        PodcastDownloader.resumePending()
        Logbook.log("offline: this account's downloads in place (${OfflineStore.tracks.size} songs)")
    }

    /**
     * Moves [dir] to [to]. A folder already there (left by an earlier sign-out that went wrong) is
     * kept under another name rather than deleted. False when the move failed: [dir] is untouched.
     */
    private fun putAside(dir: File, to: File): Boolean {
        to.parentFile?.mkdirs()
        if (to.exists() && !to.renameTo(File(to.parentFile, "${to.name}-${System.currentTimeMillis()}"))) return false
        return dir.renameTo(to)
    }

    private fun stamp(dir: File, key: String) {
        dir.mkdirs()
        runCatching { com.music.spotui.data.writeTextSafely(File(dir, OWNER), key) }
    }

    private fun hasDownloads(dir: File): Boolean =
        File(dir, "index.json").length() > 2 || File(dir, "collections.json").length() > 2 ||
            File(dir, "pending.json").length() > 2 || File(dir, "podcasts-pending.json").length() > 2

    /** Running downloads stop; the queues on disk are kept as they were, so they resume with their account. */
    private suspend fun stopDownloads(dir: File) {
        val queues = listOf(File(dir, "pending.json"), File(dir, "podcasts-pending.json"))
        val saved = withContext(Dispatchers.IO) { queues.map { f -> f to f.takeIf { it.isFile }?.readText() } }
        OfflineDownloader.forgetSession()
        PodcastDownloader.forgetSession()
        withContext(Dispatchers.IO) { saved.forEach { (f, text) -> if (text != null) runCatching { f.writeText(text) } } }
    }

    private suspend fun reopen(context: Context) = withContext(Dispatchers.Main) {
        OfflineStore.reopen(context)
        OfflineCollections.reopen()
    }
}
