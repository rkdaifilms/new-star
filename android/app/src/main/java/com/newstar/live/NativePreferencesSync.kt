package com.newstar.live

import android.net.Uri
import org.json.JSONObject

/**
 * Reads the signed-in user's last synced preference document.
 * The local settings remain the immediate source of truth while the request is in flight.
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

    suspend fun load(session: NativeSession): JSONObject? {
        val rows = NativeSupabase.get(
            "/rest/v1/user_preferences?select=preferences&user_id=eq.${Uri.encode(session.userId)}&limit=1",
            session
        )
        if (rows.length() == 0) return null
        return rows.optJSONObject(0)?.optJSONObject("preferences")
    }
}
