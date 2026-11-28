package com.newstar.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class NativeProfileVideoPolicyTest {
    @Test
    fun normalizesHashtagsAndRemovesDuplicates() {
        assertEquals(
            listOf("#nepal", "#newstar", "#creator"),
            NativeProfileVideoPolicy.normalizeTags("nepal, #newstar newstar CREATOR")
        )
    }

    @Test
    fun limitsHashtagsToThirty() {
        val tags = (1..35).joinToString(",") { "tag$it" }
        assertEquals(30, NativeProfileVideoPolicy.normalizeTags(tags).size)
    }

    @Test
    fun acceptsAllSupportedVisibilityModes() {
        listOf("public", "unlisted", "private").forEach { visibility ->
            NativeProfileVideoPolicy.validate("Caption", "#newstar", visibility)
        }
    }

    @Test
    fun rejectsUnsupportedVisibility() {
        assertThrows(IllegalArgumentException::class.java) {
            NativeProfileVideoPolicy.validate("Caption", "#newstar", "friends")
        }
    }

    @Test
    fun enforcesWebTitleAndHashtagLengthLimits() {
        NativeProfileVideoPolicy.validate("x".repeat(2000), "x".repeat(1000), "public")
        assertThrows(IllegalArgumentException::class.java) {
            NativeProfileVideoPolicy.validate("x".repeat(2001), "", "public")
        }
        assertThrows(IllegalArgumentException::class.java) {
            NativeProfileVideoPolicy.validate("Caption", "x".repeat(1001), "public")
        }
    }
}
