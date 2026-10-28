package com.music.spotui.di

import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import androidx.compose.runtime.mutableStateOf
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.music.spotui.data.local.LocalTrack
import com.music.spotui.data.preferences.CrossfadePref
import com.music.spotui.cast.CastPlayback

/**
 * Plays the device's own audio files (Local Files). While it plays, the Spotify engine is
 * paused and SongPlayer routes the controls here (player, mini player, notification);
 * playing anything from Spotify again hands back to the engine.
 */
object LocalPlayer {
    /** Local playback is the current source. */
    val active = mutableStateOf(false)
    /** The play order, kept in step with the player's items (shuffle reorders it, see [setShuffle]). */
    private val tracks = mutableListOf<LocalTrack>()
    /** The order before shuffle, to go back to. */
    private var unshuffled: List<LocalTrack>? = null
    /**
     * The songs the user queued ("Play next", "Add to queue"), kept right after the current one
     * as Spotify's queue is (see [LocalQueueOrder]); a song leaves it when it starts.
     */
    private val queued: MutableSet<LocalTrack> = java.util.Collections.newSetFromMap(java.util.IdentityHashMap())
    private val isQueued: (LocalTrack) -> Boolean = { it in queued }
    /** The queued song playing now (the player then says it plays from the queue). */
    private var playingQueued: LocalTrack? = null
    /** Where the queue comes from (Local Files, Downloaded…). */
    private var listName: String = ""
    /** The songs of the song radio added after the list (see continueIfLast), and its name. */
    private val radioSongs: MutableSet<LocalTrack> = java.util.Collections.newSetFromMap(java.util.IdentityHashMap())
    private var radioName: String = ""
    /** Where the song playing comes from, shown in the player: the list, or the song radio that goes on after it. */
    val contextName: String get() = if (current()?.let { it in radioSongs } == true) radioName else listName
    /** The Spotify list the queue was taken from ("" for Local Files and the Downloaded list, and in its song radio). */
    val contextUri: String get() = if (current()?.let { it in radioSongs } == true) "" else listUri
    private var listUri: String = ""
    private var exo: ExoPlayer? = null
    private val errors = StreamErrors()

    private val audioAttributes = AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build()

