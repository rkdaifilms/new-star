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
        require(position in setOf("top", "center", "bottom")) {
            "Text position top, center या bottom होनी चाहिए।"
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
}
