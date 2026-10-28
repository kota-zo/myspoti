package com.music.spotui.ui.screens

import com.music.spotui.ui.components.wideClickable
import com.music.spotui.ui.theme.AppDestructive
import com.music.spotui.ui.theme.tone
import com.music.spotui.ui.theme.AppText
import com.music.spotui.ui.theme.headerTint

import com.music.spotui.ui.theme.AppMuted

import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.ui.res.stringResource

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.async
import com.music.spotui.ui.components.SkeletonKind
import com.music.spotui.ui.components.animatedRow
import com.music.spotui.ui.components.pressable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Group
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.CrossFade
import com.bumptech.glide.integration.compose.GlideImage
import com.music.spotui.data.api.Api
import com.music.spotui.data.api.EditablePlaylists
import com.music.spotui.data.api.Response
import com.music.spotui.data.entity.ContextPageModel
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.di.CurrentSongState
import com.music.spotui.di.Graph
import com.music.spotui.di.Palette
import com.music.spotui.di.SongPlayer
import com.music.spotui.ui.components.Loader
import com.music.spotui.ui.navigation.artistRoute
import app.myspoti.engine.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.withContext
import androidx.compose.foundation.layout.offset
import androidx.compose.material.icons.filled.AutoAwesome
import app.myspoti.host.android.MyspotiRuntime

private val SpotifyGreen: Color get() = com.music.spotui.ui.theme.AppAccent
private val PageBackground: Color get() = com.music.spotui.ui.theme.AppBackground

