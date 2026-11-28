package com.newstar.live

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

internal object NativeProfileVideoPolicy {
    fun normalizeTags(raw: String): List<String> {
        return raw.split(Regex("[,\\s]+"))
            .map(String::trim)
            .filter(String::isNotBlank)
            .map { if (it.startsWith("#")) it else "#$it" }
            .distinctBy { it.lowercase() }
            .take(30)
    }

    fun validate(caption: String, visibility: String) {
        require(caption.length <= 3000) { "Title 3,000 अक्षरों से छोटा रखें।" }
        require(visibility in setOf("public", "unlisted", "private")) {
            "Video privacy विकल्प अमान्य है।"
        }
    }

    fun storagePath(videoUrl: String, userId: String): String? {
        if (videoUrl.isBlank() || userId.isBlank()) return null
        val rawPath = when {
            videoUrl.startsWith("storage://videos/") ->
                videoUrl.removePrefix("storage://videos/")
            else -> {
                val uri = runCatching { Uri.parse(videoUrl) }.getOrNull() ?: return null
                val path = uri.path.orEmpty()
                val markers = listOf(
                    "/storage/v1/object/public/videos/",
                    "/storage/v1/object/sign/videos/",
                    "/storage/v1/object/videos/"
                )
                val marker = markers.firstOrNull { path.contains(it) } ?: return null
                path.substringAfter(marker)
            }
        }
        val decoded = runCatching { Uri.decode(rawPath) }.getOrDefault(rawPath)
        val segments = decoded.split("/")
        if (segments.size < 2 || segments.first() != userId ||
            segments.any { it.isBlank() || it == "." || it == ".." }) return null
        return decoded
    }
}

data class NativeProfileVideoManagementUiState(
    val busy: Boolean = false,
    val error: String? = null,
    val message: String? = null,
    val updatedVideo: JSONObject? = null,
    val deletedVideoId: String? = null
)

class NativeProfileVideoManagementRepository {
    suspend fun update(
        session: NativeSession,
        videoId: String,
        caption: String,
        rawTags: String,
        visibility: String
    ): JSONObject {
        NativeProfileVideoPolicy.validate(caption, visibility)
        require(videoId.isNotBlank()) { "Video ID उपलब्ध नहीं है।" }
        val body = JSONObject()
            .put("caption", caption.trim())
            .put("tags", JSONArray(NativeProfileVideoPolicy.normalizeTags(rawTags)))
            .put("visibility", visibility)
        val response = NativeSupabase.patch(
            "/rest/v1/videos?id=eq.${Uri.encode(videoId)}&user_id=eq.${Uri.encode(session.userId)}",
            body,
            session
        )
        val rows = JSONArray(response)
        require(rows.length() == 1) { "Video update नहीं हुआ। Owner permission जाँचें।" }
        return rows.getJSONObject(0)
    }

    suspend fun delete(
        session: NativeSession,
        video: JSONObject
    ): String? {
        val videoId = video.optString("id")
        require(videoId.isNotBlank()) { "Video ID उपलब्ध नहीं है।" }
        val response = NativeSupabase.deleteReturning(
            "/rest/v1/videos?id=eq.${Uri.encode(videoId)}&user_id=eq.${Uri.encode(session.userId)}",
            session
        )
        val rows = JSONArray(response)
        require(rows.length() == 1) { "Video delete नहीं हुआ। Owner permission जाँचें।" }

        val path = NativeProfileVideoPolicy.storagePath(video.optString("video_url"), session.userId)
            ?: return null
        return try {
            NativeSupabase.removeVideoObject(path, session)
            null
        } catch (error: Exception) {
            "Video हट गया, लेकिन पुरानी media file की cleanup पूरी नहीं हुई: ${error.message ?: "Storage error"}"
        }
    }
}

class NativeProfileVideoManagementViewModel(
    private val repository: NativeProfileVideoManagementRepository = NativeProfileVideoManagementRepository()
) : ViewModel() {
    var uiState by mutableStateOf(NativeProfileVideoManagementUiState())
        private set

    fun clearFeedback() {
        if (!uiState.busy) uiState = NativeProfileVideoManagementUiState()
    }

    fun update(
        session: NativeSession,
        videoId: String,
        caption: String,
        tags: String,
        visibility: String
    ) {
        if (uiState.busy) return
        uiState = NativeProfileVideoManagementUiState(busy = true)
        viewModelScope.launch {
            try {
                val updated = repository.update(session, videoId, caption, tags, visibility)
                uiState = NativeProfileVideoManagementUiState(
                    message = "Title, hashtags और privacy सेव हो गए।",
                    updatedVideo = updated
                )
            } catch (error: Exception) {
                uiState = NativeProfileVideoManagementUiState(
                    error = error.message ?: "Video details सेव नहीं हुए।"
                )
            }
        }
    }

    fun delete(session: NativeSession, video: JSONObject) {
        if (uiState.busy) return
        uiState = NativeProfileVideoManagementUiState(busy = true)
        viewModelScope.launch {
            try {
                val cleanupWarning = repository.delete(session, video)
                uiState = NativeProfileVideoManagementUiState(
                    message = cleanupWarning ?: "Video delete हो गया।",
                    deletedVideoId = video.optString("id")
                )
            } catch (error: Exception) {
                uiState = NativeProfileVideoManagementUiState(
                    error = error.message ?: "Video delete नहीं हुआ।"
                )
            }
        }
    }
}
