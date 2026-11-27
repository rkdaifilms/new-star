package com.newstar.live

import android.app.Activity
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import coil.compose.AsyncImage
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
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
    private var pendingGamingSession: NativeSession? = null
    private var pendingGamingRoomId: String? = null
    private var pendingGamingWhip: String? = null
    private var activeGamingRoomId: String? = null
    private var activeGamingSession: NativeSession? = null
    private var gamingStatusReceiver: BroadcastReceiver? = null
    private var queuedGamingSession: NativeSession? = null
    private var queuedGamingTitle: String? = null
    private val oauthSession = mutableStateOf<NativeSession?>(null)

    private val microphonePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val session = queuedGamingSession
        val title = queuedGamingTitle
        queuedGamingSession = null
        queuedGamingTitle = null
        if (session != null && title != null) createGamingRoom(session, title, granted)
    }

    private val screenPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val session = pendingGamingSession
        val roomId = pendingGamingRoomId
        val whip = pendingGamingWhip
        pendingGamingSession = null
        pendingGamingRoomId = null
        pendingGamingWhip = null
        if (result.resultCode == Activity.RESULT_OK && result.data != null && session != null && roomId != null && whip != null) {
            val micGranted = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
            val intent = Intent(this, GamingStreamService::class.java).apply {
                action = GamingStreamService.START
                putExtra(GamingStreamService.RESULT_CODE, result.resultCode)
                putExtra(GamingStreamService.RESULT_DATA, result.data)
                putExtra(GamingStreamService.WHIP_URL, whip)
                putExtra(GamingStreamService.INCLUDE_MIC, micGranted)
            }
            ContextCompat.startForegroundService(this, intent)
        } else if (session != null && roomId != null) {
            activeGamingRoomId = roomId
            lifecycleScope.launch {
                try {
                    NativeSupabase.invokeFunction("cloudflare-live",
                        JSONObject().put("action", "stop").put("room_id", roomId), session)
                } catch (_: Exception) { }
                finally { if (activeGamingRoomId == roomId) { activeGamingRoomId = null; activeGamingSession = null } }
            }
            sendBroadcast(Intent(GamingStreamService.STATUS).setPackage(packageName)
                .putExtra("status", "error")
                .putExtra("message", "Screen sharing permission रद्द हुई; LIVE room बंद कर दिया गया।"))
            android.widget.Toast.makeText(this, "Screen sharing रद्द हुई; LIVE room बंद कर दिया गया।", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        gamingStatusReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val state = intent?.getStringExtra("status").orEmpty()
                val roomId = activeGamingRoomId ?: return
                val session = activeGamingSession ?: return
                if (state == "connected") {
                    lifecycleScope.launch {
                        try {
                            NativeSupabase.invokeFunction("cloudflare-live",
                                JSONObject().put("action", "confirm").put("room_id", roomId), session)
                        } catch (e: Exception) {
                            android.widget.Toast.makeText(this@MainActivity,
                                e.message ?: "LIVE confirm नहीं हुआ।", android.widget.Toast.LENGTH_LONG).show()
                            stopGaming(session)
                        }
                    }
                } else if (state == "error") {
                    lifecycleScope.launch {
                        try {
                            NativeSupabase.invokeFunction("cloudflare-live",
                                JSONObject().put("action", "stop").put("room_id", roomId), session)
                        } catch (_: Exception) { }
                        finally {
                            if (activeGamingRoomId == roomId) {
                                activeGamingRoomId = null
                                activeGamingSession = null
                            }
                        }
                    }
                }
            }
        }.also { receiver ->
            ContextCompat.registerReceiver(this, receiver, IntentFilter(GamingStreamService.STATUS),
                ContextCompat.RECEIVER_NOT_EXPORTED)
        }
        handleOAuthCallback(intent)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                NativeApp(
                    googleSession = oauthSession.value,
                    onGoogleSignIn = { NativeGoogleOAuth.launch(this) },
                    onStartGaming = ::prepareGaming,
                    onStopGaming = ::stopGaming
                )
            }
        }
    }

    override fun onDestroy() {
        gamingStatusReceiver?.let { runCatching { unregisterReceiver(it) } }
        gamingStatusReceiver = null
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleOAuthCallback(intent)
    }

    private fun handleOAuthCallback(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme != "com.newstar.live" || uri.host != "auth" || uri.path != "/callback") return
        val code = uri.getQueryParameter("code") ?: return
        val state = uri.getQueryParameter("state") ?: return
        lifecycleScope.launch {
            val pending = NativeSessionStore.consumePendingOAuth(this@MainActivity)
            if (pending == null || pending.first != state) {
                android.widget.Toast.makeText(this@MainActivity, "Google Login state verification failed.", android.widget.Toast.LENGTH_LONG).show()
                return@launch
            }
            try {
                oauthSession.value = NativeSupabase.exchangeCode(this@MainActivity, code, pending.second)
            } catch (e: Exception) {
                android.widget.Toast.makeText(this@MainActivity, e.message ?: "Google Login failed.", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun prepareGaming(session: NativeSession, title: String) {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            createGamingRoom(session, title, true)
        } else {
            queuedGamingSession = session
            queuedGamingTitle = title
            microphonePermission.launch(android.Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun createGamingRoom(session: NativeSession, title: String, micGranted: Boolean) {
        lifecycleScope.launch {
            try {
                val response = NativeSupabase.invokeFunction("cloudflare-live", JSONObject()
                    .put("action", "prepare")
                    .put("room_title", title)
                    .put("room_type", "gaming")
                    .put("seat_capacity", 1), session)
                if (!response.optBoolean("ok")) throw IllegalStateException(response.optString("error", "Gaming room तैयार नहीं हुआ।"))
                val roomId = response.getString("room_id")
                pendingGamingSession = session
                pendingGamingRoomId = roomId
                pendingGamingWhip = response.getString("whip_url")
                activeGamingRoomId = roomId
                activeGamingSession = session
                if (!micGranted) {
                    android.widget.Toast.makeText(this@MainActivity, "Microphone अनुमति नहीं मिली; LIVE केवल screen video के साथ शुरू होगा।", android.widget.Toast.LENGTH_LONG).show()
                }
                val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                screenPermission.launch(manager.createScreenCaptureIntent())
            } catch (e: Exception) {
                val detail = e.message ?: "Gaming LIVE शुरू नहीं हुआ।"
                sendBroadcast(Intent(GamingStreamService.STATUS).setPackage(packageName)
                    .putExtra("status", "error").putExtra("message", detail))
                android.widget.Toast.makeText(this@MainActivity, detail, android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun stopGaming(session: NativeSession) {
        val stop = Intent(this, GamingStreamService::class.java).apply { action = GamingStreamService.STOP }
        startService(stop)
        lifecycleScope.launch {
            try {
                val current = NativeSessionStore.load(this@MainActivity) ?: session
                val roomId = activeGamingRoomId ?: return@launch
            NativeSupabase.invokeFunction("cloudflare-live", JSONObject().put("action", "stop").put("room_id", roomId), current)
            activeGamingRoomId = null
            activeGamingSession = null
            } catch (_: Exception) { }
        }
    }
}

@Composable
private fun NativeApp(
    googleSession: NativeSession?,
    onGoogleSignIn: () -> Unit,
    onStartGaming: (NativeSession, String) -> Unit,
    onStopGaming: (NativeSession) -> Unit
) {
    val context = LocalContext.current
    var session by remember { mutableStateOf<NativeSession?>(null) }
    var checkingSession by remember { mutableStateOf(true) }
    var page by remember { mutableStateOf("Home") }
    var selectedCreatorId by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        session = NativeSupabase.validSession(context)
        checkingSession = false
    }
    LaunchedEffect(googleSession) {
        if (googleSession != null) {
            session = googleSession
            page = "Home"
        }
    }
    LaunchedEffect(session?.refreshToken) {
        while (session != null) {
            delay(60_000)
            val refreshed = NativeSupabase.validSession(context)
            if (refreshed == null) {
                NativeSessionStore.clear(context)
                session = null
                page = "Home"
                break
            }
            session = refreshed
        }
    }

    if (checkingSession) {
        Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    if (page == "Home") {
        NativeHome(session = session, onOpenCreator = { selectedCreatorId = it; page = "Creator" }) { page = it }
    } else if (session == null) {
        NativeAuthScreen(onSignedIn = { session = it; page = "Home" }, onGoogleSignIn = onGoogleSignIn)
    } else {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.Start) {
                TextButton(onClick = { page = "Home" }) { Text("← Home") }
                Spacer(Modifier.weight(1f))
                Text(page, modifier = Modifier.padding(12.dp), fontWeight = FontWeight.Bold)
            }
            when (page) {
                "Upload" -> NativeUploadScreen(session!!)
                "Inbox" -> NativeInboxScreen(session!!)
                "Wallet" -> NativeWalletScreen(session!!)
                "Creator AI" -> NativeCreatorAiScreen(session!!)
                "Live" -> NativeLiveDiscoveryScreen(session!!)
                "Gaming" -> NativeGamingScreen(
                    session = session!!,
                    onStart = { title -> onStartGaming(session!!, title) },
                    onStop = { onStopGaming(session!!) }
                )
                "Creator" -> selectedCreatorId?.let { NativePublicCreatorScreen(it, session!!) }
                "Profile" -> NativeProfileScreen(session!!) {
                    val current = session
                    if (current != null) scope.launch {
                        try { NativeSupabase.deleteSession(context, current) }
                        finally { session = null; page = "Home" }
                    }
                }
                else -> NativeHome(session = session, onOpenCreator = { selectedCreatorId = it; page = "Creator" }) { page = it }
            }
        }
    }
}


@Composable
private fun NativeLiveDiscoveryScreen(session: NativeSession) {
    var rooms by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    var refresh by remember { mutableIntStateOf(0) }
    var creatorProfiles by remember { mutableStateOf<Map<String, JSONObject>>(emptyMap()) }

    LaunchedEffect(session.userId) {
        while (true) {
            delay(20_000)
            refresh++
        }
    }

    LaunchedEffect(session.userId, refresh) {
        loading = true
        error = ""
        try {
            val rows = NativeSupabase.get(
                "/rest/v1/live_rooms?select=id,creator_id,room_title,stream_playback_url,viewer_count,room_type,started_at&is_live=eq.true&order=started_at.desc&limit=50",
                session
            )
            val loaded = (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }
            rooms = loaded
            val creatorIds = loaded.map { it.optString("creator_id") }.filter { it.isNotBlank() }.distinct()
            creatorProfiles = if (creatorIds.isEmpty()) emptyMap() else {
                val ids = Uri.encode("(${creatorIds.joinToString(",")})")
                val profiles = NativeSupabase.get(
                    "/rest/v1/creator_public_profiles?select=user_id,username,full_name,avatar_url&user_id=in.$ids",
                    session
                )
                (0 until profiles.length()).mapNotNull { profiles.optJSONObject(it) }
                    .associateBy { it.optString("user_id") }
            }
        } catch (e: Exception) {
            error = e.message ?: "Live rooms लोड नहीं हुए।"
        } finally { loading = false }
    }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("LIVE Now", style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = { refresh++ }, enabled = !loading) { Text("Refresh") }
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        if (!loading && rooms.isEmpty() && error.isBlank()) Text("अभी कोई Creator Live नहीं है।")
        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(rooms) { room ->
                val playback = room.optString("stream_playback_url")
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(room.optString("room_title").ifBlank { "New Star Live" },
                            style = MaterialTheme.typography.titleLarge)
                        val creator = creatorProfiles[room.optString("creator_id")]
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            AsyncImage(
                                model = creator?.optString("avatar_url")?.takeIf { it.startsWith("https://") },
                                contentDescription = "LIVE creator avatar",
                                modifier = Modifier.size(34.dp).clip(CircleShape)
                            )
                            Column(Modifier.padding(start = 8.dp)) {
                                Text(creator?.optString("full_name").orEmpty().ifBlank {
                                    creator?.optString("username").orEmpty().ifBlank { "New Star Creator" }
                                })
                                Text("@${creator?.optString("username").orEmpty()} · ${room.optInt("viewer_count")} viewers",
                                    style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        if (playback.startsWith("https://")) {
                            val context = LocalContext.current
                            val player = remember(playback) {
                                ExoPlayer.Builder(context).build().apply {
                                    setMediaItem(MediaItem.fromUri(playback))
                                    prepare()
                                    playWhenReady = false
                                }
                            }
                            DisposableEffect(player) { onDispose { player.release() } }
                            AndroidView(
                                factory = { viewContext ->
                                    PlayerView(viewContext).apply {
                                        useController = true
                                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                                        this.player = player
                                    }
                                },
                                update = { it.player = player },
                                modifier = Modifier.fillMaxWidth().height(220.dp)
                            )
                        } else {
                            Text("इस Live room का playback अभी उपलब्ध नहीं है।")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NativePublicCreatorScreen(creatorId: String, session: NativeSession) {
    var profile by remember { mutableStateOf<JSONObject?>(null) }
    var videos by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var following by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    LaunchedEffect(creatorId, session.userId) {
        try {
            val safeId = Uri.encode(creatorId)
            val profiles = NativeSupabase.get(
                "/rest/v1/creator_public_profiles?select=user_id,username,full_name,avatar_url,bio,is_private&user_id=eq.$safeId",
                session
            )
            profile = profiles.optJSONObject(0)
            val relation = NativeSupabase.get(
                "/rest/v1/followers?select=id&follower_id=eq.${Uri.encode(session.userId)}&following_id=eq.$safeId",
                session
            )
            following = relation.length() > 0
            val rows = NativeSupabase.get(
                "/rest/v1/videos?select=id,caption,category,video_type,likes_count,views_count,created_at,processing_status&user_id=eq.$safeId&order=created_at.desc&limit=60",
                session
            )
            val ids = JSONArray()
            val sourceRows = (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }
            sourceRows.forEach { ids.put(it.optString("id")) }
            if (ids.length() > 0) {
                val resolved = NativeSupabase.invokeFunction(
                    "video-url", JSONObject().put("video_ids", ids), session
                )
                require(resolved.optBoolean("ok")) { "Creator video playback URL resolve नहीं हुआ।" }
                val urls = resolved.optJSONObject("urls") ?: JSONObject()
                videos = sourceRows.mapNotNull { row ->
                    val id = row.optString("id")
                    val url = urls.optString(id)
                    if (!url.startsWith("https://")) null else JSONObject()
                        .put("id", id).put("url", url)
                        .put("caption", row.optString("caption"))
                        .put("category", row.optString("category"))
                        .put("video_type", row.optString("video_type"))
                        .put("likes_count", row.optInt("likes_count"))
                        .put("views_count", row.optInt("views_count"))
                        .put("username", profile?.optString("username").orEmpty())
                }
            } else videos = emptyList()
        } catch (e: Exception) {
            error = e.message ?: "Creator profile लोड नहीं हुई।"
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        if (profile == null && error.isBlank()) CircularProgressIndicator()
        profile?.let { creator ->
            val avatar = creator.optString("avatar_url")
            if (avatar.isNotBlank()) {
                AsyncImage(model = avatar, contentDescription = "Creator photo",
                    modifier = Modifier.size(88.dp).clip(RoundedCornerShape(44.dp)),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop)
            }
            Text(creator.optString("full_name").ifBlank { creator.optString("username") },
                style = MaterialTheme.typography.headlineSmall)
            Text("@${creator.optString("username")}")
            if (creator.optString("bio").isNotBlank()) Text(creator.optString("bio"))
            if (creatorId != session.userId) {
                Button(enabled = !busy, onClick = {
                    busy = true
                    scope.launch {
                        try {
                            if (following) {
                                NativeSupabase.delete(
                                    "/rest/v1/followers?follower_id=eq.${Uri.encode(session.userId)}&following_id=eq.${Uri.encode(creatorId)}",
                                    session
                                )
                                following = false
                            } else {
                                NativeSupabase.post("/rest/v1/followers", JSONObject()
                                    .put("follower_id", session.userId)
                                    .put("following_id", creatorId), session)
                                following = true
                            }
                        } catch (e: Exception) {
                            error = e.message ?: "Follow update नहीं हुआ।"
                        } finally { busy = false }
                    }
                }) { Text(if (busy) "Please wait…" else if (following) "Following" else "Follow") }
            }
            if (creator.optBoolean("is_private") && !following && creatorId != session.userId) {
                Text("यह Private Creator account है। वीडियो देखने के लिए Follow request स्वीकार होना जरूरी है।")
            } else {
                Text("Videos", style = MaterialTheme.typography.titleLarge)
                LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(videos) { row ->
                        val id = row.optString("id")
                        val nativeVideo = NativeVideo(
                            id = id,
                            url = row.optString("url"),
                            caption = row.optString("caption"),
                            username = row.optString("username"),
                            category = row.optString("category"),
                            likes = row.optInt("likes_count"),
                            views = row.optInt("views_count"),
                            type = row.optString("video_type").ifBlank { "short" }
                        )
                        NativeVideoCard(nativeVideo, session)
                    }
                    if (videos.isEmpty()) item { Text("अभी कोई उपलब्ध वीडियो नहीं है।") }
                }
            }
        }
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NativeHome(session: NativeSession?, onOpenCreator: (String) -> Unit, onNavigate: (String) -> Unit) {
    var category by remember { mutableStateOf("All") }
    var feedMode by remember { mutableStateOf("For You") }
    var search by remember { mutableStateOf("") }
    var creatorResults by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var creatorSearchLoading by remember { mutableStateOf(false) }
    var creatorSearchError by remember { mutableStateOf<String?>(null) }
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

    LaunchedEffect(category, feedMode, refresh, session?.userId) {
        loading = true
        error = null
        try {
            feed = when (feedMode) {
                "Following" -> {
                    val current = session ?: throw IllegalStateException("Following feed देखने के लिए Login करें।")
                    NativeFeed.loadFollowing(current)
                }
                "Shorts", "Reels" -> NativeFeed.load(category, "short")
                "Long videos" -> NativeFeed.load(category, "long")
                else -> NativeFeed.load(category)
            }
            if (feed.isEmpty()) error = if (feedMode == "Following") "आप जिन Creators को Follow करते हैं, उनकी अभी कोई सार्वजनिक वीडियो नहीं है।" else "अभी कोई सार्वजनिक वीडियो उपलब्ध नहीं है।"
        } catch (e: Exception) {
            error = e.message ?: "Feed लोड नहीं हो सकी।"
        } finally { loading = false }
    }

    LaunchedEffect(search, session?.userId) {
        val term = search.trim().replace("%", "").replace("*", "").replace(",", " ").replace("(", " ").replace(")", " ")
        if (term.length < 2) {
            creatorResults = emptyList()
            creatorSearchError = null
            creatorSearchLoading = false
        } else {
            delay(350)
            creatorSearchLoading = true
            creatorSearchError = null
            try {
                val filter = Uri.encode("(username.ilike.*$term*,full_name.ilike.*$term*)")
                val rows = NativeSupabase.get(
                    "/rest/v1/creator_public_profiles?select=user_id,username,full_name,avatar_url,bio,is_private&or=$filter&order=username.asc&limit=20",
                    session
                )
                creatorResults = (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }
            } catch (e: Exception) {
                creatorResults = emptyList()
                creatorSearchError = e.message ?: "Creator search नहीं हो सकी।"
            } finally { creatorSearchLoading = false }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("🌟 New Star", fontWeight = FontWeight.Bold) },
                actions = { Row { TextButton(onClick = { onNavigate("Creator AI") }) { Text("Studio AI") }; TextButton(onClick = { onNavigate("Profile") }) { Text("Creator Hub") } } }
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(true, { category = "All" }, icon = { Text("⌂") }, label = { Text("Home") })
                NavigationBarItem(false, { onNavigate("Upload") }, icon = { Text("+") }, label = { Text("Upload") })
                NavigationBarItem(false, { onNavigate("Inbox") }, icon = { Text("✉") }, label = { Text("Inbox") })
                NavigationBarItem(false, { onNavigate("Live") }, icon = { Text("●") }, label = { Text("Live") })
                NavigationBarItem(false, { onNavigate("Gaming") }, icon = { Text("▣") }, label = { Text("Gaming") })
                NavigationBarItem(false, { onNavigate("Wallet") }, icon = { Text("₹") }, label = { Text("Wallet") })
                NavigationBarItem(false, { onNavigate("Profile") }, icon = { Text("●") }, label = { Text("Profile") })
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = search, onValueChange = { search = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                singleLine = true, label = { Text("इस Feed में वीडियो या Creator खोजें") },
                trailingIcon = { if (search.isNotEmpty()) TextButton(onClick = { search = "" }) { Text("Clear") } }
            )
            if (search.trim().length >= 2) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                    Text("Creator खोज", style = MaterialTheme.typography.titleMedium)
                    when {
                        creatorSearchLoading -> LinearProgressIndicator(Modifier.fillMaxWidth())
                        creatorSearchError != null -> Text(creatorSearchError.orEmpty(), color = MaterialTheme.colorScheme.error)
                        creatorResults.isEmpty() -> Text("इस नाम से कोई Creator नहीं मिला।", style = MaterialTheme.typography.bodySmall)
                        else -> creatorResults.forEach { creator ->
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 6.dp).clickable { onOpenCreator(creator.optString("user_id")) },
                                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                            ) {
                                val avatar = creator.optString("avatar_url")
                                if (avatar.isNotBlank()) {
                                    AsyncImage(
                                        model = avatar,
                                        contentDescription = "Creator profile photo",
                                        modifier = Modifier.size(44.dp).clip(RoundedCornerShape(22.dp)),
                                        contentScale = androidx.compose.ui.layout.ContentScale.Crop
                                    )
                                } else {
                                    Surface(Modifier.size(44.dp), shape = RoundedCornerShape(22.dp),
                                        color = MaterialTheme.colorScheme.secondaryContainer) {
                                        Box(contentAlignment = androidx.compose.ui.Alignment.Center) {
                                            Text(creator.optString("username").take(1).uppercase())
                                        }
                                    }
                                }
                                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                                    Text(creator.optString("full_name").ifBlank { creator.optString("username") },
                                        style = MaterialTheme.typography.titleSmall)
                                    Text("@${creator.optString("username")}" +
                                        if (creator.optBoolean("is_private")) " · Private" else "",
                                        style = MaterialTheme.typography.bodySmall)
                                    if (creator.optString("bio").isNotBlank()) {
                                        Text(creator.optString("bio"), maxLines = 1,
                                            style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(listOf("For You", "Following", "Shorts", "Reels", "Long videos", "LIVE")) { name ->
                    FilterChip(selected = feedMode == name, onClick = {
                        if (name == "LIVE") onNavigate("Live") else feedMode = name
                    }, label = { Text(name) })
                }
            }
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(listOf("All", "Comedy", "Music", "Gaming", "Tech", "Vlogs")) { name ->
                    FilterChip(selected = category == name, onClick = { category = name }, label = { Text(name) })
                }
            }
            when {
                loading -> Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) { CircularProgressIndicator() }
                error != null -> Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
                    Text(error.orEmpty(), style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { refresh++ }) { Text("Retry") }
                }
                visibleFeed.isEmpty() -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = androidx.compose.ui.Alignment.Center) {
                    Text("इस खोज में कोई वीडियो नहीं मिला।")
                }
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) { items(visibleFeed, key = { it.id }) { video -> NativeVideoCard(video, session) } }
            }
        }
    }
}

@Composable
private fun NativeVideoCard(video: NativeVideo, session: NativeSession?) {
    val context = LocalContext.current
    var liked by remember(video.id, session?.userId) { mutableStateOf(false) }
    var likeCount by remember(video.id) { mutableIntStateOf(video.likes) }
    var likeBusy by remember(video.id) { mutableStateOf(false) }
    var likeError by remember(video.id) { mutableStateOf<String?>(null) }
    var commentsOpen by remember(video.id) { mutableStateOf(false) }
    var commentRows by remember(video.id) { mutableStateOf<List<JSONObject>>(emptyList()) }
    var commentDraft by remember(video.id) { mutableStateOf("") }
    var commentBusy by remember(video.id) { mutableStateOf(false) }
    var commentError by remember(video.id) { mutableStateOf<String?>(null) }
    var currentUsername by remember(session?.userId) { mutableStateOf("creator") }
    val scope = rememberCoroutineScope()
    LaunchedEffect(session?.userId) {
        val current = session ?: return@LaunchedEffect
        try {
            val profile = NativeSupabase.get(
                "/rest/v1/creator_public_profiles?select=username&user_id=eq.${Uri.encode(current.userId)}&limit=1",
                current
            ).optJSONObject(0)
            currentUsername = profile?.optString("username")?.takeIf { it.isNotBlank() } ?: "creator"
        } catch (_: Exception) { }
    }
    LaunchedEffect(video.id, session?.userId) {
        if (session != null) {
            try {
                val rows = NativeSupabase.get(
                    "/rest/v1/likes?select=id&user_id=eq.${Uri.encode(session.userId)}&video_id=eq.${Uri.encode(video.id)}&limit=1",
                    session
                )
                liked = rows.length() > 0
            } catch (e: Exception) {
                likeError = e.message ?: "Like status नहीं मिल सका।"
            }
        }
    }
    val exoPlayer = remember(video.url) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(video.url)); prepare(); playWhenReady = false
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
                factory = { ctx -> PlayerView(ctx).apply {
                    player = exoPlayer; useController = true
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                } },
                update = { it.player = exoPlayer }
            )
            Column(Modifier.fillMaxWidth().padding(14.dp)) {
                Text("@${video.username.ifBlank { "creator" }}", fontWeight = FontWeight.Bold)
                if (video.caption.isNotBlank()) { Spacer(Modifier.height(4.dp)); Text(video.caption) }
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text("${video.category} · $likeCount likes · ${video.views} views",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(
                        enabled = session != null && !likeBusy,
                        onClick = {
                            val current = session ?: return@TextButton
                            likeBusy = true
                            likeError = null
                            scope.launch {
                                try {
                                    val user = Uri.encode(current.userId)
                                    val videoId = Uri.encode(video.id)
                                    if (liked) {
                                        NativeSupabase.delete(
                                            "/rest/v1/likes?user_id=eq.$user&video_id=eq.$videoId",
                                            current
                                        )
                                        liked = false
                                        likeCount = (likeCount - 1).coerceAtLeast(0)
                                    } else {
                                        NativeSupabase.post(
                                            "/rest/v1/likes",
                                            JSONObject().put("user_id", current.userId).put("video_id", video.id),
                                            current
                                        )
                                        liked = true
                                        likeCount += 1
                                    }
                                } catch (e: Exception) {
                                    likeError = e.message ?: "Like update नहीं हुआ।"
                                } finally { likeBusy = false }
                            }
                        }
                    ) { Text(if (likeBusy) "…" else if (liked) "♥ Liked" else "♡ Like") }
                }
                if (likeError != null) Text(likeError.orEmpty(), color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelSmall)
                TextButton(onClick = {
                    commentsOpen = true
                    commentError = null
                    scope.launch {
                        try {
                            val rows = NativeSupabase.get(
                                "/rest/v1/video_comments?select=id,user_id,username,comment,created_at&video_id=eq.${Uri.encode(video.id)}&order=created_at.desc&limit=100",
                                session
                            )
                            commentRows = (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }
                        } catch (e: Exception) {
                            commentError = e.message ?: "Comments लोड नहीं हुए।"
                        }
                    }
                }) { Text("Comments") }
            }
        }
    }
    if (commentsOpen) {
        AlertDialog(
            onDismissRequest = { if (!commentBusy) commentsOpen = false },
            title = { Text("Comments") },
            text = {
                Column(Modifier.fillMaxWidth()) {
                    LazyColumn(Modifier.heightIn(max = 300.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(commentRows) { row ->
                            Column(Modifier.fillMaxWidth()) {
                                Text("@${row.optString("username").ifBlank { "creator" }}",
                                    style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                                Text(row.optString("comment"))
                                Text(row.optString("created_at"), style = MaterialTheme.typography.labelSmall)
                            }
                            Divider()
                        }
                    }
                    if (commentRows.isEmpty() && commentError == null) {
                        Text("अभी कोई Comment नहीं है।")
                    }
                    if (commentError != null) Text(commentError.orEmpty(), color = MaterialTheme.colorScheme.error)
                    OutlinedTextField(
                        value = commentDraft,
                        onValueChange = { if (it.length <= 2000) commentDraft = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("अपना Comment लिखें") },
                        minLines = 2,
                        enabled = session != null && !commentBusy
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = session != null && commentDraft.isNotBlank() && !commentBusy,
                    onClick = {
                        val current = session ?: return@TextButton
                        val message = commentDraft.trim()
                        commentBusy = true
                        commentError = null
                        scope.launch {
                            try {
                                NativeSupabase.post(
                                    "/rest/v1/video_comments",
                                    JSONObject().put("video_id", video.id)
                                        .put("user_id", current.userId)
                                        .put("username", currentUsername)
                                        .put("comment", message),
                                    current
                                )
                                commentDraft = ""
                                val rows = NativeSupabase.get(
                                    "/rest/v1/video_comments?select=id,user_id,username,comment,created_at&video_id=eq.${Uri.encode(video.id)}&order=created_at.desc&limit=100",
                                    current
                                )
                                commentRows = (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }
                            } catch (e: Exception) {
                                commentError = e.message ?: "Comment भेजा नहीं गया।"
                            } finally { commentBusy = false }
                        }
                    }
                ) { Text(if (commentBusy) "Sending…" else "Send") }
            },
            dismissButton = {
                TextButton(onClick = { commentsOpen = false }, enabled = !commentBusy) { Text("Close") }
            }
        )
    }
}

private object NativeFeed {
    suspend fun load(category: String, videoType: String? = null): List<NativeVideo> = withContext(Dispatchers.IO) {
        val args = JSONObject().put("p_limit", 30).put("p_offset", 0)
            .put("p_video_type", videoType ?: JSONObject.NULL)
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
            NativeVideo(id, playback, row.optString("caption").takeUnless { it == "null" }.orEmpty(),
                row.optString("username").takeUnless { it == "null" }.orEmpty(),
                row.optString("category").takeUnless { it == "null" }.orEmpty(),
                row.optInt("likes_count"), row.optInt("views_count"),
                row.optString("video_type").ifBlank { "short" })
        }.toList()
    }

    suspend fun loadFollowing(session: NativeSession): List<NativeVideo> = withContext(Dispatchers.IO) {
        val followRows = NativeSupabase.get(
            "/rest/v1/followers?select=following_id&follower_id=eq." + Uri.encode(session.userId) + "&limit=500",
            session
        )
        val creatorIds = (0 until followRows.length()).mapNotNull {
            followRows.optJSONObject(it)?.optString("following_id")?.takeIf { id -> id.isNotBlank() }
        }.distinct()
        if (creatorIds.isEmpty()) return@withContext emptyList()
        val idsFilter = Uri.encode("(" + creatorIds.joinToString(",") + ")")
        val rows = NativeSupabase.get(
            "/rest/v1/videos?select=id,caption,username,category,likes_count,views_count,video_type,user_id&user_id=in." + idsFilter + "&visibility=eq.public&order=created_at.desc&limit=60",
            session
        )
        val sourceRows = (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }
        if (sourceRows.isEmpty()) return@withContext emptyList()
        val videoIds = JSONArray()
        sourceRows.forEach { videoIds.put(it.optString("id")) }
        val resolved = NativeSupabase.invokeFunction("video-url", JSONObject().put("video_ids", videoIds), session)
        if (!resolved.optBoolean("ok")) throw IllegalStateException("Following video playback URL resolve नहीं हुआ।")
        val urls = resolved.optJSONObject("urls") ?: JSONObject()
        sourceRows.mapNotNull { row ->
            val id = row.optString("id")
            val playback = urls.optString(id).takeIf { it.startsWith("https://") } ?: return@mapNotNull null
            NativeVideo(id, playback, row.optString("caption"), row.optString("username"),
                row.optString("category"), row.optInt("likes_count"), row.optInt("views_count"),
                row.optString("video_type").ifBlank { "short" })
        }
    }
    private fun post(endpoint: String, body: JSONObject): Any {
        val connection = URL(endpoint).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"; connection.connectTimeout = 15000; connection.readTimeout = 25000
            connection.doOutput = true
            connection.setRequestProperty("apikey", PUBLIC_API_KEY)
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) throw IllegalStateException("Server error $status: ${response.take(160)}")
            return if (response.trimStart().startsWith("[")) JSONArray(response) else JSONObject(response)
        } finally { connection.disconnect() }
    }
}
