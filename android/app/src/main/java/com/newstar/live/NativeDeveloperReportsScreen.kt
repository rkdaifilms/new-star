package com.newstar.live

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

data class NativeDeveloperReportsState(
    val checkingAccess: Boolean = true,
    val isOwner: Boolean = false,
    val loading: Boolean = false,
    val reports: List<JSONObject> = emptyList(),
    val busyReportId: String? = null,
    val error: String? = null,
    val message: String? = null
)

class NativeDeveloperReportsRepository {
    suspend fun load(session: NativeSession): List<JSONObject> {
        val access = NativeSupabase.invokeFunction(
            "admin-overview",
            JSONObject().put("action", "check_owner_access"),
            session
        )
        if (!access.optBoolean("ok") || !access.optBoolean("is_owner")) {
            throw SecurityException("Developer Owner access नहीं है। यह screen केवल verified owner account के लिए है।")
        }
        val response = NativeSupabase.invokeFunction(
            "admin-overview",
            JSONObject().put("action", "list_content_reports"),
            session
        )
        if (!response.optBoolean("ok")) {
            throw IllegalStateException(response.optString("error").ifBlank { "Reports लोड नहीं हुईं।" })
        }
        val rows = response.optJSONArray("reports") ?: JSONArray()
        return (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }
    }

    suspend fun review(session: NativeSession, reportId: String, status: String): JSONObject {
        require(status in setOf("reviewing", "actioned", "dismissed"))
        val response = NativeSupabase.invokeFunction(
            "admin-overview",
            JSONObject()
                .put("action", "review_content_report")
                .put("report_id", reportId)
                .put("status", status),
            session
        )
        if (!response.optBoolean("ok")) {
            throw IllegalStateException(response.optString("error").ifBlank { "Review save नहीं हुई।" })
        }
        return response.optJSONObject("report")
            ?: throw IllegalStateException("Server ने updated report वापस नहीं भेजी।")
    }
}

class NativeDeveloperReportsViewModel(
    private val repository: NativeDeveloperReportsRepository = NativeDeveloperReportsRepository()
) : ViewModel() {
    var state by mutableStateOf(NativeDeveloperReportsState())
        private set

    fun load(session: NativeSession) {
        if (state.loading) return
        state = state.copy(checkingAccess = true, loading = true, error = null, message = null)
        viewModelScope.launch {
            try {
                val reports = repository.load(session)
                state = NativeDeveloperReportsState(
                    checkingAccess = false,
                    isOwner = true,
                    reports = reports,
                    message = if (reports.isEmpty()) "अभी कोई content report नहीं है।" else null
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                state = NativeDeveloperReportsState(
                    checkingAccess = false,
                    isOwner = false,
                    error = error.message ?: "Developer reports लोड नहीं हुईं।"
                )
            }
        }
    }

    fun review(session: NativeSession, reportId: String, status: String) {
        if (state.busyReportId != null) return
        state = state.copy(busyReportId = reportId, error = null, message = null)
        viewModelScope.launch {
            try {
                val updated = repository.review(session, reportId, status)
                val reports = state.reports.map { row ->
                    if (row.optString("id") == reportId) updated else row
                }
                state = state.copy(
                    reports = reports,
                    busyReportId = null,
                    message = "Report status '@{status}' सेव हो गया और server audit दर्ज कर चुका है।"
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                state = state.copy(
                    busyReportId = null,
                    error = error.message ?: "Review save नहीं हुई।"
                )
            }
        }
    }
}

@Composable
fun NativeDeveloperReportsScreen(
    session: NativeSession,
    onBack: () -> Unit,
    viewModel: NativeDeveloperReportsViewModel = viewModel(key = "developer-reports-@{session.userId}")
) {
    val state = viewModel.state
    LaunchedEffect(session.userId) { viewModel.load(session) }

    Column(
        Modifier.fillMaxSize().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text("Developer · Content Safety", style = MaterialTheme.typography.titleLarge)
                Text("Owner-only report review · server-verified", style = MaterialTheme.typography.bodySmall)
            }
            OutlinedButton(onClick = onBack) { Text("Back") }
        }
        if (state.loading) {
            CircularProgressIndicator()
            Text(if (state.checkingAccess) "Owner access जाँचा जा रहा है…" else "Reports लोड हो रही हैं…")
        }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        state.message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        if (!state.loading && state.isOwner) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("@{state.reports.size} recent reports", style = MaterialTheme.typography.titleMedium)
                OutlinedButton(onClick = { viewModel.load(session) }, enabled = state.busyReportId == null) {
                    Text("Refresh")
                }
            }
            LazyColumn(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(state.reports, key = { it.optString("id") }) { report ->
                    val id = report.optString("id")
                    val busy = state.busyReportId == id
                    Card(Modifier.fillMaxWidth()) {
                        Column(
                            Modifier.fillMaxWidth().padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                "@{report.optString("target_type")} · @{report.optString("reason")}",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text("Target: @{report.optString("target_id")}", style = MaterialTheme.typography.bodySmall)
                            Text("Status: @{report.optString("status")}", style = MaterialTheme.typography.labelLarge)
                            val details = report.optString("details")
                            if (details.isNotBlank() && details != "null") Text(details)
                            Text("Reporter: @{report.optString("reporter_id")}", style = MaterialTheme.typography.bodySmall)
                            Text("Submitted: @{report.optString("created_at")}", style = MaterialTheme.typography.bodySmall)
                            if (busy) {
                                CircularProgressIndicator()
                            } else {
                                Text("Review decision", style = MaterialTheme.typography.labelLarge)
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    OutlinedButton(onClick = { viewModel.review(session, id, "reviewing") }) { Text("Reviewing") }
                                    Button(onClick = { viewModel.review(session, id, "actioned") }) { Text("Actioned") }
                                    OutlinedButton(onClick = { viewModel.review(session, id, "dismissed") }) { Text("Dismiss") }
                                }
                            }
                        }
                    }
                }
            }
            Text(
                "Status update audit होता है। 'Actioned' status अपने-आप content delete या account suspend नहीं करता।",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else if (!state.loading && !state.isOwner && state.error == null) {
            Text("यह सुविधा केवल verified Developer Owner के लिए उपलब्ध है।")
        }
    }
}
