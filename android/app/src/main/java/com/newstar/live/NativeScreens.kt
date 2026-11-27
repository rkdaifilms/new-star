package com.newstar.live

import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.webkit.MimeTypeMap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
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
    val context = LocalContext.current
    var profile by remember { mutableStateOf<JSONObject?>(null) }
    var videos by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var avatarUrl by remember { mutableStateOf("") }
    var coverUrl by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var photoBusy by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var fullName by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var bio by remember { mutableStateOf("") }
    var refresh by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    val avatarPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            photoBusy = true
            error = ""
            try {
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: error("Avatar फोटो पढ़ नहीं सकी।")
                require(bytes.size in 1..(5 * 1024 * 1024)) { "Avatar फोटो 5 MB से छोटी होनी चाहिए।" }
                val mime = context.contentResolver.getType(uri) ?: "image/jpeg"
                require(mime.startsWith("image/")) { "कृपया image file चुनें।" }
                val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)?.lowercase() ?: "jpg"
                val path = "${session.userId}/avatar_${System.currentTimeMillis()}.$ext"
                val url = NativeSupabase.uploadPublicObject("avatars", path, bytes, mime, session)
                val user = NativeSupabase.currentUser(session)
                val metadata = user.optJSONObject("user_metadata") ?: JSONObject()
                metadata.put("avatar_url", url)
                NativeSupabase.updateUserMetadata(metadata, session)
                NativeSupabase.patch("/rest/v1/profiles?user_id=eq.${Uri.encode(session.userId)}",
                    JSONObject().put("avatar_url", url), session)
                avatarUrl = url
                refresh++
            } catch (e: Exception) { error = e.message ?: "Avatar upload असफल हुआ।" }
            finally { photoBusy = false }
        }
    }

    val coverPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            photoBusy = true
            error = ""
            try {
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: error("Cover फोटो पढ़ नहीं सकी।")
                require(bytes.size in 1..(8 * 1024 * 1024)) { "Cover फोटो 8 MB से छोटी होनी चाहिए।" }
                val mime = context.contentResolver.getType(uri) ?: "image/jpeg"
                require(mime.startsWith("image/")) { "कृपया image file चुनें।" }
                val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)?.lowercase() ?: "jpg"
                val path = "${session.userId}/cover_${System.currentTimeMillis()}.$ext"
                val url = NativeSupabase.uploadPublicObject("avatars", path, bytes, mime, session)
                val user = NativeSupabase.currentUser(session)
                val metadata = user.optJSONObject("user_metadata") ?: JSONObject()
                metadata.put("cover_url", url)
                NativeSupabase.updateUserMetadata(metadata, session)
                coverUrl = url
            } catch (e: Exception) { error = e.message ?: "Cover upload असफल हुआ।" }
            finally { photoBusy = false }
        }
    }

    LaunchedEffect(session.userId, refresh) {
        loading = true; error = ""
        try {
            val id = Uri.encode(session.userId)
            val rows = NativeSupabase.get("/rest/v1/profiles?select=user_id,username,full_name,avatar_url,bio&user_id=eq.$id", session)
            profile = rows.optJSONObject(0)
            fullName = profile?.optString("full_name").orEmpty()
            username = profile?.optString("username").orEmpty()
            bio = profile?.optString("bio").orEmpty()
            avatarUrl = profile?.optString("avatar_url").orEmpty()
            val user = NativeSupabase.currentUser(session)
            coverUrl = user.optJSONObject("user_metadata")?.optString("cover_url").orEmpty()
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
            if (coverUrl.isNotBlank()) {
                AsyncImage(model = coverUrl, contentDescription = "Profile cover",
                    modifier = Modifier.fillMaxWidth().height(112.dp), contentScale = ContentScale.Crop)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                if (avatarUrl.isNotBlank()) {
                    AsyncImage(model = avatarUrl, contentDescription = "Profile photo",
                        modifier = Modifier.size(76.dp).clip(CircleShape), contentScale = ContentScale.Crop)
                }
                Column(Modifier.weight(1f)) {
                    Text(fullName.ifBlank { username.ifBlank { session.email } }, style = MaterialTheme.typography.titleLarge)
                    Text("@$username")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = !photoBusy, onClick = { avatarPicker.launch(arrayOf("image/*")) }) {
                    Text(if (photoBusy) "Uploading…" else "Change photo")
                }
                OutlinedButton(enabled = !photoBusy, onClick = { coverPicker.launch(arrayOf("image/*")) }) {
                    Text("Change cover")
                }
            }
            if (editing) {
                OutlinedTextField(fullName, { fullName = it }, Modifier.fillMaxWidth(), label = { Text("Display name") }, singleLine = true)
                OutlinedTextField(username, { username = it.trim().replace(" ", "") }, Modifier.fillMaxWidth(), label = { Text("Username") }, singleLine = true)
                OutlinedTextField(bio, { bio = it }, Modifier.fillMaxWidth(), label = { Text("Bio") }, minLines = 3)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = !saving && username.matches(Regex("[A-Za-z0-9_.]{3,30}")), onClick = {
                        saving = true; error = ""
                        scope.launch {
                            try {
                                val id = Uri.encode(session.userId)
                                NativeSupabase.patch("/rest/v1/profiles?user_id=eq.$id",
                                    JSONObject().put("full_name", fullName.trim())
                                        .put("username", username.trim()).put("bio", bio.trim()), session)
                                editing = false
                                refresh++
                            } catch (e: Exception) { error = e.message ?: "Profile save नहीं हुई।" }
                            finally { saving = false }
                        }
                    }) { Text(if (saving) "Saving…" else "Save profile") }
                    TextButton(enabled = !saving, onClick = {
                        fullName = profile?.optString("full_name").orEmpty()
                        username = profile?.optString("username").orEmpty()
                        bio = profile?.optString("bio").orEmpty()
                        editing = false
                    }) { Text("Cancel") }
                }
                Text("Username में 3–30 अक्षर: English letters, numbers, _ या .")
            } else {
                Text(bio)
                TextButton(onClick = { editing = true }) { Text("Edit profile") }
            }
            Text("आपकी वीडियो पोस्ट: ${videos.size}")
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
            TextButton(onClick = { refresh++ }, enabled = !saving && !photoBusy) { Text("Refresh") }
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
    val peers = remember(messages, session.userId) {
        messages.mapNotNull { row ->
            val sender = row.optString("sender_id")
            val recipient = row.optString("recipient_id")
            (if (sender == session.userId) recipient else sender).takeIf { it.isNotBlank() }
        }.distinct()
    }
    val thread = remember(messages, peer, session.userId) {
        messages.filter {
            val sender = it.optString("sender_id")
            val recipient = it.optString("recipient_id")
            (sender == session.userId && recipient == peer) ||
                (sender == peer && recipient == session.userId)
        }.sortedBy { it.optString("created_at") }
    }

    LaunchedEffect(session.userId) {
        while (true) {
            delay(15_000)
            refresh++
        }
    }

    LaunchedEffect(session.userId, refresh) {
        busy = true
        error = ""
        try {
            val filter = Uri.encode("(sender_id.eq.${session.userId},recipient_id.eq.${session.userId})")
            val rows = NativeSupabase.get(
                "/rest/v1/messages?select=id,conversation_id,sender_id,recipient_id,body,media_url,created_at,read_at&or=$filter&order=created_at.desc&limit=250",
                session
            )
            val loadedMessages = (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }
            messages = loadedMessages
            if (peer.isBlank()) {
                peer = loadedMessages.firstNotNullOfOrNull { item ->
                    val sender = item.optString("sender_id")
                    val recipient = item.optString("recipient_id")
                    (if (sender == session.userId) recipient else sender).takeIf { it.isNotBlank() }
                }.orEmpty()
            }
            val unreadIds = loadedMessages.filter {
                it.optString("recipient_id") == session.userId && it.isNull("read_at")
            }.map { it.optString("id") }.filter { it.isNotBlank() }
            if (unreadIds.isNotEmpty()) {
                val ids = Uri.encode("(${unreadIds.joinToString(",")})")
                NativeSupabase.patch(
                    "/rest/v1/messages?id=in.$ids&recipient_id=eq.${Uri.encode(session.userId)}",
                    JSONObject().put("read_at", java.time.Instant.now().toString()),
                    session
                )
            }
        } catch (e: Exception) {
            error = e.message ?: "Inbox लोड नहीं हुई।"
        } finally { busy = false }
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text("Inbox", style = MaterialTheme.typography.headlineSmall)
                Text("Messages auto-refresh every 15 seconds", style = MaterialTheme.typography.labelSmall)
            }
            TextButton(onClick = { refresh++ }, enabled = !busy) { Text("Refresh") }
        }
        if (peers.isNotEmpty()) {
            Text("Conversations", style = MaterialTheme.typography.titleMedium)
            LazyColumn(Modifier.heightIn(max = 150.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(peers) { id ->
                    val latest = messages.firstOrNull {
                        it.optString("sender_id") == id || it.optString("recipient_id") == id
                    }
                    Surface(
                        modifier = Modifier.fillMaxWidth().clickable { peer = id },
                        color = if (peer == id) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Column(Modifier.padding(10.dp)) {
                            Text(id, style = MaterialTheme.typography.labelMedium)
                            Text(latest?.optString("body").orEmpty().ifBlank { "Message" },
                                maxLines = 1, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
        Text("नया संदेश भेजने के लिए Creator User ID डालें।")
        OutlinedTextField(peer, { peer = it }, Modifier.fillMaxWidth(),
            label = { Text("Creator User ID") }, singleLine = true)
        OutlinedTextField(body, { body = it }, Modifier.fillMaxWidth(),
            label = { Text("Message") }, minLines = 2)
        Button(enabled = !busy && peer.isNotBlank() && body.isNotBlank(), onClick = {
            busy = true
            val messageText = body.trim()
            val target = peer.trim()
            scope.launch {
                try {
                    require(target != session.userId) { "आप खुद को Message नहीं भेज सकते।" }
                    require(messageText.length <= 5000) { "Message 5000 अक्षरों से छोटा रखें।" }
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
        }) { Text(if (busy) "Please wait…" else "Send Message") }
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        Text("Selected conversation", style = MaterialTheme.typography.titleMedium)
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(thread) { item ->
                val own = item.optString("sender_id") == session.userId
                Row(Modifier.fillMaxWidth(), horizontalArrangement = if (own) Arrangement.End else Arrangement.Start) {
                    Card(colors = CardDefaults.cardColors(
                        containerColor = if (own) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                    )) {
                        Column(Modifier.padding(10.dp)) {
                            Text(if (own) "You" else "Creator", style = MaterialTheme.typography.labelSmall)
                            Text(item.optString("body").ifBlank { "Media message" })
                            Text(item.optString("created_at"), style = MaterialTheme.typography.labelSmall)
                        }
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
                val percent = ((offset * 100) / size).toInt()
                withContext(Dispatchers.Main) { onProgress(percent) }
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
    val context = LocalContext.current
    var title by remember { mutableStateOf("") }
    var preparing by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("Gaming LIVE Android screen capture") }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                message = intent?.getStringExtra("message") ?: "Gaming LIVE status update"
                val state = intent?.getStringExtra("status").orEmpty()
                preparing = state == "starting" || state == "connecting"
            }
        }
        ContextCompat.registerReceiver(
            context, receiver, IntentFilter(GamingStreamService.STATUS),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }
    Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Native Gaming LIVE", style = MaterialTheme.typography.headlineSmall)
        Text("यह स्क्रीन Android MediaProjection permission और native GamingStreamService का उपयोग करती है। LIVE eligibility server पर जाँची जाएगी।")
        OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth(), label = { Text("Gaming room title") })
        Button(
            enabled = !preparing && title.trim().isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                preparing = true
                message = "Cloudflare LIVE room तैयार हो रहा है… Android permission prompt की प्रतीक्षा करें।"
                onStart(title.trim())
            }
        ) { Text(if (preparing) "Preparing…" else "Start Gaming LIVE") }
        OutlinedButton(onClick = onStop, modifier = Modifier.fillMaxWidth()) { Text("Stop Gaming LIVE") }
        Text(message)
        Text("Streaming के लिए Android की screen-capture अनुमति आवश्यक है। Game/system audio की उपलब्धता Android version और device restrictions पर निर्भर करती है।", style = MaterialTheme.typography.bodySmall)
    }
}


@Composable
fun NativeWalletScreen(session: NativeSession) {
    var wallet by remember { mutableStateOf<JSONObject?>(null) }
    var transactions by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var withdrawals by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    var refresh by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(session.userId, refresh) {
        loading = true
        error = ""
        try {
            val userId = Uri.encode(session.userId)
            val walletRows = NativeSupabase.get(
                "/rest/v1/user_wallets?select=coin_balance,real_balance&user_id=eq.$userId&limit=1", session
            )
            wallet = walletRows.optJSONObject(0)
            val txRows = NativeSupabase.get(
                "/rest/v1/coin_transactions?select=id,sender_id,receiver_id,gift_name,coins_deducted,created_at&or=(sender_id.eq.$userId,receiver_id.eq.$userId)&order=created_at.desc&limit=30",
                session
            )
            transactions = (0 until txRows.length()).mapNotNull { txRows.optJSONObject(it) }
            val withdrawalRows = NativeSupabase.get(
                "/rest/v1/withdrawal_requests?select=id,amount,status,currency,created_at&creator_id=eq.$userId&order=created_at.desc&limit=20",
                session
            )
            withdrawals = (0 until withdrawalRows.length()).mapNotNull { withdrawalRows.optJSONObject(it) }
        } catch (e: Exception) {
            error = e.message ?: "Wallet data लोड नहीं हुआ।"
        } finally { loading = false }
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Wallet & Earnings", style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = { refresh++ }, enabled = !loading) { Text("Refresh") }
        }
        Text("यहाँ केवल सर्वर पर उपलब्ध वास्तविक बैलेंस और रिकॉर्ड दिखते हैं।")
        if (loading) CircularProgressIndicator()
        wallet?.let { balance ->
            Card(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text("Coins", style = MaterialTheme.typography.labelLarge)
                    Text(balance.optInt("coin_balance").toString(), style = MaterialTheme.typography.headlineMedium)
                    Spacer(Modifier.height(12.dp))
                    Text("Creator earnings", style = MaterialTheme.typography.labelLarge)
                    Text(balance.optString("real_balance", "0"), style = MaterialTheme.typography.headlineMedium)
                }
            }
        } ?: if (!loading && error.isBlank()) {
            Text("इस अकाउंट के लिए Wallet रिकॉर्ड अभी उपलब्ध नहीं है।")
        }
        Text("Gift / Coin transactions", style = MaterialTheme.typography.titleLarge)
        LazyColumn(Modifier.weight(1f, fill = true), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(transactions) { tx ->
                val sent = tx.optString("sender_id") == session.userId
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(tx.optString("gift_name").ifBlank { "Gift transaction" },
                            style = MaterialTheme.typography.titleMedium)
                        Text((if (sent) "Sent" else "Received") + " · " +
                            tx.optInt("coins_deducted") + " coins")
                        Text(tx.optString("created_at"), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            if (transactions.isEmpty() && !loading) item { Text("अभी कोई transaction नहीं है।") }
            item { Text("Withdrawal history", style = MaterialTheme.typography.titleLarge) }
            items(withdrawals) { request ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text("${request.optString("currency").trim()} ${request.optString("amount")}")
                        Text("Status: ${request.optString("status")}")
                        Text(request.optString("created_at"), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            if (withdrawals.isEmpty() && !loading) item { Text("अभी कोई withdrawal record नहीं है।") }
        }
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
    }
}
