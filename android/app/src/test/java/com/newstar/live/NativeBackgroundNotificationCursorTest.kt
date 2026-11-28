package com.newstar.live

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NativeBackgroundNotificationCursorTest {
    @Test
    fun parseSeenIdsKeepsUniqueNonBlankIds() {
        assertEquals(
            linkedSetOf("a", "b"),
            NativeBackgroundNotificationCursor.parseSeenIds("[\"a\",\"a\",\"\",\"b\"]")
        )
    }

    @Test
    fun mergeSeenIdsKeepsNewestIdsWithinBound() {
        val merged = NativeBackgroundNotificationCursor.mergeSeenIds(
            linkedSetOf("old", "middle"),
            listOf("middle", "new"),
            maxEntries = 2
        )
        assertEquals(listOf("middle", "new"), NativeBackgroundNotificationCursor.parseSeenIds(merged).toList())
    }

    @Test
    fun unseenFiltersPreviouslyDeliveredRows() {
        val rows = listOf(
            JSONObject().put("id", "seen"),
            JSONObject().put("id", "fresh"),
            JSONObject().put("id", "")
        )
        val unseen = NativeBackgroundNotificationCursor.unseen(rows, setOf("seen"))
        assertEquals(1, unseen.size)
        assertEquals("fresh", unseen.single().optString("id"))
        assertTrue(NativeBackgroundNotificationCursor.unseen(emptyList(), emptySet()).isEmpty())
        assertFalse(unseen.any { it.optString("id") == "seen" })
    }
}
