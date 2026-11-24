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
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
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
    var voiceStatus by remember { mutableStateOf("") }
    var voiceLevel by remember { mutableStateOf(0f) }
    var speechRecognizer by remember { mutableStateOf<SpeechRecognizer?>(null) }
    var imageMode by remember { mutableStateOf(false) }
    var generatedImage by remember { mutableStateOf<Bitmap?>(null) }

    fun startVoiceRecognition() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            voiceStatus = "इस Android डिवाइस पर speech recognition उपलब्ध नहीं है।"
            return
        }
        runCatching { speechRecognizer?.destroy() }
        val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        speechRecognizer = recognizer
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { listening = true; voiceLevel = 0f; voiceStatus = "माइक चालू है — अब बोलें।" }
            override fun onBeginningOfSpeech() { voiceStatus = "आवाज़ सुन रहा है…" }
            override fun onRmsChanged(rmsdB: Float) { voiceLevel = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f) }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { listening = false; voiceLevel = 0f; voiceStatus = "आवाज़ रिकॉर्ड हो गई — अब शब्द पहचान रहा है…" }
            override fun onError(errorCode: Int) {
                listening = false
                voiceLevel = 0f
                voiceStatus = when (errorCode) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "आवाज़ समझ नहीं आई। फिर बोलें या लिखें।"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "माइक्रोफोन की अनुमति नहीं मिली।"
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Speech recognition के लिए नेटवर्क उपलब्ध नहीं है।"
                    else -> "Voice recognition रुक गया। दोबारा कोशिश करें।"
                }
            }
            override fun onResults(results: Bundle?) {
                listening = false
                voiceLevel = 0f
                val spoken = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                if (spoken.isNotBlank()) {
                    draft = listOf(draft.trim(), spoken.trim()).filter { it.isNotBlank() }.joinToString(" ")
                    error = ""
                    voiceStatus = "आवाज़ टेक्स्ट में आ गई। अब AI को भेजें।"
                } else voiceStatus = "कोई शब्द पहचान में नहीं आया। फिर कोशिश करें।"
            }
            override fun onPartialResults(partialResults: Bundle?) {
                val partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!partial.isNullOrBlank()) voiceStatus = "पहचाने गए शब्द: " + partial
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }
        listening = true
        voiceLevel = 0f
        voiceStatus = "माइक चालू हो रहा है…"
        runCatching { recognizer.startListening(intent) }.onFailure {
            listening = false
            voiceLevel = 0f
            voiceStatus = it.message ?: "माइक्रोफोन शुरू नहीं हो सका।"
        }
    }

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startVoiceRecognition() else voiceStatus = "AI voice input के लिए microphone permission आवश्यक है।"
    }

    DisposableEffect(Unit) {
        onDispose {
            runCatching { speechRecognizer?.cancel(); speechRecognizer?.destroy() }
            speechRecognizer = null
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
                    voiceStatus = "रिकॉर्डिंग रोक रहे हैं — आवाज़ के शब्द तैयार हो रहे हैं…"
                    runCatching { speechRecognizer?.stopListening() }
                } else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    startVoiceRecognition()
                } else {
                    micPermission.launch(Manifest.permission.RECORD_AUDIO)
                }
            },
            enabled = !sending,
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (listening) "■ रिकॉर्डिंग रोकें — माइक चालू है" else "🎙️ बोलकर लिखें") }
        if (voiceStatus.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = if (listening) Color(0xFF351D25) else Color(0xFF1B2630)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text(if (listening) "🔴" else "🎙️", fontSize = 17.sp)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (listening) "माइक LIVE — आपकी आवाज़ सुन रहा है" else voiceStatus,
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    if (listening) {
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = voiceLevel,
                            modifier = Modifier.fillMaxWidth(),
                            color = Color(0xFFFF526F),
                            trackColor = Color(0xFF68404A)
                        )
                        Spacer(Modifier.height(4.dp))
                        Text("बोलते समय यह पट्टी आवाज़ के साथ हिलेगी। रोकने पर शब्द टेक्स्ट बॉक्स में आएँगे।", color = Color.LightGray, fontSize = 11.sp)
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
                        "(तस्वीर|तस्बिर|फोटो|चित्र|इमेज|छवि|image|picture|photo|illustration|thumbnail|logo).*(बना|बनाओ|बनाइ|बनाइदे|तैयार|जनरेट|create|make|draw|generate)|(?:बना|बनाओ|बनाइ|बनाइदे|तैयार|जनरेट|create|make|draw|generate).*(तस्वीर|तस्बिर|फोटो|चित्र|इमेज|छवि|image|picture|photo|illustration|thumbnail|logo)",
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
    val client = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(90, TimeUnit.SECONDS).writeTimeout(20, TimeUnit.SECONDS).build()
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
