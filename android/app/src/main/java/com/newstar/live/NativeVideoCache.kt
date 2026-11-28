package com.newstar.live

import android.content.Context
import android.net.Uri
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.database.StandaloneDatabaseProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Shared on-disk media cache for feed playback and bounded next-item preloading.
 * The cache is process-wide: creating more than one SimpleCache for the same
 * directory can corrupt the cache index, so initialization is synchronized.
 */
internal object NativeVideoCache {
    private const val CACHE_DIRECTORY = "new_star_video_cache"
    private const val MAX_CACHE_BYTES = 150L * 1024L * 1024L
    private const val PRELOAD_BYTES = 1_500_000L

    @Volatile
    private var sharedCache: SimpleCache? = null

    private fun cache(context: Context): SimpleCache {
        sharedCache?.let { return it }
        return synchronized(this) {
            sharedCache ?: run {
                val appContext = context.applicationContext
                val directory = File(appContext.cacheDir, CACHE_DIRECTORY)
                val database = StandaloneDatabaseProvider(appContext)
                SimpleCache(
                    directory,
                    LeastRecentlyUsedCacheEvictor(MAX_CACHE_BYTES),
                    database
                ).also { sharedCache = it }
            }
        }
    }

    fun playbackDataSourceFactory(context: Context, preferOffline: Boolean): DataSource.Factory {
        if (preferOffline) {
            runCatching { NativeOfflineDownloads.offlineDataSourceFactory(context) }
                .getOrNull()?.let { return it }
        }
        return dataSourceFactory(context)
    }

    fun dataSourceFactory(context: Context): DataSource.Factory {
        val upstream = DefaultHttpDataSource.Factory()
            .setUserAgent("NewStar-Android")
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)

        return try {
            CacheDataSource.Factory()
                .setCache(cache(context))
                .setUpstreamDataSourceFactory(upstream)
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        } catch (_: Exception) {
            // A damaged/unavailable cache must not prevent online video playback.
            // Fall back to a normal HTTP/local data source for this process.
            DefaultDataSource.Factory(context.applicationContext, upstream)
        }
    }

    /**
     * Warms a bounded prefix of the next feed item's media into the shared cache.
     * It never downloads an entire long video. HLS URLs warm the playlist
     * resource; the playback source factory then caches media requests normally.
     */
    suspend fun preload(context: Context, url: String) = withContext(Dispatchers.IO) {
        if (!url.startsWith("https://") && !url.startsWith("http://")) return@withContext
        var dataSource: DataSource? = null
        try {
            dataSource = dataSourceFactory(context).createDataSource()
            val spec = DataSpec.Builder()
                .setUri(Uri.parse(url))
                .setPosition(0L)
                .setLength(PRELOAD_BYTES)
                .build()
            CacheWriter(dataSource, spec, null, null).cache()
        } catch (_: Exception) {
            // Preloading is opportunistic. Cache/index/storage failures must never
            // take down the feed; playback can still use the upstream source.
        } finally {
            runCatching { dataSource?.close() }
        }
    }
}
