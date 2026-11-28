package com.newstar.live

import android.content.Context
import android.content.ContentValues
import android.os.Build
import android.provider.MediaStore
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture
import androidx.camera.video.PendingRecording
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.io.File
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.webkit.MimeTypeMap
import android.media.MediaMetadataRetriever
import android.media.MediaRecorder
import android.media.MediaPlayer
import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.IntOffset
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import androidx.media3.common.audio.GainProcessor
import androidx.media3.common.audio.ToInt16PcmAudioProcessor
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.compose.ui.viewinterop.AndroidView
import org.webrtc.SurfaceViewRenderer
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.effect.Crop
import androidx.media3.effect.RgbFilter
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.TextOverlay
import androidx.media3.effect.StaticOverlaySettings
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
import androidx.work.WorkManager
import androidx.work.WorkInfo
import androidx.compose.runtime.livedata.observeAsState

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
    var coverScale by remember { mutableFloatStateOf(1f) }
    var coverX by remember { mutableFloatStateOf(0f) }
    var coverY by remember { mutableFloatStateOf(0f) }
    var coverPreviewSize by remember { mutableStateOf(IntSize.Zero) }
    var coverSaveBusy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var photoBusy by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var fullName by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var bio by remember { mutableStateOf("") }
    var isPrivate by remember { mutableStateOf(false) }
    var locationVisibility by remember { mutableStateOf("hidden") }
    var locationQuery by remember { mutableStateOf("") }
    var locationResults by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var selectedLocation by remember { mutableStateOf<JSONObject?>(null) }
    var locationSearching by remember { mutableStateOf(false) }
    var socialLinks by remember { mutableStateOf(mapOf(
        "website" to "", "facebook" to "", "instagram" to "", "youtube" to "",
        "tiktok" to "", "whatsapp" to "", "phone" to ""
    )) }
    var refresh by remember { mutableIntStateOf(0) }
    var videoTab by remember { mutableStateOf("All Videos") }
    val filteredVideos = remember(videos, videoTab) {
        videos.filter { row ->
            when (videoTab) {
                "Short Videos" -> row.optString("video_type").equals("short", true)
                "Long Videos" -> row.optString("video_type").equals("long", true)
                else -> true
            }
        }
    }
    val scope = rememberCoroutineScope()

    LaunchedEffect(locationQuery) {
        val query = locationQuery.trim()
        if (query.length < 2) {
            locationResults = emptyList()
            locationSearching = false
        } else {
            kotlinx.coroutines.delay(350)
            locationSearching = true
            try {
                val result = NativeSupabase.invokeFunction(
                    "mapbox-geocode",
                    JSONObject().put("query", query).put("scope", "creator"),
                    session
                )
                val features = result.optJSONArray("features") ?: JSONArray()
                locationResults = (0 until features.length()).mapNotNull { features.optJSONObject(it) }
                if (locationQuery.trim() == query && locationResults.isEmpty()) error = "इस नाम से कोई शहर या इलाका नहीं मिला।"
            } catch (e: Exception) {
                if (locationQuery.trim() == query) error = e.message ?: "Location search नहीं चला।"
                locationResults = emptyList()
            } finally {
                if (locationQuery.trim() == query) locationSearching = false
            }
        }
    }

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
                metadata.put("cover_transform", JSONObject().put("scale", coverScale).put("x", coverX).put("y", coverY))
                NativeSupabase.updateUserMetadata(metadata, session)
                NativeSupabase.upsert(
                    "/rest/v1/creator_profile_extras",
                    JSONObject().put("user_id", session.userId)
                        .put("cover_url", url)
                        .put("cover_transform", metadata.optJSONObject("cover_transform") ?: JSONObject()),
                    session
                )
                coverUrl = url
            } catch (e: Exception) { error = e.message ?: "Cover upload असफल हुआ।" }
            finally { photoBusy = false }
        }
    }

    LaunchedEffect(session.userId, refresh) {
        loading = true; error = ""
        try {
            val id = Uri.encode(session.userId)
            val rows = NativeSupabase.get("/rest/v1/profiles?select=user_id,username,full_name,avatar_url,bio,is_private,creator_location_visibility,creator_location_name,creator_location_latitude,creator_location_longitude,creator_mapbox_place_id&user_id=eq.$id", session)
            profile = rows.optJSONObject(0)
            fullName = profile?.optString("full_name").orEmpty()
            username = profile?.optString("username").orEmpty()
            bio = profile?.optString("bio").orEmpty()
            avatarUrl = profile?.optString("avatar_url").orEmpty()
            isPrivate = profile?.optBoolean("is_private") ?: false
            locationVisibility = profile?.optString("creator_location_visibility").takeUnless { it.isNullOrBlank() || it == "null" } ?: "hidden"
            locationQuery = profile?.optString("creator_location_name").orEmpty()
            selectedLocation = if (locationQuery.isNotBlank()) JSONObject()
                .put("id", profile?.optString("creator_mapbox_place_id").orEmpty())
                .put("name", locationQuery)
                .put("latitude", profile?.optDouble("creator_location_latitude") ?: Double.NaN)
                .put("longitude", profile?.optDouble("creator_location_longitude") ?: Double.NaN)
                else null
            val user = NativeSupabase.currentUser(session)
            val metadata = user.optJSONObject("user_metadata") ?: JSONObject()
            val extrasRows = NativeSupabase.get(
                "/rest/v1/creator_profile_extras?select=cover_url,cover_transform,website,facebook,instagram,youtube,tiktok&user_id=eq.$id",
                session
            )
            val extras = extrasRows.optJSONObject(0)
            coverUrl = extras?.optString("cover_url")?.takeIf { it.isNotBlank() && it != "null" }
                ?: metadata.optString("cover_url").orEmpty()
            val coverTransform = extras?.optJSONObject("cover_transform")
                ?: metadata.optJSONObject("cover_transform") ?: JSONObject()
            coverScale = coverTransform.optDouble("scale", 1.0).toFloat().coerceIn(1f, 3.5f)
            coverX = coverTransform.optDouble("x", 0.0).toFloat().coerceIn(-50f, 50f)
            coverY = coverTransform.optDouble("y", 0.0).toFloat().coerceIn(-50f, 50f)
            socialLinks = socialLinks.mapValues { (key, _) ->
                extras?.optString(key)?.takeIf { it.isNotBlank() && it != "null" }
                    ?: metadata.optString(key).takeUnless { it == "null" }.orEmpty()
            }
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
                Text("Profile cover · 16:9", style = MaterialTheme.typography.titleSmall)
                Box(
                    Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp))
                        .onSizeChanged { coverPreviewSize = it }
                        .pointerInput(coverPreviewSize) {
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                if (coverPreviewSize.width > 0 && coverPreviewSize.height > 0) {
                                    coverX = (coverX + dragAmount.x / coverPreviewSize.width * 100f).coerceIn(-50f, 50f)
                                    coverY = (coverY + dragAmount.y / coverPreviewSize.height * 100f).coerceIn(-50f, 50f)
                                }
                            }
                        }
                ) {
                    AsyncImage(
                        model = coverUrl, contentDescription = "Profile cover preview",
                        modifier = Modifier.fillMaxSize().graphicsLayer {
                            scaleX = coverScale
                            scaleY = coverScale
                            translationX = coverX / 100f * coverPreviewSize.width
                            translationY = coverY / 100f * coverPreviewSize.height
                        },
                        contentScale = ContentScale.Crop
                    )
                }
                Text("एक उँगली से फोटो खिसकाएँ। Zoom और Position नीचे सेट करें।",
                    style = MaterialTheme.typography.bodySmall)
                Text("Zoom")
                Slider(value = coverScale, onValueChange = { coverScale = it }, valueRange = 1f..3.5f)
                Text("Horizontal position")
                Slider(value = coverX, onValueChange = { coverX = it }, valueRange = -50f..50f)
                Text("Vertical position")
                Slider(value = coverY, onValueChange = { coverY = it }, valueRange = -50f..50f)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { coverScale = 1f; coverX = 0f; coverY = 0f }) { Text("Reset position") }
                    Button(enabled = !coverSaveBusy, onClick = {
                        scope.launch {
                            coverSaveBusy = true
                            error = ""
                            try {
                                val user = NativeSupabase.currentUser(session)
                                val metadata = user.optJSONObject("user_metadata") ?: JSONObject()
                                val transform = JSONObject().put("scale", coverScale.coerceIn(1f, 3.5f))
                                    .put("x", coverX.coerceIn(-50f, 50f))
                                    .put("y", coverY.coerceIn(-50f, 50f))
                                metadata.put("cover_url", coverUrl).put("cover_transform", transform)
                                NativeSupabase.updateUserMetadata(metadata, session)
                                NativeSupabase.upsert(
                                    "/rest/v1/creator_profile_extras",
                                    JSONObject().put("user_id", session.userId)
                                        .put("cover_url", coverUrl)
                                        .put("cover_transform", transform)
                                        .put("website", socialLinks["website"].orEmpty())
                                        .put("facebook", socialLinks["facebook"].orEmpty())
                                        .put("instagram", socialLinks["instagram"].orEmpty())
                                        .put("youtube", socialLinks["youtube"].orEmpty())
                                        .put("tiktok", socialLinks["tiktok"].orEmpty()),
                                    session
                                )
                            } catch (e: Exception) {
                                error = e.message ?: "Cover position सेव नहीं हुई।"
                            } finally { coverSaveBusy = false }
                        }
                    }) { Text(if (coverSaveBusy) "Saving…" else "Save cover position") }
                }
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
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Private account", style = MaterialTheme.typography.titleSmall)
                        Text("केवल approved followers आपकी private videos देख पाएँगे।",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = isPrivate, onCheckedChange = { isPrivate = it })
                }
                Text("Creator location", style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(
                    value = locationQuery,
                    onValueChange = { locationQuery = it; selectedLocation = null },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("City, area or country") },
                    singleLine = true,
                    trailingIcon = {
                        if (locationQuery.isNotBlank()) TextButton(onClick = {
                            locationQuery = ""; selectedLocation = null; locationResults = emptyList()
                        }) { Text("Clear") }
                    }
                )
                if (locationSearching) {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text("दुनिया भर में जगह खोज रहे हैं…", Modifier.padding(start = 8.dp))
                    }
                }
                if (locationResults.isNotEmpty()) {
                    Column(Modifier.fillMaxWidth().heightIn(max = 190.dp)) {
                        locationResults.forEach { feature ->
                            val center = feature.optJSONArray("center")
                            val valid = center != null && center.length() >= 2 &&
                                center.optDouble(0, Double.NaN).isFinite() &&
                                center.optDouble(1, Double.NaN).isFinite()
                            if (valid) TextButton(
                                onClick = {
                                    selectedLocation = JSONObject()
                                        .put("id", feature.optString("id"))
                                        .put("name", feature.optString("name").take(240))
                                        .put("longitude", center!!.optDouble(0))
                                        .put("latitude", center!!.optDouble(1))
                                    locationQuery = feature.optString("name").take(240)
                                    locationResults = emptyList()
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text(feature.optString("name"), Modifier.fillMaxWidth()) }
                        }
                    }
                }
                Text(
                    selectedLocation?.optString("name")?.let { "✓ Selected: $it" }
                        ?: "नतीजे में से शहर/इलाका चुनें। घर का पता न डालें।",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text("Creator location visibility", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = locationVisibility == "hidden",
                        onClick = { locationVisibility = "hidden" }, label = { Text("Hidden") })
                    FilterChip(selected = locationVisibility == "public",
                        onClick = { locationVisibility = "public" }, label = { Text("Public") })
                }
                Text("Social links", style = MaterialTheme.typography.titleSmall)
                listOf("website" to "Website", "facebook" to "Facebook",
                    "instagram" to "Instagram", "youtube" to "YouTube",
                    "tiktok" to "TikTok", "whatsapp" to "WhatsApp", "phone" to "Phone"
                ).forEach { (key, label) ->
                    OutlinedTextField(
                        value = socialLinks[key].orEmpty(),
                        onValueChange = { value -> socialLinks = socialLinks.toMutableMap().apply { put(key, value) } },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(label) },
                        singleLine = true
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = !saving && username.matches(Regex("[A-Za-z0-9_.]{3,30}")), onClick = {
                        saving = true; error = ""
                        scope.launch {
                            try {
                                val id = Uri.encode(session.userId)
                                val cleanName = fullName.trim()
                                val cleanUsername = username.trim()
                                val cleanBio = bio.trim()
                                val user = NativeSupabase.currentUser(session)
                                val metadata = user.optJSONObject("user_metadata") ?: JSONObject()
                                metadata.put("full_name", cleanName)
                                    .put("username", cleanUsername)
                                    .put("bio", cleanBio)
                                socialLinks.forEach { (key, value) -> metadata.put(key, value.trim()) }
                                NativeSupabase.updateUserMetadata(metadata, session)
                                NativeSupabase.upsert(
                                    "/rest/v1/creator_profile_extras",
                                    JSONObject().put("user_id", session.userId)
                                        .put("cover_url", coverUrl.takeIf { it.isNotBlank() } ?: JSONObject.NULL)
                                        .put("cover_transform", JSONObject().put("scale", coverScale)
                                            .put("x", coverX).put("y", coverY))
                                        .put("website", socialLinks["website"].orEmpty().trim())
                                        .put("facebook", socialLinks["facebook"].orEmpty().trim())
                                        .put("instagram", socialLinks["instagram"].orEmpty().trim())
                                        .put("youtube", socialLinks["youtube"].orEmpty().trim())
                                        .put("tiktok", socialLinks["tiktok"].orEmpty().trim()),
                                    session
                                )
                                NativeSupabase.patch("/rest/v1/profiles?user_id=eq.$id",
                                    JSONObject().put("full_name", cleanName)
                                        .put("username", cleanUsername)
                                        .put("bio", cleanBio)
                                        .put("is_private", isPrivate)
                                        .put("creator_location_visibility", locationVisibility)
                                        .put("creator_location_name", selectedLocation?.optString("name") ?: JSONObject.NULL)
                                        .put("creator_location_latitude", selectedLocation?.optDouble("latitude")?.takeIf { it.isFinite() } ?: JSONObject.NULL)
                                        .put("creator_location_longitude", selectedLocation?.optDouble("longitude")?.takeIf { it.isFinite() } ?: JSONObject.NULL)
                                        .put("creator_mapbox_place_id", selectedLocation?.optString("id") ?: JSONObject.NULL), session)
                                profile = (profile ?: JSONObject()).put("full_name", cleanName)
                                    .put("username", cleanUsername).put("bio", cleanBio)
                                    .put("is_private", isPrivate)
                                    .put("creator_location_visibility", locationVisibility)
                                    .put("creator_location_name", selectedLocation?.optString("name") ?: JSONObject.NULL)
                                    .put("creator_location_latitude", selectedLocation?.optDouble("latitude")?.takeIf { it.isFinite() } ?: JSONObject.NULL)
                                    .put("creator_location_longitude", selectedLocation?.optDouble("longitude")?.takeIf { it.isFinite() } ?: JSONObject.NULL)
                                    .put("creator_mapbox_place_id", selectedLocation?.optString("id") ?: JSONObject.NULL)
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
                        isPrivate = profile?.optBoolean("is_private") ?: false
                        locationVisibility = profile?.optString("creator_location_visibility")
                            .takeUnless { it.isNullOrBlank() || it == "null" } ?: "hidden"
                        locationQuery = profile?.optString("creator_location_name").orEmpty()
                        selectedLocation = if (locationQuery.isNotBlank()) JSONObject()
                            .put("id", profile?.optString("creator_mapbox_place_id").orEmpty())
                            .put("name", locationQuery)
                            .put("latitude", profile?.optDouble("creator_location_latitude") ?: Double.NaN)
                            .put("longitude", profile?.optDouble("creator_location_longitude") ?: Double.NaN)
                            else null
                        editing = false
                    }) { Text("Cancel") }
                }
                Text("Username में 3–30 अक्षर: English letters, numbers, _ या .")
            } else {
                Text(bio)
                val visibleSocialLinks = listOf(
                    "website" to "Website", "facebook" to "Facebook",
                    "instagram" to "Instagram", "youtube" to "YouTube",
                    "tiktok" to "TikTok", "whatsapp" to "WhatsApp",
                    "phone" to "Phone"
                ).filter { (key, _) -> socialLinks[key].orEmpty().isNotBlank() }
                if (visibleSocialLinks.isNotEmpty()) {
                    Text("Social links", style = MaterialTheme.typography.titleSmall)
                    visibleSocialLinks.forEach { (key, label) ->
                        TextButton(onClick = {
                            val raw = socialLinks[key].orEmpty().trim()
                            val destination = when (key) {
                                "phone" -> "tel:" + raw.filter { it.isDigit() || it == '+' }
                                "whatsapp" -> {
                                    val number = raw.filter { it.isDigit() }
                                    if (number.isNotBlank()) "https://wa.me/$number"
                                    else if (raw.startsWith("https://") || raw.startsWith("http://")) raw else ""
                                }
                                else -> if (raw.startsWith("https://") || raw.startsWith("http://")) raw
                                    else if (key == "website" && raw.isNotBlank()) "https://$raw" else ""
                            }
                            if (destination.isBlank()) {
                                error = "इस $label लिंक का सही URL या नंबर उपलब्ध नहीं है।"
                            } else {
                                try {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(destination)))
                                } catch (e: Exception) {
                                    error = e.message ?: "$label लिंक नहीं खुल सका।"
                                }
                            }
                        }) { Text("$label · ${socialLinks[key]}") }
                    }
                }
                TextButton(onClick = { editing = true }) { Text("Edit profile") }
            }
            Text("आपकी वीडियो पोस्ट: ${videos.size}")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("All Videos", "Short Videos", "Long Videos").forEach { tab ->
                    FilterChip(selected = videoTab == tab, onClick = { videoTab = tab }, label = { Text(tab) })
                }
            }
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
            TextButton(onClick = { refresh++ }, enabled = !saving && !photoBusy) { Text("Refresh") }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(filteredVideos) { item ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(item.optString("caption").ifBlank { "Untitled video" })
                            Text("${item.optString("category")} · ${item.optInt("likes_count")} likes · ${item.optInt("views_count")} views")
                            Text(item.optString("processing_status"))
                        }
                    }
                }
                if (filteredVideos.isEmpty()) item { Text("इस श्रेणी में अभी कोई वीडियो नहीं है।") }
            }
        }
    }
}

