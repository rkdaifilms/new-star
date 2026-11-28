package com.newstar.live

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeNotificationPreferencesTest {
    @Test
    fun recognizesSupportedNotificationTypes() {
        assertTrue(NativeNotificationPreferences.category("new_follower", "") == NativeNotificationCategory.FOLLOW)
        assertTrue(NativeNotificationPreferences.category("video_liked", "") == NativeNotificationCategory.LIKE)
        assertTrue(NativeNotificationPreferences.category("new_comment", "") == NativeNotificationCategory.COMMENT)
        assertTrue(NativeNotificationPreferences.category("direct_message", "") == NativeNotificationCategory.MESSAGE)
    }

    @Test
    fun recognizesLegacyMessageTextWhenTypeIsGeneric() {
        assertTrue(NativeNotificationPreferences.category("activity", "Alex followed you") == NativeNotificationCategory.FOLLOW)
        assertTrue(NativeNotificationPreferences.category("activity", "Someone liked your video") == NativeNotificationCategory.LIKE)
        assertTrue(NativeNotificationPreferences.category("activity", "Someone commented on your video") == NativeNotificationCategory.COMMENT)
        assertTrue(NativeNotificationPreferences.category("activity", "You have a new message") == NativeNotificationCategory.MESSAGE)
    }

    @Test
    fun eachPreferenceOnlySuppressesItsOwnCategory() {
        val state = NativeNotificationPreferenceState(
            follows = false,
            likes = true,
            comments = false,
            messages = true
        )
        assertFalse(NativeNotificationPreferences.isEnabled("follow", "", state))
        assertTrue(NativeNotificationPreferences.isEnabled("like", "", state))
        assertFalse(NativeNotificationPreferences.isEnabled("comment", "", state))
        assertTrue(NativeNotificationPreferences.isEnabled("message", "", state))
    }

    @Test
    fun unknownNotificationTypesRemainEnabled() {
        val allDisabled = NativeNotificationPreferenceState(false, false, false, false)
        assertTrue(NativeNotificationPreferences.isEnabled("campaign_approved", "Campaign approved", allDisabled))
    }
}
