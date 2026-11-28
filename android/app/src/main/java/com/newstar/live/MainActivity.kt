package com.newstar.live

import android.app.Activity
import android.app.DownloadManager
import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.widget.Toast
import android.os.Bundle
import android.os.Environment
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

private const val API_BASE = "https://uzsusgoetrkujmofxcmb.supabase.co"
private const val PUBLIC_API_KEY = "sb_publishable_Zz2hljevMsXBcK8c33UvFw_wuc2itVr"

data class NativeVideo(
    val id: String, val url: String, val caption: String, val username: String,
    val category: String, val likes: Int, val views: Int, val type: String,
    val userId: String = ""
)

class MainActivity : ComponentActivity() {
    private var pendingGamingSession: NativeSession? = null
    private var pendingGamingRoomId: String? = null
    private var pendingGamingWhip: String? = null
    private var activeGamingRoomId: String? = null
    private val activeGamingRoomState = mutableStateOf<String?>(null)
    private var activeGamingSession: NativeSession? = null
    private var gamingStatusReceiver: BroadcastReceiver? = null
    private var queuedGamingSession: NativeSession? = null
    private var queuedGamingTitle: String? = null
    private val oauthSession = mutableStateOf<NativeSession?>(null)
    private val notificationCreatorId = mutableStateOf<String?>(null)
    private val notificationRowId = mutableStateOf<String?>(null)
    private val notificationType = mutableStateOf<String?>(null)
    private val openGamingSignal = mutableIntStateOf(0)
    private val sharedVideoId = mutableStateOf<String?>(null)
    private val referralId = mutableStateOf<String?>(null)
    private var referralSyncInProgress = false

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
                                activeGamingRoomState.value = null
                                activeGamingSession = null
                            }
                        }
                    }
                }
            }
        }
        runCatching {
            ContextCompat.registerReceiver(
                this,
                requireNotNull(gamingStatusReceiver),
                IntentFilter(GamingStreamService.STATUS),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        }.onFailure { error ->
            android.util.Log.e("NewStarStartup", "Gaming status receiver registration failed", error)
        }

        // Deep-link parsing and encrypted referral storage must never prevent the
        // primary Compose activity from being created.
        runCatching { handleNotificationIntent(intent) }
            .onFailure { android.util.Log.e("NewStarStartup", "Notification intent ignored", it) }
        runCatching { handleOAuthCallback(intent) }
            .onFailure { android.util.Log.e("NewStarStartup", "OAuth callback ignored", it) }
        runCatching { handleSharedVideoIntent(intent) }
            .onFailure { android.util.Log.e("NewStarStartup", "Shared-video intent ignored", it) }
        runCatching { handleReferralIntent(intent) }
            .onFailure { android.util.Log.e("NewStarStartup", "Referral intent ignored", it) }

        setContent {
            NewStarNativeTheme {
                NativeApp(
                    googleSession = oauthSession.value,
                    notificationCreatorId = notificationCreatorId.value,
                    notificationRowId = notificationRowId.value,
                    notificationType = notificationType.value,
                    openGamingSignal = openGamingSignal.intValue,
                    onNotificationIntentHandled = {
                        notificationCreatorId.value = null
                        notificationRowId.value = null
                        notificationType.value = null
                    },
                    sharedVideoId = sharedVideoId.value,
                    onSharedVideoIntentHandled = { sharedVideoId.value = null },
                    referralId = referralId.value,
                    onGoogleSignIn = { NativeGoogleOAuth.launch(this) },
                    gamingRoomId = activeGamingRoomState.value,
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
        handleNotificationIntent(intent)
        handleOAuthCallback(intent)
        handleSharedVideoIntent(intent)
        handleReferralIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        NativeSessionStore.load(this)?.let(::syncPendingReferral)
    }

    private fun syncPendingReferral(session: NativeSession) {
        if (referralSyncInProgress) return
        val referrer = NativeSessionStore.pendingReferral(this) ?: return
        referralSyncInProgress = true
        lifecycleScope.launch {
            try {
                val nowSeconds = System.currentTimeMillis() / 1000L
                val activeSession = if (session.expiresAt <= nowSeconds + 30L) {
                    NativeSupabase.refresh(this@MainActivity, session)
                } else {
                    session
                }
                NativeSupabase.post(
                    "/rest/v1/rpc/record_referral_attribution",
                    JSONObject().put("p_referrer_id", referrer),
                    activeSession
                )
                NativeSessionStore.clearPendingReferral(this@MainActivity)
                referralId.value = null
            } catch (_: Exception) {
                // Keep the encrypted referral intent. onResume retries after connectivity returns.
            } finally {
                referralSyncInProgress = false
            }
        }
    }

    private fun handleReferralIntent(intent: Intent?) {
        val uri = intent?.data ?: return
        if (!uri.scheme.equals("https", ignoreCase = true) ||
            !uri.host.equals("new-star-delta.vercel.app", ignoreCase = true)) return
        val candidate = uri.getQueryParameter("ref") ?: return
        if (candidate.matches(Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))) {
            referralId.value = candidate
            NativeSessionStore.savePendingReferral(this, candidate)
        }
    }

    private fun handleSharedVideoIntent(intent: Intent?) {
        val uri = intent?.data ?: return
        val isHttpsLink = uri.scheme.equals("https", ignoreCase = true) &&
            uri.host.equals("new-star-delta.vercel.app", ignoreCase = true)
        val isAppLink = uri.scheme.equals("com.newstar.live", ignoreCase = true) &&
            uri.host.equals("video", ignoreCase = true)
        if (!isHttpsLink && !isAppLink) return
        val candidate = uri.getQueryParameter("video") ?: if (isAppLink) uri.lastPathSegment else null
        val safeId = candidate?.takeIf { it.length in 1..128 && it.matches(Regex("[A-Za-z0-9_-]+")) }
        if (safeId != null) sharedVideoId.value = safeId
    }

    private fun handleNotificationIntent(intent: Intent?) {
        if (intent?.action == NativeSystemNotifications.ACTION_OPEN_GAMING) {
            openGamingSignal.intValue++
            return
        }
        if (intent?.action != NativeSystemNotifications.ACTION_OPEN_CREATOR) return
        notificationCreatorId.value = intent.getStringExtra(NativeSystemNotifications.EXTRA_CREATOR_ID)
        notificationRowId.value = intent.getStringExtra(NativeSystemNotifications.EXTRA_NOTIFICATION_ID)
        notificationType.value = intent.getStringExtra(NativeSystemNotifications.EXTRA_NOTIFICATION_TYPE)
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
                val signedIn = NativeSupabase.exchangeCode(this@MainActivity, code, pending.second)
                syncPendingReferral(signedIn)
                oauthSession.value = signedIn
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
                    .put("seat_capacity", 4), session)
                if (!response.optBoolean("ok")) throw IllegalStateException(response.optString("error", "Gaming room तैयार नहीं हुआ।"))
                val roomId = response.getString("room_id")
                pendingGamingSession = session
                pendingGamingRoomId = roomId
                pendingGamingWhip = response.getString("whip_url")
                activeGamingRoomId = roomId
                activeGamingRoomState.value = roomId
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
            activeGamingRoomState.value = null
            activeGamingSession = null
            } catch (_: Exception) { }
        }
    }
}

