package com.newstar.live

import android.net.Uri
import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.json.JSONObject

@Composable
fun NativeNotificationsScreen(session: NativeSession, onOpenCreator: (String) -> Unit) {
    var notifications by remember(session.userId) { mutableStateOf<List<JSONObject>>(emptyList()) }
    var actorProfiles by remember(session.userId) { mutableStateOf<Map<String, JSONObject>>(emptyMap()) }
    var loading by remember(session.userId) { mutableStateOf(true) }
    var busy by remember(session.userId) { mutableStateOf(false) }
    var error by remember(session.userId) { mutableStateOf("") }
    var refresh by remember(session.userId) { mutableIntStateOf(0) }
    var realtimeStatus by remember(session.userId) { mutableStateOf("Realtime connecting…") }
    val context = LocalContext.current
    var notificationPermissionGranted by remember { mutableStateOf(NativeSystemNotifications.hasPermission(context)) }
    val notificationPermissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted -> notificationPermissionGranted = granted }
    val scope = rememberCoroutineScope()

    DisposableEffect(context) {
        NativeSystemNotifications.createChannel(context)
        onDispose { }
    }
    val unreadCount = notifications.count { !it.optBoolean("is_read", false) }

    DisposableEffect(session.userId, session.accessToken) {
        val realtime = NativeRealtimeMessages(
            accessToken = session.accessToken,
            userId = session.userId,
            onChange = { scope.launch { refresh++ } },
            onStatus = { status -> scope.launch { realtimeStatus = status } }
        )
        realtime.connect()
        onDispose { realtime.close() }
    }

    LaunchedEffect(session.userId, refresh) {
        loading = true
        error = ""
        try {
            val rows = NativeSupabase.get(
                "/rest/v1/notifications?select=id,user_id,actor_id,type,message,is_read,created_at&user_id=eq.${Uri.encode(session.userId)}&order=created_at.desc&limit=100",
                session
            )
            val loaded = (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }
            notifications = loaded
            val actorIds = loaded.map { it.optString("actor_id") }
                .filter { it.isNotBlank() && it != session.userId }.distinct()
            actorProfiles = if (actorIds.isEmpty()) emptyMap() else {
                val ids = Uri.encode("(${actorIds.joinToString(",")})")
                val profiles = NativeSupabase.get(
                    "/rest/v1/creator_public_profiles?select=user_id,username,full_name,avatar_url&user_id=in.$ids",
                    session
                )
                (0 until profiles.length()).mapNotNull { profiles.optJSONObject(it) }
                    .associateBy { it.optString("user_id") }
            }

        } catch (e: Exception) {
            error = e.message ?: "Notifications लोड नहीं हुईं।"
        } finally {
            loading = false
        }
    }

    fun openNotification(notification: JSONObject) {
        scope.launch {
            val id = notification.optString("id")
            val actorId = notification.optString("actor_id")
            if (id.isNotBlank() && !notification.optBoolean("is_read", false)) {
                busy = true
                error = ""
                try {
                    NativeSupabase.patch(
                        "/rest/v1/notifications?id=eq.${Uri.encode(id)}&user_id=eq.${Uri.encode(session.userId)}",
                        JSONObject().put("is_read", true),
                        session
                    )
                    notifications = notifications.map {
                        if (it.optString("id") == id) JSONObject(it.toString()).put("is_read", true) else it
                    }
                } catch (e: Exception) {
                    error = e.message ?: "Notification read mark नहीं हुई।"
                } finally {
                    busy = false
                }
            }
            if (actorId.isNotBlank() && actorId != session.userId) {
                onOpenCreator(actorId)
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text("Notifications", style = MaterialTheme.typography.headlineSmall)
                Text("$unreadCount unread · $realtimeStatus",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column {
                if (Build.VERSION.SDK_INT >= 33 && !notificationPermissionGranted) {
                    OutlinedButton(
                        onClick = {
                            notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                        },
                        enabled = !busy
                    ) { Text("Enable alerts") }
                }
                OutlinedButton(onClick = { refresh++ }, enabled = !loading && !busy) { Text("Refresh") }
                Button(
                    onClick = {
                        scope.launch {
                            busy = true
                            error = ""
                            try {
                                NativeSupabase.patch(
                                    "/rest/v1/notifications?user_id=eq.${Uri.encode(session.userId)}&is_read=eq.false",
                                    JSONObject().put("is_read", true),
                                    session
                                )
                                notifications = notifications.map {
                                    JSONObject(it.toString()).put("is_read", true)
                                }
                            } catch (e: Exception) {
                                error = e.message ?: "सभी Notifications read mark नहीं हुईं।"
                            } finally {
                                busy = false
                            }
                        }
                    },
                    enabled = unreadCount > 0 && !busy && !loading
                ) { Text("Mark all read") }
            }
        }
        if (loading) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            CircularProgressIndicator()
        }
        if (error.isNotBlank()) {
            Text(error, color = MaterialTheme.colorScheme.error)
        }
        if (!loading && notifications.isEmpty() && error.isBlank()) {
            Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.large) {
                Text("अभी कोई Notification नहीं है। नई activity आने पर यहाँ दिखाई देगी।",
                    Modifier.fillMaxWidth().padding(20.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(notifications, key = { it.optString("id") }) { notification ->
                val actor = actorProfiles[notification.optString("actor_id")]
                val actorName = actor?.optString("full_name").orEmpty()
                    .ifBlank { actor?.optString("username").orEmpty() }
                val read = notification.optBoolean("is_read", false)
                Card(
                    modifier = Modifier.fillMaxWidth().clickable(enabled = !busy) {
                        openNotification(notification)
                    },
                    colors = CardDefaults.cardColors(
                        containerColor = if (read) MaterialTheme.colorScheme.surface
                        else MaterialTheme.colorScheme.secondaryContainer
                    )
                ) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(
                                actorName.ifBlank { notification.optString("type").ifBlank { "New Star" } },
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(if (read) "Read" else "NEW",
                                color = if (read) MaterialTheme.colorScheme.onSurfaceVariant
                                else MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.labelSmall)
                        }
                        Text(notification.optString("message"), style = MaterialTheme.typography.bodyMedium)
                        Text(notification.optString("created_at"),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (notification.optString("actor_id").isNotBlank() &&
                            notification.optString("actor_id") != session.userId
                        ) {
                            Text("Tap to open creator profile", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary)
                        } else if (!read) {
                            Text("Tap to mark as read", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
    }
}