@Composable
fun NativeInboxScreen(session: NativeSession, initialPeer: String? = null) {
    val context = LocalContext.current
    var messages by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var peer by remember(initialPeer) { mutableStateOf(initialPeer.orEmpty()) }
    var peerSearch by remember { mutableStateOf("") }
    var creatorResults by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var peerProfiles by remember { mutableStateOf<Map<String, JSONObject>>(emptyMap()) }
    var followRequests by remember(session.userId) { mutableStateOf<List<JSONObject>>(emptyList()) }
    var followRequestProfiles by remember(session.userId) { mutableStateOf<Map<String, JSONObject>>(emptyMap()) }
    var followRequestsLoading by remember(session.userId) { mutableStateOf(true) }
    var requestActionIds by remember(session.userId) { mutableStateOf<Set<String>>(emptySet()) }
    var relationTab by remember(session.userId) { mutableStateOf("followers") }
    var relationRows by remember(session.userId) { mutableStateOf<List<JSONObject>>(emptyList()) }
    var relationProfiles by remember(session.userId) { mutableStateOf<Map<String, JSONObject>>(emptyMap()) }
    var relationsLoading by remember(session.userId) { mutableStateOf(false) }
    var relationActionIds by remember(session.userId) { mutableStateOf<Set<String>>(emptySet()) }
    var body by remember { mutableStateOf("") }
    var attachmentUrl by remember { mutableStateOf("") }
    var selectedAttachmentUri by remember { mutableStateOf<Uri?>(null) }
    var selectedAttachmentName by remember { mutableStateOf("") }
    var voiceFile by remember { mutableStateOf<File?>(null) }
    var voiceRecording by remember { mutableStateOf(false) }
    var voicePlaying by remember { mutableStateOf(false) }
    var voiceError by remember { mutableStateOf("") }
    var voiceRecorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var voicePlayer by remember { mutableStateOf<android.media.MediaPlayer?>(null) }
    var remoteAudioPlayer by remember { mutableStateOf<android.media.MediaPlayer?>(null) }
    var remoteAudioPlayingUrl by remember { mutableStateOf("") }
    var signedMediaUrls by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var error by remember { mutableStateOf("") }
    var refresh by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val attachmentPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            voicePlayer?.release()
            voicePlayer = null
            voicePlaying = false
            voiceFile?.delete()
            voiceFile = null
        }
        selectedAttachmentUri = uri
        selectedAttachmentName = uri?.lastPathSegment?.substringAfterLast('/')?.take(100).orEmpty()
        if (uri != null) attachmentUrl = ""
    }
    val microphonePermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            try {
                val output = File(context.cacheDir, "inbox-voice-${java.util.UUID.randomUUID()}.m4a")
                val recorder = MediaRecorder()
                recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
                recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                recorder.setAudioEncodingBitRate(96000)
                recorder.setAudioSamplingRate(44100)
                recorder.setOutputFile(output.absolutePath)
                recorder.prepare()
                recorder.start()
                voiceRecorder = recorder
                voiceFile = output
                voiceRecording = true
                voiceError = ""
            } catch (e: Exception) {
                runCatching { voiceRecorder?.release() }
                voiceRecorder = null
                voiceRecording = false
                voiceError = e.message ?: "Voice recording शुरू नहीं हुई।"
            }
        } else voiceError = "Voice note के लिए Microphone permission आवश्यक है।"
    }

    fun startVoiceNote() {
        selectedAttachmentUri = null
        selectedAttachmentName = ""
        attachmentUrl = ""
        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED) {
            try {
                val output = File(context.cacheDir, "inbox-voice-${java.util.UUID.randomUUID()}.m4a")
                val recorder = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
                recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
                recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                recorder.setAudioEncodingBitRate(96000)
                recorder.setAudioSamplingRate(44100)
                recorder.setOutputFile(output.absolutePath)
                recorder.prepare()
                recorder.start()
                voiceRecorder = recorder
                voiceFile = output
                voiceRecording = true
                voiceError = ""
            } catch (e: Exception) {
                runCatching { voiceRecorder?.release() }
                voiceRecorder = null
                voiceRecording = false
                voiceError = e.message ?: "Voice recording शुरू नहीं हुई।"
            }
        } else microphonePermission.launch(android.Manifest.permission.RECORD_AUDIO)
    }

    fun stopVoiceNote() {
        val recorder = voiceRecorder ?: return
        try {
            recorder.stop()
            voiceError = ""
        } catch (e: Exception) {
            voiceFile?.delete()
            voiceFile = null
            voiceError = "रिकॉर्डिंग बहुत छोटी थी। दोबारा रिकॉर्ड करें।"
        } finally {
            runCatching { recorder.reset() }
            runCatching { recorder.release() }
            voiceRecorder = null
            voiceRecording = false
        }
    }

    fun playVoiceNote() {
        val file = voiceFile ?: return
        try {
            voicePlayer?.release()
            voicePlayer = android.media.MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setOnCompletionListener { voicePlaying = false }
                prepare()
                start()
            }
            voicePlaying = true
            voiceError = ""
        } catch (e: Exception) {
            voicePlaying = false
            voiceError = e.message ?: "Voice note preview नहीं चल सकी।"
        }
    }

    fun playRemoteAudio(url: String) {
        try {
            remoteAudioPlayer?.release()
            remoteAudioPlayer = null
            remoteAudioPlayingUrl = url
            remoteAudioPlayer = android.media.MediaPlayer().apply {
                setDataSource(url)
                setOnPreparedListener { player ->
                    player.start()
                    remoteAudioPlayingUrl = url
                }
                setOnCompletionListener {
                    remoteAudioPlayingUrl = ""
                    remoteAudioPlayer?.release()
                    remoteAudioPlayer = null
                }
                setOnErrorListener { _, _, _ ->
                    remoteAudioPlayingUrl = ""
                    remoteAudioPlayer?.release()
                    remoteAudioPlayer = null
                    true
                }
                prepareAsync()
            }
            voiceError = ""
        } catch (e: Exception) {
            remoteAudioPlayingUrl = ""
            voiceError = e.message ?: "Voice message नहीं चल सका।"
        }
    }

    DisposableEffect(session.userId) {
        onDispose {
            runCatching { voiceRecorder?.stop() }
            runCatching { voiceRecorder?.release() }
            runCatching { voicePlayer?.release() }
            runCatching { remoteAudioPlayer?.release() }
            voiceFile?.delete()
        }
    }

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
    LaunchedEffect(thread, session.userId) {
        val paths = thread.map { it.optString("media_url") }
            .filter { it.isNotBlank() && !it.startsWith("https://") }
            .distinct()
        val resolved = mutableMapOf<String, String>()
        for (path in paths) {
            try {
                resolved[path] = NativeSupabase.createSignedObjectUrl("message-media", path, session)
            } catch (_: Exception) {
                // Keep unavailable private attachments hidden; refresh can retry.
            }
        }
        signedMediaUrls = resolved
    }

    val unreadByPeer = remember(messages, session.userId) {
        messages.filter {
            it.optString("recipient_id") == session.userId && it.isNull("read_at")
        }.groupingBy { it.optString("sender_id") }.eachCount()
    }
    val totalUnread = remember(unreadByPeer) { unreadByPeer.values.sum() }

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

    LaunchedEffect(peers.joinToString("|"), initialPeer, session.userId) {
        try {
            val ids = (peers + listOfNotNull(initialPeer)).distinct()
            if (ids.isEmpty()) {
                peerProfiles = emptyMap()
            } else {
                val idsFilter = Uri.encode("(${ids.joinToString(",")})")
                val rows = NativeSupabase.get(
                    "/rest/v1/creator_public_profiles?select=user_id,username,full_name,avatar_url&user_id=in.$idsFilter",
                    session
                )
                peerProfiles = (0 until rows.length()).mapNotNull { index ->
                    rows.optJSONObject(index)
                }.associateBy { profileRow -> profileRow.optString("user_id") }
            }
        } catch (e: Exception) {
            error = e.message ?: "Creator details लोड नहीं हुए।"
        }
    }

    LaunchedEffect(session.userId, refresh) {
        followRequestsLoading = true
        try {
            val requestRows = NativeSupabase.get(
                "/rest/v1/follow_requests?select=id,requester_id,created_at,status&target_user_id=eq.${Uri.encode(session.userId)}&status=eq.pending&order=created_at.desc&limit=100",
                session
            )
            val loadedRequests = (0 until requestRows.length()).mapNotNull { index ->
                requestRows.optJSONObject(index)
            }
            followRequests = loadedRequests
            val requesterIds = loadedRequests.map { it.optString("requester_id") }
                .filter { it.isNotBlank() }.distinct()
            if (requesterIds.isEmpty()) {
                followRequestProfiles = emptyMap()
            } else {
                val idsFilter = Uri.encode("(${requesterIds.joinToString(",")})")
                val profileRows = NativeSupabase.get(
                    "/rest/v1/creator_public_profiles?select=user_id,username,full_name,avatar_url,bio&user_id=in.${idsFilter}",
                    session
                )
                followRequestProfiles = (0 until profileRows.length()).mapNotNull { index ->
                    profileRows.optJSONObject(index)
                }.associateBy { it.optString("user_id") }
            }
        } catch (e: Exception) {
            error = e.message ?: "Follow Requests लोड नहीं हुईं।"
        } finally {
            followRequestsLoading = false
        }
    }

    LaunchedEffect(session.userId, refresh, relationTab) {
        relationsLoading = true
        error = ""
        try {
            val column = if (relationTab == "followers") "follower_id" else "following_id"
            val filterColumn = if (relationTab == "followers") "following_id" else "follower_id"
            val rows = NativeSupabase.get(
                "/rest/v1/followers?select=follower_id,following_id,created_at&$filterColumn=eq.${Uri.encode(session.userId)}&order=created_at.desc&limit=200",
                session
            )
            relationRows = (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }
            val ids = relationRows.mapNotNull { row ->
                row.optString(column).takeIf { it.isNotBlank() }
            }.distinct()
            relationProfiles = if (ids.isEmpty()) emptyMap() else {
                val encodedIds = Uri.encode("(${ids.joinToString(",")})")
                val profiles = NativeSupabase.get(
                    "/rest/v1/creator_public_profiles?select=user_id,username,full_name,avatar_url,bio&user_id=in.$encodedIds",
                    session
                )
                (0 until profiles.length()).mapNotNull { profiles.optJSONObject(it) }
                    .associateBy { it.optString("user_id") }
            }
        } catch (e: Exception) {
            error = e.message ?: "Followers/Following सूची लोड नहीं हुई।"
            relationRows = emptyList()
            relationProfiles = emptyMap()
        } finally {
            relationsLoading = false
        }
    }

    var realtimeStatus by remember(session.userId) { mutableStateOf("Realtime connecting…") }
    var followRealtimeStatus by remember(session.userId) { mutableStateOf("Follow sync connecting…") }

    DisposableEffect(session.userId, session.accessToken) {
        val followRealtime = NativeFollowRealtime.observe(
            session = session,
            creatorId = session.userId,
            onChange = {
                scope.launch { refresh++ }
            },
            onStatus = { status ->
                scope.launch { followRealtimeStatus = status }
            }
        )
        followRealtime.start()
        onDispose { followRealtime.close() }
    }

    DisposableEffect(session.userId, session.accessToken) {
        val realtime = NativeRealtimeMessages(
            accessToken = session.accessToken,
            userId = session.userId,
            onChange = {
                scope.launch { refresh++ }
            },
            onStatus = { status ->
                scope.launch { realtimeStatus = status }
            }
        )
        realtime.connect()
        onDispose { realtime.close() }
    }

    LaunchedEffect(peer, messages, session.userId) {
        if (peer.isNotBlank()) {
            val unreadIds = messages.filter {
                it.optString("sender_id") == peer &&
                    it.optString("recipient_id") == session.userId && it.isNull("read_at")
            }.map { it.optString("id") }.filter { it.isNotBlank() }
            if (unreadIds.isNotEmpty()) {
                val readAt = java.time.Instant.now().toString()
                try {
                    val ids = Uri.encode("(${unreadIds.joinToString(",")})")
                    NativeSupabase.patch(
                        "/rest/v1/messages?id=in.$ids&recipient_id=eq.${Uri.encode(session.userId)}",
                        JSONObject().put("read_at", readAt),
                        session
                    )
                    messages = messages.map { item ->
                        if (item.optString("id") in unreadIds) JSONObject(item.toString()).put("read_at", readAt)
                        else item
                    }
                } catch (e: Exception) {
                    error = e.message ?: "Read receipt अपडेट नहीं हुआ।"
                }
            }
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
        } catch (e: Exception) {
            error = e.message ?: "Inbox लोड नहीं हुई।"
        } finally { busy = false }
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Inbox", style = MaterialTheme.typography.headlineSmall)
                    if (totalUnread > 0) {
                        Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(20.dp)) {
                            Text("$totalUnread unread", modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                Text(realtimeStatus, style = MaterialTheme.typography.labelSmall,
                    color = if (realtimeStatus.contains("connected", true)) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Followers: $followRealtimeStatus", style = MaterialTheme.typography.labelSmall,
                    color = if (followRealtimeStatus == "Connected") MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = { refresh++ }, enabled = !busy) { Text("Refresh") }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text("Follow Requests", style = MaterialTheme.typography.titleMedium)
                    Text(followRequests.size.toString(), color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelLarge)
                }
                if (followRequestsLoading) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                } else if (followRequests.isEmpty()) {
                    Text("अभी कोई Pending Follow Request नहीं है।",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    LazyColumn(Modifier.heightIn(max = 220.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(followRequests, key = { it.optString("id") }) { request ->
                            val requestId = request.optString("id")
                            val requesterId = request.optString("requester_id")
                            val profile = followRequestProfiles[requesterId]
                            val actionBusy = requestId in requestActionIds
                            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                AsyncImage(
                                    model = profile?.optString("avatar_url")?.takeIf { it.startsWith("https://") },
                                    contentDescription = "Follow requester avatar",
                                    modifier = Modifier.size(40.dp).clip(CircleShape)
                                )
                                Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                                    Text(profile?.optString("full_name").orEmpty().ifBlank {
                                        profile?.optString("username").orEmpty().ifBlank { "New Star Creator" }
                                    }, style = MaterialTheme.typography.titleSmall)
                                    Text("आपको Follow करना चाहते हैं",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Button(enabled = !actionBusy && requestId.isNotBlank() && requesterId.isNotBlank(),
                                        onClick = {
                                            scope.launch {
                                                requestActionIds = requestActionIds + requestId
                                                error = ""
                                                try {
                                                    NativeSupabase.patch(
                                                        "/rest/v1/follow_requests?id=eq.${Uri.encode(requestId)}&target_user_id=eq.${Uri.encode(session.userId)}&status=eq.pending",
                                                        JSONObject().put("status", "accepted"),
                                                        session
                                                    )
                                                    try {
                                                        NativeSupabase.post(
                                                            "/rest/v1/followers",
                                                            JSONObject().put("follower_id", requesterId)
                                                                .put("following_id", session.userId),
                                                            session
                                                        )
                                                    } catch (insertError: Exception) {
                                                        val message = insertError.message.orEmpty()
                                                        if (!message.contains("duplicate", true) &&
                                                            !message.contains("unique", true)) throw insertError
                                                    }
                                                    followRequests = followRequests.filterNot {
                                                        it.optString("id") == requestId
                                                    }
                                                } catch (e: Exception) {
                                                    error = e.message ?: "Follow Request स्वीकार नहीं हुई।"
                                                } finally {
                                                    requestActionIds = requestActionIds - requestId
                                                }
                                            }
                                        }) { Text(if (actionBusy) "…" else "Accept") }
                                    OutlinedButton(enabled = !actionBusy && requestId.isNotBlank(),
                                        onClick = {
                                            scope.launch {
                                                requestActionIds = requestActionIds + requestId
                                                error = ""
                                                try {
                                                    NativeSupabase.patch(
                                                        "/rest/v1/follow_requests?id=eq.${Uri.encode(requestId)}&target_user_id=eq.${Uri.encode(session.userId)}&status=eq.pending",
                                                        JSONObject().put("status", "rejected"),
                                                        session
                                                    )
                                                    followRequests = followRequests.filterNot {
                                                        it.optString("id") == requestId
                                                    }
                                                } catch (e: Exception) {
                                                    error = e.message ?: "Follow Request अस्वीकार नहीं हुई।"
                                                } finally {
                                                    requestActionIds = requestActionIds - requestId
                                                }
                                            }
                                        }) { Text("Decline") }
                                }
                            }
                        }
                    }
                }
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Community", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = relationTab == "followers",
                        onClick = { relationTab = "followers" },
                        label = { Text("Followers") }
                    )
                    FilterChip(
                        selected = relationTab == "following",
                        onClick = { relationTab = "following" },
                        label = { Text("Following") }
                    )
                    Text("${relationRows.size}", style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(top = 10.dp))
                }
                if (relationsLoading) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                } else if (relationRows.isEmpty()) {
                    Text(
                        if (relationTab == "followers") "अभी कोई follower नहीं है।" else "आप अभी किसी को follow नहीं करते।",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    LazyColumn(Modifier.heightIn(max = 190.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(relationRows, key = { row ->
                            if (relationTab == "followers") row.optString("follower_id") else row.optString("following_id")
                        }) { relation ->
                            val creatorId = if (relationTab == "followers") {
                                relation.optString("follower_id")
                            } else relation.optString("following_id")
                            val profile = relationProfiles[creatorId]
                            val actionBusy = creatorId in relationActionIds
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                            ) {
                                AsyncImage(
                                    model = profile?.optString("avatar_url")?.takeIf { it.startsWith("https://") },
                                    contentDescription = "Creator avatar",
                                    modifier = Modifier.size(38.dp).clip(CircleShape)
                                )
                                Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                                    Text(
                                        profile?.optString("full_name").orEmpty().ifBlank {
                                            profile?.optString("username").orEmpty().ifBlank { "New Star Creator" }
                                        },
                                        style = MaterialTheme.typography.titleSmall
                                    )
                                    profile?.optString("username")?.takeIf { it.isNotBlank() }?.let {
                                        Text("@$it", style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                if (relationTab == "following") {
                                    OutlinedButton(enabled = !actionBusy, onClick = {
                                        scope.launch {
                                            relationActionIds = relationActionIds + creatorId
                                            try {
                                                NativeSupabase.delete(
                                                    "/rest/v1/followers?follower_id=eq.${Uri.encode(session.userId)}&following_id=eq.${Uri.encode(creatorId)}",
                                                    session
                                                )
                                                relationRows = relationRows.filterNot {
                                                    it.optString("following_id") == creatorId
                                                }
                                                refresh++
                                            } catch (e: Exception) {
                                                error = e.message ?: "Unfollow नहीं हुआ।"
                                            } finally {
                                                relationActionIds = relationActionIds - creatorId
                                            }
                                        }
                                    }) { Text(if (actionBusy) "…" else "Following") }
                                }
                            }
                        }
                    }
                }
            }
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
                            val selectedId = creator.optString("user_id")
                            peerProfiles = peerProfiles + (selectedId to JSONObject(creator.toString()))
                            peer = selectedId
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
                                val unread = unreadByPeer[id] ?: 0
                                if (unread > 0) {
                                    Text("$unread unread", color = MaterialTheme.colorScheme.primary,
                                        style = MaterialTheme.typography.labelSmall)
                                }
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
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            OutlinedButton(
                enabled = !busy,
                onClick = { attachmentPicker.launch(arrayOf("image/*", "video/*", "audio/*", "application/pdf")) }
            ) { Text("Attach file") }
            if (selectedAttachmentName.isNotBlank()) {
                Text(selectedAttachmentName, modifier = Modifier.weight(1f),
                    maxLines = 1, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = {
                    selectedAttachmentUri = null
                    selectedAttachmentName = ""
                }, enabled = !busy) { Text("Remove") }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Button(
                enabled = !busy && !voiceRecording,
                onClick = { startVoiceNote() }
            ) { Text("🎙 Record voice") }
            if (voiceRecording) {
                Button(onClick = { stopVoiceNote() }, colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                )) { Text("Stop recording") }
                Text("Recording…", color = MaterialTheme.colorScheme.error)
            } else if (voiceFile != null) {
                OutlinedButton(onClick = { if (voicePlaying) {
                    voicePlayer?.pause()
                    voicePlaying = false
                } else playVoiceNote() }, enabled = !busy) {
                    Text(if (voicePlaying) "Pause preview" else "Play preview")
                }
                TextButton(onClick = {
                    voicePlayer?.release()
                    voicePlayer = null
                    voicePlaying = false
                    voiceFile?.delete()
                    voiceFile = null
                }, enabled = !busy) { Text("Remove voice") }
            }
        }
        if (voiceError.isNotBlank()) Text(voiceError, color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(
            value = attachmentUrl,
            onValueChange = { attachmentUrl = it.trim(); if (it.isNotBlank()) {
                selectedAttachmentUri = null
                selectedAttachmentName = ""
            } },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("External HTTPS link (optional)") },
            singleLine = true
        )
        Text("Private attachments Supabase Storage में upload होते हैं और केवल chat के सदस्यों को उपलब्ध रहते हैं। अधिकतम 20 MB।",
            style = MaterialTheme.typography.labelSmall)
        Button(enabled = !busy && peer.isNotBlank() &&
            (body.isNotBlank() || attachmentUrl.isNotBlank() || selectedAttachmentUri != null || voiceFile != null) && !voiceRecording, onClick = {
            busy = true
            val messageText = body.trim()
            val mediaLink = attachmentUrl.trim()
            val mediaUri = selectedAttachmentUri
            val recordedVoice = voiceFile
            val target = peer.trim()
            scope.launch {
                try {
                    require(target != session.userId) { "आप खुद को Message नहीं भेज सकते।" }
                    require(messageText.length <= 5000) { "Message 5000 अक्षरों से छोटा रखें।" }
                    require(mediaLink.isBlank() || (mediaLink.startsWith("https://") &&
                        Uri.parse(mediaLink).host?.isNotBlank() == true)) {
                        "External attachment के लिए valid HTTPS link डालें।"
                    }
                    var storedMedia = mediaLink
                    if (recordedVoice != null) {
                        val bytes = withContext(Dispatchers.IO) { recordedVoice.readBytes() }
                        require(bytes.isNotEmpty() && bytes.size <= 20 * 1024 * 1024) {
                            "Voice note 20 MB से छोटी होनी चाहिए।"
                        }
                        val objectPath = "${session.userId}/${java.util.UUID.randomUUID()}.m4a"
                        storedMedia = NativeSupabase.uploadPrivateObject(
                            "message-media", objectPath, bytes, "audio/mp4", session
                        )
                    } else if (mediaUri != null) {
                        val mime = context.contentResolver.getType(mediaUri) ?: "application/octet-stream"
                        require(mime.startsWith("image/") || mime.startsWith("video/") ||
                            mime.startsWith("audio/") || mime == "application/pdf") {
                            "यह file type भेजने की अनुमति नहीं है।"
                        }
                        val bytes = withContext(Dispatchers.IO) {
                            context.contentResolver.openInputStream(mediaUri)?.use { it.readBytes() }
                        } ?: error("चुनी गई file पढ़ी नहीं जा सकी।")
                        require(bytes.isNotEmpty() && bytes.size <= 20 * 1024 * 1024) {
                            "Attachment 20 MB से छोटी होनी चाहिए।"
                        }
                        val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
                            ?.lowercase()?.takeIf { it.matches(Regex("[a-z0-9]{1,8}")) } ?: "bin"
                        val objectPath = "${session.userId}/${java.util.UUID.randomUUID()}.$extension"
                        storedMedia = NativeSupabase.uploadPrivateObject(
                            "message-media", objectPath, bytes, mime, session
                        )
                    }
                    val conversation = stableConversationId(session.userId, target)
                    val payload = JSONObject()
                        .put("conversation_id", conversation)
                        .put("sender_id", session.userId)
                        .put("recipient_id", target)
                        .put("body", messageText)
                    if (storedMedia.isNotBlank()) payload.put("media_url", storedMedia)
                    NativeSupabase.post("/rest/v1/messages", payload, session)
                    body = ""
                    attachmentUrl = ""
                    selectedAttachmentUri = null
                    selectedAttachmentName = ""
                    voicePlayer?.release()
                    voicePlayer = null
                    voicePlaying = false
                    voiceFile?.delete()
                    voiceFile = null
                    refresh++
                } catch (e: Exception) { error = e.message ?: "Message नहीं भेजा गया।" }
                finally { busy = false }
            }
        }) { Text(if (busy) "Uploading / sending…" else "Send Message") }
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
                            val messageBody = item.optString("body")
                            if (messageBody.isNotBlank()) Text(messageBody)
                            val mediaUrl = item.optString("media_url")
                            val resolvedMediaUrl = if (mediaUrl.startsWith("https://")) mediaUrl
                                else signedMediaUrls[mediaUrl].orEmpty()
                            if (resolvedMediaUrl.isNotBlank()) {
                                val lowerUrl = mediaUrl.lowercase().substringBefore("?")
                                if (listOf(".jpg", ".jpeg", ".png", ".webp", ".gif").any { lowerUrl.endsWith(it) }) {
                                    AsyncImage(
                                        model = resolvedMediaUrl,
                                        contentDescription = "Private message image attachment",
                                        modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp).clip(RoundedCornerShape(10.dp)),
                                        contentScale = ContentScale.Fit
                                    )
                                } else if (listOf(".m4a", ".mp3", ".aac", ".wav", ".ogg", ".mp4").any { lowerUrl.endsWith(it) }) {
                                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                        Button(onClick = {
                                            if (remoteAudioPlayingUrl == resolvedMediaUrl) {
                                                runCatching { remoteAudioPlayer?.pause() }
                                                remoteAudioPlayer?.release()
                                                remoteAudioPlayer = null
                                                remoteAudioPlayingUrl = ""
                                            } else playRemoteAudio(resolvedMediaUrl)
                                        }) {
                                            Text(if (remoteAudioPlayingUrl == resolvedMediaUrl) "Stop voice" else "Play voice note")
                                        }
                                        Text("Audio message", style = MaterialTheme.typography.labelSmall)
                                    }
                                } else {
                                    TextButton(onClick = {
                                        runCatching {
                                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(resolvedMediaUrl)))
                                        }.onFailure { error = "Attachment खोलने के लिए compatible app नहीं मिला।" }
                                    }) { Text("Open attachment") }
                                }
                            } else if (mediaUrl.isNotBlank()) {
                                Text("Private attachment लोड हो रहा है या उपलब्ध नहीं है।",
                                    style = MaterialTheme.typography.labelSmall)
                            }
                            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(item.optString("created_at"), style = MaterialTheme.typography.labelSmall)
                                if (own) {
                                    Text(
                                        if (!item.isNull("read_at")) "Seen" else "Sent",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (!item.isNull("read_at")) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}



@Composable
private fun NativeCameraStudio(onVideoReady: (Uri) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mainExecutor = remember { ContextCompat.getMainExecutor(context) }
    var cameraGranted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED) }
    var audioGranted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) }
    var permissionMessage by remember { mutableStateOf("") }
    var lensFacing by remember { mutableIntStateOf(CameraSelector.LENS_FACING_BACK) }
    var recording by remember { mutableStateOf(false) }
    var stopRequested by remember { mutableStateOf(false) }
    var recordingSeconds by remember { mutableIntStateOf(0) }
    var capture by remember { mutableStateOf<VideoCapture<Recorder>?>(null) }
    var activeRecording by remember { mutableStateOf<androidx.camera.video.Recording?>(null) }
    var cameraError by remember { mutableStateOf("") }
    var recordedUri by remember { mutableStateOf<Uri?>(null) }
    val recordedPlayer = remember(recordedUri) {
        recordedUri?.let { uri ->
            ExoPlayer.Builder(context).build().apply {
                setMediaItem(MediaItem.fromUri(uri))
                prepare()
                playWhenReady = false
            }
        }
    }
    DisposableEffect(recordedPlayer) {
        onDispose { recordedPlayer?.release() }
    }
    val previewView = remember { PreviewView(context).apply {
        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        scaleType = PreviewView.ScaleType.FILL_CENTER
    } }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        cameraGranted = result[android.Manifest.permission.CAMERA] == true ||
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED
        audioGranted = result[android.Manifest.permission.RECORD_AUDIO] == true ||
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
        permissionMessage = if (cameraGranted && !audioGranted) "Microphone अनुमति नहीं मिली; वीडियो बिना आवाज़ रिकॉर्ड होगा।" else ""
    }
    LaunchedEffect(cameraGranted, lensFacing, recordedUri) {
        if (!cameraGranted) return@LaunchedEffect
        try {
            val future = ProcessCameraProvider.getInstance(context)
            if (recordedUri != null) {
                runCatching { future.get().unbindAll() }
                capture = null
                return@LaunchedEffect
            }
            val provider = kotlinx.coroutines.suspendCancellableCoroutine<ProcessCameraProvider> { continuation ->
                future.addListener({
                    try { continuation.resume(future.get()) }
                    catch (error: Throwable) { continuation.resumeWithException(error) }
                }, mainExecutor)
            }
            val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
            val recorder = Recorder.Builder().setQualitySelector(QualitySelector.from(Quality.HD)).build()
            val videoCapture = VideoCapture.withOutput(recorder)
            val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
            provider.unbindAll()
            provider.bindToLifecycle(lifecycleOwner, selector, preview, videoCapture)
            capture = videoCapture
            cameraError = ""
        } catch (error: Exception) { cameraError = error.message ?: "Camera शुरू नहीं हो सका।" }
    }
    DisposableEffect(Unit) {
        onDispose {
            runCatching { activeRecording?.stop() }
            runCatching { ProcessCameraProvider.getInstance(context).get().unbindAll() }
        }
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Native Camera Studio", style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = onClose, enabled = !recording) { Text("Close") }
        }
        if (!cameraGranted) {
            Text("Camera permission देकर native recording शुरू करें।")
            Button(onClick = { permissionLauncher.launch(arrayOf(android.Manifest.permission.CAMERA, android.Manifest.permission.RECORD_AUDIO)) }) { Text("Allow Camera & Microphone") }
        } else if (recordedUri != null) {
            Text("Recording preview · पसंद आए तो Use Video दबाएँ")
            AndroidView(
                factory = { viewContext -> PlayerView(viewContext).apply {
                    player = recordedPlayer
                    useController = true
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                } },
                update = { it.player = recordedPlayer },
                modifier = Modifier.fillMaxWidth().height(320.dp)
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        recordedPlayer?.pause()
                        runCatching { context.contentResolver.delete(recordedUri!!, null, null) }
                        recordedUri = null
                        cameraError = ""
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("Retake") }
                Button(
                    onClick = { recordedUri?.let(onVideoReady) },
                    modifier = Modifier.weight(1f)
                ) { Text("Use Video") }
            }
        } else {
            AndroidView(factory = { previewView }, modifier = Modifier.fillMaxWidth().height(320.dp))
            Text(if (recording) "Recording चालू · ${recordingSeconds}s / 600s" else "HD recording · अधिकतम 10 मिनट")
            if (!audioGranted) {
                TextButton(onClick = { permissionLauncher.launch(arrayOf(android.Manifest.permission.RECORD_AUDIO)) }, enabled = !recording) {
                    Text("Enable Microphone")
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK },
                    enabled = !recording && capture != null,
                    modifier = Modifier.weight(1f)
                ) { Text("Switch Camera") }
                Button(
                    onClick = {
                        val currentCapture = capture ?: return@Button
                        if (recording) {
                            if (!stopRequested) { stopRequested = true; activeRecording?.stop() }
                        } else {
                            val values = ContentValues().apply {
                                put(MediaStore.Video.Media.DISPLAY_NAME, "newstar-camera-${System.currentTimeMillis()}.mp4")
                                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                    put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/New Star")
                                }
                            }
                            val options = MediaStoreOutputOptions.Builder(
                                context.contentResolver,
                                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                            ).setContentValues(values).build()
                            var pending: PendingRecording = currentCapture.output.prepareRecording(context, options)
                            if (audioGranted) pending = pending.withAudioEnabled()
                            activeRecording = pending.start(mainExecutor) { event ->
                                when (event) {
                                    is VideoRecordEvent.Start -> { recording = true; stopRequested = false; recordingSeconds = 0 }
                                    is VideoRecordEvent.Status -> {
                                        recordingSeconds = (event.recordingStats.recordedDurationNanos / 1_000_000_000L).toInt().coerceAtMost(600)
                                        if (recordingSeconds >= 600 && !stopRequested) { stopRequested = true; activeRecording?.stop() }
                                    }
                                    is VideoRecordEvent.Finalize -> {
                                        recording = false
                                        stopRequested = false
                                        recordingSeconds = 0
                                        activeRecording = null
                                        val savedUri = event.outputResults.outputUri
                                        if (event.hasError() || savedUri == Uri.EMPTY) {
                                            if (savedUri != Uri.EMPTY) runCatching { context.contentResolver.delete(savedUri, null, null) }
                                            cameraError = event.cause?.message ?: "Recording save नहीं हुई।"
                                        } else {
                                            recordedUri = savedUri
                                            cameraError = ""
                                        }
                                    }
                                }
                            }
                        }
                    },
                    enabled = capture != null && !stopRequested,
                    modifier = Modifier.weight(1f)
                ) { Text(if (recording) "Stop Recording" else "● Record") }
            }
        }
        if (permissionMessage.isNotBlank()) Text(permissionMessage, color = MaterialTheme.colorScheme.error)
        if (cameraError.isNotBlank()) Text(cameraError, color = MaterialTheme.colorScheme.error)
    }
}