/** Album / playlist page, loaded by Spotify id and laid out like the official app. */
class ContextViewModel(
    private val api: Api = Graph.api,
    private val state: CurrentSongState = Graph.currentSongState,
) : ViewModel() {
    companion object {
        // Last pages shown, process-wide (back / forward / reopen are instant).
        private val recent = object : java.util.LinkedHashMap<String, Response<ContextPageModel>>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Response<ContextPageModel>>?) = size > 12
        }.let { java.util.Collections.synchronizedMap(it) }

        /** Signed out: the pages shown belong to the old account. */
        fun clearCache() { recent.clear(); inflight.values.forEach { it.cancel() }; inflight.clear() }

        // Look-ups started by a touch (see pressHint), joined by the page when it opens.
        private val inflight = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Deferred<Unit>>()
        private val prefetchScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)

        /** A playlist up to this long is prefetched whole, longer ones by their first page. */
        private const val WHOLE_PLAYLIST_MAX = 200

        /** Pages likely to be opened next (top of the library, home shortcuts), loaded quietly. */
        fun prefetchSoon(pages: List<Pair<String, String>>) {
            prefetchScope.launch {
                kotlinx.coroutines.delay(1_000)
                // One after the other: they must not slow down what is on screen.
                for ((type, id) in pages) {
                    prefetch(type, id)
                    inflight["$type:$id"]?.await()
                }
            }
        }

        /** Start loading a page the user is about to open (first page of tracks). */
        fun prefetch(type: String, id: String) {
            if (id.isBlank() && type != "liked") return
            val key = "$type:$id"
            if (recent[key] is Response.Success || inflight.containsKey(key)) return
            inflight[key] = prefetchScope.async {
                try {
                    val api = Graph.api
                    val flow = when (type) {
                        "album" -> api.getAlbumPage(id)
                        "liked" -> api.getLikedPage()
                        else -> api.getPlaylistPage(id)
                    }
                    // The first page of tracks; a playlist of 200 songs or fewer is waited for whole (as
                    // PagePrefetch does), so it opens complete rather than cut after its first 100.
                    var first: Response<ContextPageModel>? = null
                    val settled = flow.first { r ->
                        if (r is Response.Loading) return@first false
                        if (first == null) first = r
                        r !is Response.Success || type != "playlist" || r.data.complete || r.data.tracks.size > WHOLE_PLAYLIST_MAX
                    }
                    val shown = (settled as? Response.Success)?.takeIf { it.data.complete } ?: first
                    if (shown is Response.Success) {
                        recent[key] = shown
                        // The hearts are known before the page opens.
                        val p = shown.data
                        if (p.type != "liked" && p.type != "liked-artist") SongPlayer.libraryContains(
                            p.tracks.mapNotNull { s -> s.spotifyTrackId.takeIf { it.isNotBlank() }?.let { "spotify:track:$it" } } + p.uri)
                        // The header's cover (the big one) is on storage before the page draws it.
                        if (p.coverUri.startsWith("http") && !com.music.spotui.data.DataSaver.metered)
                            runCatching { com.bumptech.glide.Glide.with(Graph.app).downloadOnly().load(p.coverUri).submit() }
                    }
                } catch (t: kotlinx.coroutines.CancellationException) {
                    throw t
                } catch (_: Throwable) {
                } finally {
                    inflight.remove(key)
                }
            }
        }
    }
    private val _page = MutableStateFlow<Response<ContextPageModel>>(Response.Loading())
    val page: StateFlow<Response<ContextPageModel>> = _page
    val savedUris = mutableStateOf<Set<String>>(emptySet())
    val contextSaved = mutableStateOf(false)
    /** The page is a playlist the user may change: only there can a track be removed. */
    val editableContext = mutableStateOf(false)
    /** Who can change this playlist: more than one when it is collaborative (their faces in the header). */
    val members = mutableStateOf<List<app.myspoti.api.PlaylistMember>>(emptyList())
    /** On a collaborative playlist: who added each row (by row id, then by song). */
    val adders = mutableStateOf<Map<String, app.myspoti.api.PlaylistMember>>(emptyMap())
    /** Someone else's collaborative playlist this account was never invited to: it can't add to it. */
    val collabLocked = mutableStateOf(false)
    private var key: String? = null
    private var savedChecked = 0

    private var lastType = ""
    private var lastId = ""

    /** A pull-to-refresh is reading the page again. */
    val refreshing = mutableStateOf(false)

    /** "Recommended songs" under an own playlist; null until asked. */
    val recs = mutableStateOf<List<SongsModel>?>(null)
    /** Suggested already (shown, added or refreshed away): not suggested again on this page. */
    private val recsSeen = LinkedHashSet<String>()
    private var recsJob: kotlinx.coroutines.Job? = null

    /** Asks Spotify for songs that fit [p] (its name when it is empty), [refresh] for new ones. */
    fun loadRecs(p: ContextPageModel, refresh: Boolean = false) {
        if (recsJob?.isActive == true) return
        if (!refresh && recs.value != null) return
        recsJob = viewModelScope.launch(Dispatchers.IO) {
            val inList = p.tracks.mapTo(HashSet()) { it.spotifyTrackId }
            val skip = (inList + recsSeen).map { "spotify:track:$it" }
            val got = runCatching { MyspotiRuntime.api.playlists.recommended(p.uri, limit = RECS_COUNT, skip = skip) }
                .getOrDefault(emptyList()).map(api::songOf)
                .filter { it.spotifyTrackId !in inList && it.spotifyTrackId !in recsSeen }
            got.forEach { recsSeen += it.spotifyTrackId }
            recs.value = got
        }
    }

    /** [song] added to the playlist: it leaves the suggestions at once, and one more is asked for when few remain. */
    fun takeRec(p: ContextPageModel, song: SongsModel) {
        val left = recs.value.orEmpty().filter { it.spotifyTrackId != song.spotifyTrackId }
        recs.value = left
        if (left.size < 3) loadRecs(p, refresh = true)
    }

    fun refresh() {
        if (refreshing.value) return
        refreshing.value = true
        reload()
    }

    /** Set by [reload]: the next load skips the short-lived caches (whole Liked list, playlist songs). */
    private var fresh = false

    /** After an edit (name, cover, description): the page from the server again. */
    fun reload() {
        val k = key ?: return
        recent.remove(k)
        key = null
        fresh = true
        // The likes, the members and who added each song are read again with the new list (a
        // song just added showed no face on a collaborative playlist).
        savedChecked = 0
        load(lastType, lastId)
    }

    private var warmedFor = ""

    fun load(type: String, id: String) {
        lastType = type; lastId = id
        if (key == "$type:$id") return
        key = "$type:$id"
        // A page seen recently is on screen from the first frame (no blank, then pop,
        // in the middle of the slide); the network still refreshes it behind.
        recent[key]?.let { _page.value = it }
        val pending = inflight[key]
        viewModelScope.launch(Dispatchers.IO) {
            // Touched a moment ago: join that look-up instead of sending a second one.
            if (pending != null && _page.value !is Response.Success) {
                runCatching { pending.await() }
                recent[key]?.let { _page.value = it }
            }
            // Fetched ahead (PagePrefetch) or opened before, on storage: shown whole at once.
            var fromDisk = false
            // A list downloaded as a whole opens offline: its kept page, the network refreshing it behind.
            val offlineUri = when (type) { "album", "playlist" -> "spotify:$type:$id"; "liked" -> "spotify:collection:tracks"; else -> null }
            if (_page.value !is Response.Success && offlineUri != null)
                com.music.spotui.data.offline.OfflineCollections.offlinePage(offlineUri)?.let { fromDisk = true; _page.value = Response.Success(it) }
            if (_page.value !is Response.Success && type == "playlist")
                com.music.spotui.data.api.ScreenCache.page(id).read()?.let { fromDisk = true; _page.value = Response.Success(it) }
            val force = fresh.also { fresh = false }
            val flow = when (type) {
                "album" -> api.getAlbumPage(id)
                "liked" -> api.getLikedPage(full = force)
                // id: "<artist id>|<artist name>"
                "liked-artist" -> api.getLikedByArtistPage(id.substringBefore('|'), id.substringAfter('|', ""))
                else -> api.getPlaylistPage(id, force = force)
            }
            // Liked Songs: a like or unlike anywhere puts the list right at once.
            if (type == "liked") {
                fun show(songs: List<SongsModel>) {
                    val current = (_page.value as? Response.Success)?.data ?: return
                    val updated = Response.Success(current.copy(
                        tracks = songs,
                        metaLine = Graph.app.resources.getQuantityString(R.plurals.liked_songs_header_metadata_text, songs.size, songs.size),
                    ))
                    _page.value = updated
                    recent[key!!] = updated
                }
                // An unlike leaves the list on the tap, before Spotify has answered (reported: slow).
                viewModelScope.launch(Dispatchers.IO) {
                    SongPlayer.likedTaps.collect { (id, liked) ->
                        if (liked) return@collect
                        val current = (_page.value as? Response.Success)?.data ?: return@collect
                        if (current.tracks.any { it.spotifyTrackId == id }) show(current.tracks.filterNot { it.spotifyTrackId == id })
                    }
                }
                // Once Spotify took it, the newest songs are read again: at once, then once more
                // in case the first read still had the old list.
                viewModelScope.launch(Dispatchers.IO) {
                    SongPlayer.likedChanges.drop(1).collectLatest {
                        api.mergeLikedHead()?.let(::show)
                        kotlinx.coroutines.delay(2_500)
                        api.mergeLikedHead()?.let(::show)
                    }
                }
            }
            try { flow.collect { r ->
                if (r is Response.Loading && _page.value is Response.Success) return@collect
                // Offline or refused: the page already on screen stays rather than an error.
                if (r is Response.Error && fromDisk && _page.value is Response.Success) return@collect
                // A refresh keeps the full list on screen until the new one is complete: the first
                // page alone would cut a long playlist short and lose the place in it.
                if ((refreshing.value || fromDisk) && r is Response.Success && !r.data.complete &&
                    (_page.value as? Response.Success)?.data?.let { it.uri == r.data.uri && it.tracks.size > r.data.tracks.size } == true) return@collect
                _page.value = r
                if (r is Response.Success) {
                    recent[key!!] = r
                    val p = r.data
                    // Its first songs are made ready, so a tap plays at once (an audio-source extension).
                    if (warmedFor != p.uri) { warmedFor = p.uri; viewModelScope.launch { runCatching { com.music.spotui.extensions.ExtensionAudio.warm(p.tracks) } } }
                    if (type == "playlist" && p.complete) com.music.spotui.data.api.PagePrefetch.refreshed(id, p)
                    // Check saved state on the first page and again once complete.
                    if ((savedChecked == 0 && p.tracks.isNotEmpty()) || (p.complete && savedChecked < 2)) {
                        savedChecked = if (p.complete) 2 else 1
                        refreshSaved(p)
                    }
                }
            } } finally { refreshing.value = false }
        }
    }

    private fun refreshSaved(p: ContextPageModel) = viewModelScope.launch {
        if (p.type == "liked" || p.type == "liked-artist") {
            // Everything here is liked by definition.
            savedUris.value = p.tracks.map { "spotify:track:${it.spotifyTrackId}" }.toSet()
            return@launch
        }
        val uris = p.tracks.mapNotNull { s -> s.spotifyTrackId.takeIf { it.isNotBlank() }?.let { "spotify:track:$it" } } + p.uri
        // Apart from what follows: the hearts of a 6000-song list are 120 requests one after the
        // other, and the page's own controls (add songs, members, who added what) waited behind
        // all of them.
        launch {
            val saved = SongPlayer.libraryContains(uris)
            savedUris.value = saved
            contextSaved.value = p.uri in saved
        }
        // A11: Spotify refuses an edit to a list the user cannot change, so the entry is only
        // offered where it would work (own and collaborative playlists, as Spotify itself).
        if (p.type == "playlist") editableContext.value = withContext(Dispatchers.IO) {
            runCatching { EditablePlaylists.load().any { it.id == p.uri.substringAfterLast(':') } }.getOrDefault(false)
        }
        if (p.type == "playlist" && !p.isBlend) members.value = withContext(Dispatchers.IO) {
            runCatching { MyspotiRuntime.api.playlists.members(p.uri) }.getOrDefault(emptyList())
        }
        // Collaborative: each song shows who added it (the official app's small face on the row).
        val crew = members.value.associateBy { it.username }
        adders.value = if (crew.size < 2) emptyMap() else withContext(Dispatchers.IO) {
            runCatching {
                val out = HashMap<String, app.myspoti.api.PlaylistMember>()
                MyspotiRuntime.api.playlists.addedBy(p.uri).forEach { r ->
                    val m = crew[r.username] ?: return@forEach
                    if (r.rowUid.isNotBlank()) out["row:" + r.rowUid] = m
                    out.putIfAbsent("song:" + r.trackUri.substringAfterLast(':'), m)
                }
                out
            }.getOrDefault(emptyMap())
        }
        // Collaborative yet not editable here: only an invitation link lets this account add songs.
        // Said on the page instead of leaving the user wondering where the + went.
        collabLocked.value = p.type == "playlist" && !p.isBlend && !editableContext.value && withContext(Dispatchers.IO) {
            runCatching { MyspotiRuntime.api.playlists.access(p.uri) == app.myspoti.api.PlaylistAccess.COLLABORATIVE }.getOrDefault(false)
        }
    }

    /**
     * Owner, from the "Shared with" list: a member becomes a collaborator or a listener, or leaves
     * the playlist ([collaborator] null). The list answers at once; a refusal puts back what Spotify has.
     */
    fun changeMember(p: ContextPageModel, m: app.myspoti.api.PlaylistMember, collaborator: Boolean?, onDone: (Boolean) -> Unit) {
        val before = members.value
        members.value = if (collaborator == null) before.filter { it.username != m.username }
        else before.map { if (it.username == m.username) it.copy(isCollaborator = collaborator) else it }
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    if (collaborator == null) MyspotiRuntime.api.playlists.removeMember(p.uri, m.username)
                    else MyspotiRuntime.api.playlists.setMemberCollaborator(p.uri, m.username, collaborator)
                }.isSuccess
            }
            if (!ok) members.value = withContext(Dispatchers.IO) {
                runCatching { MyspotiRuntime.api.playlists.members(p.uri) }.getOrDefault(before)
            }
            onDone(ok)
        }
    }

    fun toggleContextSaved(p: ContextPageModel) {
        val on = !(SongPlayer.library[p.uri] ?: contextSaved.value)
        com.music.spotui.di.Haptics.toggle(on)
        contextSaved.value = on
        SongPlayer.setSaved(p.uri, on)
    }

    /**
     * The track left this page's playlist: its row goes at once, so the list answers the tap
     * without waiting for Spotify (the write reads every page of the playlist first). A
     * failure puts the page back from the server.
     */
    fun dropTrack(song: SongsModel) {
        val k = key ?: return
        val current = (_page.value as? Response.Success)?.data ?: return
        // The row, not the song: the same song twice in a playlist has the same id twice.
        val at = if (song.rowUid.isNotBlank()) current.tracks.indexOfFirst { it.rowUid == song.rowUid }
            else current.tracks.indexOfFirst { it.id == song.id }
        if (at < 0) return
        val updated = Response.Success(current.copy(tracks = current.tracks.toMutableList().apply { removeAt(at) }))
        _page.value = updated
        recent[k] = updated
    }

    /** A name or description just saved shows in the header at once; the page is read again behind it. */
    fun showDetails(name: String, description: String) {
        val k = key ?: return
        val current = (_page.value as? Response.Success)?.data ?: return
        val updated = Response.Success(current.copy(name = name, description = description))
        _page.value = updated
        recent[k] = updated
    }

    /**
     * Smart shuffle (Spotify keeps its own for Premium): the playlist shuffled, with one of
     * Spotify's recommendations for it after every [SmartShuffle.EVERY] songs.
     */
    fun playSmart(p: ContextPageModel, first: SongsModel?) {
        viewModelScope.launch { SmartShuffle.start(p, first) }
    }

    fun play(p: ContextPageModel, song: SongsModel, sort: TrackOrder = TrackOrder()) {
        // Local files of the playlist (Spotify holds their tags only): one found on this device
        // plays from there, with the other such files of the list; one not found cannot play,
        // the next song that can does.
        with(com.music.spotui.data.local.LocalFileMatch) {
            val sorted = sort.apply(p.tracks)
            if (song.isUnmatchedLocal) {
                val after = sorted.drop(sorted.indexOfFirst { it.id == song.id && it.rowUid == song.rowUid } + 1)
                val next = (after + sorted).firstOrNull { !it.isUnmatchedLocal } ?: return
                return play(p, next, sort)
            }
            if (song.isDeviceFile) {
                viewModelScope.launch {
                    val files = com.music.spotui.data.local.LocalMusic.tracks(Graph.app).associateBy { key(it) }
                    val list = sorted.mapNotNull { files[it.url] }
                    val at = list.indexOfFirst { key(it) == song.url }.coerceAtLeast(0)
                    if (list.isNotEmpty()) com.music.spotui.di.LocalPlayer.play(list, at, shuffle = state.shuffle.value, context = p.name, contextUri = p.uri)
                }
                return
            }
        }
        // No connection (the Web Player cannot stream), or the data saver on mobile data:
        // the page's downloaded songs play from the phone. Online, a downloaded song also plays
        // from its file when the whole list is downloaded (it used to stream anyway).
        val app = Graph.app
        val store = com.music.spotui.data.offline.OfflineStore
        val limited = com.music.spotui.data.DataSaver.active || !store.isOnline(app)
        val ids = com.music.spotui.data.offline.LocalPlayPlan.localIds(
            sort.apply(p.tracks).map { it.spotifyTrackId }, song.spotifyTrackId, p.complete,
            offline = !store.isOnline(app), dataSaver = com.music.spotui.data.DataSaver.active, isDownloaded = store::isDownloaded,
        )
        if (ids != null) {
            val local = ids.mapNotNull { store.tracks[it] }
            val at = local.indexOfFirst { it.trackId == song.spotifyTrackId }.coerceAtLeast(0)
            if (limited && local.size < p.tracks.size)
                android.widget.Toast.makeText(app, app.getString(R.string.offline_only_downloaded, local.size, p.tracks.size), android.widget.Toast.LENGTH_SHORT).show()
            com.music.spotui.di.LocalPlayer.play(local.map(store::toLocalTrack), at, shuffle = state.shuffle.value, context = p.name, contextUri = p.uri)
            return
        }
        // An extension that gives songs' audio (audio-source): the list plays in the local
        // player from there, like downloads; it has nothing for the picked song, Spotify plays.
        if (!limited && com.music.spotui.extensions.Extensions.hasAudioSource()) {
            val shuffle = state.shuffle.value
            SongPlayer.playThroughExtensions(song.spotifyTrackId, song, p.name, { playOnSpotify(p, song, sort) }) { isLatest, _ ->
                com.music.spotui.extensions.ExtensionAudio.playList(sort.apply(p.tracks), song, p.name, shuffle, p.uri, isLatest)
            }
            return
        }
        playOnSpotify(p, song, sort)
    }

    private fun playOnSpotify(p: ContextPageModel, song: SongsModel, sort: TrackOrder) {
        state.updateQueue(p.tracks)
        // B26: keep the list's own name — the player header falls back to it when Spotify's
        // context label comes back blank (it then showed the album's name).
        SongPlayer.registerContextName(p.uri, p.name)
        if (p.type == "liked" || p.type == "liked-artist") SongPlayer.registerLikedSongs(p.tracks.map { it.url })
        // Sorted by the user: Spotify plays the list in that order, not the playlist's. An
        // artist's liked songs are a part of Liked Songs: always given, so only they play.
        val order = if (sort.isCustom && p.type != "liked-artist") null else sort.apply(p.tracks).filter { it.spotifyTrackId.isNotBlank() }.map { "spotify:track:${it.spotifyTrackId}" }
        if (order != null) state.updateQueue(sort.apply(p.tracks))
        // The audio-source extensions were asked already (or the phone is offline): Spotify plays.
        SongPlayer.playSong(song.url, contextUri = p.uri, order = order, askExtensions = false)
        state.updateSongState(song.coverUri, song.title, song.singer, true, song.id, 0, song.album)
    }
}

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
fun ContextScreen(navController: NavController, type: String, id: String) {
    val vm: ContextViewModel = viewModel(key = "$type:$id")
    // During composition, so a cached page is there for the very first frame.
    remember(type, id) { vm.load(type, id) }
    val resp by vm.page.collectAsState()
    Box(modifier = Modifier.fillMaxSize().background(PageBackground)) {
        when (val r = resp) {
            is Response.Loading -> Loader(SkeletonKind.Header)
            is Response.Error -> com.music.spotui.ui.components.ErrorRetry(
                stringResource(R.string.page_load_failed),
                Modifier.align(Alignment.Center),
            ) { vm.reload() }
            is Response.Success -> ContextContent(navController, vm, r.data)
        }
    }
}

@OptIn(ExperimentalGlideComposeApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun ContextContent(navController: NavController, vm: ContextViewModel, p: ContextPageModel) {
    val context = LocalContext.current
    val playerVm: com.music.spotui.ui.viewmodel.PlayerViewModel = viewModel()
    val currentSongId = playerVm.currentSongId.value
    val playing = playerVm.currentSongPlayingState.value
    val shuffle = playerVm.shuffleState.value
    val np by SongPlayer.nowPlaying
    val isThisContext = np.contextUri == p.uri || ((p.type == "liked" || p.type == "liked-artist") && np.contextUri.contains(":collection"))
    val saved by vm.savedUris
    val contextSaved by vm.contextSaved

    var tintTarget by remember { mutableStateOf(Palette.cachedSecond(p.coverUri) ?: tone(0xFF3A3A3A)) }
    LaunchedEffect(p.coverUri) {
        if (p.coverUri.isNotBlank()) Palette().extractSecondColorFromCoverUrl(context, p.coverUri) { tintTarget = it }
    }
    val tint by androidx.compose.animation.animateColorAsState(tintTarget, androidx.compose.animation.core.tween(350), label = "tint")
    var menuSong by remember { mutableStateOf<SongsModel?>(null) }
    // "Find in Liked Songs / in playlist".
    var find by remember { mutableStateOf("") }
    // "Sort by" for the list, kept per list (also after leaving it or restarting the app).
    val ctx = LocalContext.current
    var sort by remember(p.uri) { mutableStateOf(TrackOrder.load(ctx, p.uri)) }
    var askRemoveDownloads by remember { mutableStateOf(false) }
    var playlistMenu by remember { mutableStateOf(false) }
    var albumMenu by remember { mutableStateOf(false) }
    var addingSongs by remember { mutableStateOf(false) }
    var leaveAsk by remember { mutableStateOf(false) }
    // The profile id comes once per session (and is no Compose state): `name` is set just before it,
    // so reading it here redraws the page when the id is there.
    LaunchedEffect(Unit) { com.music.spotui.data.api.ProfileCache.ensure(context) }
    val ownPlaylist = com.music.spotui.data.api.ProfileCache.let { cache ->
        cache.name
        cache.id?.let { id -> p.ownerUri == "spotify:user:$id" } == true
    }
    // A11: confirmations (and their Undo) float above the list, not inside a menu that closes.
    val snackbar = remember { androidx.compose.material3.SnackbarHostState() }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    // A11: the row goes at once, then the removal is written to Spotify. The snackbar keeps
    // the mistake reversible: Undo adds the track back and the page is read again.
    fun removeFromThisPlaylist(song: SongsModel) {
        val playlistId = p.uri.substringAfterLast(':')
        vm.dropTrack(song)
        com.music.spotui.data.api.SpotifySync.removeRowFromPlaylist(context, playlistId, song.spotifyTrackId, song.rowUid) { ok ->
            if (!ok) {
                vm.reload()
                android.widget.Toast.makeText(context, context.getString(R.string.playlist_change_refused), android.widget.Toast.LENGTH_SHORT).show()
            } else scope.launch {
                val chosen = snackbar.showSnackbar(
                    message = context.getString(R.string.removed_from, p.name),
                    actionLabel = context.getString(R.string.undo),
                    withDismissAction = false,
                    // Long: the write has just gone through, the mistake deserves time to be undone.
                    duration = androidx.compose.material3.SnackbarDuration.Long,
                )
                if (chosen == androidx.compose.material3.SnackbarResult.ActionPerformed) {
                    com.music.spotui.data.api.SpotifySync.addTrackToPlaylist(context, playlistId, song.spotifyTrackId) { back ->
                        if (back) vm.reload()
                        else android.widget.Toast.makeText(context, context.getString(R.string.playlist_change_refused), android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    if (leaveAsk) androidx.compose.material3.AlertDialog(
        onDismissRequest = { leaveAsk = false },
        containerColor = tone(0xFF282828),
        title = { Text(stringResource(R.string.playlist_leave), color = AppText) },
        text = { Text(stringResource(R.string.playlist_leave_confirm, p.name), color = AppMuted) },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = {
                leaveAsk = false
                // As from the playlist menu: the page closes now; a refusal says so and the library is read again.
                com.music.spotui.data.api.LibraryEdits.run(context, null,
                    write = { MyspotiRuntime.api.playlists.leave(p.uri) },
                    onDone = { ok -> if (ok) com.music.spotui.data.api.LibraryEdits.reloadSoon() })
                navController.navigateUp()
            }) { Text(stringResource(R.string.playlist_leave_button), color = AppDestructive) }
        },
        dismissButton = { androidx.compose.material3.TextButton(onClick = { leaveAsk = false }) { Text(stringResource(R.string.cancel), color = AppText) } },
    )
    if (addingSongs) com.music.spotui.ui.components.AddSongsScreen(p) { added -> addingSongs = false; if (added) vm.reload() }
    if (albumMenu) com.music.spotui.ui.components.AlbumOptionsSheet(p.uri, p.name, known = p) { albumMenu = false }
    if (playlistMenu) com.music.spotui.ui.components.PlaylistOptionsSheet(
        page = p,
        onAddSongs = if (p.type == "playlist" && vm.editableContext.value && !p.isBlend) ({ addingSongs = true }) else null,
        onDismiss = { playlistMenu = false },
        onChanged = { vm.reload() },
        onEdited = { name, description -> vm.showDetails(name, description) },
        onDeleted = { navController.navigateUp() },
        onProfile = { navController.navigate(com.music.spotui.ui.navigation.profileRoute(it, p.ownerName)) },
    )
    if (askRemoveDownloads) androidx.compose.material3.AlertDialog(
        onDismissRequest = { askRemoveDownloads = false },
        containerColor = tone(0xFF282828),
        title = { Text(stringResource(R.string.playlist_entity_remove_download_dialog_title), color = AppText) },
        text = { Text(stringResource(R.string.playlist_entity_remove_download_dialog_description), color = AppMuted) },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = {
                askRemoveDownloads = false
                com.music.spotui.data.offline.OfflineCollections.remove(p.uri)
                android.widget.Toast.makeText(context, context.getString(R.string.toast_undownload), android.widget.Toast.LENGTH_SHORT).show()
            }) { Text(stringResource(R.string.playlist_entity_remove_download_dialog_positive_button), color = SpotifyGreen) }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = { askRemoveDownloads = false }) {
                Text(stringResource(R.string.add_to_playlist_button_cancel), color = AppText)
            }
        },
    )
    menuSong?.let { sel ->
        val inPlaylist = p.type == "playlist"
        val canRemove = inPlaylist && vm.editableContext.value
        com.music.spotui.ui.components.SongOptionsSheet(
            song = sel,
            navController = navController,
            context = context,
            onDismiss = { menuSong = null },
            // The removal entry only exists on a playlist page the user may change: nowhere else
            // is there a list to take the track out of.
            onRemoveFromPlaylist = if (canRemove) ({ removeFromThisPlaylist(sel) }) else null,
            currentPlaylistId = if (inPlaylist) p.uri.substringAfterLast(':') else null,
        )
    }

    // Liked Songs: a track unliked anywhere leaves the list right away. Derived state:
    // recomputed when the page, the search, the sort or a like changes, while a recomposition
    // of the screen with nothing new in them re-sorts nothing (a 900-track list is real work).
    val findable = p.type == "liked" || p.type == "playlist"
    val all by remember(p) {
        derivedStateOf {
            if (p.type == "liked" || p.type == "liked-artist") p.tracks.filter { SongPlayer.library["spotify:track:${it.spotifyTrackId}"] != false } else p.tracks
        }
    }
    // Unique and stable for a given list: a playlist can hold the same song twice (the id alone
    // is not unique) and the index changes at every sort (which would lose every item). Made with
    // the rows in one derived state: the list re-reads its rows outside composition (a like, a
    // search letter), and keys remembered apart from them could be one list behind (a crash).
    val shown by remember(p, find, sort) {
        derivedStateOf {
            val found = if (findable) all.filter { com.music.spotui.ui.components.matchesFind(find, it.title, it.singer, it.album) } else all
            val list = if (findable) sort.apply(found) else found
            val seen = HashMap<String, Int>()
            list to list.map { s ->
                val id = s.id.toString()
                val n = (seen[id] ?: 0) + 1
                seen[id] = n
                if (n == 1) id else "$id#$n"
            }
        }
    }
    val rows = shown.first

    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val pullState = androidx.compose.material3.pulltorefresh.rememberPullToRefreshState()
    val refreshing by vm.refreshing
    // The search field and the Blend line are folded away: the page opens on its songs. A pull
    // down at the top unfolds them in place (that pull does not refresh; the next one does), and
    // they fold again once the songs are scrolled past them, unless a search is typed.
    val hasExtras = (findable && all.isNotEmpty()) || p.isBlend
    var extrasShown by remember(p.uri) { mutableStateOf(false) }
    val revealPull = remember(p.uri) { ExtrasReveal() }
    revealPull.enabled = hasExtras && !extrasShown
    revealPull.onReveal = { extrasShown = true; com.music.spotui.di.Haptics.toggle(true) }
    // The search typed now, not the one when the page opened (always empty): read stale, the field
    // folded away under the user's fingers while typing, keyboard open (reported freezes and crashes).
    val findNow by androidx.compose.runtime.rememberUpdatedState(find)
    LaunchedEffect(p.uri, listState) {
        androidx.compose.runtime.snapshotFlow { listState.firstVisibleItemIndex }.collect { first ->
            if (first > 3 && findNow.isBlank()) extrasShown = false
        }
    }
    Box(Modifier.fillMaxSize()) {
    androidx.compose.material3.pulltorefresh.PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = { vm.refresh() },
        state = pullState,
        modifier = Modifier.fillMaxSize(),
        // Below the status bar: the page's header runs under it.
        indicator = {
            androidx.compose.material3.pulltorefresh.PullToRefreshDefaults.Indicator(
                state = pullState, isRefreshing = refreshing,
                modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding(),
                containerColor = tone(0xFF282828), color = SpotifyGreen,
            )
        },
    ) {
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize().nestedScroll(revealPull)) {
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(headerTint(tint, 0.25f), PageBackground)))
                    .statusBarsPadding()
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .padding(12.dp)
                        .wideClickable { navController.navigateUp() }
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.35f))
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.accessibility_navigate_back), tint = AppText)
                }
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth()) {
                    if (p.type == "liked" || p.type == "liked-artist") LikedCover(Modifier.fillMaxWidth(0.66f).aspectRatio(1f).shadow(16.dp, RoundedCornerShape(4.dp))) else
                    GlideImage(
                        transition = CrossFade,
                        model = p.coverUri,
                        contentDescription = "",
                        contentScale = ContentScale.Crop,
                        // No cover at all (a new playlist): the app's own image, not an empty square.
                        failure = com.bumptech.glide.integration.compose.placeholder(R.drawable.placeholder),
                        modifier = Modifier
                            .fillMaxWidth(0.66f)
                            .aspectRatio(1f)
                            .shadow(16.dp, RoundedCornerShape(4.dp))
                            .clip(RoundedCornerShape(4.dp)),
                    )
                }
                Spacer(Modifier.height(20.dp))
                Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                    com.music.spotui.ui.components.FitText(p.name, fontSize = 26.sp, minSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 2)
                    if (p.description.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(p.description, color = AppText.copy(alpha = 0.7f), fontSize = 14.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                    val crew = vm.members.value
                    if (crew.size > 1) {
                        // Collaborative: everyone who adds to it, as the official app shows them.
                        Spacer(Modifier.height(10.dp))
                        var showMembers by remember { mutableStateOf(false) }
                        if (showMembers) MembersSheet(
                            members = crew, navController = navController,
                            // The owner manages everyone; a guest only has "Leave playlist" on their own row.
                            ownPlaylist = ownPlaylist, me = com.music.spotui.data.api.ProfileCache.id, canLeave = p.canLeave,
                            onChange = { m, collaborator ->
                                vm.changeMember(p, m, collaborator) { ok ->
                                    if (!ok) android.widget.Toast.makeText(context, R.string.playlist_change_refused, android.widget.Toast.LENGTH_SHORT).show()
                                }
                            },
                            onLeave = { showMembers = false; leaveAsk = true },
                        ) { showMembers = false }
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.pressable(0.96f) { showMembers = true }) {
                            Box(Modifier.width((24 + 16 * (crew.size.coerceAtMost(4) - 1)).dp).height(24.dp)) {
                                crew.take(4).forEachIndexed { n, m ->
                                    Box(Modifier.padding(start = (16 * n).dp).size(24.dp).clip(CircleShape).background(tone(0xFF121212)).padding(1.dp)) {
                                        com.music.spotui.ui.components.ProfileAvatar(m.imageUrl, m.name, 22.dp)
                                    }
                                }
                            }
                            Spacer(Modifier.width(8.dp))
                            Text(
                                if (crew.size <= 2) crew.joinToString(", ") { it.name }
                                else stringResource(R.string.playlist_members_more, crew.first().name, crew.size - 1),
                                color = AppText, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    } else if (p.ownerName.isNotBlank()) {
                        Spacer(Modifier.height(10.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.pressable(0.96f) {
                                if (p.ownerUri.startsWith("spotify:artist:")) {
                                    navController.navigate(artistRoute(p.ownerName.substringBefore(",").trim(), p.ownerUri.substringAfterLast(":")))
                                } else if (p.ownerUri.startsWith("spotify:user:")) {
                                    navController.navigate(com.music.spotui.ui.navigation.profileRoute(p.ownerUri.substringAfterLast(":"), p.ownerName))
                                }
                            },
                        ) {
                            if (p.ownerAvatar.isNotBlank()) {
                                GlideImage(
                                    transition = CrossFade,
                                    model = p.ownerAvatar,
                                    contentDescription = "",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.size(24.dp).clip(CircleShape),
                                )
                                Spacer(Modifier.width(8.dp))
                            }
                            Text(p.ownerName, color = AppText, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    if (p.metaLine.isNotBlank() || p.explicit) {
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (p.explicit && p.type == "album") {
                                ExplicitBadge()
                                Spacer(Modifier.width(6.dp))
                            }
                            Text(p.metaLine, color = AppText.copy(alpha = 0.7f), fontSize = 13.sp)
                        }
                    }
                }
                // Action row: save, shuffle, big green play.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp)
                ) {
                    // Own playlist: "Add to this playlist" (search, suggestions), where the small cover was.
                    if (p.type == "playlist" && vm.editableContext.value && !p.isBlend) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.size(36.dp).clip(CircleShape).background(AppText.copy(alpha = 0.12f)).pressable(0.9f) { addingSongs = true },
                        ) {
                            Icon(Icons.Default.Add, contentDescription = stringResource(R.string.add_songs_title), tint = AppText, modifier = Modifier.size(24.dp))
                        }
                        Spacer(Modifier.width(20.dp))
                    }
                    // Saving is for other people's playlists: the user's own are in the library already.
                    if (p.type != "liked" && !(p.type == "playlist" && ownPlaylist)) {
                        LikeButton(liked = SongPlayer.library[p.uri] ?: contextSaved, size = 28.dp) { vm.toggleContextSaved(p) }
                        Spacer(Modifier.width(20.dp))
                    }
                    // Once per list, not at every redraw of the header (6000 songs, mapped each time).
                    val ids = remember(p.tracks) { p.tracks.map { it.spotifyTrackId }.filter { it.isNotBlank() }.distinct() }
                    // Songs with no matching recording count as settled: the list can still be removed.
                    val downloadedCount = ids.count {
                        com.music.spotui.data.offline.OfflineStore.isDownloaded(it) ||
                            com.music.spotui.data.offline.OfflineStore.status[it] == com.music.spotui.data.offline.OfflineStatus.Unavailable
                    }
                    val busy = ids.any {
                        val st = com.music.spotui.data.offline.OfflineStore.status[it]
                        st == com.music.spotui.data.offline.OfflineStatus.Queued || st is com.music.spotui.data.offline.OfflineStatus.Downloading
                    }
                    // A list downloaded as a whole stays in step with Spotify (new songs in, gone songs out).
                    val listKept = com.music.spotui.data.offline.OfflineCollections.isDownloaded(p.uri)
                    LaunchedEffect(listKept, p.complete, p.tracks.size) { if (listKept && p.complete) com.music.spotui.data.offline.OfflineCollections.sync(p) }
                    com.music.spotui.ui.components.DownloadListButton(downloadedCount, ids.size, busy, kept = listKept) {
                        if (listKept) askRemoveDownloads = true
                        else {
                            com.music.spotui.ui.components.DownloadAsk.start(context) { q ->
                                com.music.spotui.di.Haptics.confirm()
                                com.music.spotui.data.offline.OfflineCollections.download(p, q)
                                android.widget.Toast.makeText(context, context.getString(R.string.download_notification_downloading), android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    if (p.type == "playlist" || p.type == "album" || p.type == "liked" || p.type == "liked-artist") {
                        Spacer(Modifier.width(16.dp))
                        Icon(Icons.Default.MoreVert, stringResource(R.string.content_description_context_menu), tint = AppText.copy(alpha = 0.8f),
                            modifier = Modifier.size(26.dp).pressable(0.9f) { if (p.type == "playlist") playlistMenu = true else albumMenu = true })
                    }
                    // Sorting stays in reach in the header (the search field above the songs is folded).
                    if (findable && all.isNotEmpty()) {
                        Spacer(Modifier.width(16.dp))
                        TrackSortButton(sort) { sort = it; TrackOrder.save(ctx, p.uri, it) }
                    }
                    Spacer(Modifier.weight(1f))
                    val smart = SmartShuffle.on.value == p.uri
                    Box {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_player_shuffle),
                        contentDescription = stringResource(if (smart) R.string.smart_shuffle_title else R.string.play_mode_item_shuffle_title),
                        tint = if (shuffle || smart) SpotifyGreen else AppText.copy(alpha = 0.8f),
                        modifier = Modifier
                            .size(26.dp)
                            .drawWithContent {
                                drawContent()
                                if (shuffle || smart) drawCircle(SpotifyGreen, radius = 2.dp.toPx(),
                                    center = androidx.compose.ui.geometry.Offset(size.width / 2, size.height + 7.dp.toPx()))
                            }
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) {
                                when {
                                    smart -> { SmartShuffle.on.value = null; playerVm.updateShuffleState(false) }
                                    shuffle && (p.type == "playlist" || p.type == "liked") -> {
                                        SmartShuffle.on.value = p.uri
                                        android.widget.Toast.makeText(context, context.getString(R.string.smart_shuffle_on), android.widget.Toast.LENGTH_SHORT).show()
                                        if (isThisContext) vm.playSmart(p, p.tracks.firstOrNull { it.id == currentSongId })
                                    }
                                    else -> playerVm.updateShuffleState(!shuffle)
                                }
                            },
                    )
                    if (smart) Icon(
                        androidx.compose.material.icons.Icons.Default.AutoAwesome, contentDescription = null, tint = SpotifyGreen,
                        modifier = Modifier.align(Alignment.TopEnd).offset(x = 6.dp, y = (-6).dp).size(11.dp),
                    )
                    }
                    Spacer(Modifier.width(20.dp))
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(56.dp)
                            .pressable(pressedScale = 0.92f) {
                                when {
                                    isThisContext && playing -> SongPlayer.pause()
                                    isThisContext -> SongPlayer.play()
                                    p.tracks.isNotEmpty() && SmartShuffle.on.value == p.uri -> vm.playSmart(p, null)
                                    p.tracks.isNotEmpty() -> vm.play(p, if (shuffle) p.tracks.random() else sort.apply(p.tracks).first(), sort)
                                }
                            }
                            .clip(CircleShape)
                            .background(SpotifyGreen),
                    ) {
                        Icon(
                            painter = painterResource(id = if (isThisContext && playing) R.drawable.ic_playing else R.drawable.play_svgrepo_com),
                            contentDescription = if (isThisContext && playing) stringResource(R.string.content_description_pause_button) else stringResource(R.string.content_description_play_button),
                            tint = Color.Black,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
            }
        }
        // Liked Songs: a track unliked anywhere leaves the list right away.
        // A Blend: its taste-match card and its members, as on the official Blend page.
        if (vm.collabLocked.value && !vm.editableContext.value) item(key = "collab-locked") {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp)).background(AppText.copy(alpha = 0.06f)).padding(12.dp),
            ) {
                Icon(Icons.Outlined.Group, contentDescription = null, tint = AppMuted, modifier = Modifier.size(20.dp))
                Text(stringResource(R.string.playlist_collab_locked, p.ownerName.ifBlank { stringResource(R.string.playlist_collab_owner) }),
                    color = AppMuted, fontSize = 13.sp, modifier = Modifier.padding(start = 10.dp))
            }
        }
        if (p.isBlend) item(key = "blend") {
            androidx.compose.animation.AnimatedVisibility(extrasShown, enter = UnfoldIn, exit = FoldOut) {
                com.music.spotui.ui.components.BlendPanel(p.uri)
            }
        }
        if (findable && all.isNotEmpty()) item(key = "find") {
            androidx.compose.animation.AnimatedVisibility(extrasShown || find.isNotBlank(), enter = UnfoldIn, exit = FoldOut) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp)) {
                com.music.spotui.ui.components.FindInListField(
                    find, { find = it },
                    stringResource(if (p.type == "liked") R.string.find_in_context_edit_text_liked_songs_hint else R.string.find_in_context_edit_text_playlist_hint),
                    Modifier.weight(1f),
                )
            }
            }
        }
        if (shown.first.isEmpty() && find.isNotBlank()) item(key = "find-empty") {
            com.music.spotui.ui.components.FindInListEmpty(stringResource(R.string.assisted_curation_search_empty_view_no_result_title, find.trim()))
        }
        val (listed, keys) = shown
        itemsIndexed(listed, key = { i, _ -> keys[i] }) { _, song ->
            com.music.spotui.ui.components.SwipeToQueue(song, animatedRow()) { TrackRow(
                song = song,
                showCover = p.type != "album",
                isCurrent = song.id == currentSongId,
                saved = SongPlayer.library["spotify:track:${song.spotifyTrackId}"] ?: ("spotify:track:${song.spotifyTrackId}" in saved),
                offline = com.music.spotui.data.offline.OfflineStore.statusOf(song.spotifyTrackId),
                addedBy = vm.adders.value.let { a -> a["row:" + song.rowUid] ?: a["song:" + song.spotifyTrackId] },
                onClick = { if (SmartShuffle.on.value == p.uri) vm.playSmart(p, song) else vm.play(p, song, sort) },
                onMore = { menuSong = song },
            ) }
        }
        // "Recommended songs", as under an own playlist in the official apps (an empty one too).
        if (p.type == "playlist" && vm.editableContext.value && find.isBlank() && !p.isBlend) recommendedItems(vm, p, ctx, currentSongId)
        // Phone files added to this Spotify playlist on this phone (Spotify can't hold them).
        if (p.type == "playlist") {
            val playlistId = p.uri.substringAfterLast(":")
            val additions = com.music.spotui.data.local.LocalPlaylists.additions[playlistId].orEmpty()
            if (additions.isNotEmpty()) {
                item {
                    Text(stringResource(R.string.local_added_section), color = AppText, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(16.dp, 20.dp, 16.dp, 4.dp))
                }
                localItems(additions, p.name) { com.music.spotui.data.local.LocalPlaylists.removeFromSpotify(playlistId, it.key) }
            }
        }
        // An album: Spotify's "More like this", albums close to its first song.
        if (p.type == "album") item(key = "similar") { SimilarAlbums(navController, p.tracks.firstOrNull()?.spotifyTrackId.orEmpty(), p.uri) }
        item { Spacer(Modifier.height(com.music.spotui.ui.components.bottomBarsSpace())) }
    }
    }
    com.music.spotui.ui.components.FastScrollbar(listState, Modifier.align(Alignment.CenterEnd))
    // As in the official app: once the header has mostly scrolled away, a bar in the page's colour
    // fades in at the top with the back arrow and the name; the list no longer runs bare under the
    // status bar. Its opacity follows the scroll in the draw phase (no recomposition per frame).
    val barAlpha = remember(listState) {
        androidx.compose.runtime.derivedStateOf {
            if (listState.firstVisibleItemIndex > 0) 1f else {
                val h = listState.layoutInfo.visibleItemsInfo.firstOrNull()?.size?.toFloat() ?: return@derivedStateOf 0f
                ((listState.firstVisibleItemScrollOffset - h * 0.6f) / (h * 0.2f)).coerceIn(0f, 1f)
            }
        }
    }
    val barShown by remember(listState) { androidx.compose.runtime.derivedStateOf { barAlpha.value > 0f } }
    if (barShown) Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer { alpha = barAlpha.value }
            .background(headerTint(tint, 0.45f))
            .statusBarsPadding()
            .height(56.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.padding(horizontal = 8.dp).wideClickable(horizontal = 8.dp, vertical = 8.dp) { navController.navigateUp() }.size(40.dp),
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.accessibility_navigate_back), tint = AppText)
        }
        // Centred, as on the artist page.
        Text(p.name, color = AppText, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.weight(1f).padding(end = 56.dp))
    }
    // Opened from a collaborative-playlist invitation (…?pt=): the playlist is joined from here.
    val invite by com.music.spotui.data.api.InviteLinks.pending.collectAsState()
    var joining by remember { mutableStateOf(false) }
    val offered = invite?.takeIf { p.type == "playlist" && it.uri == p.uri && !vm.editableContext.value }
    if (offered != null) Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(start = 16.dp, end = 16.dp, bottom = com.music.spotui.ui.components.bottomBarsSpace())
            .clip(RoundedCornerShape(12.dp))
            .background(tone(0xFF282828))
            .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
    ) {
        Text(stringResource(R.string.playlist_invite_banner), color = AppText, fontSize = 14.sp, modifier = Modifier.weight(1f).padding(end = 12.dp))
        androidx.compose.material3.Button(
            enabled = !joining,
            colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = SpotifyGreen, contentColor = Color.Black),
            onClick = {
                joining = true
                com.music.spotui.data.api.InviteLinks.join(context, offered, p) { ok ->
                    joining = false
                    if (ok) {
                        vm.editableContext.value = true
                        vm.contextSaved.value = true
                        SongPlayer.library[p.uri] = true
                        vm.reload()
                        android.widget.Toast.makeText(context, context.getString(R.string.playlist_invite_joined), android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            },
        ) { Text(stringResource(R.string.playlist_invite_join), fontWeight = FontWeight.Bold) }
    }
    androidx.compose.material3.SnackbarHost(
        hostState = snackbar,
        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = com.music.spotui.ui.components.bottomBarsSpace()),
    ) { data ->
        androidx.compose.material3.Snackbar(
            snackbarData = data,
            containerColor = tone(0xFF282828),
            contentColor = AppText,
            actionContentColor = SpotifyGreen,
        )
    }
    }
}

