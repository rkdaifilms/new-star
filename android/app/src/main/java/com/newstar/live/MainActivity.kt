package com.newstar.live

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

private const val API_BASE = "https://uzsusgoetrkujmofxcmb.supabase.co"
private const val PUBLIC_API_KEY = "sb_publishable_Zz2hljevMsXBcK8c33UvFw_wuc2itVr"

data class NativeVideo(
    val id: String, val url: String, val caption: String, val username: String,
    val category: String, val likes: Int, val views: Int, val type: String
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                NativeHome { page ->
                    startActivity(Intent(this, LegacyWebActivity::class.java).putExtra("page", page))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NativeHome(openPage: (String) -> Unit) {
    var category by remember { mutableStateOf("All") }
    var search by remember { mutableStateOf("") }
    var feed by remember { mutableStateOf<List<NativeVideo>>(emptyList()) }
    val visibleFeed = remember(feed, search) {
        val term = search.trim().lowercase()
        if (term.isEmpty()) feed else feed.filter { video ->
            video.caption.lowercase().contains(term) ||
                video.username.lowercase().contains(term) ||
                video.category.lowercase().contains(term)
        }
    }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }

    LaunchedEffect(category, refresh) {
        loading = true
        error = null
        try {
            feed = NativeFeed.load(category)
            if (feed.isEmpty()) error = "अभी कोई सार्वजनिक वीडियो उपलब्ध नहीं है।"
        } catch (e: Exception) {
            error = e.message ?: "Feed लोड नहीं हो सकी।"
        } finally {
            loading = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("🌟 New Star", fontWeight = FontWeight.Bold) },
                actions = { TextButton(onClick = { openPage("profile.html") }) { Text("Creator Hub") } }
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(true, { category = "All" }, icon = { Text("⌂") }, label = { Text("Home") })
                NavigationBarItem(false, { openPage("camera.html") }, icon = { Text("+") }, label = { Text("Upload") })
                NavigationBarItem(false, { openPage("inbox.html") }, icon = { Text("✉") }, label = { Text("Inbox") })
                NavigationBarItem(false, { openPage("gaming.html") }, icon = { Text("▣") }, label = { Text("Gaming") })
                NavigationBarItem(false, { openPage("profile.html") }, icon = { Text("●") }, label = { Text("Profile") })
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                singleLine = true,
                label = { Text("इस Feed में वीडियो या Creator खोजें") },
                trailingIcon = {
                    if (search.isNotEmpty()) TextButton(onClick = { search = "" }) { Text("Clear") }
                }
            )
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(listOf("All", "Comedy", "Music", "Gaming", "Tech", "Vlogs")) { name ->
                    FilterChip(selected = category == name, onClick = { category = name }, label = { Text(name) })
                }
            }
            when {
                loading -> Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                    CircularProgressIndicator()
                }
                error != null -> Column(
                    Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(error.orEmpty(), style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { refresh++ }) { Text("Retry") }
                }
                else if (visibleFeed.isEmpty() -> Box(
                    Modifier.fillMaxSize().padding(24.dp),
                    contentAlignment = androidx.compose.ui.Alignment.Center
                ) {
                    Text("इस खोज में कोई वीडियो नहीं मिला।")
                }
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(visibleFeed, key = { it.id }) { video -> NativeVideoCard(video) }
                }
            }
        }
    }
}

@Composable
private fun NativeVideoCard(video: NativeVideo) {
    val context = LocalContext.current
    val exoPlayer = remember(video.url) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(video.url))
            prepare()
            playWhenReady = false
        }
    }
    DisposableEffect(exoPlayer) { onDispose { exoPlayer.release() } }
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column {
            AndroidView(
                modifier = Modifier.fillMaxWidth().height(if (video.type == "long") 235.dp else 500.dp),
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        player = exoPlayer
                        useController = true
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    }
                },
                update = { it.player = exoPlayer }
            )
            Column(Modifier.fillMaxWidth().padding(14.dp)) {
                Text("@${video.username.ifBlank { "creator" }}", fontWeight = FontWeight.Bold)
                if (video.caption.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(video.caption)
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "${video.category} · ${video.likes} likes · ${video.views} views",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private object NativeFeed {
    suspend fun load(category: String): List<NativeVideo> = withContext(Dispatchers.IO) {
        val args = JSONObject()
            .put("p_limit", 30).put("p_offset", 0)
            .put("p_video_type", JSONObject.NULL)
            .put("p_category", if (category == "All") JSONObject.NULL else category)
        val rows = post("$API_BASE/rest/v1/rpc/get_recommended_video_feed", args) as? JSONArray
            ?: throw IllegalStateException("Feed response invalid.")
        val byId = LinkedHashMap<String, JSONObject>()
        val ids = JSONArray()
        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: continue
            val id = row.optString("id")
            if (id.isNotBlank()) { byId[id] = row; ids.put(id) }
        }
        if (ids.length() == 0) return@withContext emptyList()
        val resolved = post("$API_BASE/functions/v1/video-url", JSONObject().put("video_ids", ids)) as? JSONObject
            ?: throw IllegalStateException("Video URL response invalid.")
        if (!resolved.optBoolean("ok")) throw IllegalStateException("वीडियो एक्सेस सत्यापित नहीं हुआ।")
        val urls = resolved.optJSONObject("urls") ?: JSONObject()
        byId.mapNotNull { (id, row) ->
            val playback = urls.optString(id).takeIf { it.startsWith("https://") } ?: return@mapNotNull null
            NativeVideo(
                id, playback,
                row.optString("caption").takeUnless { it == "null" }.orEmpty(),
                row.optString("username").takeUnless { it == "null" }.orEmpty(),
                row.optString("category").takeUnless { it == "null" }.orEmpty(),
                row.optInt("likes_count"), row.optInt("views_count"),
                row.optString("video_type").ifBlank { "short" }
            )
        }.toList()
    }

    private fun post(endpoint: String, body: JSONObject): Any {
        val connection = URL(endpoint).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 15000
            connection.readTimeout = 25000
            connection.doOutput = true
            connection.setRequestProperty("apikey", PUBLIC_API_KEY)
            connection.setRequestProperty("Authorization", "Bearer $PUBLIC_API_KEY")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) throw IllegalStateException("Server error $status: ${response.take(160)}")
            return if (response.trimStart().startsWith("[")) JSONArray(response) else JSONObject(response)
        } finally {
            connection.disconnect()
        }
    }
}
