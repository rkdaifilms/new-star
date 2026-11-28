package com.newstar.live

import org.json.JSONObject

/**
 * Supabase returns the newest notifications first. This helper identifies rows
 * inserted after the last foreground notification without replaying the baseline.
 */
internal object NativeNotificationDelta {
    fun newlyInserted(
        newestFirst: List<JSONObject>,
        lastSeenId: String
    ): List<JSONObject> {
        if (newestFirst.isEmpty() || lastSeenId.isBlank()) return emptyList()
        val baselineIndex = newestFirst.indexOfFirst { it.optString("id") == lastSeenId }
        return if (baselineIndex >= 0) {
            newestFirst.take(baselineIndex).asReversed()
        } else {
            newestFirst.take(1).asReversed()
        }
    }
}
