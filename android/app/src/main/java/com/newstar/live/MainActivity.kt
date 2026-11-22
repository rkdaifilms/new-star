package com.newstar.live

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

private const val SB_URL = "https://uzsusgoetrkujmofxcmb.supabase.co"
private const val SB_KEY = "sb_publishable_Zz2hljevMsXBcK8c33UvFw_wuc2itVr"
private val Ink = Color(0xFF0D0D12)
private val Panel = Color(0xFF1B1B25)
private val Accent = Color(0xFFFF3158)
private val Cyan = Color(0xFF25F4EE)

class MainActivity : ComponentActivity() {
    private val prefs by lazy { getSharedPreferences("newstar_native_session", MODE_PRIVATE) }
    private var selectedMedia by mutableStateOf<Uri?>(null)
    private val mediaPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            selectedMedia = uri
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(13,13,18)
        window.navigationBarColor = android.graphics.Color.rgb(13,13,18)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                primary = Accent, secondary = Cyan, background = Ink, surface = Panel,
                onBackground = Color.White, onSurface = Color.White
            )) {
                var accessToken by remember { mutableStateOf(prefs.getString("access_token", "") ?: "") }
                var email by remember { mutableStateOf(prefs.getString("email", "") ?: "") }
                var busy by remember { mutableStateOf(false) }
                var error by remember { mutableStateOf("") }
                var mode by remember { mutableStateOf("login") }
                var page by remember { mutableStateOf("home") }
                var emailInput by remember { mutableStateOf("") }
                var password by remember { mutableStateOf("") }

                if (accessToken.isBlank()) {
                    AuthScreen(
                        mode = mode, email = emailInput, password = password, busy = busy, error = error,
                        onMode = { mode = it; error = "" },
                        onEmail = { emailInput = it }, onPassword = { password = it },
                        onSubmit = {
                            if (emailInput.isBlank() || password.length < 6) {
                                error = "ईमेल और कम-से-कम 6 अक्षर का पासवर्ड दर्ज करें।"
                            } else {
                                busy = true; error = ""
                                lifecycleScope.launch {
                                    try {
                                        val result = withContext(Dispatchers.IO) {
                                            authRequest(if (mode == "signup") "signup" else "token?grant_type=password", emailInput.trim(), password)
                                        }
                                        val token = result.optString("access_token")
                                        if (token.isBlank()) {
                                            error = if (mode == "signup") "खाता अनुरोध मिला। ईमेल सत्यापन पूरा करके लॉगिन करें।" else result.optString("msg", "लॉगिन नहीं हो सका।")
                                        } else {
                                            accessToken = token
                                            email = result.optJSONObject("user")?.optString("email", emailInput) ?: emailInput
                                            prefs.edit().putString("access_token", token).putString("email", email).apply()
                                        }
                                    } catch (e: Exception) { error = e.message ?: "नेटवर्क कनेक्शन नहीं हो सका।" }
                                    finally { busy = false }
                                }
                            }
                        }
                    )
                } else {
                    NativeHome(
                        email = email, page = page, selectedMedia = selectedMedia,
                        onPage = { page = it },
                        onPickMedia = { mediaPicker.launch(arrayOf("video/*")) },
                        onLogout = {
                            val token = accessToken
                            accessToken = ""; email = ""; prefs.edit().clear().apply()
                            lifecycleScope.launch(Dispatchers.IO) {
                                runCatching { authLogout(token) }
                            }
                        }
                    )
                }
            }
        }
    }
}

private suspend fun authRequest(path: String, email: String, password: String): JSONObject =
    withContext(Dispatchers.IO) {
        val conn = (URL("$SB_URL/auth/v1/$path").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; connectTimeout = 15000; readTimeout = 20000
            doOutput = true
            setRequestProperty("apikey", SB_KEY)
            setRequestProperty("Content-Type", "application/json")
        }
        conn.outputStream.use { it.write(JSONObject().put("email", email).put("password", password).toString().toByteArray()) }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val body = stream.bufferedReader().use { it.readText() }
        if (code !in 200..299) {
            val message = runCatching { JSONObject(body).optString("msg").ifBlank { JSONObject(body).optString("message") } }.getOrNull()
            throw IllegalStateException(message?.takeIf { it.isNotBlank() } ?: "Supabase authentication error ($code)")
        }
        JSONObject(body)
    }

private fun authLogout(token: String) {
    val conn = (URL("$SB_URL/auth/v1/logout").openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"; connectTimeout = 10000; readTimeout = 10000
        setRequestProperty("apikey", SB_KEY); setRequestProperty("Authorization", "Bearer $token")
    }
    runCatching { conn.responseCode }
    conn.disconnect()
}

