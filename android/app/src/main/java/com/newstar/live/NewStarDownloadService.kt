package com.newstar.live

import android.app.Notification
import android.content.Context
import android.net.Uri
import androidx.media3.common.MimeTypes
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Scheduler
import java.io.File
import java.util.concurrent.Executor

/**
 * Persistent Media3 download service. HLS manifests are downloaded as HLS
 * media packages; progressive MP4 files remain progressive downloads.
 * Content is stored in app-private files and does not pretend to be a public MP4.
 */
internal object NativeOfflineDownloads {
    private const val DOWNLOAD_DIRECTORY = "new_star_offline_media"

    @Volatile
    private var manager: DownloadManager? = null

    @Volatile
    private var cache: SimpleCache? = null

    @Volatile
    private var database: StandaloneDatabaseProvider? = null

    fun manager(context: Context): DownloadManager {
        manager?.let { return it }
        return synchronized(this) {
            manager ?: createManager(context.applicationContext).also { manager = it }
        }
    }

    private fun createManager(context: Context): DownloadManager {
        val db = StandaloneDatabaseProvider(context)
        val mediaCache = SimpleCache(
            File(context.filesDir, DOWNLOAD_DIRECTORY),
            NoOpCacheEvictor(),
            db
        )
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("NewStar-Android")
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(20_000)
            .setReadTimeoutMs(30_000)
        val executor = Executor { command ->
            Thread(command, "new-star-media-download").start()
        }
        database = db
        cache = mediaCache
        return DownloadManager(context, db, mediaCache, httpFactory, executor).apply {
            maxParallelDownloads = 2
            minRetryCount = 3
        }
    }

    fun enqueue(context: Context, videoId: String, url: String) {
        val uri = Uri.parse(url)
        require(uri.scheme == "https") { "सिर्फ सुरक्षित HTTPS video URL डाउनलोड किया जा सकता है।" }
        val safeId = videoId.replace(Regex("[^A-Za-z0-9_-]"), "").take(100)
        require(safeId.isNotBlank()) { "Video ID अमान्य है।" }
        val mimeType = mimeTypeFor(uri)
        val request = DownloadRequest.Builder(safeId, uri)
            .setMimeType(mimeType)
            .build()
        DownloadService.sendAddDownload(
            context.applicationContext,
            NewStarDownloadService::class.java,
            request,
            true
        )
    }

    internal fun mimeTypeFor(uri: Uri): String {
        val path = uri.path.orEmpty().lowercase()
        return when {
            path.endsWith(".m3u8") -> MimeTypes.APPLICATION_M3U8
            path.endsWith(".mpd") -> MimeTypes.APPLICATION_MPD
            path.endsWith(".mp4") -> MimeTypes.VIDEO_MP4
            else -> throw IllegalArgumentException(
                "Video URL का format पहचाना नहीं गया। HLS (.m3u8), DASH (.mpd) या MP4 URL चाहिए।"
            )
        }
    }
}

class NewStarDownloadService : DownloadService(
    NOTIFICATION_ID,
    DEFAULT_FOREGROUND_NOTIFICATION_UPDATE_INTERVAL,
    CHANNEL_ID,
    R.string.new_star_download_channel,
    R.string.new_star_download_channel_description
) {
    private val notificationHelper by lazy { DownloadNotificationHelper(this, CHANNEL_ID) }

    override fun getDownloadManager(): DownloadManager =
        NativeOfflineDownloads.manager(this)

    override fun getScheduler(): Scheduler? = null

    override fun getForegroundNotification(
        downloads: MutableList<Download>,
        notMetRequirements: Int
    ): Notification = notificationHelper.buildProgressNotification(
        this,
        android.R.drawable.stat_sys_download,
        null,
        "New Star offline downloads",
        downloads,
        notMetRequirements
    )

    companion object {
        private const val NOTIFICATION_ID = 7320
        private const val CHANNEL_ID = "new_star_offline_downloads"
    }
}
