package com.newstar.live

import android.Manifest
import android.app.Application
import android.media.MediaRecorder
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

internal data class NativeAudioMixState(
    val busy: Boolean = false,
    val progress: Int = 0,
    val message: String = "",
    val error: String? = null
)

internal class NativeProfileAudioMixerRepository(private val application: Application) {
    suspend fun replaceAudio(
        session: NativeSession,
        video: JSONObject,
        audioUri: Uri?,
        voiceFile: File?,
        progress: (Int) -> Unit
    ): JSONObject = withContext(Dispatchers.IO) {
        require(audioUri != null || (voiceFile?.isFile == true && voiceFile.length() > 0L)) {
            "पहले Gallery audio चुनें या voice record करें।"
        }
        val videoId = video.optString("id")
        require(videoId.isNotBlank()) { "Video ID उपलब्ध नहीं है।" }
        val videoUrl = video.optString("video_url")
        val sourceUrl = if (videoUrl.startsWith("storage://videos/")) {
            val originalPath = NativeProfileVideoPolicy.storagePath(videoUrl, session.userId)
                ?: error("Original video का storage path owner से match नहीं करता।")
            NativeSupabase.createSignedObjectUrl("videos", originalPath, session)
        } else {
            require(videoUrl.startsWith("https://") || videoUrl.startsWith("http://")) {
                "Original video का सुरक्षित playback URL उपलब्ध नहीं है। Profile refresh करके दोबारा कोशिश करें।"
            }
            videoUrl
        }
        val rendered = NativeVideoEditor.remix(
            application,
            Uri.parse(sourceUrl),
            audioUri,
            voiceFile,
            progress
        )
        var uploadedPath: String? = null
        try {
            require(rendered.isFile && rendered.length() > 10_000L) { "Audio mix export खाली या अधूरा है।" }
            val path = "${session.userId}/${videoId}-audio-${UUID.randomUUID()}.mp4"
            uploadedPath = path
            val storageRef = NativeSupabase.uploadVideoFile(path, rendered, session)
            val encodedId = Uri.encode(videoId)
            val encodedUser = Uri.encode(session.userId)
            val response = NativeSupabase.patch(
                "/rest/v1/videos?id=eq.$encodedId&user_id=eq.$encodedUser&select=id,video_url",
                JSONObject().put("video_url", storageRef),
                session
            )
            val rows = JSONArray(response)
            require(rows.length() == 1 && rows.optJSONObject(0)?.optString("id") == videoId) {
                "Database ने नया वीडियो स्वीकार नहीं किया। Original वीडियो सुरक्षित रखा गया है।"
            }
            val oldPath = NativeProfileVideoPolicy.storagePath(videoUrl, session.userId)
            if (oldPath != null && oldPath != path) {
                try { NativeSupabase.removeVideoObject(oldPath, session) } catch (_: Exception) { }
            }
            JSONObject(video.toString()).put("video_url", storageRef)
        } catch (error: Exception) {
            if (uploadedPath != null) {
                try { NativeSupabase.removeVideoObject(uploadedPath!!, session) } catch (_: Exception) { }
            }
            throw error
        } finally {
            rendered.delete()
        }
    }
}

internal class NativeProfileAudioMixerViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = NativeProfileAudioMixerRepository(application)
    var state by mutableStateOf(NativeAudioMixState())
        private set

    fun mix(
        session: NativeSession,
        video: JSONObject,
        audioUri: Uri?,
        voiceFile: File?,
        onSaved: (JSONObject) -> Unit
    ) {
        if (state.busy) return
        state = NativeAudioMixState(busy = true, message = "Video export शुरू हो रहा है…")
        viewModelScope.launch {
            try {
                val updated = repository.replaceAudio(session, video, audioUri, voiceFile) { value ->
                    state = state.copy(progress = value, message = "Audio mix और export: $value%")
                }
                state = NativeAudioMixState(message = "Audio mix सेव हो गया।")
                onSaved(updated)
            } catch (error: Exception) {
                state = NativeAudioMixState(error = error.message ?: "Audio mix पूरा नहीं हुआ। Original video सुरक्षित है।")
            }
        }
    }
}