private fun deleteNativeEditorTemp(context: Context, uri: Uri?) {
    val path = uri?.path ?: return
    runCatching {
        val file = java.io.File(path).canonicalFile
        val cache = context.cacheDir.canonicalFile
        if (file.parentFile == cache && file.name.startsWith("newstar-edit-") && file.isFile) {
            file.delete()
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
    var visibility by remember { mutableStateOf("public") }
    var audienceType by remember { mutableStateOf("all") }
    var mediaKind by remember { mutableStateOf("video") }
    var affiliateLink by remember { mutableStateOf("") }
    var locationQuery by remember { mutableStateOf("") }
    var locationResults by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var locationSearchMessage by remember { mutableStateOf("लोकेशन जोड़ना वैकल्पिक है।") }
    var selectedLocation by remember { mutableStateOf<JSONObject?>(null) }
    var locationVisibility by remember { mutableStateOf("public") }
    var progress by remember { mutableIntStateOf(0) }
    var editingProgress by remember { mutableIntStateOf(0) }
    var status by remember { mutableStateOf("3 घंटे तक का वीडियो चुनें। अधिकतम आकार 5 GiB है।") }
    var uploading by remember { mutableStateOf(false) }
    var hasRecoverableUpload by remember { mutableStateOf(false) }
    val uploadWorkName = remember(session.userId) { NativeVideoUploadWork.uniqueName(session.userId) }
    val uploadWorkInfos by WorkManager.getInstance(context)
        .getWorkInfosForUniqueWorkLiveData(uploadWorkName)
        .observeAsState(initial = emptyList())
    val activeUploadWork = uploadWorkInfos.firstOrNull {
        it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.BLOCKED
    }
    LaunchedEffect(uploadWorkInfos) {
        // Prefer the active request when WorkManager still returns an older
        // completed request under the same unique-work name.
        val info = uploadWorkInfos.firstOrNull {
            it.state == WorkInfo.State.ENQUEUED ||
                it.state == WorkInfo.State.RUNNING ||
                it.state == WorkInfo.State.BLOCKED
        } ?: uploadWorkInfos.firstOrNull()
        if (info != null) {
            val savedDraft = NativeUploadDraftStore.load(context, session.userId)
            if (selected == null && savedDraft != null) {
                selected = Uri.parse(savedDraft.optString("uri"))
                title = savedDraft.optString("title")
                description = savedDraft.optString("description")
                category = savedDraft.optString("category", "General")
            }
            when (info.state) {
                WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING, WorkInfo.State.BLOCKED -> {
                    uploading = true
                    hasRecoverableUpload = true
                    progress = info.progress.getInt("progress", progress).coerceIn(0, 100)
                    status = info.progress.getString("message") ?: "Background upload queue में है।"
                }
                WorkInfo.State.SUCCEEDED -> {
                    uploading = false
                    progress = 100
                    hasRecoverableUpload = false
                    status = info.outputData.getString("message") ?: "वीडियो upload पूरा हुआ।"
                    NativeUploadDraftStore.clear(context, session.userId)
                    deleteNativeEditorTemp(context, selected)
                    selected = null
                }
                WorkInfo.State.FAILED -> {
                    uploading = false
                    val error = info.outputData.getString("error") ?: "Background upload असफल हुआ।"
                    status = error
                    val draft = NativeUploadDraftStore.load(context, session.userId)
                    hasRecoverableUpload = draft != null
                    if (draft != null) {
                        val total = draft.optLong("size")
                        progress = if (total > 0L) ((draft.optLong("offset") * 100L) / total).toInt().coerceIn(0, 100) else 0
                    }
                }
                WorkInfo.State.CANCELLED -> {
                    uploading = false
                    val draft = NativeUploadDraftStore.load(context, session.userId)
                    hasRecoverableUpload = draft != null
                    status = if (draft != null) "Upload रोक दिया गया। Resume Upload दबाकर वहीं से जारी करें।" else "Upload रद्द किया गया।"
                }
            }
        }
    }
    var cameraStudioOpen by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var trimStartSeconds by remember { mutableStateOf("0") }
    var trimEndSeconds by remember { mutableStateOf("") }
    var sourceDurationSeconds by remember { mutableIntStateOf(0) }
    var sourceWidth by remember { mutableIntStateOf(0) }
    var sourceHeight by remember { mutableIntStateOf(0) }
    var rotationDegrees by remember { mutableIntStateOf(0) }
    var muteOriginalAudio by remember { mutableStateOf(false) }
    var originalAudioVolume by remember { mutableFloatStateOf(1f) }
    var selectedFilter by remember { mutableStateOf("none") }
    var centerCrop by remember { mutableStateOf(false) }
    var exportSpeed by remember { mutableFloatStateOf(1f) }
    var overlayText by remember { mutableStateOf("") }
    var overlayTextSize by remember { mutableIntStateOf(24) }
    var overlayTextColor by remember { mutableStateOf("#FFFFFF") }
    var overlayTextPosition by remember { mutableStateOf("center") }
    var overlayTextX by remember { mutableFloatStateOf(0f) }
    var overlayTextY by remember { mutableFloatStateOf(0f) }
    var previewTextSize by remember { mutableStateOf(IntSize.Zero) }
    var splitAtSeconds by remember { mutableStateOf("") }
    var splitParts by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var musicUri by remember { mutableStateOf<Uri?>(null) }
    var musicVolume by remember { mutableFloatStateOf(0.4f) }
    var voiceVolume by remember { mutableFloatStateOf(1f) }
    var voiceoverFile by remember { mutableStateOf<File?>(null) }
    var voiceRecordingFile by remember { mutableStateOf<File?>(null) }
    var voiceRecorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var voiceRecording by remember { mutableStateOf(false) }
    var audioStatus by remember { mutableStateOf("Music चुनें या Voice recording शुरू करें।") }
    fun acceptSelectedVideo(uri: Uri?, source: String) {
        if (uri == null) {
            status = if (source == "camera") "Camera से वीडियो वापस नहीं मिला। दोबारा रिकॉर्ड करें या Gallery चुनें।" else status
            return
        }
        val pendingDraft = NativeUploadDraftStore.load(context, session.userId)
        if (pendingDraft != null && pendingDraft.optString("uri") != uri.toString()) {
            NativeUploadDraftStore.clear(context, session.userId)
            hasRecoverableUpload = false
        }
        deleteNativeEditorTemp(context, selected)
        splitParts.forEach { deleteNativeEditorTemp(context, it) }
        selected = uri
        splitParts = emptyList()
        try {
            if (source == "gallery") {
                try { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
            }
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                sourceDurationSeconds = ((retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) / 1000L).toInt()
                sourceWidth = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                sourceHeight = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                require(sourceDurationSeconds > 0 && sourceWidth > 0 && sourceHeight > 0) { "वीडियो की अवधि या resolution पढ़ नहीं सके।" }
                if (source == "camera") require(sourceDurationSeconds <= 600) { "Camera recording 10 मिनट से अधिक है। कृपया 10 मिनट या उससे छोटा वीडियो रिकॉर्ड करें।" }
            } finally {
                retriever.release()
            }
            trimStartSeconds = "0"
            trimEndSeconds = sourceDurationSeconds.toString()
            splitAtSeconds = ""
            rotationDegrees = 0
            muteOriginalAudio = false
            originalAudioVolume = 1f
            selectedFilter = "none"
            centerCrop = false
            exportSpeed = 1f
            overlayText = ""
            overlayTextSize = 24
            overlayTextColor = "#FFFFFF"
            overlayTextPosition = "center"
            overlayTextX = 0f
            overlayTextY = 0f
            editing = true
            status = if (source == "camera") "Camera recording मिल गई। Native editor में बदलाव करके Publish करें।" else "वीडियो चुना गया। Trim, rotate या mute सेट करके Apply edits दबाएँ।"
        } catch (e: Exception) {
            selected = null
            status = e.message ?: "वीडियो metadata पढ़ नहीं सके।"
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        acceptSelectedVideo(uri, "gallery")
    }
    LaunchedEffect(session.userId) {
        val draft = NativeUploadDraftStore.load(context, session.userId)
        if (draft != null && draft.optBoolean("upload_complete")) {
            NativeUploadDraftStore.clear(context, session.userId)
            status = "पिछला वीडियो सफलतापूर्वक upload हो चुका है।"
        } else if (draft != null) {
            try {
                val draftUri = Uri.parse(draft.optString("uri"))
                context.contentResolver.openFileDescriptor(draftUri, "r")?.use { }
                    ?: throw IllegalStateException("पिछले upload की video file अब उपलब्ध नहीं है।")
                selected = draftUri
                title = draft.optString("title")
                description = draft.optString("description")
                category = draft.optString("category", "General")
                visibility = draft.optString("visibility", "public")
                audienceType = draft.optString("audience_type", "all")
                mediaKind = draft.optString("media_kind", "video")
                affiliateLink = draft.optString("affiliate_link")
                locationVisibility = draft.optString("location_visibility", "public")
                val savedLocation = draft.optJSONObject("location")
                selectedLocation = savedLocation
                locationQuery = savedLocation?.optString("name").orEmpty()
                sourceDurationSeconds = draft.optInt("duration")
                sourceWidth = draft.optInt("width")
                sourceHeight = draft.optInt("height")
                val total = draft.optLong("size")
                progress = if (total > 0L) ((draft.optLong("offset") * 100L) / total).toInt().coerceIn(0, 100) else 0
                hasRecoverableUpload = true
                status = if (draft.optString("video_id").isNotBlank()) {
                    "Upload पूरा हो चुका है। Publish record की पुष्टि जारी रख सकते हैं।"
                } else {
                    "पिछला अधूरा upload मिला (${progress}%)। Publish Video दबाकर वहीं से जारी करें।"
                }
            } catch (e: Exception) {
                NativeUploadDraftStore.clear(context, session.userId)
                hasRecoverableUpload = false
                status = e.message ?: "पिछला upload restore नहीं हो सका। वीडियो फिर से चुनें।"
            }
        }
    }
    val musicPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) { }
            musicUri = uri
            audioStatus = "Music चुना गया। Play दबाकर Preview सुनें।"
        }
    }
    val microphonePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        audioStatus = if (granted) "Microphone अनुमति मिल गई। Start Voice दबाएँ।" else "Voice recording के लिए Microphone permission जरूरी है।"
    }
    val musicPlayer = remember(musicUri) {
        musicUri?.let { uri ->
            ExoPlayer.Builder(context).build().apply {
                setMediaItem(MediaItem.fromUri(uri))
                prepare()
                playWhenReady = false
                repeatMode = androidx.media3.common.Player.REPEAT_MODE_ONE
            }
        }
    }
    val voicePlayer = remember(voiceoverFile) {
        voiceoverFile?.let { file ->
            ExoPlayer.Builder(context).build().apply {
                setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
                prepare()
                playWhenReady = false
            }
        }
    }
    LaunchedEffect(musicPlayer, musicVolume) { musicPlayer?.volume = musicVolume }
    LaunchedEffect(voicePlayer, voiceVolume) { voicePlayer?.volume = voiceVolume }
    LaunchedEffect(editing) {
        if (!editing) {
            musicPlayer?.pause()
            voicePlayer?.pause()
        }
    }
    DisposableEffect(musicPlayer, voicePlayer) {
        onDispose { musicPlayer?.release(); voicePlayer?.release() }
    }
    val latestVoiceRecorder by rememberUpdatedState(voiceRecorder)
    val latestVoiceRecording by rememberUpdatedState(voiceRecording)
    val latestVoiceRecordingFile by rememberUpdatedState(voiceRecordingFile)
    val latestVoiceoverFile by rememberUpdatedState(voiceoverFile)
    DisposableEffect(Unit) {
        onDispose {
            latestVoiceRecorder?.let { recorder ->
                runCatching { if (latestVoiceRecording) recorder.stop() }
                runCatching { recorder.reset() }
                runCatching { recorder.release() }
            }
            latestVoiceRecordingFile?.delete()
            latestVoiceoverFile?.delete()
        }
    }
    val scope = rememberCoroutineScope()
    LaunchedEffect(locationQuery, session.accessToken) {
        val query = locationQuery.trim()
        if (query.length < 2) {
            locationResults = emptyList()
            locationSearchMessage = "कम से कम 2 अक्षर लिखें।"
        } else if (selectedLocation?.optString("name") != query) {
            delay(350)
            locationSearchMessage = "दुनिया भर में जगह खोजी जा रही है…"
            try {
                val response = NativeSupabase.invokeFunction(
                    "mapbox-geocode",
                    JSONObject().put("query", query),
                    session
                )
                val features = response.optJSONArray("features")
                val found = buildList {
                    if (features != null) {
                        for (index in 0 until minOf(features.length(), 8)) {
                            val feature = features.optJSONObject(index) ?: continue
                            val center = feature.optJSONArray("center") ?: continue
                            if (center.length() != 2) continue
                            val longitude = center.optDouble(0, Double.NaN)
                            val latitude = center.optDouble(1, Double.NaN)
                            val name = feature.optString("name").trim()
                            if (name.isNotBlank() && longitude.isFinite() && latitude.isFinite() &&
                                longitude in -180.0..180.0 && latitude in -90.0..90.0) {
                                add(JSONObject().put("id", feature.optString("id"))
                                    .put("name", name.take(240))
                                    .put("longitude", longitude)
                                    .put("latitude", latitude))
                            }
                        }
                    }
                }
                locationResults = found
                locationSearchMessage = if (found.isEmpty()) "कोई जगह नहीं मिली। नाम बदलकर खोजें।" else "एक सही जगह चुनें।"
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                locationResults = emptyList()
                locationSearchMessage = error.message?.take(240) ?: "लोकेशन सर्च उपलब्ध नहीं है।"
            }
        }
    }
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
        if (cameraStudioOpen) {
            NativeCameraStudio(
                onVideoReady = { uri -> acceptSelectedVideo(uri, "camera"); cameraStudioOpen = false },
                onClose = { cameraStudioOpen = false }
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { picker.launch(arrayOf("video/*")) }, enabled = !uploading && !voiceRecording, modifier = Modifier.weight(1f)) { Text("Gallery से वीडियो चुनें") }
            OutlinedButton(onClick = { cameraStudioOpen = true }, enabled = !uploading && !voiceRecording, modifier = Modifier.weight(1f)) { Text("Native Camera Studio") }
        }
        Text(selected?.lastPathSegment ?: "कोई वीडियो नहीं चुना गया")
        if (selected != null && sourceWidth > 0 && sourceHeight > 0) {
            Text("Video Info · ${sourceWidth} × ${sourceHeight} · ${sourceDurationSeconds}s")
        }
        if (previewPlayer != null) {
            Box(Modifier.fillMaxWidth().height(240.dp).onSizeChanged { previewTextSize = it }) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { viewContext ->
                        PlayerView(viewContext).apply {
                            player = previewPlayer
                            useController = true
                            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        }
                    },
                    update = { it.player = previewPlayer }
                )
                if (editing && overlayText.isNotBlank()) {
                    Surface(
                        modifier = Modifier.align(Alignment.Center)
                            .offset { IntOffset((overlayTextX * previewTextSize.width / 2f).toInt(), (overlayTextY * previewTextSize.height / 2f).toInt()) }
                            .pointerInput(previewTextSize) {
                                detectDragGestures { change, dragAmount ->
                                    change.consume()
                                    if (previewTextSize.width > 0 && previewTextSize.height > 0) {
                                        overlayTextX = (overlayTextX + dragAmount.x / (previewTextSize.width / 2f)).coerceIn(-0.85f, 0.85f)
                                        overlayTextY = (overlayTextY + dragAmount.y / (previewTextSize.height / 2f)).coerceIn(-0.85f, 0.85f)
                                        overlayTextPosition = "custom"
                                    }
                                }
                            },
                        color = androidx.compose.ui.graphics.Color(android.graphics.Color.argb(170, 0, 0, 0)),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            overlayText,
                            color = androidx.compose.ui.graphics.Color(android.graphics.Color.parseColor(overlayTextColor)),
                            fontSize = overlayTextSize.sp,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            maxLines = 2
                        )
                    }
                }
            }
        }
        if (editing && selected != null) {
            Text("Native editor · कुल अवधि $sourceDurationSeconds सेकंड", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(value = splitAtSeconds, onValueChange = { splitAtSeconds = it.filter(Char::isDigit) }, modifier = Modifier.fillMaxWidth(), label = { Text("Split point (seconds)") }, singleLine = true, enabled = !uploading)
            Button(enabled = !uploading && !voiceRecording && selected != null, onClick = {
                val input = selected ?: return@Button
                val start = trimStartSeconds.toLongOrNull()
                val end = trimEndSeconds.toLongOrNull()
                val split = splitAtSeconds.toLongOrNull()
                if (start == null || end == null || split == null || split <= start || split >= end || start < 0 || end > sourceDurationSeconds) {
                    status = "Split point, Trim start और Trim end के बीच होना चाहिए।"
                    return@Button
                }
                uploading = true
                editingProgress = 0
                status = "दोनों वीडियो segments export हो रहे हैं…"
                scope.launch {
                    val created = mutableListOf<java.io.File>()
                    try {
                        val first = NativeVideoEditor.export(
                            context, input, start * 1000L, split * 1000L, rotationDegrees,
                            muteOriginalAudio, selectedFilter, centerCrop, exportSpeed, overlayText,
                            textSizePx = overlayTextSize, textColorHex = overlayTextColor, textPosition = overlayTextPosition,
                            textPositionX = overlayTextX, textPositionY = overlayTextY,
                            sourceVolume = originalAudioVolume,
                            onProgress = { value -> editingProgress = (value / 2).coerceIn(0, 49) }
                        )
                        created.add(first)
                        val second = NativeVideoEditor.export(
                            context, input, split * 1000L, end * 1000L, rotationDegrees,
                            muteOriginalAudio, selectedFilter, centerCrop, exportSpeed, overlayText,
                            textSizePx = overlayTextSize, textColorHex = overlayTextColor, textPosition = overlayTextPosition,
                            textPositionX = overlayTextX, textPositionY = overlayTextY,
                            sourceVolume = originalAudioVolume,
                            onProgress = { value -> editingProgress = (50 + value / 2).coerceIn(50, 99) }
                        )
                        created.add(second)
                        val previousSelection = input
                        splitParts = created.map { Uri.fromFile(it) }
                        selected = splitParts.first()
                        if (previousSelection != selected) deleteNativeEditorTemp(context, previousSelection)
                        editing = false
                        editingProgress = 100
                        val meta = MediaMetadataRetriever()
                        try {
                            meta.setDataSource(first.absolutePath)
                            sourceDurationSeconds = ((meta.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) / 1000L).toInt()
                            sourceWidth = meta.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: sourceWidth
                            sourceHeight = meta.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: sourceHeight
                        } finally { meta.release() }
                        status = "दोनों clips तैयार हैं। नीचे Clip 1 या Clip 2 चुनकर अलग-अलग Publish करें।"
                    } catch (e: Exception) {
                        created.forEach { it.delete() }
                        splitParts = emptyList()
                        status = e.message ?: "Video split असफल हुआ।"
                    } finally { uploading = false }
                }
            }) { Text("Split into 2 clips") }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = trimStartSeconds, onValueChange = { trimStartSeconds = it.filter(Char::isDigit) }, modifier = Modifier.weight(1f), label = { Text("Trim start (sec)") }, singleLine = true, enabled = !uploading)
                OutlinedTextField(value = trimEndSeconds, onValueChange = { trimEndSeconds = it.filter(Char::isDigit) }, modifier = Modifier.weight(1f), label = { Text("Trim end (sec)") }, singleLine = true, enabled = !uploading)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { rotationDegrees = (rotationDegrees + 270) % 360 }, enabled = !uploading) { Text("Rotate left") }
                OutlinedButton(onClick = { rotationDegrees = (rotationDegrees + 90) % 360 }, enabled = !uploading) { Text("Rotate right") }
                FilterChip(selected = muteOriginalAudio, onClick = { muteOriginalAudio = !muteOriginalAudio }, enabled = !uploading, label = { Text(if (muteOriginalAudio) "Original audio muted" else "Keep original audio") })
                FilterChip(selected = centerCrop, onClick = { centerCrop = !centerCrop }, enabled = !uploading, label = { Text(if (centerCrop) "Center crop on" else "Center crop off") })
                OutlinedButton(onClick = {
                    trimStartSeconds = "0"
                    trimEndSeconds = sourceDurationSeconds.toString()
                    splitAtSeconds = ""
                    rotationDegrees = 0
                    muteOriginalAudio = false
                    selectedFilter = "none"
                    centerCrop = false
                    exportSpeed = 1f
                    originalAudioVolume = 1f
                    overlayText = ""
                    overlayTextSize = 24
                    overlayTextColor = "#FFFFFF"
                    overlayTextPosition = "center"
                    overlayTextX = 0f
                    overlayTextY = 0f
                    status = "Editor settings reset हो गईं।"
                }, enabled = !uploading) { Text("Reset") }
            }
            Text("Export speed", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0.5f, 1f, 1.5f, 2f).forEach { speed ->
                    FilterChip(selected = exportSpeed == speed, onClick = { exportSpeed = speed }, enabled = !uploading, label = { Text("${speed}x") })
                }
            }
            Text("Original Video Volume · ${(originalAudioVolume * 100).toInt()}%", style = MaterialTheme.typography.labelLarge)
            Slider(value = originalAudioVolume, onValueChange = { originalAudioVolume = it }, enabled = !uploading && !muteOriginalAudio)
            Text("Audio & Music Preview", style = MaterialTheme.typography.titleMedium)
            Text("Apply edits पर चुना हुआ Music और Voiceover, video audio के साथ final MP4 में mix होंगे। Music पूरे वीडियो में loop होगा।", style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { musicPicker.launch(arrayOf("audio/*")) }, enabled = !uploading, modifier = Modifier.weight(1f)) {
                    Text(if (musicUri == null) "Select Music" else "Change Music")
                }
                Button(
                    onClick = {
                        val player = musicPlayer
                        if (player == null) audioStatus = "पहले audio file चुनें।"
                        else if (player.isPlaying) { player.pause(); audioStatus = "Music Preview paused." }
                        else { player.play(); audioStatus = "Music Preview चल रहा है।" }
                    },
                    enabled = musicPlayer != null && !uploading,
                    modifier = Modifier.weight(1f)
                ) { Text(if (musicPlayer?.isPlaying == true) "Pause Music" else "Play Music") }
            }
            Text("Background Music Volume · ${(musicVolume * 100).toInt()}%")
            Slider(value = musicVolume, onValueChange = { musicVolume = it }, enabled = musicPlayer != null && !uploading)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                            microphonePermission.launch(android.Manifest.permission.RECORD_AUDIO)
                        } else {
                            try {
                                val file = File(context.cacheDir, "newstar-voiceover-${System.currentTimeMillis()}.m4a")
                                val recorder = MediaRecorder()
                                recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
                                recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                                recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                                recorder.setAudioEncodingBitRate(128000)
                                recorder.setAudioSamplingRate(44100)
                                recorder.setOutputFile(file.absolutePath)
                                recorder.prepare()
                                musicPlayer?.pause()
                                previewPlayer?.pause()
                                recorder.start()
                                voiceRecorder = recorder
                                voiceRecordingFile = file
                                voiceRecording = true
                                voiceoverFile?.delete()
                                voiceoverFile = null
                                audioStatus = "Voice recording चालू है। Stop Voice दबाएँ।"
                            } catch (e: Exception) {
                                voiceRecorder?.let { runCatching { it.release() } }
                                voiceRecorder = null
                                voiceRecordingFile?.delete()
                                voiceRecordingFile = null
                                voiceRecording = false
                                audioStatus = e.message ?: "Voice recording शुरू नहीं हो सकी।"
                            }
                        }
                    },
                    enabled = !voiceRecording && !uploading,
                    modifier = Modifier.weight(1f)
                ) { Text("Start Voice") }
                OutlinedButton(
                    onClick = {
                        val recorder = voiceRecorder
                        val file = voiceRecordingFile
                        if (recorder == null || file == null || !voiceRecording) {
                            audioStatus = "अभी Voice recording चालू नहीं है।"
                        } else {
                            try {
                                recorder.stop()
                                recorder.reset()
                                recorder.release()
                                voiceRecorder = null
                                voiceRecordingFile = null
                                voiceRecording = false
                                voiceoverFile = file
                                audioStatus = "Voice recording तैयार है। Play Voice दबाकर सुनें।"
                            } catch (e: Exception) {
                                runCatching { recorder.reset() }
                                runCatching { recorder.release() }
                                voiceRecorder = null
                                voiceRecordingFile?.delete()
                                voiceRecordingFile = null
                                voiceRecording = false
                                audioStatus = e.message ?: "Voice recording save नहीं हो सकी।"
                            }
                        }
                    },
                    enabled = voiceRecording && !uploading,
                    modifier = Modifier.weight(1f)
                ) { Text("Stop Voice") }
            }
            if (voiceoverFile != null) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            val player = voicePlayer
                            if (player != null) {
                                if (player.isPlaying) { player.pause(); audioStatus = "Voice Preview paused." }
                                else { player.play(); audioStatus = "Voice Preview चल रहा है।" }
                            }
                        },
                        enabled = !uploading,
                        modifier = Modifier.weight(1f)
                    ) { Text(if (voicePlayer?.isPlaying == true) "Pause Voice" else "Play Voice") }
                    OutlinedButton(
                        onClick = {
                            voicePlayer?.pause()
                            voiceoverFile?.delete()
                            voiceoverFile = null
                            audioStatus = "Voice recording हटाई गई।"
                        },
                        enabled = !uploading,
                        modifier = Modifier.weight(1f)
                    ) { Text("Delete Voice") }
                }
                Text("Voice Volume · ${(voiceVolume * 100).toInt()}%")
                Slider(value = voiceVolume, onValueChange = { voiceVolume = it }, enabled = !uploading)
            }
            Text(audioStatus, style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(
                value = overlayText,
                onValueChange = { overlayText = it.take(100) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Video text overlay (optional)") },
                maxLines = 2,
                enabled = !uploading
            )
            Text("Text Size · $overlayTextSize px", style = MaterialTheme.typography.labelLarge)
            Slider(value = overlayTextSize.toFloat(), onValueChange = { overlayTextSize = it.toInt().coerceIn(14, 70) }, valueRange = 14f..70f, enabled = !uploading && overlayText.isNotBlank())
            Text("Text Color", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                listOf("#FFFFFF" to "White", "#FFFF00" to "Yellow", "#25F4EE" to "Cyan", "#FF4D8D" to "Pink").forEach { (hex, label) ->
                    FilterChip(selected = overlayTextColor == hex, onClick = { overlayTextColor = hex }, enabled = !uploading, label = { Text(label) })
                }
            }
            Text("Text Position · preview text को drag करके कहीं भी रखें", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("top" to "Top", "center" to "Center", "bottom" to "Bottom").forEach { (value, label) ->
                    FilterChip(selected = overlayTextPosition == value, onClick = { overlayTextPosition = value; overlayTextY = NativeVideoEditorPolicy.textPositionAnchorY(value); overlayTextX = 0f }, enabled = !uploading, label = { Text(label) })
                }
            }
            Text("Color filter", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = selectedFilter == "none", onClick = { selectedFilter = "none" }, enabled = !uploading, label = { Text("Original") })
                FilterChip(selected = selectedFilter == "grayscale", onClick = { selectedFilter = "grayscale" }, enabled = !uploading, label = { Text("Grayscale") })
                FilterChip(selected = selectedFilter == "inverted", onClick = { selectedFilter = "inverted" }, enabled = !uploading, label = { Text("Inverted") })
            }
            if (uploading && status.contains("edit/export")) {
                LinearProgressIndicator(progress = { editingProgress / 100f }, modifier = Modifier.fillMaxWidth())
                Text("Export $editingProgress%")
            }
            Button(enabled = !uploading && !voiceRecording, onClick = {
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
                        val output = NativeVideoEditor.export(
                            context, input, start * 1000L, end * 1000L, rotationDegrees,
                            muteOriginalAudio, selectedFilter, centerCrop, exportSpeed, overlayText,
                            textSizePx = overlayTextSize, textColorHex = overlayTextColor, textPosition = overlayTextPosition,
                            textPositionX = overlayTextX, textPositionY = overlayTextY,
                            sourceVolume = originalAudioVolume,
                            musicUri = musicUri, voiceoverFile = voiceoverFile,
                            musicVolume = musicVolume, voiceVolume = voiceVolume,
                            onProgress = { value -> editingProgress = value }
                        )
                        val previousSelection = input
                        selected = Uri.fromFile(output)
                        if (previousSelection != selected) deleteNativeEditorTemp(context, previousSelection)
                        val editedMetadata = MediaMetadataRetriever()
                        try {
                            editedMetadata.setDataSource(output.absolutePath)
                            sourceDurationSeconds = ((editedMetadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) / 1000L).toInt()
                            sourceWidth = editedMetadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: sourceWidth
                            sourceHeight = editedMetadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: sourceHeight
                        } finally {
                            editedMetadata.release()
                        }
                        trimStartSeconds = "0"
                        trimEndSeconds = sourceDurationSeconds.toString()
                        splitAtSeconds = ""
                        rotationDegrees = 0
                        muteOriginalAudio = false
                        originalAudioVolume = 1f
                        exportSpeed = 1f
                        overlayText = ""
                        editing = false
                        editingProgress = 100
                        val mixedAudio = musicUri != null || voiceoverFile != null
                        if (mixedAudio) {
                            musicPlayer?.pause()
                            voicePlayer?.pause()
                            musicUri = null
                            voiceoverFile = null
                            audioStatus = "Music और Voiceover final video में mix हो गए।"
                        }
                        status = "Edited video तैयार है। अब Publish दबाएँ।"
                    } catch (e: Exception) { status = e.message ?: "Video editing असफल हुई।" }
                    finally { uploading = false }
                }
            }) { Text("Apply edits and save") }
        }
        if (splitParts.isNotEmpty()) {
            Text("Split clips · एक बार में चुनी हुई clip Publish होगी।", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                splitParts.forEachIndexed { index, part ->
                    OutlinedButton(enabled = !uploading, onClick = {
                        selected = part
                        val meta = MediaMetadataRetriever()
                        try {
                            meta.setDataSource(part.path!!)
                            sourceDurationSeconds = ((meta.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) / 1000L).toInt()
                            sourceWidth = meta.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: sourceWidth
                            sourceHeight = meta.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: sourceHeight
                        } finally { meta.release() }
                        status = "Clip ${index + 1} चुनी गई। Publish दबाएँ।"
                    }) { Text("Clip ${index + 1}") }
                }
            }
        }
        OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth(), label = { Text("Title") })
        OutlinedTextField(description, { description = it }, Modifier.fillMaxWidth(), label = { Text("Description") })
        OutlinedTextField(category, { category = it }, Modifier.fillMaxWidth(), label = { Text("Category") })
        Text("Video Privacy", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("public" to "Public", "unlisted" to "Unlisted", "private" to "Private").forEach { (value, label) ->
                FilterChip(selected = visibility == value, onClick = { visibility = value }, enabled = !uploading, label = { Text(label) })
            }
        }
        Text("Audience", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = audienceType == "all", onClick = { audienceType = "all" }, enabled = !uploading, label = { Text("General") })
            FilterChip(selected = audienceType == "kids", onClick = { audienceType = "kids" }, enabled = !uploading, label = { Text("Made for Kids") })
        }
        Text("Media Type", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("video" to "Video", "movie" to "Film", "music" to "Music", "story" to "Story").forEach { (value, label) ->
                FilterChip(selected = mediaKind == value, onClick = { mediaKind = value }, enabled = !uploading, label = { Text(label) })
            }
        }
        OutlinedTextField(affiliateLink, { affiliateLink = it.take(1000) }, Modifier.fillMaxWidth(), label = { Text("Affiliate / Promotion HTTPS link (optional)") }, singleLine = true, enabled = !uploading)
        Text("Video Location · दुनिया भर की जगह खोजें", style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(
            value = locationQuery,
            onValueChange = {
                locationQuery = it.take(160)
                if (selectedLocation?.optString("name") != it.trim()) selectedLocation = null
            },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("City, region या country") },
            singleLine = true,
            enabled = !uploading
        )
        if (locationResults.isNotEmpty()) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                locationResults.forEach { feature ->
                    OutlinedButton(
                        onClick = {
                            selectedLocation = feature
                            locationQuery = feature.optString("name")
                            locationResults = emptyList()
                            locationSearchMessage = "✓ चुनी गई लोकेशन: ${feature.optString("name")}"
                        },
                        enabled = !uploading,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(feature.optString("name"), modifier = Modifier.fillMaxWidth()) }
                }
            }
        } else {
            Text(locationSearchMessage, style = MaterialTheme.typography.bodySmall)
        }
        Text("Location Visibility", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = locationVisibility == "public", onClick = { locationVisibility = "public" }, enabled = !uploading, label = { Text("Public location") })
            FilterChip(selected = locationVisibility == "hidden", onClick = { locationVisibility = "hidden" }, enabled = !uploading, label = { Text("Hide exact location") })
        }
        LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
        Text("$progress%")
        Button(enabled = selected != null && !uploading && activeUploadWork == null && !voiceRecording, onClick = {
            val uri = selected ?: return@Button
            try {
                if (uri.scheme == "content") {
                    runCatching {
                        context.contentResolver.takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                    }
                }
                NativeVideoUploadWork.enqueue(
                    context,
                    uri,
                    session.userId,
                    title,
                    description,
                    category,
                    visibility,
                    audienceType,
                    mediaKind,
                    affiliateLink,
                    selectedLocation?.toString(),
                    locationVisibility
                )
                uploading = true
                hasRecoverableUpload = true
                progress = 0
                status = "Background upload शुरू हो गया। App की दूसरी screen खोलने पर भी upload जारी रहेगा।"
            } catch (error: Exception) {
                uploading = false
                status = error.message ?: "Background upload शुरू नहीं हो सका।"
            }
        }) { Text(if (uploading) "Uploading in background…" else if (hasRecoverableUpload) "Resume Upload" else "Publish Video") }
        if (activeUploadWork != null) {
            TextButton(onClick = { NativeVideoUploadWork.cancel(context, session.userId) }) {
                Text("Cancel background upload")
            }
        }
        if (hasRecoverableUpload && !uploading) {
            TextButton(onClick = {
                NativeUploadDraftStore.clear(context, session.userId)
                deleteNativeEditorTemp(context, selected)
                selected = null
                hasRecoverableUpload = false
                progress = 0
                status = "Saved upload हटाया गया। आप नया वीडियो चुन सकते हैं।"
            }) { Text("Discard saved upload") }
        }
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
    private fun audioEffects(volume: Float): Effects {
        require(volume in 0f..1f) { "Audio volume 0 से 100% के बीच होना चाहिए।" }
        val channelMixer = ChannelMixingAudioProcessor()
        for (channels in 1..6) {
            channelMixer.putChannelMixingMatrix(
                ChannelMixingMatrix.createForConstantPower(channels, 2)
            )
        }
        val gain = GainProcessor(object : GainProcessor.GainProvider {
            override fun getGainFactorAtSamplePosition(samplePosition: Long, sampleRate: Int): Float = volume
            override fun isUnityUntil(samplePosition: Long, sampleRate: Int): Long =
                if (volume == 1f) C.TIME_END_OF_SOURCE else C.TIME_UNSET
        })
        return Effects(listOf(ToInt16PcmAudioProcessor(), channelMixer, gain), emptyList())
    }

    suspend fun export(
        context: Context,
        input: Uri,
        startMs: Long,
        endMs: Long,
        rotation: Int,
        muteAudio: Boolean,
        filter: String,
        centerCrop: Boolean,
        speed: Float,
        overlayText: String,
        textSizePx: Int = 24,
        textColorHex: String = "#FFFFFF",
        textPosition: String = "center",
        textPositionX: Float = 0f,
        textPositionY: Float = 0f,
        sourceVolume: Float = 1f,
        musicUri: Uri? = null,
        voiceoverFile: File? = null,
        musicVolume: Float = 0.4f,
        voiceVolume: Float = 1f,
        onProgress: (Int) -> Unit
    ): File = suspendCancellableCoroutine { continuation ->
        val output = File(context.cacheDir, "newstar-edit-" + System.currentTimeMillis() + ".mp4")
        val clipping = MediaItem.ClippingConfiguration.Builder()
            .setStartPositionMs(startMs)
            .setEndPositionMs(endMs)
            .build()
        val mediaItem = MediaItem.Builder().setUri(input).setClippingConfiguration(clipping).build()
        val videoEffects = mutableListOf<androidx.media3.common.Effect>()
        if (rotation != 0) {
            videoEffects.add(ScaleAndRotateTransformation.Builder().setRotationDegrees(rotation.toFloat()).build())
        }
        if (centerCrop) videoEffects.add(Crop(-0.8f, 0.8f, -0.8f, 0.8f))
        when (filter) {
            "grayscale" -> videoEffects.add(RgbFilter.createGrayscaleFilter())
            "inverted" -> videoEffects.add(RgbFilter.createInvertedFilter())
            "none" -> Unit
            else -> error("Unsupported video filter.")
        }
        if (overlayText.isNotBlank()) {
            NativeVideoEditorPolicy.requireTextStyle(textSizePx, textColorHex, textPosition)
            NativeVideoEditorPolicy.requireTextCoordinates(textPositionX, textPositionY)
            val text = SpannableString(overlayText.trim().take(100))
            text.setSpan(ForegroundColorSpan(Color.parseColor(textColorHex)), 0, text.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            text.setSpan(BackgroundColorSpan(Color.argb(170, 0, 0, 0)), 0, text.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            text.setSpan(AbsoluteSizeSpan(textSizePx), 0, text.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            val overlaySettings = StaticOverlaySettings.Builder()
                .setBackgroundFrameAnchor(textPositionX, textPositionY)
                .setOverlayFrameAnchor(0f, 0f)
                .build()
            videoEffects.add(OverlayEffect(listOf(TextOverlay.createStaticTextOverlay(text, overlaySettings))))
        }
        NativeVideoEditorPolicy.requireSpeed(speed)
        val speedProvider = object : SpeedProvider {
            override fun getSpeed(timeUs: Long): Float = speed
            override fun getNextSpeedChangeTimeUs(timeUs: Long): Long = C.TIME_UNSET
        }
        val hasExternalAudio = musicUri != null || voiceoverFile != null
        NativeVideoEditorPolicy.requireSourceVolume(sourceVolume)
        val videoAudioEffects = if (muteAudio) emptyList() else audioEffects(sourceVolume).audioProcessors
        val editedVideo = EditedMediaItem.Builder(mediaItem)
            .setEffects(Effects(videoAudioEffects, videoEffects))
            .setRemoveAudio(muteAudio)
            .setSpeed(speedProvider)
            .build()

        val composition = if (hasExternalAudio) {
            val sequences = mutableListOf<EditedMediaItemSequence>()
            sequences.add(EditedMediaItemSequence.withAudioAndVideoFrom(listOf(editedVideo)))
            musicUri?.let { uri ->
                val musicItem = EditedMediaItem.Builder(MediaItem.fromUri(uri))
                    .setEffects(audioEffects(musicVolume))
                    .build()
                sequences.add(
                    EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_AUDIO))
                        .addItem(musicItem)
                        .setIsLooping(true)
                        .build()
                )
            }
            voiceoverFile?.let { file ->
                require(file.isFile && file.length() > 0L) { "Voice recording file उपलब्ध नहीं है। दोबारा रिकॉर्ड करें।" }
                val voiceItem = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(file)))
                    .setEffects(audioEffects(voiceVolume))
                    .build()
                sequences.add(
                    EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_AUDIO))
                        .addItem(voiceItem)
                        .build()
                )
            }
            Composition.Builder(sequences).build()
        } else null

        val progressHandler = android.os.Handler(android.os.Looper.getMainLooper())
        val progressHolder = androidx.media3.transformer.ProgressHolder()
        lateinit var progressPoll: Runnable
        val transformer = Transformer.Builder(context).addListener(object : Transformer.Listener {
            override fun onCompleted(compositionResult: Composition, exportResult: ExportResult) {
                progressHandler.removeCallbacks(progressPoll)
                if (continuation.isActive) {
                    onProgress(100)
                    continuation.resume(output)
                }
            }
            override fun onError(compositionResult: Composition, exportResult: ExportResult, exportException: ExportException) {
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
            if (composition != null) transformer.start(composition, output.absolutePath)
            else transformer.start(editedVideo, output.absolutePath)
            progressHandler.post(progressPoll)
        } catch (e: Exception) {
            progressHandler.removeCallbacks(progressPoll)
            output.delete()
            if (continuation.isActive) continuation.resumeWithException(e)
        }
    }
}
private object NativeUploadDraftStore {
    private const val PREFS = "newstar_native_upload_drafts"
    private const val KEY_ALIAS = "newstar_native_upload_draft_key"

