package com.newstar.live

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeVideoUploadPolicyTest {
    @Test
    fun uniqueWorkNameIsScopedToNormalizedUserId() {
        assertEquals("new-star-video-upload-user-123", NativeVideoUploadPolicy.uniqueWorkName(" user-123 "))
    }

    @Test(expected = IllegalArgumentException::class)
    fun uniqueWorkNameRejectsBlankUserId() {
        NativeVideoUploadPolicy.uniqueWorkName("  ")
    }

    @Test
    fun metadataIsBoundedToWorkManagerSafeLimits() {
        assertEquals(300, NativeVideoUploadPolicy.bounded("x".repeat(500), NativeVideoUploadPolicy.MAX_TITLE_LENGTH).length)
        assertEquals(3500, NativeVideoUploadPolicy.bounded("x".repeat(4000), NativeVideoUploadPolicy.MAX_DESCRIPTION_LENGTH).length)
        assertEquals(100, NativeVideoUploadPolicy.bounded("x".repeat(150), NativeVideoUploadPolicy.MAX_CATEGORY_LENGTH).length)
        assertEquals(1000, NativeVideoUploadPolicy.bounded("x".repeat(1200), NativeVideoUploadPolicy.MAX_AFFILIATE_LENGTH).length)
        assertEquals(2500, NativeVideoUploadPolicy.bounded("x".repeat(3000), NativeVideoUploadPolicy.MAX_LOCATION_LENGTH).length)
    }

    @Test
    fun ioErrorsAreRetriedOnlyWithinAttemptBudget() {
        val error = IOException("socket disconnected")
        assertTrue(NativeVideoUploadPolicy.shouldRetry(error, 0))
        assertTrue(NativeVideoUploadPolicy.shouldRetry(error, 3))
        assertFalse(NativeVideoUploadPolicy.shouldRetry(error, 4))
    }

    @Test
    fun transientCloudflareAndAuthErrorsAreRetried() {
        assertTrue(NativeVideoUploadPolicy.shouldRetry(IllegalStateException("Cloudflare offset mismatch"), 1))
        assertTrue(NativeVideoUploadPolicy.shouldRetry(IllegalStateException("JWT token expired 401"), 2))
        assertTrue(NativeVideoUploadPolicy.shouldRetry(IllegalStateException("resume check failed"), 2))
    }

    @Test
    fun permanentValidationErrorsAreNotRetried() {
        assertFalse(NativeVideoUploadPolicy.shouldRetry(IllegalArgumentException("Unsupported video format"), 0))
        assertFalse(NativeVideoUploadPolicy.shouldRetry(IllegalStateException("Login account missing"), 0))
    }

    @Test
    fun invalidAttemptCountsAreNotRetried() {
        assertFalse(NativeVideoUploadPolicy.shouldRetry(IOException("network down"), -1))
    }
}