private val UnfoldIn = androidx.compose.animation.expandVertically(androidx.compose.animation.core.tween(280, easing = androidx.compose.animation.core.FastOutSlowInEasing)) +
    androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(220, delayMillis = 60))
private val FoldOut = androidx.compose.animation.shrinkVertically(androidx.compose.animation.core.tween(220)) +
    androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(120))

/**
 * The pull that unfolds a page's folded parts (search, Blend line). It sits between the list and
 * the pull-to-refresh: a drag past the top goes here first, and the whole gesture is kept from
 * the refresh, so unfolding never also reloads the page.
 */
private class ExtrasReveal : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
    var enabled = false
    var onReveal: () -> Unit = {}
    /** This drag unfolded them: the rest of it is not a refresh pull. */
    private var swallowing = false

    override fun onPostScroll(consumed: androidx.compose.ui.geometry.Offset, available: androidx.compose.ui.geometry.Offset,
                              source: androidx.compose.ui.input.nestedscroll.NestedScrollSource): androidx.compose.ui.geometry.Offset {
        if (source != androidx.compose.ui.input.nestedscroll.NestedScrollSource.UserInput || available.y <= 0f) return androidx.compose.ui.geometry.Offset.Zero
        if (enabled && !swallowing) { swallowing = true; onReveal() }
        return if (swallowing) available else androidx.compose.ui.geometry.Offset.Zero
    }

    override suspend fun onPreFling(available: androidx.compose.ui.unit.Velocity): androidx.compose.ui.unit.Velocity {
        if (!swallowing) return androidx.compose.ui.unit.Velocity.Zero
        swallowing = false
        return available
    }
}

