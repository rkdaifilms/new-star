package com.newstar.live

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
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
import androidx.compose.foundation.Image
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
import androidx.compose.ui.graphics.asImageBitmap
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
import androidx.media3.common.MediaItem
import androidx.media3.common.C
import androidx.media3.common.audio.SpeedProvider
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.effect.Crop
import androidx.media3.effect.RgbFilter
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.TextOverlay
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.text.style.BackgroundColorSpan
import android.text.style.AbsoluteSizeSpan
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
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
    var peerSearch by remember { mutableStateOf("") }
    var creatorResults by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var peerProfiles by remember { mutableStateOf<Map<String, JSONObject>>(emptyMap()) }
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

    LaunchedEffect(peerSearch, session.userId) {
        val term = peerSearch.trim().replace("%", "").replace("*", "").replace(",", " ")
            .replace("(", " ").replace(")", " ")
        if (term.length < 2) {
            creatorResults = emptyList()
        } else {
            delay(300)
            try {
                val filter = Uri.encode("(username.ilike.*$term*,full_name.ilike.*$term*)")
                val rows = NativeSupabase.get(
                    "/rest/v1/creator_public_profiles?select=user_id,username,full_name,avatar_url&or=$filter&order=username.asc&limit=15",
                    session
                )
                creatorResults = (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }
                    .filter { it.optString("user_id") != session.userId }
            } catch (_: Exception) { creatorResults = emptyList() }
        }
    }

    LaunchedEffect(peers.joinToString("|"), session.userId) {
        if (peers.isEmpty()) {
            peerProfiles = emptyMap()
        } else {
            try {
                val ids = Uri.encode("(${peers.joinToString(",")})")
                val rows = NativeSupabase.get(
                    "/rest/v1/creator_public_profiles?select=user_id,username,full_name,avatar_url&user_id=in.$ids",
                    session
                )
                peerProfiles = (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }
                    .associateBy { it.optString("user_id") }
            } catch (_: Exception) { peerProfiles = emptyMap() }
        }
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
        OutlinedTextField(
            value = peerSearch,
            onValueChange = { peerSearch = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Creator का नाम या @username खोजें") },
            singleLine = true
        )
        if (creatorResults.isNotEmpty()) {
            LazyColumn(Modifier.heightIn(max = 150.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(creatorResults) { creator ->
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            peer = creator.optString("user_id")
                            peerSearch = ""
                            creatorResults = emptyList()
                        }.padding(8.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                    ) {
                        AsyncImage(
                            model = creator.optString("avatar_url").takeIf { it.startsWith("https://") },
                            contentDescription = "Creator avatar",
                            modifier = Modifier.size(36.dp).clip(CircleShape)
                        )
                        Column(Modifier.padding(start = 10.dp)) {
                            Text(creator.optString("full_name").ifBlank { creator.optString("username") })
                            Text("@${creator.optString("username")}", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
        if (peers.isNotEmpty()) {
            Text("Conversations", style = MaterialTheme.typography.titleMedium)
            LazyColumn(Modifier.heightIn(max = 150.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(peers) { id ->
                    val latest = messages.firstOrNull {
                        it.optString("sender_id") == id || it.optString("recipient_id") == id
                    }
                    val profile = peerProfiles[id]
                    Surface(
                        modifier = Modifier.fillMaxWidth().clickable { peer = id },
                        color = if (peer == id) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Row(Modifier.padding(10.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            AsyncImage(
                                model = profile?.optString("avatar_url")?.takeIf { it.startsWith("https://") },
                                contentDescription = "Creator avatar",
                                modifier = Modifier.size(38.dp).clip(CircleShape)
                            )
                            Column(Modifier.padding(start = 10.dp)) {
                                Text(profile?.optString("full_name").orEmpty().ifBlank {
                                    profile?.optString("username").orEmpty().ifBlank { "Creator" }
                                })
                                Text("@${profile?.optString("username").orEmpty()}",
                                    style = MaterialTheme.typography.labelSmall)
                                Text(latest?.optString("body").orEmpty().ifBlank { "Media message" },
                                    maxLines = 1, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
        Text("Selected creator: " + (peerProfiles[peer]?.optString("full_name").orEmpty()
            .ifBlank { peerProfiles[peer]?.optString("username").orEmpty().ifBlank { peer } }))
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
                            Text(if (own) "You" else peerProfiles[peer]?.optString("full_name").orEmpty().ifBlank { peerProfiles[peer]?.optString("username").orEmpty().ifBlank { "Creator" } }, style = MaterialTheme.typography.labelSmall)
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
    var editingProgress by remember { mutableIntStateOf(0) }
    var status by remember { mutableStateOf("3 घंटे तक का वीडियो चुनें। अधिकतम आकार 5 GiB है।") }
    var uploading by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var trimStartSeconds by remember { mutableStateOf("0") }
    var trimEndSeconds by remember { mutableStateOf("") }
    var sourceDurationSeconds by remember { mutableIntStateOf(0) }
    var sourceWidth by remember { mutableIntStateOf(0) }
    var sourceHeight by remember { mutableIntStateOf(0) }
    var rotationDegrees by remember { mutableIntStateOf(0) }
    var muteOriginalAudio by remember { mutableStateOf(false) }
    var selectedFilter by remember { mutableStateOf("none") }
    var centerCrop by remember { mutableStateOf(false) }
    var exportSpeed by remember { mutableFloatStateOf(1f) }
    var overlayText by remember { mutableStateOf("") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        selected = uri
        if (uri != null) {
            try { context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
            try {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(context, uri)
                    sourceDurationSeconds = ((retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) / 1000L).toInt()
                    sourceWidth = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                    sourceHeight = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                    require(sourceDurationSeconds > 0 && sourceWidth > 0 && sourceHeight > 0) { "वीडियो की अवधि या resolution पढ़ नहीं सके।" }
                } finally {
                    retriever.release()
                }
                trimStartSeconds = "0"
                trimEndSeconds = sourceDurationSeconds.toString()
                rotationDegrees = 0
                muteOriginalAudio = false
                selectedFilter = "none"
                centerCrop = false
                exportSpeed = 1f
                overlayText = ""
                editing = true
                status = "वीडियो चुना गया। Trim, rotate या mute सेट करके Apply edits दबाएँ।"
            } catch (e: Exception) { status = e.message ?: "वीडियो metadata पढ़ नहीं सके।" }
        }
    }
    val scope = rememberCoroutineScope()
    val previewPlayer = remember(selected) {
        selected?.let { uri ->
            ExoPlayer.Builder(context).build().apply {
                setMediaItem(MediaItem.fromUri(uri))
                prepare()
                playWhenReady = false
            }
        }
    }
    DisposableEffect(previewPlayer) {
        onDispose { previewPlayer?.release() }
    }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Native Video Upload", style = MaterialTheme.typography.headlineSmall)
        Button(onClick = { picker.launch(arrayOf("video/*")) }, enabled = !uploading) { Text("Gallery से वीडियो चुनें") }
        Text(selected?.lastPathSegment ?: "कोई वीडियो नहीं चुना गया")
        if (selected != null && sourceWidth > 0 && sourceHeight > 0) {
            Text("Video Info · ${sourceWidth} × ${sourceHeight} · ${sourceDurationSeconds}s")
        }
        if (previewPlayer != null) {
            AndroidView(
                modifier = Modifier.fillMaxWidth().height(240.dp),
                factory = { viewContext ->
                    PlayerView(viewContext).apply {
                        player = previewPlayer
                        useController = true
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    }
                },
                update = { it.player = previewPlayer }
            )
        }
        if (editing && selected != null) {
            Text("Native editor · कुल अवधि $sourceDurationSeconds सेकंड", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = trimStartSeconds, onValueChange = { trimStartSeconds = it.filter(Char::isDigit) }, modifier = Modifier.weight(1f), label = { Text("Trim start (sec)") }, singleLine = true)
                OutlinedTextField(value = trimEndSeconds, onValueChange = { trimEndSeconds = it.filter(Char::isDigit) }, modifier = Modifier.weight(1f), label = { Text("Trim end (sec)") }, singleLine = true)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { rotationDegrees = (rotationDegrees + 90) % 360 }, enabled = !uploading) { Text("Rotate $rotationDegrees°") }
                FilterChip(selected = muteOriginalAudio, onClick = { muteOriginalAudio = !muteOriginalAudio }, label = { Text(if (muteOriginalAudio) "Original audio muted" else "Keep original audio") })
                FilterChip(selected = centerCrop, onClick = { centerCrop = !centerCrop }, label = { Text(if (centerCrop) "Center crop on" else "Center crop off") })
                OutlinedButton(onClick = {
                    trimStartSeconds = "0"
                    trimEndSeconds = sourceDurationSeconds.toString()
                    rotationDegrees = 0
                    muteOriginalAudio = false
                    selectedFilter = "none"
                    centerCrop = false
                    exportSpeed = 1f
                    overlayText = ""
                    status = "Editor settings reset हो गईं।"
                }, enabled = !uploading) { Text("Reset") }
            }
            Text("Export speed", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0.5f, 1f, 1.5f, 2f).forEach { speed ->
                    FilterChip(selected = exportSpeed == speed, onClick = { exportSpeed = speed }, enabled = !uploading, label = { Text("${speed}x") })
                }
            }
            OutlinedTextField(
                value = overlayText,
                onValueChange = { overlayText = it.take(100) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Video text overlay (optional)") },
                maxLines = 2,
                enabled = !uploading
            )
            Text("Color filter", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = selectedFilter == "none", onClick = { selectedFilter = "none" }, label = { Text("Original") })
                FilterChip(selected = selectedFilter == "grayscale", onClick = { selectedFilter = "grayscale" }, label = { Text("Grayscale") })
                FilterChip(selected = selectedFilter == "inverted", onClick = { selectedFilter = "inverted" }, label = { Text("Inverted") })
            }
            if (uploading && status.contains("edit/export")) {
                LinearProgressIndicator(progress = { editingProgress / 100f }, modifier = Modifier.fillMaxWidth())
                Text("Export $editingProgress%")
            }
            Button(enabled = !uploading, onClick = {
                val input = selected ?: return@Button
                val start = trimStartSeconds.toLongOrNull()
                val end = trimEndSeconds.toLongOrNull()
                if (start == null || end == null || start < 0 || end > sourceDurationSeconds || end <= start) {
                    status = "Trim समय सही रखें: Start 0 से शुरू हो और End, Start से बड़ा तथा वीडियो अवधि के अंदर हो।"
                    return@Button
                }
                uploading = true
                editingProgress = 0
                status = "वीडियो edit/export हो रहा है…"
                scope.launch {
                    try {
                        val output = NativeVideoEditor.export(context, input, start * 1000L, end * 1000L, rotationDegrees, muteOriginalAudio, selectedFilter, centerCrop, exportSpeed, overlayText) { value -> editingProgress = value }
                        selected = Uri.fromFile(output)
                        sourceDurationSeconds = (end - start).toInt()
                        trimStartSeconds = "0"
                        trimEndSeconds = sourceDurationSeconds.toString()
                        rotationDegrees = 0
                        muteOriginalAudio = false
                        exportSpeed = 1f
                        overlayText = ""
                        editing = false
                        editingProgress = 100
                        status = "Edited video तैयार है। अब Publish दबाएँ।"
                    } catch (e: Exception) { status = e.message ?: "Video editing असफल हुई।" }
                    finally { uploading = false }
                }
            }) { Text("Apply edits and save") }
        }
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

@OptIn(UnstableApi::class)
private object NativeVideoEditor {
    suspend fun export(context: Context, input: Uri, startMs: Long, endMs: Long, rotation: Int, muteAudio: Boolean, filter: String, centerCrop: Boolean, speed: Float, overlayText: String, onProgress: (Int) -> Unit): java.io.File =
        suspendCancellableCoroutine { continuation ->
            val output = java.io.File(context.cacheDir, "newstar-edit-" + System.currentTimeMillis() + ".mp4")
            val clipping = MediaItem.ClippingConfiguration.Builder().setStartPositionMs(startMs).setEndPositionMs(endMs).build()
            val mediaItem = MediaItem.Builder().setUri(input).setClippingConfiguration(clipping).build()
            val videoEffects = mutableListOf<androidx.media3.common.Effect>()
            if (rotation != 0) videoEffects.add(ScaleAndRotateTransformation.Builder().setRotationDegrees(rotation.toFloat()).build())
            if (centerCrop) videoEffects.add(Crop(-0.8f, 0.8f, -0.8f, 0.8f))
            when (filter) {
                "grayscale" -> videoEffects.add(RgbFilter.createGrayscaleFilter())
                "inverted" -> videoEffects.add(RgbFilter.createInvertedFilter())
                "none" -> Unit
                else -> error("Unsupported video filter.")
            }
            if (overlayText.isNotBlank()) {
                val text = SpannableString(overlayText.trim().take(100))
                text.setSpan(ForegroundColorSpan(Color.WHITE), 0, text.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                text.setSpan(BackgroundColorSpan(Color.argb(170, 0, 0, 0)), 0, text.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                text.setSpan(AbsoluteSizeSpan(64), 0, text.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                videoEffects.add(OverlayEffect(listOf(TextOverlay.createStaticTextOverlay(text))))
            }
            require(speed in 0.5f..2f) { "वीडियो speed 0.5x से 2x के बीच रखें।" }
            val speedProvider = object : SpeedProvider {
                override fun getSpeed(timeUs: Long): Float = speed
                override fun getNextSpeedChangeTimeUs(timeUs: Long): Long = C.TIME_UNSET
            }
            val edited = EditedMediaItem.Builder(mediaItem).setEffects(Effects(emptyList(), videoEffects)).setRemoveAudio(muteAudio).setSpeed(speedProvider).build()
            val progressHandler = android.os.Handler(android.os.Looper.getMainLooper())
            val progressHolder = androidx.media3.transformer.ProgressHolder()
            lateinit var progressPoll: Runnable
            val transformer = Transformer.Builder(context).addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    progressHandler.removeCallbacks(progressPoll)
                    if (continuation.isActive) {
                        onProgress(100)
                        continuation.resume(output)
                    }
                }
                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    progressHandler.removeCallbacks(progressPoll)
                    output.delete()
                    if (continuation.isActive) continuation.resumeWithException(exportException)
                }
            }).build()
            progressPoll = object : Runnable {
                override fun run() {
                    if (!continuation.isActive) return
                    try {
                        transformer.getProgress(progressHolder)
                        onProgress(progressHolder.progress.coerceIn(0, 99))
                    } catch (_: Exception) { }
                    if (continuation.isActive) progressHandler.postDelayed(this, 300L)
                }
            }
            continuation.invokeOnCancellation {
                progressHandler.removeCallbacks(progressPoll)
                try { transformer.cancel() } catch (_: Exception) { }
                output.delete()
            }
            try {
                transformer.start(edited, output.absolutePath)
                progressHandler.post(progressPoll)
            } catch (e: Exception) {
                progressHandler.removeCallbacks(progressPoll)
                output.delete()
                if (continuation.isActive) continuation.resumeWithException(e)
            }
        }
}

private object NativeVideoUploader {
    suspend fun upload(
        context: Context, uri: Uri, session: NativeSession, title: String, description: String,
        category: String, onProgress: (Int) -> Unit
    ) = withContext(Dispatchers.IO) {
        val queriedSize = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else -1L
            } ?: -1L
        }.getOrDefault(-1L)
        val assetDescriptorSize = runCatching {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
                descriptor.length
            } ?: -1L
        }.getOrDefault(-1L)
        val parcelDescriptorSize = runCatching {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                descriptor.statSize
            } ?: -1L
        }.getOrDefault(-1L)
        val size = listOf(queriedSize, assetDescriptorSize, parcelDescriptorSize)
            .firstOrNull { it > 0 } ?: -1L
        require(size in 1..(5L * 1024 * 1024 * 1024)) {
            "वीडियो का आकार इस फ़ाइल provider से नहीं पढ़ा जा सका या 5 GiB से बड़ा है। कृपया फ़ाइल को डिवाइस में डाउनलोड करके फिर चुनें।"
        }
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
        val locationHeader = create.getHeaderField("Location") ?: error("Cloudflare upload location नहीं मिली।")
        val createUrl = URL("https://uzsusgoetrkujmofxcmb.supabase.co/functions/v1/cloudflare-tus-proxy")
        val uploadUrl = URL(createUrl, locationHeader)
        require(uploadUrl.protocol == "https") { "Cloudflare upload URL सुरक्षित HTTPS नहीं है।" }
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
                val request = Request.Builder().url(uploadUrl)
                    .header("Tus-Resumable", "1.0.0")
                    .header("Upload-Offset", offset.toString())
                    .patch(chunk.toRequestBody("application/offset+octet-stream".toMediaType()))
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IllegalStateException("वीडियो chunk upload असफल (${response.code}): ${response.body?.string().orEmpty()}")
                    }
                    val confirmedOffset = response.header("Upload-Offset")?.toLongOrNull()
                    if (confirmedOffset != null && confirmedOffset != offset + wanted) {
                        throw IllegalStateException("Cloudflare ने upload offset की पुष्टि नहीं की; वीडियो सुरक्षित रूप से जारी नहीं किया गया।")
                    }
                }
                offset += wanted
                val percent = ((offset * 100) / size).toInt()
                withContext(Dispatchers.Main) { onProgress(percent) }
            }
        }
        val username = runCatching {
            val profileRows = NativeSupabase.get(
                "/rest/v1/profiles?select=username&user_id=eq.${Uri.encode(session.userId)}&limit=1", session
            )
            profileRows.optJSONObject(0)?.optString("username").orEmpty()
        }.getOrDefault("").ifBlank { session.email.substringBefore("@").ifBlank { "New Star Creator" } }
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
        }
        if (wallet == null && !loading && error.isBlank()) {
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


private data class NativeAiMessage(val role: String, val content: String)

@Composable
fun NativeCreatorAiScreen(session: NativeSession) {
    var prompt by remember { mutableStateOf("") }
    var messages by remember { mutableStateOf<List<NativeAiMessage>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var generatedImage by remember { mutableStateOf<Bitmap?>(null) }
    var imageRatio by remember { mutableStateOf("16:9") }
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize().padding(14.dp)) {
        Text("New Star Creator Studio AI", style = MaterialTheme.typography.headlineSmall)
        Text("AI chat और image generation — native Android", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(8.dp))
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(messages.size) { index ->
                val item = messages[index]
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(if (item.role == "user") "You" else "New Star AI",
                            style = MaterialTheme.typography.labelMedium)
                        Text(item.content)
                    }
                }
            }
            if (generatedImage != null) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Image(
                            bitmap = generatedImage!!.asImageBitmap(),
                            contentDescription = "AI generated creator image",
                            modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
                            contentScale = androidx.compose.ui.layout.ContentScale.Fit
                        )
                    }
                }
            }
        }
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        OutlinedTextField(
            value = prompt,
            onValueChange = { if (it.length <= 3500) prompt = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("अपना सवाल या image prompt लिखें") },
            minLines = 2,
            maxLines = 5,
            enabled = !busy
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                modifier = Modifier.weight(1f),
                enabled = !busy && prompt.isNotBlank(),
                onClick = {
                    val text = prompt.trim()
                    val history = (messages + NativeAiMessage("user", text)).takeLast(10)
                    val payload = JSONArray()
                    history.forEach { payload.put(JSONObject().put("role", it.role).put("content", it.content)) }
                    messages = history
                    prompt = ""
                    busy = true
                    error = ""
                    scope.launch {
                        try {
                            val response = NativeSupabase.invokeFunction(
                                "ai-gateway",
                                JSONObject().put("messages", payload).put("mode", "creator"),
                                session
                            )
                            if (!response.optBoolean("ok")) throw IllegalStateException(response.optString("error", "AI reply उपलब्ध नहीं है।"))
                            val answer = response.optString("answer").trim()
                            require(answer.isNotBlank()) { "AI ने खाली जवाब दिया।" }
                            messages = messages + NativeAiMessage("assistant", answer)
                        } catch (e: Exception) {
                            error = e.message ?: "AI request असफल हुई।"
                            messages = messages.dropLast(1)
                        } finally { busy = false }
                    }
                }
            ) { Text(if (busy) "Working…" else "Ask AI") }
            OutlinedButton(
                modifier = Modifier.weight(1f),
                enabled = !busy && prompt.isNotBlank(),
                onClick = {
                    val text = prompt.trim()
                    busy = true
                    error = ""
                    generatedImage = null
                    scope.launch {
                        try {
                            val response = NativeSupabase.invokeFunction(
                                "ai-gateway",
                                JSONObject().put("action", "image").put("prompt", text)
                                    .put("aspectRatio", imageRatio).put("imageSize", "1K"),
                                session
                            )
                            if (!response.optBoolean("ok")) throw IllegalStateException(response.optString("error", "Image generation failed"))
                            val encoded = response.optString("image")
                            require(encoded.isNotBlank()) { "AI ने image data नहीं लौटाया।" }
                            val bytes = Base64.decode(encoded, Base64.DEFAULT)
                            generatedImage = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                ?: throw IllegalStateException("Generated image decode नहीं हुई।")
                            prompt = ""
                        } catch (e: Exception) {
                            error = e.message ?: "Image generation असफल हुई।"
                        } finally { busy = false }
                    }
                }
            ) { Text("Create Image") }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = imageRatio == "16:9", onClick = { imageRatio = "16:9" }, label = { Text("16:9") })
            FilterChip(selected = imageRatio == "9:16", onClick = { imageRatio = "9:16" }, label = { Text("9:16") })
            FilterChip(selected = imageRatio == "1:1", onClick = { imageRatio = "1:1" }, label = { Text("1:1") })
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { messages = emptyList(); generatedImage = null; error = "" }, enabled = !busy) { Text("Clear") }
        }
    }
}
