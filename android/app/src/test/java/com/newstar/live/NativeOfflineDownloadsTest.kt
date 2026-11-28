package com.newstar.live

import org.junit.Assert.assertEquals
import org.junit.Test

class NativeOfflineDownloadsTest {
    @Test
    fun identifiesHlsPlaylistEvenWithSignedQueryParameters() {
        assertEquals(
            "application/x-mpegURL",
            NativeOfflineDownloads.mimeTypeFor("https://stream.example/manifest/video.m3u8?token=secret")
        )
    }

    @Test
    fun identifiesDashManifest() {
        assertEquals(
            "application/dash+xml",
            NativeOfflineDownloads.mimeTypeFor("https://stream.example/manifest/video.mpd?token=secret")
        )
    }

    @Test
    fun identifiesProgressiveMp4() {
        assertEquals(
            "video/mp4",
            NativeOfflineDownloads.mimeTypeFor("https://cdn.example/videos/clip.mp4?signature=abc")
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnknownFormatInsteadOfNamingItMp4() {
        NativeOfflineDownloads.mimeTypeFor("https://cdn.example/video/123?token=abc")
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsMalformedUrl() {
        NativeOfflineDownloads.mimeTypeFor("https://[invalid")
    }
}