@Composable
internal fun NativeProfileVideoAudioMixerDialog(
    session: NativeSession,
    video: JSONObject,
    onDismiss: () -> Unit,
    onSaved: (JSONObject) -> Unit
) {
    val context = LocalContext.current
    val factory = remember(context.applicationContext) {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return NativeProfileAudioMixerViewModel(context.applicationContext as Application) as T
            }
        }
    }
    val vm: NativeProfileAudioMixerViewModel = viewModel(
        key = "profile-audio-mixer-${session.userId}-${video.optString("id")}",
        factory = factory
    )
    val state = vm.state
    var audioUri by remember { mutableStateOf<Uri?>(null) }
    var audioName by remember { mutableStateOf("") }
    var voiceFile by remember { mutableStateOf<File?>(null) }
    var recorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var recording by remember { mutableStateOf(false) }
    var permissionError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) { }
            audioUri = uri
            audioName = uri.lastPathSegment?.substringAfterLast('/') ?: "Selected audio"
            voiceFile?.delete()
            voiceFile = null
        }
    }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            try {
                val output = File(context.cacheDir, "profile-voice-${System.currentTimeMillis()}.m4a")
                val mediaRecorder = if (android.os.Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else MediaRecorder()
                mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC)
                mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                mediaRecorder.setAudioSamplingRate(44100)
                mediaRecorder.setAudioEncodingBitRate(128000)
                mediaRecorder.setOutputFile(output.absolutePath)
                mediaRecorder.prepare()
                mediaRecorder.start()
                recorder = mediaRecorder
                voiceFile = output
                audioUri = null
                audioName = ""
                recording = true
                permissionError = null
            } catch (error: Exception) {
                permissionError = error.message ?: "Voice recording शुरू नहीं हो सकी।"
                try { recorder?.release() } catch (_: Exception) { }
                recorder = null
            }
        } else permissionError = "Voice recording के लिए microphone permission चाहिए।"
    }
    DisposableEffect(Unit) {
        onDispose {
            try {
                if (recording) recorder?.stop()
            } catch (_: Exception) { }
            try { recorder?.release() } catch (_: Exception) { }
            recorder = null
        }
    }
    AlertDialog(
        onDismissRequest = { if (!state.busy && !recording) onDismiss() },
        title = { Text("Voice / Music Mix") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Gallery से audio चुनें या अपनी voice रिकॉर्ड करें। नया MP4 पहले upload होगा और database update सफल होने के बाद ही पुराना video हटेगा।")
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.busy && !recording,
                    onClick = { audioPicker.launch(arrayOf("audio/*")) }
                ) { Text("Choose audio from Gallery") }
                if (audioName.isNotBlank()) Text("Selected: $audioName", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        enabled = !state.busy && !recording,
                        onClick = {
                            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                                android.content.pm.PackageManager.PERMISSION_GRANTED) {
                                micPermission.launch(Manifest.permission.RECORD_AUDIO)
                            } else micPermission.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    ) { Text("🎙 Record voice") }
                    OutlinedButton(
                        enabled = recording,
                        onClick = {
                            try {
                                recorder?.stop()
                                voiceFile?.takeIf { it.isFile && it.length() > 0L }?.let {
                                    audioName = "Recorded voice"
                                    audioUri = null
                                }
                            } catch (error: Exception) {
                                voiceFile?.delete()
                                voiceFile = null
                                permissionError = error.message ?: "Recording save नहीं हो सकी।"
                            } finally {
                                try { recorder?.release() } catch (_: Exception) { }
                                recorder = null
                                recording = false
                            }
                        }
                    ) { Text("⏹ Stop") }
                }
                if (recording) Text("🔴 Recording…", color = MaterialTheme.colorScheme.error)
                if (permissionError != null) Text(permissionError!!, color = MaterialTheme.colorScheme.error)
                if (state.busy) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text(state.message)
                    }
                } else if (state.error != null) {
                    Text(state.error!!, color = MaterialTheme.colorScheme.error)
                } else if (state.message.isNotBlank()) {
                    Text(state.message, color = MaterialTheme.colorScheme.primary)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !state.busy && !recording && (audioUri != null || (voiceFile?.isFile == true && voiceFile!!.length() > 0L)),
                onClick = {
                    vm.mix(session, video, audioUri, voiceFile) { updated ->
                        voiceFile?.delete()
                        onSaved(updated)
                    }
                }
            ) { Text(if (state.busy) "Mixing…" else "Replace Music & Save") }
        },
        dismissButton = {
            TextButton(enabled = !state.busy && !recording, onClick = {
                voiceFile?.delete()
                onDismiss()
            }) { Text("Cancel") }
        }
    )
}