private const val RECS_COUNT = 10

/** The suggestions under an own playlist: tap to listen, "+" to add, "Refresh" for others. */
private fun androidx.compose.foundation.lazy.LazyListScope.recommendedItems(vm: ContextViewModel, p: ContextPageModel, context: android.content.Context, currentSongId: Int) {
    item(key = "recs-title") {
        LaunchedEffect(p.uri) { vm.loadRecs(p) }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(16.dp, 28.dp, 8.dp, 4.dp)) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.recommended_songs), color = AppText, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text(stringResource(if (p.tracks.isEmpty()) R.string.recommended_songs_from_name else R.string.recommended_songs_from_songs),
                    color = AppMuted, fontSize = 13.sp)
            }
            androidx.compose.material3.TextButton(onClick = { vm.loadRecs(p, refresh = true) }) {
                Text(stringResource(R.string.recommended_songs_refresh), color = AppText, fontWeight = FontWeight.Bold)
            }
        }
    }
    val recs = vm.recs.value
    if (recs == null) item(key = "recs-loading") { com.music.spotui.ui.components.SkeletonList(count = 3) }
    else items(recs, key = { "rec-" + it.spotifyTrackId }) { song ->
        RecommendedRow(
            song,
            isCurrent = song.id == currentSongId,
            onPlay = { SongPlayer.playSong(song.url, context, songs = listOf(song)) },
            onAdd = {
                vm.takeRec(p, song)
                com.music.spotui.data.api.SpotifySync.addTrackToPlaylist(context, p.uri.substringAfterLast(':'), song.spotifyTrackId) { ok ->
                    if (ok) vm.reload()
                    else android.widget.Toast.makeText(context, context.getString(R.string.playlist_change_refused), android.widget.Toast.LENGTH_SHORT).show()
                }
            },
            modifier = Modifier.animateItem(),
        )
    }
}

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun RecommendedRow(song: SongsModel, isCurrent: Boolean, onPlay: () -> Unit, onAdd: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.fillMaxWidth().clickable(onClick = onPlay).padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
    ) {
        GlideImage(model = song.coverUri, contentDescription = null, contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            modifier = Modifier.size(48.dp).clip(RoundedCornerShape(4.dp)))
        Column(Modifier.weight(1f).padding(start = 12.dp, end = 8.dp)) {
            // The song playing is green, as in the playlist above.
            Text(song.title, color = if (isCurrent) SpotifyGreen else AppText, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(song.singer, color = AppMuted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        androidx.compose.material3.IconButton(onClick = onAdd) {
            Icon(androidx.compose.material.icons.Icons.Outlined.AddCircleOutline, contentDescription = stringResource(R.string.recommended_songs_add), tint = AppText)
        }
    }
}

/** The people of a collaborative playlist: owner first, then collaborators; a tap opens a profile. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun MembersSheet(
    members: List<app.myspoti.api.PlaylistMember>,
    navController: NavController,
    ownPlaylist: Boolean,
    me: String?,
    canLeave: Boolean,
    /** Owner: [collaborator] true/false changes the member's level, null removes the member. */
    onChange: (app.myspoti.api.PlaylistMember, Boolean?) -> Unit,
    onLeave: () -> Unit,
    onDismiss: () -> Unit,
) {
    com.music.spotui.ui.components.LookSheet(onDismissRequest = onDismiss, containerColor = tone(0xFF282828)) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
            Text(stringResource(R.string.playlist_members_title), color = AppText, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(20.dp, 0.dp, 20.dp, 12.dp))
            members.forEach { m ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().clickable {
                        onDismiss(); navController.navigate(com.music.spotui.ui.navigation.profileRoute(m.username, m.name))
                    }.padding(horizontal = 20.dp, vertical = 10.dp),
                ) {
                    com.music.spotui.ui.components.ProfileAvatar(m.imageUrl, m.name, 44.dp)
                    Column(Modifier.weight(1f).padding(start = 14.dp)) {
                        Text(m.name, color = AppText, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(stringResource(when {
                            m.isOwner -> R.string.playlist_member_owner
                            m.isCollaborator -> R.string.playlist_member_collab
                            else -> R.string.playlist_member_listener
                        }), color = AppMuted, fontSize = 13.sp)
                    }
                    // As the Web Player's "Shared with": the owner's menu per member, "Leave playlist" on a guest's own row.
                    val ownRow = canLeave && me != null && m.username == me
                    if (!m.isOwner && (ownPlaylist || ownRow)) {
                        var open by remember { mutableStateOf(false) }
                        Box {
                            Icon(Icons.Default.MoreVert, contentDescription = null, tint = AppText,
                                modifier = Modifier.size(40.dp).clip(CircleShape).clickable { open = true }.padding(8.dp))
                            androidx.compose.material3.DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = tone(0xFF282828)) {
                                @Composable fun item(label: Int, action: () -> Unit) = androidx.compose.material3.DropdownMenuItem(
                                    text = { Text(stringResource(label), color = AppText) },
                                    onClick = { open = false; action() })
                                if (ownPlaylist) {
                                    if (m.isCollaborator) item(R.string.playlist_member_remove_collab) { onChange(m, false) }
                                    else item(R.string.playlist_member_make_collab) { onChange(m, true) }
                                    item(R.string.playlist_member_remove) { onChange(m, null) }
                                } else item(R.string.playlist_leave) { onLeave() }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** How long a greyed track is given to start before the app says why it did not. */
private const val UNAVAILABLE_CHECK_MS = 4_000L

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun TrackRow(
    song: SongsModel,
    showCover: Boolean,
    isCurrent: Boolean,
    saved: Boolean,
    offline: com.music.spotui.data.offline.OfflineStatus?,
    onClick: () -> Unit,
    onMore: () -> Unit,
    addedBy: app.myspoti.api.PlaylistMember? = null,
) {
    // A greyed row is still tried (see the click): the tap is followed by a check, not a refusal.
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val appContext = androidx.compose.ui.platform.LocalContext.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (song.playable) Modifier else Modifier.alpha(0.4f))
            .clickable {
                onClick()
                // Spotify's playability flag is per market and can be wrong (testers heard greyed
                // "region" tracks play in the official app), so a greyed row is still tried. If the
                // player has not moved to it a few seconds later, the reason is said — a tap that
                // appears to do nothing is what makes the app look broken.
                if (!song.playable) scope.launch {
                    kotlinx.coroutines.delay(UNAVAILABLE_CHECK_MS)
                    val playing = SongPlayer.nowPlaying.value.uri
                    if (song.spotifyTrackId.isNotBlank() && !playing.endsWith(song.spotifyTrackId)) {
                        android.widget.Toast.makeText(
                            appContext,
                            app.myspoti.engine.R.string.track_unavailable_region,
                            android.widget.Toast.LENGTH_LONG,
                        ).show()
                    }
                }
            }
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        if (showCover) {
            GlideImage(
                transition = CrossFade,
                model = song.coverUri,
                contentDescription = "",
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(48.dp),
            )
            Spacer(Modifier.width(12.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                song.title,
                color = if (isCurrent) SpotifyGreen else AppText,
                fontSize = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                if (offline != null) {
                    com.music.spotui.ui.components.DownloadBadge(offline, size = 14.dp)
                    Spacer(Modifier.width(6.dp))
                }
                if (song.explicit) {
                    ExplicitBadge()
                    Spacer(Modifier.width(6.dp))
                }
                Text(song.singer, color = AppText.copy(alpha = 0.7f), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        // Collaborative playlist: who added this song.
        addedBy?.let { m ->
            com.music.spotui.ui.components.ProfileAvatar(m.imageUrl, m.name, 20.dp)
            Spacer(Modifier.width(14.dp))
        }
        if (saved) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(20.dp).clip(CircleShape).background(SpotifyGreen),
            ) {
                Icon(Icons.Default.Check, contentDescription = stringResource(R.string.liked_song), tint = Color.Black, modifier = Modifier.size(14.dp))
            }
            Spacer(Modifier.width(16.dp))
        }
        Icon(
            Icons.Default.MoreVert,
            contentDescription = stringResource(R.string.content_description_context_menu),
            tint = AppText.copy(alpha = 0.7f),
            modifier = Modifier.pressable(0.96f) { onMore() }
                .size(24.dp)
                ,
        )
    }
}

/** The Liked Songs artwork: purple-to-blue gradient with a white heart. */
@Composable
private fun LikedCover(modifier: Modifier) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF450AF5), Color(0xFF8E8EE5), Color(0xFFC4EFD9)))),
    ) {
        Icon(Icons.Default.Favorite, contentDescription = null, tint = AppText, modifier = Modifier.fillMaxWidth(0.4f).aspectRatio(1f))
    }
}


