package com.newstar.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeLiveInviteAlertPolicyTest {
    @Test
    fun onlyNewValidInviteIdsAreReturned() {
        assertEquals(
            setOf("invite-b"),
            NativeLiveInviteAlertPolicy.unseenIds(
                listOf("invite-a", "invite-b", "", "invite-b"),
                setOf("invite-a")
            )
        )
    }

    @Test
    fun seenIdsRemainBoundedAndPreserveNewestIds() {
        val bounded = NativeLiveInviteAlertPolicy.mergeSeenIds(
            linkedSetOf("id-1", "id-2", "id-3", "id-4"),
            listOf("id-5", ""),
            3
        )
        assertEquals(3, bounded.size)
        assertTrue("id-5" in bounded)
        assertTrue("id-1" !in bounded)
    }

    @Test
    fun invalidStoredStateResetsSafely() {
        assertTrue(NativeLiveInviteAlertPolicy.decode("not-json").isEmpty())
    }
}
