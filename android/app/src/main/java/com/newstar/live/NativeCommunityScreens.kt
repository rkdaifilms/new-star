package com.newstar.live

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.webrtc.SurfaceViewRenderer
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import org.json.JSONObject

@Composable
fun NativeFanSupportScreen(
    session: NativeSession,
    onOpenCreator: (String) -> Unit
) {
    var fans by remember(session.userId) { mutableStateOf<List<JSONObject>>(emptyList()) }
    var following by remember(session.userId) { mutableStateOf<List<JSONObject>>(emptyList()) }
    var selectedTab by remember(session.userId) { mutableIntStateOf(0) }
    var loading by remember(session.userId) { mutableStateOf(true) }
    var error by remember(session.userId) { mutableStateOf("") }
    var refreshKey by remember(session.userId) { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(session.userId, refreshKey) {
        loading = true
        error = ""
        try {
            val followerRows = NativeSupabase.get(
                "/rest/v1/followers?select=follower_id,created_at&following_id=eq.${Uri.encode(session.userId)}&order=created_at.desc&limit=200",
                session
            )
            val followingRows = NativeSupabase.get(
                "/rest/v1/followers?select=following_id,created_at&follower_id=eq.${Uri.encode(session.userId)}&order=created_at.desc&limit=200",
                session
            )
            val fanIds = (0 until followerRows.length()).mapNotNull {
                followerRows.optJSONObject(it)?.optString("follower_id")?.takeIf(String::isNotBlank)
            }
            val followingIds = (0 until followingRows.length()).mapNotNull {
                followingRows.optJSONObject(it)?.optString("following_id")?.takeIf(String::isNotBlank)
            }
            val allIds = (fanIds + followingIds).distinct()
            val profiles = if (allIds.isEmpty()) emptyList() else {
                val inFilter = Uri.encode("(${allIds.joinToString(",")})")
                val rows = NativeSupabase.get(
                    "/rest/v1/creator_public_profiles?select=user_id,username,full_name,avatar_url,is_private&user_id=in.$inFilter",
                    session
                )
                (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }
            }
            val byId = profiles.associateBy { it.optString("user_id") }
            fun profileList(ids: List<String>) = ids.map { id ->
                byId[id] ?: JSONObject()
                    .put("user_id", id)
                    .put("username", "Private / unavailable creator")
            }
            fans = profileList(fanIds)
            following = profileList(followingIds)
        } catch (e: Exception) {
            error = e.message ?: "Community list लोड नहीं हुई।"
        } finally {
            loading = false
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("⭐ Creator Fan Support", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(
                    "यह मुफ्त community support है। यहाँ आपके वास्तविक followers और आपके द्वारा follow किए गए creators दिखते हैं। कोई paid membership, coin transfer या earning सुविधा अभी उपलब्ध नहीं है।",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CommunityCountCard("Your Fans", fans.size, Modifier.weight(1f))
                    CommunityCountCard("You Support", following.size, Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { refreshKey++ }, enabled = !loading) { Text("Refresh") }
                }
            }
        }
        TabRow(selectedTabIndex = selectedTab) {
            Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text("Your Fans") })
            Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text("You Support") })
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        val rows = if (selectedTab == 0) fans else following
        if (!loading && error.isBlank() && rows.isEmpty()) {
            Text(
                if (selectedTab == 0) "अभी आपका कोई follower नहीं है।" else "आपने अभी किसी creator को follow नहीं किया है।",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(rows, key = { it.optString("user_id") }) { profile ->
                val id = profile.optString("user_id")
                Card(Modifier.fillMaxWidth().clickable(enabled = id.isNotBlank() && profile.optString("username") != "Private / unavailable creator") {
                    onOpenCreator(id)
                }) {
                    Row(
                        Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        AsyncImage(
                            model = profile.optString("avatar_url").takeIf { it.startsWith("https://") },
                            contentDescription = "Creator avatar",
                            modifier = Modifier.size(46.dp).clip(CircleShape)
                        )
                        Column(Modifier.weight(1f)) {
                            Text(
                                profile.optString("full_name").ifBlank {
                                    profile.optString("username").ifBlank { "New Star Creator" }
                                },
                                fontWeight = FontWeight.SemiBold
                            )
                            val username = profile.optString("username")
                            if (username.isNotBlank() && username != "Private / unavailable creator") {
                                Text("@$username", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            } else {
                                Text("Private / unavailable creator", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        if (id.isNotBlank() && profile.optString("username") != "Private / unavailable creator") {
                            Text("›", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CommunityCountCard(label: String, count: Int, modifier: Modifier = Modifier) {
    Card(modifier) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Text(count.toString(), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun NativeLiveCameraPreview(
    publisher: NativeLivePublisher,
    modifier: Modifier = Modifier
) {
    var renderer by remember { mutableStateOf<SurfaceViewRenderer?>(null) }
    AndroidView(
        factory = { viewContext ->
            SurfaceViewRenderer(viewContext).also { renderer = it }
        },
        modifier = modifier
    )
    val activeRenderer = renderer
    DisposableEffect(activeRenderer, publisher) {
        if (activeRenderer != null) publisher.attachPreview(activeRenderer)
        onDispose {
            if (activeRenderer != null) {
                publisher.detachPreview(activeRenderer)
                runCatching { activeRenderer.release() }
            }
        }
    }
}

@androidx.compose.runtime.Composable
internal fun NativeLiveHostControls(session: NativeSession) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var title by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
    var seats by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(1) }
    var busy by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var notice by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
    var roomId by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    var publisher by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<NativeLivePublisher?>(null) }
    var micEnabled by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(true) }
    var cameraEnabled by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(true) }
    var pkStatus by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("idle") }
    var pkMatchId by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
    var pkOpponentName by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
    var pkOwnScore by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(0) }
    var pkOpponentScore by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(0) }
    var pkBusy by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var pkNotice by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
    var sfuConfigured by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<Boolean?>(null) }
    var sfuMessage by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("SFU configuration अभी check नहीं हुई है।") }
    var sfuBusy by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var sfuSubscriber by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<NativeSfuSubscriber?>(null) }
    var subscriberBusy by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var subscriberNotice by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
    val currentPublisher by androidx.compose.runtime.rememberUpdatedState(publisher)
    val currentSubscriber by androidx.compose.runtime.rememberUpdatedState(sfuSubscriber)
    val currentRoomId by androidx.compose.runtime.rememberUpdatedState(roomId)
    val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val cameraGranted = permissions[android.Manifest.permission.CAMERA] == true ||
            androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        val microphoneGranted = permissions[android.Manifest.permission.RECORD_AUDIO] == true ||
            androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!cameraGranted) {
            notice = "LIVE शुरू करने के लिए Camera permission आवश्यक है।"
        } else {
            scope.launch {
                busy = true
                var preparedRoom: String? = null
                var createdPublisher: NativeLivePublisher? = null
                try {
                    val prepared = NativeSupabase.invokeFunction("cloudflare-live", org.json.JSONObject()
                        .put("action", "prepare").put("room_title", title.trim())
                        .put("room_type", "live").put("seat_capacity", seats), session)
                    if (!prepared.optBoolean("ok")) throw IllegalStateException(prepared.optString("error", "LIVE room तैयार नहीं हुआ।"))
                    preparedRoom = prepared.getString("room_id")
                    val sfuHealth = NativeLiveRepository.getSfuHealth(session)
                    val useSfu = sfuHealth.optBoolean("configured")
                    if (useSfu) {
                        val confirmed = NativeSupabase.invokeFunction("cloudflare-live",
                            org.json.JSONObject().put("action", "confirm").put("room_id", preparedRoom)
                                .put("camera_enabled", true).put("microphone_enabled", microphoneGranted), session)
                        if (!confirmed.optBoolean("ok")) throw IllegalStateException(confirmed.optString("error", "LIVE room confirm नहीं हुआ।"))
                        val sfuPublisher = NativeSfuPublisher.start(context, preparedRoom, session, microphoneGranted)
                        createdPublisher = sfuPublisher
                        roomId = preparedRoom
                        publisher = sfuPublisher
                        micEnabled = microphoneGranted
                        cameraEnabled = true
                        notice = if (microphoneGranted) "आप Cloudflare Realtime SFU पर LIVE हैं। Camera और microphone publish हो रहे हैं।"
                            else "आप Cloudflare Realtime SFU पर LIVE हैं। केवल camera publish हो रहा है।"
                    } else {
                        val whipPublisher = NativeWhipPublisher.start(context, prepared.getString("whip_url"), microphoneGranted)
                        createdPublisher = whipPublisher
                        val confirmed = NativeSupabase.invokeFunction("cloudflare-live",
                            org.json.JSONObject().put("action", "confirm").put("room_id", preparedRoom)
                                .put("camera_enabled", true).put("microphone_enabled", microphoneGranted), session)
                        if (!confirmed.optBoolean("ok")) throw IllegalStateException(confirmed.optString("error", "LIVE room confirm नहीं हुआ।"))
                        roomId = preparedRoom
                        publisher = whipPublisher
                        micEnabled = microphoneGranted
                        cameraEnabled = true
                        notice = if (microphoneGranted) "आप Cloudflare Stream WHIP पर LIVE हैं। Camera और microphone broadcast हो रहे हैं।"
                            else "आप LIVE हैं। Microphone permission नहीं मिली, इसलिए केवल camera broadcast हो रहा है।"
                    }
                } catch (e: Exception) {
                    runCatching { createdPublisher?.stop() }
                    preparedRoom?.let { id ->
                        runCatching { NativeSupabase.invokeFunction("cloudflare-live",
                            org.json.JSONObject().put("action", "stop").put("room_id", id), session) }
                    }
                    notice = e.message ?: "Native LIVE शुरू नहीं हुआ।"
                } finally { busy = false }
            }
        }
    }


    androidx.compose.runtime.LaunchedEffect(roomId) {
        val activeRoom = roomId ?: return@LaunchedEffect
        while (true) {
            try {
                val status = NativeLiveRepository.getPkStatus(activeRoom, session)
                val next = status.optString("status", "idle")
                pkStatus = next
                pkMatchId = status.optString("match_id")
                pkOpponentName = status.optString("opponent_name")
                pkOwnScore = status.optInt("own_score")
                pkOpponentScore = status.optInt("opponent_score")
                if (next == "idle") {
                    pkMatchId = ""
                    pkOpponentName = ""
                    pkOwnScore = 0
                    pkOpponentScore = 0
                }
                if (pkNotice.startsWith("Battle status")) pkNotice = ""
            } catch (e: Exception) {
                pkNotice = "Battle status: " + (e.message ?: "अभी उपलब्ध नहीं है।")
            }
            kotlinx.coroutines.delay(3500)
        }
    }

    androidx.compose.runtime.DisposableEffect(session.userId) {
        onDispose {
            val activePublisher = currentPublisher
            val activeRoom = currentRoomId
            if (activePublisher != null || activeRoom != null) {
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO).launch {
                    runCatching { activePublisher?.stop() }
                    runCatching { currentSubscriber?.stop() }
                    if (activeRoom != null) runCatching {
                        NativeSupabase.invokeFunction("cloudflare-live",
                            org.json.JSONObject().put("action", "stop").put("room_id", activeRoom), session)
                    }
                }
            }
        }
    }

    androidx.compose.material3.Card(androidx.compose.ui.Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        androidx.compose.foundation.layout.Column(
            androidx.compose.ui.Modifier.padding(12.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)
        ) {
            androidx.compose.material3.Text("Start Native Video LIVE", style = androidx.compose.material3.MaterialTheme.typography.titleLarge)
            androidx.compose.material3.Text("100% native Camera2 + WebRTC WHIP publishing to Cloudflare Stream. LIVE eligibility is verified by the server.")
            if (roomId == null) {
                androidx.compose.material3.OutlinedTextField(
                    value = title, onValueChange = { title = it.take(120) },
                    label = { androidx.compose.material3.Text("LIVE room title") },
                    modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
                    singleLine = true
                )
                androidx.compose.material3.Text("Guest seat capacity")
                androidx.compose.foundation.layout.Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                    listOf(1, 4, 10).forEach { count ->
                        androidx.compose.material3.FilterChip(
                            selected = seats == count, onClick = { seats = count },
                            label = { androidx.compose.material3.Text("$count seats") }
                        )
                    }
                }
                androidx.compose.material3.Button(
                    enabled = !busy && title.isNotBlank(),
                    onClick = {
                        permissionLauncher.launch(arrayOf(android.Manifest.permission.CAMERA, android.Manifest.permission.RECORD_AUDIO))
                    },
                    modifier = androidx.compose.ui.Modifier.fillMaxWidth()
                ) {
                    if (busy) androidx.compose.material3.CircularProgressIndicator(
                        modifier = androidx.compose.ui.Modifier.size(18.dp), strokeWidth = 2.dp
                    ) else androidx.compose.material3.Text("🔴 Go LIVE Now")
                }
            } else {
                publisher?.let { activePublisher ->
                    NativeLiveCameraPreview(
                        publisher = activePublisher,
                        modifier = androidx.compose.ui.Modifier.fillMaxWidth().height(300.dp)
                    )
                }
                androidx.compose.material3.Text("● LIVE · Room " + roomId!!.take(8),
                    color = androidx.compose.material3.MaterialTheme.colorScheme.primary,
                    style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
                androidx.compose.foundation.layout.Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                    androidx.compose.material3.OutlinedButton(onClick = {
                        micEnabled = !micEnabled
                        publisher?.setMicrophoneEnabled(micEnabled)
                    }) { androidx.compose.material3.Text(if (micEnabled) "Mic ON" else "Mic OFF") }
                    androidx.compose.material3.OutlinedButton(onClick = {
                        cameraEnabled = !cameraEnabled
                        publisher?.setCameraEnabled(cameraEnabled)
                    }) { androidx.compose.material3.Text(if (cameraEnabled) "Camera ON" else "Camera OFF") }
                }

                androidx.compose.material3.Card(androidx.compose.ui.Modifier.fillMaxWidth()) {
                    androidx.compose.foundation.layout.Column(
                        androidx.compose.ui.Modifier.padding(12.dp),
                        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(7.dp)
                    ) {
                        androidx.compose.material3.Text("Friendly LIVE PK Battle", style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
                        androidx.compose.material3.Text(
                            when (pkStatus) {
                                "waiting" -> "Matchmaking queue में हैं। दूसरे LIVE creator की प्रतीक्षा है।"
                                "matched" -> "Opponent: $pkOpponentName"
                                else -> "दूसरे active LIVE creator के साथ friendly matchmaking।"
                            },
                            style = androidx.compose.material3.MaterialTheme.typography.bodySmall
                        )
                        if (pkStatus == "matched") {
                            androidx.compose.material3.Text(
                                "आपके LIVE likes: $pkOwnScore  ·  Opponent likes: $pkOpponentScore",
                                style = androidx.compose.material3.MaterialTheme.typography.titleSmall
                            )
                        }
                        androidx.compose.material3.Button(
                            enabled = !pkBusy && (pkStatus == "idle" || pkStatus == "waiting" || pkStatus == "matched"),
                            onClick = {
                                pkBusy = true
                                pkNotice = ""
                                scope.launch {
                                    try {
                                        when (pkStatus) {
                                            "waiting" -> {
                                                NativeLiveRepository.leavePkQueue(session)
                                                pkStatus = "idle"
                                                pkNotice = "Matchmaking queue छोड़ दी गई।"
                                            }
                                            "matched" -> {
                                                NativeLiveRepository.finishPkMatch(pkMatchId, session)
                                                pkStatus = "idle"
                                                pkMatchId = ""
                                                pkNotice = "Friendly battle समाप्त हुई।"
                                            }
                                            else -> {
                                                val joined = NativeLiveRepository.joinPkQueue(roomId!!, session)
                                                pkStatus = joined.optString("status", "waiting")
                                                pkMatchId = joined.optString("match_id")
                                                pkNotice = if (pkStatus == "matched") "Opponent मिल गया।" else "Matchmaking queue में शामिल हो गए।"
                                            }
                                        }
                                    } catch (e: Exception) {
                                        pkNotice = e.message ?: "PK matchmaking उपलब्ध नहीं है।"
                                    } finally { pkBusy = false }
                                }
                            },
                            modifier = androidx.compose.ui.Modifier.fillMaxWidth()
                        ) {
                            androidx.compose.material3.Text(
                                when {
                                    pkBusy -> "Please wait…"
                                    pkStatus == "waiting" -> "Cancel matchmaking"
                                    pkStatus == "matched" -> "End friendly battle"
                                    else -> "Find LIVE opponent"
                                }
                            )
                        }
                        if (pkNotice.isNotBlank()) androidx.compose.material3.Text(
                            pkNotice,
                            color = if (pkNotice.startsWith("Battle status")) androidx.compose.material3.MaterialTheme.colorScheme.error else androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                            style = androidx.compose.material3.MaterialTheme.typography.bodySmall
                        )
                    }
                }
                androidx.compose.material3.Card(androidx.compose.ui.Modifier.fillMaxWidth()) {
                    androidx.compose.foundation.layout.Column(
                        androidx.compose.ui.Modifier.padding(12.dp),
                        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(7.dp)
                    ) {
                        androidx.compose.material3.Text("Cloudflare Realtime SFU readiness", style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
                        androidx.compose.material3.Text(
                            when (sfuConfigured) {
                                true -> "SFU App ID और App Secret server पर मौजूद हैं। अगला चरण native SDP/media negotiation है।"
                                false -> "SFU credentials server पर configure नहीं हैं। Stream token इन credentials की जगह नहीं ले सकता।"
                                null -> sfuMessage
                            },
                            style = androidx.compose.material3.MaterialTheme.typography.bodySmall
                        )
                        androidx.compose.material3.Button(
                            enabled = !sfuBusy,
                            onClick = {
                                sfuBusy = true
                                scope.launch {
                                    try {
                                        val health = NativeLiveRepository.getSfuHealth(session)
                                        sfuConfigured = health.optBoolean("configured")
                                        sfuMessage = health.optString("message", "SFU status प्राप्त हुआ।")
                                    } catch (e: Exception) {
                                        sfuConfigured = null
                                        sfuMessage = e.message ?: "SFU status जाँचा नहीं जा सका।"
                                    } finally {
                                        sfuBusy = false
                                    }
                                }
                            },
                            modifier = androidx.compose.ui.Modifier.fillMaxWidth()
                        ) {
                            if (sfuBusy) androidx.compose.material3.CircularProgressIndicator(
                                modifier = androidx.compose.ui.Modifier.size(18.dp), strokeWidth = 2.dp
                            ) else androidx.compose.material3.Text("Check SFU server configuration")
                        }
                        if (sfuConfigured == false) {
                            androidx.compose.material3.Text(
                                "Server owner को Supabase Edge Function secrets में CLOUDFLARE_SFU_APP_ID (32 hex) और CLOUDFLARE_SFU_APP_SECRET (64 hex) जोड़ने होंगे। Secret values Android app में कभी न रखें।",
                                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                                color = androidx.compose.material3.MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
                if (sfuConfigured == true) {
                    androidx.compose.material3.Card(androidx.compose.ui.Modifier.fillMaxWidth()) {
                        androidx.compose.foundation.layout.Column(
                            androidx.compose.ui.Modifier.padding(12.dp),
                            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)
                        ) {
                            androidx.compose.material3.Text("Native SFU remote viewer", style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
                            androidx.compose.material3.Text(
                                "इस receiving session में LIVE room के active camera और microphone publications subscribe होंगे। Audio native WebRTC से route होगा।",
                                style = androidx.compose.material3.MaterialTheme.typography.bodySmall
                            )
                            sfuSubscriber?.let { activeSubscriber ->
                                NativeSfuSubscriberPreview(
                                    subscriber = activeSubscriber,
                                    modifier = androidx.compose.ui.Modifier.fillMaxWidth().height(230.dp)
                                )
                            }
                            androidx.compose.material3.Button(
                                enabled = !subscriberBusy,
                                onClick = {
                                    subscriberBusy = true
                                    subscriberNotice = ""
                                    scope.launch {
                                        try {
                                            sfuSubscriber?.stop()
                                            sfuSubscriber = null
                                            sfuSubscriber = NativeSfuSubscriber.start(context, roomId!!, session)
                                            subscriberNotice = "Remote SFU media connected."
                                        } catch (e: Exception) {
                                            subscriberNotice = e.message ?: "SFU remote media connect नहीं हुआ।"
                                        } finally {
                                            subscriberBusy = false
                                        }
                                    }
                                },
                                modifier = androidx.compose.ui.Modifier.fillMaxWidth()
                            ) {
                                if (subscriberBusy) androidx.compose.material3.CircularProgressIndicator(
                                    modifier = androidx.compose.ui.Modifier.size(18.dp), strokeWidth = 2.dp
                                ) else androidx.compose.material3.Text(if (sfuSubscriber == null) "Connect remote SFU media" else "Reconnect remote SFU media")
                            }
                            if (sfuSubscriber != null) {
                                androidx.compose.material3.OutlinedButton(
                                    enabled = !subscriberBusy,
                                    onClick = {
                                        subscriberBusy = true
                                        scope.launch {
                                            try {
                                                sfuSubscriber?.stop()
                                                sfuSubscriber = null
                                                subscriberNotice = "Remote SFU viewer disconnected."
                                            } catch (e: Exception) {
                                                subscriberNotice = e.message ?: "SFU viewer disconnect नहीं हुआ।"
                                            } finally { subscriberBusy = false }
                                        }
                                    },
                                    modifier = androidx.compose.ui.Modifier.fillMaxWidth()
                                ) { androidx.compose.material3.Text("Disconnect remote viewer") }
                            }
                            if (subscriberNotice.isNotBlank()) androidx.compose.material3.Text(
                                subscriberNotice,
                                color = if (subscriberNotice.contains("नहीं") || subscriberNotice.contains("failed", true))
                                    androidx.compose.material3.MaterialTheme.colorScheme.error
                                else androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                                style = androidx.compose.material3.MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
                androidx.compose.material3.Button(
                    enabled = !busy, onClick = {
                        scope.launch {
                            busy = true
                            try {
                                sfuSubscriber?.stop()
                                sfuSubscriber = null
                                publisher?.stop()
                                val r = NativeSupabase.invokeFunction("cloudflare-live",
                                    org.json.JSONObject().put("action", "stop").put("room_id", roomId), session)
                                if (!r.optBoolean("ok")) throw IllegalStateException(r.optString("error", "LIVE बंद नहीं हुआ।"))
                                publisher = null
                                roomId = null
                                notice = "LIVE room सफलतापूर्वक बंद हुआ।"
                            } catch (e: Exception) {
                                notice = e.message ?: "LIVE बंद नहीं हुआ।"
                            } finally { busy = false }
                        }
                    },
                    modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                        containerColor = androidx.compose.material3.MaterialTheme.colorScheme.error
                    )
                ) { androidx.compose.material3.Text("End LIVE") }
            }
            if (notice.isNotBlank()) androidx.compose.material3.Text(notice, style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
        }
    }
}



@androidx.compose.runtime.Composable
private fun NativeSfuSubscriberPreview(
    subscriber: NativeSfuSubscriber,
    modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier
) {
    val mids by subscriber.remoteVideoMids.collectAsState()
    if (mids.isEmpty()) {
        androidx.compose.foundation.layout.Box(
            modifier = modifier.heightIn(min = 180.dp, max = 230.dp),
            contentAlignment = androidx.compose.ui.Alignment.Center
        ) {
            androidx.compose.material3.Text(
                "SFU connected — remote camera tracks आने का इंतज़ार है।",
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    } else {
        androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
            columns = androidx.compose.foundation.lazy.grid.GridCells.Fixed(if (mids.size == 1) 1 else 2),
            modifier = modifier.heightIn(min = 180.dp, max = 460.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(2.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)
        ) {
            items(items = mids, key = { it }) { mid ->
                NativeSfuSubscriberVideoTile(subscriber, mid)
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun NativeSfuSubscriberVideoTile(
    subscriber: NativeSfuSubscriber,
    mid: String
) {
    var renderer by androidx.compose.runtime.remember(subscriber, mid) {
        androidx.compose.runtime.mutableStateOf<org.webrtc.SurfaceViewRenderer?>(null)
    }
    androidx.compose.ui.viewinterop.AndroidView(
        factory = { viewContext ->
            org.webrtc.SurfaceViewRenderer(viewContext).also { view ->
                renderer = view
                subscriber.attachRenderer(mid, view)
            }
        },
        update = { view ->
            if (renderer !== view) renderer = view
            subscriber.attachRenderer(mid, view)
        },
        modifier = androidx.compose.ui.Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
    )
    androidx.compose.runtime.DisposableEffect(subscriber, mid, renderer) {
        onDispose {
            renderer?.let { view ->
                subscriber.detachRenderer(mid, view)
                runCatching { view.release() }
            }
        }
    }
}