/** "Sort by" for the songs of a playlist or Liked Songs; [CUSTOM] is Spotify's own order. */
/** Smart shuffle, per playlist (uri), for this session. */
object SmartShuffle {
    const val EVERY = 3
    val on = androidx.compose.runtime.mutableStateOf<String?>(null)
    /** The recommendations mixed into the playing list. */
    val recommended = androidx.compose.runtime.mutableStateOf<Set<String>>(emptySet())

    private val keepScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)

    /** [trackId] was mixed in by smart shuffle: it is not in the playlist (yet). */
    fun isRecommendation(trackId: String): Boolean = on.value != null && trackId.isNotBlank() && trackId in recommended.value

    /**
     * The + of a recommendation, as in the official app: the song joins the playlist it was mixed
     * into (Liked Songs: it is liked), and is no longer marked as a recommendation.
     */
    fun keep(context: android.content.Context, song: SongsModel) {
        val list = on.value ?: return
        val id = song.spotifyTrackId
        recommended.value = recommended.value - id
        if (!list.startsWith("spotify:playlist:")) {
            if (SongPlayer.currentTrackId == id) SongPlayer.setLiked(true)
            else keepScope.launch { runCatching { MyspotiRuntime.api.library.add(listOf("spotify:track:$id")) } }
            android.widget.Toast.makeText(context, context.getString(R.string.snackbar_added_to_liked_songs), android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        com.music.spotui.data.api.SpotifySync.addTrackToPlaylist(context, list.substringAfterLast(':'), id) { ok ->
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                if (!ok) recommended.value = recommended.value + id
                val name = SongPlayer.knownContextName(list)
                android.widget.Toast.makeText(context,
                    if (ok) context.getString(R.string.local_added_to, name.ifBlank { context.getString(R.string.smart_shuffle_title) })
                    else context.getString(R.string.playlist_change_refused),
                    android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** Smart shuffle can play [contextUri]: a playlist or Liked Songs. */
    fun canPlay(contextUri: String): Boolean = contextUri.startsWith("spotify:playlist:") || contextUri.contains(":collection")

    /**
     * Smart shuffle (Spotify keeps its own for Premium): the playlist shuffled, with one of
     * Spotify's recommendations for it after every [EVERY] songs.
     */
    suspend fun start(p: ContextPageModel, first: SongsModel?, fromPositionMs: Long? = null) {
        val api = Graph.api
        val state = Graph.currentSongState
        on.value = p.uri
        val inList = p.tracks.map { it.spotifyTrackId }.toSet()
        val recs = withContext(Dispatchers.IO) {
            if (p.type == "liked") {
                // Spotify's playlist extender refuses Liked Songs: the radios of a few liked songs instead.
                p.tracks.shuffled().take(3).flatMap { api.getRecommendations(listOf(it.spotifyTrackId)).take(8) }
            } else runCatching { MyspotiRuntime.api.playlists.recommended(p.uri, limit = 20) }.getOrDefault(emptyList()).map(api::songOf)
                // Spotify's extender gives nothing for some playlists: the radios of a few of its songs.
                .ifEmpty { p.tracks.shuffled().take(3).flatMap { runCatching { api.getRecommendations(listOf(it.spotifyTrackId)).take(8) }.getOrDefault(emptyList()) } }
        }.filter { it.spotifyTrackId !in inList }.distinctBy { it.spotifyTrackId }.shuffled()
        val shuffled = p.tracks.shuffled().let { list -> first?.let { f -> listOf(f) + list.filter { it.spotifyTrackId != f.spotifyTrackId } } ?: list }
        if (shuffled.isEmpty()) return
        val mixed = ArrayList<SongsModel>()
        val more = recs.iterator()
        shuffled.forEachIndexed { i, s ->
            mixed += s
            if ((i + 1) % EVERY == 0 && more.hasNext()) mixed += more.next()
        }
        recommended.value = recs.map { it.spotifyTrackId }.toSet()
        // Spotify's shuffle would mix the given order again.
        SongPlayer.setShuffle(false); state.shuffle.value = false
        state.updateQueue(mixed)
        val startSong = mixed.first()
        // Turned on from the player: the song playing goes on where it is, not from its start.
        val position = fromPositionMs?.takeIf { first != null && it > 0 }
        // The song playing going on where it is stays on Spotify; a new play asks the audio-source extensions first.
        SongPlayer.playSong(startSong.url, contextUri = p.uri, order = mixed.map { "spotify:track:${it.spotifyTrackId}" }, positionMs = position,
            songs = mixed, askExtensions = position == null)
        state.updateSongState(startSong.coverUri, startSong.title, startSong.singer, true, startSong.id, 0, startSong.album)
    }

    /** From the player: loads the playing list, then smart-shuffles it, the current song first. */
    suspend fun startFor(contextUri: String, currentTrackId: String): Boolean {
        val api = Graph.api
        val flow = when {
            contextUri.contains(":collection") -> api.getLikedPage()
            contextUri.startsWith("spotify:playlist:") -> api.getPlaylistPage(contextUri.substringAfterLast(':'))
            else -> return false
        }
        val page = (withContext(Dispatchers.IO) { flow.first { it !is Response.Loading } } as? Response.Success)?.data ?: return false
        // Reported: turning it on from the player skipped the song. A long list's page holds only
        // its first tracks, so the song playing was often not found and a random one started
        // instead; the song playing now always stays first.
        val current = page.tracks.firstOrNull { it.spotifyTrackId == currentTrackId }
            ?: SongPlayer.engineQueue.value.current?.takeIf { it.spotifyTrackId == currentTrackId }
        start(page, current, SongPlayer.getCurrentPosition())
        return true
    }
}

/** Text order for sorting (accents ignored): one collator per thread, none is thread-safe. */
private val textOrder = ThreadLocal.withInitial {
    java.text.Collator.getInstance().apply { strength = java.text.Collator.PRIMARY }
}

enum class TrackSort(val label: Int) {
    CUSTOM(R.string.your_library_sort_order_custom),
    TITLE(R.string.track_sort_title),
    ARTIST(R.string.track_sort_artist),
    ALBUM(R.string.track_sort_album),
    DURATION(R.string.track_sort_duration),
    ADDED(R.string.track_sort_added);

    internal fun natural(songs: List<SongsModel>): List<SongsModel> {
        // One collator per thread: sorting runs at every sort change, not at every recomposition.
        val text = textOrder.get()
        return when (this) {
            CUSTOM -> songs
            TITLE -> songs.sortedWith { a, b -> text.compare(a.title, b.title) }
            ARTIST -> songs.sortedWith { a, b -> text.compare(a.singer, b.singer).takeIf { it != 0 } ?: text.compare(a.album, b.album) }
            ALBUM -> songs.sortedWith { a, b -> text.compare(a.album, b.album).takeIf { it != 0 } ?: text.compare(a.title, b.title) }
            DURATION -> songs.sortedBy { it.durationMs }
            // Newest first; ISO 8601 dates sort as text.
            ADDED -> songs.sortedByDescending { it.addedAt }
        }
    }
}

/**
 * A sort and its direction. [reversed] flips the sort's natural order (A to Z, shortest
 * first, newest first for date added); for [TrackSort.CUSTOM], the playlist from its end.
 */
data class TrackOrder(val sort: TrackSort = TrackSort.CUSTOM, val reversed: Boolean = false) {
    val isCustom get() = sort == TrackSort.CUSTOM && !reversed

    fun apply(songs: List<SongsModel>): List<SongsModel> = sort.natural(songs).let { if (reversed) it.asReversed() else it }

    companion object {
        private const val PREFS = "track_sort"

        fun load(context: android.content.Context, uri: String): TrackOrder {
            val v = context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).getString(uri, null) ?: return TrackOrder()
            val sort = TrackSort.entries.firstOrNull { it.name == v.substringBefore(':') } ?: return TrackOrder()
            return TrackOrder(sort, v.endsWith(":reversed"))
        }

        fun save(context: android.content.Context, uri: String, order: TrackOrder) {
            context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).edit().apply {
                if (order == TrackOrder()) remove(uri) else putString(uri, order.sort.name + if (order.reversed) ":reversed" else "")
            }.apply()
        }
    }
}

/** Spotify's sort menu: picking the sort already chosen flips its direction (the arrow shows it). */
@Composable
private fun TrackSortButton(sort: TrackOrder, onSort: (TrackOrder) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Icon(androidx.compose.material.icons.Icons.Default.SwapVert,
            contentDescription = stringResource(R.string.filter_and_sort_sort_section_title),
            tint = if (sort.isCustom) AppText.copy(alpha = 0.8f) else SpotifyGreen,
            modifier = Modifier.size(26.dp).pressable(0.9f) { open = true })
        androidx.compose.material3.DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = tone(0xFF282828)) {
            Text(stringResource(R.string.filter_and_sort_sort_section_title), color = AppMuted, fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
            TrackSort.entries.forEach { option ->
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text(stringResource(option.label), color = if (option == sort.sort) SpotifyGreen else AppText) },
                    trailingIcon = if (option == sort.sort) { {
                        Icon(if (sort.reversed) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward,
                            contentDescription = null, tint = SpotifyGreen, modifier = Modifier.size(18.dp))
                    } } else null,
                    onClick = { open = false; onSort(if (option == sort.sort) sort.copy(reversed = !sort.reversed) else TrackOrder(option)) },
                )
            }
        }
    }
}


