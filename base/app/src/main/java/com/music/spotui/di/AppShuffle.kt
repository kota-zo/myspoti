package com.music.spotui.di

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import app.myspoti.host.android.MyspotiRuntime
import app.myspoti.engine.R
import com.music.spotui.data.api.Response
import com.music.spotui.data.entity.ContextPageModel
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.data.preferences.AppShufflePref
import com.music.spotui.ui.screens.SmartShuffle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.lastOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

/**
 * Myspoti shuffle (an option, [AppShufflePref]). Reported (5 people): Spotify's shuffle plays the
 * same ~15 songs of a 40-song list again and again, and songs heard already before the rest. With
 * the option and shuffle on, a list played on Spotify plays in an order Myspoti draws once
 * ([ShuffleOrder]): every song of the list once, the one picked first, before the list starts
 * over. The order goes to the page as a sorted list's does (the list played as that order, see
 * player-bridge.js), with Spotify's shuffle off, and stays while the list plays: a skip goes on in
 * it, a song picked in the list plays next in it, songs added to the list join what is to come.
 * The player shows shuffle on.
 *
 * The local player (extensions, downloads, files of the device) deals its own order the same way
 * already (LocalPlayer.setShuffle): nothing changes there. A list Spotify keeps on its own shuffle
 * (an account that may only shuffle, see SpotifyPlayer.onRefused) is left to it, and the user told.
 */
object AppShuffle {
    private const val TRACK = "spotify:track:"
    /** The page reporting another list for that long after a play: the play did not land. */
    private const val LAND_MS = 15_000L
    /** The page turning its shuffle back on within that after a play is Spotify keeping it. */
    private const val IMPOSED_MS = 30_000L

    private val main = Handler(Looper.getMainLooper())
    /** Bumped by every play and by the end of the shuffle: a list loaded for an older one plays nothing. */
    private val gen = AtomicInteger()