@Composable
private fun AuthScreen(
    mode: String, email: String, password: String, busy: Boolean, error: String,
    onMode: (String) -> Unit, onEmail: (String) -> Unit, onPassword: (String) -> Unit, onSubmit: () -> Unit
) {
    Column(
        Modifier.fillMaxSize().background(Ink).verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text("NEW STAR", color = Accent, fontSize = 32.sp, fontWeight = FontWeight.Black)
        Text("Creator platform • Native Android", color = Cyan, fontSize = 14.sp)
        Spacer(Modifier.height(28.dp))
        Text(if (mode == "login") "लॉगिन करें" else "नया अकाउंट बनाएँ", fontSize = 25.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(18.dp))
        OutlinedTextField(email, onEmail, Modifier.fillMaxWidth(), label = { Text("ईमेल") }, singleLine = true)
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(password, onPassword, Modifier.fillMaxWidth(), label = { Text("पासवर्ड") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
        if (error.isNotBlank()) { Spacer(Modifier.height(10.dp)); Text(error, color = Color(0xFFFF8A8A), fontSize = 13.sp) }
        Spacer(Modifier.height(18.dp))
        Button(onClick = onSubmit, enabled = !busy, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(14.dp)) {
            Text(if (busy) "कनेक्ट हो रहा है…" else if (mode == "login") "लॉगिन" else "साइन अप")
        }
        TextButton(onClick = { onMode(if (mode == "login") "signup" else "login") }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text(if (mode == "login") "नया अकाउंट बनाएँ" else "पहले से अकाउंट है? लॉगिन")
        }
        Text("आपका लॉगिन सीधे New Star Supabase Auth से सत्यापित होता है।", color = Color.LightGray, fontSize = 12.sp)
    }
}

@Composable
private fun NativeHome(
    email: String, page: String, selectedMedia: Uri?,
    onPage: (String) -> Unit, onPickMedia: () -> Unit, onLogout: () -> Unit
) {
    Column(Modifier.fillMaxSize().background(Ink)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("NEW STAR", color = Accent, fontWeight = FontWeight.Black, fontSize = 25.sp)
                Text("Native Android", color = Cyan, fontSize = 12.sp)
            }
            TextButton(onClick = onLogout) { Text("लॉगआउट") }
        }
        NavigationBar(containerColor = Panel) {
            listOf("home" to "होम", "studio" to "स्टूडियो", "live" to "LIVE", "profile" to "प्रोफाइल").forEach { (key, label) ->
                NavigationBarItem(selected = page == key, onClick = { onPage(key) }, icon = {}, label = { Text(label) })
            }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(18.dp)) {
            when (page) {
                "home" -> {
                    Text("आपका Creator Dashboard", fontSize = 23.sp, fontWeight = FontWeight.Bold)
                    Text("लॉगिन सफल • Supabase Auth connected", color = Cyan, fontSize = 13.sp)
                    Spacer(Modifier.height(18.dp))
                    NativeTile("वीडियो स्टूडियो", "फोन से वीडियो चुनें", "वीडियो चुनें", onPickMedia)
                    Spacer(Modifier.height(12.dp))
                    NativeTile("LIVE और Gaming LIVE", "Native Android स्क्रीन-कैप्चर इंजन प्रोजेक्ट में मौजूद है। लाइव प्रसारण शुरू करने के लिए सुरक्षित server-issued WHIP session जोड़ना बाकी है।", "LIVE सेक्शन खोलें") { onPage("live") }
                }
                "studio" -> {
                    Text("Creator Studio", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Text("Android फ़ाइल पिकर से वीडियो चुनें।", color = Color.LightGray)
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = onPickMedia) { Text("गैलरी से वीडियो चुनें") }
                    selectedMedia?.let {
                        Spacer(Modifier.height(12.dp))
                        Text("चुनी गई फ़ाइल:", color = Cyan)
                        Text(it.lastPathSegment ?: it.toString(), color = Color.White)
                    }
                    Spacer(Modifier.height(12.dp))
                    Text("यह Native चयन चरण है। वीडियो का resumable Cloudflare upload और publish flow अभी इस Native स्क्रीन से जोड़ा नहीं गया है।", color = Color(0xFFFFC66D), fontSize = 13.sp)
                }
                "live" -> {
                    Text("LIVE Center", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(12.dp))
                    NativeTile("Standard LIVE", "Camera/microphone capture और Cloudflare LIVE session का Native hookup अभी बाकी है।", "अभी उपलब्ध नहीं")
                    Spacer(Modifier.height(12.dp))
                    NativeTile("Gaming LIVE", "इस डिवाइस की पूरी स्क्रीन साझा करने के लिए Android MediaProjection अनुमति चाहिए। Native प्रसारण को सुरक्षित WHIP session से जोड़ना अभी बाकी है।", "अभी उपलब्ध नहीं")
                }
                else -> {
                    Text("प्रोफाइल", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text(email, color = Cyan)
                    Text("Supabase authenticated session", color = Color.LightGray, fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
private fun NativeTile(title: String, detail: String, action: String, onClick: (() -> Unit)? = null) {
    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp)) {
            Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(detail, color = Color.LightGray, fontSize = 13.sp)
            if (onClick != null) {
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = onClick) { Text(action) }
            } else {
                Spacer(Modifier.height(10.dp))
                Text(action, color = Color(0xFFFFC66D), fontSize = 12.sp)
            }
        }
    }
}
