package com.newstar.live

/**
 * Validation shared by the native editor UI and export pipeline.
 * Audio gains are deliberately bounded to the 0..100% UI range.
 */
internal object NativeVideoEditorPolicy {
    fun requireSourceVolume(volume: Float): Float {
        require(volume.isFinite() && volume in 0f..1f) {
            "Original audio volume 0 से 100% के बीच होना चाहिए।"
        }
        return volume
    }

    fun requireSpeed(speed: Float): Float {
        require(speed.isFinite() && speed in 0.5f..2f) {
            "वीडियो speed 0.5x से 2x के बीच रखें।"
        }
        return speed
    }

    fun requireTextStyle(sizePx: Int, colorHex: String, position: String) {
        require(sizePx in 14..70) { "Text size 14 से 70 px के बीच रखें।" }
        require(Regex("^#[0-9A-Fa-f]{6}$").matches(colorHex)) {
            "Text color valid #RRGGBB format में होना चाहिए।"
        }
        require(position in setOf("top", "center", "bottom", "custom")) {
            "Text position top, center, bottom या custom होनी चाहिए।"
        }
    }

    fun textPositionAnchorY(position: String): Float {
        require(position in setOf("top", "center", "bottom")) {
            "Text position top, center या bottom होनी चाहिए।"
        }
        return when (position) {
            "top" -> 0.72f
            "bottom" -> -0.72f
            else -> 0f
        }
    }

    fun videoQualityLabel(width: Int, height: Int): String {
        require(width > 0 && height > 0) { "Video resolution positive होनी चाहिए।" }
        return when {
            width >= 3840 || height >= 3840 -> "4K / UHD"
            width >= 2560 || height >= 2560 -> "1440p / QHD"
            width >= 1920 || height >= 1920 -> "1080p / Full HD"
            width >= 1280 || height >= 1280 -> "720p / HD"
            else -> "SD"
        }
    }

    fun videoAspectRatioLabel(width: Int, height: Int): String {
        require(width > 0 && height > 0) { "Video resolution positive होनी चाहिए।" }
        val ratio = width.toDouble() / height.toDouble()
        return when {
            ratio in 0.55..0.58 -> "9:16 · Vertical"
            ratio in 0.74..0.76 -> "3:4 · Portrait"
            ratio in 0.99..1.01 -> "1:1 · Square"
            ratio in 1.32..1.35 -> "4:3 · Landscape"
            ratio in 1.76..1.79 -> "16:9 · Widescreen"
            ratio > 1.0 -> "Landscape · " + "%.2f".format(java.util.Locale.ROOT, ratio) + ":1"
            else -> "Portrait · 1:" + "%.2f".format(java.util.Locale.ROOT, 1.0 / ratio)
        }
    }

    fun subtitleWindow(
        startSeconds: Int,
        endSeconds: Int,
        trimStartMs: Long,
        trimEndMs: Long
    ): Pair<Long, Long>? {
        require(startSeconds >= 0 && endSeconds > startSeconds) {
            "Subtitle का End time, Start time से बड़ा होना चाहिए।"
        }
        require(trimStartMs >= 0L && trimEndMs > trimStartMs) {
            "Trim interval valid होना चाहिए।"
        }
        val startMs = maxOf(startSeconds.toLong() * 1000L, trimStartMs)
        val endMs = minOf(endSeconds.toLong() * 1000L, trimEndMs)
        if (endMs <= startMs) return null
        return (startMs - trimStartMs) to (endMs - trimStartMs)
    }

    fun requireTextCoordinates(x: Float, y: Float) {
        require(x.isFinite() && y.isFinite() && x in -0.85f..0.85f && y in -0.85f..0.85f) {
            "Text coordinates preview bounds के अंदर होने चाहिए।"
        }
    }
}
