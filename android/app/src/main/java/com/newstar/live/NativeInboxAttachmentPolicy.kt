package com.newstar.live

import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * Client-side inbox attachment contract mirrors the private message-media bucket.
 * MIME types are allow-listed, filenames are canonicalized, and streams are bounded
 * before allocating the upload byte array.
 */
internal object NativeInboxAttachmentPolicy {
    const val MAX_BYTES = 20 * 1024 * 1024
    private val extensions = linkedMapOf(
        "image/jpeg" to "jpg",
        "image/png" to "png",
        "image/webp" to "webp",
        "image/gif" to "gif",
        "image/heic" to "heic",
        "image/heif" to "heif",
        "video/mp4" to "mp4",
        "video/quicktime" to "mov",
        "video/webm" to "webm",
        "audio/mpeg" to "mp3",
        "audio/mp4" to "m4a",
        "audio/aac" to "aac",
        "audio/wav" to "wav",
        "application/pdf" to "pdf"
    )

    fun normalizeMimeType(value: String?): String {
        val raw = value.orEmpty().substringBefore(';').trim().lowercase()
        val normalized = when (raw) {
            "image/jpg" -> "image/jpeg"
            "audio/x-wav", "audio/wave" -> "audio/wav"
            else -> raw
        }
        require(normalized in extensions) {
            "यह attachment format समर्थित नहीं है। JPG, PNG, WebP, GIF, HEIC, HEIF, MP4, MOV, WebM, audio या PDF चुनें।"
        }
        return normalized
    }

    fun extensionForMimeType(value: String): String =
        extensions.getValue(normalizeMimeType(value))

    fun safeDisplayName(value: String?, mimeType: String): String {
        val extension = extensionForMimeType(mimeType)
        val leaf = value.orEmpty()
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .replace(Regex("[\\u0000-\\u001F\\u007F]"), "")
            .trim()
        val base = leaf.substringBeforeLast('.', leaf)
            .replace(Regex("[^\\p{L}\\p{N} _.-]"), "_")
            .replace(Regex("\\s+"), " ")
            .trim()
            .trim('.')
            .take(80)
            .trim()
            .trim('.')
            .ifBlank { "attachment" }
        return "$base.$extension"
    }

    fun readBounded(input: InputStream, maxBytes: Int = MAX_BYTES): ByteArray {
        require(maxBytes in 1..MAX_BYTES)
        val output = ByteArrayOutputStream(minOf(maxBytes, 64 * 1024))
        val buffer = ByteArray(64 * 1024)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            total += count
            require(total <= maxBytes) { "Attachment 20 MB से छोटी होनी चाहिए।" }
            output.write(buffer, 0, count)
        }
        require(total > 0) { "खाली attachment नहीं भेजी जा सकती।" }
        return output.toByteArray()
    }
}
