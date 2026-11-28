package com.newstar.live

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import org.json.JSONObject

internal enum class NativeNotificationCategory {
    FOLLOW, LIKE, COMMENT, MESSAGE
}

internal data class NativeNotificationPreferenceState(
    val follows: Boolean = true,
    val likes: Boolean = true,
    val comments: Boolean = true,
    val messages: Boolean = true
) {
    fun allows(category: NativeNotificationCategory?): Boolean = when (category) {
        NativeNotificationCategory.FOLLOW -> follows
        NativeNotificationCategory.LIKE -> likes
        NativeNotificationCategory.COMMENT -> comments
        NativeNotificationCategory.MESSAGE -> messages
        null -> true
    }
}

/**
 * Applies the same four notification switches stored by NativeSettings and
 * preferences.js. Unknown future notification types remain visible by default.
 */
internal object NativeNotificationPreferences {
    private const val PREFS_NAME = "newstar_preferences"

    fun read(preferences: SharedPreferences): NativeNotificationPreferenceState =
        NativeNotificationPreferenceState(
            follows = preferences.getBoolean("notifyFollow", true),
            likes = preferences.getBoolean("notifyLike", true),
            comments = preferences.getBoolean("notifyComment", true),
            messages = preferences.getBoolean("notifyMessage", true)
        )

    fun category(type: String?, message: String?): NativeNotificationCategory? {
        val normalizedType = type.orEmpty().lowercase().replace(Regex("[^a-z0-9]+"), "_")
        when {
            normalizedType.contains("follow") || normalizedType.contains("follower") ->
                return NativeNotificationCategory.FOLLOW
            normalizedType.contains("like") || normalizedType.contains("heart") ->
                return NativeNotificationCategory.LIKE
            normalizedType.contains("comment") || normalizedType.contains("reply") ->
                return NativeNotificationCategory.COMMENT
            normalizedType.contains("message") || normalizedType.contains("chat") ||
                normalizedType == "dm" || normalizedType.startsWith("dm_") ->
                return NativeNotificationCategory.MESSAGE
        }

        val text = message.orEmpty().lowercase()
        return when {
            listOf("followed you", "started following", "new follower", "follow request",
                "फॉलो किया", "फॉलो रिक्वेस्ट", "अनुसरण").any(text::contains) ->
                NativeNotificationCategory.FOLLOW
            listOf("liked your", "likes your", "liked a video", "ने लाइक", "पसंद किया").any(text::contains) ->
                NativeNotificationCategory.LIKE
            listOf("commented", "new comment", "replied to", "कमेंट", "टिप्पणी").any(text::contains) ->
                NativeNotificationCategory.COMMENT
            listOf("sent you a message", "new message", "direct message", "message from",
                "संदेश भेजा", "नया संदेश").any(text::contains) ->
                NativeNotificationCategory.MESSAGE
            else -> null
        }
    }

    fun isEnabled(
        type: String?,
        message: String?,
        state: NativeNotificationPreferenceState
    ): Boolean = state.allows(category(type, message))

    fun isEnabled(context: Context, notification: JSONObject): Boolean {
        val state = read(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE))
        return isEnabled(notification.optString("type"), notification.optString("message"), state)
    }

    /**
     * Refresh cloud preferences before foreground notification monitoring starts.
     * The complete document shape matches NativeSettings and the web preferences.js.
     * If the network is unavailable, previously saved local switches remain active.
     */
    suspend fun synchronize(context: Context, session: NativeSession) {
        val local = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val accessibility = JSONObject()
            .put("textScale", (local.getString("textScale", "1") ?: "1").toDoubleOrNull() ?: 1.0)
            .put("reducedMotion", local.getBoolean("reducedMotion", false))
            .put("highContrast", local.getBoolean("highContrast", false))
        val notifications = JSONObject()
            .put("follows", local.getBoolean("notifyFollow", true))
            .put("likes", local.getBoolean("notifyLike", true))
            .put("comments", local.getBoolean("notifyComment", true))
            .put("messages", local.getBoolean("notifyMessage", true))
        val document = JSONObject()
            .put("language", local.getString("language", "hi") ?: "hi")
            .put("theme", local.getString("theme", "dark") ?: "dark")
            .put("accessibility", accessibility)
            .put("notifications", notifications)
            .put("_updatedAt", local.getString("_updatedAt", "") ?: "")

        val resolved = NativePreferencesSync.synchronize(session, document)
        val remoteNotifications = resolved.optJSONObject("notifications") ?: return
        val editor = local.edit()
        NativePreferencesSync.validBoolean(remoteNotifications, "follows")?.let {
            editor.putBoolean("notifyFollow", it)
        }
        NativePreferencesSync.validBoolean(remoteNotifications, "likes")?.let {
            editor.putBoolean("notifyLike", it)
        }
        NativePreferencesSync.validBoolean(remoteNotifications, "comments")?.let {
            editor.putBoolean("notifyComment", it)
        }
        NativePreferencesSync.validBoolean(remoteNotifications, "messages")?.let {
            editor.putBoolean("notifyMessage", it)
        }
        resolved.optString("_updatedAt").takeIf { it.isNotBlank() }?.let {
            editor.putString("_updatedAt", it)
        }
        editor.apply()
    }
}
