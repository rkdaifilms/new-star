package com.newstar.live

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

@Composable
internal fun NativeLiveGuestSeatPanel(
    roomId: String,
    session: NativeSession,
    seatCapacity: Int
) {
    var participants by remember(roomId) { mutableStateOf<List<JSONObject>>(emptyList()) }
    var loading by remember(roomId) { mutableStateOf(true) }
    var error by remember(roomId) { mutableStateOf("") }
    var notice by remember(roomId) { mutableStateOf("") }
    var busyParticipantId by remember(roomId) { mutableStateOf("") }
    var searchQuery by remember(roomId) { mutableStateOf("") }
    var searchBusy by remember(roomId) { mutableStateOf(false) }
    var searchError by remember(roomId) { mutableStateOf("") }
    var searchResults by remember(roomId) { mutableStateOf<List<NativeLiveCreator>>(emptyList()) }
    var refreshKey by remember(roomId) { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    suspend fun reloadParticipants() {
        try {
            participants = NativeLiveRepository.listParticipants(roomId, session)
            error = ""
        } catch (e: Exception) {
            error = e.message ?: "LIVE guest list लोड नहीं हुई।"
        } finally {
            loading = false
        }
    }

    LaunchedEffect(roomId, refreshKey) {
        loading = participants.isEmpty()
        reloadParticipants()
    }

    LaunchedEffect(roomId) {
        while (true) {
            delay(5_000)
            reloadParticipants()
        }
    }

    val requested = participants.filter { it.optString("status") == "requested" }
    val admitted = participants.filter { it.optString("status") == "admitted" }
    val occupiedSeats = admitted.mapNotNull { participant ->
        participant.optInt("seat_number", -1).takeIf { it > 0 }
    }.toSet()

    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("LIVE guest seats", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        "${admitted.size}/${(seatCapacity - 1).coerceAtLeast(0)} guest seats occupied · ${requested.size} requests",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                OutlinedButton(onClick = { refreshKey++ }, enabled = !loading) {
                    Text("Refresh")
                }
            }

            if (loading) LinearProgressIndicatorForGuestSeats()
            if (error.isNotBlank()) {
                Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            if (notice.isNotBlank()) {
                Text(notice, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
            }

            Text("Seat occupancy", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (2..seatCapacity.coerceAtLeast(1)).forEach { seat ->
                    val occupant = admitted.firstOrNull { it.optInt("seat_number", -1) == seat }
                    Card(Modifier.weight(1f)) {
                        Column(
                            Modifier.fillMaxWidth().padding(8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Text("Seat $seat", style = MaterialTheme.typography.labelSmall)
                            Text(
                                if (occupant == null) "Open" else occupant.optString("display_name").ifBlank { "Guest" },
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 2
                            )
                        }
                    }
                }
            }

            Text("Guest requests", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            if (!loading && requested.isEmpty()) {
                Text("अभी कोई guest seat request नहीं है।", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            requested.forEach { participant ->
                val participantId = participant.optString("id")
                Card(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.fillMaxWidth().padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                participant.optString("display_name").ifBlank { "New Star Creator" },
                                fontWeight = FontWeight.SemiBold
                            )
                            Text("Waiting for host approval", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Button(
                            enabled = busyParticipantId.isBlank() && participantId.isNotBlank() && admitted.size < (seatCapacity - 1).coerceAtLeast(0),
                            onClick = {
                                busyParticipantId = participantId
                                notice = ""
                                scope.launch {
                                    try {
                                        NativeLiveRepository.admitGuest(roomId, participantId, session)
                                        notice = "Guest को seat ${(2..seatCapacity).firstOrNull { it !in occupiedSeats } ?: ""} assign की गई।"
                                        reloadParticipants()
                                    } catch (e: Exception) {
                                        notice = e.message ?: "Guest admit नहीं हुआ।"
                                    } finally {
                                        busyParticipantId = ""
                                    }
                                }
                            }
                        ) {
                            if (busyParticipantId == participantId) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            else Text("Admit")
                        }
                        OutlinedButton(
                            enabled = busyParticipantId.isBlank() && participantId.isNotBlank(),
                            onClick = {
                                busyParticipantId = participantId
                                notice = ""
                                scope.launch {
                                    try {
                                        NativeLiveRepository.removeGuest(roomId, participantId, session)
                                        notice = "Guest request हटा दी गई।"
                                        reloadParticipants()
                                    } catch (e: Exception) {
                                        notice = e.message ?: "Request हटाई नहीं जा सकी।"
                                    } finally {
                                        busyParticipantId = ""
                                    }
                                }
                            }
                        ) { Text("Decline") }
                    }
                }
            }

            Text("Admitted guests", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            if (!loading && admitted.isEmpty()) {
                Text("अभी कोई guest seat पर नहीं है।", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            admitted.forEach { participant ->
                val participantId = participant.optString("id")
                Card(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.fillMaxWidth().padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                participant.optString("display_name").ifBlank { "New Star Creator" },
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                "Seat ${participant.optInt("seat_number")} · ${participant.optString("role").ifBlank { "guest" }}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            val cameraAllowed = participant.optBoolean("camera_allowed")
                            val microphoneAllowed = participant.optBoolean("microphone_allowed")
                            OutlinedButton(
                                enabled = busyParticipantId.isBlank() && participant.optString("user_id").isNotBlank(),
                                onClick = {
                                    busyParticipantId = participantId
                                    scope.launch {
                                        try {
                                            NativeLiveRepository.setMemberPermissions(
                                                roomId = roomId,
                                                targetUserId = participant.optString("user_id"),
                                                cameraAllowed = !cameraAllowed,
                                                session = session
                                            )
                                            notice = if (cameraAllowed) "Guest camera permission बंद की गई।" else "Guest camera permission चालू की गई।"
                                            reloadParticipants()
                                        } catch (e: Exception) {
                                            notice = e.message ?: "Camera permission update नहीं हुई।"
                                        } finally {
                                            busyParticipantId = ""
                                        }
                                    }
                                }
                            ) { Text(if (cameraAllowed) "Camera allowed" else "Allow camera") }
                            OutlinedButton(
                                enabled = busyParticipantId.isBlank() && participant.optString("user_id").isNotBlank(),
                                onClick = {
                                    busyParticipantId = participantId
                                    scope.launch {
                                        try {
                                            NativeLiveRepository.setMemberPermissions(
                                                roomId = roomId,
                                                targetUserId = participant.optString("user_id"),
                                                microphoneAllowed = !microphoneAllowed,
                                                session = session
                                            )
                                            notice = if (microphoneAllowed) "Guest microphone permission बंद की गई।" else "Guest microphone permission चालू की गई।"
                                            reloadParticipants()
                                        } catch (e: Exception) {
                                            notice = e.message ?: "Microphone permission update नहीं हुई।"
                                        } finally {
                                            busyParticipantId = ""
                                        }
                                    }
                                }
                            ) { Text(if (microphoneAllowed) "Mic allowed" else "Allow mic") }
                            OutlinedButton(
                                enabled = busyParticipantId.isBlank() && participantId.isNotBlank(),
                                onClick = {
                                    busyParticipantId = participantId
                                    notice = ""
                                    scope.launch {
                                        try {
                                            NativeLiveRepository.removeGuest(roomId, participantId, session)
                                            notice = "Guest LIVE seat से हटा दिया गया।"
                                            reloadParticipants()
                                        } catch (e: Exception) {
                                            notice = e.message ?: "Guest remove नहीं हुआ।"
                                        } finally {
                                            busyParticipantId = ""
                                        }
                                    }
                                }
                            ) {
                                if (busyParticipantId == participantId) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                else Text("Remove")
                            }
                        }
                    }
                }
            }

            Text("Invite a creator", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it.take(60); searchError = ""; searchResults = emptyList() },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Creator username or name") },
                singleLine = true
            )
            Button(
                modifier = Modifier.fillMaxWidth(),
                enabled = !searchBusy && searchQuery.trim().length >= 2,
                onClick = {
                    searchBusy = true
                    searchError = ""
                    searchResults = emptyList()
                    scope.launch {
                        try {
                            searchResults = NativeLiveRepository.searchCreators(searchQuery, session.userId, session)
                        } catch (e: Exception) {
                            searchError = e.message ?: "Creator search नहीं हो सकी।"
                        } finally {
                            searchBusy = false
                        }
                    }
                }
            ) {
                if (searchBusy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text("Search creators")
            }
            if (searchError.isNotBlank()) {
                Text(searchError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            searchResults.forEach { creator ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(creator.displayName, fontWeight = FontWeight.SemiBold)
                        if (creator.username.isNotBlank()) {
                            Text("@${creator.username}", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    OutlinedButton(
                        enabled = !searchBusy,
                        onClick = {
                            searchBusy = true
                            searchError = ""
                            scope.launch {
                                try {
                                    NativeLiveRepository.createInvite(roomId, creator.userId, session)
                                    searchError = ""
                                    notice = "LIVE invitation ${creator.displayName} को भेज दी गई।"
                                } catch (e: Exception) {
                                    searchError = e.message ?: "LIVE invitation नहीं भेजी गई।"
                                } finally {
                                    searchBusy = false
                                }
                            }
                        }
                    ) { Text("Invite") }
                }
            }
            if (occupiedSeats.size >= (seatCapacity - 1).coerceAtLeast(0) && seatCapacity > 1) {
                Text("सभी guest seats भरी हुई हैं। नया guest admit करने से पहले एक seat खाली करें।",
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun LinearProgressIndicatorForGuestSeats() {
    androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth())
}


@Composable
internal fun NativeLiveSeatRequestPanel(
    session: NativeSession,
    roomId: String
) {
    var participant by remember(roomId, session.userId) { mutableStateOf<JSONObject?>(null) }
    var loading by remember(roomId, session.userId) { mutableStateOf(true) }
    var busy by remember(roomId, session.userId) { mutableStateOf(false) }
    var error by remember(roomId, session.userId) { mutableStateOf("") }
    var notice by remember(roomId, session.userId) { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    var guestPublisher by remember(roomId, session.userId) {
        mutableStateOf<NativeSfuPublisher?>(null)
    }
    var requestedMicrophoneAllowed by remember(roomId, session.userId) { mutableStateOf(false) }
    val cameraPermissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val cameraGranted = permissions[android.Manifest.permission.CAMERA] == true ||
            androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        val microphoneGranted = requestedMicrophoneAllowed && (
            permissions[android.Manifest.permission.RECORD_AUDIO] == true ||
                androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        )
        if (!cameraGranted) {
            error = "Guest broadcast शुरू करने के लिए Camera permission आवश्यक है।"
        } else {
            busy = true
            scope.launch {
                try {
                    guestPublisher = NativeSfuPublisher.start(context, roomId, session, microphoneGranted)
                    notice = if (microphoneGranted) "आपका camera और microphone LIVE में publish हो रहा है।"
                        else "आपका camera LIVE में publish हो रहा है।"
                    error = ""
                } catch (e: Exception) {
                    error = e.message ?: "Guest camera broadcast शुरू नहीं हुआ।"
                } finally {
                    busy = false
                }
            }
        }
    }

    suspend fun refreshSeat() {
        try {
            participant = NativeLiveRepository.listParticipants(roomId, session)
                .firstOrNull { it.optString("user_id") == session.userId }
            error = ""
        } catch (e: Exception) {
            error = e.message ?: "आपकी LIVE seat स्थिति लोड नहीं हुई।"
        } finally {
            loading = false
        }
    }

    LaunchedEffect(roomId, session.userId) {
        refreshSeat()
        while (true) {
            delay(5_000)
            refreshSeat()
        }
    }

    val status = participant?.optString("status").orEmpty()
    val admitted = status == "admitted"
    val requested = status == "requested"
    val cameraAllowed = participant?.optBoolean("camera_allowed") == true
    val microphoneAllowed = participant?.optBoolean("microphone_allowed") == true

    LaunchedEffect(status, guestPublisher) {
        if (status != "admitted" && guestPublisher != null) {
            runCatching { guestPublisher?.stop() }
            guestPublisher = null
        }
    }

    androidx.compose.runtime.DisposableEffect(roomId, session.userId) {
        onDispose {
            val activePublisher = guestPublisher
            if (activePublisher != null) {
                kotlinx.coroutines.CoroutineScope(
                    kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO
                ).launch {
                    runCatching { activePublisher.stop() }
                }
            }
        }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Join this LIVE as a guest", style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold)
            when {
                loading -> {
                    androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("आपकी seat स्थिति जाँची जा रही है।", style = MaterialTheme.typography.bodySmall)
                }
                admitted -> {
                    Text(
                        "Host ने आपको guest seat ${participant?.optInt("seat_number", 0) ?: 0} पर admit कर दिया है।",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        "Camera permission: ${if (cameraAllowed) "Allowed" else "Waiting for host"} · Microphone permission: ${if (microphoneAllowed) "Allowed" else "Blocked"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    guestPublisher?.let { activePublisher ->
                        NativeLiveCameraPreview(
                            publisher = activePublisher,
                            modifier = Modifier.fillMaxWidth().size(1.dp)
                        )
                        OutlinedButton(
                            enabled = !busy,
                            onClick = {
                                busy = true
                                scope.launch {
                                    try {
                                        activePublisher.stop()
                                        guestPublisher = null
                                        notice = "Guest broadcast बंद कर दिया गया।"
                                    } catch (e: Exception) {
                                        error = e.message ?: "Guest broadcast बंद नहीं हुआ।"
                                    } finally {
                                        busy = false
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Stop guest broadcast") }
                    }
                    if (guestPublisher == null) {
                        Button(
                            enabled = !busy && cameraAllowed,
                            onClick = {
                                requestedMicrophoneAllowed = microphoneAllowed
                                val permissions = if (microphoneAllowed) {
                                    arrayOf(android.Manifest.permission.CAMERA, android.Manifest.permission.RECORD_AUDIO)
                                } else {
                                    arrayOf(android.Manifest.permission.CAMERA)
                                }
                                cameraPermissionLauncher.launch(permissions)
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            else Text(if (cameraAllowed) "Start guest camera" else "Waiting for host camera permission")
                        }
                    }
                    Text(
                        "Host की अनुमति के बिना camera या microphone broadcast नहीं होगा।",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedButton(
                        enabled = !busy,
                        onClick = {
                            busy = true
                            notice = ""
                            scope.launch {
                                try {
                                    guestPublisher?.stop()
                                    guestPublisher = null
                                    NativeLiveRepository.leaveSeat(roomId, session)
                                    notice = "आप LIVE guest seat से बाहर आ गए।"
                                    refreshSeat()
                                } catch (e: Exception) {
                                    error = e.message ?: "Guest seat छोड़ी नहीं जा सकी।"
                                } finally {
                                    busy = false
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        else Text("Leave guest seat")
                    }
                }
                requested -> {
                    Text("आपकी request host को भेज दी गई है। Host के admit करने तक प्रतीक्षा करें।",
                        style = MaterialTheme.typography.bodyMedium)
                    Text("यह स्थिति अपने-आप refresh होती रहेगी।", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                else -> {
                    Text("Host से guest seat माँगें। Host के approve करने के बाद आप LIVE guest बनेंगे।",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(
                        enabled = !busy,
                        onClick = {
                            busy = true
                            error = ""
                            notice = ""
                            scope.launch {
                                try {
                                    NativeLiveRepository.requestSeat(roomId, session)
                                    notice = "Guest seat request host को भेज दी गई।"
                                    refreshSeat()
                                } catch (e: Exception) {
                                    error = e.message ?: "Guest seat request नहीं भेजी जा सकी।"
                                } finally {
                                    busy = false
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        else Text("Request guest seat")
                    }
                }
            }
            if (error.isNotBlank()) {
                Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            if (notice.isNotBlank()) {
                Text(notice, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
