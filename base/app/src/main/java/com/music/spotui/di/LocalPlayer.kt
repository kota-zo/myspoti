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
    private fun build(main: Boolean, deck: Boolean = false): ExoPlayer = ExoPlayer.Builder(Graph.app, if (deck) DeckRenderers else Renderers)
        // The fading-out copy of a crossfade owns neither the audio focus nor the noisy-audio pause:
        // the main player has them and the fade follows its play state.
        .setAudioAttributes(audioAttributes, main && !mirrorActive)
        .setHandleAudioBecomingNoisy(main)
        // Downloaded songs are encrypted files: the player reads them through the decrypting source.
        .setMediaSourceFactory(androidx.media3.exoplayer.source.DefaultMediaSourceFactory(
            com.music.spotui.data.offline.OfflineCrypto.Factory(Graph.app)).setLoadErrorHandlingPolicy(NoAudioNotRetried))
        .build()
        // The speed and the silence skipping of the settings (the fading-out copy too: both are heard).
        .also(::tune)

    /**
     * The default renderers, with the equalizer in the audio chain (each player its own, see
     * EqualizerProcessor), ahead of the silence skipping and the speed the sink adds after it.
     */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private open class AudioRenderers : androidx.media3.exoplayer.DefaultRenderersFactory(Graph.app) {
        override fun buildAudioSink(context: android.content.Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): androidx.media3.exoplayer.audio.AudioSink =
            androidx.media3.exoplayer.audio.DefaultAudioSink.Builder(context)
                .setEnableFloatOutput(enableFloatOutput)
                .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                .setAudioProcessors(arrayOf(EqualizerProcessor()))
                .build()
    }
    private object Renderers : AudioRenderers()

    /**
     * An AutoMix deck's renderers: its speed is the AudioTrack's own (set while it plays, at once and
     * without a break), not the sink's Sonic, which drains and starts over at each change (a click,
     * heard on some phones) and is heard only after the whole buffer.
     */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private object DeckRenderers : AudioRenderers() {
        init { setEnableAudioTrackPlaybackParams(true) }
    }

    private fun tune(p: ExoPlayer) {
        val pref = com.music.spotui.data.preferences.EqualizerPref
        if (p.playbackParameters.speed != pref.speed) p.playbackParameters = androidx.media3.common.PlaybackParameters(pref.speed)
        if (p.skipSilenceEnabled != pref.skipSilence) p.skipSilenceEnabled = pref.skipSilence
    }

    /** The speed or the silence skipping changed in the settings: the song playing (and a fade) takes it now. */
    fun applyPlayback() {
        exo?.let(::tune); outgoing?.let(::tune)
        // A deck in a mix takes its own speed again at the next step.
        mix?.lastSpeed = 0f
        log("speed ${rate()}×, skip silences ${if (com.music.spotui.data.preferences.EqualizerPref.skipSilence) "on" else "off"}")
    }

    /** The speed the local player plays at (the settings', 1 by default). */
    fun rate(): Float = com.music.spotui.data.preferences.EqualizerPref.speed

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun player(): ExoPlayer = exo ?: build(true).also { p ->
            mainListener = listenerFor(p).also(p::addListener)
            exo = p
            deckEq = if (com.music.spotui.automix.AutoMix.on) com.music.spotui.automix.DeckEq.attach(p.audioSessionId) else null
            // A new local session takes the repeat the player shows (it started off).
            setRepeatMode(SongPlayer.repeatMode.value)
        }

    /** The main player's listener: it moves to the deck that takes over at an AutoMix (see [takeOver]). */
    private var mainListener: Player.Listener? = null

    private fun listenerFor(p: ExoPlayer): Player.Listener = object : Player.Listener {
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
            if (!playWhenReady) mixPaused()
            if (playWhenReady && CastPlayback.active) { p.pause(); CastPlayback.play() }
        }
        // Moves the player makes by itself (not a seek, not a song's end): logged, so a song
        // jumping elsewhere shows where it went and why.
        override fun onPositionDiscontinuity(old: Player.PositionInfo, new: Player.PositionInfo, reason: Int) {
            if (reason == Player.DISCONTINUITY_REASON_SEEK) { mixMoved(); return }
            if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION) return
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
            planned = null; dropped = null
            // The song kept from fading out is left behind: the next one fades as usual.
            if (p.currentMediaItemIndex != noFadeFor) noFadeFor = -1
            // A song to play again that the player went past by itself: put back, never followed.
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && wentPastRepeat(p, p.previousMediaItemIndex)) return
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO || reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) startsAtZero(p)
            if (skipHidden(p, reason)) return
            prefetchNext(p); continueIfLast(p)
        }

        // A song whose audio an extension did not give or that would not play (audio-source
        // hook) is never skipped silently: asked again once if the stream itself failed,
        // then Spotify plays it, where it was, and goes on; a queue that keeps failing stops.
        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            log("error ${error.errorCodeName}: ${generic(error.cause?.toString() ?: error.message.orEmpty())}")
            val item = p.currentMediaItem?.localConfiguration?.uri ?: return
            if (item.scheme == com.music.spotui.extensions.ExtensionAudio.SCHEME) com.music.spotui.extensions.Extensions.noteAttempt(item.lastPathSegment) { it.copy(error = "${error.errorCodeName}: ${error.cause ?: error.message.orEmpty()}") }
            // A file that is gone (a download deleted while the queue played): never left stopped on it.
            if (item.scheme != com.music.spotui.extensions.ExtensionAudio.SCHEME && DeletedDownloads.isMissingFile(error.errorCode, error.cause)) return fileGone(p, item)
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

    /**
     * The song playing opened again where it is: its audio is read anew from the answer kept for it
     * (another source was picked for it in the player, see SongPlayer.switchSource). The queue, the
     * shuffle, the repeat and play or pause stay. False: nothing an extension streams plays here.
     */
    fun reopen(): Boolean {
        val p = exo ?: return false
        if (CastPlayback.active || !active.value) return false
        if (p.currentMediaItem?.localConfiguration?.uri?.scheme != com.music.spotui.extensions.ExtensionAudio.SCHEME) return false
        if (outgoing != null) endFade()
        val at = p.currentPosition
        log("${current()?.title}: another source picked, opened again at $at ms")
        errors.played()
        p.stop()
        p.seekTo(p.currentMediaItemIndex, at)
        p.prepare()
        return true
    }

    /** The downloads deleted during this session, by file: the song each one is (see [downloadsDeleted]). */
    private val deletedDownloads = HashMap<String, com.music.spotui.data.entity.SongsModel>()

    /**
     * Downloads were deleted ([gone]: the song of each file, see OfflineStore.remove, removeAll): the
     * songs of the queue that played from those files play from their source, as the list's songs
     * never downloaded do (the extensions, else Spotify), or are left out offline (see
     * [DeletedDownloads]). The song playing keeps its open file and ends as usual. On the main thread.
     */
    fun downloadsDeleted(gone: Map<String, com.music.spotui.data.entity.SongsModel>) {
        val p = exo ?: return
        if (!active.value || tracks.isEmpty()) return
        deletedDownloads.putAll(gone)
        val cur = p.currentMediaItemIndex
        val plan = DeletedDownloads.plan(tracks.map { t -> gone[t.uri.toString()]?.spotifyTrackId }, cur,
            com.music.spotui.data.offline.OfflineStore.isOnline(Graph.app))
        if (plan.all { it == DeletedDownloads.Fix.KEEP }) return
        var streamed = 0; var dropped = 0; var droppedBefore = 0
        for (i in plan.indices.reversed()) when (plan[i]) {
            DeletedDownloads.Fix.STREAM -> { swap(p, i, com.music.spotui.extensions.ExtensionAudio.localTrack(gone.getValue(tracks[i].uri.toString()))); streamed++ }
            DeletedDownloads.Fix.DROP -> {
                p.removeMediaItem(i)
                val t = tracks.removeAt(i)
                queued -= t; radioSongs -= t
                unshuffled = unshuffled?.filterNot { it === t }
                dropped++; if (i < cur) droppedBefore++
            }
            DeletedDownloads.Fix.KEEP -> Unit
        }
        if (noFadeFor >= 0) noFadeFor -= droppedBefore
        log("downloads deleted while playing: $streamed songs of the queue play from their source, $dropped left out (offline or no id); ${current()?.title} ends from its open file")
        prefetchNext(p); continueIfLast(p)
        publish()
    }

    /** Puts [t] at [index] of the queue in place of the song there (same place in the queue, the shuffle and the radio). */
    private fun swap(p: ExoPlayer, index: Int, t: LocalTrack) {
        val old = tracks[index]
        p.replaceMediaItem(index, item(t))
        tracks[index] = t
        if (queued.remove(old)) queued += t
        if (radioSongs.remove(old)) radioSongs += t
        if (playingQueued === old) playingQueued = t
        unshuffled = unshuffled?.map { if (it === old) t else it }
    }

    /**
     * The file of the song the player is on is gone ([uri]: a download deleted while the queue
     * played, or a file that left the phone): never left stopped there. A deleted download plays
     * from its source (see [downloadsDeleted]); any other file is skipped, the queue stopping only
     * when the songs after it fail too (see StreamErrors).
     */
    private fun fileGone(p: ExoPlayer, uri: android.net.Uri) {
        val i = p.currentMediaItemIndex
        val t = tracks.getOrNull(i) ?: return
        val song = deletedDownloads[uri.toString()]
            ?.takeIf { it.spotifyTrackId.isNotBlank() && com.music.spotui.data.offline.OfflineStore.isOnline(Graph.app) }
        if (song != null) {
            log("${t.title}: its download was deleted, it plays from its source")
            // From where it was (a seek into a song whose file went reopens it).
            val at = p.currentPosition
            swap(p, i, com.music.spotui.extensions.ExtensionAudio.localTrack(song))
            p.seekTo(i, at); p.prepare(); p.play()
            return
        }
        // No retry: a file that is not there will not be on a second open.
        errors.alreadyRetried(uri.toString())
        when (errors.failed(uri.toString(), p.hasNextMediaItem())) {
            StreamErrors.Step.SKIP -> {
                log("skipped ${t.title}: its file is gone")
                android.widget.Toast.makeText(Graph.app, Graph.app.getString(app.myspoti.engine.R.string.song_unavailable_skipped, t.title), android.widget.Toast.LENGTH_SHORT).show()
                p.seekToNextMediaItem(); p.prepare(); p.play()
            }
            else -> {
                log("stopped on ${t.title}: its file is gone, and the songs after it failed too or none follows")
                android.widget.Toast.makeText(Graph.app, Graph.app.getString(app.myspoti.engine.R.string.song_unavailable, t.title), android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    /** The most a song's start read ahead is kept (less when its address expires sooner). */
    private const val HEAD_KEEP_MS = 10 * 60_000L

    /**
     * [startMs]: where the song at [index] starts (a resume). [continues]: the extensions play the
     * queue, it goes on after its last song as Spotify's does (see continueIfLast). [paused]: the song
     * is put there paused (another source picked for it while it was paused), the user's play starts it.
     */
    fun play(list: List<LocalTrack>, index: Int, shuffle: Boolean = false, context: String? = null, contextUri: String = "", startMs: Long = 0L, continues: Boolean = false, paused: Boolean = false) {
        if (list.isEmpty()) return
        // Another song picked: repeat once was for the song left.
        val shown = SongPlayer.nowPlaying.value.uri.substringAfterLast(':')
        val picked = list.getOrNull(index.coerceIn(0, list.lastIndex))?.let(::spotifyId).orEmpty()
        if (SongPlayer.repeatMode.value == LocalRepeat.ONCE && (picked.isBlank() || picked != shown)) {
            log("repeat once: cancelled (another song picked), back to off")
            SongPlayer.setRepeatMode(LocalRepeat.OFF)
        }
        SongPlayer.pauseEngineForLocal()
        forgetRadio(); this.continues = continues
        // Spotify's queued songs (the account's: maybe queued on another device or install) play first.
        val (all, fromSpotify) = QueueSync.withSpotifyQueue(list, index.coerceIn(0, list.lastIndex))
        tracks.clear(); tracks.addAll(all); unshuffled = null
        // An index kept from fading belonged to the list before: this one fades from its first song.
        noFadeFor = -1
        queued.clear(); playingQueued = null; radioSongs.clear()
        queued += fromSpotify
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
        // Paused before it loads: a player left playing by a song before never starts this one.
        if (paused) p.pause()
        p.setMediaItems(all.map(::item), index.coerceIn(0, list.lastIndex), startMs.coerceAtLeast(0L))
        p.prepare()
        if (shuffle) setShuffle(true)
        if (!paused) p.play()
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
        forgetRadio(); continues = false; deletedDownloads.clear()
        exo?.let { it.stop(); it.clearMediaItems() }
        tracks.clear(); unshuffled = null
        queued.clear(); playingQueued = null; radioSongs.clear()
        SongPlayer.restoreEngineQueue()
    }


    // ── AutoMix (see com.music.spotui.automix.AutoMix) ──
    // Two decks, no song ever handed from one player to another: the next song is prepared on a
    // second player (the deck) at its entry point, started muted a little before the mix and brought
    // in step with the song playing by nudging its speed (a phase-locked loop on both positions).
    // When the mix starts the deck becomes the main player (queue, listener, audio focus: see
    // [takeOver]) and the old one plays out the mix as the crossfade's outgoing player, then stops.
    // Gains are the players' volumes, the bass swap and filters the decks' DynamicsProcessing: both
    // act on what is heard now. The deck's speed goes back to the song's own after the mix.

    enum class MixState { PREPARING, READY, PREROLL, MIXING, RESTORING }

    private class MixRun(
        var plan: app.myspoti.automix.MixPlan,
        val toIndex: Int,
        val toId: String,
        val deck: ExoPlayer,
        val eq: com.music.spotui.automix.DeckEq?,
        /** Where the deck starts (B's time, ms): the entry point less the muted run-up. */
        val startMs: Long,
        /** A mix for Next: started from A's next downbeat once the deck is ready (see [mixOnSkip]). */
        val skip: Boolean = false,
    ) {
        val createdAt = android.os.SystemClock.uptimeMillis()
        /** The run-up, in the outgoing song's ms: only as long as B has audio before its entry point. */
        val runUpA get() = (plan.entryB * 1000 - startMs) / plan.speedB
        var firstLocked = false
        var moves = 0
        var startedAt = 0L
        var lastTickAt = 0L
        /** The deck's position moved by the speed nudges since its start (ms of B). */
        var corrected = 0.0
        var state = MixState.PREPARING
        var a: ExoPlayer? = null
        var eqA: com.music.spotui.automix.DeckEq? = null
        var lastSpeed = 0f
        val errors = ArrayDeque<Double>()
        var locked = false
        var worstMs = 0.0
        var loggedAt = 0L
        /** Progress of the mix from the outgoing song's position. */
        fun progress(): Double = a?.let { ((it.currentPosition / 1000.0) - plan.exitA) / plan.length } ?: 1.0
    }

    /** The plan for the song playing → the next one, once both are analysed. */
    private class Planned(val fromIndex: Int, val toId: String, val plan: app.myspoti.automix.MixPlan)
    @Volatile private var planned: Planned? = null
    private var mix: MixRun? = null
    /** The main player's EQ (a deck's once it took over). */
    private var deckEq: com.music.spotui.automix.DeckEq? = null
    private var asking: Pair<Int, String>? = null
    /** The pair whose deck was dropped: no new deck for it. */
    private var dropped: Pair<Int, String>? = null

    private const val PREPARE_LEAD_MS = 15_000L
    /**
     * How long a deck takes from play to sound (ms), learned at each start (about a second in, once
     * the AudioTrack's timestamps hold) and kept: the deck is started that much earlier.
     */
    private var startLead = Graph.app.getSharedPreferences("myspoti_prefs", 0).getFloat("automix_start_lead", 120f).toDouble()
    private const val RUN_UP_MS = 2_500L

    /** One AutoMix step (every 200 ms, every 20 ms around a mix). False: no more ticks. */
    private fun mixTick(p: ExoPlayer): Boolean {
        val m = mix
        if (m != null && m.state >= MixState.MIXING) return mixing(m)
        if (!p.isPlaying) return false
        val next = p.nextMediaItemIndex
        val canMix = next != C.INDEX_UNSET && !LocalRepeat.loopsSong(repeat, p.mediaItemCount) && p.playWhenReady
        if (!canMix) { if (m != null) cancelMix("no next song"); return true }
        val nextId = p.getMediaItemAt(next).mediaId
        // The next song changed (queue edited): the deck prepared for another one goes.
        if (m != null && (m.toIndex != next || m.toId != nextId)) cancelMix("the next song changed")
        val pos = p.currentPosition
        val run = mix
        if (run == null) {
            val plan = planFor(p, next, nextId) ?: return true
            val exitMs = (plan.exitA * 1000).toLong()
            if (pos >= exitMs - RUN_UP_MS - PREPARE_LEAD_MS && pos < exitMs - RUN_UP_MS / 2 && dropped != next to nextId) prepareDeck(p, next, nextId, plan)
            return true
        }
        val exitMs = (run.plan.exitA * 1000).toLong()
        val startAt = exitMs - run.runUpA - startLead
        when (run.state) {
            MixState.PREPARING -> {
                // Too late for the deck: the song ends as usual (a skip that waited too long: the plain skip).
                if (run.skip && android.os.SystemClock.uptimeMillis() - run.createdAt > SKIP_WAIT_MS) {
                    cancelMix("not ready in time for the skip"); p.seekToNextMediaItem()
                } else if (!run.skip && pos > startAt) cancelMix("the next song was not ready in time")
            }
            MixState.READY -> {
                // A skip's deck ready later than its plan: the mix moves to A's next downbeat.
                if (run.skip && pos > startAt + 20) {
                    // Never more than twice: a Next always leaves the song within a few seconds.
                    if (++run.moves > 2) { cancelMix("the skip could not start"); p.seekToNextMediaItem(); return true }
                    run.plan = skipPlan(p, next, pos)
                    com.music.spotui.automix.AutoMix.journal("skip moved: ${run.plan}")
                } else if (pos >= startAt) startRunUp(run)
            }
            MixState.PREROLL -> {
                lock(run, p, preroll = true)
                if (pos >= exitMs) takeOver(run, p)
            }
            else -> Unit
        }
        return true
    }

    /** The plan for this pair: the analysed one, else (once the end is near) a fade on the songs' lengths. */
    private fun planFor(p: ExoPlayer, next: Int, nextId: String): app.myspoti.automix.MixPlan? {
        val cur = p.currentMediaItemIndex
        planned?.takeIf { it.fromIndex == cur && it.toId == nextId }?.let { return it.plan }
        val a = tracks.getOrNull(cur) ?: return null
        val b = tracks.getOrNull(next) ?: return null
        if (asking != cur to nextId) {
            asking = cur to nextId
            // The song after the next one too: a skip then finds both of its songs analysed.
            com.music.spotui.automix.AutoMix.request(listOfNotNull(a.uri, b.uri, tracks.getOrNull(next + 1)?.uri)) {}
            if (followsOnAlbum(a, b)) com.music.spotui.automix.AutoMix.journal("${a.title} → ${b.title}: next on the album, no mix")
        }
        // B right after A on their album: they run into each other as the album does (a skip still mixes).
        if (followsOnAlbum(a, b)) return null
        val ra = com.music.spotui.automix.AutoMix.analysis(a.uri); val rb = com.music.spotui.automix.AutoMix.analysis(b.uri)
        if (ra != null && rb != null) {
            val plan = com.music.spotui.automix.AutoMix.planFor(ra, rb)
            planned = Planned(cur, nextId, plan)
            com.music.spotui.automix.AutoMix.journal("plan ${a.title} → ${b.title}: $plan")
            return plan
        }
        // Not analysed yet with the end near: an equal-power fade of 6 s on the lengths.
        val dur = p.duration.takeIf { it > 0 } ?: return null
        if (dur - p.currentPosition > PREPARE_LEAD_MS + RUN_UP_MS + 1000) return null
        val len = minOf(6.0, dur / 4000.0)
        val plan = app.myspoti.automix.MixPlan(app.myspoti.automix.MixPlan.Style.FADE, dur / 1000.0 - len, 0.0, len, 1.0, 0, 0.5, 0.1, 1.0, 0.0, "fade: not analysed in time")
        planned = Planned(cur, nextId, plan)
        com.music.spotui.automix.AutoMix.journal("plan ${a.title} → ${b.title}: $plan")
        return plan
    }

    /**
     * [b] is the song after [a] on their album: by the files' track numbers (the next track, or the
     * first of the next disc); without numbers (streams, downloads), when the album itself is the
     * list and [b] follows [a] in its own order (the shuffle aside). Two songs of one album that do
     * not follow each other there are mixed as any others.
     */
    private fun followsOnAlbum(a: LocalTrack, b: LocalTrack): Boolean {
        val x = a.album.trim(); val y = b.album.trim()
        if (x.isEmpty() || x.equals("<unknown>", true) || !x.equals(y, ignoreCase = true)) return false
        if (a.trackNo > 0 && b.trackNo > 0) {
            val da = a.trackNo / 1000; val ta = a.trackNo % 1000; val db = b.trackNo / 1000; val tb = b.trackNo % 1000
            return (db == da && tb == ta + 1) || (db == da + 1 && tb == 1)
        }
        if (!listUri.startsWith("spotify:album:")) return false
        val order = unshuffled ?: tracks
        val i = order.indexOfFirst { it === a }
        return i >= 0 && order.getOrNull(i + 1) === b
    }

    private const val SKIP_WAIT_MS = 2500L
    /** How long a skip waits from the deck's readiness to the mix (the run-up). */
    private const val SKIP_RUN_UP_MS = 600L

    /** The skip mix from A's time [posMs] (ms), on what is analysed of both songs. */
    private fun skipPlan(p: ExoPlayer, next: Int, posMs: Long): app.myspoti.automix.MixPlan {
        val a = tracks.getOrNull(p.currentMediaItemIndex)?.let { com.music.spotui.automix.AutoMix.analysis(it.uri) }
        val b = tracks.getOrNull(next)?.let { com.music.spotui.automix.AutoMix.analysis(it.uri) }
        // A skip may stretch more than a natural mix (12 %): it is short, and B is back to its tempo soon after.
        return app.myspoti.automix.MixPlanner.planSkip(a, b, posMs / 1000.0, (SKIP_RUN_UP_MS + startLead) / 1000.0,
            app.myspoti.automix.MixPlanner.Settings(maxStretch = 0.12))
    }

    /**
     * Next with AutoMix on: the next song is prepared on the deck and mixed in from A's next
     * downbeat (a short beat-matched blend, a cut on the beat, or a quick fade). False: a plain skip
     * (nothing to mix into, casting, a mix already playing). A second Next skips at once.
     */
    private fun mixOnSkip(): Boolean {
        val p = exo ?: return false
        if (!com.music.spotui.automix.AutoMix.on || CastPlayback.active || !p.isPlaying || outgoing != null) return false
        mix?.let { if (it.skip || it.state >= MixState.PREROLL) return false }
        val next = p.nextMediaItemIndex
        if (next == C.INDEX_UNSET || LocalRepeat.loopsSong(repeat, p.mediaItemCount)) return false
        cancelMix("skip", again = true)
        val plan = skipPlan(p, next, p.currentPosition)
        com.music.spotui.automix.AutoMix.journal("skip: $plan")
        prepareDeck(p, next, p.getMediaItemAt(next).mediaId, plan, skip = true)
        handler.removeCallbacks(tick); handler.post(tick)
        return true
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun prepareDeck(p: ExoPlayer, next: Int, nextId: String, plan: app.myspoti.automix.MixPlan, skip: Boolean = false) {
        val t = tracks.getOrNull(next) ?: return
        val deck = build(false, deck = true)
        deck.volume = 0f
        deck.skipSilenceEnabled = false
        deck.playbackParameters = androidx.media3.common.PlaybackParameters((plan.speedB * rate()).toFloat())
        val runUpB = minOf(plan.entryB * 1000, (if (skip) SKIP_RUN_UP_MS else RUN_UP_MS) * plan.speedB)
        val start = (plan.entryB * 1000 - runUpB).toLong().coerceAtLeast(0L)
        val run = MixRun(plan, next, nextId, deck, com.music.spotui.automix.DeckEq.attach(deck.audioSessionId), start, skip)
        run.eq?.set(app.myspoti.automix.MixPlan.KILL, 0.0)
        run.lastSpeed = deck.playbackParameters.speed
        run.a = p; run.eqA = deckEq
        mix = run
        deck.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY && run.state == MixState.PREPARING) {
                    run.state = MixState.READY
                    com.music.spotui.automix.AutoMix.journal("deck ready on ${t.title} at $start ms")
                }
            }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                if (mix === run && run.state < MixState.MIXING) cancelMix("deck error ${error.errorCodeName}")
            }
        })
        deck.setMediaItem(item(t), start)
        deck.prepare()
        com.music.spotui.automix.AutoMix.journal("deck preparing ${t.title} from $start ms")
    }

    private fun startRunUp(run: MixRun) {
        run.state = MixState.PREROLL
        run.errors.clear(); run.locked = false
        run.deck.play()
        run.startedAt = android.os.SystemClock.uptimeMillis()
        com.music.spotui.automix.AutoMix.journal(String.format("run-up started (%.0f ms, start lead %.0f ms)", run.runUpA, startLead))
    }

    /**
     * Keeps the deck on its place in the mix: its position against where the outgoing song's says it
     * should be, corrected by a speed nudge (a seek when far off during the muted run-up).
     */
    private fun lock(run: MixRun, a: ExoPlayer, preroll: Boolean, quiet: Boolean = false) {
        val plan = run.plan
        if (!plan.synced && !preroll) return
        // A cut between tempos too far apart: B's first downbeat on A's is all there is to hold; after
        // it the two run apart anyway (A is gone within two beats), so B is left at its own pace.
        if (!preroll && plan.style == app.myspoti.automix.MixPlan.Style.CUT && plan.restoreSec == 0.0) return
        val d = run.deck
        if (!d.isPlaying) return
        val aPos = a.currentPosition; val bPos = d.currentPosition
        val target = plan.entryB * 1000 + (aPos - plan.exitA * 1000) * plan.speedB
        // Started well before its time (a pause and play moved the outgoing song back): wait for it again.
        // (Started early by the start delay on purpose: up to that much before its start is expected.)
        if (preroll && target < run.startMs - startLead * plan.speedB - 300) { d.pause(); d.seekTo(run.startMs); run.state = MixState.READY; run.errors.clear(); return }
        val err = bPos - target
        run.errors.addLast(err); while (run.errors.size > 3) run.errors.removeFirst()
        val e = run.errors.sorted()[run.errors.size / 2]
        val base = plan.speedB * rate()
        // A second after the start, the deck's distance says how late it sounded: the next starts that much earlier.
        val sinceStart = android.os.SystemClock.uptimeMillis() - run.startedAt
        if (!run.firstLocked && run.startedAt > 0 && sinceStart in 900..1600 && run.errors.size == 3) {
            run.firstLocked = true
            // The correction already made by the speed since the start counts too.
            startLead = (startLead - 0.6 * (e - run.corrected) / plan.speedB).coerceIn(0.0, 500.0)
            Graph.app.getSharedPreferences("myspoti_prefs", 0).edit().putFloat("automix_start_lead", startLead.toFloat()).apply()
        }
        if (preroll && kotlin.math.abs(e) > 400 && run.errors.size == 3) {
            d.seekTo((target + 120).toLong().coerceAtLeast(0L)); run.errors.clear(); return
        }
        run.locked = kotlin.math.abs(e) < 15
        if (!preroll) run.worstMs = maxOf(run.worstMs, kotlin.math.abs(e))
        // Muted, or still low in the mix: fast and strong; heard: gentle.
        val fast = preroll || quiet
        val dead = if (preroll) 2.0 else 6.0
        val horizon = if (fast) 300.0 else 1500.0
        val limit = if (preroll) 0.08 else if (quiet) 0.05 else 0.02
        val nudge = if (kotlin.math.abs(e) <= dead) 0.0 else (-e / horizon).coerceIn(-limit, limit)
        val speed = (base * (1 + nudge)).toFloat()
        // What the nudges moved the deck by so far (ms of B), for the start delay learnt above.
        if (run.lastTickAt > 0 && run.lastSpeed > 0) run.corrected += (run.lastSpeed / base - 1) * (android.os.SystemClock.uptimeMillis() - run.lastTickAt) * plan.speedB
        run.lastTickAt = android.os.SystemClock.uptimeMillis()
        if (kotlin.math.abs(speed - run.lastSpeed) > 0.0005f) {
            d.playbackParameters = androidx.media3.common.PlaybackParameters(speed)
            run.lastSpeed = speed
        }
        // Once a second in the journal: how far the deck is from its place.
        val now = android.os.SystemClock.uptimeMillis()
        if (now - run.loggedAt >= 1000) {
            run.loggedAt = now
            com.music.spotui.automix.AutoMix.journal(String.format("%s: deck %+.0f ms, speed %.4f", if (preroll) "run-up" else "mix", e, speed))
        }
    }

    /**
     * The mix starts: the deck becomes the main player (the queue around its song, the listener, the
     * audio focus), the old main player plays the mix out as the outgoing one.
     */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun takeOver(run: MixRun, a: ExoPlayer) {
        val d = run.deck
        if (a.getMediaItemAt(run.toIndex).mediaId != run.toId) return cancelMix("the queue moved")
        val err = run.errors.lastOrNull() ?: 0.0
        val items = (0 until a.mediaItemCount).map(a::getMediaItemAt)
        if (run.toIndex > 0) d.addMediaItems(0, items.subList(0, run.toIndex))
        if (run.toIndex + 1 < items.size) d.addMediaItems(items.subList(run.toIndex + 1, items.size))
        d.repeatMode = a.repeatMode
        d.shuffleModeEnabled = false
        mainListener?.let(a::removeListener)
        // The focus moves with the main role: given up by the old player first, so it does not pause.
        a.setAudioAttributes(audioAttributes, false); a.setHandleAudioBecomingNoisy(false)
        d.setAudioAttributes(audioAttributes, !mirrorActive); d.setHandleAudioBecomingNoisy(true)
        mainListener = listenerFor(d).also(d::addListener)
        exo = d
        outgoing = a
        run.eqA = deckEq
        deckEq = run.eq
        run.state = MixState.MIXING
        SongPlayer.onLocalFade(true)
        com.music.spotui.automix.AutoMix.journal(String.format("mix started (%s), deck %+.0f ms from its place%s", run.plan.style, err, if (run.locked) ", locked" else ""))
        // What a transition of the main player does (not the move to 0:00: the deck starts where the plan says).
        if (skipHidden(d, Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)) return
        prefetchNext(d); continueIfLast(d)
        publish()
    }

    /** During and after the mix: gains, EQ, the lock, then the deck's speed and gain back to its own. */
    private fun mixing(run: MixRun): Boolean {
        val d = run.deck
        if (exo !== d) { endMix(); return false }
        val plan = run.plan
        val a = run.a
        if (run.state == MixState.MIXING && a != null) {
            val x = run.progress()
            if (x >= 1.0) {
                // A is gone.
                com.music.spotui.automix.AutoMix.journal(String.format("mix done, largest gap %.0f ms", run.worstMs))
                handler.removeCallbacks(tick)
                outgoing?.let { it.stop(); it.release() }
                outgoing = null
                SongPlayer.onLocalFade(false)
                run.eqA?.release(); run.eqA = null; run.a = null
                run.state = MixState.RESTORING
            } else {
                val lv = plan.levels(x)
                a.volume = lv.gainA.toFloat(); d.volume = lv.gainB.toFloat()
                run.eqA?.set(lv.lowA, lv.highA); run.eq?.set(lv.lowB, lv.highB)
                // B low in the mix, or a cut (B whole at once, A out within two beats): corrected fast.
                if (a.isPlaying) lock(run, a, preroll = false, quiet = lv.gainB < 0.35 || plan.style == app.myspoti.automix.MixPlan.Style.CUT)
                // Paused: the ticks start again with the play (onIsPlayingChanged).
                return d.playWhenReady
            }
        }
        // Restoring: the song's own speed and full gain.
        val tB = d.currentPosition / 1000.0
        run.eq?.set(0.0, 0.0)
        d.volume = plan.gainAfter(tB).toFloat()
        val speed = (plan.speedAfter(tB) * rate()).toFloat()
        if (kotlin.math.abs(speed - run.lastSpeed) > 0.0005f) { d.playbackParameters = androidx.media3.common.PlaybackParameters(speed); run.lastSpeed = speed }
        if (plan.speedAfter(tB) == 1.0 && plan.gainAfter(tB) >= 0.999) {
            mix = null
            d.volume = 1f
            tune(d)
            com.music.spotui.automix.AutoMix.journal("back to the song's own speed")
            return d.isPlaying
        }
        return true
    }

    /** A pause before the mix: the run-up starts again when the song plays on. */
    private fun mixPaused() {
        val run = mix ?: return
        if (run.state == MixState.PREROLL) {
            run.deck.pause(); run.deck.seekTo(run.startMs); run.state = MixState.READY
        }
    }

    /** The user moved in the song before the mix: the deck goes (prepared again if the end is still ahead). */
    private fun mixMoved() {
        val run = mix ?: return
        if (run.state < MixState.MIXING) cancelMix("seek", again = true)
    }

    private fun cancelMix(why: String, again: Boolean = false) {
        val run = mix ?: return
        if (run.state >= MixState.MIXING) return
        mix = null
        // Not prepared again for this pair (a deck that failed would fail again), unless the user moved.
        if (!again) dropped = run.toIndex to run.toId
        run.eq?.release()
        run.deck.release()
        com.music.spotui.automix.AutoMix.journal("deck dropped: $why")
    }

    /** A user action (skip, seek, stop) during or after the mix: the deck plays on as the song, at its own speed. */
    private fun endMix() {
        val run = mix ?: return
        if (run.state < MixState.MIXING) return cancelMix("stopped")
        mix = null
        run.eqA?.release()
        run.eq?.neutral()
        if (exo === run.deck) tune(run.deck)
        com.music.spotui.automix.AutoMix.journal("mix ended early")
    }

    /** AutoMix turned on or off in the settings. */
    fun autoMixChanged(on: Boolean) {
        val p = exo ?: return
        if (on && deckEq == null) deckEq = com.music.spotui.automix.DeckEq.attach(p.audioSessionId)
        if (!on) { cancelMix("AutoMix off"); planned = null }
        if (p.isPlaying) { handler.removeCallbacks(tick); handler.post(tick) }
    }

    /** For the command line: where the mix stands. */
    fun autoMixState(): String {
        val run = mix
        val p = exo
        return "AutoMix ${if (com.music.spotui.automix.AutoMix.on) "on" else "off"}, " +
            (if (run == null) "no deck" else "deck ${run.state} ${run.plan.style}, progress ${"%.2f".format(run.progress())}") +
            (planned?.let { ", plan ${it.plan}" } ?: "") + (p?.let { ", at ${it.currentPosition} ms of ${it.duration}" } ?: "")
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
            if (outgoing != null && mix == null) {
                val t = CrossfadeRamp.progress(p.currentPosition, fadeMs)
                p.volume = CrossfadeRamp.incomingGain(t)
                outgoing?.volume = CrossfadeRamp.outgoingGain(t)
                if (t >= 1f) endFade() else handler.postDelayed(this, 40)
                return
            }
            if (mix != null || (com.music.spotui.automix.AutoMix.on && !CastPlayback.active)) {
                // Every 20 ms from the deck's readiness: its start and the lock depend on it.
                if (mixTick(p)) handler.postDelayed(this, if (mix?.let { it.state >= MixState.READY } == true) 20 else 200)
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
        endMix()
        exo?.let { it.volume = 1f; if (it.isPlaying) handler.postDelayed(tick, 200) }
    }

    /**
     * How far the fade is, 0 (the song going out alone) to 1 (the next one alone); 1 with no fade.
     * Follows the incoming song's own position, as the volumes do: a stall holds it.
     */
    fun fadeProgress(): Float {
        val p = exo ?: return 1f
        mix?.takeIf { it.state == MixState.MIXING }?.let { m -> return m.progress().toFloat().coerceIn(0f, 1f) }
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
    /** The sleep timer's fade: the player's volume (a crossfade owns it while it runs, the fade then goes on after it). */
    fun setGain(gain: Float) { if (outgoing == null && mix == null) exo?.volume = gain }
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
        if (mixOnSkip()) {
            cancelOnce("next")
            com.music.spotui.extensions.Extensions.skipAsked("forward")
            return
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

    /** The Spotify uris of the queued songs after the current one, in order (a device file has none: left out). */
    fun queuedUris(): List<String> {
        val i = exo?.currentMediaItemIndex ?: return emptyList()
        val from = (i + 1).coerceAtMost(tracks.size)
        return tracks.subList(from, (from + queuedCount()).coerceAtMost(tracks.size)).map(::spotifyId).filter { it.isNotBlank() }.map { "spotify:track:$it" }
    }

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
    /** SongPlayer.repeatMode as this player has it (0 off, 1 the queue, 2 the song for good, 3 the song once; see LocalRepeat). */
    private var repeat = LocalRepeat.OFF

    fun setRepeatMode(mode: Int) {
        repeat = mode
        // Repeat once tapped again, or another mode: its replay to come is cancelled.
        if (mode != LocalRepeat.ONCE) replaying = null
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
        if (repeat != LocalRepeat.ONCE) return
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
     * ExoPlayer repeats a queue of several songs itself; a song that follows itself (repeat one or once, a
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
        if (repeat == LocalRepeat.ONCE) replaying = tracks.getOrNull(index)
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
    /** The hidden songs ("Not interested") skipped in a row; a pick by hand starts no run. */
    private var hiddenRun = 0

    /**
     * A hidden song the queue comes to by itself (or right after another one skipped) is skipped;
     * a queue of only hidden songs is gone through once, then the last one plays. True: skipped.
     */
    private fun skipHidden(p: ExoPlayer, reason: Int): Boolean {
        val t = current()
        val hidden = t != null && com.music.spotui.data.preferences.HiddenSongs.isHidden(spotifyIdOf(t))
        val ours = reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO || (hiddenRun > 0 && reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK)
        if (!hidden || !ours || hiddenRun >= tracks.size || !p.hasNextMediaItem()) { hiddenRun = 0; return false }
        hiddenRun++
        log("skipped ${t?.title}: hidden (not interested)")
        next()
        return true
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
    /** The format the decoder reports for the song playing (its input; Android may resample), or null. */
    fun audioFormat(): androidx.media3.common.Format? = if (CastPlayback.active) null else exo?.audioFormat
    /** The length the player knows of the audio itself (null: not known), never Spotify's. */
    fun knownLength(): Long? = if (CastPlayback.active) null else exo?.duration?.takeIf { it != C.TIME_UNSET && it > 0 }
    /** How far ahead of the position the song is loaded, ms (null: nothing playing here); read by "Stats for nerds" only. */
    fun bufferedAheadMs(): Long? = if (CastPlayback.active) null else exo?.let { (it.bufferedPosition - it.currentPosition).coerceAtLeast(0L) }
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
            end == LocalRepeat.End.REPLAY -> { if (repeat == LocalRepeat.ONCE) replaying = current(); CastPlayback.replay() }
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
        QueueSync.localChanged(queuedUris())
    }
}
