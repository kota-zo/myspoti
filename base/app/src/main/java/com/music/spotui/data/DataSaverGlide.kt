package com.music.spotui.data

import android.content.Context
import com.bumptech.glide.Glide
import com.bumptech.glide.GlideBuilder
import com.bumptech.glide.Priority
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.Options
import com.bumptech.glide.load.data.DataFetcher
import com.bumptech.glide.load.engine.cache.InternalCacheDiskCacheFactory
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.model.ModelLoader
import com.bumptech.glide.load.model.ModelLoaderFactory
import com.bumptech.glide.load.model.MultiModelLoaderFactory
import com.bumptech.glide.load.model.stream.HttpGlideUrlLoader
import java.io.InputStream

/**
 * Images under the data saver: Glide still shows what its disk cache holds, and asks
 * the network for nothing (see [DataSaver]).
 */
object DataSaverGlide {
    /** Glide's default is 250 MB; covers are small, so the cache stays at 150 MB. */
    private const val DISK_CACHE_BYTES = 150L * 1024 * 1024
    private const val TIMEOUT_MS = 10_000

    fun install(context: Context) {
        // Before the first Glide.get: no annotation processor here, so the default builder is replaced by hand.
        Glide.init(context, GlideBuilder().setDiskCache(InternalCacheDiskCacheFactory(context, DISK_CACHE_BYTES)))
        Glide.get(context).registry.replace(GlideUrl::class.java, InputStream::class.java, Factory())
    }

    private class Factory : ModelLoaderFactory<GlideUrl, InputStream> {
        override fun build(multiFactory: MultiModelLoaderFactory): ModelLoader<GlideUrl, InputStream> =
            Loader(HttpGlideUrlLoader())
        override fun teardown() {}
    }

    private class Loader(private val inner: ModelLoader<GlideUrl, InputStream>) : ModelLoader<GlideUrl, InputStream> {
        override fun buildLoadData(model: GlideUrl, width: Int, height: Int, options: Options): ModelLoader.LoadData<InputStream>? {
            // Glide waits 2.5 s to connect and 2.5 s per read, then gives up for good: on a slow
            // mobile network the covers stayed grey. 10 s, as the app's other clients.
            val timed = Options().apply { putAll(options); set(HttpGlideUrlLoader.TIMEOUT, TIMEOUT_MS) }
            val data = inner.buildLoadData(model, width, height, timed) ?: return null
            return ModelLoader.LoadData(data.sourceKey, data.alternateKeys, Fetcher(data.fetcher))
        }
        override fun handles(model: GlideUrl) = inner.handles(model)
    }

    private class Fetcher(private val inner: DataFetcher<InputStream>) : DataFetcher<InputStream> {
        override fun loadData(priority: Priority, callback: DataFetcher.DataCallback<in InputStream>) {
            if (DataSaver.active) callback.onLoadFailed(DataSaver.Blocked()) else inner.loadData(priority, callback)
        }
        override fun cleanup() = inner.cleanup()
        override fun cancel() = inner.cancel()
        override fun getDataClass(): Class<InputStream> = inner.dataClass
        override fun getDataSource(): DataSource = inner.dataSource
    }
}
