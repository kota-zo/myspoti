package com.music.spotui.ui.components

import com.music.spotui.di.SongPlayer

/**
 * One Canvas player for the whole app: closing and reopening the player screen keeps the
 * clip loaded (and its last frame), instead of building a new player that starts from nothing.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
object CanvasPlayer {
    private var exo: androidx.media3.exoplayer.ExoPlayer? = null
    private var url: String? = null
    private var framed: String? = null
    private val firstFrame = mutableListOf<() -> Unit>()

    /**
     * [url] is loaded and ready to draw: its first frame shows as soon as a view is attached,
     * so the player screen opens on the clip instead of the cover.
     */
    fun isReady(url: String) = this.url == url && (framed == url || exo?.playbackState == androidx.media3.common.Player.STATE_READY)

    /**
     * Loads the clip of the song now playing before the player screen opens (called while
     * the mini player is on screen, so only when the app is in use). Main thread.
     */
    fun preload(context: android.content.Context, url: String) {
        // An address looked up while the app was being left: loaded when the app is back.
        if (!com.music.spotui.di.AppForeground.inForeground) return
        get(context, url)
    }

    /**
     * The app left the screen: the clip's decoder is given back, the clip's address kept, and the
     * clip prepared again at the next [get]. Paused, a prepared player still held its video decoder
     * (a hardware one), which the phone counts and powers as video playing for as long as the app
     * stays in the background: the clip of the last song seen, for the whole background listen.
     * Main thread.
     */
    fun onBackground() {
        val player = exo ?: return
        player.playWhenReady = false
        if (player.playbackState != androidx.media3.common.Player.STATE_IDLE) player.stop()
        framed = null
    }

    /** The app is on screen again: the clip given back in the background is loaded again, ready to be shown. Main thread. */
    fun onForeground() {
        val player = exo ?: return
        if (url != null && player.playbackState == androidx.media3.common.Player.STATE_IDLE) player.prepare()
    }

    /**
     * Downloads the clip [url] to the media cache on storage, without a player and without
     * keeping anything in memory: the next song's Canvas, so it starts from storage when that
     * song comes (the player still loads it then, but from the phone, not the network).
     */
    suspend fun warm(context: android.content.Context, url: String) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        runCatching {
            val source = androidx.media3.datasource.cache.CacheDataSource.Factory()
                .setCache(SongPlayer.mediaCache(context.applicationContext))
                .setUpstreamDataSourceFactory(androidx.media3.datasource.DefaultHttpDataSource.Factory())
                .createDataSource()
            androidx.media3.datasource.cache.CacheWriter(source, androidx.media3.datasource.DataSpec(android.net.Uri.parse(url)), null, null).cache()
        }
    }

    fun get(context: android.content.Context, url: String): androidx.media3.exoplayer.ExoPlayer {
        val player = exo ?: run {
            val cached = androidx.media3.datasource.cache.CacheDataSource.Factory()
                .setCache(SongPlayer.mediaCache(context.applicationContext))
                .setUpstreamDataSourceFactory(androidx.media3.datasource.DefaultHttpDataSource.Factory())
            androidx.media3.exoplayer.ExoPlayer.Builder(context.applicationContext)
                .setMediaSourceFactory(androidx.media3.exoplayer.source.DefaultMediaSourceFactory(cached))
                .build().apply {
                    repeatMode = androidx.media3.common.Player.REPEAT_MODE_ALL
                    volume = 0f
                    playWhenReady = false
                    addListener(object : androidx.media3.common.Player.Listener {
                        override fun onRenderedFirstFrame() {
                            framed = this@CanvasPlayer.url
                            firstFrame.toList().forEach { it() }
                        }
                    })
                }.also { exo = it }
        }
        // A blurred loop under the player: 720p at most, the energy saver's own cap below that
        // (EnergyPolicy.videoMaxHeight was never applied). Muted, it needs no audio track either.
        val maxHeight = minOf(CANVAS_MAX_HEIGHT, app.myspoti.power.EnergySettings.state.value.policy.videoMaxHeight)
        if (player.trackSelectionParameters.maxVideoHeight != maxHeight) {
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setMaxVideoSize(Int.MAX_VALUE, maxHeight)
                .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_AUDIO, true)
                .build()
        }
        if (url != this.url) {
            this.url = url
            framed = null
            player.setMediaItem(androidx.media3.common.MediaItem.fromUri(url))
            player.prepare()
        } else if (player.playbackState == androidx.media3.common.Player.STATE_IDLE) {
            // Given back in the background (see onBackground), or stopped by an error: loaded again.
            player.prepare()
        }
        return player
    }

    /** Tests: [player] stands for the shared player, with [url] loaded in it. */
    internal fun attach(player: androidx.media3.exoplayer.ExoPlayer, url: String) {
        exo = player
        this.url = url
        framed = null
    }

    fun onFirstFrame(listener: () -> Unit): () -> Unit {
        firstFrame += listener
        return { firstFrame -= listener }
    }
}

/** The Canvas's tallest video (see CanvasPlayer.get). */
private const val CANVAS_MAX_HEIGHT = 720