    private fun encryptionKey(): java.security.Key {
        val keyStore = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? java.security.Key)?.let { return it }
        val generator = javax.crypto.KeyGenerator.getInstance(
            android.security.keystore.KeyProperties.KEY_ALGORITHM_AES,
            "AndroidKeyStore"
        )
        generator.init(
            android.security.keystore.KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or
                    android.security.keystore.KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    fun load(context: Context, userId: String): JSONObject? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(userId, null) ?: return null
        return try {
            val envelope = JSONObject(raw)
            val iv = Base64.decode(envelope.getString("iv"), Base64.NO_WRAP)
            val ciphertext = Base64.decode(envelope.getString("data"), Base64.NO_WRAP)
            val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                javax.crypto.Cipher.DECRYPT_MODE,
                encryptionKey(),
                javax.crypto.spec.GCMParameterSpec(128, iv)
            )
            JSONObject(String(cipher.doFinal(ciphertext), Charsets.UTF_8))
        } catch (_: Exception) {
            prefs.edit().remove(userId).commit()
            null
        }
    }

    fun save(context: Context, userId: String, draft: JSONObject) {
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, encryptionKey())
        val ciphertext = cipher.doFinal(draft.toString().toByteArray(Charsets.UTF_8))
        val envelope = JSONObject()
            .put("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .put("data", Base64.encodeToString(ciphertext, Base64.NO_WRAP))
            .toString()
        check(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(userId, envelope).commit()) {
            "Upload recovery state डिवाइस पर सुरक्षित नहीं हो सकी।"
        }
    }

    fun clear(context: Context, userId: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(userId).commit()
    }
}

