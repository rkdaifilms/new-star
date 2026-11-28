package com.newstar.live

internal object NativeCreatorImagePolicy {
    const val MAX_IMAGE_BYTES = 24 * 1024 * 1024
    const val MAX_BASE64_CHARS = 4 * ((MAX_IMAGE_BYTES + 2) / 3) + 16

    fun normalizeMimeType(value: String?): String = when (value?.substringBefore(';')?.trim()?.lowercase()) {
        "image/png" -> "image/png"
        "image/jpeg", "image/jpg" -> "image/jpeg"
        "image/webp" -> "image/webp"
        else -> throw IllegalArgumentException("AI ने unsupported image format लौटाया।")
    }

    fun extensionForMimeType(mimeType: String): String = when (normalizeMimeType(mimeType)) {
        "image/jpeg" -> "jpg"
        "image/webp" -> "webp"
        else -> "png"
    }

    fun previewSampleSize(width: Int, height: Int, maxDimension: Int): Int {
        require(width > 0 && height > 0) { "Image dimensions must be positive." }
        require(maxDimension > 0) { "Preview dimension must be positive." }
        var sample = 1
        while (width / (sample * 2) >= maxDimension || height / (sample * 2) >= maxDimension) sample *= 2
        return sample
    }
}
