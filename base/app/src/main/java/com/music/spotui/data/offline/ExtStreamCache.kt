/* SPDX-License-Identifier: GPL-3.0-only */
package com.music.spotui.data.offline

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import com.music.spotui.di.SongPlayer
import com.music.spotui.extensions.ExtensionAudio

/**
 * The songs an audio-source extension streams, kept in the media cache as they are read, and the
 * next one read whole ahead ([cacheAhead]): a song then plays on through a network that drops
 * (airplane mode, a tunnel) and a skip back plays from the phone. Keyed by the song (the
 * extension's address changes at each ask); downloads and files of the device are read as before.
 */
@OptIn(UnstableApi::class)
object ExtStreamCache {
    private fun key(uri: Uri) = "ext:" + uri.lastPathSegment.orEmpty()

    private fun cached(context: Context): CacheDataSource.Factory = CacheDataSource.Factory()
        .setCache(SongPlayer.mediaCache(context.applicationContext))
        .setUpstreamDataSourceFactory(OfflineCrypto.Factory(context))
        .setCacheKeyFactory { spec -> key(spec.uri) }
        // A cache that fails (full storage) never stops the song: it is read from the network.
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    /** The player's sources: an extension's song through the cache, anything else as before. */
    class Factory(private val context: Context) : DataSource.Factory {
        private val plain = OfflineCrypto.Factory(context)
        private val viaCache by lazy { cached(context) }
        override fun createDataSource(): DataSource = Routing(plain.createDataSource()) { viaCache.createDataSource() }
    }

    /** Opens [cachedSource] for an extension's song, [plain] for the rest. */
    private class Routing(private val plain: DataSource, private val cachedSource: () -> DataSource) : DataSource {
        private var cache: DataSource? = null
        private var active: DataSource = plain
        private val listeners = mutableListOf<TransferListener>()

        override fun addTransferListener(transferListener: TransferListener) {
            listeners += transferListener
            plain.addTransferListener(transferListener)
            cache?.addTransferListener(transferListener)
        }

        override fun open(dataSpec: DataSpec): Long {
            active = if (dataSpec.uri.scheme == ExtensionAudio.SCHEME)
                (cache ?: cachedSource().also { c -> listeners.forEach(c::addTransferListener); cache = c })
            else plain
            return active.open(dataSpec)
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = active.read(buffer, offset, length)
        override fun getUri(): Uri? = active.uri
        override fun getResponseHeaders(): Map<String, List<String>> = active.responseHeaders
        override fun close() = active.close()
    }

    /**
     * Reads the song at [uri] into the cache, up to half the media cache (a long FLAC is kept in
     * part: its start, which is what a drop or a skip needs first). Blocking; [keepGoing] false
     * stops it. Off a metered network only (the caller checks).
     */
    fun cacheAhead(context: Context, uri: Uri, keepGoing: () -> Boolean) {
        if (uri.scheme != ExtensionAudio.SCHEME) return
        val cap = com.music.spotui.data.AppStorage.mediaCacheLimit() / 2
        val spec = DataSpec.Builder().setUri(uri).setLength(cap).build()
        val writer = CacheWriter(cached(context).createDataSource(), spec, null) { _, _, _ -> }
        val watch = Thread {
            while (keepGoing()) runCatching { Thread.sleep(250) }.onFailure { return@Thread }
            writer.cancel()
        }.apply { isDaemon = true; name = "ext-cache-watch"; start() }
        try { writer.cache() } finally { watch.interrupt() }
    }
}
