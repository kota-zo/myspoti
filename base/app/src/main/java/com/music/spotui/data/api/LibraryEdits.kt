package com.music.spotui.data.api

import android.content.Context
import android.util.Log
import android.widget.Toast
import app.myspoti.engine.R
import com.music.spotui.data.entity.LibraryEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Library changes shown before Spotify has them. Spotify's library list (libraryV3) trails a
 * write by several seconds, so a list read right after "delete" still held the playlist and a
 * reload put it back. Each edit is applied on top of whatever list the server gave, until the
 * server has had time to catch up; a refused write drops its edit, which restores the list.
 *
 * The screens keep the server's list as it came and show [applyTo] of it: a rollback is then
 * just "apply without this edit". Every edit is idempotent, so a server list that already
 * has the change is left as it is.
 */
object LibraryEdits {
    private const val TAG = "LibraryEdits"
    /** How long a written edit keeps covering the list: past this Spotify's list is right. */
    private const val SETTLE_MS = 25_000L
    /** An edit whose write never answers does not hide the server's list for ever. */
    private const val MAX_WRITE_MS = 120_000L

    abstract class Edit {
        /** [level] is the library folder's uri, null for the top level. */
        abstract fun apply(level: String?, list: List<LibraryEntry>): List<LibraryEntry>
    }

    /** A playlist or folder leaves the library (deleted, unfollowed, or moved elsewhere). */
    class Remove(val uri: String) : Edit() {
        override fun apply(level: String?, list: List<LibraryEntry>) = list.filterNot { it.matches(uri) }
    }

    class Rename(private val uri: String, private val name: String) : Edit() {
        override fun apply(level: String?, list: List<LibraryEntry>) =
            list.map { if (it.matches(uri) && it.name != name) it.copy(name = name) else it }
    }

    class Pin(private val uri: String, private val pinned: Boolean) : Edit() {
        override fun apply(level: String?, list: List<LibraryEntry>) =
            list.map { if (it.matches(uri) && it.pinned != pinned) it.copy(pinned = pinned) else it }
    }

    /**
     * A new playlist or folder in [level]. It first shows with a placeholder id (the real one is
     * known when Spotify has answered); [resolve] then swaps in the real entry.
     */
    class Add(private val level: String?, entry: LibraryEntry) : Edit() {
        @Volatile var entry: LibraryEntry = entry
            internal set
        override fun apply(level: String?, list: List<LibraryEntry>) = if (level == this.level) insertOnce(level, list, entry) else list
    }

    /** [entry] moves to [to] (a folder uri, null: the top level) and leaves every other level. */
    class Move(private val entry: LibraryEntry, private val to: String?) : Edit() {
        override fun apply(level: String?, list: List<LibraryEntry>) =
            if (level == to) insertOnce(level, list, entry) else list.filterNot { it.matches(entry.uri) }
    }

    /** After Liked Songs at the top level (it stays pinned there), first in a folder. */
    private fun insertOnce(level: String?, list: List<LibraryEntry>, e: LibraryEntry): List<LibraryEntry> {
        if (list.any { it.matches(e.uri) }) return list
        val at = if (level == null) list.indexOfFirst { it.spotifyId == Api.HomeCache.LIKED_SONGS_ID } + 1 else 0
        return list.toMutableList().apply { add(at.coerceIn(0, size), e) }
    }

    private class Pending(val edit: Edit, @Volatile var until: Long)

    private val pending = CopyOnWriteArrayList<Pending>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** Counts every change to the set of edits: the screens show their list again when it moves. */
    val version = MutableStateFlow(0)

    /** Asks the screens to read the library again (the write is in; Spotify's list may have caught up). */
    private val _reloads = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val reloads: SharedFlow<Unit> = _reloads

    fun applyTo(level: String?, list: List<LibraryEntry>): List<LibraryEntry> {
        val now = System.currentTimeMillis()
        if (pending.removeAll { it.until < now }) version.value++
        var out = list
        for (p in pending) out = p.edit.apply(level, out)
        return out
    }

    /** The row [edit] added now has its real uri. */
    fun resolve(edit: Add, real: LibraryEntry) {
        edit.entry = real
        version.value++
    }

    private fun find(edit: Edit) = pending.firstOrNull { it.edit === edit }

    /** Shows [edit] at once. */
    fun begin(edit: Edit) {
        // A playlist deleted right after it was made must not come back from its own Add.
        if (edit is Remove) pending.removeAll { p -> (p.edit as? Add)?.entry?.let { edit.apply(null, listOf(it)).isEmpty() } == true }
        pending.add(Pending(edit, System.currentTimeMillis() + MAX_WRITE_MS))
        version.value++
    }

    /** The write went through: the edit keeps covering the list while Spotify's list catches up. */
    fun confirm(edit: Edit) {
        find(edit)?.until = System.currentTimeMillis() + SETTLE_MS
        reloadSoon()
    }

    /** A change written elsewhere (a Blend joined): the library is read again a few times, the first read can still be the old one. */
    fun reloadSoon() {
        scope.launch { for (wait in longArrayOf(1_500, 3_500, 7_000)) { delay(wait); _reloads.tryEmit(Unit) } }
    }

    /** [uri] is back in the library (followed or saved again): an earlier removal no longer covers the list. */
    fun dropRemovals(uri: String) {
        if (pending.removeAll { (it.edit as? Remove)?.uri == uri }) version.value++
    }

    /** The write failed: the list goes back to what it was. */
    fun cancel(edit: Edit) {
        if (pending.removeAll { it.edit === edit }) version.value++
    }

    /**
     * Shows [edit] (when given), then writes it in the app's own scope, so leaving the screen
     * that asked does not cancel it. A refused write undoes the edit and says so.
     */
    fun run(context: Context, edit: Edit?, write: suspend () -> Unit, onDone: (Boolean) -> Unit = {}) {
        val app = context.applicationContext
        if (edit != null) begin(edit)
        scope.launch {
            val failure = runCatching { write() }.exceptionOrNull()
            if (failure is kotlinx.coroutines.CancellationException) throw failure
            if (failure == null) { edit?.let(::confirm) }
            else {
                Log.w(TAG, "library write refused: ${failure.message}")
                edit?.let(::cancel)
                Toast.makeText(app, app.getString(R.string.library_change_undone), Toast.LENGTH_SHORT).show()
            }
            onDone(failure == null)
        }
    }

    private fun LibraryEntry.matches(uri: String) = uri.isNotBlank() && (this.uri == uri || folderUri == uri)

    /** A row shown for something Spotify is still creating: it cannot be opened yet. */
    const val PENDING_PREFIX = "pending:"
    fun isPending(id: String) = id.startsWith(PENDING_PREFIX)
}
