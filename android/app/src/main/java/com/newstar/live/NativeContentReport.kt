package com.newstar.live

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import org.json.JSONObject

data class NativeContentReportUiState(
    val isSubmitting: Boolean = false,
    val isSubmitted: Boolean = false,
    val error: String? = null
)

internal object NativeContentReportPolicy {
    private val targetTypes = setOf("video", "comment", "user")
    private val reasons = setOf(
        "copyright", "harassment", "spam", "scam",
        "nudity_or_sexual_content", "violence", "dangerous_content",
        "child_safety", "privacy", "other"
    )

    fun validate(targetType: String, targetId: String, reason: String, details: String?) {
        require(targetType in targetTypes) { "Report target type अमान्य है।" }
        require(targetId.isNotBlank() && targetId.length <= 240) {
            "Report target उपलब्ध नहीं है।"
        }
        require(reason in reasons) { "Report reason अमान्य है।" }
        require(details == null || details.length <= 1200) {
            "अतिरिक्त जानकारी 1,200 अक्षरों से कम रखें।"
        }
    }
}

class NativeContentReportRepository {
    suspend fun submit(
        session: NativeSession,
        targetType: String,
        targetId: String,
        reason: String,
        details: String?
    ) {
        NativeContentReportPolicy.validate(targetType, targetId, reason, details)
        NativeSupabase.post(
            "/rest/v1/content_reports",
            JSONObject()
                .put("reporter_id", session.userId)
                .put("target_type", targetType)
                .put("target_id", targetId)
                .put("reason", reason)
                .put("details", details),
            session
        )
    }
}

class NativeContentReportViewModel(
    private val repository: NativeContentReportRepository = NativeContentReportRepository()
) : ViewModel() {
    var uiState by mutableStateOf(NativeContentReportUiState())
        private set

    fun reset() {
        if (!uiState.isSubmitting) uiState = NativeContentReportUiState()
    }

    fun submit(
        session: NativeSession,
        targetType: String,
        targetId: String,
        reason: String,
        details: String?
    ) {
        if (uiState.isSubmitting) return
        uiState = NativeContentReportUiState(isSubmitting = true)
        viewModelScope.launch {
            try {
                repository.submit(session, targetType, targetId, reason, details)
                uiState = NativeContentReportUiState(isSubmitted = true)
            } catch (error: Exception) {
                uiState = NativeContentReportUiState(
                    error = error.message ?: "Report भेजी नहीं जा सकी। कृपया फिर कोशिश करें।"
                )
            }
        }
    }
}
