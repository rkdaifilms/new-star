package com.newstar.live

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
        assertEquals(
            listOf("middle", "new"),
            NativeBackgroundNotificationCursor.parseSeenIds(merged).toList()
        )
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

    @Test
    fun cursorRoundTripsTimestampAndId() {
        val cursor = NativeBackgroundNotificationCursor.Cursor(
            "2026-11-28T12:00:00.123456+00:00",
            "00000000-0000-0000-0000-000000000123"
        )
        assertEquals(cursor, NativeBackgroundNotificationCursor.decode(
            NativeBackgroundNotificationCursor.encode(cursor)
        ))
    }

    @Test
    fun legacyTimestampCursorStartsAtMinimumIdToRecoverTimestampTies() {
        val cursor = NativeBackgroundNotificationCursor.decode("2026-11-28T12:00:00+00:00")
        assertNotNull(cursor)
        assertEquals(NativeBackgroundNotificationCursor.MIN_UUID, cursor?.id)
        assertEquals("2026-11-28T12:00:00+00:00", cursor?.createdAt)
    }

    @Test
    fun malformedJsonCursorIsRejected() {
        assertNull(NativeBackgroundNotificationCursor.decode("{broken-json"))
        assertNull(NativeBackgroundNotificationCursor.decode(""))
    }

    @Test
    fun cursorFilterUsesTimestampAndIdTieBreaker() {
        val cursor = NativeBackgroundNotificationCursor.Cursor(
            "2026-11-28T12:00:00+00:00",
            "00000000-0000-0000-0000-000000000123"
        )
        val filter = NativeBackgroundNotificationCursor.filter(cursor)
        assertTrue(filter.startsWith("or="))
        val decoded = android.net.Uri.decode(filter.removePrefix("or="))
        assertTrue(decoded.contains("created_at.gt.2026-11-28T12:00:00+00:00"))
        assertTrue(decoded.contains("created_at.eq.2026-11-28T12:00:00+00:00"))
        assertTrue(decoded.contains("id.gt.00000000-0000-0000-0000-000000000123"))
    }

    @Test
    fun cursorCanBeReadFromNotificationRow() {
        val row = JSONObject()
            .put("created_at", "2026-11-28T12:00:00+00:00")
            .put("id", "00000000-0000-0000-0000-000000000123")
        assertEquals(
            NativeBackgroundNotificationCursor.Cursor(
                "2026-11-28T12:00:00+00:00",
                "00000000-0000-0000-0000-000000000123"
            ),
            NativeBackgroundNotificationCursor.fromRow(row)
        )
    }
}
