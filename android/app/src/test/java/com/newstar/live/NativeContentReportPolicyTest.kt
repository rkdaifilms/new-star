package com.newstar.live

import org.junit.Assert.assertThrows
import org.junit.Test

class NativeContentReportPolicyTest {
    @Test
    fun acceptsSupportedTargetsAndReasons() {
        val targets = listOf("video", "comment", "user")
        val reasons = listOf(
            "copyright", "harassment", "spam", "scam",
            "nudity_or_sexual_content", "violence", "dangerous_content",
            "child_safety", "privacy", "other"
        )
        targets.forEach { target ->
            reasons.forEach { reason ->
                NativeContentReportPolicy.validate(target, "valid-target-id", reason, null)
            }
        }
    }

    @Test
    fun rejectsUnknownTargetType() {
        assertThrows(IllegalArgumentException::class.java) {
            NativeContentReportPolicy.validate("profile", "target", "spam", null)
        }
    }

    @Test
    fun rejectsBlankAndOversizedTargetIds() {
        assertThrows(IllegalArgumentException::class.java) {
            NativeContentReportPolicy.validate("video", " ", "spam", null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            NativeContentReportPolicy.validate("video", "x".repeat(241), "spam", null)
        }
    }

    @Test
    fun rejectsUnknownReasons() {
        assertThrows(IllegalArgumentException::class.java) {
            NativeContentReportPolicy.validate("video", "target", "fake_reason", null)
        }
    }

    @Test
    fun acceptsDetailsAtLimitAndRejectsBeyondLimit() {
        NativeContentReportPolicy.validate("comment", "comment-id", "harassment", "x".repeat(1200))
        assertThrows(IllegalArgumentException::class.java) {
            NativeContentReportPolicy.validate("comment", "comment-id", "harassment", "x".repeat(1201))
        }
    }
}
