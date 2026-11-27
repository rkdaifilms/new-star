package com.newstar.live

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

@Composable
fun NativeAuthScreen(onSignedIn: (NativeSession) -> Unit, onGoogleSignIn: () -> Unit) {
    val context = LocalContext.current
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var signup by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text("🌟 New Star", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(10.dp))
        Text(if (signup) "नया अकाउंट बनाएँ" else "अपने अकाउंट में Login करें")
        Spacer(Modifier.height(18.dp))
        OutlinedTextField(email, { email = it }, Modifier.fillMaxWidth(), label = { Text("Email") }, singleLine = true)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth(), label = { Text("Password") }, singleLine = true)
        Spacer(Modifier.height(12.dp))
        Button(
            enabled = !busy && email.contains("@") && password.length >= 6,
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                busy = true
                message = ""
                scope.launch {
                    try {
                        if (signup) {
                            val result = NativeSupabase.signUp(email.trim(), password)
                            val access = result.optString("access_token")
                            if (access.isBlank()) message = "Account बन गया। Email confirmation के बाद Login करें।"
                            else {
                                val user = result.optJSONObject("user") ?: JSONObject()
                                val session = NativeSession(access, result.optString("refresh_token"),
                                    System.currentTimeMillis() / 1000 + result.optLong("expires_in", 3600),
                                    user.optString("id"), user.optString("email", email.trim()))
                                NativeSessionStore.save(context, session)
                                onSignedIn(session)
                            }
                        } else onSignedIn(NativeSupabase.signIn(context, email.trim(), password))
                    } catch (e: Exception) { message = e.message ?: "Login नहीं हो सका।" }
                    finally { busy = false }
                }
            }
        ) { Text(if (busy) "कृपया प्रतीक्षा करें…" else if (signup) "Create Account" else "Login") }
        TextButton(onClick = { signup = !signup; message = "" }, enabled = !busy) {
            Text(if (signup) "पहले से अकाउंट है? Login" else "नया अकाउंट बनाएँ")
        }
        OutlinedButton(onClick = onGoogleSignIn, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text("Continue with Google")
        }
        if (message.isNotBlank()) Text(message, color = MaterialTheme.colorScheme.error)
    }
}

