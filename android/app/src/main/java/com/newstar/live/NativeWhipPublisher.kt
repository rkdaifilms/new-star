package com.newstar.live

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.EglBase
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class NativeWhipPublisher private constructor(
    private val factory: PeerConnectionFactory,
    private val egl: EglBase,
    private val capturer: CameraVideoCapturer,
    private val textureHelper: SurfaceTextureHelper,
    private val videoSource: VideoSource,
    private val videoTrack: VideoTrack,
    private val audioSource: AudioSource?,
    private val audioTrack: AudioTrack?,
    private val peer: PeerConnection,
    private val resourceUrl: String
) {
    fun setMicrophoneEnabled(enabled: Boolean) { audioTrack?.setEnabled(enabled) }
    fun setCameraEnabled(enabled: Boolean) { videoTrack.setEnabled(enabled) }

    suspend fun stop() {
        withContext(Dispatchers.IO) {
            runCatching {
                val c = URL(resourceUrl).openConnection() as HttpURLConnection
                c.requestMethod = "DELETE"
                c.connectTimeout = 8000
                c.readTimeout = 8000
                c.responseCode
                c.disconnect()
            }
        }
        runCatching { capturer.stopCapture() }
        runCatching { peer.close(); peer.dispose() }
        runCatching { videoTrack.dispose(); audioTrack?.dispose() }
        runCatching { videoSource.dispose(); audioSource?.dispose() }
        runCatching { capturer.dispose(); textureHelper.dispose() }
        runCatching { factory.dispose(); egl.release() }
    }

    companion object {
        @Volatile private var initialized = false

        private fun initialize(context: Context) {
            if (initialized) return
            synchronized(this) {
                if (!initialized) {
                    PeerConnectionFactory.initialize(
                        PeerConnectionFactory.InitializationOptions.builder(context.applicationContext).createInitializationOptions()
                    )
                    initialized = true
                }
            }
        }

        private suspend fun offer(peer: PeerConnection): SessionDescription =
            suspendCancellableCoroutine { c ->
                peer.createOffer(object : SdpObserver {
                    override fun onCreateSuccess(value: SessionDescription) { if (c.isActive) c.resume(value) }
                    override fun onSetSuccess() {}
                    override fun onCreateFailure(error: String) { if (c.isActive) c.resumeWithException(IllegalStateException(error)) }
                    override fun onSetFailure(error: String) {}
                }, MediaConstraints())
            }

        private suspend fun setDescription(peer: PeerConnection, value: SessionDescription, local: Boolean) =
            suspendCancellableCoroutine<Unit> { c ->
                val observer = object : SdpObserver {
                    override fun onCreateSuccess(value: SessionDescription) {}
                    override fun onSetSuccess() { if (c.isActive) c.resume(Unit) }
                    override fun onCreateFailure(error: String) {}
                    override fun onSetFailure(error: String) { if (c.isActive) c.resumeWithException(IllegalStateException(error)) }
                }
                if (local) peer.setLocalDescription(observer, value) else peer.setRemoteDescription(observer, value)
            }

        suspend fun start(context: Context, whipUrl: String, microphoneGranted: Boolean): NativeWhipPublisher {
            require(whipUrl.startsWith("https://")) { "Cloudflare WHIP URL अमान्य है।" }
            check(androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED) { "Camera permission आवश्यक है।" }
            initialize(context)
            val egl = EglBase.create()
            val factory = PeerConnectionFactory.builder()
                .setVideoEncoderFactory(org.webrtc.DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
                .setVideoDecoderFactory(org.webrtc.DefaultVideoDecoderFactory(egl.eglBaseContext))
                .createPeerConnectionFactory()
            val enumerator = Camera2Enumerator(context)
            val cameraName = enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) }
                ?: enumerator.deviceNames.firstOrNull()
                ?: throw IllegalStateException("इस Android device पर camera उपलब्ध नहीं है।")
            val capturer = enumerator.createCapturer(cameraName, null)
                ?: throw IllegalStateException("Camera capture शुरू नहीं हो पाया।")
            val texture = SurfaceTextureHelper.create("NewStarLiveCamera", egl.eglBaseContext)
                ?: throw IllegalStateException("Camera texture initialize नहीं हुआ।")
            val videoSource = factory.createVideoSource(false)
            val videoTrack = factory.createVideoTrack("newstar-live-video", videoSource)
            var audioSource: AudioSource? = null
            var audioTrack: AudioTrack? = null
            var peer: PeerConnection? = null
            try {
                capturer.initialize(texture, context.applicationContext, videoSource.capturerObserver)
                capturer.startCapture(1280, 720, 24)
                if (microphoneGranted && androidx.core.content.ContextCompat.checkSelfPermission(
                    context, android.Manifest.permission.RECORD_AUDIO
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    audioSource = factory.createAudioSource(MediaConstraints())
                    audioTrack = factory.createAudioTrack("newstar-live-audio", audioSource)
                }
                val gathered = CompletableDeferred<Unit>()
                val config = PeerConnection.RTCConfiguration(emptyList()).apply {
                    sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                }
                peer = factory.createPeerConnection(config, object : PeerConnection.Observer {
                    override fun onSignalingChange(state: PeerConnection.SignalingState) {}
                    override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                        if (state == PeerConnection.IceConnectionState.FAILED) Log.e("NewStarLIVE", "ICE failed")
                    }
                    override fun onIceConnectionReceivingChange(receiving: Boolean) {}
                    override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
                        if (state == PeerConnection.IceGatheringState.COMPLETE && !gathered.isCompleted) gathered.complete(Unit)
                    }
                    override fun onIceCandidate(candidate: org.webrtc.IceCandidate) {}
                    override fun onIceCandidatesRemoved(candidates: Array<out org.webrtc.IceCandidate>) {}
                    override fun onAddStream(stream: org.webrtc.MediaStream) {}
                    override fun onRemoveStream(stream: org.webrtc.MediaStream) {}
                    override fun onDataChannel(channel: org.webrtc.DataChannel) {}
                    override fun onRenegotiationNeeded() {}
                    override fun onAddTrack(receiver: org.webrtc.RtpReceiver, streams: Array<out org.webrtc.MediaStream>) {}
                    override fun onConnectionChange(state: PeerConnection.PeerConnectionState) {}
                    override fun onSelectedCandidatePairChanged(event: org.webrtc.CandidatePairChangeEvent) {}
                }) ?: throw IllegalStateException("WebRTC peer connection create नहीं हुआ।")
                peer.addTransceiver(videoTrack, RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_ONLY))
                audioTrack?.let { peer.addTransceiver(it, RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_ONLY)) }
                setDescription(peer, offer(peer), true)
                withTimeout(25000) { gathered.await() }
                val sdp = peer.localDescription?.description ?: throw IllegalStateException("WebRTC offer तैयार नहीं हुआ।")
                val response = withContext(Dispatchers.IO) {
                    val c = URL(whipUrl).openConnection() as HttpURLConnection
                    c.requestMethod = "POST"
                    c.connectTimeout = 20000
                    c.readTimeout = 30000
                    c.doOutput = true
                    c.setRequestProperty("Content-Type", "application/sdp")
                    c.setRequestProperty("Accept", "application/sdp")
                    c.outputStream.use { it.write(sdp.toByteArray(Charsets.UTF_8)) }
                    val code = c.responseCode
                    val answer = (if (code in 200..299) c.inputStream else c.errorStream)
                        ?.bufferedReader()?.use { it.readText() }.orEmpty()
                    val location = c.getHeaderField("Location")
                    c.disconnect()
                    if (code !in 200..299) throw IllegalStateException("Cloudflare broadcast handshake failed (" + code + "). " + answer.take(240))
                    if (answer.isBlank() || location.isNullOrBlank()) throw IllegalStateException("Cloudflare ने अधूरा WHIP session लौटाया।")
                    URL(URL(whipUrl), location).toString() to answer
                }
                setDescription(peer, SessionDescription(SessionDescription.Type.ANSWER, response.second), false)
                return NativeWhipPublisher(factory, egl, capturer, texture, videoSource, videoTrack, audioSource, audioTrack, peer, response.first)
            } catch (e: Exception) {
                runCatching { capturer.stopCapture() }
                runCatching { peer?.close(); peer?.dispose() }
                runCatching { videoTrack.dispose(); audioTrack?.dispose() }
                runCatching { videoSource.dispose(); audioSource?.dispose() }
                runCatching { capturer.dispose(); texture.dispose() }
                runCatching { factory.dispose(); egl.release() }
                throw e
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
    var publisher by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<NativeWhipPublisher?>(null) }
    var micEnabled by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(true) }
    var cameraEnabled by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(true) }
    val currentPublisher by androidx.compose.runtime.rememberUpdatedState(publisher)
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
                try {
                    val prepared = NativeSupabase.invokeFunction("cloudflare-live", org.json.JSONObject()
                        .put("action", "prepare").put("room_title", title.trim())
                        .put("room_type", "live").put("seat_capacity", seats), session)
                    if (!prepared.optBoolean("ok")) throw IllegalStateException(prepared.optString("error", "LIVE room तैयार नहीं हुआ।"))
                    preparedRoom = prepared.getString("room_id")
                    val livePublisher = NativeWhipPublisher.start(context, prepared.getString("whip_url"), microphoneGranted)
                    val confirmed = NativeSupabase.invokeFunction("cloudflare-live",
                        org.json.JSONObject().put("action", "confirm").put("room_id", preparedRoom), session)
                    if (!confirmed.optBoolean("ok")) throw IllegalStateException(confirmed.optString("error", "LIVE room confirm नहीं हुआ।"))
                    roomId = preparedRoom
                    publisher = livePublisher
                    micEnabled = microphoneGranted
                    cameraEnabled = true
                    notice = if (microphoneGranted) "आप Cloudflare पर LIVE हैं। Camera और microphone broadcast हो रहे हैं।"
                        else "आप LIVE हैं। Microphone permission नहीं मिली, इसलिए केवल camera broadcast हो रहा है।"
                } catch (e: Exception) {
                    preparedRoom?.let { id ->
                        runCatching { NativeSupabase.invokeFunction("cloudflare-live",
                            org.json.JSONObject().put("action", "stop").put("room_id", id), session) }
                    }
                    notice = e.message ?: "Native LIVE शुरू नहीं हुआ।"
                } finally { busy = false }
            }
        }
    }

    androidx.compose.runtime.DisposableEffect(session.userId) {
        onDispose {
            val activePublisher = currentPublisher
            val activeRoom = currentRoomId
            if (activePublisher != null || activeRoom != null) {
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO).launch {
                    runCatching { activePublisher?.stop() }
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
                androidx.compose.material3.Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
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
                androidx.compose.material3.Text("● LIVE · Room " + roomId!!.take(8),
                    color = androidx.compose.material3.MaterialTheme.colorScheme.primary,
                    style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
                androidx.compose.material3.Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                    androidx.compose.material3.OutlinedButton(onClick = {
                        micEnabled = !micEnabled
                        publisher?.setMicrophoneEnabled(micEnabled)
                    }) { androidx.compose.material3.Text(if (micEnabled) "Mic ON" else "Mic OFF") }
                    androidx.compose.material3.OutlinedButton(onClick = {
                        cameraEnabled = !cameraEnabled
                        publisher?.setCameraEnabled(cameraEnabled)
                    }) { androidx.compose.material3.Text(if (cameraEnabled) "Camera ON" else "Camera OFF") }
                }
                androidx.compose.material3.Button(
                    enabled = !busy, onClick = {
                        scope.launch {
                            busy = true
                            try {
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
