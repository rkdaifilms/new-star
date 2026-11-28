package com.newstar.live

import android.content.Context
import android.net.Uri
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.concurrent.TimeUnit

object NativeBackgroundNotificationWork {
    private const val UNIQUE_WORK = "new_star_background_notifications"
    private const val PREFS_NAME = "newstar_background_notification_cursor"

    fun schedule(context: Context, userId: String) {
        require(userId.isNotBlank())
        val cursorPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val cursorKey = "cursor_$userId"
        if (cursorPreferences.getString(cursorKey, null).isNullOrBlank()) {
            cursorPreferences.edit()
                .putString(cursorKey, NativeBackgroundNotificationCursor.encode(
                    NativeBackgroundNotificationCursor.Cursor(
                        createdAt = Instant.now().toString(),
                        id = NativeBackgroundNotificationCursor.MIN_UUID
                    )
                ))
                .putString("seen_$userId", "[]")
                .apply()
        }
        val request = PeriodicWorkRequestBuilder<NativeBackgroundNotificationWorker>(
            15, TimeUnit.MINUTES
        ).setInputData(androidx.work.workDataOf("user_id" to userId))
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            UNIQUE_WORK,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK)
    }
}

internal object NativeBackgroundNotificationCursor {
    const val MIN_UUID = "00000000-0000-0000-0000-000000000000"

    data class Cursor(val createdAt: String, val id: String)

    fun decode(encoded: String?): Cursor? {
        if (encoded.isNullOrBlank()) return null
        return try {
            val json = JSONObject(encoded)
            val timestamp = json.optString("created_at")
            val id = json.optString("id")
            if (timestamp.isBlank() || id.isBlank()) null else Cursor(timestamp, id)
        } catch (_: Exception) {
            if (encoded.startsWith("{")) null
            else Cursor(encoded, MIN_UUID)
        }
    }

    fun encode(cursor: Cursor): String =
        JSONObject().put("created_at", cursor.createdAt).put("id", cursor.id).toString()

    fun filter(cursor: Cursor): String {
        val timestamp = cursor.createdAt
        val expression =
            "(created_at.gt.$timestamp,and(created_at.eq.$timestamp,id.gt.${cursor.id}))"
        return "or=${Uri.encode(expression)}"
    }

    fun fromRow(row: JSONObject): Cursor? {
        val timestamp = row.optString("created_at")
        val id = row.optString("id")
        return if (timestamp.isBlank() || id.isBlank()) null else Cursor(timestamp, id)
    }

    fun parseSeenIds(encoded: String): LinkedHashSet<String> {
        val result = LinkedHashSet<String>()
        runCatching {
            val array = JSONArray(encoded)
            for (index in 0 until array.length()) {
                array.optString(index).takeIf { it.isNotBlank() }?.let(result::add)
            }
        }
        return result
    }

    fun mergeSeenIds(existing: Set<String>, newIds: List<String>, maxEntries: Int = 300): String {
        require(maxEntries in 1..1000)
        val merged = LinkedHashSet<String>()
        (existing.toList() + newIds).filter { it.isNotBlank() }.forEach { merged.add(it) }
        while (merged.size > maxEntries) merged.remove(merged.first())
        return JSONArray().also { array -> merged.forEach(array::put) }.toString()
    }

    fun unseen(rows: List<JSONObject>, seenIds: Set<String>): List<JSONObject> =
        rows.filter { row ->
            val id = row.optString("id")
            id.isNotBlank() && id !in seenIds
        }
}

class NativeBackgroundNotificationWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val expectedUserId = inputData.getString("user_id")
            ?: return Result.failure()
        return try {
            if (!NativeSystemNotifications.hasPermission(applicationContext)) return Result.success()
            val session = NativeSupabase.validSession(applicationContext)
                ?: return Result.success()
            if (session.userId != expectedUserId) return Result.success()

            val preferences = applicationContext.getSharedPreferences(
                PREFS_NAME,
                Context.MODE_PRIVATE
            )
            val cursorKey = "cursor_$expectedUserId"
            val seenKey = "seen_$expectedUserId"
            val storedCursor = preferences.getString(cursorKey, null)
            var pageCursor = NativeBackgroundNotificationCursor.decode(storedCursor)
            if (pageCursor == null) {
                // Establish a baseline for first install or a corrupted cursor; do not replay history.
                pageCursor = NativeBackgroundNotificationCursor.Cursor(
                    createdAt = Instant.now().toString(),
                    id = NativeBackgroundNotificationCursor.MIN_UUID
                )
                preferences.edit()
                    .putString(cursorKey, NativeBackgroundNotificationCursor.encode(pageCursor))
                    .putString(seenKey, "[]")
                    .apply()
                return Result.success()
            }

            runCatching { NativeNotificationPreferences.synchronize(applicationContext, session) }
            val seenIds = NativeBackgroundNotificationCursor.parseSeenIds(
                preferences.getString(seenKey, "[]").orEmpty()
            )
            val allRows = ArrayList<JSONObject>()

            // Keyset pagination avoids offset drift while new rows arrive and handles equal timestamps.
            for (page in 0 until MAX_PAGES) {
                val rows = NativeSupabase.get(
                    "/rest/v1/notifications?select=id,user_id,actor_id,type,message,is_read,created_at" +
                        "&user_id=eq.${Uri.encode(session.userId)}" +
                        "&${NativeBackgroundNotificationCursor.filter(pageCursor)}" +
                        "&order=created_at.asc,id.asc&limit=$PAGE_SIZE",
                    session
                )
                val pageRows = (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }
                if (pageRows.isEmpty()) break
                allRows.addAll(pageRows)
                val nextCursor = NativeBackgroundNotificationCursor.fromRow(pageRows.last())
                    ?: throw IllegalStateException("Notification row is missing its cursor fields.")
                if (nextCursor == pageCursor) {
                    throw IllegalStateException("Notification pagination did not advance.")
                }
                pageCursor = nextCursor
                if (pageRows.size < PAGE_SIZE) break
            }

            if (allRows.isEmpty()) return Result.success()

            val unseen = NativeBackgroundNotificationCursor.unseen(allRows, seenIds)
            val unreadEnabled = unseen.filter { row ->
                !row.optBoolean("is_read", false) &&
                    NativeNotificationPreferences.isEnabled(applicationContext, row)
            }
            val actorIds = unreadEnabled.map { it.optString("actor_id") }
                .filter { it.isNotBlank() && it != session.userId }
                .distinct()
            val profilesById = if (actorIds.isEmpty()) {
                emptyMap()
            } else {
                val encodedIds = Uri.encode("(${actorIds.joinToString(",")})")
                val profiles = NativeSupabase.get(
                    "/rest/v1/creator_public_profiles?select=user_id,username,full_name" +
                        "&user_id=in.$encodedIds",
                    session
                )
                (0 until profiles.length()).mapNotNull { profiles.optJSONObject(it) }
                    .associateBy { it.optString("user_id") }
            }

            unreadEnabled.forEach { notification ->
                val actorId = notification.optString("actor_id")
                val profile = profilesById[actorId]
                val actorName = profile?.optString("full_name").orEmpty()
                    .ifBlank { profile?.optString("username").orEmpty() }
                NativeSystemNotifications.show(applicationContext, notification, actorName)
            }

            val updatedSeen = NativeBackgroundNotificationCursor.mergeSeenIds(
                seenIds,
                allRows.map { it.optString("id") }
            )
            preferences.edit().apply {
                putString(seenKey, updatedSeen)
                putString(cursorKey, NativeBackgroundNotificationCursor.encode(pageCursor))
            }.apply()
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            Result.retry()
        }
    }

    private companion object {
        const val PREFS_NAME = "newstar_background_notification_cursor"
        const val PAGE_SIZE = 100
        const val MAX_PAGES = 5
    }
}
