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
        NativeVideoEditorPolicy.requireTextStyle(24, "#FFFFFF", "custom")
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

    @Test
    fun formatsCommonVideoAspectRatiosAndQualities() {
        assertEquals("9:16 · Vertical", NativeVideoEditorPolicy.videoAspectRatioLabel(1080, 1920))
        assertEquals("16:9 · Widescreen", NativeVideoEditorPolicy.videoAspectRatioLabel(1920, 1080))
        assertEquals("1:1 · Square", NativeVideoEditorPolicy.videoAspectRatioLabel(1080, 1080))
        assertEquals("4K / UHD", NativeVideoEditorPolicy.videoQualityLabel(3840, 2160))
        assertEquals("1080p / Full HD", NativeVideoEditorPolicy.videoQualityLabel(1920, 1080))
        assertEquals("720p / HD", NativeVideoEditorPolicy.videoQualityLabel(1280, 720))
        assertEquals("SD", NativeVideoEditorPolicy.videoQualityLabel(640, 480))
    }

    @Test
    fun rejectsInvalidVideoDimensionsForMetadata() {
        assertThrows(IllegalArgumentException::class.java) {
            NativeVideoEditorPolicy.videoAspectRatioLabel(0, 1080)
        }
        assertThrows(IllegalArgumentException::class.java) {
            NativeVideoEditorPolicy.videoQualityLabel(1920, 0)
        }
    }

    @Test
    fun mapsSubtitleWindowToTrimmedClipTimeline() {
        assertEquals(1_000L to 4_000L, NativeVideoEditorPolicy.subtitleWindow(3, 6, 2_000L, 7_000L))
        assertEquals(0L to 2_000L, NativeVideoEditorPolicy.subtitleWindow(1, 4, 2_000L, 7_000L))
        assertEquals(3_000L to 5_000L, NativeVideoEditorPolicy.subtitleWindow(5, 9, 2_000L, 7_000L))
    }

    @Test
    fun returnsNoSubtitleWhenItDoesNotOverlapTrim() {
        assertEquals(null, NativeVideoEditorPolicy.subtitleWindow(0, 1, 2_000L, 7_000L))
        assertEquals(null, NativeVideoEditorPolicy.subtitleWindow(8, 9, 2_000L, 7_000L))
    }

    @Test
    fun rejectsInvalidSubtitleAndTrimIntervals() {
        assertThrows(IllegalArgumentException::class.java) {
            NativeVideoEditorPolicy.subtitleWindow(4, 4, 0L, 10_000L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            NativeVideoEditorPolicy.subtitleWindow(0, 4, -1L, 10_000L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            NativeVideoEditorPolicy.subtitleWindow(0, 4, 10_000L, 10_000L)
        }
    }

}
