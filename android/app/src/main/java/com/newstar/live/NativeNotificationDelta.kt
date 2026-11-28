package com.newstar.live

/**
 * Supabase returns the newest notifications first. This helper identifies rows
 * inserted after the last foreground notification without replaying the baseline.
 */
internal object NativeNotificationDelta {
    fun <T> newlyInserted(
        newestFirst: List<T>,
        lastSeenId: String,
        idOf: (T) -> String
    ): List<T> {
        if (newestFirst.isEmpty() || lastSeenId.isBlank()) return emptyList()
        val baselineIndex = newestFirst.indexOfFirst { idOf(it) == lastSeenId }
        return if (baselineIndex >= 0) {
            newestFirst.take(baselineIndex).asReversed()
        } else {
            newestFirst.take(1).asReversed()
        }
    }
}