/** "More like this" under an album: Spotify's similar albums, from its first song. */
@OptIn(com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi::class)
@Composable
private fun SimilarAlbums(navController: NavController, firstTrackId: String, albumUri: String) {
    val albums by androidx.compose.runtime.produceState(emptyList<app.myspoti.api.Album>(), firstTrackId) {
        if (firstTrackId.isBlank()) return@produceState
        value = runCatching { app.myspoti.host.android.MyspotiRuntime.api.catalog.similarAlbums("spotify:track:$firstTrackId", 12) }
            .getOrDefault(emptyList()).filter { it.uri != albumUri }
    }
    if (albums.isEmpty()) return
    Column(Modifier.padding(top = 24.dp)) {
        Text(stringResource(R.string.more_like_this), color = AppText, fontSize = 20.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        androidx.compose.foundation.lazy.LazyRow(
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(albums.size) { i ->
                val a = albums[i]
                val artists = a.artists.joinToString(", ") { it.name }
                Column(Modifier.width(140.dp).pressable(0.96f) {
                    navController.navigate(com.music.spotui.ui.navigation.albumRoute(a.name, artists, a.uri.substringAfterLast(':')))
                }) {
                    GlideImage(transition = CrossFade, model = a.images.maxByOrNull { it.width ?: 0 }?.url, contentDescription = null, contentScale = ContentScale.Crop,
                        modifier = Modifier.size(140.dp).clip(RoundedCornerShape(4.dp)))
                    Text(a.name, color = AppText, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                    Text(artists, color = AppMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
