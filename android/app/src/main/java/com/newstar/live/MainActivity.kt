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
import android.media.MediaMetadataRetriever
import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

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
    private var passwordRecoveryMode by mutableStateOf(false)
    private var nativeUploadBusy by mutableStateOf(false)
    private var nativeUploadProgress by mutableStateOf(0)
    private var nativeUploadStatus by mutableStateOf("")
    private var nativeUploadError by mutableStateOf("")
    private var roomId: String? = null
    private var liveKind: String? = null
    private var liveStatus by mutableStateOf("अभी LIVE बंद है।")
    private var liveBusy by mutableStateOf(false)
    private var liveActive by mutableStateOf(false)
    private var liveError by mutableStateOf("")
    private var gamingMicEnabled by mutableStateOf(true)
    private var pendingGamingPermission: Intent? = null
    private var permissionRequestForGaming = false

    private val mediaPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); selectedMedia = uri }
    }
    private val runtimePermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val granted = result[Manifest.permission.CAMERA] == true && result[Manifest.permission.RECORD_AUDIO] == true
        if (granted) {
            if (permissionRequestForGaming) {
                permissionRequestForGaming = false
                val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                projectionPermission.launch(manager.createScreenCaptureIntent())
            } else startStandardLiveRequest()
        } else {
            permissionRequestForGaming = false
            liveError = "Camera और microphone permissions दोनों आवश्यक हैं।"
        }
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
        handleAuthCallback(intent)
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
                var recoveryMode by remember { mutableStateOf(passwordRecoveryMode) }
                var newPassword by remember { mutableStateOf("") }
                LaunchedEffect(Unit) {
                    val refresh = prefs.getString("refresh_token", "").orEmpty()
                    if (refresh.isNotBlank() && !recoveryMode) {
                        try {
                            val renewed = withContext(Dispatchers.IO) { authRefreshRequest(refresh) }
                            val renewedAccess = renewed.optString("access_token")
                            if (renewedAccess.isNotBlank()) {
                                val renewedRefresh = renewed.optString("refresh_token", refresh)
                                tokenState = renewedAccess; accessToken = renewedAccess
                                email = renewed.optJSONObject("user")?.optString("email", email) ?: email
                                prefs.edit().putString("access_token", renewedAccess).putString("refresh_token", renewedRefresh).putString("email", email).apply()
                            }
                        } catch (_: Exception) { /* Keep the existing access token; transient network failures must not log the user out. */ }
                    }
                }
                var page by remember { mutableStateOf("home") }
                var emailInput by remember { mutableStateOf("") }
                var password by remember { mutableStateOf("") }
                if (recoveryMode) {
                    PasswordResetScreen(newPassword, { newPassword = it }, busy, error, onSubmit = {
                        if (newPassword.length < 6) error = "नया पासवर्ड कम-से-कम 6 अक्षर का रखें।"
                        else {
                            busy = true; error = ""
                            lifecycleScope.launch {
                                try {
                                    withContext(Dispatchers.IO) { authUpdatePassword(newPassword, prefs.getString("access_token", "") ?: "") }
                                    prefs.edit().clear().apply(); accessToken = ""; tokenState = ""; email = ""; passwordRecoveryMode = false; recoveryMode = false; newPassword = ""
                                    error = ""
                                } catch (e: Exception) { error = e.message ?: "पासवर्ड अपडेट नहीं हुआ।" }
                                finally { busy = false }
                            }
                        }
                    })
                } else if (accessToken.isBlank()) {
                    AuthScreen(mode, emailInput, password, busy, error,
                        onMode = { mode = it; error = "" }, onEmail = { emailInput = it }, onPassword = { password = it },
                        onGoogleLogin = { startGoogleSignIn() },
                        onForgotPassword = {
                            if (emailInput.isBlank()) error = "पहले अपना ईमेल दर्ज करें।"
                            else {
                                busy = true; error = ""
                                lifecycleScope.launch {
                                    try {
                                        withContext(Dispatchers.IO) { authRecoveryRequest(emailInput.trim().lowercase()) }
                                        error = "यदि इस ईमेल का अकाउंट है, तो पासवर्ड बदलने का लिंक भेजा गया है। अपना ईमेल देखें।"
                                    } catch (e: Exception) { error = e.message ?: "रिकवरी ईमेल नहीं भेजा जा सका।" }
                                    finally { busy = false }
                                }
                            }
                        },
                        onSubmit = {
                            if (emailInput.isBlank() || !android.util.Patterns.EMAIL_ADDRESS.matcher(emailInput.trim()).matches() || password.isBlank() || (mode == "signup" && password.length < 6)) error = if (mode == "signup") "सही ईमेल और कम-से-कम 6 अक्षर का पासवर्ड दर्ज करें।" else "सही ईमेल और पासवर्ड दर्ज करें।"
                            else {
                                busy = true; error = ""
                                lifecycleScope.launch {
                                    try {
                                        val result = withContext(Dispatchers.IO) { authRequest(if (mode == "signup") "signup" else "token?grant_type=password", emailInput.trim().lowercase(), password) }
                                        val newToken = result.optString("access_token")
                                        if (newToken.isBlank()) error = if (mode == "signup") "खाता अनुरोध मिला। ईमेल सत्यापन पूरा करके लॉगिन करें।" else result.optString("msg", "लॉगिन नहीं हो सका।")
                                        else {
                                            accessToken = newToken; tokenState = newToken
                                            email = result.optJSONObject("user")?.optString("email", emailInput) ?: emailInput
                                            prefs.edit().putString("access_token", newToken).putString("refresh_token", result.optString("refresh_token")).putString("email", email).apply()
                                        }
                                     } catch (e: Exception) {
                                        val raw = e.message.orEmpty()
                                        error = when {
                                            raw.contains("already registered", ignoreCase = true) || raw.contains("user_already_exists", ignoreCase = true) -> {
                                                mode = "login"
                                                "यह ईमेल पहले से रजिस्टर्ड है। अब लॉगिन करें या ‘पासवर्ड भूल गए?’ से नया पासवर्ड बनाएँ।"
                                            }
                                            raw.contains("invalid login credentials", ignoreCase = true) || raw.contains("invalid_credentials", ignoreCase = true) -> "ईमेल या पासवर्ड मेल नहीं खा रहा। ईमेल जाँचें या ‘पासवर्ड भूल गए?’ दबाकर पासवर्ड बदलें।"
                                            raw.contains("email not confirmed", ignoreCase = true) -> "आपका ईमेल अभी सत्यापित नहीं है। Inbox में Supabase verification लिंक खोलें।"
                                            raw.contains("network", ignoreCase = true) || raw.contains("timeout", ignoreCase = true) -> "इंटरनेट कनेक्शन धीमा है। नेटवर्क जाँचकर फिर कोशिश करें।"
                                            else -> raw.ifBlank { "लॉगिन नहीं हो सका। कुछ देर बाद फिर कोशिश करें।" }
                                        }
                                    }
                                    finally { busy = false }
                                }
                            }
                        })
                } else {
                    NativeHome(
                        email = email, accessToken = accessToken, page = page, selectedMedia = selectedMedia,
                        uploadBusy = nativeUploadBusy, uploadProgress = nativeUploadProgress, uploadStatus = nativeUploadStatus, uploadError = nativeUploadError,
                        onPublishVideo = { title, caption ->
                            if (selectedMedia == null) nativeUploadError = "पहले गैलरी से वीडियो चुनें।"
                            else if (title.isBlank()) nativeUploadError = "वीडियो का शीर्षक लिखें।"
                            else if (!nativeUploadBusy) {
                                nativeUploadBusy = true; nativeUploadProgress = 0; nativeUploadError = ""; nativeUploadStatus = "वीडियो की जानकारी पढ़ी जा रही है…"
                                lifecycleScope.launch {
                                    try {
                                        withContext(Dispatchers.IO) { uploadNativeVideo(this@MainActivity, selectedMedia!!, accessToken, email, title.trim(), caption.trim()) { pct, status -> runOnUiThread { nativeUploadProgress = pct; nativeUploadStatus = status } } }
                                        nativeUploadProgress = 100; nativeUploadStatus = "✓ वीडियो अपलोड और डेटाबेस में सेव हो गया।"
                                    } catch (e: Exception) { nativeUploadError = e.message ?: "वीडियो अपलोड नहीं हो सका।"; nativeUploadStatus = "अपलोड रुक गया।" }
                                    finally { nativeUploadBusy = false }
                                }
                            }
                        },
                        liveStatus = liveStatus, liveBusy = liveBusy, liveActive = liveActive, liveError = liveError,
                        isGamingLive = liveKind == "gaming", gamingMicEnabled = gamingMicEnabled,
                        onToggleGamingMic = { updateGamingMicEnabled(!gamingMicEnabled) },
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
            permissionRequestForGaming = true
            runtimePermissions.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
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
                gamingMicEnabled = true
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

    private fun updateGamingMicEnabled(enabled: Boolean) {
        if (liveKind != "gaming" || !liveActive) {
            liveError = "Mic नियंत्रण Gaming LIVE जुड़ने के बाद उपलब्ध होगा।"
            return
        }
        gamingMicEnabled = enabled
        startService(
            Intent(this, GamingStreamService::class.java)
                .setAction(GamingStreamService.SET_MIC)
                .putExtra(GamingStreamService.MIC_ENABLED, enabled)
        )
        liveStatus = if (enabled) "Gaming LIVE microphone चालू है।" else "Mic mute है; screen stream जारी है।"
    }

    private fun stopNativeLive() {
        val kind = liveKind
        val id = roomId
        if (kind == "gaming") startService(Intent(this, GamingStreamService::class.java).setAction(GamingStreamService.STOP))
        if (kind == "live") startService(Intent(this, CameraLiveService::class.java).setAction(CameraLiveService.STOP))
        roomId = null; liveKind = null; liveActive = false; liveBusy = false
        gamingMicEnabled = true
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

    private fun startGoogleSignIn() {
        val redirect = "com.newstar.live://auth-callback"
        val authUrl = Uri.parse("$SB_URL/auth/v1/authorize").buildUpon()
            .appendQueryParameter("provider", "google")
            .appendQueryParameter("redirect_to", redirect)
            .build()
        startActivity(Intent(Intent.ACTION_VIEW, authUrl))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (handleAuthCallback(intent)) recreate()
    }

    private fun handleAuthCallback(source: Intent?): Boolean {
        val uri = source?.data ?: return false
        if (uri.scheme != "com.newstar.live" || uri.host != "auth-callback") return false
        val values = uri.fragment.orEmpty().split("&").mapNotNull { part ->
            val pair = part.split("=", limit = 2)
            if (pair.size == 2) pair[0] to Uri.decode(pair[1]) else null
        }.toMap()
        val access = values["access_token"].orEmpty()
        val refresh = values["refresh_token"].orEmpty()
        if (access.isNotBlank()) {
            tokenState = access
            passwordRecoveryMode = values["type"] == "recovery"
            prefs.edit().putString("access_token", access).putString("refresh_token", refresh).apply()
            return true
        }
        return false
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(streamReceiver) }
        super.onDestroy()
    }
}

private suspend fun authRecoveryRequest(email: String) = withContext(Dispatchers.IO) {
    val redirect = java.net.URLEncoder.encode("com.newstar.live://auth-callback", "UTF-8")
    val conn = (URL("$SB_URL/auth/v1/recover?redirect_to=$redirect").openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"; connectTimeout = 15000; readTimeout = 20000; doOutput = true
        setRequestProperty("apikey", SB_KEY); setRequestProperty("Content-Type", "application/json")
    }
    val body = JSONObject().put("email", email)
    conn.outputStream.use { it.write(body.toString().toByteArray()) }
    val code = conn.responseCode
    val stream = if (code in 200..299) conn.inputStream else conn.errorStream
    val raw = stream.bufferedReader().use { it.readText() }
    conn.disconnect()
    if (code !in 200..299) {
        val message = runCatching { JSONObject(raw).optString("msg").ifBlank { JSONObject(raw).optString("message") } }.getOrNull()
        throw IllegalStateException(message?.takeIf { it.isNotBlank() } ?: "Password recovery request failed ($code)")
    }
}

private suspend fun authRefreshRequest(refreshToken: String): JSONObject = withContext(Dispatchers.IO) {
    val conn = (URL("$SB_URL/auth/v1/token?grant_type=refresh_token").openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"; connectTimeout = 15000; readTimeout = 20000; doOutput = true
        setRequestProperty("apikey", SB_KEY); setRequestProperty("Content-Type", "application/json")
    }
    conn.outputStream.use { it.write(JSONObject().put("refresh_token", refreshToken).toString().toByteArray()) }
    val code = conn.responseCode; val stream = if (code in 200..299) conn.inputStream else conn.errorStream
    val raw = stream.bufferedReader().use { it.readText() }; conn.disconnect()
    if (code !in 200..299) throw IllegalStateException("Session refresh failed ($code): $raw")
    JSONObject(raw)
}

private suspend fun authUpdatePassword(password: String, accessToken: String) = withContext(Dispatchers.IO) {
    check(accessToken.isNotBlank()) { "पासवर्ड बदलने का लिंक दोबारा खोलें।" }
    val conn = (URL("$SB_URL/auth/v1/user").openConnection() as HttpURLConnection).apply {
        requestMethod = "PUT"; connectTimeout = 15000; readTimeout = 20000; doOutput = true
        setRequestProperty("apikey", SB_KEY); setRequestProperty("Authorization", "Bearer $accessToken"); setRequestProperty("Content-Type", "application/json")
    }
    conn.outputStream.use { it.write(JSONObject().put("password", password).toString().toByteArray()) }
    val code = conn.responseCode
    val stream = if (code in 200..299) conn.inputStream else conn.errorStream
    val raw = stream.bufferedReader().use { it.readText() }; conn.disconnect()
    if (code !in 200..299) throw IllegalStateException(runCatching { JSONObject(raw).optString("message").ifBlank { JSONObject(raw).optString("msg") } }.getOrNull()?.ifBlank { null } ?: "पासवर्ड अपडेट नहीं हुआ ($code)")
}

@Composable
private fun PasswordResetScreen(password: String, onPassword: (String) -> Unit, busy: Boolean, error: String, onSubmit: () -> Unit) {
    Column(Modifier.fillMaxSize().background(Ink).verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text("NEW STAR", color = Accent, fontSize = 32.sp, fontWeight = FontWeight.Black)
        Spacer(Modifier.height(24.dp)); Text("नया पासवर्ड बनाएँ", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp)); Text("ईमेल से मिले सुरक्षित लिंक के बाद अपना नया पासवर्ड दर्ज करें।", color = Color.LightGray)
        Spacer(Modifier.height(16.dp)); OutlinedTextField(password, onPassword, Modifier.fillMaxWidth(), label = { Text("नया पासवर्ड") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
        if (error.isNotBlank()) { Spacer(Modifier.height(10.dp)); Text(error, color = Color(0xFFFF8A8A), fontSize = 13.sp) }
        Spacer(Modifier.height(18.dp)); Button(onClick = onSubmit, enabled = !busy, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text(if (busy) "अपडेट हो रहा है…" else "पासवर्ड अपडेट करें") }
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
        val message = runCatching { val json = JSONObject(body); json.optString("msg").ifBlank { json.optString("message").ifBlank { json.optString("error_description").ifBlank { json.optString("error").ifBlank { json.optString("code") } } } } }.getOrNull()
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
    onMode: (String) -> Unit, onEmail: (String) -> Unit, onPassword: (String) -> Unit,
    onGoogleLogin: () -> Unit, onForgotPassword: () -> Unit, onSubmit: () -> Unit) {
    Column(Modifier.fillMaxSize().background(Ink).verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text("NEW STAR", color = Accent, fontSize = 32.sp, fontWeight = FontWeight.Black)
        Spacer(Modifier.height(34.dp))
        Text(if (mode == "login") "लॉगिन करें" else "नया अकाउंट बनाएँ", fontSize = 25.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(18.dp))
        OutlinedButton(onClick = onGoogleLogin, enabled = !busy, modifier = Modifier.fillMaxWidth().height(50.dp)) { Text("Google से जारी रखें") }
        Spacer(Modifier.height(12.dp))
        Text("या ईमेल से लॉगिन करें", color = Color.LightGray, fontSize = 13.sp)
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(email, onEmail, Modifier.fillMaxWidth(), label = { Text("ईमेल") }, singleLine = true)
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(password, onPassword, Modifier.fillMaxWidth(), label = { Text("पासवर्ड") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
        if (error.isNotBlank()) { Spacer(Modifier.height(10.dp)); Text(error, color = Color(0xFFFF8A8A), fontSize = 13.sp) }
        Spacer(Modifier.height(18.dp))
        Button(onClick = onSubmit, enabled = !busy, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(14.dp)) {
            Text(if (busy) "कनेक्ट हो रहा है…" else if (mode == "login") "लॉगिन" else "साइन अप")
        }
        if (mode == "login") TextButton(onClick = onForgotPassword, enabled = !busy, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("पासवर्ड भूल गए?") }
        TextButton(onClick = { onMode(if (mode == "login") "signup" else "login") }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text(if (mode == "login") "नया अकाउंट बनाएँ" else "पहले से अकाउंट है? लॉगिन")
        }
    }
}

private suspend fun uploadNativeVideo(
    context: Context, uri: Uri, accessToken: String, email: String, title: String, description: String,
    onProgress: (Int, String) -> Unit
) = withContext(Dispatchers.IO) {
    check(accessToken.isNotBlank()) { "अपलोड करने से पहले लॉगिन करें।" }
    val size = context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
        ?: throw IllegalStateException("वीडियो फ़ाइल का आकार नहीं पढ़ा जा सका।")
    require(size > 0 && size <= 5L * 1024 * 1024 * 1024) { "वीडियो 5 GiB की सीमा के अंदर होना चाहिए।" }
    val retriever = MediaMetadataRetriever()
    val durationMs: Long
    val width: Int
    val height: Int
    try {
        retriever.setDataSource(context, uri)
        durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            ?: error("वीडियो की अवधि पढ़ी नहीं जा सकी।")
        width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
        height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
    } finally { retriever.release() }
    val duration = ((durationMs + 999) / 1000).toInt()
    require(duration in 1..10800) { "वीडियो 1 सेकंड से 3 घंटे के बीच होना चाहिए।" }
    val mime = context.contentResolver.getType(uri) ?: "video/mp4"
    val name = uri.lastPathSegment?.substringAfterLast('/')?.take(180)?.ifBlank { "video.mp4" } ?: "video.mp4"
    val encode: (String) -> String = { Base64.encodeToString(it.toByteArray(Charsets.UTF_8), Base64.NO_WRAP) }
    val metadata = listOf("maxDurationSeconds " + encode(duration.toString()), "name " + encode(name), "filetype " + encode(mime)).joinToString(",")
    val client = OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(0, TimeUnit.MILLISECONDS).build()
    val endpoint = "$SB_URL/functions/v1/cloudflare-tus-proxy"
    val create = Request.Builder().url(endpoint).post(ByteArray(0).toRequestBody())
        .header("Origin", "https://new-star-delta.vercel.app")
        .header("Authorization", "Bearer $accessToken").header("apikey", SB_KEY)
        .header("Tus-Resumable", "1.0.0").header("Upload-Length", size.toString())
        .header("Upload-Metadata", metadata).build()
    var location = ""
    var streamId = ""
    client.newCall(create).execute().use { response ->
        if (!response.isSuccessful) throw IllegalStateException("Cloudflare upload शुरू नहीं हुआ (" + response.code + "): " + response.body?.string()?.take(500))
        location = response.header("Location") ?: error("Cloudflare ने upload URL नहीं दिया।")
        streamId = response.header("stream-media-id") ?: error("Cloudflare ने Video ID नहीं दिया।")
    }
    onProgress(1, "Cloudflare upload session तैयार है…")
    val chunkSize = 8 * 1024 * 1024
    var offset = 0L
    context.contentResolver.openInputStream(uri)?.use { input ->
        val mediaType = "application/offset+octet-stream".toMediaType()
        val buffer = ByteArray(chunkSize)
        while (offset < size) {
            var filled = 0
            val wanted = minOf(chunkSize.toLong(), size - offset).toInt()
            while (filled < wanted) {
                val count = input.read(buffer, filled, wanted - filled)
                if (count < 0) error("वीडियो फ़ाइल पढ़ते समय अधूरी मिली।")
                filled += count
            }
            val bytes = if (filled == buffer.size) buffer else buffer.copyOf(filled)
            val patch = Request.Builder().url(location).patch(bytes.toRequestBody(mediaType))
                .header("Tus-Resumable", "1.0.0").header("Upload-Offset", offset.toString()).build()
            client.newCall(patch).execute().use { response ->
                if (response.code != 204) throw IllegalStateException("वीडियो upload बीच में रुक गया (" + response.code + "): " + response.body?.string()?.take(300))
                val returnedOffset = response.header("Upload-Offset")?.toLongOrNull()
                    ?: throw IllegalStateException("Cloudflare ने upload offset वापस नहीं दिया।")
                if (returnedOffset != offset + filled) error("Cloudflare upload offset मेल नहीं खा रहा।")
            }
            offset += filled
            val percent = (offset * 100 / size).toInt().coerceIn(1, 99)
            onProgress(percent, "वीडियो अपलोड हो रहा है: $percent%")
        }
    } ?: error("वीडियो फ़ाइल खोली नहीं जा सकी।")
    onProgress(99, "अपलोड पूरा; वीडियो डेटाबेस में सेव हो रहा है…")
    val caption = listOf(title, description).filter { it.isNotBlank() }.joinToString("\n\n")
    val username = email.substringBefore("@").ifBlank { "New Star Creator" }
    val userRequest = Request.Builder().url("$SB_URL/auth/v1/user").get()
        .header("apikey", SB_KEY).header("Authorization", "Bearer $accessToken").build()
    val userId = client.newCall(userRequest).execute().use { response ->
        if (!response.isSuccessful) error("लॉगिन सत्र सत्यापित नहीं हुआ (" + response.code + ")।")
        JSONObject(response.body?.string().orEmpty()).optString("id").takeIf { it.isNotBlank() }
            ?: error("Supabase user ID नहीं मिली।")
    }
    val payload = JSONObject()
        .put("user_id", userId).put("video_url", "cloudflare://" + streamId)
        .put("caption", caption).put("category", "Other").put("username", username)
        .put("likes_count", 0).put("views_count", 0).put("audience_type", "all")
        .put("duration_seconds", duration).put("width", width).put("height", height)
        .put("video_type", if (duration <= 60) "short" else "long")
        .put("processing_status", "processing").put("visibility", "public")
        .put("media_kind", "video").put("aspect_ratio_setting", "original")
        .put("quality_preference", "original").put("frame_fit", "contain")
        .put("source_quality", "\${width}x\${height}").put("video_provider", "cloudflare")
        .put("provider_video_id", streamId).put("provider_status", "processing")
    val insert = Request.Builder().url("$SB_URL/rest/v1/videos").post(payload.toString().toRequestBody("application/json".toMediaType()))
        .header("apikey", SB_KEY).header("Authorization", "Bearer $accessToken")
        .header("Content-Type", "application/json").header("Prefer", "return=minimal").build()
    client.newCall(insert).execute().use { response ->
        if (!response.isSuccessful) throw IllegalStateException("Cloudflare पर वीडियो सुरक्षित है, लेकिन database में सेव नहीं हुआ (" + response.code + "): " + response.body?.string()?.take(500))
    }
    onProgress(100, "✓ वीडियो अपलोड और डेटाबेस में सेव हो गया।")
}

@Composable
private fun NativeHome(email: String, accessToken: String, page: String, selectedMedia: Uri?, uploadBusy: Boolean, uploadProgress: Int, uploadStatus: String, uploadError: String, onPublishVideo: (String, String) -> Unit, liveStatus: String, liveBusy: Boolean,
    liveActive: Boolean, liveError: String, isGamingLive: Boolean, gamingMicEnabled: Boolean,
    onToggleGamingMic: () -> Unit, onPage: (String) -> Unit, onPickMedia: () -> Unit,
    onStartStandard: () -> Unit, onStartGaming: () -> Unit, onStopLive: () -> Unit, onLogout: () -> Unit) {
    var videoTitle by remember { mutableStateOf("") }
    var videoDescription by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().background(Ink)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("NEW STAR", color = Accent, fontWeight = FontWeight.Black, fontSize = 25.sp)
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
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(videoTitle, { videoTitle = it }, Modifier.fillMaxWidth(), label = { Text("वीडियो शीर्षक") }, singleLine = true)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(videoDescription, { videoDescription = it }, Modifier.fillMaxWidth(), label = { Text("विवरण (वैकल्पिक)") }, minLines = 2)
                    Spacer(Modifier.height(10.dp))
                    Button(onClick = { onPublishVideo(videoTitle, videoDescription) }, enabled = !uploadBusy && selectedMedia != null, modifier = Modifier.fillMaxWidth()) { Text(if (uploadBusy) "अपलोड हो रहा है…" else "वीडियो अपलोड और Publish करें") }
                    if (uploadBusy || uploadProgress > 0) { Spacer(Modifier.height(10.dp)); LinearProgressIndicator(progress = uploadProgress / 100f, modifier = Modifier.fillMaxWidth()); Text("$uploadProgress% · $uploadStatus", color = Cyan, fontSize = 12.sp) }
                    else if (uploadStatus.isNotBlank()) Text(uploadStatus, color = Cyan, fontSize = 12.sp)
                    if (uploadError.isNotBlank()) Text(uploadError, color = Color(0xFFFF8A8A), fontSize = 12.sp)
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
                    if (isGamingLive && liveActive) {
                        Spacer(Modifier.height(10.dp))
                        OutlinedButton(onClick = onToggleGamingMic, modifier = Modifier.fillMaxWidth()) {
                            Text(if (gamingMicEnabled) "🎙 Mic mute करें" else "🎙 Mic unmute करें")
                        }
                        Text(
                            if (gamingMicEnabled) "Mic चालू — screen stream जारी है।" else "Mic mute — screen stream जारी है।",
                            color = Cyan, fontSize = 12.sp
                        )
                    }
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
