package com.newstar.live

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.ExistingWorkPolicy
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.UUID

object NativeVideoUploadWork {
    private const val CHANNEL_ID = "new_star_video_upload"
    private const val CHANNEL_NAME = "Video uploads"

    fun uniqueName(userId: String): String = "new-star-video-upload-$userId"

    fun enqueue(
        context: Context,
        uri: Uri,
        userId: String,
        title: String,
        description: String,
        category: String,
        visibility: String,
        audienceType: String,
        mediaKind: String,
        affiliateLink: String,
        location: String?,
        locationVisibility: String
    ): UUID {
        val data = Data.Builder()
            .putString(KEY_URI, uri.toString())
            .putString(KEY_USER_ID, userId)
            .putString(KEY_TITLE, title.take(300))
            .putString(KEY_DESCRIPTION, description.take(3500))
            .putString(KEY_CATEGORY, category.take(100))
            .putString(KEY_VISIBILITY, visibility)
            .putString(KEY_AUDIENCE, audienceType)
            .putString(KEY_MEDIA_KIND, mediaKind)
            .putString(KEY_AFFILIATE, affiliateLink.take(1000))
            .putString(KEY_LOCATION, location?.take(2500))
            .putString(KEY_LOCATION_VISIBILITY, locationVisibility)
            .build()
        val request = OneTimeWorkRequestBuilder<NativeVideoUploadWorker>()
            .setInputData(data)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setBackoffCriteria(
                androidx.work.BackoffPolicy.EXPONENTIAL,
                30,
                java.util.concurrent.TimeUnit.SECONDS
            )
            .addTag(uniqueName(userId))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            uniqueName(userId),
            ExistingWorkPolicy.KEEP,
            request
        )
        return request.id
    }

    fun cancel(context: Context, userId: String) {
        WorkManager.getInstance(context).cancelUniqueWork(uniqueName(userId))
    }

    internal fun foregroundInfo(context: Context, progress: Int, message: String): ForegroundInfo {
        ensureChannel(context)
        val notification = buildNotification(context, progress, message)
        val serviceType = if (Build.VERSION.SDK_INT >= 29) {
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else 0
        return if (Build.VERSION.SDK_INT >= 29) {
            ForegroundInfo(NOTIFICATION_ID, notification, serviceType)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        CHANNEL_NAME,
                        NotificationManager.IMPORTANCE_LOW
                    ).apply { description = "Shows progress for native New Star video uploads." }
                )
            }
        }
    }

    private fun buildNotification(context: Context, progress: Int, message: String): Notification {
        val safeProgress = progress.coerceIn(0, 100)
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("New Star · Video upload")
            .setContentText(message.take(120))
            .setProgress(100, safeProgress, safeProgress == 0)
            .setOngoing(safeProgress < 100)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    internal const val KEY_URI = "video_uri"
    internal const val KEY_USER_ID = "user_id"
    internal const val KEY_TITLE = "title"
    internal const val KEY_DESCRIPTION = "description"
    internal const val KEY_CATEGORY = "category"
    internal const val KEY_VISIBILITY = "visibility"
    internal const val KEY_AUDIENCE = "audience_type"
    internal const val KEY_MEDIA_KIND = "media_kind"
    internal const val KEY_AFFILIATE = "affiliate_link"
    internal const val KEY_LOCATION = "location_json"
    internal const val KEY_LOCATION_VISIBILITY = "location_visibility"
    private const val NOTIFICATION_ID = 7318
}

class NativeVideoUploadWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val expectedUserId = inputData.getString(NativeVideoUploadWork.KEY_USER_ID)
            ?: return Result.failure(workDataOf("error" to "Login account missing."))
        val uriText = inputData.getString(NativeVideoUploadWork.KEY_URI)
            ?: return Result.failure(workDataOf("error" to "Video file reference missing."))
        val session = NativeSupabase.validSession(applicationContext)
            ?: return Result.failure(workDataOf("error" to "Login session समाप्त हो गई। दोबारा Login करें।"))
        if (session.userId != expectedUserId) {
            return Result.failure(workDataOf("error" to "Upload draft दूसरे account का है। सही account से login करें।"))
        }

        val uri = runCatching { Uri.parse(uriText) }.getOrNull()
            ?: return Result.failure(workDataOf("error" to "Video file URI अमान्य है।"))
        val location = inputData.getString(NativeVideoUploadWork.KEY_LOCATION)
            ?.takeIf { it.isNotBlank() }
            ?.let { runCatching { org.json.JSONObject(it) }.getOrNull() }

        var latestProgress = 0
        var latestMessage = "Upload शुरू हो रहा है…"
        setForeground(NativeVideoUploadWork.foregroundInfo(applicationContext, 0, latestMessage))
        setProgress(workDataOf("progress" to 0, "message" to latestMessage))

        return try {
            NativeVideoUploader.upload(
                applicationContext,
                uri,
                session,
                inputData.getString(NativeVideoUploadWork.KEY_TITLE).orEmpty(),
                inputData.getString(NativeVideoUploadWork.KEY_DESCRIPTION).orEmpty(),
                inputData.getString(NativeVideoUploadWork.KEY_CATEGORY) ?: "General",
                inputData.getString(NativeVideoUploadWork.KEY_VISIBILITY) ?: "public",
                inputData.getString(NativeVideoUploadWork.KEY_AUDIENCE) ?: "all",
                inputData.getString(NativeVideoUploadWork.KEY_MEDIA_KIND) ?: "video",
                inputData.getString(NativeVideoUploadWork.KEY_AFFILIATE).orEmpty(),
                location,
                inputData.getString(NativeVideoUploadWork.KEY_LOCATION_VISIBILITY) ?: "public",
                onProgress = { value ->
                    latestProgress = value.coerceIn(0, 100)
                    latestMessage = "वीडियो upload हो रहा है: $latestProgress%"
                    setProgress(workDataOf("progress" to latestProgress, "message" to latestMessage))
                    setForeground(
                        NativeVideoUploadWork.foregroundInfo(
                            applicationContext,
                            latestProgress,
                            latestMessage
                        )
                    )
                },
                onStatus = { message ->
                    latestMessage = message
                    setProgress(workDataOf("progress" to latestProgress, "message" to message.take(500)))
                    setForeground(
                        NativeVideoUploadWork.foregroundInfo(
                            applicationContext,
                            latestProgress,
                            message
                        )
                    )
                }
            )
            Result.success(workDataOf("progress" to 100, "message" to latestMessage))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            val message = error.message ?: "Video upload असफल हुआ।"
            val retryable = error is IOException ||
                error is SocketTimeoutException ||
                message.contains("network", true) ||
                message.contains("connection", true) ||
                message.contains("timeout", true) ||
                message.contains("Cloudflare", true) ||
                message.contains("offset", true) ||
                message.contains("चार कोशिशों", true) ||
                message.contains("resume check failed", true) ||
                message.contains("JWT", true) ||
                message.contains("token", true) ||
                message.contains("401")
            if (retryable && runAttemptCount < 4) {
                Result.retry()
            } else {
                Result.failure(workDataOf("error" to message.take(1500), "progress" to latestProgress))
            }
        }
    }
}
