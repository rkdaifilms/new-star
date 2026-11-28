package com.newstar.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class NativeVideoEditorPolicyTest {
    @Test
    fun acceptsOriginalAudioVolumeRange() {
        assertEquals(0f, NativeVideoEditorPolicy.requireSourceVolume(0f), 0f)
        assertEquals(0.4f, NativeVideoEditorPolicy.requireSourceVolume(0.4f), 0f)
        assertEquals(1f, NativeVideoEditorPolicy.requireSourceVolume(1f), 0f)
    }

    @Test
    fun rejectsInvalidOriginalAudioVolume() {
        assertThrows(IllegalArgumentException::class.java) {
            NativeVideoEditorPolicy.requireSourceVolume(-0.01f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            NativeVideoEditorPolicy.requireSourceVolume(1.01f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            NativeVideoEditorPolicy.requireSourceVolume(Float.NaN)
        }
    }

    @Test
    fun acceptsSupportedExportSpeedRange() {
        assertEquals(0.5f, NativeVideoEditorPolicy.requireSpeed(0.5f), 0f)
        assertEquals(1f, NativeVideoEditorPolicy.requireSpeed(1f), 0f)
        assertEquals(2f, NativeVideoEditorPolicy.requireSpeed(2f), 0f)
    }

    @Test
    fun rejectsUnsupportedExportSpeed() {
        assertThrows(IllegalArgumentException::class.java) {
            NativeVideoEditorPolicy.requireSpeed(0.25f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            NativeVideoEditorPolicy.requireSpeed(2.5f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            NativeVideoEditorPolicy.requireSpeed(Float.POSITIVE_INFINITY)
        }
    }
}
