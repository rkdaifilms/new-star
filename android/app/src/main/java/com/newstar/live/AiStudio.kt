package com.newstar.live

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

private data class AiChatLine(val role: String, val content: String)

@Composable
internal fun NativeAiStudio(accessToken: String) {
    var draft by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val lines = remember { mutableStateListOf<AiChatLine>() }
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxWidth()) {
        Text("AI Creator Studio", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Spacer(Modifier.height(6.dp))
        Text("वीडियो, स्क्रिप्ट, एडिटिंग, आइडिया और तकनीकी सवालों पर AI से बात करें।", color = Color.LightGray, fontSize = 13.sp)
        Spacer(Modifier.height(14.dp))
        if (lines.isEmpty()) {
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1B1B25)), shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text("नमस्ते! मैं New Star Creator AI हूँ।", color = Color.White, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Text("अपना सवाल लिखें—मैं उसी भाषा में जवाब दूँगा।", color = Color.LightGray, fontSize = 13.sp)
                }
            }
        }
        lines.forEach { line ->
            Spacer(Modifier.height(8.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = if (line.role == "user") Color(0xFF30202A) else Color(0xFF1B1B25)),
                shape = RoundedCornerShape(14.dp)
            ) {
                Column(Modifier.padding(13.dp)) {
                    Text(if (line.role == "user") "आप" else "New Star AI", color = if (line.role == "user") Color(0xFFFF7890) else Color(0xFF25F4EE), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Text(line.content, color = Color.White, fontSize = 14.sp)
                }
            }
        }
        if (sending) {
            Spacer(Modifier.height(10.dp))
            Row {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Color(0xFF25F4EE))
                Spacer(Modifier.width(8.dp))
                Text("AI जवाब तैयार कर रहा है…", color = Color.LightGray, fontSize = 12.sp)
            }
        }
        if (error.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(error, color = Color(0xFFFF8A8A), fontSize = 12.sp)
        }
        Spacer(Modifier.height(14.dp))
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it; error = "" },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("AI से कुछ भी पूछें…") },
            minLines = 2,
            maxLines = 5,
            enabled = !sending
        )
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = {
                val message = draft.trim()
                if (message.isNotEmpty() && !sending) {
                    draft = ""
                    lines.add(AiChatLine("user", message))
                    sending = true
                    error = ""
                    scope.launch {
                        try {
                            val history = lines.takeLast(10).map { it.role to it.content }
                            val answer = withContext(Dispatchers.IO) { requestNativeAi(accessToken, history) }
                            lines.add(AiChatLine("assistant", answer))
                        } catch (e: Exception) {
                            error = e.message ?: "AI से जवाब नहीं मिला। कृपया फिर कोशिश करें।"
                            if (lines.lastOrNull()?.role == "user") lines.removeAt(lines.lastIndex)
                            draft = message
                        } finally { sending = false }
                    }
                }
            },
            enabled = !sending && draft.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (sending) "जवाब आ रहा है…" else "AI को भेजें") }
    }
}

private fun requestNativeAi(accessToken: String, history: List<Pair<String, String>>): String {
    check(accessToken.isNotBlank()) { "AI इस्तेमाल करने के लिए पहले लॉगिन करें।" }
    val messages = JSONArray()
    history.forEach { (role, content) ->
        messages.put(JSONObject().put("role", role).put("content", content))
    }
    val payload = JSONObject().put("messages", messages)
    val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(75, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()
    val request = Request.Builder()
        .url("https://uzsusgoetrkujmofxcmb.supabase.co/functions/v1/ai-gateway")
        .post(payload.toString().toRequestBody("application/json".toMediaType()))
        .header("Origin", "https://new-star-git-new-star-repair-pass-1-rkdaifilms-8858.vercel.app")
        .header("apikey", "sb_publishable_Zz2hljevMsXBcK8c33UvFw_wuc2itVr")
        .header("Authorization", "Bearer $accessToken")
        .build()
    client.newCall(request).execute().use { response ->
        val raw = response.body?.string().orEmpty()
        val data = runCatching { JSONObject(raw) }.getOrElse { JSONObject() }
        if (!response.isSuccessful || !data.optBoolean("ok", false)) {
            val detail = data.optString("error").ifBlank { "AI सेवा ने अनुरोध स्वीकार नहीं किया (" + response.code + ")।" }
            throw IllegalStateException(detail)
        }
        return data.optString("answer").takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("AI ने खाली जवाब दिया।")
    }
}
