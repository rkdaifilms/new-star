package com.newstar.live

import java.io.IOException

/**
 * Pure upload policy shared by WorkManager scheduling and the worker.
 * Keeping limits and retry rules here makes the behavior deterministic and unit-testable.
 */
object NativeVideoUploadPolicy {
    const val MAX_TITLE_LENGTH = 300
    const val MAX_DESCRIPTION_LENGTH = 3500
    const val MAX_CATEGORY_LENGTH = 100
    const val MAX_AFFILIATE_LENGTH = 1000
    const val MAX_LOCATION_LENGTH = 2500
    const val MAX_RETRY_ATTEMPTS = 4

    fun uniqueWorkName(userId: String): String {
        val normalized = userId.trim()
        require(normalized.isNotEmpty()) { "A user ID is required for a unique upload." }
        return "new-star-video-upload-$normalized"
    }

    fun bounded(value: String, maxLength: Int): String {
        require(maxLength > 0) { "Maximum length must be positive." }
        return value.take(maxLength)
    }

    fun shouldRetry(error: Throwable, runAttemptCount: Int): Boolean {
        if (runAttemptCount < 0 || runAttemptCount >= MAX_RETRY_ATTEMPTS) return false
        return generateSequence(error) { it.cause }.any { cause ->
            val message = cause.message.orEmpty()
            cause is IOException ||
                message.contains("network", ignoreCase = true) ||
                message.contains("connection", ignoreCase = true) ||
                message.contains("timeout", ignoreCase = true) ||
                message.contains("Cloudflare", ignoreCase = true) ||
                message.contains("offset", ignoreCase = true) ||
                message.contains("चार कोशिशों", ignoreCase = true) ||
                message.contains("resume check failed", ignoreCase = true) ||
                message.contains("JWT", ignoreCase = true) ||
                message.contains("token", ignoreCase = true) ||
                message.contains("401", ignoreCase = true)
        }
    }
}
