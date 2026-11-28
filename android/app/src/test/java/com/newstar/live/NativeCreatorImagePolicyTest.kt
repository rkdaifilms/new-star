package com.newstar.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class NativeCreatorImagePolicyTest {
    @Test fun normalizesSupportedImageMimeTypes() {
        assertEquals("image/png", NativeCreatorImagePolicy.normalizeMimeType("image/png"))
        assertEquals("image/jpeg", NativeCreatorImagePolicy.normalizeMimeType("image/jpeg; charset=binary"))
        assertEquals("image/webp", NativeCreatorImagePolicy.normalizeMimeType("image/webp"))
    }

    @Test fun rejectsUnsupportedMimeTypes() {
        assertThrows(IllegalArgumentException::class.java) { NativeCreatorImagePolicy.normalizeMimeType("text/html") }
    }

    @Test fun choosesPreviewSampleSizeWithoutUpscaling() {
        assertEquals(1, NativeCreatorImagePolicy.previewSampleSize(1280, 720, 1600))
        assertEquals(2, NativeCreatorImagePolicy.previewSampleSize(3840, 2160, 1600))
        assertEquals(4, NativeCreatorImagePolicy.previewSampleSize(7680, 4320, 1600))
    }

    @Test fun mapsMimeTypesToGalleryExtensions() {
        assertEquals("jpg", NativeCreatorImagePolicy.extensionForMimeType("image/jpeg"))
        assertEquals("webp", NativeCreatorImagePolicy.extensionForMimeType("image/webp"))
        assertEquals("png", NativeCreatorImagePolicy.extensionForMimeType("image/png"))
    }
}
