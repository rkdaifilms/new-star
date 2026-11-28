package com.newstar.live

import android.net.Uri
import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Test

class NativeOfflineDownloadsTest {
    @Test
    fun identifiesHlsPlaylistEvenWithSignedQueryParameters() {
        val uri = Uri.parse("https://stream.example/manifest/video.m3u8?token=secret")
        assertEquals(MimeTypes.APPLICATION_M3U8, NativeOfflineDownloads.mimeTypeFor(uri))
    }

    @Test
    fun identifiesDashManifest() {
        val uri = Uri.parse("https://stream.example/manifest/video.mpd?token=secret")
        assertEquals(MimeTypes.APPLICATION_MPD, NativeOfflineDownloads.mimeTypeFor(uri))
    }

    @Test
    fun identifiesProgressiveMp4() {
        val uri = Uri.parse("https://cdn.example/videos/clip.mp4?signature=abc")
        assertEquals(MimeTypes.VIDEO_MP4, NativeOfflineDownloads.mimeTypeFor(uri))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnknownFormatInsteadOfNamingItMp4() {
        NativeOfflineDownloads.mimeTypeFor(Uri.parse("https://cdn.example/video/123?token=abc"))
    }
}