internal object NativeVideoUploader {
    suspend fun upload(
        context: Context, uri: Uri, session: NativeSession, title: String, description: String,
        category: String, visibility: String, audienceType: String, mediaKind: String, affiliateLink: String,
        location: JSONObject?, locationVisibility: String,
        onProgress: suspend (Int) -> Unit, onStatus: suspend (String) -> Unit
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
        val savedDraft = NativeUploadDraftStore.load(context, session.userId)?.takeIf {
            it.optString("uri") == uri.toString() && it.optLong("size") == size &&
                it.optString("user_id") == session.userId
        }
        val finalizedVideoId = savedDraft?.optString("video_id").orEmpty()
        val createUrl = URL("https://uzsusgoetrkujmofxcmb.supabase.co/functions/v1/cloudflare-tus-proxy")
        var streamId = savedDraft?.optString("stream_id").orEmpty()
        var uploadUrl: URL? = savedDraft?.optString("upload_url")?.takeIf { it.isNotBlank() }?.let(::URL)
        if (savedDraft == null) {
            NativeUploadDraftStore.clear(context, session.userId)
            val create = (createUrl.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; connectTimeout = 20000; readTimeout = 60000
                setRequestProperty("apikey", "sb_publishable_Zz2hljevMsXBcK8c33UvFw_wuc2itVr")
                setRequestProperty("Authorization", "Bearer ${session.accessToken}")
                setRequestProperty("Origin", "https://new-star-delta.vercel.app")
                setRequestProperty("Tus-Resumable", "1.0.0")
                setRequestProperty("Upload-Length", size.toString())
                setRequestProperty("Upload-Metadata", metadata)
                setRequestProperty("Cache-Control", "no-store")
            }
            val code = create.responseCode
            if (code !in 200..299) {
                val error = (create.errorStream ?: create.inputStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
                create.disconnect()
                throw IllegalStateException("Upload session नहीं बनी ($code): ${error.take(500)}")
            }
            val locationHeader = create.getHeaderField("Location")
            streamId = create.getHeaderField("stream-media-id").orEmpty()
            create.disconnect()
            require(!locationHeader.isNullOrBlank()) { "Cloudflare upload location नहीं मिली।" }
            require(streamId.isNotBlank()) { "Cloudflare Stream ID नहीं मिली।" }
            uploadUrl = URL(createUrl, locationHeader)
        } else {
            withContext(Dispatchers.Main) { onStatus("पिछला अधूरा upload मिला। Cloudflare से सुरक्षित offset जाँचा जा रहा है…") }
        }
        val resolvedUploadUrl = requireNotNull(uploadUrl) { "Cloudflare upload URL उपलब्ध नहीं है।" }
        require(resolvedUploadUrl.protocol == "https" && resolvedUploadUrl.host.isNotBlank()) { "Cloudflare upload URL सुरक्षित HTTPS नहीं है।" }
        require(resolvedUploadUrl.host == "api.cloudflare.com" || resolvedUploadUrl.host.endsWith(".cloudflarestream.com") ||
            resolvedUploadUrl.host.endsWith(".videodelivery.net")) { "Cloudflare ने असुरक्षित या अपरिचित upload host लौटाया।" }

        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .build()
        val draft = savedDraft ?: JSONObject().apply {
            put("user_id", session.userId)
            put("uri", uri.toString())
            put("size", size)
            put("upload_url", resolvedUploadUrl.toString())
            put("stream_id", streamId)
            put("offset", 0L)
            put("title", title)
            put("description", description)
            put("category", category)
            put("visibility", visibility)
            put("audience_type", audienceType)
            put("media_kind", mediaKind)
            put("affiliate_link", affiliateLink)
            put("location", location ?: JSONObject.NULL)
            put("location_visibility", locationVisibility)
            put("duration", duration)
            put("width", width)
            put("height", height)
            put("file_name", name)
        }
        draft.put("title", title)
        draft.put("description", description)
        draft.put("category", category)
        draft.put("visibility", visibility)
        draft.put("audience_type", audienceType)
        draft.put("media_kind", mediaKind)
        draft.put("affiliate_link", affiliateLink)
        draft.put("location", location ?: JSONObject.NULL)
        draft.put("location_visibility", locationVisibility)
        NativeUploadDraftStore.save(context, session.userId, draft)
        var offset = if (finalizedVideoId.isNotBlank()) size else readTusOffset(client, resolvedUploadUrl, size)
        require(offset in 0L..size) { "Cloudflare ने upload का अमान्य resume offset दिया।" }
        draft.put("offset", offset)
        NativeUploadDraftStore.save(context, session.userId, draft)
        withContext(Dispatchers.Main) { onProgress(((offset * 100L) / size).toInt().coerceIn(0, 100)) }
        if (finalizedVideoId.isBlank()) {
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "वीडियो फ़ाइल पढ़ नहीं सकी।" }
            var skipped = 0L
            while (skipped < offset) {
                val count = input.skip(offset - skipped)
                if (count <= 0L) {
                    val consumed = input.read()
                    if (consumed < 0) throw IllegalStateException("Resume offset तक वीडियो फ़ाइल नहीं पढ़ सके।")
                    skipped++
                } else skipped += count
            }
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

                val expectedOffset = offset + wanted
                var accepted = false
                var retry = 0
                var lastFailure: Exception? = null
                while (!accepted && retry < 4) {
                    try {
                        val request = Request.Builder().url(resolvedUploadUrl)
                            .header("Tus-Resumable", "1.0.0")
                            .header("Upload-Offset", offset.toString())
                            .patch(chunk.toRequestBody("application/offset+octet-stream".toMediaType()))
                            .build()
                        client.newCall(request).execute().use { response ->
                            val responseOffset = response.header("Upload-Offset")?.toLongOrNull()
                            if (response.isSuccessful && responseOffset == expectedOffset) {
                                offset = expectedOffset
                                accepted = true
                            } else {
                                if (response.code in 400..499 && response.code !in setOf(408, 409, 425, 429)) {
                                    throw IllegalStateException(
                                        "Cloudflare ने upload रोक दिया (${response.code}): ${response.body?.string().orEmpty().take(240)}"
                                    )
                                }
                                lastFailure = IllegalStateException(
                                    "Chunk response ${response.code}; server offset verify किया जा रहा है।"
                                )
                            }
                        }
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        if (error.message?.startsWith("Cloudflare ने upload रोक दिया") == true) throw error
                        lastFailure = error
                    }

                    if (!accepted) {
                        val serverOffset = try {
                            readTusOffset(client, resolvedUploadUrl, size)
                        } catch (headError: Exception) {
                            lastFailure = IllegalStateException(
                                "Upload connection और server offset दोनों verify नहीं हुए: ${headError.message ?: "network error"}",
                                lastFailure
                            )
                            -1L
                        }
                        when (serverOffset) {
                            expectedOffset -> {
                                // PATCH reached Cloudflare, but its response was lost.
                                offset = expectedOffset
                                accepted = true
                            }
                            offset -> {
                                // Cloudflare did not accept this chunk; resend identical bytes.
                                retry++
                                if (retry < 4) kotlinx.coroutines.delay(1000L * (1L shl (retry - 1)))
                            }
                            -1L -> {
                                retry++
                                if (retry < 4) kotlinx.coroutines.delay(1000L * (1L shl (retry - 1)))
                            }
                            else -> throw IllegalStateException(
                                "Cloudflare का resumable offset अमान्य है ($serverOffset); expected $offset या $expectedOffset।",
                                lastFailure
                            )
                        }
                    }
                }
                if (!accepted) {
                    throw IllegalStateException(
                        "वीडियो upload चार कोशिशों के बाद भी जारी नहीं हो सका। नेटवर्क ठीक करके दोबारा Publish करें।",
                        lastFailure
                    )
                }
                draft.put("offset", offset)
                NativeUploadDraftStore.save(context, session.userId, draft)
                val percent = ((offset * 100) / size).toInt()
                withContext(Dispatchers.Main) { onProgress(percent) }
            }
        }
        }
        val username = runCatching {
            val profileRows = NativeSupabase.get(
                "/rest/v1/profiles?select=username&user_id=eq.${Uri.encode(session.userId)}&limit=1", session
            )
            profileRows.optJSONObject(0)?.optString("username").orEmpty()
        }.getOrDefault("").ifBlank { session.email.substringBefore("@").ifBlank { "New Star Creator" } }
        val caption = listOf(title.trim(), description.trim()).filter { it.isNotBlank() }.joinToString("\n\n").ifBlank { "New Star Video" }
        val safeAffiliate = affiliateLink.trim().takeIf { it.isNotEmpty() }?.also {
            val parsed = java.net.URI(it)
            require(parsed.scheme == "https" && !parsed.host.isNullOrBlank()) { "Affiliate link के लिए HTTPS URL जरूरी है।" }
        }
        require(visibility in setOf("public", "unlisted", "private")) { "Video privacy setting अमान्य है।" }
        require(audienceType in setOf("all", "kids")) { "Audience setting अमान्य है।" }
        require(mediaKind in setOf("video", "movie", "music", "story")) { "Media type अमान्य है।" }
        val row = JSONObject().apply {
            put("user_id", session.userId); put("video_url", "cloudflare://$streamId")
            put("video_provider", "cloudflare"); put("provider_video_id", streamId)
            put("provider_status", "processing"); put("processing_status", "processing")
            put("caption", caption); put("category", category.trim().ifBlank { "General" })
            put("username", username); put("likes_count", 0); put("views_count", 0)
            put("duration_seconds", duration); put("width", width); put("height", height)
            put("video_type", if (duration <= 60L) "short" else "long")
            put("visibility", visibility); put("audience_type", audienceType)
            put("media_kind", mediaKind); put("affiliate_link", safeAffiliate)
            put("aspect_ratio_setting", "original")
            put("quality_preference", "original"); put("source_quality", "${width}x$height")
            put("frame_fit", "contain")
            put("location_name", location?.optString("name")?.takeIf { it.isNotBlank() } ?: JSONObject.NULL)
            put("location_latitude", location?.optDouble("latitude")?.takeIf { it.isFinite() } ?: JSONObject.NULL)
            put("location_longitude", location?.optDouble("longitude")?.takeIf { it.isFinite() } ?: JSONObject.NULL)
            put("mapbox_place_id", location?.optString("id")?.takeIf { it.isNotBlank() } ?: JSONObject.NULL)
            put("location_visibility", if (locationVisibility == "hidden") "hidden" else "public")
        }
        val videoId = if (finalizedVideoId.isNotBlank()) finalizedVideoId else {
            // A process can die after Postgres commits but before video_id is
            // persisted in the encrypted local draft. Use the provider identity
            // as the database idempotency key so retry never creates a second row.
            val conflictTarget = "video_provider,provider_video_id"
            val savedRows = JSONArray(
                NativeSupabase.upsert(
                    "/rest/v1/videos?on_conflict=$conflictTarget",
                    row,
                    session,
                    ignoreDuplicates = true
                )
            )
            val insertedId = savedRows.optJSONObject(0)?.optString("id").orEmpty()
            val existingId = if (insertedId.isNotBlank()) insertedId else {
                val existingRows = NativeSupabase.get(
                    "/rest/v1/videos?select=id&video_provider=eq.cloudflare" +
                        "&provider_video_id=eq.${Uri.encode(streamId)}" +
                        "&user_id=eq.${Uri.encode(session.userId)}&limit=1",
                    session
                )
                existingRows.optJSONObject(0)?.optString("id").orEmpty()
            }
            require(existingId.isNotBlank()) {
                "Cloudflare upload सुरक्षित है, लेकिन इस account के लिए उसका Video record नहीं मिला। Stream ID: $streamId"
            }
            draft.put("video_id", existingId)
            NativeUploadDraftStore.save(context, session.userId, draft)
            existingId
        }
        withContext(Dispatchers.Main) { onStatus("वीडियो upload हो गया। Cloudflare processing status जाँचा जा रहा है…") }
        var finalStatus = "वीडियो upload हो गया। Cloudflare processing जारी है; तैयार होने पर वीडियो प्रोफ़ाइल में दिखेगा।"
        for (attempt in 0 until 6) {
            try {
                NativeSupabase.invokeFunction(
                    "video-url",
                    JSONObject().put("video_ids", JSONArray().put(videoId)),
                    session
                )
                val rows = NativeSupabase.get(
                    "/rest/v1/videos?select=processing_status,provider_status&id=eq.${Uri.encode(videoId)}&limit=1",
                    session
                )
                val rowStatus = rows.optJSONObject(0)
                val processing = rowStatus?.optString("processing_status").orEmpty()
                val provider = rowStatus?.optString("provider_status").orEmpty()
                if (processing == "published" && provider == "ready") {
                    finalStatus = "वीडियो सफलतापूर्वक तैयार और प्रकाशित हो गया है।"
                    break
                }
                if (processing == "failed" || provider == "error") {
                    finalStatus = "Cloudflare processing असफल हुआ। वीडियो रिकॉर्ड सुरक्षित है; Stream ID: $streamId"
                    break
                }
                finalStatus = "वीडियो upload पूरा है। Cloudflare processing जारी है (${attempt + 1}/6)…"
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                finalStatus = "वीडियो upload सुरक्षित है। Processing status अभी नहीं मिल सका: ${error.message?.take(160) ?: "अस्थायी त्रुटि"}"
            }
            if (attempt < 5) kotlinx.coroutines.delay(5000)
        }
        // Keep a completion marker until WorkManager commits SUCCEEDED.
        // If Android kills the process in this tiny interval, the retry sees
        // video_id + upload_complete and cannot create another Cloudflare upload.
        draft.put("upload_complete", true)
        NativeUploadDraftStore.save(context, session.userId, draft)
        withContext(Dispatchers.Main) { onStatus(finalStatus) }
    }

    private fun readTusOffset(client: OkHttpClient, uploadUrl: URL, expectedSize: Long): Long {
        val request = Request.Builder().url(uploadUrl)
            .header("Tus-Resumable", "1.0.0")
            .head()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("Cloudflare resume check failed (${response.code}).")
            }
            val serverLength = response.header("Upload-Length")?.toLongOrNull()
                ?: throw IllegalStateException("Cloudflare ने Upload-Length नहीं लौटाया।")
            require(serverLength == expectedSize) {
                "Cloudflare upload session का file size चुनी गई वीडियो से अलग है।"
            }
            val serverOffset = response.header("Upload-Offset")?.toLongOrNull()
                ?: throw IllegalStateException("Cloudflare ने resumable Upload-Offset नहीं लौटाया।")
            require(serverOffset in 0L..expectedSize) {
                "Cloudflare ने upload session का अमान्य offset लौटाया।"
            }
            return serverOffset
        }
    }

    private fun b64(value: String) = Base64.encodeToString(value.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
}


