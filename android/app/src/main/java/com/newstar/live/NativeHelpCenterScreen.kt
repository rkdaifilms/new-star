package com.newstar.live

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

internal data class NativeHelpMessage(val role: String, val content: String)

data class NativeHelpCenterUiState(
    val messages: List<NativeHelpMessage> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null
)

class NativeHelpCenterRepository {
    suspend fun ask(session: NativeSession, messages: List<NativeHelpMessage>): String {
        require(messages.isNotEmpty() && messages.size <= 10) { "Help message history अमान्य है।" }
        val payload = JSONArray()
        messages.forEach { message ->
            require(message.role == "user" || message.role == "assistant") {
                "Help message role अमान्य है।"
            }
            require(message.content.isNotBlank() && message.content.length <= 8000) {
                "Message खाली है या 8,000 अक्षरों से लंबा है।"
            }
            payload.put(JSONObject().put("role", message.role).put("content", message.content))
        }
        require(messages.last().role == "user") { "पहले अपना सवाल भेजें।" }
        require(messages.sumOf { it.content.length } <= 20_000) { "Conversation बहुत लंबी है। नया chat शुरू करें।" }

        val response = NativeSupabase.invokeFunction(
            "ai-gateway",
            JSONObject().put("messages", payload).put("mode", "help"),
            session
        )
        if (!response.optBoolean("ok")) {
            throw IllegalStateException(response.optString("error").ifBlank { "Help Center AI अभी उपलब्ध नहीं है।" })
        }
        return response.optString("answer").trim().also {
            require(it.isNotBlank()) { "Help Center AI ने खाली जवाब दिया।" }
        }
    }
}

class NativeHelpCenterViewModel(
    private val repository: NativeHelpCenterRepository = NativeHelpCenterRepository()
) : ViewModel() {
    var uiState by mutableStateOf(NativeHelpCenterUiState())
        private set

    fun clearConversation() {
        if (!uiState.isLoading) uiState = NativeHelpCenterUiState()
    }

    fun send(session: NativeSession, text: String) {
        val message = text.trim()
        if (uiState.isLoading || message.isBlank()) return
        if (message.length > 8000) {
            uiState = uiState.copy(error = "सवाल 8,000 अक्षरों से छोटा रखें।")
            return
        }
        val history = (uiState.messages + NativeHelpMessage("user", message)).takeLast(10)
        submit(session, history)
    }

    fun retry(session: NativeSession) {
        if (uiState.isLoading) return
        val lastUserIndex = uiState.messages.indexOfLast { it.role == "user" }
        if (lastUserIndex < 0) return
        val retryHistory = uiState.messages.take(lastUserIndex + 1).takeLast(10)
        submit(session, retryHistory)
    }

    private fun submit(session: NativeSession, history: List<NativeHelpMessage>) {
        uiState = uiState.copy(messages = history, isLoading = true, error = null)
        viewModelScope.launch {
            try {
                val answer = repository.ask(session, history.takeLast(10))
                uiState = uiState.copy(
                    messages = (history + NativeHelpMessage("assistant", answer)).takeLast(10),
                    isLoading = false,
                    error = null
                )
            } catch (error: Exception) {
                uiState = uiState.copy(
                    isLoading = false,
                    error = error.message ?: "Help Center AI का जवाब नहीं मिला। फिर कोशिश करें।"
                )
            }
        }
    }
}

@Composable
fun NativeHelpCenterScreen(session: NativeSession) {
    val vm: NativeHelpCenterViewModel = viewModel(key = "help-center-${session.userId}")
    val state = vm.uiState
    var draft by remember(session.userId) { mutableStateOf("") }

    Column(
        Modifier.fillMaxSize().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text("New Star Help Center", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "Account support · Safety · Reports · Warnings",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = { vm.clearConversation(); draft = "" }, enabled = !state.isLoading) {
                Text("New chat")
            }
        }
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text(
                "यह support assistant है, Creator Studio नहीं। यह report लिखने और app के steps समझाने में मदद करता है; किसी account पर खुद warning, ban या content removal नहीं करता। Password, OTP या API key कभी साझा न करें।",
                Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodySmall
            )
        }
        if (state.messages.isEmpty()) {
            Text("आप किस चीज़ में मदद चाहते हैं?", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AssistChip(onClick = { draft = "मेरा account login नहीं हो रहा। मुझे सुरक्षित तरीके से क्या जाँचना चाहिए?" }, label = { Text("Login help") })
                AssistChip(onClick = { draft = "New Star पर किसी video या comment की report कैसे करूँ?" }, label = { Text("Report") })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AssistChip(onClick = { draft = "मेरे upload में समस्या आ रही है। कौन-से troubleshooting steps अपनाऊँ?" }, label = { Text("Upload issue") })
                AssistChip(onClick = { draft = "मुझे New Star की privacy और blocked accounts settings समझाएँ।" }, label = { Text("Privacy") })
            }
        }
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            itemsIndexed(state.messages, key = { index, item -> "${index}-${item.role}" }) { _, item ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (item.role == "user") MaterialTheme.colorScheme.secondaryContainer
                        else MaterialTheme.colorScheme.surface
                    )
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            if (item.role == "user") "You" else "New Star Help Center AI",
                            style = MaterialTheme.typography.labelMedium
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(item.content, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            if (state.isLoading) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator()
                        Text("आपके सवाल का जवाब तैयार हो रहा है…")
                    }
                }
            }
        }
        state.error?.let { message ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Column(Modifier.padding(10.dp)) {
                    Text(message, color = MaterialTheme.colorScheme.onErrorContainer)
                    TextButton(
                        onClick = { vm.retry(session) },
                        enabled = !state.isLoading && state.messages.lastOrNull()?.role == "user"
                    ) { Text("Retry") }
                }
            }
        }
        OutlinedTextField(
            value = draft,
            onValueChange = { if (it.length <= 8000) draft = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Account या app support के बारे में पूछें") },
            minLines = 2,
            maxLines = 4,
            enabled = !state.isLoading
        )
        Button(
            onClick = {
                val question = draft.trim()
                vm.send(session, question)
                if (question.isNotBlank()) draft = ""
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !state.isLoading && draft.isNotBlank()
        ) {
            Text(if (state.isLoading) "Please wait…" else "Send to Help Center")
        }
    }
}
