package com.newstar.live

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
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
import androidx.core.content.ContextCompat
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
    private var tokenState by mutableStateOf("")
    private var roomId: String? = null
    private var liveKind: String? = null
    private var liveStatus by mutableStateOf("अभी LIVE बंद है।")
    private var liveBusy by mutableStateOf(false)
    private var liveActive by mutableStateOf(false)
    private var liveError by mutableStateOf("")
    private var pendingGamingPermission: Intent? = null

    private val mediaPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); selectedMedia = uri }
    }
    private val runtimePermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val granted = result[Manifest.permission.CAMERA] == true && result[Manifest.permission.RECORD_AUDIO] == true
        if (granted) startStandardLiveRequest() else liveError = "Camera और microphone permissions दोनों आवश्यक हैं।"
    }
    private val projectionPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            pendingGamingPermission = data
            startGamingLiveRequest(result.resultCode, data)
        } else liveError = "Screen sharing permission नहीं मिली।"
    }

    private val streamReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val status = intent?.getStringExtra("status") ?: return
            val message = intent.getStringExtra("message") ?: ""
            liveStatus = message
            when (status) {
                "connected" -> {
                    liveActive = true
                    val id = roomId
                    if (id != null) lifecycleScope.launch {
                        runCatching { liveApi("confirm", id) }
                            .onFailure { liveError = "Stream जुड़ गया, लेकिन room activate नहीं हुआ: ${it.message}" }
                    }
                }
                "error" -> {
                    liveActive = false
                    val id = roomId
                    if (id != null) lifecycleScope.launch(Dispatchers.IO) { runCatching { liveApi("stop", id) } }
                    roomId = null; liveKind = null
                    liveError = message
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tokenState = prefs.getString("access_token", "") ?: ""
        val filter = IntentFilter().apply {
            addAction(GamingStreamService.STATUS)
            addAction(CameraLiveService.STATUS)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(streamReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") registerReceiver(streamReceiver, filter)
        window.statusBarColor = android.graphics.Color.rgb(13,13,18)
        window.navigationBarColor = android.graphics.Color.rgb(13,13,18)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Accent, secondary = Cyan, background = Ink, surface = Panel, onBackground = Color.White, onSurface = Color.White)) {
                var accessToken by remember { mutableStateOf(tokenState) }
                var email by remember { mutableStateOf(prefs.getString("email", "") ?: "") }
                var busy by remember { mutableStateOf(false) }
                var error by remember { mutableStateOf("") }
                var mode by remember { mutableStateOf("login") }
                var page by remember { mutableStateOf("home") }
                var emailInput by remember { mutableStateOf("") }
                var password by remember { mutableStateOf("") }
                if (accessToken.isBlank()) {
                    AuthScreen(mode, emailInput, password, busy, error,
                        onMode = { mode = it; error = "" }, onEmail = { emailInput = it }, onPassword = { password = it },
                        onSubmit = {
                            if (emailInput.isBlank() || password.length < 6) error = "ईमेल और कम-से-कम 6 अक्षर का पासवर्ड दर्ज करें।"
                            else {
                                busy = true; error = ""
                                lifecycleScope.launch {
                                    try {
                                        val result = withContext(Dispatchers.IO) { authRequest(if (mode == "signup") "signup" else "token?grant_type=password", emailInput.trim(), password) }
                                        val newToken = result.optString("access_token")
                                        if (newToken.isBlank()) error = if (mode == "signup") "खाता अनुरोध मिला। ईमेल सत्यापन पूरा करके लॉगिन करें।" else result.optString("msg", "लॉगिन नहीं हो सका।")
                                        else {
                                            accessToken = newToken; tokenState = newToken
                                            email = result.optJSONObject("user")?.optString("email", emailInput) ?: emailInput
                                            prefs.edit().putString("access_token", newToken).putString("email", email).apply()
                                        }
                                    } catch (e: Exception) { error = e.message ?: "नेटवर्क कनेक्शन नहीं हो सका।" }
                                    finally { busy = false }
                                }
                            }
                        })
                } else {
                    NativeHome(
                        email = email, page = page, selectedMedia = selectedMedia,
                        liveStatus = liveStatus, liveBusy = liveBusy, liveActive = liveActive, liveError = liveError,
                        onPage = { page = it },
                        onPickMedia = { mediaPicker.launch(arrayOf("video/*")) },
                        onStartStandard = { requestStandardLive() },
                        onStartGaming = { requestGamingLive() },
                        onStopLive = { stopNativeLive() },
                        onLogout = {
                            stopNativeLive()
                            val oldToken = accessToken
                            accessToken = ""; tokenState = ""; email = ""; prefs.edit().clear().apply()
                            lifecycleScope.launch(Dispatchers.IO) { runCatching { authLogout(oldToken) } }
                        }
                    )
                }
            }
        }
    }

    private fun requestStandardLive() {
        if (liveBusy || liveActive) return
        val needed = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        val missing = needed.filter { ContextCompat.checkSelfPermission(this, it) != android.content.pm.PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) startStandardLiveRequest() else runtimePermissions.launch(missing.toTypedArray())
    }

    private fun requestGamingLive() {
        if (liveBusy || liveActive) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            runtimePermissions.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
            return
        }
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionPermission.launch(manager.createScreenCaptureIntent())
    }

    private fun startStandardLiveRequest() {
        if (liveBusy || liveActive) return
        liveBusy = true; liveError = ""; liveStatus = "Cloudflare LIVE session तैयार हो रहा है…"
        lifecycleScope.launch {
            try {
                val prepared = withContext(Dispatchers.IO) { liveApi("prepare", null, "live") }
                roomId = prepared.getString("room_id")
                liveKind = "live"
                val service = Intent(this@MainActivity, CameraLiveService::class.java)
                    .setAction(CameraLiveService.START).putExtra(CameraLiveService.WHIP_URL, prepared.getString("whip_url"))
                ContextCompat.startForegroundService(this@MainActivity, service)
                liveStatus = "Camera और microphone stream शुरू हो रही है…"
            } catch (e: Exception) {
                liveError = e.message ?: "Standard LIVE शुरू नहीं हो सका।"; liveStatus = "LIVE बंद है।"
                roomId = null; liveKind = null
            } finally { liveBusy = false }
        }
    }

    private fun startGamingLiveRequest(resultCode: Int, permissionData: Intent) {
        if (liveBusy || liveActive) return
        liveBusy = true; liveError = ""; liveStatus = "Gaming LIVE session तैयार हो रहा है…"
        lifecycleScope.launch {
            try {
                val prepared = withContext(Dispatchers.IO) { liveApi("prepare", null, "gaming") }
                roomId = prepared.getString("room_id")
                liveKind = "gaming"
                val service = Intent(this@MainActivity, GamingStreamService::class.java)
                    .setAction(GamingStreamService.START)
                    .putExtra(GamingStreamService.WHIP_URL, prepared.getString("whip_url"))
                    .putExtra(GamingStreamService.RESULT_CODE, resultCode)
                    .putExtra(GamingStreamService.RESULT_DATA, permissionData)
                    .putExtra(GamingStreamService.INCLUDE_MIC, true)
                ContextCompat.startForegroundService(this@MainActivity, service)
                liveStatus = "Screen और microphone stream शुरू हो रही है…"
            } catch (e: Exception) {
                liveError = e.message ?: "Gaming LIVE शुरू नहीं हो सका।"; liveStatus = "LIVE बंद है।"
                roomId = null; liveKind = null
            } finally { liveBusy = false }
        }
    }

    private fun stopNativeLive() {
        val kind = liveKind
        val id = roomId
        if (kind == "gaming") startService(Intent(this, GamingStreamService::class.java).setAction(GamingStreamService.STOP))
        if (kind == "live") startService(Intent(this, CameraLiveService::class.java).setAction(CameraLiveService.STOP))
        roomId = null; liveKind = null; liveActive = false; liveBusy = false
        liveStatus = "LIVE बंद कर दिया गया।"
        if (id != null) lifecycleScope.launch(Dispatchers.IO) { runCatching { liveApi("stop", id) } }
    }

    private suspend fun liveApi(action: String, id: String? = null, roomType: String? = null): JSONObject = withContext(Dispatchers.IO) {
        val token = tokenState.ifBlank { prefs.getString("access_token", "") ?: "" }
        check(token.isNotBlank()) { "Please sign in before starting LIVE." }
        val body = JSONObject().put("action", action)
        if (id != null) body.put("room_id", id)
        if (roomType != null) body.put("room_type", roomType).put("room_title", if (roomType == "gaming") "Gaming LIVE" else "New Star LIVE").put("seat_capacity", 1)
        val conn = (URL("$SB_URL/functions/v1/cloudflare-live").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; connectTimeout = 20000; readTimeout = 30000; doOutput = true
            setRequestProperty("apikey", SB_KEY); setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Content-Type", "application/json")
        }
        conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val raw = stream.bufferedReader().use { it.readText() }
        conn.disconnect()
        val payload = JSONObject(raw.ifBlank { "{}" })
        if (code !in 200..299 || payload.optBoolean("ok") == false) throw IllegalStateException(payload.optString("error", "LIVE request failed ($code)."))
        payload
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(streamReceiver) }
        super.onDestroy()
    }
}

