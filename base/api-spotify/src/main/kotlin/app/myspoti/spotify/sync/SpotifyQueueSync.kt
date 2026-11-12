package app.myspoti.spotify.sync

import app.myspoti.api.ItemKind
import app.myspoti.api.sync.ListenSyncHost
import app.myspoti.api.sync.QueueSyncRules
import app.myspoti.api.sync.QueueTake
import app.myspoti.api.sync.SyncTrack
import app.myspoti.spotify.sync.SpotifySyncRules.TRACK_PREFIX
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The local player's queue is Spotify's too, while the listen sync holds the page ([running]): the
 * songs the user queues here ("Play next", "Add to queue", single songs and lists) are queued on
 * Spotify, in the same order, and their removal or move there follows. Spotify's queue is the
 * account's: another device, or another install of the app, that takes playback finds them. A local
 * player that starts takes Spotify's queued songs first ([adopt]), as Spotify plays its queue before
 * a list.
 *
 * Spotify's queue is brought to the local one ([QueueSyncRules.plan]) after a local queue edit and
 * once the page is on each new song ([landed]). Edits made on another device while the local player
 * plays are not taken here: a song removed there comes back at the next song. Songs without a
 * Spotify id ([uriOf] null) stay in the local queue only. Runs on [scope] (the main thread in the
 * app), page calls in the background.
 */
internal class SpotifyQueueSync(
    private val page: SyncPage,
    private val host: ListenSyncHost,
    private val scope: CoroutineScope,
    private val uriOf: (SyncTrack) -> String?,
    private val running: () -> Boolean,
    /** The page plays this uri for the sync (it landed there). */
    private val landedOn: (String) -> Boolean,
) {
    private val lock = Mutex()
    /** How many of each uri the sync queued on Spotify (or took from there): the ones it may take out again. */
    private val owned = HashMap<String, Int>()
    /** The local queue's songs at the last edit seen (see [localChanged]). */
    @Volatile private var lastLocal: List<String> = emptyList()
    private val sync = Later(scope) { syncNow() }

    /** The local player's queue as published: synced when it changed. */
    fun localChanged(queued: List<SyncTrack>) {
        val uris = queued.mapNotNull(uriOf)
        if (uris == lastLocal) return
        lastLocal = uris
        schedule()
    }

    /** The page is on the local player's song: Spotify's queue is checked again. */
    fun landed() = schedule()

    /** The sync played [uri] from Spotify's queue, which consumed that copy. */
    fun consumed(uri: String) = synchronized(owned) { take(uri, 1) }

    private fun schedule() = sync.schedule(SETTLE_MS)

    private fun usable(): Boolean =
        host.enabled && host.localActive && running() && !host.routing &&
            page.keepsQueue && page.ready.value && !page.playsElsewhere.value && !page.adPlaying.value

    private fun syncNow() {
        if (!usable()) return
        val local = host.queued().mapNotNull(uriOf)
        // The song about to be played from Spotify's queue by the sync keeps its copy there until then.
        val current = host.local()?.track?.let(uriOf)
        val spare = current?.takeIf { !landedOn(it) }
        scope.launch { lock.withLock { runCatching { reconcile(local, spare) }.onFailure { log("not synced: ${it.message}") } } }
    }

    private suspend fun reconcile(local: List<String>, spare: String?) {
        // Spotify slow to send its queue: waited for a moment rather than this edit dropped.
        val q = page.queue.value ?: withTimeoutOrNull(QUEUE_ECHO_MS * 2) { page.queue.first { it != null } }
            ?: return log("not synced: Spotify sent no queue")
        val items = q.queued.filter { it.kind == ItemKind.TRACK && it.uri.startsWith(TRACK_PREFIX) }
        val plan = synchronized(owned) { QueueSyncRules.plan(local, items.map { it.uri }, HashMap(owned), spare) }
        if (plan.empty) return
        // A newer local edit, or the sync gone meanwhile: this plan is stale, the next sync does it.
        fun stale() = local != lastLocal || !usable()
        if (stale()) return
        if (plan.remove.isNotEmpty()) {
            val gone = plan.remove.map(items::get)
            page.removeFromQueue(gone)
            synchronized(owned) { gone.forEach { take(it.uri, 1) } }
        }
        for (insert in plan.inserts) for (batch in insert.uris.chunked(BATCH)) {
            if (stale()) return
            val before = insert.beforeIndex?.let(items::get)
            if (before == null) page.addToQueue(batch) else page.insertInQueue(batch, before)
            synchronized(owned) { batch.forEach { owned[it] = (owned[it] ?: 0) + 1 } }
        }
        log("Spotify's queue follows the local one: ${plan.inserts.sumOf { it.uris.size }} added, ${plan.remove.size} removed")
        // The next sync reads the queue with these edits in it.
        withTimeoutOrNull(QUEUE_ECHO_MS) { page.queue.first { it !== q } }
    }

    private fun take(uri: String, n: Int) {
        val left = (owned[uri] ?: 0) - n
        if (left > 0) owned[uri] = left else owned.remove(uri)
    }

    /**
     * A local player starts on a list whose songs after the picked one are [localNext]: Spotify's
     * queued songs (the account's, maybe queued on another device or by another install of the app)
     * play right after the picked song, as queued songs, then the list goes on. Those [localNext] has
     * there already (the takeover lists put Spotify's queue after the song) are taken as they are; the
     * others are added. Empty with the setting off or nothing queued on Spotify.
     */
    fun adopt(localNext: List<SyncTrack>): List<QueueTake> {
        lastLocal = emptyList()
        if (!host.enabled) return emptyList()
        // The new local queue is made of what Spotify's holds now: the sync owns those, nothing else.
        synchronized(owned) { owned.clear() }
        val queued = page.queue.value?.queued.orEmpty().filter { it.kind == ItemKind.TRACK && it.uri.startsWith(TRACK_PREFIX) }
        if (queued.isEmpty()) return emptyList()
        val ways = QueueSyncRules.adopt(localNext.map { uriOf(it).orEmpty() }, queued.map { it.uri })
        return ways.mapIndexed { i, k -> if (k != null) QueueTake.Local(k) else QueueTake.Remote(queued[i]) }
    }

    /** The songs [adopt]'s answer put in the new local queue: the sync owns those. */
    fun taken(taken: List<SyncTrack>) {
        if (taken.isEmpty()) return
        synchronized(owned) { taken.forEach { t -> uriOf(t)?.let { owned[it] = (owned[it] ?: 0) + 1 } } }
        log("${taken.size} queued songs taken from Spotify")
    }

    private fun log(line: String) = host.log("queue: $line")

    private companion object {
        /** A burst of edits (a list queued song by song, a drag) is synced once it settles. */
        const val SETTLE_MS = 1_000L
        /** Waited (at most) for Spotify's queue to show the edits before the next sync reads it. */
        const val QUEUE_ECHO_MS = 2_500L
        /** Songs per call: a whole playlist in one call is a long wait for the page. */
        const val BATCH = 100
    }
}
