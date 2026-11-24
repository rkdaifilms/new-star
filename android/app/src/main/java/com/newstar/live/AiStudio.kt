package com.newstar.live

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.Manifest
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.media.MediaRecorder
import java.io.File
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import java.util.Locale
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.Image
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
    val context = LocalContext.current
    var listening by remember { mutableStateOf(false) }
    var voiceProcessing by remember { mutableStateOf(false) }
    var voiceStatus by remember { mutableStateOf("") }
    var recorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var recordingFile by remember { mutableStateOf<File?>(null) }
    var imageMode by remember { mutableStateOf(false) }
    var generatedImage by remember { mutableStateOf<Bitmap?>(null) }

    fun startVoiceRecording() {
        val file = File(context.cacheDir, "new-star-voice-${System.currentTimeMillis()}.m4a")
        val active = MediaRecorder()
        try {
            active.setAudioSource(MediaRecorder.AudioSource.MIC)
            active.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            active.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            active.setAudioSamplingRate(16000)
            active.setAudioEncodingBitRate(64000)
            active.setAudioChannels(1)
            active.setMaxDuration(120000)
            active.setOutputFile(file.absolutePath)
            active.setOnInfoListener { _, what, _ ->
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) {
                    voiceStatus = "दो मिनट की रिकॉर्डिंग सीमा पूरी हुई — अब टेक्स्ट तैयार होगा।"
                }
            }
            active.prepare()
            active.start()
            recorder = active
            recordingFile = file
            listening = true
            voiceStatus = "🔴 रिकॉर्डिंग चालू है — बोलें, फिर रिकॉर्डिंग रोकें।"
        } catch (failure: Exception) {
            runCatching { active.reset(); active.release() }
            runCatching { file.delete() }
            listening = false
            voiceStatus = "माइक शुरू नहीं हुआ: " + (failure.message ?: "Android microphone error")
        }
    }

    fun stopVoiceRecording() {
        val active = recorder
        val file = recordingFile
        recorder = null
        recordingFile = null
        listening = false
        if (active == null || file == null) {
            voiceStatus = "कोई सक्रिय रिकॉर्डिंग नहीं मिली।"
            return
        }
        val stopped = runCatching { active.stop() }.isSuccess
        runCatching { active.reset(); active.release() }
        if (!stopped || !file.exists() || file.length() < 256L) {
            runCatching { file.delete() }
            voiceStatus = "रिकॉर्डिंग खाली या अधूरी है। कृपया फिर बोलें।"
            return
        }
        voiceProcessing = true
        voiceStatus = "रिकॉर्डिंग सुरक्षित है — AI उसे टेक्स्ट में बदल रहा है…"
        scope.launch {
            try {
                val transcript = withContext(Dispatchers.IO) {
                    requestNativeTranscription(accessToken, file.readBytes(), "audio/mp4")
                }
                draft = listOf(draft.trim(), transcript.trim()).filter { it.isNotBlank() }.joinToString(" ")
                error = ""
                voiceStatus = "आवाज़ टेक्स्ट बॉक्स में लिख दी गई है। अब Send दबाएँ।"
            } catch (failure: Exception) {
                voiceStatus = failure.message ?: "आवाज़ को टेक्स्ट में नहीं बदला जा सका।"
            } finally {
                runCatching { file.delete() }
                voiceProcessing = false
            }
        }
    }

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startVoiceRecording()
        else voiceStatus = "AI voice input के लिए microphone permission आवश्यक है।"
    }

    DisposableEffect(Unit) {
        onDispose {
            runCatching { recorder?.stop(); recorder?.reset(); recorder?.release() }
            recorder = null
            recordingFile?.let { runCatching { it.delete() } }
            recordingFile = null
        }
    }

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
                    Text("कुछ भी पूछें, या तस्वीर बनाने को कहें। माइक दबाकर बोलें—रिकॉर्डिंग की स्थिति नीचे साफ़ दिखेगी।", color = Color.LightGray, fontSize = 13.sp)
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
        generatedImage?.let { bitmap ->
            Spacer(Modifier.height(12.dp))
            Image(bitmap = bitmap.asImageBitmap(), contentDescription = "AI द्वारा बनाई गई तस्वीर", modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp))
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
        OutlinedButton(
            onClick = { imageMode = !imageMode; generatedImage = null },
            enabled = !sending,
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (imageMode) "🖼️ तस्वीर बनाने का मोड चालू है" else "🖼️ तस्वीर बनाएँ") }
        OutlinedButton(
            onClick = {
                if (listening) {
                    voiceStatus = "रिकॉर्डिंग रोकी जा रही है…"
                    stopVoiceRecording()
                } else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    startVoiceRecording()
                } else {
                    micPermission.launch(Manifest.permission.RECORD_AUDIO)
                }
            },
            enabled = !sending && !voiceProcessing,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                when {
                    listening -> "■ रिकॉर्डिंग रोकें"
                    voiceProcessing -> "आवाज़ टेक्स्ट में बदल रही है…"
                    else -> "🎙️ माइक से बोलें"
                }
            )
        }
        if (voiceStatus.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = if (listening) Color(0xFF351D25) else Color(0xFF1B2630)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        if (listening) "🔴 माइक LIVE — रिकॉर्डिंग हो रही है" else voiceStatus,
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (listening || voiceProcessing) {
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth(),
                            color = Color(0xFFFF526F)
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = {
                val message = draft.trim()
                if (message.isNotEmpty() && !sending) {
                    val wantsImage = imageMode || Regex(
                        "(तस्वीर|तस्बिर|फोटो|चित्र|इमेज|छवि|image|picture|photo|illustration|thumbnail|logo).*(बना|बनाओ|बनाइ|बनाइदे|तैयार|जनरेट|दिखा|चाहिए|create|make|draw|generate|show)|(?:बना|बनाओ|बनाइ|बनाइदे|तैयार|जनरेट|दिखा|चाहिए|create|make|draw|generate|show).*(तस्वीर|तस्बिर|फोटो|चित्र|इमेज|छवि|image|picture|photo|illustration|thumbnail|logo)",
                        RegexOption.IGNORE_CASE
                    ).containsMatchIn(message)
                    draft = ""
                    lines.add(AiChatLine("user", message))
                    sending = true
                    error = ""
                    scope.launch {
                        try {
                            if (wantsImage) {
                                val result = withContext(Dispatchers.IO) { requestNativeImage(accessToken, message) }
                                val bytes = Base64.decode(result.first, Base64.DEFAULT)
                                generatedImage = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                    ?: throw IllegalStateException("AI image data को तस्वीर में नहीं बदला जा सका।")
                                lines.add(AiChatLine("assistant", "आपकी तस्वीर तैयार है।"))
                            } else {
                                val history = lines.takeLast(10).map { it.role to it.content }
                                val answer = withContext(Dispatchers.IO) { requestNativeAi(accessToken, history) }
                                lines.add(AiChatLine("assistant", answer))
                            }
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

private fun requestNativeImage(accessToken: String, prompt: String): Pair<String, String> {
    check(accessToken.isNotBlank()) { "तस्वीर बनाने के लिए पहले लॉगिन करें।" }
    val payload = JSONObject().put("action", "image").put("prompt", prompt).put("language", Locale.getDefault().language).put("aspectRatio", "1:1").put("imageSize", "1K")
    val client = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS).writeTimeout(20, TimeUnit.SECONDS).build()
    val request = Request.Builder()
        .url("https://uzsusgoetrkujmofxcmb.supabase.co/functions/v1/ai-gateway")
        .post(payload.toString().toRequestBody("application/json".toMediaType()))
        .header("apikey", "sb_publishable_Zz2hljevMsXBcK8c33UvFw_wuc2itVr")
        .header("Authorization", "Bearer $accessToken")
        .build()
    client.newCall(request).execute().use { response ->
        val raw = response.body?.string().orEmpty()
        val data = runCatching { JSONObject(raw) }.getOrElse { JSONObject() }
        if (!response.isSuccessful || !data.optBoolean("ok", false)) {
            throw IllegalStateException(data.optString("error").ifBlank { "तस्वीर नहीं बन पाई (" + response.code + ")।" })
        }
        val image = data.optString("image")
        check(image.isNotBlank()) { "AI ने तस्वीर डेटा नहीं भेजा।" }
        return image to data.optString("mimeType", "image/png")
    }
}

private fun requestNativeTranscription(accessToken: String, audioBytes: ByteArray, mimeType: String): String {
    check(accessToken.isNotBlank()) { "आवाज़ टेक्स्ट में बदलने के लिए पहले लॉगिन करें।" }
    check(audioBytes.isNotEmpty() && audioBytes.size <= 8 * 1024 * 1024) { "रिकॉर्डिंग खाली है या बहुत बड़ी है।" }
    val payload = JSONObject()
        .put("action", "transcribe")
        .put("mimeType", mimeType)
        .put("audio", Base64.encodeToString(audioBytes, Base64.NO_WRAP))
    val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
    val request = Request.Builder()
        .url("https://uzsusgoetrkujmofxcmb.supabase.co/functions/v1/ai-gateway")
        .post(payload.toString().toRequestBody("application/json".toMediaType()))
        .header("apikey", "sb_publishable_Zz2hljevMsXBcK8c33UvFw_wuc2itVr")
        .header("Authorization", "Bearer $accessToken")
        .build()
    client.newCall(request).execute().use { response ->
        val raw = response.body?.string().orEmpty()
        val data = runCatching { JSONObject(raw) }.getOrElse { JSONObject() }
        if (!response.isSuccessful || !data.optBoolean("ok", false)) {
            throw IllegalStateException(data.optString("error").ifBlank { "आवाज़ पहचान सेवा उपलब्ध नहीं है (" + response.code + ")।" })
        }
        return data.optString("transcript").takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("आवाज़ से कोई टेक्स्ट नहीं मिला।")
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
