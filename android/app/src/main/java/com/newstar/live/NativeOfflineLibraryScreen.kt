package com.newstar.live

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.media3.exoplayer.offline.Download

internal data class NativeOfflineLibraryState(
    val entries: List<NativeOfflineDownloadEntry> = emptyList(),
    val storageBytes: Long = 0L,
    val loading: Boolean = true,
    val removingId: String = "",
    val error: String = ""
)

internal class NativeOfflineLibraryViewModel(context: Context) : ViewModel() {
    private val appContext = context.applicationContext
    private val mutableState = MutableStateFlow(NativeOfflineLibraryState())
    val state: StateFlow<NativeOfflineLibraryState> = mutableState.asStateFlow()

    init {
        refresh()
        viewModelScope.launch {
            while (true) {
                delay(4_000)
                val hasActive = mutableState.value.entries.any {
                    it.state == Download.STATE_QUEUED ||
                        it.state == Download.STATE_DOWNLOADING ||
                        it.state == Download.STATE_RESTARTING
                }
                if (!hasActive) break
                refresh(silent = true)
            }
        }
    }

    fun refresh(silent: Boolean = false) {
        viewModelScope.launch {
            if (!silent) mutableState.update { it.copy(loading = true, error = "") }
            try {
                val result = withContext(Dispatchers.IO) {
                    NativeOfflineDownloads.savedDownloads(appContext) to
                        NativeOfflineDownloads.storageBytes(appContext)
                }
                mutableState.update {
                    it.copy(entries = result.first, storageBytes = result.second, loading = false, error = "")
                }
            } catch (e: Exception) {
                mutableState.update {
                    it.copy(loading = false, error = e.message ?: "Offline downloads लोड नहीं हुए।")
                }
            }
        }
    }

    fun remove(videoId: String) {
        if (mutableState.value.removingId.isNotBlank()) return
        viewModelScope.launch {
            mutableState.update { it.copy(removingId = videoId, error = "") }
            try {
                withContext(Dispatchers.IO) { NativeOfflineDownloads.remove(appContext, videoId) }
                mutableState.update { current ->
                    current.copy(entries = current.entries.filterNot { it.id == videoId })
                }
                delay(500)
                refresh(silent = true)
            } catch (e: Exception) {
                mutableState.update { it.copy(error = e.message ?: "Offline video हटाया नहीं जा सका।") }
            } finally {
                mutableState.update { it.copy(removingId = "") }
            }
        }
    }

    class Factory(context: Context) : ViewModelProvider.Factory {
        private val appContext = context.applicationContext

        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(NativeOfflineLibraryViewModel::class.java))
            return NativeOfflineLibraryViewModel(appContext) as T
        }
    }
}

@Composable
fun NativeOfflineLibraryScreen(onOpenVideo: (String, String) -> Unit) {
    val context = LocalContext.current
    val model: NativeOfflineLibraryViewModel = viewModel(
        factory = NativeOfflineLibraryViewModel.Factory(context)
    )
    val state by model.state.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { model.refresh() }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Offline Library", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(
                    "App में सेव किए गए वीडियो",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            OutlinedButton(onClick = { model.refresh() }, enabled = !state.loading) {
                Text("Refresh")
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Offline storage", style = MaterialTheme.typography.labelLarge)
                Text(
                    formatOfflineBytes(state.storageBytes),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "यह डेटा केवल New Star ऐप के निजी storage में रखा जाता है।",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (state.error.isNotBlank()) {
            Text(state.error, color = MaterialTheme.colorScheme.error)
            OutlinedButton(onClick = { model.refresh() }) { Text("Try again") }
        }

        if (state.loading && state.entries.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CircularProgressIndicator()
                Text("Offline videos लोड हो रहे हैं…", modifier = Modifier.padding(top = 12.dp))
            }
        } else if (state.entries.isEmpty()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("अभी कोई offline video नहीं है", fontWeight = FontWeight.SemiBold)
                    Text(
                        "Feed में किसी वीडियो का Download बटन दबाएँ। डाउनलोड पूरा होने पर वह यहाँ दिखेगा।",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        } else {
            Text("Saved downloads (${state.entries.size})", fontWeight = FontWeight.SemiBold)
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(state.entries, key = { it.id }) { entry ->
                    OfflineDownloadCard(
                        entry = entry,
                        removing = state.removingId == entry.id,
                        onPlay = { onOpenVideo(entry.id, entry.uri) },
                        onRemove = { model.remove(entry.id) }
                    )
                }
            }
        }
    }
}

@Composable
private fun OfflineDownloadCard(
    entry: NativeOfflineDownloadEntry,
    removing: Boolean,
    onPlay: () -> Unit,
    onRemove: () -> Unit
) {
    val completed = entry.state == Download.STATE_COMPLETED
    val failed = entry.state == Download.STATE_FAILED
    val status = when {
        completed -> "Saved offline"
        failed -> "Download failed"
        entry.state == Download.STATE_DOWNLOADING -> "Downloading"
        entry.state == Download.STATE_QUEUED || entry.state == Download.STATE_RESTARTING -> "Queued"
        entry.state == Download.STATE_STOPPED -> "Paused"
        else -> "Preparing"
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Video ${entry.id.take(8)}", fontWeight = FontWeight.SemiBold)
            Text(status, color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            Text(
                "${entry.mimeType.ifBlank { "Video" }} • ${formatOfflineBytes(entry.bytesDownloaded)}" +
                    if (entry.contentLength > 0L) " / ${formatOfflineBytes(entry.contentLength)}" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (!completed && !failed) {
                LinearProgressIndicator(
                    progress = { (entry.percentDownloaded.coerceIn(0f, 100f) / 100f) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(onClick = onPlay, enabled = completed, modifier = Modifier.weight(1f)) {
                    Text("Play offline")
                }
                OutlinedButton(onClick = onRemove, enabled = !removing, modifier = Modifier.weight(1f)) {
                    if (removing) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    else Text("Remove")
                }
            }
        }
    }
}

private fun formatOfflineBytes(bytes: Long): String {
    if (bytes < 1024L) return "${bytes} B"
    val kb = bytes / 1024.0
    if (kb < 1024.0) return String.format(java.util.Locale.US, "%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024.0) return String.format(java.util.Locale.US, "%.1f MB", mb)
    return String.format(java.util.Locale.US, "%.2f GB", mb / 1024.0)
}
