package com.newstar.live

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeLiveInviteAlertPolicyTest {
    @Test
    fun onlyNewValidInviteIdsAreReturned() {
        val rows = listOf(
            JSONObject().put("id", "invite-a"),
            JSONObject().put("id", "invite-b"),
            JSONObject().put("id", "")
        )
        assertEquals(
            listOf("invite-b"),
            NativeLiveInviteAlertPolicy.unseen(rows, setOf("invite-a")).map { it.optString("id") }
        )
    }

    @Test
    fun seenIdsRoundTripAndRemainBounded() {
        val merged = NativeLiveInviteAlertPolicy.mergeSeenIds(setOf("old"), listOf("new", ""))
        assertEquals(setOf("old", "new"), NativeLiveInviteAlertPolicy.decode(NativeLiveInviteAlertPolicy.encode(merged)))
        val bounded = NativeLiveInviteAlertPolicy.mergeSeenIds((1..4).map { "id-$it" }.toSet(), listOf("id-5"), 3)
        assertEquals(3, bounded.size)
        assertTrue("id-5" in bounded)
        assertTrue("id-1" !in bounded)
    }

    @Test
    fun invalidStoredStateResetsSafely() {
        assertTrue(NativeLiveInviteAlertPolicy.decode("not-json").isEmpty())
    }
}