@Composable
fun NativeProfileScreen(session: NativeSession, onLogout: () -> Unit) {
    var profile by remember { mutableStateOf<JSONObject?>(null) }
    var videos by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var error by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var refresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(session.userId, refresh) {
        loading = true; error = ""
        try {
            val id = Uri.encode(session.userId)
            val rows = NativeSupabase.get("/rest/v1/profiles?select=user_id,username,full_name,avatar_url,bio&user_id=eq.$id", session)
            profile = rows.optJSONObject(0)
            val videoRows = NativeSupabase.get("/rest/v1/videos?select=id,caption,category,video_type,likes_count,views_count,created_at,processing_status&user_id=eq.$id&order=created_at.desc&limit=100", session)
            videos = (0 until videoRows.length()).mapNotNull { videoRows.optJSONObject(it) }
        } catch (e: Exception) { error = e.message ?: "Profile लोड नहीं हुई।" }
        finally { loading = false }
    }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("My Profile", style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = onLogout) { Text("Logout") }
        }
        if (loading) CircularProgressIndicator()
        else {
            Text(profile?.optString("full_name").orEmpty().ifBlank { profile?.optString("username").orEmpty().ifBlank { session.email } }, style = MaterialTheme.typography.titleLarge)
            Text("@"+profile?.optString("username").orEmpty())
            Text(profile?.optString("bio").orEmpty())
            Text("आपकी वीडियो पोस्ट: ${videos.size}")
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
            TextButton(onClick = { refresh++ }) { Text("Refresh") }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(videos) { item ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(item.optString("caption").ifBlank { "Untitled video" })
                            Text("${item.optString("category")} · ${item.optInt("likes_count")} likes · ${item.optInt("views_count")} views")
                            Text(item.optString("processing_status"))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun NativeInboxScreen(session: NativeSession) {
    var messages by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var peer by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var refresh by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(session.userId, refresh) {
        busy = true; error = ""
        try {
            val filter = Uri.encode("(sender_id.eq.${session.userId},recipient_id.eq.${session.userId})")
            val rows = NativeSupabase.get("/rest/v1/messages?select=id,conversation_id,sender_id,recipient_id,body,media_url,created_at&or=$filter&order=created_at.desc&limit=250", session)
            messages = (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }
        } catch (e: Exception) { error = e.message ?: "Inbox लोड नहीं हुई।" }
        finally { busy = false }
    }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Inbox", style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = { refresh++ }) { Text("Refresh") }
        }
        Text("नया संदेश भेजने के लिए प्राप्तकर्ता का User ID डालें।")
        OutlinedTextField(peer, { peer = it }, Modifier.fillMaxWidth(), label = { Text("Creator User ID") }, singleLine = true)
        OutlinedTextField(body, { body = it }, Modifier.fillMaxWidth(), label = { Text("Message") })
        Button(enabled = !busy && peer.isNotBlank() && body.isNotBlank(), onClick = {
            busy = true
            val messageText = body.trim()
            val target = peer.trim()
            scope.launch {
                try {
                    require(target != session.userId) { "आप खुद को Message नहीं भेज सकते।" }
                    val conversation = stableConversationId(session.userId, target)
                    NativeSupabase.post("/rest/v1/messages", JSONObject()
                        .put("conversation_id", conversation)
                        .put("sender_id", session.userId)
                        .put("recipient_id", target)
                        .put("body", messageText), session)
                    body = ""
                    refresh++
                } catch (e: Exception) { error = e.message ?: "Message नहीं भेजा गया।" }
                finally { busy = false }
            }
        }) { Text("Send Message") }
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(messages) { item ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(10.dp)) {
                        Text(if (item.optString("sender_id") == session.userId) "You" else item.optString("sender_id"))
                        Text(item.optString("body").ifBlank { "Media message" })
                        Text(item.optString("created_at"), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

@Composable
fun NativeUploadScreen(session: NativeSession) {
    val context = LocalContext.current
    var selected by remember { mutableStateOf<Uri?>(null) }
    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("General") }
    var progress by remember { mutableIntStateOf(0) }
    var status by remember { mutableStateOf("3 घंटे तक का वीडियो चुनें। अधिकतम आकार 5 GiB है।") }
    var uploading by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        selected = uri
        if (uri != null) {
            try { context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
            status = "वीडियो चुना गया। अब Publish दबाएँ।"
        }
    }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Native Video Upload", style = MaterialTheme.typography.headlineSmall)
        Button(onClick = { picker.launch(arrayOf("video/*")) }, enabled = !uploading) { Text("Gallery से वीडियो चुनें") }
        Text(selected?.lastPathSegment ?: "कोई वीडियो नहीं चुना गया")
        OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth(), label = { Text("Title") })
        OutlinedTextField(description, { description = it }, Modifier.fillMaxWidth(), label = { Text("Description") })
        OutlinedTextField(category, { category = it }, Modifier.fillMaxWidth(), label = { Text("Category") })
        LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
        Text("$progress%")
        Button(enabled = selected != null && !uploading, onClick = {
            val uri = selected ?: return@Button
            uploading = true; progress = 0
            scope.launch {
                try {
                    NativeVideoUploader.upload(context, uri, session, title, description, category) { progress = it }
                    progress = 100
                    status = "वीडियो अपलोड हो गया। Processing के बाद दिखाई देगा।"
                } catch (e: Exception) { status = e.message ?: "Upload असफल हुआ।" }
                finally { uploading = false }
            }
        }) { Text(if (uploading) "Uploading…" else "Publish Video") }
        Text(status, color = if (status.contains("असफल") || status.contains("Error")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
    }
}

private fun stableConversationId(first: String, second: String): String {
    val pair = listOf(first, second).sorted().joinToString(":")
    val digest = MessageDigest.getInstance("SHA-256").digest(("new-star-dm:" + pair).toByteArray(Charsets.UTF_8))
    val hex = digest.take(16).joinToString("") { "%02x".format(it) }
    return hex.substring(0, 8) + "-" + hex.substring(8, 12) + "-5" +
        hex.substring(13, 16) + "-a" + hex.substring(17, 20) + "-" + hex.substring(20, 32)
}

private object NativeVideoUploader {
    suspend fun upload(
        context: Context, uri: Uri, session: NativeSession, title: String, description: String,
        category: String, onProgress: (Int) -> Unit
    ) = withContext(Dispatchers.IO) {
        val size = context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
        require(size in 1..(5L * 1024 * 1024 * 1024)) { "वीडियो का आकार पढ़ नहीं सका या 5 GiB से बड़ा है।" }
        val retriever = MediaMetadataRetriever()
        val (duration, width, height) = try {
            retriever.setDataSource(context, uri)
            Triple(
                (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) / 1000,
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0,
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            )
        } finally { retriever.release() }
        require(duration in 1..10800 && width > 0 && height > 0) { "वीडियो metadata अमान्य है या अवधि 3 घंटे से अधिक है।" }
        val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else "video.mp4"
        } ?: "video.mp4"
        val metadata = listOf(
            "maxDurationSeconds " + b64(duration.toString()),
            "name " + b64(name.take(180)),
            "filetype " + b64(context.contentResolver.getType(uri) ?: "video/mp4")
        ).joinToString(",")
        val create = (URL("https://uzsusgoetrkujmofxcmb.supabase.co/functions/v1/cloudflare-tus-proxy").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; connectTimeout = 20000; readTimeout = 60000
            setRequestProperty("apikey", "sb_publishable_Zz2hljevMsXBcK8c33UvFw_wuc2itVr")
            setRequestProperty("Authorization", "Bearer ${session.accessToken}")
            setRequestProperty("Tus-Resumable", "1.0.0")
            setRequestProperty("Upload-Length", size.toString())
            setRequestProperty("Upload-Metadata", metadata)
        }
        val code = create.responseCode
        if (code !in 200..299) {
            val error = create.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            throw IllegalStateException("Upload session नहीं बनी ($code): $error")
        }
        val location = create.getHeaderField("Location") ?: error("Cloudflare upload location नहीं मिली।")
        val streamId = create.getHeaderField("stream-media-id") ?: error("Cloudflare Stream ID नहीं मिली।")
        create.disconnect()

        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .build()
        var offset = 0L
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "वीडियो फ़ाइल पढ़ नहीं सकी।" }
            val chunkSize = 8 * 1024 * 1024
            while (offset < size) {
                val wanted = minOf(chunkSize.toLong(), size - offset).toInt()
                val chunk = ByteArray(wanted)
                var read = 0
                while (read < wanted) {
                    val count = input.read(chunk, read, wanted - read)
                    if (count < 0) throw IllegalStateException("वीडियो पढ़ते समय फ़ाइल अधूरी मिली।")
                    read += count
                }
                val request = Request.Builder().url(location)
                    .header("Tus-Resumable", "1.0.0")
                    .header("Upload-Offset", offset.toString())
                    .patch(chunk.toRequestBody("application/offset+octet-stream".toMediaType()))
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IllegalStateException("वीडियो chunk upload असफल (${response.code}): ${response.body?.string().orEmpty()}")
                    }
                }
                offset += wanted
                onProgress(((offset * 100) / size).toInt())
            }
        }
        val username = session.email.substringBefore("@").ifBlank { "New Star Creator" }
        val caption = listOf(title.trim(), description.trim()).filter { it.isNotBlank() }.joinToString("\n\n").ifBlank { "New Star Video" }
        val row = JSONObject().apply {
            put("user_id", session.userId); put("video_url", "cloudflare://$streamId")
            put("video_provider", "cloudflare"); put("provider_video_id", streamId)
            put("provider_status", "processing"); put("processing_status", "processing")
            put("caption", caption); put("category", category.trim().ifBlank { "General" })
            put("username", username); put("likes_count", 0); put("views_count", 0)
            put("duration_seconds", duration); put("width", width); put("height", height)
            put("video_type", if (width < height) "short" else "long")
            put("visibility", "public"); put("audience_type", "all")
            put("media_kind", "video"); put("aspect_ratio_setting", "original")
            put("quality_preference", "original"); put("source_quality", "${width}x$height")
            put("frame_fit", "contain")
        }
        NativeSupabase.post("/rest/v1/videos", row, session)
    }

    private fun b64(value: String) = Base64.encodeToString(value.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
}


@Composable
fun NativeGamingScreen(
    session: NativeSession,
    onStart: (String) -> Unit,
    onStop: () -> Unit
) {
    var title by remember { mutableStateOf("") }
    var preparing by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("Gaming LIVE Android screen capture") }
    Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Native Gaming LIVE", style = MaterialTheme.typography.headlineSmall)
        Text("यह स्क्रीन Android MediaProjection permission और native GamingStreamService का उपयोग करती है। LIVE eligibility server पर जाँची जाएगी।")
        OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth(), label = { Text("Gaming room title") })
        Button(
            enabled = !preparing && title.trim().isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                preparing = true
                message = "Cloudflare LIVE room तैयार हो रहा है…"
                onStart(title.trim())
                preparing = false
            }
        ) { Text(if (preparing) "Preparing…" else "Start Gaming LIVE") }
        OutlinedButton(onClick = onStop, modifier = Modifier.fillMaxWidth()) { Text("Stop Gaming LIVE") }
        Text(message)
        Text("Streaming के लिए Android की screen-capture अनुमति आवश्यक है। Game/system audio की उपलब्धता Android version और device restrictions पर निर्भर करती है।", style = MaterialTheme.typography.bodySmall)
    }
}
