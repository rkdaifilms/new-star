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
}
