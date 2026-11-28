package com.newstar.live

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.webrtc.SurfaceViewRenderer

@Composable
internal fun NativeLiveSfuViewer(session: NativeSession, roomId: String) {
    val context = LocalContext.current
    var subscriber by remember(roomId, session.userId) {
        mutableStateOf<NativeSfuSubscriber?>(null)
    }
    var loading by remember(roomId, session.userId) { mutableStateOf(false) }
    var notice by remember(roomId, session.userId) { mutableStateOf("LIVE guest media की प्रतीक्षा है…") }
    val currentSubscriber by rememberUpdatedState(subscriber)

    LaunchedEffect(roomId, session.userId) {
        while (true) {
            val active = subscriber
            if (active == null) {
                loading = true
                try {
                    subscriber = NativeSfuSubscriber.start(context, roomId, session)
                    notice = "LIVE guest media connected."
                } catch (error: Exception) {
                    notice = error.message ?: "LIVE guest media अभी उपलब्ध नहीं है।"
                } finally {
                    loading = false
                }
            } else {
                try {
                    val added = active.refreshPublications()
                    if (added > 0) notice = "${added} नए LIVE video track जोड़े गए।"
                } catch (error: Exception) {
                    notice = error.message ?: "LIVE guest media refresh नहीं हो पाया।"
                }
            }
            delay(5_000)
        }
    }

    DisposableEffect(roomId, session.userId) {
        onDispose {
            val active = currentSubscriber
            if (active != null) {
                CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                    runCatching { active.stop() }
                }
            }
        }
    }

    val active = subscriber
    val midsState = active?.remoteVideoMids?.collectAsState()
    val ownersState = active?.remoteVideoOwners?.collectAsState()
    val mids = midsState?.value.orEmpty()
    val owners = ownersState?.value.orEmpty()

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Guest cameras", style = MaterialTheme.typography.titleMedium)
        if (loading) CircularProgressIndicator()
        if (notice.isNotBlank()) {
            Text(notice, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        mids.forEach { mid ->
            val renderer = remember(active, mid) { SurfaceViewRenderer(context) }
            DisposableEffect(active, mid, renderer) {
                active?.attachRenderer(mid, renderer)
                onDispose {
                    active?.detachRenderer(mid, renderer)
                    runCatching { renderer.release() }
                }
            }
            val owner = owners[mid]
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                if (owner != null) {
                    Text("Guest · ${owner.userId.take(8)}", style = MaterialTheme.typography.labelSmall)
                }
                AndroidView(factory = { renderer }, modifier = Modifier.fillMaxWidth().height(210.dp))
            }
        }
    }
}
