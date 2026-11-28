package com.newstar.live

import org.junit.Assert.assertEquals
import org.junit.Test

class NativeNotificationDeltaTest {
    @Test
    fun returnsAllRowsInsertedAfterBaselineInOldestFirstOrder() {
        val newest = listOf("n3", "n2", "baseline")
        val fresh = NativeNotificationDelta.newlyInserted(newest, "baseline") { it }

        assertEquals(listOf("n2", "n3"), fresh)
    }

    @Test
    fun returnsEmptyWhenRealtimeOnlyReportsAnExistingRowUpdate() {
        val newest = listOf("baseline", "older")

        assertEquals(emptyList<String>(), NativeNotificationDelta.newlyInserted(newest, "baseline") { it })
    }

    @Test
    fun doesNotReplayRowsWhenBaselineHasNotBeenLoaded() {
        val newest = listOf("n2", "n1")

        assertEquals(emptyList<String>(), NativeNotificationDelta.newlyInserted(newest, "") { it })
    }

    @Test
    fun fallsBackToLatestRowWhenBaselineFallsOutsideRecentWindow() {
        val newest = listOf("latest", "previous")

        assertEquals(listOf("latest"), NativeNotificationDelta.newlyInserted(newest, "older-baseline") { it })
    }
}
