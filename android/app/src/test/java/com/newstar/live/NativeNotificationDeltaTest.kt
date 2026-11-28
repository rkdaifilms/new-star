package com.newstar.live

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NativeNotificationDeltaTest {
    @Test
    fun returnsAllRowsInsertedAfterBaselineInOldestFirstOrder() {
        val newest = listOf(row("n3"), row("n2"), row("baseline"))
        val fresh = NativeNotificationDelta.newlyInserted(newest, "baseline")

        assertEquals(listOf("n2", "n3"), fresh.map { it.optString("id") })
    }

    @Test
    fun returnsEmptyWhenRealtimeOnlyReportsAnExistingRowUpdate() {
        val newest = listOf(row("baseline"), row("older"))

        assertEquals(emptyList<String>(), NativeNotificationDelta.newlyInserted(newest, "baseline").map { it.optString("id") })
    }

    @Test
    fun doesNotReplayRowsWhenBaselineHasNotBeenLoaded() {
        val newest = listOf(row("n2"), row("n1"))

        assertEquals(emptyList<String>(), NativeNotificationDelta.newlyInserted(newest, "").map { it.optString("id") })
    }

    @Test
    fun fallsBackToLatestRowWhenBaselineFallsOutsideRecentWindow() {
        val newest = listOf(row("latest"), row("previous"))

        assertEquals(listOf("latest"), NativeNotificationDelta.newlyInserted(newest, "older-baseline").map { it.optString("id") })
    }

    private fun row(id: String) = JSONObject().put("id", id)
}
