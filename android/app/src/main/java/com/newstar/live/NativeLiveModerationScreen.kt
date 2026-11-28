package com.newstar.live

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
internal fun NativeLiveModerationScreen(roomId: String, session: NativeSession) {
    val model: NativeLiveModerationViewModel = viewModel()
    val state by model.state.collectAsState()
    var removeTarget by remember(roomId) { mutableStateOf<NativeLiveMember?>(null) }

    LaunchedEffect(roomId, session.userId, session.accessToken) {
        model.start(roomId, session)
    }
    DisposableEffect(model) {
        onDispose { model.stop() }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text("LIVE Participant Moderation", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Host और admitted moderators यहाँ guest camera, microphone और moderator role नियंत्रित कर सकते हैं।",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(onClick = model::refresh, enabled = !state.loading) { Text("Refresh") }
            }
            if (state.loading && state.members.isEmpty()) {
                CircularProgressIndicator(Modifier.size(22.dp))
            }
            if (state.error.isNotBlank()) {
                Text(state.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            if (state.notice.isNotBlank()) {
                Text(state.notice, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
            }
            if (!state.loading && state.error.isBlank() && state.members.isEmpty()) {
                Text("अभी कोई guest request या admitted participant नहीं है।", style = MaterialTheme.typography.bodySmall)
            }
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(state.members, key = { it.userId }) { member ->
                    val canManage = state.actorRole == "host" ||
                        (state.actorRole == "moderator" && member.role != "moderator")
                    Card(Modifier.fillMaxWidth()) {
                        Column(
                            Modifier.fillMaxWidth().padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                member.displayName + " · " + member.role +
                                    (member.seatNumber?.let { " · Seat $it" } ?: ""),
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                "Status: ${member.status} · Camera: ${if (member.cameraAllowed) "allowed" else "blocked"}" +
                                    " · Mic: ${if (member.microphoneAllowed) "allowed" else "blocked"}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                "Publishing — Camera: ${if (member.cameraEnabled) "ON" else "OFF"} · Mic: ${if (member.microphoneEnabled) "ON" else "OFF"}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (canManage) {
                                val busy = state.busyUserId == member.userId
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    OutlinedButton(
                                        modifier = Modifier.weight(1f),
                                        enabled = state.busyUserId == null,
                                        onClick = {
                                            model.setPermission(member.userId, "camera_allowed", !member.cameraAllowed)
                                        }
                                    ) {
                                        Text(if (member.cameraAllowed) "Block camera" else "Allow camera")
                                    }
                                    OutlinedButton(
                                        modifier = Modifier.weight(1f),
                                        enabled = state.busyUserId == null,
                                        onClick = {
                                            model.setPermission(member.userId, "microphone_allowed", !member.microphoneAllowed)
                                        }
                                    ) {
                                        Text(if (member.microphoneAllowed) "Block mic" else "Allow mic")
                                    }
                                }
                                if (state.actorRole == "host" && member.status == "admitted") {
                                    OutlinedButton(
                                        modifier = Modifier.fillMaxWidth(),
                                        enabled = state.busyUserId == null,
                                        onClick = { model.setModerator(member.userId, member.role != "moderator") }
                                    ) {
                                        Text(if (member.role == "moderator") "Remove moderator role" else "Make moderator")
                                    }
                                }
                                Button(
                                    modifier = Modifier.fillMaxWidth(),
                                    enabled = state.busyUserId == null,
                                    onClick = { removeTarget = member }
                                ) {
                                    if (busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                    else Text(if (member.status == "requested") "Reject request" else "Remove participant")
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.size(1.dp))
        }
    }

    removeTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { removeTarget = null },
            title = { Text("Remove LIVE participant?") },
            text = { Text("This will revoke the participant's room access and turn off their camera and microphone publishing permissions.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        model.removeMember(target.userId)
                        removeTarget = null
                    }
                ) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { removeTarget = null }) { Text("Cancel") } }
        )
    }
}
