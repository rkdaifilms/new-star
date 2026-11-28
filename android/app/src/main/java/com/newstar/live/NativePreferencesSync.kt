package com.newstar.live

import android.net.Uri
import org.json.JSONObject
import java.time.Instant

/**
 * Keeps the native settings document in sync with the same user_preferences
 * row used by preferences.js. The most recently edited document wins.
 */
internal object NativePreferencesSync {
    val languages = setOf(
        "hi", "ne", "en", "zh", "es", "fr", "ar", "ja", "de", "ru", "pt", "it",
        "ko", "tr", "bn", "ur", "id", "vi", "th", "fa", "sw", "nl", "pl", "uk", "el"
    )
    val themes = setOf(
        "dark", "light", "ocean", "forest", "sunset", "purple", "rose", "crimson",
        "gold", "teal", "cyan", "indigo", "lavender", "mint", "emerald", "ruby",
        "amber", "coral", "slate", "coffee", "plum", "sakura", "neon", "sand", "aurora"
    )
    val textScales = setOf("0.9", "1", "1.1", "1.2", "1.25")

    fun validString(source: JSONObject, key: String, allowed: Set<String>): String? {
        val value = source.optString(key).takeIf { it.isNotBlank() } ?: return null
        return value.takeIf { it in allowed }
    }

    fun validTextScale(source: JSONObject): String? {
        val raw = source.opt("textScale")?.toString() ?: return null
        val value = raw.toDoubleOrNull()?.let { number ->
            if (number % 1.0 == 0.0) number.toInt().toString() else number.toString()
        } ?: raw
        return value.takeIf { it in textScales }
    }

    fun validBoolean(source: JSONObject, key: String): Boolean? {
        if (!source.has(key) || source.isNull(key)) return null
        val value = source.opt(key)
        return when (value) {
            is Boolean -> value
            is Number -> value.toInt() != 0
            is String -> when (value.lowercase()) {
                "true", "1" -> true
                "false", "0" -> false
                else -> null
            }
            else -> null
        }
    }

    fun timestampMillis(value: String?): Long {
        if (value.isNullOrBlank()) return 0L
        return try { Instant.parse(value).toEpochMilli() } catch (_: Exception) { 0L }
    }

    fun shouldRestoreRemote(localUpdatedAt: String?, remoteUpdatedAt: String?): Boolean {
        val remoteTime = timestampMillis(remoteUpdatedAt)
        return remoteTime > 0L && remoteTime > timestampMillis(localUpdatedAt)
    }

    suspend fun synchronize(session: NativeSession, localPreferences: JSONObject): JSONObject {
        val localDocument = JSONObject(localPreferences.toString())
        val localUpdatedAt = localDocument.optString("_updatedAt").takeIf { it.isNotBlank() }
            ?: Instant.now().toString().also { localDocument.put("_updatedAt", it) }
        val rows = NativeSupabase.get(
            "/rest/v1/user_preferences?select=preferences,updated_at&user_id=eq.${Uri.encode(session.userId)}&limit=1",
            session
        )
        val row = rows.optJSONObject(0)
        val remotePreferences = row?.optJSONObject("preferences")
        val remoteUpdatedAt = row?.optString("updated_at")
        if (remotePreferences != null && shouldRestoreRemote(localUpdatedAt, remoteUpdatedAt)) {
            val restored = JSONObject(remotePreferences.toString())
            restored.put("_updatedAt", remoteUpdatedAt)
            return restored
        }
        val timestamp = if (row == null) localUpdatedAt else {
            if (timestampMillis(localUpdatedAt) > timestampMillis(remoteUpdatedAt)) localUpdatedAt
            else Instant.now().toString()
        }
        localDocument.put("_updatedAt", timestamp)
        NativeSupabase.upsert(
            "/rest/v1/user_preferences",
            JSONObject()
                .put("user_id", session.userId)
                .put("preferences", localDocument)
                .put("updated_at", timestamp),
            session
        )
        return localDocument
    }
}