    /**
     * An extension mirrors the song in the Web Player, unheard: that page asks Android for the audio
     * focus too, and took it from this player (it paused 3 s into every mirror, which ended the
     * mirror). While the mirror lasts this player keeps playing whoever asks; calls and other apps
     * pause it again when it ends.
     */
    @Volatile private var mirrorActive = false
    fun setMirrorFocus(on: Boolean) {
        mirrorActive = on
        android.os.Handler(android.os.Looper.getMainLooper()).post { exo?.setAudioAttributes(audioAttributes, !on) }
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun build(main: Boolean): ExoPlayer = ExoPlayer.Builder(Graph.app)
        // The fading-out copy of a crossfade owns neither the audio focus nor the noisy-audio pause:
        // the main player has them and the fade follows its play state.
        .setAudioAttributes(audioAttributes, main && !mirrorActive)
        .setHandleAudioBecomingNoisy(main)
        // Downloaded songs are encrypted files: the player reads them through the decrypting source.
        .setMediaSourceFactory(androidx.media3.exoplayer.source.DefaultMediaSourceFactory(
            com.music.spotui.data.offline.OfflineCrypto.Factory(Graph.app)).setLoadErrorHandlingPolicy(NoAudioNotRetried))
        .build()

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun player(): ExoPlayer = exo ?: build(true).also { p ->
            p.addListener(object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) = publish()

                // Each change of what the player does, one line each, so a song that never starts says why.
                override fun onPlaybackStateChanged(state: Int) {
                    log("state ${stateName(state)} at ${p.currentPosition} ms, item ${p.currentMediaItemIndex + 1}/${p.mediaItemCount}")
                    // Casting: this player keeps the queue only, stopped; the song plays on the device.
                    if (CastPlayback.active && state != Player.STATE_IDLE) { p.stop(); return }
                    if (state == Player.STATE_READY) checkLength(p)
                    if (state == Player.STATE_ENDED && !playAgain(p)) atEnd(p)
                }
                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                    log("playWhenReady $playWhenReady (${PLAY_WHEN_READY_REASONS.getOrElse(reason - 1) { "reason $reason" }})")
                    // A song on repeat paused at its end (see LocalRepeat): it starts again at 0:00.
                    if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM && playAgain(p)) return
                    // A pause (or a lost focus) during a fade stops the fading-out song too.
                    outgoing?.playWhenReady = playWhenReady
                    if (playWhenReady && CastPlayback.active) { p.pause(); CastPlayback.play() }
                }
                // Moves the player makes by itself (not a seek, not a song's end): logged, so a song
                // jumping elsewhere shows where it went and why.
                override fun onPositionDiscontinuity(old: Player.PositionInfo, new: Player.PositionInfo, reason: Int) {
                    if (reason == Player.DISCONTINUITY_REASON_SEEK || reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION) return
                    log("moved by the player from ${old.positionMs} to ${new.positionMs} ms (reason $reason), item ${new.mediaItemIndex + 1}")
                }
                // The queue grew or shrank: a queue of one song repeated loops it as repeat one does.
                override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) = applyRepeat(p)
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    log("isPlaying $isPlaying at ${p.currentPosition} ms")
                    if (isPlaying) errors.played()
                    if (isPlaying) tick.run()
                }
                override fun onPlaybackSuppressionReasonChanged(reason: Int) =
                    log("suppressed: ${if (reason == Player.PLAYBACK_SUPPRESSION_REASON_NONE) "no" else "reason $reason"}")

                // The next song an extension streams is asked for as soon as this one starts: it then
                // starts at once instead of waiting for the extension when this one ends.
                // The last one starting asks what goes on after it (see continueIfLast).
                override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                    // Anything but the transition the fade itself makes (a skip, a tap in the queue) ends the fade.
                    if (!startingFade) endFade()
                    // A song to play again that the player went past by itself: put back, never followed.
                    if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && wentPastRepeat(p, p.previousMediaItemIndex)) return
                    if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO || reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) startsAtZero(p)
                    prefetchNext(p); continueIfLast(p)
                }

                // A song whose audio an extension did not give or that would not play (audio-source
                // hook) is never skipped silently: asked again once if the stream itself failed,
                // then Spotify plays it, where it was, and goes on; a queue that keeps failing stops.
                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    log("error ${error.errorCodeName}: ${generic(error.cause?.toString() ?: error.message.orEmpty())}")
                    val item = p.currentMediaItem?.localConfiguration?.uri ?: return
                    if (item.scheme != com.music.spotui.extensions.ExtensionAudio.SCHEME) return
                    val t = current() ?: return
                    val noAudio = com.music.spotui.extensions.AudioRoute.isNoAudio(error)
                    // No extension had it (the data source already asked twice): Spotify, no new ask.
                    val again = errors.alreadyRetried(item.toString())
                    if (!noAudio && !again) {
                        log("${t.title}: the stream failed, the extensions are asked again once")
                        val at = p.currentPosition
                        prefetch.launch {
                            runCatching { com.music.spotui.extensions.ExtensionAudio.resolve(item, again = true) }
                            handler.post { if (exo === p && p.currentMediaItem?.localConfiguration?.uri == item) { p.prepare(); p.seekTo(at); p.play() } }
                        }
                        return
                    }
                    if (SongPlayer.playUnavailableOnSpotify(t, p.currentPosition)) return
                    when (errors.failed(item.toString(), p.hasNextMediaItem())) {
                        StreamErrors.Step.SKIP -> {
                            log("skipped ${t.title}: no extension had it and Spotify cannot play it now")
                            android.widget.Toast.makeText(Graph.app, Graph.app.getString(app.myspoti.engine.R.string.song_unavailable_skipped, t.title), android.widget.Toast.LENGTH_SHORT).show()
                            p.seekToNextMediaItem()
                            p.prepare()
                            p.play()
                        }
                        else -> {
                            log("stopped on ${t.title}: it cannot be played, and the songs after it failed too or none follows")
                            android.widget.Toast.makeText(Graph.app, Graph.app.getString(app.myspoti.engine.R.string.song_unavailable, t.title), android.widget.Toast.LENGTH_LONG).show()
                        }
                    }
                }
            })
            exo = p
            // A new local session takes the repeat the player shows (it started off).
            setRepeatMode(SongPlayer.repeatMode.value)
        }

    /**
     * The player's load errors: a song no extension gave usable audio for fails at once (each
     * retry would ask the extensions again, five asks in two seconds were seen); others as usual.
     */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private object NoAudioNotRetried : androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy() {
        override fun getRetryDelayMsFor(info: androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.LoadErrorInfo): Long =
            if (com.music.spotui.extensions.AudioRoute.isNoAudio(info.exception)) C.TIME_UNSET else super.getRetryDelayMsFor(info)
    }

    private val prefetch = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)

    private var ahead: kotlinx.coroutines.Job? = null

    /**
     * Resolves the next item's audio ahead (ExtensionAudio keeps the answer for its data source),
     * and reads its start: the first bytes of a url (StreamHead), the index and first segment of a
     * DASH answer, so a skip starts at once. Off a metered network only, apart from the answer
     * and the DASH index; the next song changing abandons it.
     */
    private fun prefetchNext(p: ExoPlayer) {
        ahead?.cancel()
        val i = p.nextMediaItemIndex
        if (i == C.INDEX_UNSET) return
        val uri = p.getMediaItemAt(i).localConfiguration?.uri ?: return
        if (uri.scheme != com.music.spotui.extensions.ExtensionAudio.SCHEME) return
        ahead = prefetch.launch {
            // Not at once: a song skipped in its first seconds would have cost its source an ask
            // for nothing, and sources limit how often they are asked (the song is asked anyway
            // when it starts).
            kotlinx.coroutines.delay(AHEAD_DELAY_MS)
            runCatching {
                val s = com.music.spotui.extensions.ExtensionAudio.resolve(uri, ahead = true) ?: return@launch
                val cheap = !com.music.spotui.data.DataSaver.metered
                if (s.manifest != null) {
                    val live = com.music.spotui.data.offline.DashLive.open(s.manifest!!, s.headers, com.music.spotui.data.offline.OfflineDownloader.DownloadUa)
                    if (cheap && isActive) live.warm()
                } else if (cheap && isActive) {
                    com.music.spotui.data.offline.StreamHead.fetch(uri.lastPathSegment.orEmpty(), s.url, s.headers,
                        System.currentTimeMillis() + s.keepMs(HEAD_KEEP_MS)) { isActive }
                }
            }
        }
    }

    private const val AHEAD_DELAY_MS = 10_000L

    /**
     * The audio-source extensions changed (see SongPlayer.audioSourcesChanged): the next song is
     * asked again ahead, and a song the queue stopped on for want of a source is tried again.
     */
    fun sourcesChanged() {
        val p = exo ?: return
        if (CastPlayback.active) return
        errors.played()
        val item = p.currentMediaItem?.localConfiguration?.uri
        if (p.playerError != null && item?.scheme == com.music.spotui.extensions.ExtensionAudio.SCHEME) {
            log("${current()?.title}: the audio sources changed, asked again")
            p.prepare(); p.play()
        }
        prefetchNext(p)
    }

    /** The most a song's start read ahead is kept (less when its address expires sooner). */
    private const val HEAD_KEEP_MS = 10 * 60_000L

    /**
     * [startMs]: where the song at [index] starts (a resume). [continues]: the extensions play the
     * queue, it goes on after its last song as Spotify's does (see continueIfLast).
     */
    fun play(list: List<LocalTrack>, index: Int, shuffle: Boolean = false, context: String? = null, contextUri: String = "", startMs: Long = 0L, continues: Boolean = false) {
        if (list.isEmpty()) return
        // Another song picked: repeat once was for the song left.
        val shown = SongPlayer.nowPlaying.value.uri.substringAfterLast(':')
        val picked = list.getOrNull(index.coerceIn(0, list.lastIndex))?.let(::spotifyId).orEmpty()
        if (SongPlayer.repeatMode.value == LocalRepeat.ONE && (picked.isBlank() || picked != shown)) {
            log("repeat once: cancelled (another song picked), back to off")
            SongPlayer.setRepeatMode(LocalRepeat.OFF)
        }
        SongPlayer.pauseEngineForLocal()
        forgetRadio(); this.continues = continues
        tracks.clear(); tracks.addAll(list); unshuffled = null
        queued.clear(); playingQueued = null; radioSongs.clear()
        listName = context ?: Graph.app.getString(app.myspoti.engine.R.string.local_files_title)
        listUri = contextUri
        active.value = true
        endFade()
        val p = player()
        p.shuffleModeEnabled = false
        // A new session takes the repeat the player shows (changed on a Spotify song meanwhile).
        replaying = null
        repeat = SongPlayer.repeatMode.value
        applyRepeat(p)
        p.setMediaItems(list.map(::item), index.coerceIn(0, list.lastIndex), startMs.coerceAtLeast(0L))
        p.prepare()
        if (shuffle) setShuffle(true)
        p.play()
        continueIfLast(p)
    }

    /** Back to Spotify: stop and forget the local queue. */
    fun stop() {
        if (!active.value) return
        CastPlayback.stop(back = false)
        endFade()
        active.value = false
        // A page left muted by an extension's mirror must not stay silent once Spotify plays again.
        SongPlayer.unmuteMirror()
        forgetRadio(); continues = false
        exo?.let { it.stop(); it.clearMediaItems() }
        tracks.clear(); unshuffled = null
        queued.clear(); playingQueued = null; radioSongs.clear()
        SongPlayer.restoreEngineQueue()
    }


    // ── Crossfade (real overlap: every song of this player is a local-player one) ──
    // Near the end of a song, a second player takes over its last seconds (fading out) while the
    // main player moves on to the next item at once (fading in). The main player thus sees an
    // ordinary track change: session, notification, queue and play counting are untouched.
    private var outgoing: ExoPlayer? = null
    private var fadeMs = 0L
    private var startingFade = false
    private var preparingFade = false
    /** The item index whose end must not fade (the user sought into its tail, or the fade failed). */
    private var noFadeFor = -1
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    private val tick: Runnable = object : Runnable {
        override fun run() {
            handler.removeCallbacks(this)
            val p = exo ?: return
            if (outgoing != null) {
                val t = CrossfadeRamp.progress(p.currentPosition, fadeMs)
                p.volume = CrossfadeRamp.incomingGain(t)
                outgoing?.volume = CrossfadeRamp.outgoingGain(t)
                if (t >= 1f) endFade() else handler.postDelayed(this, 40)
                return
            }
            if (!p.isPlaying) return
            if (!preparingFade && p.currentMediaItemIndex != noFadeFor && canFade(p) &&
                CrossfadeRamp.shouldStart(CrossfadePref.seconds, p.duration.takeIf { it > 0 } ?: 0L, p.currentPosition)) beginFade(p)
            handler.postDelayed(this, 200)
        }
    }

    private fun canFade(p: ExoPlayer): Boolean =
        CrossfadePref.seconds > 0 && !LocalRepeat.loopsSong(repeat, p.mediaItemCount) && p.nextMediaItemIndex != C.INDEX_UNSET &&
            p.currentMediaItem != null && p.playWhenReady && !CastPlayback.active

    private fun beginFade(p: ExoPlayer) {
        val item = p.currentMediaItem ?: return
        val index = p.currentMediaItemIndex
        val remaining = (p.duration - p.currentPosition).coerceAtLeast(0)
        val fade = minOf(CrossfadeRamp.fadeMs(CrossfadePref.seconds, p.duration), remaining)
        if (fade < 500) return
        preparingFade = true
        val out = build(false)
        out.volume = 1f
        // The copy starts a little ahead of the main player: it is heard once it is ready.
        out.setMediaItem(item, p.currentPosition + 300)
        out.addListener(object : Player.Listener {
            private var aligned = false
            override fun onPlaybackStateChanged(state: Int) {
                if (state != Player.STATE_READY || exo == null) return
                val main = exo ?: return
                // Ready later than its lead (an extension's stream opened at an offset can take a
                // second): started there, the copy would play again what was just heard. Moved
                // once to where the main player is, inside what it has loaded, then started.
                if (!aligned && main.currentMediaItemIndex == index && main.currentPosition + 50 > out.currentPosition) {
                    aligned = true
                    out.seekTo(main.currentPosition + 100)
                    return
                }
                out.removeListener(this)
                preparingFade = false
                if (!active.value || main.currentMediaItemIndex != index || !main.playWhenReady || !canFade(main)) return out.release()
                // What is left of the song now, not when the copy was asked for: a longer fade would
                // run past the copy's end, the next song not yet at full volume.
                val length = minOf(fade, (main.duration - main.currentPosition).coerceAtLeast(0))
                if (length < 500) { noFadeFor = index; return out.release() }
                outgoing = out; fadeMs = length
                out.play()
                startingFade = true
                main.volume = 0f
                main.seekToNextMediaItem()
                startingFade = false
                // After the switch, synchronously: the screens' state still shows the song fading out.
                SongPlayer.onLocalFade(true)
                log("crossfade $length ms")
                tick.run()
            }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                // Could not start the copy: this song ends as usual.
                preparingFade = false; noFadeFor = index; out.release()
            }
        })
        out.prepare()
        // Not ready in time: no fade for this song.
        handler.postDelayed({
            if (preparingFade && outgoing == null) { preparingFade = false; noFadeFor = index; out.release() }
        }, 1500)
    }

    /** Ends a fade (done, or interrupted by a skip/seek/stop): the copy goes, the main player is at full volume. */
    private fun endFade() {
        handler.removeCallbacks(tick)
        outgoing?.let { it.stop(); it.release() }
        if (outgoing != null) SongPlayer.onLocalFade(false)
        outgoing = null
        exo?.let { it.volume = 1f; if (it.isPlaying) handler.postDelayed(tick, 200) }
    }

    /**
     * How far the fade is, 0 (the song going out alone) to 1 (the next one alone); 1 with no fade.
     * Follows the incoming song's own position, as the volumes do: a stall holds it.
     */
    fun fadeProgress(): Float {
        val p = exo ?: return 1f
        return if (outgoing == null) 1f else CrossfadeRamp.progress(p.currentPosition, fadeMs)
    }
    /** Where the song fading out is (0 with no fade). */
    fun fadeOutPosition(): Long = outgoing?.currentPosition ?: 0L

    /** A player stopped by an error (idle) is prepared again first: a play alone does nothing there. */
    fun resume() {
        if (CastPlayback.active) return CastPlayback.play()
        val p = exo ?: return
        if (p.playbackState == Player.STATE_IDLE && p.mediaItemCount > 0) { log("resume after an error: preparing again"); p.prepare() }
        p.play()
    }
    fun pause() { if (CastPlayback.active) CastPlayback.pause() else exo?.pause() }
    fun next() {
        // In the first half of a crossfade the main player is on the next song already, while the
        // screens (and the ear) are still on the one going out: a skip there went two songs on.
        // It lands on the song coming in, at full volume.
        if (outgoing != null && fadeProgress() < 0.5f) {
            com.music.spotui.extensions.Extensions.skipAsked("forward")
            log("skip during the crossfade: the song coming in plays")
            return endFade()
        }
        endFade()
        cancelOnce("next")
        exo?.let {
            if (it.hasNextMediaItem()) { com.music.spotui.extensions.Extensions.skipAsked("forward"); it.seekToNextMediaItem() }
            // A queue of one song repeated (see LocalRepeat): Next plays it again, as the queue's wrap would.
            else if (repeat == LocalRepeat.ALL) { com.music.spotui.extensions.Extensions.skipAsked("forward"); it.seekTo(0, 0L) }
            else if (atEnd(it)) com.music.spotui.extensions.Extensions.skipAsked("forward")
        }
    }
    fun previous() {
        endFade()
        cancelOnce("previous")
        exo?.let {
            if (position() > 3000 || !it.hasPreviousMediaItem()) seekTo(0)
            else { com.music.spotui.extensions.Extensions.skipAsked("backward"); it.seekToPreviousMediaItem() }
        }
    }
    /** Back to the song before the current one, wherever this one is (a previous asked on another device). */
    fun previousItem() {
        endFade()
        cancelOnce("previous")
        exo?.let { if (it.hasPreviousMediaItem()) { com.music.spotui.extensions.Extensions.skipAsked("backward"); it.seekToPreviousMediaItem() } }
    }

    /** The Spotify ids of the songs before and after the current one ("" when none or no id). */
    fun neighbourIds(): Pair<String, String> {
        val i = exo?.currentMediaItemIndex ?: return "" to ""
        return spotifyId(tracks.getOrNull(i - 1)) to spotifyId(tracks.getOrNull(i + 1))
    }

    fun seekTo(ms: Long) {
        endFade()
        exo?.let { p ->
            // A seek into the tail of a song is the user's choice: that song then ends as usual.
            if (CrossfadeRamp.shouldStart(CrossfadePref.seconds, duration(), ms)) noFadeFor = p.currentMediaItemIndex
            if (CastPlayback.active) { CastPlayback.seek(ms); p.seekTo(ms); return }
            val known = knownLength()
            // To the song's end: it ended, and plays again as its repeat says (never the next song then).
            if (LocalRepeat.seekEnds(ms, known) && playAgain(p, seekAt = ms)) return
            // Within the length the player knows: Spotify's (or an extension's) is not the audio's.
            val to = LocalRepeat.seekTarget(ms, known)
            if (to != ms) log("seek to $ms ms kept within the audio's $known ms")
            p.seekTo(to)
        }
    }
    /**
     * Shuffle reorders the songs after the current one (and off puts the list's order back),
     * so the queue shown is the order that really plays and can be edited like any list.
     */
    fun setShuffle(on: Boolean) {
        val p = exo ?: return
        if (on == (unshuffled != null) || tracks.isEmpty()) return
        val cur = p.currentMediaItemIndex.coerceIn(0, tracks.lastIndex)
        if (on) {
            unshuffled = tracks.filterNot(isQueued)
            arrange(LocalQueueOrder.shuffled(tracks.toList(), cur, isQueued) { it.shuffled() })
        } else {
            val before = unshuffled.orEmpty()
            unshuffled = null
            arrange(LocalQueueOrder.unshuffled(tracks.toList(), cur, before, isQueued))
        }
    }

    val shuffled get() = unshuffled != null

    /** The songs after the current one, in play order, with their index in the list. */
    fun upcoming(): List<Pair<Int, LocalTrack>> {
        val i = exo?.currentMediaItemIndex ?: return emptyList()
        return (i + 1 until tracks.size).map { it to tracks[it] }
    }

    /** How many of [upcoming] the user queued (they come first). */
    fun queuedCount(): Int = exo?.let { LocalQueueOrder.queuedAfter(tracks, it.currentMediaItemIndex, isQueued) } ?: 0

    /** The current song is one the user queued ("Playing from the queue"). */
    fun fromQueue(): Boolean = playingQueued?.let { it === current() } == true

    /** A tap on the song at [index] of the queue: it plays at once; the songs queued before it stay queued. */
    fun skipTo(index: Int) {
        val p = exo ?: return
        if (index !in tracks.indices) return
        endFade()
        if (index != p.currentMediaItemIndex) cancelOnce("another song picked in the queue")
        val (order, at) = LocalQueueOrder.skip(tracks.toList(), p.currentMediaItemIndex, index, isQueued)
        if (order != tracks) arrange(order)
        p.seekTo(at, 0L); p.play()
    }

    /**
     * Moves the song at [from] before or [after] the one at [target], into [target]'s part of the
     * queue (queued, or the list's songs), as a drag in the queue does.
     */
    fun moveBeside(from: Int, target: Int, after: Boolean) {
        val p = exo ?: return
        val (order, toQueue) = LocalQueueOrder.moveBeside(tracks.toList(), p.currentMediaItemIndex, from, target, after, isQueued) ?: return
        val t = tracks[from]
        if (toQueue) queued += t else queued -= t
        arrange(order)
    }

    /** Moves the song at [from] into the queued songs: first ([target] null), else beside [target]. */
    fun moveIntoQueue(from: Int, target: Int?, after: Boolean) {
        val p = exo ?: return
        if (target != null) return moveBeside(from, target, after)
        val order = LocalQueueOrder.moveToFront(tracks.toList(), p.currentMediaItemIndex, from) ?: return
        queued += tracks[from]
        arrange(order)
    }

    /**
     * "Play next" / "Add to queue": [t] joins the queued songs, first ([next]) or after those
     * queued before it, ahead of the list's own next songs (as in Spotify's queue).
     */
    fun enqueue(t: LocalTrack, next: Boolean) {
        val p = exo ?: return
        val at = LocalQueueOrder.insertIndex(tracks, p.currentMediaItemIndex, next, isQueued)
        queued += t
        p.addMediaItem(at, item(t))
        tracks.add(at, t)
        publish()
    }

    /**
     * [enqueue] for a whole list, in the list's order: one insertion and one report (a long list
     * added song by song re-published the queue for each). Copies, so a song twice in [list] is two
     * queue rows ([queued] holds items by identity).
     */
    fun enqueueAll(list: List<LocalTrack>, next: Boolean) {
        val p = exo ?: return
        if (list.isEmpty()) return
        val songs = list.map { it.copy() }
        val at = LocalQueueOrder.insertIndex(tracks, p.currentMediaItemIndex, next, isQueued)
        queued += songs
        p.addMediaItems(at, songs.map(::item))
        tracks.addAll(at, songs)
        publish()
    }

    /** "Clear queue": the songs the user queued go; the list's own stay. */
    fun clearQueued() {
        val p = exo ?: return
        for (i in tracks.indices.reversed()) if (i > p.currentMediaItemIndex && tracks[i] in queued) {
            p.removeMediaItem(i); queued -= tracks.removeAt(i)
        }
        publish()
    }

    fun remove(index: Int) {
        val p = exo ?: return
        if (index !in tracks.indices || index == p.currentMediaItemIndex) return
        p.removeMediaItem(index)
        val t = tracks.removeAt(index)
        queued -= t
        unshuffled = unshuffled?.filterNot { it === t }
        publish()
    }

    /** Puts the player's items in [target]'s order with moves only, so the current song keeps playing. */
    private fun arrange(target: List<LocalTrack>) {
        val p = exo ?: return
        // The player reports each single move: the queue shown would flicker through half-moved orders.
        arranging = true
        try {
            for (k in target.indices) {
                val j = (k until tracks.size).firstOrNull { tracks[it] === target[k] } ?: continue
                if (j != k) { p.moveMediaItem(j, k); tracks.add(k, tracks.removeAt(j)) }
            }
        } finally { arranging = false }
        publish()
    }

    // ── After the last song ──
    /** The extensions play the queue: it goes on after its last song (files of the device never do). */
    private var continues = false
    /** The song whose song radio was asked, once per last song. */
    private var radioOf: String? = null
    private var radio: kotlinx.coroutines.Job? = null
    /** Its song radio gave nothing: Spotify goes on after it. */
    private var radioEmpty = false
    /** The song ended (or Next) while its song radio was asked: the radio plays as soon as it comes. */
    private var moveOnWhenReady = false

    private fun forgetRadio() { radio?.cancel(); radio = null; radioOf = null; radioEmpty = false; moveOnWhenReady = false }

    /** The Spotify id of a song an extension streams or of a downloaded one, "" for a file of the device. */
    private fun spotifyId(t: LocalTrack?): String = t?.let(::spotifyIdOf).orEmpty()

    /**
     * The last song of a queue the extensions play started: its song radio (what Spotify's autoplay
     * goes on with) is asked now, in the background, and added after it, each song asked to the
     * extensions when it plays as any other, so the queue goes on as Spotify's would.
     */
    private fun continueIfLast(p: ExoPlayer) {
        if (p.mediaItemCount == 0 || p.currentMediaItemIndex != p.mediaItemCount - 1) return
        val id = spotifyId(current())
        if (id == radioOf || !com.music.spotui.extensions.AudioRoute.continues(continues, id,
                repeatAll = repeat == LocalRepeat.ALL, autoplay = com.music.spotui.data.preferences.AutoplayPref.on)) return
        forgetRadio(); radioOf = id
        radio = prefetch.launch {
            val songs = runCatching { Graph.api.getRecommendations(listOf(id)) }.getOrDefault(emptyList())
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { radioCame(p, id, songs) }
        }
    }

    private fun radioCame(p: ExoPlayer, id: String, songs: List<com.music.spotui.data.entity.SongsModel>) {
        if (!active.value || radioOf != id) return
        // Repeat all tapped meanwhile: the queue plays again from its first song instead.
        if (repeat == LocalRepeat.ALL) { log("song radio not added: repeat all plays the queue again"); forgetRadio(); return }
        val more = com.music.spotui.extensions.AudioRoute.continuation(songs, tracks.map(::spotifyId))
        if (more.isEmpty()) {
            radioEmpty = true
            if (moveOnWhenReady) spotifyContinues()
            return
        }
        val added = more.map(com.music.spotui.extensions.ExtensionAudio::localTrack).let { if (shuffled) it.shuffled() else it }
        val from = tracks.size
        // Named after the song it goes on from, as Spotify's song radio is.
        radioName = Graph.app.getString(app.myspoti.engine.R.string.song_radio_name, tracks.lastOrNull { spotifyId(it) == id }?.title ?: current()?.title.orEmpty())
        radioSongs += added
        tracks.addAll(added)
        p.addMediaItems(added.map(::item))
        log("queue ended, continuing with ${added.size} songs (song radio)")
        if (moveOnWhenReady) { moveOnWhenReady = false; skipTo(from) } else prefetchNext(p)
        publish()
    }

    /**
     * No song after the current one (it ended, or Next): its song radio plays, now or when it
     * comes, or Spotify goes on when it had none. False: the queue just ends (see AudioRoute.continues).
     */
    private fun atEnd(p: ExoPlayer): Boolean {
        continueIfLast(p)
        if (radioOf == null || radioOf != spotifyId(current())) return false
        if (radioEmpty) spotifyContinues() else moveOnWhenReady = true
        return true
    }

    private fun spotifyContinues() {
        moveOnWhenReady = false
        val t = current() ?: return
        SongPlayer.continueOnSpotify(t, spotifyId(t))
    }

    private fun item(t: LocalTrack) = MediaItem.Builder().setUri(t.uri).setMediaId(t.id.toString())
        .setMediaMetadata(MediaMetadata.Builder().setTitle(t.title).setArtist(t.artist).setAlbumTitle(t.album)
            .setArtworkUri(android.net.Uri.parse(app.myspoti.host.android.Covers.large(t.artUri.toString()))).setDurationMs(t.durationMs.takeIf { it > 0 }).build()).build()
    /** SongPlayer.repeatMode as this player has it (0 off, 1 the queue, 2 the song; see LocalRepeat). */
    private var repeat = LocalRepeat.OFF

    fun setRepeatMode(mode: Int) {
        repeat = mode
        // Repeat once tapped again, or another mode: its replay to come is cancelled.
        if (mode != LocalRepeat.ONE) replaying = null
        exo?.let { applyRepeat(it); if (active.value) continueIfLast(it) }
    }

    /** The song whose one replay (repeat once) plays; null: none yet. */
    private var replaying: LocalTrack? = null

    /** The current song plays again at its end (repeat once still to replay it, a one-song queue repeated). */
    fun repeatsCurrent(): Boolean = exo?.let { p ->
        active.value && !CastPlayback.active && LocalRepeat.atEnd(repeat, p.mediaItemCount, replayedNow()) == LocalRepeat.End.REPLAY
    } == true

    private fun replayedNow(): Boolean = replaying != null && replaying === current()

    /** Manual moves (next, previous, a pick) cancel repeat once: it was for the song left. */
    private fun cancelOnce(why: String) {
        if (repeat != LocalRepeat.ONE) return
        log("repeat once: cancelled ($why), back to off")
        SongPlayer.setRepeatMode(LocalRepeat.OFF)
    }

    /** Repeat once played its song again: off now (the buttons show it), the queue goes on. */
    private fun onceDone() {
        log("repeat once: ${current()?.title} replayed, back to off")
        replaying = null
        SongPlayer.setRepeatMode(LocalRepeat.OFF)
    }

    /**
     * The player went on to the next song by itself ([left]: the item it left, the one before) while that one was
     * to play again: put back on it at 0:00 as its end would (the player's end must never decide
     * past a repeat). After repeat once's replay, going on is right: the mode goes off.
     */
    private fun wentPastRepeat(p: ExoPlayer, left: Int): Boolean {
        val t = tracks.getOrNull(left) ?: return false
        if (CastPlayback.active || !active.value || left == p.currentMediaItemIndex) return false
        when (LocalRepeat.atEnd(repeat, p.mediaItemCount, replaying === t)) {
            LocalRepeat.End.NEXT -> return false
            LocalRepeat.End.ONCE_DONE -> { log("repeat once: ${t.title} replayed, back to off"); replaying = null; SongPlayer.setRepeatMode(LocalRepeat.OFF); return false }
            LocalRepeat.End.REPLAY -> {}
        }
        log("the player went on to item ${p.currentMediaItemIndex + 1} by itself while ${t.title} is to play again: put back on it, not followed")
        replay(p, left, p.currentPosition)
        return true
    }

    /**
     * ExoPlayer repeats a queue of several songs itself; a song that follows itself (repeat one, a
     * queue of one song repeated) pauses at its end and is played again by [playAgain].
     */
    private fun applyRepeat(p: ExoPlayer) {
        p.repeatMode = LocalRepeat.playerMode(repeat, p.mediaItemCount)
        p.pauseAtEndOfMediaItems = LocalRepeat.loopsSong(repeat, p.mediaItemCount)
    }

    /**
     * A song on repeat that reached its end plays again from 0:00, and the Connect mirror's copy
     * plays it anew (a new play, as Spotify's repeat counts one). False: the song does not loop.
     */
    private fun playAgain(p: ExoPlayer, seekAt: Long? = null): Boolean {
        if (CastPlayback.active || !active.value) return false
        val end = LocalRepeat.atEnd(repeat, p.mediaItemCount, replayedNow())
        if (end == LocalRepeat.End.NEXT) return false
        // Already played again: the pause at the end and the end of the last song come together.
        if (seekAt == null && p.playWhenReady && p.playbackState != Player.STATE_ENDED) return true
        if (end == LocalRepeat.End.ONCE_DONE) {
            onceDone()
            // A seek to the end: the song ends there now, as with repeat off.
            if (seekAt != null) return false
            if (p.hasNextMediaItem()) { endFade(); p.seekToNextMediaItem(); p.play(); return true }
            // The last song: its end goes on as the queue's end (see atEnd), once the player is there.
            if (p.playbackState == Player.STATE_ENDED) return false
            p.play()
            return true
        }
        if (seekAt != null) log("seek to the end of ${current()?.title} ($seekAt ms): it ended, and plays again")
        replay(p, p.currentMediaItemIndex, seekAt ?: p.currentPosition)
        return true
    }

    /** The song at [index] plays again from 0:00, and the Connect mirror's copy plays it anew (a new play, as Spotify's repeat counts one). */
    private fun replay(p: ExoPlayer, index: Int, endedAt: Long) {
        endFade()
        if (repeat == LocalRepeat.ONE) replaying = tracks.getOrNull(index)
        p.seekTo(index, 0L)
        p.play()
        log("repeat: ${current()?.title} plays again from ${p.currentPosition} ms (it ended at $endedAt ms)")
        ConnectMirror.onLocalRepeat()
    }

    /**
     * A song the player moved to by itself (the next one, or the queue starting again on repeat)
     * starts at 0:00: put back there if the player put it elsewhere. The queue starting again is logged.
     */
    private fun startsAtZero(p: ExoPlayer) {
        val at = p.currentPosition
        if (p.currentMediaItemIndex == 0 && p.mediaItemCount > 1 && repeat == LocalRepeat.ALL)
            log("repeat: the queue plays again from ${current()?.title} at $at ms")
        if (!LocalRepeat.startedLate(at)) return
        log("${current()?.title} started at $at ms, not at its start: moved to 0 ms")
        p.seekTo(0L)
    }
    /** The Spotify id of [t]: a downloaded song's, or the song an extension streams; "" for the phone's own files. */
    fun spotifyIdOf(t: LocalTrack): String =
        com.music.spotui.data.offline.OfflineStore.byLocalId(t.id)?.trackId
            ?: t.uri.takeIf { it.scheme == com.music.spotui.extensions.ExtensionAudio.SCHEME }?.lastPathSegment.orEmpty()

    fun isPlaying() = if (CastPlayback.active) CastPlayback.isPlaying() else exo?.isPlaying == true
    /** Waiting for audio (a stream that does not keep up, a seek still loading). */
    fun isBuffering() = if (CastPlayback.active) CastPlayback.isBuffering() else exo?.playbackState == Player.STATE_BUFFERING
    /** For the diagnostic (`status`): whether it is to play, and its state (idle, buffering, ready, ended). */
    fun playWhenReady() = if (CastPlayback.active) CastPlayback.playWhenReady() else exo?.playWhenReady == true
    fun playbackState(): String = if (CastPlayback.active) CastPlayback.state() else exo?.let { stateName(it.playbackState) } ?: "none"
    fun position() = if (CastPlayback.active) CastPlayback.position() else exo?.currentPosition ?: 0L
    fun duration() = exo?.duration?.takeIf { it > 0 } ?: current()?.durationMs ?: 0L
    /** The length the player knows of the audio itself (null: not known), never Spotify's. */
    fun knownLength(): Long? = if (CastPlayback.active) null else exo?.duration?.takeIf { it != C.TIME_UNSET && it > 0 }
    fun current(): LocalTrack? = exo?.currentMediaItemIndex?.let { tracks.getOrNull(it) }

    /**
     * For the diagnostic: what plays (an extension and how it streams, a download, a file), whether
     * its source can seek (a source that cannot plays a seek from the start), its state, and its
     * length against the song's (Spotify's, for a stream or a download).
     */
    fun describe(): String {
        val t = current() ?: return "nothing"
        val p = exo ?: return "no player"
        val id = t.uri.lastPathSegment.orEmpty()
        val what = when {
            t.uri.scheme == com.music.spotui.extensions.ExtensionAudio.SCHEME ->
                "extension ${com.music.spotui.extensions.ExtensionAudio.providerOf(id) ?: "?"} (${com.music.spotui.extensions.ExtensionAudio.deliveryOf(id) ?: "not answered"})"
            com.music.spotui.data.offline.OfflineStore.byLocalId(t.id) != null -> "download"
            else -> "file"
        }
        val seek = when {
            CastPlayback.active -> "casting"
            p.isCurrentMediaItemSeekable -> "seekable"
            else -> "NOT seekable"
        }
        return "$what, $seek, ${playbackState()}, ${p.duration.takeIf { it > 0 } ?: "unknown"} ms long (song ${t.durationMs} ms)"
    }

    /** The song whose length was compared with its audio's (see [checkLength]). */
    private var lengthChecked: LocalTrack? = null

    /**
     * Once per song, when its audio is ready: an audio longer or shorter than the song (another
     * cut, an intro) puts synced lyrics, timed on Spotify's, off by the difference. Logged only.
     */
    private fun checkLength(p: ExoPlayer) {
        val t = current() ?: return
        if (lengthChecked === t) return
        lengthChecked = t
        // The three lengths, once: the audio's as the player knows it (what seeks and ends go by),
        // the one the extension announced, and Spotify's.
        val audio = p.duration.takeIf { it != C.TIME_UNSET && it > 0 }
        val announced = t.uri.takeIf { it.scheme == com.music.spotui.extensions.ExtensionAudio.SCHEME }?.lastPathSegment
            ?.let(com.music.spotui.extensions.ExtensionAudio::announcedLengthOf)
        log("${t.title}: lengths player ${audio ?: "unknown"} ms, extension ${announced?.let { "$it ms" } ?: "none"}, Spotify ${t.durationMs} ms" +
            if (p.isCurrentMediaItemSeekable) "" else ", NOT seekable")
        audio ?: return
        if (t.durationMs > 0 && kotlin.math.abs(audio - t.durationMs) > LENGTH_GAP_MS)
            log("${t.title}: the audio lasts $audio ms, the song ${t.durationMs} ms (synced lyrics may be off by the difference)")
    }

    private const val LENGTH_GAP_MS = 2_000L

    private fun log(line: String) = app.myspoti.host.android.Logbook.log("local: $line")

    /** An error's text without the addresses it may carry. */
    private fun generic(text: String) = text.replace(Regex("\\b\\w+://\\S+"), "<address>").take(200)

    private fun stateName(state: Int) = when (state) {
        Player.STATE_IDLE -> "idle"; Player.STATE_BUFFERING -> "buffering"
        Player.STATE_READY -> "ready"; Player.STATE_ENDED -> "ended"; else -> "state $state"
    }

    /** Player.PLAY_WHEN_READY_CHANGE_REASON_*, from 1. */
    private val PLAY_WHEN_READY_REASONS = listOf("asked", "audio focus lost", "audio becoming noisy", "remote", "end of item", "suppressed too long")

    // ── Cast (see CastPlayback): this player keeps the queue, the device plays ──

    /** A Cast device takes the songs: this player stops where it was and the device goes on from there. */
    internal fun castStarted() {
        val p = exo ?: return
        endFade()
        val at = p.currentPosition
        val playing = p.playWhenReady
        p.pause(); p.stop()
        log("casting from ${at / 1000} s")
        CastPlayback.follow(current(), at, playing)
        publish()
    }

    /** Back from the Cast device: this player goes on at [atMs], playing if the device was. */
    internal fun castEnded(atMs: Long, playing: Boolean) {
        val p = exo ?: return
        if (!active.value || p.mediaItemCount == 0) return
        log("back from casting at ${atMs / 1000} s")
        p.seekTo(atMs.coerceAtLeast(0))
        p.prepare()
        p.playWhenReady = playing
        publish()
    }

    /** The Cast device finished the song: the next one, as this player's own end would. */
    internal fun castFinished() {
        val p = exo ?: return
        val end = LocalRepeat.atEnd(repeat, p.mediaItemCount, replayedNow())
        if (end == LocalRepeat.End.ONCE_DONE) onceDone()
        when {
            end == LocalRepeat.End.REPLAY -> { if (repeat == LocalRepeat.ONE) replaying = current(); CastPlayback.replay() }
            p.hasNextMediaItem() -> {
                val before = current()
                p.seekToNextMediaItem()
                // A list of one song on repeat: the same song again.
                if (current() === before) CastPlayback.replay()
            }
            else -> atEnd(p)
        }
    }

    /** The Cast device's state changed (position, play/pause): the screens follow. */
    internal fun castChanged() = publish()

    private var arranging = false

    private fun publish() {
        if (!active.value || arranging) return
        val t = current() ?: return
        // A queued song that started (or was skipped past) leaves the queue: it plays from there now.
        if (t in queued) playingQueued = t
        exo?.currentMediaItemIndex?.let { cur -> for (i in 0..minOf(cur, tracks.lastIndex)) queued -= tracks[i] }
        if (CastPlayback.active) CastPlayback.follow(t, exo?.currentPosition ?: 0L)
        SongPlayer.onLocalState(t, playWhenReady(), position(), duration())
        SongPlayer.onLocalQueue(t, upcoming(), queuedCount())
    }
}
