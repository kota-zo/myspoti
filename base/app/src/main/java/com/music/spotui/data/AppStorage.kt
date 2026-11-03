package com.music.spotui.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.music.spotui.di.Graph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Settings > Storage: what the app keeps in its cache folder, shown and emptied there. The media
 * cache (Canvas videos, [com.music.spotui.di.SongPlayer.mediaCache]), the AutoMix analyses and
 * Glide's covers; downloads are counted apart ([com.music.spotui.data.offline.OfflineStore]) and
 * never cleared from here.
 */
object AppStorage {
    private const val PREFS = "myspoti_prefs"
    private const val KEY_MEDIA_MB = "media_cache_mb"
    private const val MEDIA_DEFAULT_MB = 64

    /** The sizes offered for the media cache, in MB (64 MB, the old fixed size, by default). */
    val MEDIA_CACHE_CHOICES = listOf(64, 256, 512, 1024)

    /** The media cache's size as chosen, in MB (Compose state). */
    var mediaCacheMb by mutableStateOf(
        Graph.app.getSharedPreferences(PREFS, 0).getInt(KEY_MEDIA_MB, MEDIA_DEFAULT_MB).takeIf { it in MEDIA_CACHE_CHOICES } ?: MEDIA_DEFAULT_MB)
        private set

    /** The size the open cache was given (null before its first use): the cache is sized once, so a change applies at the next start. */
    var mediaCacheMbOpen by mutableStateOf<Int?>(null)
        private set

    fun chooseMediaCacheMb(mb: Int) {
        mediaCacheMb = mb
        Graph.app.getSharedPreferences(PREFS, 0).edit().putInt(KEY_MEDIA_MB, mb).apply()
    }

    /** The media cache's limit in bytes, read once when the cache is opened. */
    fun mediaCacheLimit(): Long = mediaCacheMb.also { mediaCacheMbOpen = it } * 1024L * 1024

    /** What each part takes, in bytes. */
    class Usage(val media: Long, val automix: Long, val images: Long) {
        val caches get() = media + automix + images
    }

    suspend fun usage(context: Context): Usage = withContext(Dispatchers.IO) {
        Usage(
            media = size(File(context.cacheDir, "media")),
            automix = runCatching { com.music.spotui.automix.AutoMix.cacheBytes() }.getOrDefault(0L),
            images = size(File(context.cacheDir, com.bumptech.glide.load.engine.cache.DiskCache.Factory.DEFAULT_DISK_CACHE_DIR)),
        )
    }

    /** Empties the three caches; downloads are kept. The covers in memory are dropped too, on the main thread. */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    suspend fun clearCaches(context: Context) {
        withContext(Dispatchers.IO) {
            // Through the cache itself: it keeps an index of its files (a video playing finishes from its buffer).
            runCatching {
                val media = com.music.spotui.di.SongPlayer.mediaCache(context.applicationContext)
                media.keys.toList().forEach { runCatching { media.removeResource(it) } }
            }
            runCatching { com.music.spotui.automix.AutoMix.clearCache() }
            runCatching { com.bumptech.glide.Glide.get(context).clearDiskCache() }
        }
        withContext(Dispatchers.Main) { runCatching { com.bumptech.glide.Glide.get(context).clearMemory() } }
    }

    private fun size(dir: File): Long = runCatching { dir.walk().filter { it.isFile }.sumOf { it.length() } }.getOrDefault(0L)
}