@Composable
private fun NativeApp(
    googleSession: NativeSession?,
    notificationCreatorId: String?,
    notificationRowId: String?,
    notificationType: String?,
    openGamingSignal: Int,
    onNotificationIntentHandled: () -> Unit,
    sharedVideoId: String?,
    onSharedVideoIntentHandled: () -> Unit,
    referralId: String?,
    onGoogleSignIn: () -> Unit,
    gamingRoomId: String?,
    onStartGaming: (NativeSession, String) -> Unit,
    onStopGaming: (NativeSession) -> Unit
) {
    val context = LocalContext.current
    var session by remember { mutableStateOf<NativeSession?>(null) }
    var checkingSession by remember { mutableStateOf(true) }
    var page by remember { mutableStateOf("Home") }
    var selectedCreatorId by remember { mutableStateOf<String?>(null) }
    var selectedSharedVideoId by remember { mutableStateOf<String?>(null) }
    var selectedOfflineVideo by remember { mutableStateOf<NativeVideo?>(null) }
    var inboxTargetId by remember { mutableStateOf<String?>(null) }
    var inboxTargetRequestId by remember { mutableIntStateOf(0) }
    var aiInitialPrompt by remember { mutableStateOf("") }
    var lastForegroundNotificationId by remember(session?.userId) { mutableStateOf<String?>(null) }
    var notificationBaselineReady by remember(session?.userId) { mutableStateOf(false) }
    var notificationCheckInProgress by remember(session?.userId) { mutableStateOf(false) }
    var notificationRefreshQueued by remember(session?.userId) { mutableStateOf(false) }
    var notificationRefreshCounter by remember(session?.userId) { mutableIntStateOf(0) }
    var notificationRealtimeStatus by remember(session?.userId) { mutableStateOf("Realtime connecting…") }
    var liveInviteAlertRefreshBusy by remember(session?.userId) { mutableStateOf(false) }
    var liveInviteAlertRefreshQueued by remember(session?.userId) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    DisposableEffect(context) {
        runCatching { NativeSystemNotifications.createChannel(context) }
            .onFailure { android.util.Log.e("NewStarStartup", "Notification channel setup failed", it) }
        onDispose { }
    }

    LaunchedEffect(session?.userId) {
        val current = session
        try {
            if (current == null) {
                NativeBackgroundNotificationWork.cancel(context)
            } else {
                NativeBackgroundNotificationWork.schedule(context, current.userId)
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            android.util.Log.e("NewStarStartup", "Background notification scheduling failed", error)
        }
    }

    LaunchedEffect(session?.userId) {
        val current = session ?: return@LaunchedEffect
        notificationBaselineReady = false
        lastForegroundNotificationId = null
        try {
            // Restore the latest account notification switches before subscribing.
            // If offline, the last saved local preferences continue to apply.
            runCatching { NativeNotificationPreferences.synchronize(context, current) }
            val latest = NativeSupabase.get(
                "/rest/v1/notifications?select=id&user_id=eq.${Uri.encode(current.userId)}&order=created_at.desc&limit=1",
                current
            )
            lastForegroundNotificationId = latest.optJSONObject(0)?.optString("id")
            notificationBaselineReady = true
        } catch (_: Exception) {
            notificationBaselineReady = false
        }
    }

    DisposableEffect(session?.userId, session?.accessToken) {
        val current = session
        if (current == null) {
            onDispose { }
        } else {
            val observer = NativeRealtimeMessages(
                accessToken = current.accessToken,
                userId = current.userId,
                onChange = {
                    scope.launch {
                        if (!notificationBaselineReady) return@launch
                        if (notificationCheckInProgress) {
                            notificationRefreshQueued = true
                            return@launch
                        }
                        notificationCheckInProgress = true
                        try {
                            do {
                                notificationRefreshQueued = false
                                val rows = NativeSupabase.get(
                                    "/rest/v1/notifications?select=id,user_id,actor_id,type,message,is_read,created_at&user_id=eq.${Uri.encode(current.userId)}&order=created_at.desc&limit=100",
                                    current
                                )
                                val newestFirst = (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }
                                val newNotifications = NativeNotificationDelta.newlyInserted(
                                    newestFirst,
                                    lastForegroundNotificationId.orEmpty()
                                ) { it.optString("id") }
                                newestFirst.firstOrNull()?.optString("id")
                                    ?.takeIf { it.isNotBlank() }
                                    ?.let { lastForegroundNotificationId = it }

                                if (newNotifications.isNotEmpty()) {
                                    notificationRefreshCounter += newNotifications.size
                                    val unread = newNotifications
                                        .filterNot { it.optBoolean("is_read", false) }
                                        .filter { NativeNotificationPreferences.isEnabled(context, it) }
                                    if (unread.isNotEmpty() && NativeSystemNotifications.hasPermission(context)) {
                                        val actorIds = unread.map { it.optString("actor_id") }
                                            .filter { it.isNotBlank() && it != current.userId }
                                            .distinct()
                                        val profilesById = if (actorIds.isEmpty()) {
                                            emptyMap()
                                        } else {
                                            runCatching {
                                                val ids = Uri.encode("(${actorIds.joinToString(",")})")
                                                val profiles = NativeSupabase.get(
                                                    "/rest/v1/creator_public_profiles?select=user_id,username,full_name&user_id=in.$ids",
                                                    current
                                                )
                                                (0 until profiles.length()).mapNotNull { profiles.optJSONObject(it) }
                                                    .associateBy { it.optString("user_id") }
                                            }.getOrDefault(emptyMap())
                                        }
                                        unread.forEach { notification ->
                                            val actorId = notification.optString("actor_id")
                                            val profile = profilesById[actorId]
                                            val actorName = profile?.optString("full_name").orEmpty()
                                                .ifBlank { profile?.optString("username").orEmpty() }
                                            NativeSystemNotifications.show(context, notification, actorName)
                                        }
                                    }
                                }
                            } while (notificationRefreshQueued)
                        } catch (_: Exception) {
                            notificationRealtimeStatus = "Realtime notification refresh failed"
                        } finally {
                            notificationCheckInProgress = false
                        }
                    }
                },
                onStatus = { status -> scope.launch { notificationRealtimeStatus = status } },
                includeLiveInvites = true,
                onLiveInvite = {
                    scope.launch {
                        if (liveInviteAlertRefreshBusy) {
                            liveInviteAlertRefreshQueued = true
                            return@launch
                        }
                        liveInviteAlertRefreshBusy = true
                        try {
                            do {
                                liveInviteAlertRefreshQueued = false
                                val invitations = NativeLiveRepository.listInvitations(current)
                                val preferences = context.getSharedPreferences(
                                    "newstar_live_invite_alerts",
                                    Context.MODE_PRIVATE
                                )
                                val key = "seen_${current.userId}"
                                val seen = NativeLiveInviteAlertPolicy.decode(preferences.getString(key, null))
                                val freshIds = NativeLiveInviteAlertPolicy.unseenIds(
                                    invitations.map { it.optString("id") },
                                    seen
                                )
                                invitations.filter { it.optString("id") in freshIds }
                                    .forEach { NativeSystemNotifications.showLiveInvite(context, it) }
                                val updated = NativeLiveInviteAlertPolicy.mergeSeenIds(
                                    seen,
                                    invitations.map { it.optString("id") }
                                )
                                preferences.edit()
                                    .putString(key, NativeLiveInviteAlertPolicy.encode(updated))
                                    .apply()
                            } while (liveInviteAlertRefreshQueued)
                        } catch (error: Exception) {
                            notificationRealtimeStatus = error.message
                                ?: "LIVE invitation alert refresh failed"
                        } finally {
                            liveInviteAlertRefreshBusy = false
                        }
                    }
                }
            )
            observer.connect()
            onDispose { observer.close() }
        }
    }

    LaunchedEffect(Unit) {
        try {
            session = NativeSupabase.validSession(context)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            android.util.Log.e("NewStarStartup", "Saved session restore failed; continuing signed out", error)
            session = null
        } finally {
            checkingSession = false
        }
    }
    LaunchedEffect(googleSession) {
        if (googleSession != null) {
            session = googleSession
            if (page != "Shared Video") page = "Home"
        }
    }
    LaunchedEffect(openGamingSignal, session?.userId) {
        if (openGamingSignal > 0 && session != null) page = "Gaming"
    }
    LaunchedEffect(sharedVideoId) {
        val incomingId = sharedVideoId?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        selectedSharedVideoId = incomingId
        page = "Shared Video"
        onSharedVideoIntentHandled()
    }
    LaunchedEffect(notificationCreatorId, notificationRowId, notificationType, session?.userId) {
        val current = session
        val creatorId = notificationCreatorId
        val rowId = notificationRowId
        val type = notificationType
        if (current != null && (!creatorId.isNullOrBlank() || !rowId.isNullOrBlank())) {
            if (!rowId.isNullOrBlank()) {
                try {
                    NativeSupabase.patch(
                        "/rest/v1/notifications?id=eq.${Uri.encode(rowId)}&user_id=eq.${Uri.encode(current.userId)}",
                        JSONObject().put("is_read", true),
                        current
                    )
                    notificationRefreshCounter++
                } catch (_: Exception) {
                }
            }
            val category = NativeNotificationPreferences.category(type, null)
            if (category == NativeNotificationCategory.MESSAGE && !creatorId.isNullOrBlank()) {
                inboxTargetId = creatorId
                inboxTargetRequestId++
                page = "Inbox"
            } else if (!creatorId.isNullOrBlank()) {
                selectedCreatorId = creatorId
                page = "Creator"
            } else {
                page = "Notifications"
            }
            onNotificationIntentHandled()
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

    if (page == "Offline Playback" && selectedOfflineVideo != null) {
        Column(Modifier.fillMaxSize()) {
            TextButton(onClick = { selectedOfflineVideo = null; page = "Offline Library" }) {
                Text("← Offline Library")
            }
            NativeVideoCard(
                video = selectedOfflineVideo!!,
                session = session,
                isActive = true,
                immersive = true
            )
        }
    } else if (page == "Shared Video" && !selectedSharedVideoId.isNullOrBlank()) {
        NativeSharedVideoScreen(
            videoId = selectedSharedVideoId!!,
            session = session,
            onBack = { selectedSharedVideoId = null; page = "Home" },
            onOpenCreator = { creatorId -> selectedCreatorId = creatorId; page = "Creator" }
        )
    } else if (page == "Home") {
        NativeHome(
            session = session,
            onOpenCreator = { selectedCreatorId = it; page = "Creator" },
            onAskAi = { prompt -> aiInitialPrompt = prompt; page = "Creator AI" },
            onNavigate = { page = it }
        )
    } else if (session == null) {
        NativeAuthScreen(onSignedIn = { session = it; page = "Home" }, onGoogleSignIn = onGoogleSignIn, referrerId = referralId)
    } else {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.Start) {
                TextButton(onClick = { page = "Home" }) { Text("← Home") }
                Spacer(Modifier.weight(1f))
                Text(page, modifier = Modifier.padding(12.dp), fontWeight = FontWeight.Bold)
            }
            when (page) {
                "Settings" -> NativeSettingsScreen(
                    session = session!!,
                    onOpenDeveloperReports = { page = "Developer Reports" },
                    onOpenHelpCenter = { page = "Help Center" },
                    onLogout = {
                        val current = session
                        if (current != null) scope.launch {
                            try { NativeSupabase.deleteSession(context, current) }
                            finally { session = null; page = "Home" }
                        }
                    },
                    onLogoutAll = {
                        val current = session
                        if (current != null) scope.launch {
                            try { NativeSupabase.deleteAllSessions(context, current) }
                            finally { session = null; page = "Home" }
                        }
                    }
                )
                "Developer Reports" -> NativeDeveloperReportsScreen(
                    session = session!!,
                    onBack = { page = "Settings" }
                )
                "Upload" -> NativeUploadScreen(session!!)
                "Inbox" -> NativeInboxScreen(session!!, inboxTargetId, inboxTargetRequestId) { creatorId -> selectedCreatorId = creatorId; page = "Creator" }
                "Notifications" -> NativeNotificationsScreen(
                    session!!,
                    notificationRefreshCounter,
                    notificationRealtimeStatus
                ) { notification ->
                    val creatorId = notification.optString("actor_id")
                    val category = NativeNotificationPreferences.category(
                        notification.optString("type"),
                        notification.optString("message")
                    )
                    if (category == NativeNotificationCategory.MESSAGE && creatorId.isNotBlank()) {
                        inboxTargetId = creatorId
                        inboxTargetRequestId++
                        page = "Inbox"
                    } else if (creatorId.isNotBlank() && creatorId != session!!.userId) {
                        selectedCreatorId = creatorId
                        page = "Creator"
                    }
                }
                "Wallet" -> NativeWalletScreen(session!!)
                "Offline Library" -> NativeOfflineLibraryScreen { videoId, mediaUri ->
                    selectedOfflineVideo = NativeVideo(
                        id = videoId,
                        url = mediaUri,
                        caption = "Offline video",
                        username = "Saved video",
                        category = "",
                        likes = 0,
                        views = 0,
                        type = "long"
                    )
                    page = "Offline Playback"
                }
                "Monetization" -> NativeMonetizationScreen(
                    session = session!!,
                    onOpenWallet = { page = "Wallet" },
                    onOpenFanSupport = { page = "Fan Support" }
                )
                "Fan Support" -> NativeFanSupportScreen(session!!) { creatorId -> selectedCreatorId = creatorId; page = "Creator" }
                "Campaigns" -> NativeCampaignScreen(session!!)
                "About" -> NativeInfoScreen("About")
                "Contact" -> NativeInfoScreen("Contact")
                "Terms" -> NativeInfoScreen("Terms")
                "Privacy" -> NativeInfoScreen("Privacy")
                "Creator AI" -> NativeCreatorAiScreen(session!!, initialPrompt = aiInitialPrompt)
                "Help Center" -> NativeHelpCenterScreen(session!!)
                "Live" -> NativeLiveDiscoveryScreen(session!!)
                "Gaming" -> NativeGamingScreen(
                    session = session!!,
                    roomId = gamingRoomId,
                    onStart = { title -> onStartGaming(session!!, title) },
                    onStop = { onStopGaming(session!!) }
                )
                "Creator" -> selectedCreatorId?.let { creatorId ->
                    NativePublicCreatorScreen(creatorId, session!!) {
                        inboxTargetId = creatorId
                        page = "Inbox"
                    }
                }
                "Profile" -> NativeProfileScreen(session!!) {
                    val current = session
                    if (current != null) scope.launch {
                        try { NativeSupabase.deleteSession(context, current) }
                        finally { session = null; page = "Home" }
                    }
                }
                else -> NativeHome(
                    session = session,
                    onOpenCreator = { selectedCreatorId = it; page = "Creator" },
                    onAskAi = { prompt -> aiInitialPrompt = prompt; page = "Creator AI" },
                    onNavigate = { page = it }
                )
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
    var followingIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var followRequestPendingIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var followBusyIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(session.userId) {
        try {
            val followed = NativeSupabase.get(
                "/rest/v1/followers?select=following_id&follower_id=eq.${Uri.encode(session.userId)}&limit=1000",
                session
            )
            followingIds = (0 until followed.length()).mapNotNull { index ->
                followed.optJSONObject(index)?.optString("following_id")?.takeIf { it.isNotBlank() }
            }.toSet()
        } catch (_: Exception) { }
    }

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
                    "/rest/v1/creator_public_profiles?select=user_id,username,full_name,avatar_url,is_private&user_id=in.$ids",
                    session
                )
                (0 until profiles.length()).mapNotNull { profiles.optJSONObject(it) }
                    .associateBy { it.optString("user_id") }
            }
            val privateCreatorIds = creatorProfiles.values
                .filter { it.optBoolean("is_private") }
                .map { it.optString("user_id") }
                .filter { it.isNotBlank() && it !in followingIds }
            followRequestPendingIds = if (privateCreatorIds.isEmpty()) emptySet() else {
                val targetIds = Uri.encode("(${privateCreatorIds.joinToString(",")})")
                val pending = NativeSupabase.get(
                    "/rest/v1/follow_requests?select=target_user_id&requester_id=eq.${Uri.encode(session.userId)}&status=eq.pending&target_user_id=in.$targetIds",
                    session
                )
                (0 until pending.length()).mapNotNull { pending.optJSONObject(it)?.optString("target_user_id") }
                    .filter { it.isNotBlank() }.toSet()
            }
        } catch (e: Exception) {
            error = e.message ?: "Live rooms लोड नहीं हुए।"
        } finally { loading = false }
    }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        NativeLiveHostControls(session)
        NativeLiveInvitations(session)
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
                        val creatorId = room.optString("creator_id")
                        if (creatorId.isNotBlank() && creatorId != session.userId) {
                            val isFollowing = creatorId in followingIds
                            OutlinedButton(
                                enabled = creatorId !in followBusyIds,
                                onClick = {
                                    followBusyIds = followBusyIds + creatorId
                                    val wasFollowing = isFollowing
                                    scope.launch {
                                        try {
                                            if (wasFollowing) {
                                                NativeSupabase.delete(
                                                    "/rest/v1/followers?follower_id=eq.${Uri.encode(session.userId)}&following_id=eq.${Uri.encode(creatorId)}",
                                                    session
                                                )
                                                followingIds = followingIds - creatorId
                                            } else {
                                                val creator = creatorProfiles[creatorId]
                                                if (creator?.optBoolean("is_private") == true) {
                                                    if (creatorId !in followRequestPendingIds) {
                                                        NativeSupabase.post(
                                                            "/rest/v1/follow_requests",
                                                            JSONObject().put("requester_id", session.userId)
                                                                .put("target_user_id", creatorId)
                                                                .put("status", "pending"),
                                                            session
                                                        )
                                                        followRequestPendingIds = followRequestPendingIds + creatorId
                                                    }
                                                } else {
                                                    NativeSupabase.post(
                                                        "/rest/v1/followers",
                                                        JSONObject().put("follower_id", session.userId)
                                                            .put("following_id", creatorId),
                                                        session
                                                    )
                                                    followingIds = followingIds + creatorId
                                                }
                                            }
                                        } catch (e: Exception) {
                                            error = e.message ?: "Follow update नहीं हुआ।"
                                        } finally {
                                            followBusyIds = followBusyIds - creatorId
                                        }
                                    }
                                }
                            ) {
                                Text(
                                    when {
                                        creatorId in followBusyIds -> "Please wait…"
                                        isFollowing -> "Following"
                                        creatorProfiles[creatorId]?.optBoolean("is_private") == true &&
                                            creatorId in followRequestPendingIds -> "Requested"
                                        creatorProfiles[creatorId]?.optBoolean("is_private") == true -> "Request Follow"
                                        else -> "Follow"
                                    }
                                )
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
                        NativeLiveSfuViewer(session, room.optString("id"))
                        NativeLiveGuestControls(session, room.optString("id"), room.optString("creator_id") == session.userId)
                        NativeLiveRoomChat(session, room.optString("id"))
                    }
                }
            }
        }
    }
}

