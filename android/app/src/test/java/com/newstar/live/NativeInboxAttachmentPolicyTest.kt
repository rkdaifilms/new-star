package com.newstar.live

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream

class NativeInboxAttachmentPolicyTest {
    @Test
    fun acceptsOnlyPrivateBucketMimeTypes() {
        assertEquals("image/jpeg", NativeInboxAttachmentPolicy.normalizeMimeType("image/jpg"))
        assertEquals("audio/wav", NativeInboxAttachmentPolicy.normalizeMimeType("audio/x-wav"))
        assertEquals("video/quicktime", NativeInboxAttachmentPolicy.normalizeMimeType("video/quicktime"))
        assertThrows(IllegalArgumentException::class.java) {
            NativeInboxAttachmentPolicy.normalizeMimeType("application/zip")
        }
        assertThrows(IllegalArgumentException::class.java) {
            NativeInboxAttachmentPolicy.normalizeMimeType("image/svg+xml")
        }
    }

    @Test
    fun createsExtensionFromMimeRatherThanUserFilename() {
        assertEquals("photo.jpg", NativeInboxAttachmentPolicy.safeDisplayName("../../photo.exe", "image/jpeg"))
        assertEquals("clip.mov", NativeInboxAttachmentPolicy.safeDisplayName("clip.mp4", "video/quicktime"))
        assertEquals("attachment.pdf", NativeInboxAttachmentPolicy.safeDisplayName("...", "application/pdf"))
    }

    @Test
    fun readsSmallAttachmentsWithoutChangingBytes() {
        val expected = byteArrayOf(1, 2, 3, 4)
        val actual = NativeInboxAttachmentPolicy.readBounded(ByteArrayInputStream(expected), 4)
        assertArrayEquals(expected, actual)
    }

    @Test
    fun rejectsAttachmentsLargerThanConfiguredLimit() {
        assertThrows(IllegalArgumentException::class.java) {
            NativeInboxAttachmentPolicy.readBounded(ByteArrayInputStream(byteArrayOf(1, 2, 3, 4, 5)), 4)
        }
    }

    @Test
    fun rejectsEmptyAttachmentsAndInvalidLimits() {
        assertThrows(IllegalArgumentException::class.java) {
            NativeInboxAttachmentPolicy.readBounded(ByteArrayInputStream(byteArrayOf()))
        }
        assertThrows(IllegalArgumentException::class.java) {
            NativeInboxAttachmentPolicy.readBounded(ByteArrayInputStream(byteArrayOf(1)), 0)
        }
    }
}
