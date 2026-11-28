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
    @Test
    fun acceptsSupportedTextStylesAndPositions() {
        NativeVideoEditorPolicy.requireTextStyle(14, "#FFFFFF", "top")
        NativeVideoEditorPolicy.requireTextStyle(42, "#25F4EE", "center")
        NativeVideoEditorPolicy.requireTextStyle(70, "#FF4D8D", "bottom")
        assertEquals(0.72f, NativeVideoEditorPolicy.textPositionAnchorY("top"), 0f)
        assertEquals(0f, NativeVideoEditorPolicy.textPositionAnchorY("center"), 0f)
        assertEquals(-0.72f, NativeVideoEditorPolicy.textPositionAnchorY("bottom"), 0f)
    }

    @Test
    fun rejectsInvalidTextStylesAndPositions() {
        assertThrows(IllegalArgumentException::class.java) {
            NativeVideoEditorPolicy.requireTextStyle(13, "#FFFFFF", "top")
        }
        assertThrows(IllegalArgumentException::class.java) {
            NativeVideoEditorPolicy.requireTextStyle(71, "#FFFFFF", "center")
        }
        assertThrows(IllegalArgumentException::class.java) {
            NativeVideoEditorPolicy.requireTextStyle(24, "white", "center")
        }
        assertThrows(IllegalArgumentException::class.java) {
            NativeVideoEditorPolicy.requireTextStyle(24, "#GGGGGG", "center")
        }
        assertThrows(IllegalArgumentException::class.java) {
            NativeVideoEditorPolicy.textPositionAnchorY("middle")
        }
    }

    @Test
    fun acceptsDraggableTextCoordinatesInsidePreview() {
        NativeVideoEditorPolicy.requireTextCoordinates(0f, 0f)
        NativeVideoEditorPolicy.requireTextCoordinates(-0.85f, 0.85f)
        NativeVideoEditorPolicy.requireTextCoordinates(0.85f, -0.85f)
    }

    @Test
    fun rejectsDraggableTextCoordinatesOutsidePreview() {
        assertThrows(IllegalArgumentException::class.java) {
            NativeVideoEditorPolicy.requireTextCoordinates(-0.86f, 0f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            NativeVideoEditorPolicy.requireTextCoordinates(0f, 0.86f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            NativeVideoEditorPolicy.requireTextCoordinates(Float.NaN, 0f)
        }
    }

}