@Composable
fun NativeGamingScreen(
    session: NativeSession,
    roomId: String?,
    onStart: (String) -> Unit,
    onStop: () -> Unit
) {
    val context = LocalContext.current
    val gamingViewModel: NativeGamingViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
        key = "native-gaming-${session.userId}",
        factory = remember(session.userId) { NativeGamingViewModel.Factory(session) }
    )
    val state by gamingViewModel.state.collectAsStateWithLifecycle()
    val guestCameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        val cameraGranted = grants[android.Manifest.permission.CAMERA] == true ||
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (cameraGranted && state.guestRoomId.isNotBlank()) {
            val micGranted = grants[android.Manifest.permission.RECORD_AUDIO] == true ||
                ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
            gamingViewModel.startGuestPublisher(context, state.guestRoomId, micGranted)
        }
    }
    val guestPreview = remember(context, state.guestPublisherReady) {
        if (state.guestPublisherReady) SurfaceViewRenderer(context) else null
    }
    DisposableEffect(guestPreview, gamingViewModel) {
        guestPreview?.let(gamingViewModel::attachGuestPreview)
        onDispose {
            guestPreview?.let {
                gamingViewModel.detachGuestPreview(it)
                runCatching { it.release() }
            }
        }
    }

    DisposableEffect(context, gamingViewModel) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                gamingViewModel.updateStreamStatus(
                    status = intent?.getStringExtra("status").orEmpty(),
                    message = intent?.getStringExtra("message"),
                    micAvailable = if (intent?.hasExtra("mic_available") == true) intent.getBooleanExtra("mic_available", false) else null,
                    micEnabled = if (intent?.hasExtra("mic_enabled") == true) intent.getBooleanExtra("mic_enabled", false) else null
                )
            }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(GamingStreamService.STATUS), ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose {
            runCatching { context.unregisterReceiver(receiver) }
            gamingViewModel.stopPolling()
            gamingViewModel.stopPkPolling()
            gamingViewModel.stopGuestPublisher()
        }
    }

    LaunchedEffect(roomId, state.streaming, state.pkStatus) {
        if (!roomId.isNullOrBlank() && state.streaming) {
            gamingViewModel.startPolling(roomId)
            if (state.pkStatus != "idle") gamingViewModel.startPkPolling(roomId)
        } else {
            gamingViewModel.stopPolling()
            gamingViewModel.stopPkPolling()
        }
    }

    LaunchedEffect(session.userId) {
        gamingViewModel.refreshInvitations()
        while (true) {
            delay(12_000)
            gamingViewModel.refreshInvitations()
        }
    }

    LaunchedEffect(session.userId, state.guestRoomId) {
        val guestRoom = state.guestRoomId
        if (guestRoom.isNotBlank()) {
            gamingViewModel.refreshGuestSeat(guestRoom)
            while (true) {
                delay(4_000)
                gamingViewModel.refreshGuestSeat(guestRoom)
            }
        }
    }

    LaunchedEffect(roomId, state.streaming) {
        if (!roomId.isNullOrBlank() && state.streaming) {
            gamingViewModel.refreshMembers(roomId)
            while (true) {
                delay(5_000)
                gamingViewModel.refreshMembers(roomId)
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Native Gaming LIVE", style = MaterialTheme.typography.headlineSmall)
        Text("यह स्क्रीन Android MediaProjection और native GamingStreamService का उपयोग करती है। LIVE eligibility server पर जाँची जाएगी।")
        OutlinedTextField(
            value = state.title,
            onValueChange = gamingViewModel::setTitle,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Gaming room title") },
            enabled = !state.preparing && !state.streaming
        )
        Button(
            enabled = !state.preparing && !state.streaming && state.title.trim().isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                gamingViewModel.beginPreparing()
                onStart(state.title.trim())
            }
        ) { Text(if (state.preparing) "Preparing…" else "Start Gaming LIVE") }

        if (!state.streaming || roomId.isNullOrBlank()) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("आने वाले LIVE invitations", style = MaterialTheme.typography.titleMedium)
                        TextButton(onClick = { gamingViewModel.refreshInvitations() }, enabled = !state.invitationsLoading) {
                            Text(if (state.invitationsLoading) "Loading…" else "Refresh")
                        }
                    }
                    if (state.invitationStatusMessage.isNotBlank()) {
                        Text(state.invitationStatusMessage, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                    }
                    if (state.invitationError.isNotBlank()) {
                        Text(state.invitationError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    if (state.invitations.isEmpty() && !state.invitationsLoading) {
                        Text("कोई pending LIVE invitation नहीं है।", style = MaterialTheme.typography.bodySmall)
                    }
                    state.invitations.forEach { invite ->
                        val inviteId = invite.optString("id")
                        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(invite.optString("inviter_name", "New Star Creator"), style = MaterialTheme.typography.titleSmall)
                            Text(invite.optString("room_title", "New Star LIVE"), style = MaterialTheme.typography.bodyMedium)
                            Text("Invitation सीमित समय के लिए मान्य है। स्वीकार करने पर host को guest-seat request मिलेगी।", style = MaterialTheme.typography.bodySmall)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    enabled = state.invitationBusyId.isBlank(),
                                    modifier = Modifier.weight(1f),
                                    onClick = { gamingViewModel.respondToCreatorInvite(inviteId, invite.optString("room_id"), true) }
                                ) { Text(if (state.invitationBusyId == inviteId) "Please wait…" else "Accept") }
                                OutlinedButton(
                                    enabled = state.invitationBusyId.isBlank(),
                                    modifier = Modifier.weight(1f),
                                    onClick = { gamingViewModel.respondToCreatorInvite(inviteId, invite.optString("room_id"), false) }
                                ) { Text("Decline") }
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }

        if (state.guestRoomId.isNotBlank() && roomId.isNullOrBlank()) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("LIVE guest seat", style = MaterialTheme.typography.titleMedium)
                    Text("Seat status: ${state.guestSeatStatus.ifBlank { "checking" }}")
                    if (state.guestSeatNumber > 0) Text("Seat number: ${state.guestSeatNumber}")
                    Text(
                        "Camera: ${if (state.guestCameraAllowed) "allowed" else "waiting for host permission"} · Microphone: ${if (state.guestMicrophoneAllowed) "allowed" else "not allowed"}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (state.guestPublisherError.isNotBlank()) {
                        Text(state.guestPublisherError, color = if (state.guestPublisherReady) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    if (state.guestPublisherReady && guestPreview != null) {
                        if (state.guestPublisherConnectionState == "FAILED" || state.guestPublisherConnectionState == "DISCONNECTED") {
                            Text(
                                if (state.guestPublisherConnectionState == "FAILED") "LIVE connection failed. Retry करने से नया SFU session बनेगा।" else "Network वापस आने की प्रतीक्षा करें; जरूरत हो तो Retry दबाएँ।",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall
                            )
                            OutlinedButton(
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !state.guestPublisherStarting,
                                onClick = {
                                    val micGranted = ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
                                    gamingViewModel.retryGuestPublisher(context, micGranted)
                                }
                            ) { Text(if (state.guestPublisherStarting) "Reconnecting…" else "Retry camera connection") }
                        }
                        AndroidView(
                            factory = { guestPreview ?: throw IllegalStateException("Guest preview unavailable") },
                            modifier = Modifier.fillMaxWidth().height(240.dp)
                        )
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                modifier = Modifier.weight(1f),
                                enabled = !state.guestMediaUpdating,
                                onClick = { gamingViewModel.setGuestCameraEnabled(!state.guestPublisherCameraEnabled) }
                            ) { Text(if (state.guestPublisherCameraEnabled) "Turn camera off" else "Turn camera on") }
                            OutlinedButton(
                                modifier = Modifier.weight(1f),
                                enabled = state.guestMicrophoneAllowed && state.guestPublisherMicAvailable && !state.guestMediaUpdating,
                                onClick = { gamingViewModel.setGuestMicrophoneEnabled(!state.guestPublisherMicEnabled) }
                            ) { Text(if (state.guestPublisherMicEnabled) "Mute mic" else "Unmute mic") }
                        }
                    } else if (state.guestSeatStatus == "admitted" && state.guestCameraAllowed) {
                        Button(
                            enabled = !state.guestPublisherStarting,
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                guestCameraPermission.launch(
                                    arrayOf(android.Manifest.permission.CAMERA, android.Manifest.permission.RECORD_AUDIO)
                                )
                            }
                        ) { Text(if (state.guestPublisherStarting) "Connecting camera…" else "Join LIVE with camera") }
                    } else if (state.guestSeatStatus == "requested") {
                        Text("Host की approval का इंतज़ार करें।")
                    }
                    if (state.guestSeatStatus == "admitted") {
                        OutlinedButton(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { gamingViewModel.leaveGuestSeat(state.guestRoomId) }
                        ) { Text("Leave guest seat") }
                    }
                }
            }
        }

        if (state.streaming && state.actorRole == "host" && !roomId.isNullOrBlank()) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Gaming LIVE में creator invite करें", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        value = state.creatorQuery,
                        onValueChange = gamingViewModel::setCreatorQuery,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Username या creator name") },
                        singleLine = true,
                        enabled = !state.creatorSearching
                    )
                    Button(
                        onClick = { gamingViewModel.searchCreators() },
                        enabled = !state.creatorSearching && state.creatorQuery.trim().length >= 2,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(if (state.creatorSearching) "Searching…" else "Search creators") }
                    if (state.invitationError.isNotBlank()) {
                        Text(state.invitationError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    if (state.invitationStatusMessage.isNotBlank()) {
                        Text(state.invitationStatusMessage, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                    }
                    state.creatorSearchResults.forEach { creator ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column(Modifier.weight(1f)) {
                                Text(creator.displayName, style = MaterialTheme.typography.bodyMedium)
                                if (creator.username.isNotBlank()) Text("@" + creator.username, style = MaterialTheme.typography.bodySmall)
                            }
                            OutlinedButton(
                                enabled = state.inviteSendingUserId.isBlank(),
                                onClick = { gamingViewModel.sendCreatorInvite(roomId, creator.userId) }
                            ) { Text(if (state.inviteSendingUserId == creator.userId) "Sending…" else "Invite") }
                        }
                    }
                }
            }
        }

        if (state.streaming && state.micAvailable) {
            OutlinedButton(
                onClick = {
                    context.startService(
                        Intent(context, GamingStreamService::class.java)
                            .setAction(GamingStreamService.SET_MIC)
                            .putExtra(GamingStreamService.MIC_ENABLED, !state.micEnabled)
                    )
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (state.micEnabled) "Mute microphone" else "Unmute microphone") }
        }

        if (state.streaming && !roomId.isNullOrBlank()) {
            HorizontalDivider()
            Text("Gaming LIVE chat", style = MaterialTheme.typography.titleMedium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("like" to "👍", "heart" to "❤️", "clap" to "👏", "fire" to "🔥").forEach { (kind, emoji) ->
                    OutlinedButton(
                        enabled = !state.reactionBusy,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 2.dp, vertical = 8.dp),
                        onClick = { gamingViewModel.sendReaction(roomId, kind) }
                    ) { Text("$emoji ${state.reactionCounts[kind] ?: 0}") }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = state.chatText,
                    onValueChange = gamingViewModel::setChatText,
                    modifier = Modifier.weight(1f),
                    label = { Text("Message (max 500)") },
                    enabled = !state.chatSending,
                    maxLines = 3
                )
                Button(
                    enabled = !state.chatSending && state.chatText.isNotBlank(),
                    onClick = { gamingViewModel.sendMessage(roomId) }
                ) { Text(if (state.chatSending) "…" else "Send") }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(if (state.chatLoading) "Chat loading…" else "${state.chatMessages.size} messages")
                TextButton(onClick = { gamingViewModel.refreshChat(true) }) { Text("Refresh") }
            }
            if (state.chatError.isNotBlank()) {
                Text(state.chatError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            if (state.chatMessages.isEmpty()) {
                Text("अभी कोई message नहीं है।", style = MaterialTheme.typography.bodySmall)
            } else {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 260.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(state.chatMessages, key = { it.optString("id") }) { item ->
                        Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                            Text(item.optString("display_name", "Creator"), style = MaterialTheme.typography.labelMedium)
                            Text(item.optString("message"), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }

        if (state.streaming && !roomId.isNullOrBlank()) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Friendly LIVE PK Battle", style = MaterialTheme.typography.titleMedium)
                    Text("केवल अपने active Gaming LIVE के host के रूप में matchmaking शुरू करें।")
                    when (state.pkStatus) {
                        "waiting" -> Text("🌐 दूसरे वास्तविक LIVE creator का इंतज़ार हो रहा है…")
                        "matched" -> {
                            Text("⚔️ Opponent: ${state.pkOpponentName}")
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("आपके LIVE likes: ${state.pkOwnScore}")
                                Text("Opponent likes: ${state.pkOpponentScore}")
                            }
                        }
                        else -> Text("कोई सक्रिय PK search या battle नहीं है।")
                    }
                    if (state.pkStatus == "idle") {
                        Button(
                            enabled = !state.pkBusy,
                            onClick = { gamingViewModel.joinPkQueue(roomId) },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(if (state.pkBusy) "Joining queue…" else "🌐 Find real opponent") }
                    } else {
                        OutlinedButton(
                            enabled = !state.pkBusy,
                            onClick = { gamingViewModel.cancelPk() },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                if (state.pkBusy) "Please wait…"
                                else if (state.pkStatus == "matched") "End friendly battle"
                                else "Cancel search"
                            )
                        }
                    }
                }
            }
        }

        if (state.streaming && !roomId.isNullOrBlank() &&
            (state.actorRole == "host" || state.actorRole == "moderator" || state.moderationError.isNotBlank())
        ) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Gaming LIVE moderation", style = MaterialTheme.typography.titleMedium)
                        TextButton(
                            enabled = !state.moderationLoading && state.moderationBusyUserId.isBlank(),
                            onClick = { gamingViewModel.refreshMembers(roomId) }
                        ) { Text(if (state.moderationLoading) "Loading…" else "Refresh") }
                    }
                    if (state.moderationError.isNotBlank()) {
                        Text(state.moderationError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    if (state.members.isEmpty() && !state.moderationLoading) {
                        Text("अभी कोई requested या admitted participant नहीं है।", style = MaterialTheme.typography.bodySmall)
                    }
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(state.members, key = { it.optString("user_id") }) { member ->
                            val memberId = member.optString("user_id")
                            val role = member.optString("role", "guest")
                            val canManage = role != "host" && (state.actorRole == "host" || role != "moderator")
                            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                Text(
                                    "${member.optString("display_name", "Guest")} · $role · ${member.optString("status")}",
                                    style = MaterialTheme.typography.labelLarge
                                )
                                Text(
                                    "Camera permission: ${if (member.optBoolean("camera_allowed")) "allowed" else "blocked"} · Camera active: ${if (member.optBoolean("camera_enabled")) "yes" else "no"} · Mic permission: ${if (member.optBoolean("microphone_allowed")) "allowed" else "blocked"} · Mic active: ${if (member.optBoolean("microphone_enabled")) "yes" else "no"}",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                if (canManage) {
                                    val busy = state.moderationBusyUserId.isNotBlank()
                                    if (state.actorRole == "host" && member.optString("status") == "requested") {
                                        Button(
                                            enabled = !busy,
                                            modifier = Modifier.fillMaxWidth(),
                                            onClick = { gamingViewModel.admitGuestSeat(roomId, member) }
                                        ) {
                                            Text(if (state.moderationBusyUserId == memberId) "Assigning seat…" else "Admit guest to LIVE seat")
                                        }
                                    }
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        OutlinedButton(
                                            enabled = !busy,
                                            modifier = Modifier.weight(1f),
                                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp),
                                            onClick = { gamingViewModel.setMemberCamera(roomId, member) }
                                        ) { Text(if (member.optBoolean("camera_allowed")) "Block camera" else "Allow camera") }
                                        OutlinedButton(
                                            enabled = !busy,
                                            modifier = Modifier.weight(1f),
                                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp),
                                            onClick = { gamingViewModel.setMemberMicrophone(roomId, member) }
                                        ) { Text(if (member.optBoolean("microphone_allowed")) "Block mic" else "Allow mic") }
                                    }
                                    if (state.actorRole == "host" && member.optString("status") == "admitted") {
                                        OutlinedButton(
                                            enabled = !busy,
                                            modifier = Modifier.fillMaxWidth(),
                                            onClick = { gamingViewModel.toggleMemberModerator(roomId, member) }
                                        ) { Text(if (role == "moderator") "Remove moderator" else "Make moderator") }
                                    }
                                    OutlinedButton(
                                        enabled = !busy,
                                        modifier = Modifier.fillMaxWidth(),
                                        onClick = { gamingViewModel.removeMember(roomId, member) }
                                    ) { Text(if (state.moderationBusyUserId == memberId) "Updating…" else "Remove participant") }
                                }
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
        }

        OutlinedButton(
            onClick = { gamingViewModel.stopLive(onStop) },
            enabled = !state.pkBusy && (state.preparing || state.streaming || !roomId.isNullOrBlank()),
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (state.pkBusy) "Stopping LIVE…" else "Stop Gaming LIVE") }
        Text(state.statusMessage)
        Text(
            "Game/system audio अलग से capture और WebRTC में mix नहीं किया गया है; उपलब्धता Android version और device restrictions पर निर्भर करती है।",
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
fun NativeWalletScreen(session: NativeSession) {
    var coinBalance by remember { mutableLongStateOf(0L) }
    var giftGross by remember { mutableLongStateOf(0L) }
    var giftNet by remember { mutableLongStateOf(0L) }
    var giftFee by remember { mutableLongStateOf(0L) }
    var ledger by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var orders by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var withdrawals by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var packages by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var packagesError by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    var refresh by remember { mutableIntStateOf(0) }

    LaunchedEffect(session.userId, refresh) {
        loading = true
        error = ""
        try {
            val userId = Uri.encode(session.userId)
            var balanceTotal = 0L
            var pageStart = 0
            var pageSize: Int
            do {
                val balancePage = NativeSupabase.getRange(
                    "/rest/v1/wallet_ledger?select=coin_delta&user_id=eq.$userId&order=created_at.asc",
                    session, pageStart, pageStart + 499
                )
                pageSize = balancePage.length()
                balanceTotal += (0 until pageSize).sumOf { balancePage.optJSONObject(it)?.optLong("coin_delta") ?: 0L }
                pageStart += pageSize
            } while (pageSize == 500)
            coinBalance = balanceTotal

            val ledgerRows = NativeSupabase.get(
                "/rest/v1/wallet_ledger?select=entry_type,coin_delta,amount_minor,currency,created_at&user_id=eq.$userId&order=created_at.desc&limit=30",
                session
            )
            ledger = (0 until ledgerRows.length()).mapNotNull { ledgerRows.optJSONObject(it) }

            val earningsRows = NativeSupabase.get(
                "/rest/v1/creator_gift_earnings?select=coins,gross_coins,platform_fee_coins&creator_id=eq.$userId",
                session
            )
            val earnings = (0 until earningsRows.length()).mapNotNull { earningsRows.optJSONObject(it) }
            giftGross = earnings.sumOf { it.optLong("gross_coins", it.optLong("coins")) }
            giftNet = earnings.sumOf { it.optLong("coins") }
            giftFee = earnings.sumOf { it.optLong("platform_fee_coins") }

            val orderRows = NativeSupabase.get(
                "/rest/v1/payment_orders?select=id,provider,amount_minor,currency,status,created_at&user_id=eq.$userId&order=created_at.desc&limit=20",
                session
            )
            orders = (0 until orderRows.length()).mapNotNull { orderRows.optJSONObject(it) }

            val withdrawalRows = NativeSupabase.get(
                "/rest/v1/withdrawal_requests?select=id,amount,currency,country_code,payout_method,status,created_at&creator_id=eq.$userId&order=created_at.desc&limit=20",
                session
            )
            withdrawals = (0 until withdrawalRows.length()).mapNotNull { withdrawalRows.optJSONObject(it) }

            try {
                val packageRows = NativeSupabase.get(
                    "/rest/v1/coin_packages?select=id,code,coins,bonus_coins,amount_minor,currency,country_code,channel,active&active=eq.true&order=amount_minor.asc",
                    session
                )
                packages = (0 until packageRows.length()).mapNotNull { packageRows.optJSONObject(it) }
                packagesError = ""
            } catch (packageFailure: Exception) {
                packages = emptyList()
                packagesError = packageFailure.message ?: "Coin package catalogue अभी उपलब्ध नहीं है।"
            }
        } catch (e: Exception) {
            error = e.message ?: "Wallet data लोड नहीं हुआ।"
        } finally {
            loading = false
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Wallet & Earnings", style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = { refresh++ }, enabled = !loading) { Text("Refresh") }
        }
        Text("सिर्फ verified server ledger से मिली जानकारी दिखाई जाती है।")
        if (loading) CircularProgressIndicator()
        Card(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
            Column(Modifier.padding(16.dp)) {
                Text("Verified Coin Balance", style = MaterialTheme.typography.labelLarge)
                Text(coinBalance.toString() + " Coins", style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(12.dp))
                Text("Creator gift earnings", style = MaterialTheme.typography.titleMedium)
                Text("Gross: $giftGross Coins")
                Text("Your recorded net: $giftNet Coins")
                Text("Platform fee: $giftFee Coins")
            }
        }
        Text("Published Coin Packages", style = MaterialTheme.typography.titleLarge)
        Text(
            "नीचे केवल server-published package जानकारी है। Native app से coin खरीद, payment या payout शुरू नहीं किया जा सकता।",
            style = MaterialTheme.typography.bodySmall
        )
        if (packagesError.isNotBlank()) {
            Text(packagesError, color = MaterialTheme.colorScheme.error)
        }
        if (packages.isEmpty() && !loading && packagesError.isBlank()) {
            Text("अभी कोई active coin package प्रकाशित नहीं है।")
        }
        packages.forEach { packageRow ->
            val baseCoins = packageRow.optLong("coins")
            val bonusCoins = packageRow.optLong("bonus_coins")
            val totalCoins = baseCoins + bonusCoins
            val amountMinor = packageRow.optLong("amount_minor")
            val currencyCode = packageRow.optString("currency").ifBlank { "USD" }.uppercase()
            val fractionDigits = runCatching {
                java.util.Currency.getInstance(currencyCode).defaultFractionDigits
            }.getOrDefault(2).coerceAtLeast(0)
            val formattedAmount = java.math.BigDecimal.valueOf(amountMinor, fractionDigits)
                .setScale(fractionDigits, java.math.RoundingMode.HALF_UP).toPlainString()
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("$baseCoins Coins", style = MaterialTheme.typography.titleMedium)
                    Text("+$bonusCoins bonus · $totalCoins total")
                    Text("$currencyCode $formattedAmount · ${packageRow.optString("country_code").ifBlank { "Global" }}")
                    Text("Channel: ${packageRow.optString("channel").ifBlank { "Not specified" }}")
                    OutlinedButton(onClick = {}, enabled = false) { Text("Purchase unavailable") }
                }
            }
        }
        Text("Coin Ledger", style = MaterialTheme.typography.titleLarge)
        LazyColumn(
            Modifier.weight(1f, fill = true),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(ledger) { entry ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(entry.optString("entry_type").ifBlank { "Ledger entry" },
                            style = MaterialTheme.typography.titleMedium)
                        Text((if (entry.optLong("coin_delta") > 0) "+" else "") +
                            entry.optLong("coin_delta") + " Coins")
                        Text(entry.optString("created_at"), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            if (ledger.isEmpty() && !loading) item {
                Text("अभी कोई verified Coin Ledger entry नहीं है।")
            }
            item { Text("Payment Orders", style = MaterialTheme.typography.titleLarge) }
            items(orders) { order ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(order.optString("provider").ifBlank { "Payment order" })
                        Text(order.optString("currency") + " " + order.optString("amount_minor"))
                        Text("Status: " + order.optString("status"))
                        Text(order.optString("created_at"), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            if (orders.isEmpty() && !loading) item { Text("अभी कोई Payment Order नहीं है।") }
            item { Text("Withdrawal History", style = MaterialTheme.typography.titleLarge) }
            items(withdrawals) { request ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(request.optString("payout_method").ifBlank { "Payout request" })
                        Text(request.optString("currency") + " " + request.optString("amount"))
                        Text("Status: " + request.optString("status"))
                        Text(request.optString("created_at"), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            if (withdrawals.isEmpty() && !loading) item { Text("अभी कोई Withdrawal Request नहीं है।") }
        }
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        Text(
            "Coin खरीद और cash payout अभी बंद हैं। किसी payment या payout को इस स्क्रीन से शुरू नहीं किया जा सकता।",
            style = MaterialTheme.typography.bodySmall
        )
    }
}



private data class NativeAiMessage(val role: String, val content: String)

@Composable
fun NativeCreatorAiScreen(session: NativeSession, initialPrompt: String = "") {
    val context = LocalContext.current
    val appLanguage = remember {
        context.getSharedPreferences("newstar_preferences", Context.MODE_PRIVATE)
            .getString("language", "hi") ?: "hi"
    }
    var prompt by remember { mutableStateOf(initialPrompt) }
    var messages by remember { mutableStateOf<List<NativeAiMessage>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var voiceStatus by remember { mutableStateOf("माइक दबाकर बोलें। रिकॉर्डिंग रोकने पर आवाज़ टेक्स्ट में बदलेगी।") }
    var recording by remember { mutableStateOf(false) }
    var recorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var recordingFile by remember { mutableStateOf<File?>(null) }
    var generatedImage by remember { mutableStateOf<Bitmap?>(null) }
    var imageRatio by remember { mutableStateOf("16:9") }
    var audioPlayer by remember { mutableStateOf<MediaPlayer?>(null) }
    var speaking by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val microphonePermission = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            try {
                val file = File(context.cacheDir, "creator-ai-voice-${System.currentTimeMillis()}.m4a")
                val activeRecorder = MediaRecorder().apply {
                    setAudioSource(MediaRecorder.AudioSource.MIC)
                    setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    setAudioEncodingBitRate(96_000)
                    setAudioSamplingRate(44_100)
                    setMaxDuration(60_000)
                    setMaxFileSize(6_500_000L)
                    setOutputFile(file.absolutePath)
                    prepare()
                    start()
                }
                recorder = activeRecorder
                recordingFile = file
                recording = true
                voiceStatus = "रिकॉर्डिंग चालू है… अधिकतम 60 सेकंड। रोकने के लिए Stop recording दबाएँ।"
                error = ""
            } catch (e: Exception) {
                recorder?.runCatching { stop() }
                recorder?.release()
                recorder = null
                recording = false
                voiceStatus = "माइक रिकॉर्डिंग शुरू नहीं हुई।"
                error = e.message ?: "Microphone recording शुरू नहीं हो सकी।"
            }
        } else {
            voiceStatus = "माइक अनुमति नहीं मिली। आप लिखकर भी AI से पूछ सकते हैं।"
        }
    }

    fun stopAndTranscribe() {
        val activeRecorder = recorder ?: return
        val file = recordingFile
        recorder = null
        recording = false
        try {
            activeRecorder.stop()
        } catch (e: Exception) {
            error = "रिकॉर्डिंग बहुत छोटी थी या आवाज़ रिकॉर्ड नहीं हुई। फिर कोशिश करें।"
            voiceStatus = error
        } finally {
            activeRecorder.release()
        }
        if (file == null || !file.exists() || file.length() < 256L) {
            error = "रिकॉर्डिंग खाली है। माइक के पास बोलकर फिर कोशिश करें।"
            return
        }
        voiceStatus = "आवाज़ को टेक्स्ट में बदला जा रहा है…"
        busy = true
        scope.launch {
            try {
                val encoded = withContext(Dispatchers.IO) {
                    Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
                }
                require(encoded.length <= 11_000_000) { "रिकॉर्डिंग बहुत बड़ी है। छोटा voice message रिकॉर्ड करें।" }
                val response = NativeSupabase.invokeFunction(
                    "ai-gateway",
                    JSONObject()
                        .put("action", "transcribe")
                        .put("audio", encoded)
                        .put("mimeType", "audio/mp4"),
                    session
                )
                if (!response.optBoolean("ok")) {
                    throw IllegalStateException(response.optString("error", "Voice transcription असफल हुई।"))
                }
                val transcript = response.optString("transcript").trim()
                require(transcript.isNotBlank()) { "आवाज़ से कोई शब्द समझ नहीं आया।" }
                prompt = transcript
                voiceStatus = "आवाज़ टेक्स्ट में बदल गई। जाँचकर Ask AI दबाएँ।"
                error = ""
            } catch (e: Exception) {
                error = e.message ?: "Voice transcription असफल हुई।"
                voiceStatus = "आवाज़ समझ नहीं आई। आप दोबारा बोल सकते हैं या लिख सकते हैं।"
            } finally {
                withContext(Dispatchers.IO) { runCatching { file.delete() } }
                recordingFile = null
                busy = false
            }
        }
    }

    fun speakReply(text: String) {
        if (busy || speaking) return
        speaking = true
        error = ""
        scope.launch {
            var audioFile: File? = null
            try {
                val response = NativeSupabase.invokeFunction(
                    "ai-gateway",
                    JSONObject().put("action", "speak").put("text", text),
                    session
                )
                if (!response.optBoolean("ok")) {
                    throw IllegalStateException(response.optString("error", "Spoken reply उपलब्ध नहीं है।"))
                }
                val encoded = response.optString("audio")
                require(encoded.isNotBlank()) { "AI voice ने audio data नहीं लौटाया।" }
                val bytes = Base64.decode(encoded, Base64.DEFAULT)
                audioFile = withContext(Dispatchers.IO) {
                    File(context.cacheDir, "creator-ai-reply-${System.currentTimeMillis()}.audio").apply {
                        writeBytes(bytes)
                    }
                }
                audioPlayer?.runCatching { stop() }
                audioPlayer?.release()
                val player = MediaPlayer()
                audioPlayer = player
                player.setDataSource(audioFile.absolutePath)
                player.setOnPreparedListener { it.start() }
                player.setOnCompletionListener {
                    it.release()
                    if (audioPlayer === it) audioPlayer = null
                    speaking = false
                    voiceStatus = "AI का बोलकर जवाब पूरा हुआ।"
                    audioFile?.delete()
                }
                player.setOnErrorListener { failed, _, _ ->
                    failed.release()
                    if (audioPlayer === failed) audioPlayer = null
                    speaking = false
                    error = "इस डिवाइस पर AI audio नहीं चल सका।"
                    audioFile?.delete()
                    true
                }
                player.prepareAsync()
                voiceStatus = "AI जवाब बोल रहा है…"
            } catch (e: Exception) {
                audioFile?.delete()
                speaking = false
                error = e.message ?: "AI voice उपलब्ध नहीं है।"
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            recorder?.runCatching { stop() }
            recorder?.release()
            audioPlayer?.runCatching { stop() }
            audioPlayer?.release()
            recordingFile?.delete()
        }
    }

    Column(Modifier.fillMaxSize().padding(14.dp)) {
        Text("New Star Creator Studio AI", style = MaterialTheme.typography.headlineSmall)
        Text("AI chat · Images · Voice · Multilingual", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(8.dp))
        Text("Creator tools", style = MaterialTheme.typography.titleSmall)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AssistChip(onClick = { prompt = "Give me 10 original video ideas for my channel." }, label = { Text("Video ideas") })
            AssistChip(onClick = { prompt = "Write an engaging video title, description and hashtags for this topic: " }, label = { Text("Title & tags") })
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AssistChip(onClick = { prompt = "Write an original short story about: " }, label = { Text("Story writer") })
            AssistChip(onClick = { prompt = "Create a content plan for my next 7 videos about: " }, label = { Text("7-day plan") })
        }
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(messages.size) { index ->
                val item = messages[index]
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(if (item.role == "user") "You" else "New Star AI",
                            style = MaterialTheme.typography.labelMedium)
                        Text(item.content)
                        if (item.role == "assistant") {
                            TextButton(
                                enabled = !speaking && !busy,
                                onClick = { speakReply(item.content) }
                            ) { Text("🔊 Read aloud") }
                        }
                    }
                }
            }
            if (generatedImage != null) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Image(
                                bitmap = generatedImage!!.asImageBitmap(),
                                contentDescription = "AI generated creator image",
                                modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
                                contentScale = androidx.compose.ui.layout.ContentScale.Fit
                            )
                            Button(onClick = {
                                val bitmap = generatedImage
                                if (bitmap != null) scope.launch {
                                    try {
                                        val values = ContentValues().apply {
                                            put(MediaStore.Images.Media.DISPLAY_NAME, "new-star-creator-" + System.currentTimeMillis() + ".png")
                                            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                                            if (Build.VERSION.SDK_INT >= 29) put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/New Star")
                                        }
                                        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                                            ?: throw IllegalStateException("Gallery में image save नहीं हो सकी।")
                                        val saved = context.contentResolver.openOutputStream(uri)?.use { output ->
                                            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
                                        } ?: false
                                        if (!saved) throw IllegalStateException("Image file लिखी नहीं जा सकी।")
                                        error = "Image Gallery के Pictures/New Star folder में save हो गई।"
                                    } catch (e: Exception) {
                                        error = e.message ?: "Image save नहीं हुई।"
                                    }
                                }
                            }, modifier = Modifier.fillMaxWidth()) { Text("Save image to Gallery") }
                        }
                    }
                }
            }
        }
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        Text(voiceStatus, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(
            value = prompt,
            onValueChange = { if (it.length <= 3500) prompt = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("अपना सवाल या image prompt लिखें") },
            minLines = 2,
            maxLines = 5,
            enabled = !busy && !recording
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                modifier = Modifier.weight(1f),
                enabled = !busy && !recording && prompt.isNotBlank(),
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
                                JSONObject().put("messages", payload).put("mode", "creator").put("language", appLanguage),
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
                enabled = !busy && !recording && prompt.isNotBlank(),
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
                                    .put("aspectRatio", imageRatio).put("imageSize", "1K").put("language", appLanguage),
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
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            if (recording) {
                Button(onClick = { stopAndTranscribe() }, enabled = !busy) { Text("■ Stop recording") }
            } else {
                OutlinedButton(
                    enabled = !busy && !speaking,
                    onClick = {
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                            microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
                        } else {
                            microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    }
                ) { Text("🎙️ Speak") }
            }
            FilterChip(selected = imageRatio == "16:9", onClick = { imageRatio = "16:9" }, label = { Text("16:9") })
            FilterChip(selected = imageRatio == "9:16", onClick = { imageRatio = "9:16" }, label = { Text("9:16") })
            FilterChip(selected = imageRatio == "1:1", onClick = { imageRatio = "1:1" }, label = { Text("1:1") })
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { messages = emptyList(); generatedImage = null; error = "" }, enabled = !busy && !recording) { Text("Clear") }
        }
    }
}