private suspend fun authRequest(path: String, email: String, password: String): JSONObject = withContext(Dispatchers.IO) {
    val conn = (URL("$SB_URL/auth/v1/$path").openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"; connectTimeout = 15000; readTimeout = 20000; doOutput = true
        setRequestProperty("apikey", SB_KEY); setRequestProperty("Content-Type", "application/json")
    }
    conn.outputStream.use { it.write(JSONObject().put("email", email).put("password", password).toString().toByteArray()) }
    val code = conn.responseCode
    val stream = if (code in 200..299) conn.inputStream else conn.errorStream
    val body = stream.bufferedReader().use { it.readText() }
    conn.disconnect()
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
    runCatching { conn.responseCode }; conn.disconnect()
}

@Composable
private fun AuthScreen(mode: String, email: String, password: String, busy: Boolean, error: String,
    onMode: (String) -> Unit, onEmail: (String) -> Unit, onPassword: (String) -> Unit, onSubmit: () -> Unit) {
    Column(Modifier.fillMaxSize().background(Ink).verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.Center) {
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
        Text("लॉगिन सीधे New Star Supabase Auth से सत्यापित होता है।", color = Color.LightGray, fontSize = 12.sp)
    }
}

@Composable
private fun NativeHome(email: String, page: String, selectedMedia: Uri?, liveStatus: String, liveBusy: Boolean,
    liveActive: Boolean, liveError: String, onPage: (String) -> Unit, onPickMedia: () -> Unit,
    onStartStandard: () -> Unit, onStartGaming: () -> Unit, onStopLive: () -> Unit, onLogout: () -> Unit) {
    Column(Modifier.fillMaxSize().background(Ink)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text("NEW STAR", color = Accent, fontWeight = FontWeight.Black, fontSize = 25.sp); Text("Native Android", color = Cyan, fontSize = 12.sp) }
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
                    NativeTile("LIVE और Gaming LIVE", "दोनों Native प्रसारण विकल्प LIVE Center में उपलब्ध हैं।", "LIVE सेक्शन खोलें") { onPage("live") }
                }
                "studio" -> {
                    Text("Creator Studio", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Text("Android फ़ाइल पिकर से वीडियो चुनें।", color = Color.LightGray)
                    Spacer(Modifier.height(16.dp)); Button(onClick = onPickMedia) { Text("गैलरी से वीडियो चुनें") }
                    selectedMedia?.let { Spacer(Modifier.height(12.dp)); Text("चुनी गई फ़ाइल:", color = Cyan); Text(it.lastPathSegment ?: it.toString(), color = Color.White) }
                    Spacer(Modifier.height(12.dp)); Text("वीडियो upload/publish का Native integration अलग चरण में बाकी है।", color = Color(0xFFFFC66D), fontSize = 13.sp)
                }
                "live" -> {
                    Text("LIVE Center", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text(if (liveActive) "● LIVE ACTIVE" else "○ OFFLINE", color = if (liveActive) Color(0xFF55DD88) else Color.LightGray, fontWeight = FontWeight.Bold)
                    Text(liveStatus, color = Color.LightGray, fontSize = 13.sp)
                    if (liveError.isNotBlank()) { Spacer(Modifier.height(8.dp)); Text(liveError, color = Color(0xFFFF8A8A), fontSize = 13.sp) }
                    Spacer(Modifier.height(16.dp))
                    NativeTile("Standard LIVE", "फोन के camera और microphone से Cloudflare Stream पर broadcast.", if (liveActive || liveBusy) "चल रहा है" else "Standard LIVE शुरू करें", if (liveActive || liveBusy) null else onStartStandard)
                    Spacer(Modifier.height(12.dp))
                    NativeTile("Gaming LIVE", "Android screen capture और microphone से gameplay broadcast. Android की screen-share अनुमति जरूरी है.", if (liveActive || liveBusy) "चल रहा है" else "Gaming LIVE शुरू करें", if (liveActive || liveBusy) null else onStartGaming)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = onStopLive, enabled = liveActive || liveBusy, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Accent)) { Text("LIVE बंद करें") }
                    if (liveBusy) { Spacer(Modifier.height(10.dp)); LinearProgressIndicator(Modifier.fillMaxWidth()) }
                }
                else -> { Text("प्रोफाइल", fontSize = 24.sp, fontWeight = FontWeight.Bold); Spacer(Modifier.height(8.dp)); Text(email, color = Cyan); Text("Supabase authenticated session", color = Color.LightGray, fontSize = 13.sp) }
            }
        }
    }
}

@Composable
private fun NativeTile(title: String, detail: String, action: String, onClick: (() -> Unit)? = null) {
    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp)) {
            Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp)); Text(detail, color = Color.LightGray, fontSize = 13.sp)
            if (onClick != null) { Spacer(Modifier.height(12.dp)); OutlinedButton(onClick = onClick) { Text(action) } }
            else { Spacer(Modifier.height(10.dp)); Text(action, color = Color(0xFFFFC66D), fontSize = 12.sp) }
        }
    }
}
