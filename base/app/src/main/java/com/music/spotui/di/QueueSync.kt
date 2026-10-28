package com.music.spotui.di

import android.os.Handler
import android.os.Looper
import app.myspoti.api.ItemKind
import app.myspoti.host.android.MyspotiRuntime
import com.music.spotui.data.local.LocalTrack
import com.music.spotui.data.preferences.ConnectMirrorPref
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The local player's queue is Spotify's too, while the Connect mirror holds the page: the songs the
 * user queues here ("Play next", "Add to queue", single songs and lists) are queued on Spotify, in
 * the same order, and their removal or move there follows. Spotify's queue is the account's: another
 * device, or another install of the app, that takes playback finds them. A local player that starts
 * takes Spotify's queued songs first ([withSpotifyQueue]), as Spotify plays its queue before a list.
 *
 * Spotify's queue is brought to the local one ([QueueSyncRules.plan]) after a local queue edit and
 * once the page is on each new song. Edits made on another device while the local player plays are
 * not taken here: a song removed there comes back at the next song. Device files (no Spotify id) stay
 * in the local queue only. The decisions are in [QueueSyncRules]; this runs them (main thread, page
 * calls in the background).
 */
object QueueSync {
    private const val TRACK_PREFIX = "spotify:track:"
    /** A burst of edits (a list queued song by song, a drag) is synced once it settles. */
    private const val SETTLE_MS = 1_000L
    /** Waited (at most) for Spotify's queue to show the edits before the next sync reads it. */
    private const val QUEUE_ECHO_MS = 2_500L
    /** Songs per call: a whole playlist in one call is a long wait for the page. */
    private const val BATCH = 100

    private val api get() = MyspotiRuntime.api
    private val page get() = MyspotiRuntime.spotify.player
    private val main = Handler(Looper.getMainLooper())
    private val lock = Mutex()
    /** How many of each uri the sync queued on Spotify (or took from there): the ones it may take out again. */
    private val owned = HashMap<String, Int>()
    /** The local queue's songs at the last edit seen (see [localChanged]). */
    private var lastLocal: List<String> = emptyList()

    /** The local player's queue as published (main thread): synced when it changed. */
    fun localChanged(queued: List<String>) {
        if (queued == lastLocal) return
        lastLocal = queued
        schedule()
    }

    /** The mirror's page is on the local player's song (main thread): Spotify's queue is checked again. */
    fun mirrorLanded() = schedule()

    /** The mirror played [uri] from Spotify's queue, which consumed that copy. */
    fun consumed(uri: String) = synchronized(owned) { take(uri, 1) }

    private fun schedule() {
        main.removeCallbacks(sync)
        main.postDelayed(sync, SETTLE_MS)
    }

    private fun usable(): Boolean =
        ConnectMirrorPref.on && LocalPlayer.active.value && ConnectMirror.running && !SongPlayer.routing &&
            api.ready.value && !page.playsElsewhere.value && !page.adPlaying.value

    private val sync = Runnable {
        if (!usable()) return@Runnable
        val local = LocalPlayer.queuedUris()
        // The song about to be played from Spotify's queue by the mirror keeps its copy there until then.
        val current = LocalPlayer.current()?.let(LocalPlayer::spotifyIdOf)?.takeIf { it.isNotBlank() }?.let { TRACK_PREFIX + it }
        val spare = current?.takeIf { !ConnectMirror.landedOn(it) }
        MyspotiRuntime.scope.launch { lock.withLock { runCatching { reconcile(local, spare) }.onFailure { log("not synced: ${it.message}") } } }
    }

    private suspend fun reconcile(local: List<String>, spare: String?) {
        val q = api.player.queue.value ?: return
        val items = q.queued.filter { it.kind == ItemKind.TRACK && it.uri.startsWith(TRACK_PREFIX) }
        val plan = synchronized(owned) { QueueSyncRules.plan(local, items.map { it.uri }, HashMap(owned), spare) }
        if (plan.empty) return
        if (plan.remove.isNotEmpty()) {
            val gone = plan.remove.map(items::get)
            api.player.removeFromQueue(gone)
            synchronized(owned) { gone.forEach { take(it.uri, 1) } }
        }
        for (insert in plan.inserts) for (batch in insert.uris.chunked(BATCH)) {
            val before = insert.beforeIndex?.let(items::get)
            if (before == null) api.player.addToQueue(batch) else api.player.insertInQueue(batch, before)
            synchronized(owned) { batch.forEach { owned[it] = (owned[it] ?: 0) + 1 } }
        }
        log("Spotify's queue follows the local one: ${plan.inserts.sumOf { it.uris.size }} added, ${plan.remove.size} removed")
        // The next sync reads the queue with these edits in it.
        withTimeoutOrNull(QUEUE_ECHO_MS) { api.player.queue.first { it !== q } }
    }

    private fun take(uri: String, n: Int) {
        val left = (owned[uri] ?: 0) - n
        if (left > 0) owned[uri] = left else owned.remove(uri)
    }

    /**
     * A local player starts on [list] at [at] (main thread): Spotify's queued songs (the account's,
     * maybe queued on another device or by another install of the app) play right after the picked
     * song, as queued songs, then the list goes on. Those [list] has there already (the takeover
     * lists put Spotify's queue after the song) are taken as they are; the others are added (each
     * asked to the extensions when it plays, as any queued song). Returns the list to play and the
     * songs to mark queued. Unchanged with the mirror's setting off or nothing queued on Spotify.
     */
    fun withSpotifyQueue(list: List<LocalTrack>, at: Int): Pair<List<LocalTrack>, List<LocalTrack>> {
        lastLocal = emptyList()
        if (!ConnectMirrorPref.on) return list to emptyList()
        // The new local queue is made of what Spotify's holds now: the sync owns those, nothing else.
        synchronized(owned) { owned.clear() }
        val queued = api.player.queue.value?.queued.orEmpty().filter { it.kind == ItemKind.TRACK && it.uri.startsWith(TRACK_PREFIX) }
        if (queued.isEmpty()) return list to emptyList()
        val next = list.drop(at + 1)
        val ways = QueueSyncRules.adopt(next.map { t -> LocalPlayer.spotifyIdOf(t).let { if (it.isBlank()) "" else TRACK_PREFIX + it } }, queued.map { it.uri })
        val taken = ways.mapIndexedNotNull { i, k ->
            k?.let(next::get) ?: com.music.spotui.extensions.ExtensionAudio.queueTrack(SongPlayer.songOf(queued[i]))
        }
        if (taken.isEmpty()) return list to emptyList()
        val matched = ways.count { it != null }
        synchronized(owned) {
            taken.forEach { t -> LocalPlayer.spotifyIdOf(t).takeIf { it.isNotBlank() }?.let { owned[TRACK_PREFIX + it] = (owned[TRACK_PREFIX + it] ?: 0) + 1 } }
        }
        log("${taken.size} queued songs taken from Spotify")
        return (list.take(at + 1) + taken + next.drop(matched)) to taken
    }

    private fun log(line: String) = app.myspoti.host.android.Logbook.log("queue: $line")
}