    /** The list played in Myspoti's order (null: none). */
    @Volatile private var list: String? = null
    /** Its track uris in that order, [at]: the song playing. */
    private var order: List<String> = emptyList()
    private var at = -1
    /** The order the list plays in without shuffle (a sorted list's; null: the list's own). */
    private var plain: List<String>? = null
    /** The page reported the list since the play. */
    private var landed = false
    private var startedAt = 0L
    /** Spotify's shuffle came back on once and was turned off again. */
    private var retried = false
    /** Shuffle turned on or off while paused: the song goes on in its new order at the next play. */
    private var due: (() -> Boolean)? = null
    /** Lists Spotify keeps on its own shuffle for this account, this session. */
    private val imposed = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())

    /** The last list played from its page, for shuffle turned on in the player. */
    private class Noted(val uri: String, val tracks: List<SongsModel>, val plain: List<String>?, val complete: Boolean)
    @Volatile private var noted: Noted? = null

    /** Myspoti's order may play [contextUri] now. */
    private fun usable(contextUri: String): Boolean =
        AppShufflePref.on && contextUri.isNotBlank() && contextUri !in imposed && SmartShuffle.on.value != contextUri &&
            // Another device plays: the page only commands it, and the order is the page's own.
            SongPlayer.activeDevice.value == null

    /** A list Myspoti reads in full: a playlist, an album, Liked Songs. */
    private fun dealable(contextUri: String): Boolean =
        contextUri.startsWith("spotify:playlist:") || contextUri.startsWith("spotify:album:") || contextUri.contains(":collection")

    /**
     * [song] picked in [p] (ContextViewModel, on Spotify), [plain] the order the list plays in
     * without shuffle. True: it plays in Myspoti's order (shuffle on, the option on); false: the
     * caller plays it as before.
     */
    fun play(p: ContextPageModel, song: SongsModel, plain: List<String>?): Boolean {
        if (!AppShufflePref.on) return false
        noted = Noted(p.uri, p.tracks, plain, p.complete)
        if (!Graph.currentSongState.shuffle.value || !usable(p.uri) || !dealable(p.uri) || song.spotifyTrackId.isBlank()) return false
        val first = TRACK + song.spotifyTrackId
        if (p.complete) return start(p.uri, p.tracks, first, plain)
        // The list is still loading: every page of it first, so all its songs are dealt.
        val g = gen.incrementAndGet()
        MyspotiRuntime.scope.launch {
            val all = load(p.uri)?.takeIf { l -> l.any { it.spotifyTrackId == song.spotifyTrackId } } ?: p.tracks
            main.post { if (g == gen.get()) start(p.uri, all, first, plain) }
        }
        return true
    }

    /**
     * Shuffle turned on or off while a list plays on Spotify (SongPlayer.setShuffle). True: done
     * here (the song playing goes on where it is, in the list's new order); false: Spotify's shuffle.
     */
    fun toggle(on: Boolean): Boolean {
        if (!AppShufflePref.on) return false
        val np = SongPlayer.nowPlaying.value
        val ctx = np.contextUri
        if (!on) {
            val was = list ?: return false
            val back = plain
            val dealt = order.isNotEmpty()
            clear()
            if (ctx != was || SmartShuffle.on.value == was || !np.uri.startsWith(TRACK)) return false
            Graph.currentSongState.shuffle.value = false
            // Turned off before the list was dealt: it still plays in its own order.
            if (!dealt) return true
            whenPlaying {
                val now = SongPlayer.nowPlaying.value
                if (now.contextUri != was || !now.uri.startsWith(TRACK)) return@whenPlaying false
                app.myspoti.host.android.Logbook.log("shuffle: Myspoti's off, $was back in its order")
                SongPlayer.playSong(now.uri, contextUri = was, order = back, positionMs = SongPlayer.getCurrentPosition(), askExtensions = false)
                true
            }
            return true
        }
        if (!usable(ctx) || !dealable(ctx) || !np.uri.startsWith(TRACK)) return false
        val g = gen.incrementAndGet()
        synchronized(this) { list = ctx; order = emptyList(); at = -1; plain = noted?.takeIf { it.uri == ctx }?.plain; landed = true; startedAt = SystemClock.elapsedRealtime(); retried = false; due = null }
        Graph.currentSongState.shuffle.value = true
        MyspotiRuntime.scope.launch {
            val tracks = load(ctx)
            main.post {
                if (g != gen.get()) return@post
                val now = SongPlayer.nowPlaying.value
                if (tracks == null || now.contextUri != ctx || LocalPlayer.active.value) {
                    clear()
                    // The list could not be read: Spotify's shuffle, as without the option.
                    if (tracks == null && now.contextUri == ctx) SongPlayer.setPageShuffle(true)
                    return@post
                }
                val back = plain
                whenPlaying {
                    val cur = SongPlayer.nowPlaying.value
                    if (g != gen.get() || cur.contextUri != ctx) return@whenPlaying false
                    // A song not in the list (one the user queued): Spotify's shuffle, as without the option.
                    start(ctx, tracks, cur.uri, back, SongPlayer.getCurrentPosition()).also { if (!it) { clear(); SongPlayer.setPageShuffle(true) } }
                }
            }
        }
        return true
    }

    /** Shuffle changed while paused: what [due] holds plays at the next play (SongPlayer.play). True: done here. */
    fun resume(): Boolean {
        val d = synchronized(this) { due.also { due = null } } ?: return false
        return d()
    }

    private fun whenPlaying(block: () -> Boolean) {
        if (SongPlayer.isPlaying()) block() else synchronized(this) { due = block }
    }

    /**
     * Deals [tracks] ([first] first, or next in the order dealt already) and plays it from [first].
     * False: [first] is not in the list, nothing plays.
     */
    private fun start(uri: String, tracks: List<SongsModel>, first: String, back: List<String>?, positionMs: Long? = null): Boolean {
        val songs = tracks.filter { it.spotifyTrackId.isNotBlank() }.associateBy { TRACK + it.spotifyTrackId }
        // Each song once, even one the list holds twice.
        val uris = songs.keys.toList()
        if (first !in songs) return false
        val (dealt, from) = synchronized(this) {
            // A list still loading for an older play or toggle plays nothing now.
            gen.incrementAndGet()
            // Still in this list: the order dealt goes on, with the list as it is now.
            val same = list == uri && landed && order.isNotEmpty() && SongPlayer.nowPlaying.value.contextUri == uri
            val kept = if (same) ShuffleOrder.refresh(order, at, uris) else null
            val picked = kept?.let { (o, a) -> ShuffleOrder.pick(o, a, first) }
            val dealt = picked ?: (ShuffleOrder.draw(uris, first) to 0)
            list = uri; order = dealt.first; at = dealt.second; plain = back
            landed = same; startedAt = SystemClock.elapsedRealtime(); retried = false; due = null
            dealt
        }
        val song = songs.getValue(dealt[from])
        app.myspoti.host.android.Logbook.log("shuffle: Myspoti's order for $uri, ${dealt.size} songs, playing at ${from + 1}")
        val state = Graph.currentSongState
        // Spotify's shuffle would mix the order again.
        SongPlayer.setPageShuffle(false)
        state.shuffle.value = true
        state.updateQueue(dealt.mapNotNull(songs::get))
        SongPlayer.playSong(song.url, contextUri = uri, order = dealt, positionMs = positionMs, askExtensions = false)
        return true
    }

    /**
     * A state of the page (SongPlayer.applyState) on [contextUri] and the song [uri]; [trusted]: its
     * [shuffle] is not an older state than the last shuffle asked for. True: Myspoti's order plays
     * the list (the player shows shuffle on).
     */
    fun onPage(contextUri: String, uri: String, shuffle: Boolean, trusted: Boolean): Boolean {
        var told: String? = null
        var restore = false
        val mine = synchronized(this) {
            val l = list ?: return false
            when {
                !AppShufflePref.on || SmartShuffle.on.value == l -> { clearLocked(); false }
                // The play asked for is on its way, or another list plays now (played from elsewhere
                // than its page): shuffle stays on there, Spotify's, as without the option.
                contextUri != l -> if (landed || SystemClock.elapsedRealtime() - startedAt > LAND_MS) {
                    // Shown on meanwhile: the page's shuffle is on its way.
                    clearLocked(); restore = !shuffle; restore
                } else true
                else -> {
                    landed = true
                    order.indexOf(uri).takeIf { it >= 0 }?.let { at = it }
                    if (!shuffle || !trusted) true
                    else if (SystemClock.elapsedRealtime() - startedAt > IMPOSED_MS) {
                        // Spotify's shuffle turned on later (another device): it plays the list now.
                        clearLocked(); false
                    } else if (!retried) {
                        // The page loaded with its own shuffle on (the first command came too early): once more.
                        retried = true
                        SongPlayer.setPageShuffle(false)
                        true
                    } else {
                        imposed += l; told = l; clearLocked(); false
                    }
                }
            }
        }
        if (restore) SongPlayer.setPageShuffle(true)
        told?.let { l ->
            app.myspoti.host.android.Logbook.log("shuffle: Spotify keeps its own shuffle on $l, Myspoti's order left")
            main.post { android.widget.Toast.makeText(Graph.app, Graph.app.getString(R.string.myspoti_shuffle_imposed), android.widget.Toast.LENGTH_LONG).show() }
        }
        return mine
    }

    /** The order a play of [contextUri] in [sent] leaves when shuffle is off (SongPlayer's plain order). */
    fun unshuffled(contextUri: String, sent: List<String>?): List<String>? =
        synchronized(this) { if (list == contextUri && sent != null && sent == order) plain else sent }

    /** The option turned off: a list playing in Myspoti's order goes on in Spotify's shuffle. */
    fun forget() {
        val was = list
        clear()
        noted = null
        if (was != null && SongPlayer.nowPlaying.value.contextUri == was) SongPlayer.setPageShuffle(true)
    }

    private fun clear() = synchronized(this) { clearLocked() }

    private fun clearLocked() {
        gen.incrementAndGet()
        list = null; order = emptyList(); at = -1; plain = null; landed = false; due = null
    }

    /** Every song of the list [contextUri] (all its pages), null when it cannot be read in full. */
    private suspend fun load(contextUri: String): List<SongsModel>? {
        noted?.takeIf { it.uri == contextUri && it.complete && it.tracks.isNotEmpty() }?.let { return it.tracks }
        val api = Graph.api
        val id = contextUri.substringAfterLast(':')
        val flow: Flow<Response<ContextPageModel>> = when {
            contextUri.contains(":collection") -> api.getLikedPage()
            contextUri.startsWith("spotify:playlist:") -> api.getPlaylistPage(id)
            contextUri.startsWith("spotify:album:") -> api.getAlbumPage(id)
            else -> return null
        }
        val page = withContext(Dispatchers.IO) { runCatching { flow.filterIsInstance<Response.Success<ContextPageModel>>().lastOrNull()?.data }.getOrNull() }
        return page?.takeIf { it.complete }?.tracks?.takeIf { it.isNotEmpty() }
    }
}