@Composable
private fun NativeLiveInvitations(session: NativeSession) {
    var invitations by remember(session.userId) { mutableStateOf<List<JSONObject>>(emptyList()) }
    var notice by remember(session.userId) { mutableStateOf("") }
    var busyId by remember(session.userId) { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    var realtimeStatus by remember(session.userId) { mutableStateOf("LIVE invitation Realtime connecting…") }
    var refreshBusy by remember(session.userId) { mutableStateOf(false) }

    suspend fun refreshInvites() {
        invitations = NativeLiveRepository.listInvitations(session)
    }

    LaunchedEffect(session.userId) {
        refreshBusy = true
        try {
            refreshInvites()
            notice = ""
        } catch (e: Exception) {
            notice = e.message ?: "Invitations load नहीं हुए।"
        } finally {
            refreshBusy = false
        }
    }

    DisposableEffect(session.userId, session.accessToken) {
        val observer = NativeRealtimeMessages(
            accessToken = session.accessToken,
            userId = session.userId,
            includeLiveInvites = true,
            onChange = {
                scope.launch {
                    try {
                        refreshInvites()
                        notice = ""
                    } catch (e: Exception) {
                        notice = e.message ?: "LIVE invitations refresh नहीं हुईं।"
                    }
                }
            },
            onStatus = { status -> realtimeStatus = status }
        )
        observer.connect()
        onDispose { observer.close() }
    }

    LaunchedEffect(session.userId) {
        while (true) {
            delay(30_000)
            try {
                refreshInvites()
                if (notice.isNotBlank()) notice = ""
            } catch (e: Exception) {
                notice = e.message ?: "Invitations load नहीं हुए।"
            }
        }
    }

    Card(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text("LIVE Invitations", style = MaterialTheme.typography.titleMedium)
                    TextButton(
                        enabled = !refreshBusy,
                        onClick = {
                            refreshBusy = true
                            scope.launch {
                                try {
                                    refreshInvites()
                                    notice = ""
                                } catch (e: Exception) {
                                    notice = e.message ?: "Invitations refresh नहीं हुईं।"
                                } finally {
                                    refreshBusy = false
                                }
                            }
                        }
                    ) { Text(if (refreshBusy) "Loading…" else "Refresh") }
                }
                Text(realtimeStatus, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                invitations.forEach { invite ->
                    val id = invite.optString("id")
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        AsyncImage(
                            model = invite.optString("inviter_avatar_url").takeIf { it.startsWith("https://") },
                            contentDescription = "Inviting creator avatar",
                            modifier = Modifier.size(42.dp).clip(CircleShape)
                        )
                        Column(Modifier.weight(1f)) {
                            Text(
                                invite.optString("inviter_name").ifBlank { "New Star Creator" },
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            val inviterUsername = invite.optString("inviter_username")
                            if (inviterUsername.isNotBlank()) {
                                Text("@$inviterUsername", style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(
                                "Invited you to ${invite.optString("room_title").ifBlank { "New Star Live" }}",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(enabled = busyId.isBlank(), onClick = {
                            busyId = id
                            scope.launch {
                                try {
                                    NativeLiveRepository.respondToInvitation(id, accept = true, session = session)
                                    notice = "Invitation accepted. Host से seat admit करवाएँ।"
                                    refreshInvites()
                                } catch (e: Exception) { notice = e.message ?: "Accept failed" }
                                finally { busyId = "" }
                            }
                        }) { Text(if (busyId == id) "Please wait…" else "Accept") }
                        OutlinedButton(enabled = busyId.isBlank(), onClick = {
                            busyId = id
                            scope.launch {
                                try {
                                    NativeLiveRepository.respondToInvitation(id, accept = false, session = session)
                                    notice = "Invitation declined."
                                    refreshInvites()
                                } catch (e: Exception) { notice = e.message ?: "Decline failed" }
                                finally { busyId = "" }
                            }
                        }) { Text("Decline") }
                    }
                }
                if (invitations.isEmpty() && notice.isBlank()) {
                    Text("अभी कोई pending LIVE invitation नहीं है।",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (notice.isNotBlank()) Text(notice, style = MaterialTheme.typography.bodySmall)
            }
        }
}

@Composable
private fun NativeLiveGuestControls(session: NativeSession, roomId: String, isHost: Boolean) {
    var participants by remember(roomId) { mutableStateOf<List<JSONObject>>(emptyList()) }
    var creatorQuery by remember(roomId) { mutableStateOf("") }
    var creatorResults by remember(roomId) { mutableStateOf<List<NativeLiveCreator>>(emptyList()) }
    var searchingCreators by remember(roomId) { mutableStateOf(false) }
    var busy by remember(roomId) { mutableStateOf(false) }
    var notice by remember(roomId) { mutableStateOf("") }
    var guestPublisher by remember(roomId) { mutableStateOf<NativeLivePublisher?>(null) }
    var guestMicEnabled by remember(roomId) { mutableStateOf(false) }
    var guestCameraEnabled by remember(roomId) { mutableStateOf(true) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentGuestPublisher by rememberUpdatedState(guestPublisher)
    val currentGuestParticipant = participants.firstOrNull { it.optString("user_id") == session.userId }
    val currentGuestMicAllowed = currentGuestParticipant?.optBoolean("microphone_allowed", true) == true
    val guestPermissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val cameraGranted = permissions[android.Manifest.permission.CAMERA] == true ||
            androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        val micGranted = currentGuestMicAllowed && (
            permissions[android.Manifest.permission.RECORD_AUDIO] == true ||
                androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        )
        if (!cameraGranted) {
            notice = "Guest camera शुरू करने के लिए Android Camera permission आवश्यक है।"
        } else {
            scope.launch {
                busy = true
                var created: NativeLivePublisher? = null
                try {
                    val active = NativeSfuPublisher.start(context, roomId, session, micGranted)
                    created = active
                    guestPublisher = active
                    guestMicEnabled = micGranted
                    guestCameraEnabled = true
                    notice = if (micGranted) "आपका camera और microphone LIVE room में publish हो रहा है।"
                        else "आपका camera LIVE room में publish हो रहा है। Microphone permission उपलब्ध नहीं है।"
                } catch (e: Exception) {
                    runCatching { created?.stop() }
                    notice = e.message ?: "Guest media publishing शुरू नहीं हुआ।"
                } finally {
                    busy = false
                }
            }
        }
    }

    androidx.compose.runtime.LaunchedEffect(roomId, guestPublisher) {
        if (guestPublisher != null) {
            while (guestPublisher != null) {
                delay(3_000)
                try {
                    val fresh = NativeLiveRepository.listParticipants(roomId, session)
                    val mineNow = fresh.firstOrNull { it.optString("user_id") == session.userId }
                    if (mineNow == null || mineNow.optString("status") != "admitted" ||
                        !mineNow.optBoolean("camera_allowed", true)) {
                        guestPublisher?.stop()
                        guestPublisher = null
                        notice = "Host ने guest camera permission हटाई या seat बंद हुई; publishing रोक दी गई।"
                        break
                    }
                    if (!mineNow.optBoolean("microphone_allowed", true)) {
                        guestPublisher?.setMicrophoneEnabled(false)
                        guestMicEnabled = false
                    }
                } catch (_: Exception) {
                    // Keep an established media session during a transient status request failure.
                }
            }
        }
    }

    androidx.compose.runtime.DisposableEffect(roomId) {
        onDispose {
            val active = currentGuestPublisher
            if (active != null) {
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO).launch {
                    runCatching { active.stop() }
                }
            }
        }
    }

    suspend fun reloadParticipants() {
        val current = NativeLiveRepository.listParticipants(roomId, session)
        val moderator = current.any {
            it.optString("user_id") == session.userId &&
                it.optString("role") == "moderator" && it.optString("status") == "admitted"
        }
        participants = if (isHost || moderator) {
            val managed = NativeLiveRepository.listMembers(roomId, session)
            val rows = managed.optJSONArray("members") ?: JSONArray()
            (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }
        } else current
    }

    LaunchedEffect(roomId) {
        while (true) {
            try { reloadParticipants() } catch (e: Exception) { notice = e.message ?: "Guest list लोड नहीं हुई।" }
            delay(5_000)
        }
    }

    val isModerator = participants.any {
        it.optString("user_id") == session.userId &&
            it.optString("role") == "moderator" && it.optString("status") == "admitted"
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Guest seats", style = MaterialTheme.typography.titleMedium)
        if (!isHost && !isModerator) {
            OutlinedButton(enabled = !busy, onClick = {
                busy = true
                scope.launch {
                    try {
                        val response = NativeLiveRepository.requestSeat(roomId, session)
                        val requested = response.optJSONObject("participant")
                        if (requested != null) participants = listOf(requested)
                        notice = "Seat request भेज दी गई। Host की मंज़ूरी का इंतज़ार करें।"
                        reloadParticipants()
                    } catch (e: Exception) { notice = e.message ?: "Request failed" }
                    finally { busy = false }
                }
            }) { Text("Request guest seat") }
            val mine = participants.firstOrNull { it.optString("user_id") == session.userId }
            val admitted = mine?.optString("status") == "admitted"
            val cameraAllowed = mine?.optBoolean("camera_allowed", true) == true
            val microphoneAllowed = mine?.optBoolean("microphone_allowed", true) == true
            when {
                mine?.optString("status") == "requested" -> Text(
                    "Seat request भेज दी गई है। Host के admit करने के बाद camera विकल्प खुलेगा।",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                admitted && !cameraAllowed -> Text(
                    "आप admitted हैं, लेकिन Host ने camera permission अभी नहीं दी है। Host से camera अनुमति माँगें।",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                admitted && guestPublisher == null -> OutlinedButton(
                    enabled = !busy && cameraAllowed,
                    onClick = {
                        val permissions = mutableListOf(android.Manifest.permission.CAMERA)
                        if (microphoneAllowed) permissions.add(android.Manifest.permission.RECORD_AUDIO)
                        guestPermissionLauncher.launch(permissions.toTypedArray())
                    }
                ) { Text("Start guest camera") }
            }
            guestPublisher?.let { active ->
                NativeLiveCameraPreview(active, Modifier.fillMaxWidth().height(190.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        guestMicEnabled = !guestMicEnabled
                        active.setMicrophoneEnabled(guestMicEnabled && microphoneAllowed)
                    }, enabled = microphoneAllowed) {
                        Text(if (guestMicEnabled) "Mic ON" else "Mic OFF")
                    }
                    OutlinedButton(onClick = {
                        guestCameraEnabled = !guestCameraEnabled
                        active.setCameraEnabled(guestCameraEnabled)
                    }) { Text(if (guestCameraEnabled) "Camera ON" else "Camera OFF") }
                }
                Text("आपका native WebRTC guest media इस LIVE room में भेजा जा रहा है।",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary)
            }
            if (admitted) {
                OutlinedButton(enabled = !busy, onClick = {
                    busy = true
                    scope.launch {
                        try {
                            guestPublisher?.stop()
                            guestPublisher = null
                            NativeLiveRepository.leaveSeat(roomId, session)
                            notice = "आप LIVE seat से निकल गए और camera/microphone publishing बंद कर दी गई।"
                            reloadParticipants()
                        } catch (e: Exception) { notice = e.message ?: "Leave failed" }
                        finally { busy = false }
                    }
                }) { Text("Leave seat") }
            }
        } else {
            if (isHost) {
                OutlinedTextField(
                    value = creatorQuery,
                onValueChange = { creatorQuery = it.take(80) },
                label = { Text("Creator name या username खोजें") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedButton(
                enabled = !busy && !searchingCreators && creatorQuery.trim().length >= 2,
                onClick = {
                    searchingCreators = true
                    notice = ""
                    scope.launch {
                        try {
                            creatorResults = NativeLiveRepository.searchCreators(creatorQuery, session.userId, session)
                            if (creatorResults.isEmpty()) notice = "इस नाम से कोई creator नहीं मिला।"
                        } catch (e: Exception) {
                            notice = e.message ?: "Creator search नहीं हो पाया।"
                            creatorResults = emptyList()
                        } finally { searchingCreators = false }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                if (searchingCreators) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                else Text("Search creators")
            }
            creatorResults.forEach { creator ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    AsyncImage(
                        model = creator.avatarUrl.takeIf { it.isNotBlank() },
                        contentDescription = "Creator avatar",
                        modifier = Modifier.size(40.dp).clip(CircleShape)
                    )
                    Column(Modifier.weight(1f)) {
                        Text(creator.displayName, style = MaterialTheme.typography.bodyMedium)
                        if (creator.username.isNotBlank()) {
                            Text("@${creator.username}", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    TextButton(enabled = !busy, onClick = {
                        busy = true
                        scope.launch {
                            try {
                                NativeLiveRepository.createInvite(roomId, creator.userId, session)
                                notice = "${creator.displayName} को LIVE invitation भेज दिया गया।"
                                creatorResults = creatorResults.filterNot { it.userId == creator.userId }
                            } catch (e: Exception) {
                                notice = e.message ?: "Invitation नहीं भेजा गया।"
                            } finally { busy = false }
                        }
                    }) { Text("Invite") }
                }
            }
            }
            participants.forEach { person ->
                val status = person.optString("status")
                val personId = person.optString("user_id")
                val personRole = person.optString("role")
                val cameraAllowed = person.optBoolean("camera_allowed", true)
                val microphoneAllowed = person.optBoolean("microphone_allowed", true)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text(
                        "${person.optString("display_name")} · $personRole · $status · seat ${person.optString("seat_number", "-")}",
                        modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall
                    )
                    if (status == "requested" && isHost) {
                        TextButton(enabled = !busy, onClick = {
                            busy = true
                            scope.launch {
                                try {
                                    NativeLiveRepository.admitGuest(roomId, person.optString("id"), session)
                                    notice = "Guest seat admit हो गई।"
                                    reloadParticipants()
                                } catch (e: Exception) { notice = e.message ?: "Admit failed" }
                                finally { busy = false }
                            }
                        }) { Text("Admit") }
                    }
                    if (status == "admitted" && personRole != "host" &&
                        (isHost || (isModerator && personRole == "guest"))) {
                        TextButton(enabled = !busy, onClick = {
                            busy = true
                            scope.launch {
                                try {
                                    NativeLiveRepository.setMemberPermissions(
                                        roomId, personId, cameraAllowed = !cameraAllowed, session = session
                                    )
                                    notice = if (cameraAllowed) "Camera permission बंद की गई।" else "Camera permission दी गई।"
                                    reloadParticipants()
                                } catch (e: Exception) { notice = e.message ?: "Camera permission update failed" }
                                finally { busy = false }
                            }
                        }) { Text(if (cameraAllowed) "Block camera" else "Allow camera") }
                        TextButton(enabled = !busy, onClick = {
                            busy = true
                            scope.launch {
                                try {
                                    NativeLiveRepository.setMemberPermissions(
                                        roomId, personId, microphoneAllowed = !microphoneAllowed, session = session
                                    )
                                    notice = if (microphoneAllowed) "Mic permission बंद की गई।" else "Mic permission दी गई।"
                                    reloadParticipants()
                                } catch (e: Exception) { notice = e.message ?: "Mic permission update failed" }
                                finally { busy = false }
                            }
                        }) { Text(if (microphoneAllowed) "Block mic" else "Allow mic") }
                        if (isHost) {
                            TextButton(enabled = !busy, onClick = {
                                busy = true
                                scope.launch {
                                    try {
                                        NativeLiveRepository.setModerator(roomId, personId, personRole != "moderator", session)
                                        notice = if (personRole == "moderator") "Moderator role हटाई गई।" else "Moderator बनाया गया।"
                                        reloadParticipants()
                                    } catch (e: Exception) { notice = e.message ?: "Moderator role update failed" }
                                    finally { busy = false }
                                }
                            }) { Text(if (personRole == "moderator") "Remove moderator" else "Make moderator") }
                        }
                        TextButton(enabled = !busy, onClick = {
                            busy = true
                            scope.launch {
                                try {
                                    NativeLiveRepository.removeMember(roomId, personId, session)
                                    notice = "LIVE member हटाया गया।"
                                    reloadParticipants()
                                } catch (e: Exception) { notice = e.message ?: "Remove failed" }
                                finally { busy = false }
                            }
                        }) { Text("Remove") }
                    }
                    if (status == "requested" && (isHost || isModerator)) {
                        TextButton(enabled = !busy, onClick = {
                            busy = true
                            scope.launch {
                                try {
                                    NativeLiveRepository.removeMember(roomId, personId, session)
                                    notice = "Guest request हटाई गई।"
                                    reloadParticipants()
                                } catch (e: Exception) { notice = e.message ?: "Remove failed" }
                                finally { busy = false }
                            }
                        }) { Text("Decline") }
                    }
                }
            }
        }
        if (notice.isNotBlank()) Text(notice, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary)
    }
}

@Composable
private fun NativeLiveRoomChat(session: NativeSession, roomId: String) {
    val chatViewModel: NativeLiveChatViewModel = viewModel(key = "native-live-chat-$roomId")
    val chatState by chatViewModel.state.collectAsState()
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current

    LaunchedEffect(roomId, session.userId, session.accessToken) {
        chatViewModel.start(roomId, session)
    }
    DisposableEffect(chatViewModel, roomId) {
        onDispose { chatViewModel.stop() }
    }
    LaunchedEffect(chatState.messages.lastOrNull()?.optString("id")) {
        if (chatState.messages.isNotEmpty()) {
            listState.animateScrollToItem(chatState.messages.lastIndex)
        }
    }

    Column(
        Modifier.fillMaxWidth().padding(top = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("LIVE Chat", style = MaterialTheme.typography.titleMedium)
            if (chatState.loading && chatState.messages.isEmpty()) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Text(
                    "${chatState.messages.size} recent messages",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            listOf("like" to "👍", "heart" to "❤️", "clap" to "👏", "fire" to "🔥").forEach { (reaction, emoji) ->
                OutlinedButton(
                    enabled = chatState.reactionBusy == null,
                    onClick = { chatViewModel.sendReaction(reaction) },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 3.dp, vertical = 5.dp)
                ) {
                    Text(
                        "$emoji ${chatState.reactionCounts[reaction] ?: 0}",
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1
                    )
                }
            }
        }
        if (chatState.messages.isEmpty() && !chatState.loading && chatState.error.isBlank()) {
            Text(
                "अभी कोई LIVE message नहीं है। बातचीत शुरू करें।",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth().heightIn(max = 210.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(
                items = chatState.messages,
                key = { it.optString("id") }
            ) { message ->
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Column(Modifier.padding(horizontal = 10.dp, vertical = 7.dp)) {
                        Text(
                            message.optString("display_name").ifBlank { "LIVE Viewer" },
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(message.optString("message"), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
        if (chatState.error.isNotBlank()) {
            Text(chatState.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
        }
        if (chatState.reactionNotice.isNotBlank()) {
            Text(chatState.reactionNotice, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
        }
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = chatState.draft,
                onValueChange = chatViewModel::setDraft,
                modifier = Modifier.weight(1f),
                singleLine = false,
                maxLines = 3,
                label = { Text("LIVE में message लिखें") },
                enabled = !chatState.sending,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions.Default.copy(
                    imeAction = androidx.compose.ui.text.input.ImeAction.Send
                ),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                    onSend = {
                        chatViewModel.sendMessage()
                        keyboard?.hide()
                    }
                )
            )
            Button(
                enabled = chatState.draft.isNotBlank() && !chatState.sending,
                onClick = {
                    chatViewModel.sendMessage()
                    keyboard?.hide()
                }
            ) {
                if (chatState.sending) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                } else Text("Send")
            }
        }
    }
}

@Composable
private fun NativePublicCreatorScreen(creatorId: String, session: NativeSession, onMessage: () -> Unit) {
    var profile by remember { mutableStateOf<JSONObject?>(null) }
    var videos by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var following by remember { mutableStateOf(false) }
    var followRequestPending by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var videoTab by remember(creatorId) { mutableStateOf("All") }
    var locationName by remember { mutableStateOf("") }
    var publicExtras by remember { mutableStateOf<JSONObject?>(null) }
    var followerCount by remember(creatorId) { mutableIntStateOf(0) }
    var followingCount by remember(creatorId) { mutableIntStateOf(0) }
    var creatorVideoCount by remember(creatorId) { mutableIntStateOf(0) }
    var profileRefresh by remember(creatorId) { mutableIntStateOf(0) }
    var countsLoading by remember(creatorId) { mutableStateOf(false) }
    var realtimeStatus by remember(creatorId) { mutableStateOf("Connecting") }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val visibleVideos = remember(videos, videoTab) {
        when (videoTab) {
            "Shorts" -> videos.filter { it.optString("video_type").equals("short", true) }
            "Long videos" -> videos.filter { it.optString("video_type").equals("long", true) }
            else -> videos
        }
    }

    LaunchedEffect(creatorId, session.userId, session.accessToken) {
        val observer = NativeFollowRealtime.observe(
            session = session,
            creatorId = creatorId,
            onChange = { scope.launch { profileRefresh++ } },
            onStatus = { status -> realtimeStatus = status }
        )
        observer.start()
        try {
            kotlinx.coroutines.awaitCancellation()
        } finally {
            observer.close()
        }
    }

    LaunchedEffect(creatorId, session.userId, profileRefresh) {
        try {
            val safeId = Uri.encode(creatorId)
            countsLoading = true
            val profiles = NativeSupabase.get(

                "/rest/v1/creator_public_profiles?select=user_id,username,full_name,avatar_url,bio,is_private,location_name&user_id=eq.$safeId",
                session
            )
            val creator = profiles.optJSONObject(0)
                ?: throw IllegalStateException("Creator profile उपलब्ध नहीं है।")
            profile = creator
            locationName = creator.optString("location_name")
            suspend fun countAllRows(path: String): Int {
                var offset = 0
                var total = 0
                while (true) {
                    val page = NativeSupabase.getRange(path, session, offset, offset + 999)
                    total += page.length()
                    if (page.length() < 1000) break
                    offset += 1000
                    if (offset >= 100_000) break
                }
                return total
            }
            val encodedUserId = Uri.encode(creatorId)
            val counts = kotlinx.coroutines.coroutineScope {
                val followersDeferred = async {
                    countAllRows("/rest/v1/followers?select=follower_id&following_id=eq.$encodedUserId&order=follower_id")
                }
                val followingDeferred = async {
                    countAllRows("/rest/v1/followers?select=following_id&follower_id=eq.$encodedUserId&order=following_id")
                }
                val videosDeferred = async {
                    countAllRows("/rest/v1/videos?select=id&user_id=eq.$encodedUserId&order=id")
                }
                Triple(followersDeferred.await(), followingDeferred.await(), videosDeferred.await())
            }
            followerCount = counts.first
            followingCount = counts.second
            creatorVideoCount = counts.third
            publicExtras = if (creator.optBoolean("is_private")) null else try {
                NativeSupabase.get(
                    "/rest/v1/creator_profile_extras?select=cover_url,cover_transform,website,facebook,instagram,youtube,tiktok&user_id=eq.$safeId",
                    session
                ).optJSONObject(0)
            } catch (_: Exception) { null }
            val relation = NativeSupabase.get(
                "/rest/v1/followers?select=id&follower_id=eq.${Uri.encode(session.userId)}&following_id=eq.$safeId",
                session
            )
            following = relation.length() > 0
            if (creator.optBoolean("is_private") && !following && creatorId != session.userId) {
                val requests = NativeSupabase.get(
                    "/rest/v1/follow_requests?select=id&requester_id=eq.${Uri.encode(session.userId)}&target_user_id=eq.$safeId&status=eq.pending&limit=1",
                    session
                )
                followRequestPending = requests.length() > 0
            } else {
                followRequestPending = false
            }

            // Never request private creator video rows until the viewer follows them.
            val canViewVideos = !creator.optBoolean("is_private") ||
                following || creatorId == session.userId
            if (!canViewVideos) {
                videos = emptyList()
            } else {
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
                            .put("username", creator.optString("username"))
                    }
                } else videos = emptyList()
            }
        } catch (e: Exception) {
            error = e.message ?: "Creator profile लोड नहीं हुई।"
        } finally {
            countsLoading = false
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        if (profile == null && error.isBlank()) CircularProgressIndicator()
        profile?.let { creator ->
            val extras = publicExtras
            val cover = extras?.optString("cover_url").orEmpty()
            if (cover.startsWith("https://")) {
                val transform = extras?.optJSONObject("cover_transform") ?: JSONObject()
                val scale = transform.optDouble("scale", 1.0).toFloat().coerceIn(1f, 3.5f)
                val offsetX = transform.optDouble("x", 0.0).toFloat().coerceIn(-50f, 50f)
                val offsetY = transform.optDouble("y", 0.0).toFloat().coerceIn(-50f, 50f)
                AsyncImage(
                    model = cover,
                    contentDescription = "Creator profile cover",
                    modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                        .clip(RoundedCornerShape(12.dp))
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = offsetX / 100f * size.width
                            translationY = offsetY / 100f * size.height
                        },
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop
                )
            }
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
            val socialLabels = listOf(
                "website" to "Website", "facebook" to "Facebook",
                "instagram" to "Instagram", "youtube" to "YouTube", "tiktok" to "TikTok"
            )
            val availableSocials = socialLabels.mapNotNull { (key, label) ->
                extras?.optString(key)?.takeIf { it.isNotBlank() && it != "null" }?.let { label to it }
            }
            if (availableSocials.isNotEmpty()) {
                Text("Social Links", style = MaterialTheme.typography.titleSmall)
                availableSocials.chunked(2).forEach { pair ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        pair.forEach { (label, rawUrl) ->
                            TextButton(onClick = {
                                try {
                                    val url = if (rawUrl.startsWith("http://") || rawUrl.startsWith("https://")) rawUrl
                                        else "https://$rawUrl"
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                                } catch (_: Exception) { error = "यह Social Link नहीं खुल सका।" }
                            }) { Text(label) }
                        }
                    }
                }
            }
            if (locationName.isNotBlank()) {
                Text("📍 $locationName", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                    Text(creatorVideoCount.toString(), fontWeight = FontWeight.Bold)
                    Text("Videos", style = MaterialTheme.typography.labelSmall)
                }
                Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                    Text(followerCount.toString(), fontWeight = FontWeight.Bold)
                    Text("Followers", style = MaterialTheme.typography.labelSmall)
                }
                Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                    Text(followingCount.toString(), fontWeight = FontWeight.Bold)
                    Text("Following", style = MaterialTheme.typography.labelSmall)
                }
                TextButton(enabled = !countsLoading, onClick = { profileRefresh++ }) {
                    Text(if (countsLoading) "Refreshing…" else "Refresh")
                }
            }
            Text(
                when (realtimeStatus) {
                    "Connected" -> "● Live profile sync active"
                    "Connecting" -> "○ Connecting live sync…"
                    "Stopped" -> "Live sync stopped"
                    else -> "○ Live sync reconnecting"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (creatorId != session.userId) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = onMessage,
                        enabled = !creator.optBoolean("is_private"),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(if (creator.optBoolean("is_private")) "🔒 Private Account" else "💬 Message / Chat")
                    }
                    Button(
                        enabled = !busy,
                        modifier = Modifier.weight(1f),
                        onClick = {
                    busy = true
                    scope.launch {
                        try {
                            if (following) {
                                NativeSupabase.delete(
                                    "/rest/v1/followers?follower_id=eq.${Uri.encode(session.userId)}&following_id=eq.${Uri.encode(creatorId)}",
                                    session
                                )
                                following = false
                                followerCount = (followerCount - 1).coerceAtLeast(0)
                                profileRefresh++
                            } else if (creator.optBoolean("is_private") && followRequestPending) {
                                NativeSupabase.delete(
                                    "/rest/v1/follow_requests?requester_id=eq.${Uri.encode(session.userId)}&target_user_id=eq.${Uri.encode(creatorId)}&status=eq.pending",
                                    session
                                )
                                followRequestPending = false
                            } else if (creator.optBoolean("is_private")) {
                                val safeCreatorId = Uri.encode(creatorId)
                                val existing = NativeSupabase.get(
                                    "/rest/v1/follow_requests?select=id&requester_id=eq.${Uri.encode(session.userId)}&target_user_id=eq." + safeCreatorId + "&status=eq.pending&limit=1",
                                    session
                                )
                                if (existing.length() == 0) {
                                    NativeSupabase.post("/rest/v1/follow_requests", JSONObject()
                                        .put("requester_id", session.userId)
                                        .put("target_user_id", creatorId)
                                        .put("status", "pending"), session)
                                }
                                followRequestPending = true
                            } else {
                                NativeSupabase.post("/rest/v1/followers", JSONObject()
                                    .put("follower_id", session.userId)
                                    .put("following_id", creatorId), session)
                                following = true
                                followerCount += 1
                                profileRefresh++
                            }
                        } catch (e: Exception) {
                            error = e.message ?: "Follow update नहीं हुआ।"
                        } finally { busy = false }
                    }
                    ) {
                        Text(
                            when {
                                busy -> "Please wait…"
                                following -> "Following"
                                followRequestPending -> "Cancel Request"
                                creator.optBoolean("is_private") -> "Request Follow"
                                else -> "Follow"
                            }
                        )
                    }
                }
            }
            if (creator.optBoolean("is_private") && !following && creatorId != session.userId) {
                Text(
                    if (followRequestPending) "आपकी Follow Request Pending है। चाहें तो Cancel Request दबाकर इसे वापस ले सकते हैं। Creator की मंज़ूरी के बाद ही वीडियो दिखेंगे।"
                    else "यह Private Creator account है। वीडियो देखने के लिए Follow request स्वीकार होना जरूरी है।"
                )
            } else {
                Text("Videos", style = MaterialTheme.typography.titleLarge)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(listOf("All", "Shorts", "Long videos")) { tab ->
                        FilterChip(
                            selected = videoTab == tab,
                            onClick = { videoTab = tab },
                            label = { Text("$tab (${when (tab) {
                                "Shorts" -> videos.count { it.optString("video_type").equals("short", true) }
                                "Long videos" -> videos.count { it.optString("video_type").equals("long", true) }
                                else -> videos.size
                            }})") }
                        )
                    }
                }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(visibleVideos) { row ->
                        val id = row.optString("id")
                        val nativeVideo = NativeVideo(
                            id = id,
                            url = row.optString("url"),
                            caption = row.optString("caption"),
                            username = row.optString("username"),
                            category = row.optString("category"),
                            likes = row.optInt("likes_count"),
                            views = row.optInt("views_count"),
                            type = row.optString("video_type").ifBlank { "short" },
                            userId = creatorId
                        )
                        NativeVideoCard(nativeVideo, session)
                    }
                    if (visibleVideos.isEmpty()) item {
                        Text(if (videos.isEmpty()) "अभी कोई उपलब्ध वीडियो नहीं है।" else "इस Video category में अभी कोई वीडियो नहीं है।")
                    }
                }
            }
        }
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun NativeSharedVideoScreen(
    videoId: String,
    session: NativeSession?,
    onBack: () -> Unit,
    onOpenCreator: (String) -> Unit
) {
    var video by remember(videoId) { mutableStateOf<NativeVideo?>(null) }
    var loading by remember(videoId) { mutableStateOf(true) }
    var error by remember(videoId) { mutableStateOf<String?>(null) }
    var retry by remember(videoId) { mutableIntStateOf(0) }

    LaunchedEffect(videoId, session?.userId, retry) {
        loading = true
        error = null
        video = null
        try {
            video = NativeFeed.loadSharedVideo(videoId, session)
        } catch (e: Exception) {
            error = e.message ?: "Shared video लोड नहीं हुआ।"
        } finally {
            loading = false
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) { Text("← Home") }
            Text("Shared Video", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                CircularProgressIndicator()
            }
            error != null -> Column(
                Modifier.fillMaxWidth().padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
            ) {
                Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
                Button(onClick = { retry++ }) { Text("Retry") }
            }
            video != null -> NativeVideoCard(
                video = video!!,
                session = session,
                isActive = true,
                onOpenCreator = if (session != null) onOpenCreator else null
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun NativeHome(
    session: NativeSession?,
    onOpenCreator: (String) -> Unit,
    onAskAi: (String) -> Unit,
    onNavigate: (String) -> Unit
) {
    val feedContext = LocalContext.current.applicationContext
    var category by remember { mutableStateOf("All") }
    var feedMode by remember { mutableStateOf("For You") }
    var search by remember { mutableStateOf("") }
    var searchExpanded by remember { mutableStateOf(false) }
    var creatorResults by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var creatorSearchLoading by remember { mutableStateOf(false) }
    var creatorSearchError by remember { mutableStateOf<String?>(null) }
    var videoResults by remember { mutableStateOf<List<NativeVideo>>(emptyList()) }
    var videoSearchLoading by remember { mutableStateOf(false) }
    var videoSearchError by remember { mutableStateOf<String?>(null) }
    var feed by remember { mutableStateOf<List<NativeVideo>>(emptyList()) }
    val visibleFeed = remember(feed, search) {
        val term = search.trim().lowercase()
        if (term.isEmpty()) feed else feed.filter { video ->
            video.caption.lowercase().contains(term) ||
                video.username.lowercase().contains(term) ||
                video.category.lowercase().contains(term)
        }
    }
    val feedPagerState = rememberPagerState(pageCount = { visibleFeed.size })
    LaunchedEffect(feedMode, category, visibleFeed.size, search) {
        if (visibleFeed.isNotEmpty()) feedPagerState.scrollToPage(0)
    }
    var loading by remember { mutableStateOf(true) }
    var loadingMore by remember { mutableStateOf(false) }
    var canLoadMore by remember { mutableStateOf(false) }
    var pageError by remember { mutableStateOf<String?>(null) }
    var pageRetry by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var menuExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(category, feedMode, refresh, session?.userId) {
        loading = true
        loadingMore = false
        canLoadMore = false
        pageError = null
        error = null
        try {
            feed = when (feedMode) {
                "Following", "Friends" -> {
                    val current = session ?: throw IllegalStateException("इस Feed को देखने के लिए Login करें।")
                    NativeFeed.loadFollowing(current)
                }
                "Shorts", "Reels" -> NativeFeed.load(category, "short", session, 0)
                "Long videos" -> NativeFeed.load(category, "long", session, 0)
                "Discover", "For You" -> NativeFeed.load(category, session = session, offset = 0)
                else -> NativeFeed.load(category, session = session, offset = 0)
            }
            canLoadMore = feed.size >= NativeFeed.PAGE_SIZE
            if (feed.isEmpty()) error = if (feedMode == "Following" || feedMode == "Friends") "आप जिन Creators को Follow करते हैं, उनकी अभी कोई सार्वजनिक वीडियो नहीं है।" else "अभी कोई सार्वजनिक वीडियो उपलब्ध नहीं है।"
        } catch (e: Exception) {
            feed = emptyList()
            error = e.message ?: "Feed लोड नहीं हो सकी।"
        } finally { loading = false }
    }

    LaunchedEffect(feedPagerState.currentPage, visibleFeed) {
        val nextVideo = visibleFeed.getOrNull(feedPagerState.currentPage + 1)
        if (nextVideo != null) {
            withContext(Dispatchers.IO) {
                NativeVideoCache.preload(context = feedContext, url = nextVideo.url)
            }
        }
    }

    LaunchedEffect(feedPagerState.currentPage, feed.size, canLoadMore, loading, pageRetry, feedMode, category, session?.userId) {
        val current = session
        val nearEnd = feed.isNotEmpty() && feedPagerState.currentPage >= feed.size - 3
        if (!nearEnd || !canLoadMore || loading || loadingMore) return@LaunchedEffect
        loadingMore = true
        pageError = null
        try {
            val nextPage = when (feedMode) {
                "Shorts", "Reels" -> NativeFeed.load(category, "short", current, feed.size)
                "Long videos" -> NativeFeed.load(category, "long", current, feed.size)
                "Following", "Friends" -> current?.let { NativeFeed.loadFollowing(it, feed.size) } ?: emptyList()
                else -> NativeFeed.load(category, session = current, offset = feed.size)
            }
            val existingIds = feed.asSequence().map { it.id }.toHashSet()
            val uniqueNext = nextPage.filterNot { it.id in existingIds }
            feed = feed + uniqueNext
            canLoadMore = nextPage.size >= NativeFeed.PAGE_SIZE && uniqueNext.isNotEmpty()
        } catch (e: Exception) {
            pageError = e.message ?: "अगले वीडियो लोड नहीं हो सके।"
        } finally {
            loadingMore = false
        }
    }

    LaunchedEffect(search, session?.userId) {
        val term = search.trim().replace("%", "").replace("*", "").replace(",", " ").replace("(", " ").replace(")", " ")
        if (term.length < 2) {
            videoResults = emptyList()
            videoSearchError = null
            videoSearchLoading = false
            creatorResults = emptyList()
            creatorSearchError = null
            creatorSearchLoading = false
        } else {
            delay(350)
            creatorSearchLoading = true
            videoSearchLoading = true
            creatorSearchError = null
            videoSearchError = null
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
            try {
                videoResults = NativeFeed.search(term, session)
            } catch (e: Exception) {
                videoResults = emptyList()
                videoSearchError = e.message ?: "Video search नहीं हो सकी।"
            } finally {
                videoSearchLoading = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("🌟 New Star", fontWeight = FontWeight.Bold) },
                actions = {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        IconButton(onClick = { searchExpanded = !searchExpanded }) {
                            Text(if (searchExpanded) "×" else "⌕", style = MaterialTheme.typography.headlineSmall)
                        }
                        TextButton(onClick = { onNavigate("Creator AI") }) { Text("AI Engine") }
                        Box {
                            IconButton(onClick = { menuExpanded = true }) { Text("⋮", style = MaterialTheme.typography.headlineSmall) }
                            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                                listOf(
                                    "My Profile" to "Profile",
                                    "Settings" to "Settings",
                                    "Creator Studio AI" to "Creator AI",
                                    "Help Center AI" to "Help Center",
                                    "Upload Video" to "Upload",
                                    "Inbox / Messages" to "Inbox",
                                    "Notifications" to "Notifications",
                                    "Wallet / Coins" to "Wallet",
                                    "Offline Library" to "Offline Library",
                                    "Creator Monetization Hub" to "Monetization",
                                    "Fan Support" to "Fan Support",
                                    "Campaigns & Brand Deals" to "Campaigns",
                                    "About New Star" to "About",
                                    "Contact & Support" to "Contact",
                                    "Terms & Content Rules" to "Terms",
                                    "Privacy Policy" to "Privacy",
                                    "LIVE" to "Live",
                                    "Gaming LIVE" to "Gaming"
                                ).forEach { item ->
                                    DropdownMenuItem(text = { Text(item.first) },
                                        onClick = { menuExpanded = false; onNavigate(item.second) })
                                }
                            }
                        }
                    }
                }
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
            if (searchExpanded || search.isNotBlank()) {
                OutlinedTextField(
                    value = search, onValueChange = { search = it },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    singleLine = true, label = { Text("दुनिया भर में Creator और Videos खोजें") },
                    trailingIcon = {
                        if (search.isNotEmpty()) TextButton(onClick = {
                            search = ""
                            searchExpanded = false
                        }) { Text("Clear") }
                    }
                )
            }
            if (search.trim().isNotEmpty()) {
                TextButton(onClick = { onAskAi(search.trim()) },
                    modifier = Modifier.padding(horizontal = 12.dp)) {
                    Text("New Star AI Engine से पूछें: ${search.trim().take(45)}")
                }
            }
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
            if (search.trim().length >= 2) {
                Text("Video खोज", modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.titleMedium)
                when {
                    videoSearchLoading -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                        CircularProgressIndicator()
                    }
                    videoSearchError != null -> Column(Modifier.weight(1f).fillMaxWidth().padding(16.dp),
                        verticalArrangement = Arrangement.Center) {
                        Text(videoSearchError.orEmpty(), color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = { search = "" }) { Text("Clear search") }
                    }
                    videoResults.isEmpty() -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                        Text("इस शब्द से कोई सार्वजनिक Video नहीं मिला।")
                    }
                    else -> LazyColumn(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentPadding = PaddingValues(bottom = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(videoResults, key = { it.id }) { video -> NativeVideoCard(video, session, onOpenCreator = onOpenCreator) }
                    }
                }
            } else {
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(listOf("For You", "Following", "Shorts", "Reels", "Long videos", "Discover", "Friends", "LIVE")) { name ->
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
                else -> Box(Modifier.fillMaxSize()) {
                    VerticalPager(
                        state = feedPagerState,
                        modifier = Modifier.fillMaxSize(),
                        key = { page -> visibleFeed[page].id },
                        beyondViewportPageCount = 0
                    ) { page ->
                        val video = visibleFeed[page]
                        NativeVideoCard(
                            video = video,
                            session = session,
                            isActive = feedPagerState.currentPage == page && feedPagerState.isScrollInProgress.not(),
                            immersive = true,
                            onOpenCreator = onOpenCreator
                        )
                    }
                    if (loadingMore) {
                        Surface(
                            modifier = Modifier.align(androidx.compose.ui.Alignment.BottomCenter).padding(12.dp),
                            shape = RoundedCornerShape(20.dp),
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)
                        ) {
                            Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(10.dp))
                                Text("अगले वीडियो लोड हो रहे हैं…")
                            }
                        }
                    } else if (pageError != null && canLoadMore) {
                        Surface(
                            modifier = Modifier.align(androidx.compose.ui.Alignment.BottomCenter).padding(12.dp),
                            shape = RoundedCornerShape(20.dp),
                            color = MaterialTheme.colorScheme.surface
                        ) {
                            Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                Text(pageError.orEmpty(), modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
                                TextButton(onClick = { pageRetry++ }) { Text("फिर कोशिश करें") }
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
private fun NativeVideoCard(
    video: NativeVideo,
    session: NativeSession?,
    isActive: Boolean = false,
    immersive: Boolean = false,
    onOpenCreator: ((String) -> Unit)? = null
) {
    val context = LocalContext.current
    val shareUrl = remember(video.id) {
        Uri.Builder().scheme("https").authority("new-star-delta.vercel.app")
            .appendQueryParameter("video", video.id).build().toString()
    }
    var liked by remember(video.id, session?.userId) { mutableStateOf(false) }
    var likeCount by remember(video.id) { mutableIntStateOf(video.likes) }
    var likeBusy by remember(video.id) { mutableStateOf(false) }
    var likeError by remember(video.id) { mutableStateOf<String?>(null) }
    var commentsOpen by remember(video.id) { mutableStateOf(false) }
    var commentRows by remember(video.id) { mutableStateOf<List<JSONObject>>(emptyList()) }
    var commentDraft by remember(video.id) { mutableStateOf("") }
    var commentBusy by remember(video.id) { mutableStateOf(false) }
    var commentError by remember(video.id) { mutableStateOf<String?>(null) }
    var giftDialogOpen by remember(video.id) { mutableStateOf(false) }
    var giftBusy by remember(video.id) { mutableStateOf(false) }
    var giftBalance by remember(video.id) { mutableIntStateOf(-1) }
    var giftMessage by remember(video.id) { mutableStateOf<String?>(null) }
    var currentUsername by remember(session?.userId) { mutableStateOf("creator") }
    var followLabel by remember(video.id, session?.userId) { mutableStateOf("") }
    var followBusy by remember(video.id, session?.userId) { mutableStateOf(false) }
    var followError by remember(video.id, session?.userId) { mutableStateOf<String?>(null) }
    var downloadBusy by remember(video.id) { mutableStateOf(false) }
    var offlineSaved by remember(video.id) { mutableStateOf(false) }
    var downloadRequested by remember(video.id) { mutableStateOf(false) }
    var reportDialogOpen by remember(video.id) { mutableStateOf(false) }
    var blockCreatorDialogOpen by remember(video.id) { mutableStateOf(false) }
    var blockCreatorBusy by remember(video.id) { mutableStateOf(false) }
    var blockCreatorError by remember(video.id) { mutableStateOf<String?>(null) }
    var creatorBlocked by remember(video.id, session?.userId) { mutableStateOf(false) }
    var reportTargetType by remember(video.id) { mutableStateOf("video") }
    var reportTargetId by remember(video.id) { mutableStateOf(video.id) }
    var reportReason by remember(video.id) { mutableStateOf("spam") }
    var reportDetails by remember(video.id) { mutableStateOf("") }
    val reportViewModel: NativeContentReportViewModel = viewModel(key = "content-report-${video.id}")
    val reportState = reportViewModel.uiState
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
    LaunchedEffect(video.userId, session?.userId) {
        val current = session ?: run { followLabel = ""; return@LaunchedEffect }
        val creatorId = video.userId
        if (creatorId.isBlank() || creatorId == current.userId) { followLabel = ""; return@LaunchedEffect }
        try {
            val profile = NativeSupabase.get(
                "/rest/v1/creator_public_profiles?select=is_private&user_id=eq.${Uri.encode(creatorId)}&limit=1",
                current
            ).optJSONObject(0) ?: run { followLabel = ""; return@LaunchedEffect }
            val followed = NativeSupabase.get(
                "/rest/v1/followers?select=id&follower_id=eq.${Uri.encode(current.userId)}&following_id=eq.${Uri.encode(creatorId)}&limit=1",
                current
            )
            followLabel = if (followed.length() > 0) "Following" else if (profile.optBoolean("is_private")) {
                val requests = NativeSupabase.get(
                    "/rest/v1/follow_requests?select=id&requester_id=eq.${Uri.encode(current.userId)}&target_user_id=eq.${Uri.encode(creatorId)}&status=eq.pending&limit=1",
                    current
                )
                if (requests.length() > 0) "Requested" else "Request Follow"
            } else "Follow"
        } catch (e: Exception) {
            followError = e.message ?: "Follow status लोड नहीं हुआ।"
        }
    }
    LaunchedEffect(video.userId, session?.userId) {
        val current = session ?: run { creatorBlocked = false; return@LaunchedEffect }
        if (video.userId.isBlank() || video.userId == current.userId) {
            creatorBlocked = false
            return@LaunchedEffect
        }
        try {
            val rows = NativeSupabase.get(
                "/rest/v1/user_blocks?select=id&blocker_id=eq.${Uri.encode(current.userId)}&blocked_id=eq.${Uri.encode(video.userId)}&limit=1",
                current
            )
            creatorBlocked = rows.length() > 0
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
    LaunchedEffect(video.id) {
        offlineSaved = withContext(Dispatchers.IO) {
            NativeOfflineDownloads.isDownloaded(context, video.id)
        }
    }
    LaunchedEffect(video.id, downloadRequested) {
        if (downloadRequested) {
            while (true) {
                val state = withContext(Dispatchers.IO) {
                    NativeOfflineDownloads.downloadState(context, video.id)
                }
                when (state) {
                    androidx.media3.exoplayer.offline.Download.STATE_COMPLETED -> {
                        offlineSaved = true
                        downloadRequested = false
                        break
                    }
                    androidx.media3.exoplayer.offline.Download.STATE_FAILED -> {
                        downloadRequested = false
                        Toast.makeText(context, "Offline download असफल हुआ। दोबारा कोशिश करें।", Toast.LENGTH_LONG).show()
                        break
                    }
                    else -> kotlinx.coroutines.delay(5_000)
                }
            }
        }
    }
    var playerError by remember(video.id) { mutableStateOf<String?>(null) }
    var playerRetry by remember(video.id) { mutableIntStateOf(0) }
    // Use the encoded media's real display ratio instead of fixed 500dp/235dp
    // player heights. This keeps portrait and landscape uploads fully visible.
    var videoAspectRatio by remember(video.id, video.type) {
        mutableFloatStateOf(if (video.type.equals("long", true)) 16f / 9f else 9f / 16f)
    }
    val exoPlayer = remember(video.url, offlineSaved) {
        // Offline cache/index can be unavailable after storage cleanup or an
        // interrupted download. Fall back to the normal network source instead
        // of letting player construction crash the feed composition.
        val sourceFactory = NativeVideoCache.playbackDataSourceFactory(context, offlineSaved)
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(sourceFactory))
            .build().apply {
                setMediaItem(MediaItem.fromUri(video.url))
                prepare()
                playWhenReady = false
            }
    }
    DisposableEffect(exoPlayer) {
        val listener = object : androidx.media3.common.Player.Listener {
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                playerError = error.localizedMessage ?: "वीडियो चल नहीं सका। नेटवर्क जाँचकर दोबारा कोशिश करें।"
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == androidx.media3.common.Player.STATE_READY) playerError = null
            }
            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    val ratio = (videoSize.width.toFloat() * videoSize.pixelWidthHeightRatio) /
                        videoSize.height.toFloat()
                    if (ratio.isFinite() && ratio in 0.35f..3.0f) {
                        videoAspectRatio = ratio
                    }
                }
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }
    LaunchedEffect(isActive, playerRetry) {
        try {
            if (isActive) {
                if (exoPlayer.playbackState == androidx.media3.common.Player.STATE_IDLE ||
                    exoPlayer.playbackState == androidx.media3.common.Player.STATE_ENDED) exoPlayer.prepare()
                exoPlayer.playWhenReady = true
                exoPlayer.play()
            } else {
                exoPlayer.pause()
                exoPlayer.playWhenReady = false
            }
        } catch (error: Exception) {
            playerError = error.localizedMessage ?: "वीडियो प्लेयर शुरू नहीं हो सका।"
        }
    }
    val watchTracker = remember(exoPlayer, video.id, session?.userId, session?.accessToken) {
        NativeVideoWatchTracker(session, video.id, exoPlayer)
    }
    DisposableEffect(watchTracker) {
        onDispose { watchTracker.close() }
    }
    if (!creatorBlocked) {
    Card(
        modifier = if (immersive) Modifier.fillMaxSize() else Modifier.fillMaxWidth().padding(horizontal = 10.dp),
        shape = RoundedCornerShape(if (immersive) 0.dp else 16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (immersive) androidx.compose.ui.graphics.Color.Black
                else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column {
            Box(
                modifier = if (immersive) {
                    Modifier.fillMaxWidth().weight(1f)
                } else {
                    Modifier.fillMaxWidth().aspectRatio(videoAspectRatio)
                }
            ) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx -> PlayerView(ctx).apply {
                        player = exoPlayer
                        // Feed swipes should not be intercepted by playback controls.
                        useController = !immersive
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    } },
                    update = { view ->
                        if (view.player !== exoPlayer) view.player = exoPlayer
                        view.useController = !immersive
                        // Keep FIT applied after recomposition as well as creation;
                        // ZOOM would crop portrait/landscape frames on some devices.
                        view.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    }
                )
                if (playerError != null) {
                    Surface(
                        modifier = Modifier.align(androidx.compose.ui.Alignment.Center).padding(20.dp),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f)
                    ) {
                        Column(
                            Modifier.padding(16.dp),
                            horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
                        ) {
                            Text(playerError.orEmpty(), color = MaterialTheme.colorScheme.error)
                            Spacer(Modifier.height(8.dp))
                            TextButton(onClick = {
                                playerError = null
                                playerRetry++
                            }) { Text("वीडियो फिर चलाएँ") }
                        }
                    }
                }
            }
            Column(Modifier.fillMaxWidth().padding(14.dp)) {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text(
                        "@${video.username.ifBlank { "creator" }}",
                        modifier = Modifier.weight(1f).then(if (onOpenCreator != null && video.userId.isNotBlank()) Modifier.clickable { onOpenCreator(video.userId) } else Modifier),
                        fontWeight = FontWeight.Bold
                    )
                    if (followLabel.isNotBlank()) {
                        TextButton(
                            enabled = session != null && !followBusy && followLabel != "Requested",
                            onClick = {
                                val current = session ?: return@TextButton
                                val creatorId = video.userId
                                followBusy = true
                                followError = null
                                scope.launch {
                                    try {
                                        when (followLabel) {
                                            "Following" -> {
                                                val rows = NativeSupabase.get(
                                                    "/rest/v1/followers?select=id&follower_id=eq.${Uri.encode(current.userId)}&following_id=eq.${Uri.encode(creatorId)}&limit=1",
                                                    current
                                                )
                                                val rowId = rows.optJSONObject(0)?.optString("id").orEmpty()
                                                if (rowId.isBlank()) throw IllegalStateException("Follow record नहीं मिला।")
                                                NativeSupabase.delete(
                                                    "/rest/v1/followers?id=eq.${Uri.encode(rowId)}&follower_id=eq.${Uri.encode(current.userId)}",
                                                    current
                                                )
                                                followLabel = "Follow"
                                            }
                                            "Request Follow" -> {
                                                NativeSupabase.post(
                                                    "/rest/v1/follow_requests",
                                                    JSONObject().put("requester_id", current.userId).put("target_user_id", creatorId).put("status", "pending"),
                                                    current
                                                )
                                                followLabel = "Requested"
                                            }
                                            "Follow" -> {
                                                NativeSupabase.post(
                                                    "/rest/v1/followers",
                                                    JSONObject().put("follower_id", current.userId).put("following_id", creatorId),
                                                    current
                                                )
                                                followLabel = "Following"
                                            }
                                        }
                                    } catch (e: Exception) {
                                        followError = e.message ?: "Follow update नहीं हुआ।"
                                    } finally { followBusy = false }
                                }
                            }
                        ) { Text(if (followBusy) "…" else followLabel) }
                    }
                }
                if (followError != null) Text(followError.orEmpty(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                if (video.caption.isNotBlank()) { Spacer(Modifier.height(4.dp)); Text(video.caption) }
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    if (!immersive) {
                        Text("${video.category} · $likeCount likes · ${video.views} views",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
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
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
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
                    TextButton(
                        onClick = {
                            val shareText = buildString {
                                append(video.caption.ifBlank { "Watch this video on New Star" })
                                append("\n")
                                append(shareUrl)
                            }
                            try {
                                val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_SUBJECT, "New Star Video")
                                    putExtra(Intent.EXTRA_TEXT, shareText)
                                }
                                context.startActivity(Intent.createChooser(sendIntent, "Share video"))
                            } catch (e: Exception) {
                                Toast.makeText(context, e.message ?: "Share नहीं हो सका।", Toast.LENGTH_LONG).show()
                            }
                        }
                    ) { Text("Share") }
                    TextButton(
                        enabled = !downloadBusy && !downloadRequested,
                        onClick = {
                            if (offlineSaved) {
                                try {
                                    NativeOfflineDownloads.remove(context, video.id)
                                    offlineSaved = false
                                    Toast.makeText(context, "Offline copy हटाई जा रही है।", Toast.LENGTH_SHORT).show()
                                } catch (e: Exception) {
                                    Toast.makeText(context, e.message ?: "Offline copy नहीं हट सकी।", Toast.LENGTH_LONG).show()
                                }
                                return@TextButton
                            }
                            downloadBusy = true
                            try {
                                NativeOfflineDownloads.enqueue(context, video.id, video.url)
                                downloadRequested = true
                                Toast.makeText(
                                    context,
                                    "Offline download शुरू हुआ। HLS और MP4 app storage में सेव होंगे; notification में progress देखें।",
                                    Toast.LENGTH_LONG
                                ).show()
                            } catch (e: Exception) {
                                Toast.makeText(context, e.message ?: "यह video format offline download के लिए समर्थित नहीं है।", Toast.LENGTH_LONG).show()
                            } finally {
                                downloadBusy = false
                            }
                        }
                    ) {
                        Text(
                            when {
                                downloadBusy -> "Starting…"
                                offlineSaved -> "Remove offline"
                                downloadRequested -> "Downloading…"
                                else -> "Download"
                            }
                        )
                    }
                    TextButton(
                        onClick = {
                            try {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("New Star video", shareUrl))
                                Toast.makeText(context, "Video link कॉपी हो गया।", Toast.LENGTH_SHORT).show()
                            } catch (e: Exception) {
                                Toast.makeText(context, e.message ?: "Link कॉपी नहीं हुआ।", Toast.LENGTH_LONG).show()
                            }
                        }
                    ) { Text("Copy link") }
                    TextButton(
                        enabled = session != null && !reportState.isSubmitting,
                        onClick = {
                            reportTargetType = "video"
                            reportTargetId = video.id
                            reportReason = "spam"
                            reportDetails = ""
                            reportViewModel.reset()
                            reportDialogOpen = true
                        }
                    ) { Text("Report") }
                    if (session != null && video.userId.isNotBlank() && video.userId != session.userId) {
                        TextButton(
                            enabled = !blockCreatorBusy,
                            onClick = {
                                blockCreatorError = null
                                blockCreatorDialogOpen = true
                            }
                        ) { Text("Block creator") }
                    }
                    TextButton(
                        enabled = session != null && !giftBusy,
                        onClick = {
                            giftDialogOpen = true
                            giftBalance = -1
                            giftMessage = null
                            val current = session ?: return@TextButton
                            scope.launch {
                                try {
                                    val result = NativeSupabase.invokeFunction(
                                        "video-gift", JSONObject().put("action", "balance"), current
                                    )
                                    if (!result.optBoolean("success")) {
                                        throw IllegalStateException(result.optString("error", "Wallet balance नहीं मिला।"))
                                    }
                                    giftBalance = result.optInt("balance", 0)
                                } catch (e: Exception) {
                                    giftMessage = e.message ?: "Wallet balance लोड नहीं हुआ।"
                                }
                            }
                        }
                    ) { Text("🎁 Gift") }
                }
            }
        }
    }
    }
    if (blockCreatorDialogOpen) {
        val current = session
        AlertDialog(
            onDismissRequest = { if (!blockCreatorBusy) blockCreatorDialogOpen = false },
            title = { Text("Block creator?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("इस creator को block करने पर उनके videos आपके Home, Following और Search feed से छिपेंगे। आप Settings → Blocked Accounts से बाद में unblock कर सकते हैं।")
                    if (blockCreatorError != null) {
                        Text(blockCreatorError.orEmpty(), color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = current != null && !blockCreatorBusy,
                    onClick = {
                        val actor = current ?: return@TextButton
                        blockCreatorBusy = true
                        blockCreatorError = null
                        scope.launch {
                            try {
                                NativeSupabase.post(
                                    "/rest/v1/user_blocks",
                                    JSONObject().put("blocker_id", actor.userId).put("blocked_id", video.userId),
                                    actor
                                )
                                creatorBlocked = true
                                blockCreatorDialogOpen = false
                                Toast.makeText(context, "Creator block हो गया। Settings से unblock कर सकते हैं।", Toast.LENGTH_LONG).show()
                            } catch (error: Exception) {
                                val detail = error.message ?: "Creator block नहीं हुआ।"
                                if (detail.contains("409") || detail.contains("duplicate", true)) {
                                    creatorBlocked = true
                                    blockCreatorDialogOpen = false
                                    Toast.makeText(context, "यह creator पहले से blocked है।", Toast.LENGTH_SHORT).show()
                                } else blockCreatorError = detail
                            } finally { blockCreatorBusy = false }
                        }
                    }
                ) { Text(if (blockCreatorBusy) "Blocking…" else "Block creator") }
            },
            dismissButton = {
                TextButton(enabled = !blockCreatorBusy, onClick = { blockCreatorDialogOpen = false }) { Text("Cancel") }
            }
        )
    }
    if (reportDialogOpen) {
        val current = session
        val reasons = listOf(
            "copyright" to "Copyright",
            "harassment" to "Harassment or bullying",
            "spam" to "Spam",
            "scam" to "Scam or misleading content",
            "nudity_or_sexual_content" to "Sexual content",
            "violence" to "Violence",
            "dangerous_content" to "Dangerous content",
            "child_safety" to "Child safety",
            "privacy" to "Privacy violation",
            "other" to "Other"
        )
        AlertDialog(
            onDismissRequest = { if (!reportState.isSubmitting) reportDialogOpen = false },
            title = { Text(if (reportState.isSubmitted) "Report submitted" else "Report content") },
            text = {
                Column(
                    Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (reportState.isSubmitted) {
                        Text("आपकी report सुरक्षित रूप से भेज दी गई है। हमारी moderation team इसकी समीक्षा करेगी।")
                    } else {
                        Text("आप किसकी report करना चाहते हैं?")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = reportTargetType == "video",
                                onClick = { reportTargetType = "video" },
                                enabled = !reportState.isSubmitting,
                                label = { Text("Video") }
                            )
                            FilterChip(
                                selected = reportTargetType == "user",
                                onClick = { reportTargetType = "user" },
                                enabled = !reportState.isSubmitting && video.userId.isNotBlank(),
                                label = { Text("Creator") }
                            )
                            if (reportTargetType == "comment") {
                                FilterChip(
                                    selected = true,
                                    onClick = { },
                                    enabled = false,
                                    label = { Text("Comment") }
                                )
                            }
                        }
                        Text("कारण चुनें")
                        LazyColumn(
                            Modifier.heightIn(max = 210.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            items(reasons) { item ->
                                val (value, label) = item
                                Row(
                                    Modifier.fillMaxWidth().clickable(enabled = !reportState.isSubmitting) {
                                        reportReason = value
                                    }.padding(vertical = 5.dp),
                                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                                ) {
                                    RadioButton(
                                        selected = reportReason == value,
                                        onClick = { reportReason = value },
                                        enabled = !reportState.isSubmitting
                                    )
                                    Text(label, modifier = Modifier.padding(start = 8.dp))
                                }
                            }
                        }
                        OutlinedTextField(
                            value = reportDetails,
                            onValueChange = { reportDetails = it.take(1200) },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("अतिरिक्त जानकारी (वैकल्पिक)") },
                            minLines = 2,
                            maxLines = 4,
                            enabled = !reportState.isSubmitting
                        )
                        if (reportState.error != null) {
                            Text(reportState.error.orEmpty(), color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            },
            confirmButton = {
                if (reportState.isSubmitted) {
                    TextButton(onClick = { reportDialogOpen = false }) { Text("Done") }
                } else {
                    TextButton(
                        enabled = current != null && !reportState.isSubmitting &&
                            (reportTargetType != "user" || video.userId.isNotBlank()) &&
                            (reportTargetType != "comment" || reportTargetId.isNotBlank()),
                        onClick = {
                            val reporter = current ?: return@TextButton
                            val targetId = when (reportTargetType) {
                                "user" -> video.userId
                                "comment" -> reportTargetId
                                else -> video.id
                            }
                            reportViewModel.submit(
                                reporter,
                                reportTargetType,
                                targetId,
                                reportReason,
                                reportDetails.trim().takeIf { it.isNotBlank() }
                            )
                        }
                    ) { Text(if (reportState.isSubmitting) "Sending…" else "Submit report") }
                }
            },
            dismissButton = {
                if (!reportState.isSubmitted) {
                    TextButton(onClick = { reportDialogOpen = false }, enabled = !reportState.isSubmitting) {
                        Text("Cancel")
                    }
                }
            }
        )
    }
    if (giftDialogOpen) {
        val giftOptions = listOf(
            "Rose" to 1, "Coffee" to 5, "Heart" to 10, "Glasses" to 20,
            "TikTok" to 50, "Car" to 100, "Ring" to 200, "Yacht" to 500,
            "Lion" to 1000, "Diamond" to 1500, "Crown" to 2000, "Jet" to 3000,
            "Castle" to 5000, "Dragon" to 10000, "Galaxy" to 20000, "Universe" to 50000
        )
        AlertDialog(
            onDismissRequest = { if (!giftBusy) giftDialogOpen = false },
            title = { Text("Send a Gift") },
            text = {
                Column(Modifier.fillMaxWidth()) {
                    Text(if (giftBalance >= 0) "Available coins: $giftBalance" else "Wallet balance checking…")
                    if (giftMessage != null) {
                        Text(giftMessage.orEmpty(), color = if (giftMessage.orEmpty().startsWith("Gift sent")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                    }
                    LazyColumn(Modifier.heightIn(max = 300.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        items(giftOptions) { option ->
                            val (giftName, coins) = option
                            TextButton(
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !giftBusy && giftBalance >= coins,
                                onClick = {
                                    val current = session ?: return@TextButton
                                    giftBusy = true
                                    giftMessage = null
                                    scope.launch {
                                        try {
                                            val result = NativeSupabase.invokeFunction(
                                                "video-gift",
                                                JSONObject()
                                                    .put("action", "send")
                                                    .put("videoId", video.id)
                                                    .put("giftName", giftName)
                                                    .put("coins", coins)
                                                    .put("requestId", UUID.randomUUID().toString()),
                                                current
                                            )
                                            if (!result.optBoolean("success")) {
                                                throw IllegalStateException(result.optString("error", "Gift नहीं भेजा जा सका।"))
                                            }
                                            giftMessage = "Gift sent: $giftName · $coins coins"
                                            val balance = NativeSupabase.invokeFunction(
                                                "video-gift", JSONObject().put("action", "balance"), current
                                            )
                                            if (balance.optBoolean("success")) giftBalance = balance.optInt("balance", giftBalance)
                                        } catch (e: Exception) {
                                            giftMessage = e.message ?: "Gift भेजना असफल हुआ।"
                                        } finally { giftBusy = false }
                                    }
                                }
                            ) {
                                Text("$giftName · $coins coins")
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = !giftBusy, onClick = { giftDialogOpen = false }) { Text("Close") }
            }
        )
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
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                                ) {
                                    Text(row.optString("created_at"), style = MaterialTheme.typography.labelSmall)
                                    TextButton(
                                        enabled = session != null && !reportState.isSubmitting,
                                        onClick = {
                                            reportTargetType = "comment"
                                            reportTargetId = row.optString("id")
                                            reportReason = "harassment"
                                            reportDetails = ""
                                            reportViewModel.reset()
                                            reportDialogOpen = true
                                        }
                                    ) { Text("Report comment") }
                                }
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
    private suspend fun blockedCreatorIds(session: NativeSession?): Set<String> {
        if (session == null) return emptySet()
        val rows = NativeSupabase.get(
            "/rest/v1/user_blocks?select=blocked_id&blocker_id=eq.${Uri.encode(session.userId)}&limit=1000",
            session
        )
        return (0 until rows.length()).mapNotNull { index ->
            rows.optJSONObject(index)?.optString("blocked_id")?.takeIf { it.isNotBlank() }
        }.toSet()
    }

    suspend fun search(term: String, session: NativeSession?): List<NativeVideo> = withContext(Dispatchers.IO) {
        val safeTerm = term.trim().take(100)
        require(safeTerm.length >= 2) { "कम से कम 2 अक्षर लिखें।" }
        val pattern = Uri.encode("(caption.ilike.*$safeTerm*,username.ilike.*$safeTerm*,category.ilike.*$safeTerm*)")
        val rows = NativeSupabase.get(
            "/rest/v1/videos?select=id,caption,username,category,likes_count,views_count,video_type,user_id&visibility=eq.public&or=$pattern&order=created_at.desc&limit=30",
            session
        )
        if (rows.length() == 0) return@withContext emptyList()
        val blockedIds = blockedCreatorIds(session)
        val videoIds = JSONArray()
        val byId = LinkedHashMap<String, JSONObject>()
        for (index in 0 until rows.length()) {
            val row = rows.optJSONObject(index) ?: continue
            val id = row.optString("id")
            if (id.isNotBlank() && row.optString("user_id") !in blockedIds) {
                byId[id] = row
                videoIds.put(id)
            }
        }
        if (videoIds.length() == 0) return@withContext emptyList()
        val resolved = post("$API_BASE/functions/v1/video-url", JSONObject().put("video_ids", videoIds), session) as? JSONObject
            ?: throw IllegalStateException("Video search playback response invalid.")
        if (!resolved.optBoolean("ok")) throw IllegalStateException("Video search playback URL resolve नहीं हुआ।")
        val urls = resolved.optJSONObject("urls") ?: JSONObject()
        byId.mapNotNull { (id, row) ->
            val playback = urls.optString(id).takeIf { it.startsWith("https://") } ?: return@mapNotNull null
            NativeVideo(id, playback, row.optString("caption"), row.optString("username"),
                row.optString("category"), row.optInt("likes_count"), row.optInt("views_count"),
                row.optString("video_type").ifBlank { "short" }, row.optString("user_id"))
        }
    }

    const val PAGE_SIZE = 50

    suspend fun load(
        category: String,
        videoType: String? = null,
        session: NativeSession? = null,
        offset: Int = 0
    ): List<NativeVideo> = withContext(Dispatchers.IO) {
        val safeOffset = offset.coerceAtLeast(0)
        val args = JSONObject().put("p_limit", PAGE_SIZE).put("p_offset", safeOffset)
            .put("p_video_type", videoType ?: JSONObject.NULL)
            .put("p_category", if (category == "All") JSONObject.NULL else category)
        var sourceRows = emptyList<JSONObject>()
        var rpcFailure: Exception? = null

        try {
            val response = post("$API_BASE/rest/v1/rpc/get_recommended_video_feed", args, session) as? JSONArray
                ?: throw IllegalStateException("Feed response invalid.")
            sourceRows = (0 until response.length()).mapNotNull { response.optJSONObject(it) }
        } catch (error: Exception) {
            rpcFailure = error
        }

        // Match the web feed's recovery path: a failed or empty recommendation RPC
        // falls back to published, public, all-audience videos instead of blanking Home.
        if (sourceRows.isEmpty()) {
            val fields = "id,caption,username,category,likes_count,views_count,video_type,user_id,created_at"
            val filters = "&or=${Uri.encode("(processing_status.eq.published,processing_status.is.null)")}" +
                "&or=${Uri.encode("(visibility.eq.public,visibility.is.null)")}" +
                "&or=${Uri.encode("(audience_type.eq.all,audience_type.is.null)")}"
            try {
                val direct = NativeSupabase.get(
                    "/rest/v1/videos?select=$fields$filters&order=created_at.desc&limit=$PAGE_SIZE&offset=$safeOffset",
                    session
                )
                sourceRows = (0 until direct.length()).mapNotNull { direct.optJSONObject(it) }
                    .filter { row ->
                        val matchesCategory = category == "All" ||
                            row.optString("category").equals(category, ignoreCase = true)
                        val type = row.optString("video_type").ifBlank { "short" }
                        val matchesType = videoType == null || type.equals(videoType, ignoreCase = true)
                        matchesCategory && matchesType
                    }
            } catch (directError: Exception) {
                if (rpcFailure != null) {
                    throw IllegalStateException(
                        "Recommended feed unavailable: ${rpcFailure.message}. Direct feed fallback failed: ${directError.message}",
                        directError
                    )
                }
                throw directError
            }
        }

        if (sourceRows.isEmpty()) return@withContext emptyList()
        val blockedIds = blockedCreatorIds(session)
        sourceRows = sourceRows.filter { it.optString("user_id") !in blockedIds }
        if (sourceRows.isEmpty()) return@withContext emptyList()
        val byId = LinkedHashMap<String, JSONObject>()
        val ids = JSONArray()
        for (row in sourceRows) {
            val id = row.optString("id")
            if (id.isNotBlank() && !byId.containsKey(id)) {
                byId[id] = row
                ids.put(id)
            }
        }
        if (ids.length() == 0) return@withContext emptyList()
        val resolved = post("$API_BASE/functions/v1/video-url", JSONObject().put("video_ids", ids), session) as? JSONObject
            ?: throw IllegalStateException("Video URL response invalid.")
        if (!resolved.optBoolean("ok")) throw IllegalStateException("वीडियो एक्सेस सत्यापित नहीं हुआ।")
        val urls = resolved.optJSONObject("urls") ?: JSONObject()
        byId.mapNotNull { (id, row) ->
            val playback = urls.optString(id).takeIf { it.startsWith("https://") } ?: return@mapNotNull null
            NativeVideo(id, playback, row.optString("caption").takeUnless { it == "null" }.orEmpty(),
                row.optString("username").takeUnless { it == "null" }.orEmpty(),
                row.optString("category").takeUnless { it == "null" }.orEmpty(),
                row.optInt("likes_count"), row.optInt("views_count"),
                row.optString("video_type").ifBlank { "short" }, row.optString("user_id"))
        }
    }

    suspend fun loadFollowing(session: NativeSession, offset: Int = 0): List<NativeVideo> = withContext(Dispatchers.IO) {
        val safeOffset = offset.coerceAtLeast(0)
        val followRows = NativeSupabase.get(
            "/rest/v1/followers?select=following_id&follower_id=eq." + Uri.encode(session.userId) + "&limit=500",
            session
        )
        val creatorIds = (0 until followRows.length()).mapNotNull {
            followRows.optJSONObject(it)?.optString("following_id")?.takeIf { id -> id.isNotBlank() }
        }.distinct()
        if (creatorIds.isEmpty()) return@withContext emptyList()
        val blockedIds = blockedCreatorIds(session)
        val visibleCreatorIds = creatorIds.filterNot { it in blockedIds }
        if (visibleCreatorIds.isEmpty()) return@withContext emptyList()
        val idsFilter = Uri.encode("(" + visibleCreatorIds.joinToString(",") + ")")
        val rows = NativeSupabase.get(
            "/rest/v1/videos?select=id,caption,username,category,likes_count,views_count,video_type,user_id&user_id=in." + idsFilter + "&visibility=eq.public&order=created_at.desc,id.desc&limit=$PAGE_SIZE&offset=$safeOffset",
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
                row.optString("video_type").ifBlank { "short" }, row.optString("user_id"))
        }
    }
    suspend fun loadSharedVideo(videoId: String, session: NativeSession?): NativeVideo = withContext(Dispatchers.IO) {
        require(videoId.matches(Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89aAbB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}"))) {
            "Shared video ID अमान्य है।"
        }
        val filters = "&or=" + Uri.encode("(processing_status.eq.published,processing_status.is.null)") +
            "&or=" + Uri.encode("(audience_type.eq.all,audience_type.is.null)")
        val rows = NativeSupabase.get(
            "/rest/v1/videos?select=id,caption,username,category,likes_count,views_count,video_type,user_id&id=eq." +
                Uri.encode(videoId) + "&visibility=eq.public" + filters + "&limit=1",
            session
        )
        val row = rows.optJSONObject(0)
            ?: throw IllegalStateException("यह वीडियो उपलब्ध नहीं है या सार्वजनिक नहीं है।")
        val ids = JSONArray().put(videoId)
        val resolved = post("$API_BASE/functions/v1/video-url", JSONObject().put("video_ids", ids), session) as? JSONObject
            ?: throw IllegalStateException("Secure video response invalid.")
        if (!resolved.optBoolean("ok")) {
            throw IllegalStateException(resolved.optString("error", "वीडियो access verify नहीं हुआ।"))
        }
        val playback = resolved.optJSONObject("urls")?.optString(videoId)
            ?.takeIf { it.startsWith("https://") }
            ?: throw IllegalStateException("वीडियो अभी उपलब्ध नहीं है, private है, या processing में है।")
        NativeVideo(
            videoId,
            playback,
            row.optString("caption").takeUnless { it == "null" }.orEmpty(),
            row.optString("username").takeUnless { it == "null" }.orEmpty(),
            row.optString("category").takeUnless { it == "null" }.orEmpty(),
            row.optInt("likes_count"),
            row.optInt("views_count"),
            row.optString("video_type").ifBlank { "short" },
            row.optString("user_id")
        )
    }

    private fun post(endpoint: String, body: JSONObject, session: NativeSession? = null): Any {
        val connection = URL(endpoint).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"; connection.connectTimeout = 15000; connection.readTimeout = 25000
            connection.doOutput = true
            connection.setRequestProperty("apikey", PUBLIC_API_KEY)
            if (session != null) connection.setRequestProperty("Authorization", "Bearer " + session.accessToken)
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
