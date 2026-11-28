package com.newstar.live

import android.content.Context
import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
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
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class NativeSfuPublisher private constructor(
    private val roomId: String,
    private val session: NativeSession,
    private val sfuSessionId: String,
    private val factory: PeerConnectionFactory,
    private val egl: EglBase,
    private val capturer: CameraVideoCapturer,
    private val texture: SurfaceTextureHelper,
    private val videoSource: VideoSource,
    private val videoTrack: VideoTrack,
    private val audioSource: AudioSource?,
    private val audioTrack: AudioTrack?,
    private val peer: PeerConnection,
    private val trackMids: JSONArray,
    private val onConnectionState: (String) -> Unit
) : NativeLivePublisher {
    private val stopped = AtomicBoolean(false)
    val hasMicrophoneTrack: Boolean get() = audioTrack != null
    @Volatile private var renderer: SurfaceViewRenderer? = null

    override fun setMicrophoneEnabled(enabled: Boolean) { audioTrack?.setEnabled(enabled) }
    override fun setCameraEnabled(enabled: Boolean) { videoTrack.setEnabled(enabled) }

    override fun attachPreview(renderer: SurfaceViewRenderer) {
        check(!stopped.get()) { "LIVE publisher बंद हो चुका है।" }
        if (this.renderer === renderer) return
        this.renderer?.let { videoTrack.removeSink(it) }
        renderer.init(egl.eglBaseContext, null)
        renderer.setEnableHardwareScaler(true)
        renderer.setMirror(true)
        videoTrack.addSink(renderer)
        this.renderer = renderer
    }

    override fun detachPreview(renderer: SurfaceViewRenderer) {
        if (this.renderer === renderer) {
            runCatching { videoTrack.removeSink(renderer) }
            this.renderer = null
        }
    }

    override suspend fun stop() {
        if (!stopped.compareAndSet(false, true)) return
        renderer?.let { runCatching { videoTrack.removeSink(it) } }
        renderer = null
        runCatching {
            NativeLiveRepository.closeSfuSession(roomId, sfuSessionId, trackMids, session)
        }.onFailure {
            runCatching { NativeLiveRepository.abandonSfuSession(roomId, sfuSessionId, session) }
        }
        runCatching { capturer.stopCapture() }
        runCatching { peer.close(); peer.dispose() }
        runCatching { videoTrack.dispose(); audioTrack?.dispose() }
        runCatching { videoSource.dispose(); audioSource?.dispose() }
        runCatching { capturer.dispose(); texture.dispose() }
        runCatching { factory.dispose(); egl.release() }
    }

    companion object {
        @Volatile private var initialized = false

        private fun initialize(context: Context) {
            if (initialized) return
            synchronized(this) {
                if (!initialized) {
                    PeerConnectionFactory.initialize(
                        PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                            .createInitializationOptions()
                    )
                    initialized = true
                }
            }
        }

        private suspend fun createOffer(peer: PeerConnection): SessionDescription =
            suspendCancellableCoroutine { continuation ->
                peer.createOffer(object : SdpObserver {
                    override fun onCreateSuccess(value: SessionDescription) {
                        if (continuation.isActive) continuation.resume(value)
                    }
                    override fun onSetSuccess() {}
                    override fun onCreateFailure(error: String) {
                        if (continuation.isActive) continuation.resumeWithException(IllegalStateException(error))
                    }
                    override fun onSetFailure(error: String) {}
                }, MediaConstraints())
            }

        private suspend fun setDescription(peer: PeerConnection, value: SessionDescription, local: Boolean) {
            suspendCancellableCoroutine<Unit> { continuation ->
                val observer = object : SdpObserver {
                    override fun onCreateSuccess(value: SessionDescription) {}
                    override fun onSetSuccess() {
                        if (continuation.isActive) continuation.resume(Unit)
                    }
                    override fun onCreateFailure(error: String) {}
                    override fun onSetFailure(error: String) {
                        if (continuation.isActive) continuation.resumeWithException(IllegalStateException(error))
                    }
                }
                if (local) peer.setLocalDescription(observer, value) else peer.setRemoteDescription(observer, value)
            }
        }

        suspend fun start(
            context: Context,
            roomId: String,
            session: NativeSession,
            microphoneGranted: Boolean,
            onConnectionState: (String) -> Unit = {}
        ): NativeSfuPublisher {
            require(roomId.isNotBlank()) { "LIVE room ID आवश्यक है।" }
            check(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                "Camera permission आवश्यक है।"
            }
            val sfuSessionId = NativeLiveRepository.createSfuSession(roomId, "publisher", session)
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
            val texture = SurfaceTextureHelper.create("NewStarSfuCamera", egl.eglBaseContext)
                ?: throw IllegalStateException("Camera texture initialize नहीं हुआ।")
            val videoSource = factory.createVideoSource(false)
            val videoTrack = factory.createVideoTrack("newstar-sfu-camera", videoSource)
            var audioSource: AudioSource? = null
            var audioTrack: AudioTrack? = null
            var peer: PeerConnection? = null
            try {
                capturer.initialize(texture, context.applicationContext, videoSource.capturerObserver)
                capturer.startCapture(1280, 720, 24)
                if (microphoneGranted && ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    audioSource = factory.createAudioSource(MediaConstraints())
                    audioTrack = factory.createAudioTrack("newstar-sfu-microphone", audioSource)
                }
                val gathered = CompletableDeferred<Unit>()
                val connected = CompletableDeferred<Unit>()
                val config = PeerConnection.RTCConfiguration(
                    listOf(PeerConnection.IceServer.builder("stun:stun.cloudflare.com:3478").createIceServer())
                ).apply { sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN }
                peer = factory.createPeerConnection(config, object : PeerConnection.Observer {
                    override fun onSignalingChange(state: PeerConnection.SignalingState) {}
                    override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                        onConnectionState(state.name)
                        when (state) {
                            PeerConnection.IceConnectionState.CONNECTED,
                            PeerConnection.IceConnectionState.COMPLETED -> if (!connected.isCompleted) connected.complete(Unit)
                            PeerConnection.IceConnectionState.FAILED -> if (!connected.isCompleted) {
                                connected.completeExceptionally(IllegalStateException("Cloudflare SFU ICE connection failed."))
                            }
                            else -> Unit
                        }
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
                    override fun onConnectionChange(state: PeerConnection.PeerConnectionState) {
                        onConnectionState(state.name)
                    }
                    override fun onSelectedCandidatePairChanged(event: org.webrtc.CandidatePairChangeEvent) {}
                }) ?: throw IllegalStateException("SFU PeerConnection create नहीं हुआ।")
                val videoTransceiver = peer.addTransceiver(
                    videoTrack, RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_ONLY)
                ) ?: throw IllegalStateException("Camera transceiver create नहीं हुआ।")
                val audioTransceiver = audioTrack?.let {
                    peer.addTransceiver(it, RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_ONLY))
                }
                setDescription(peer, createOffer(peer), true)
                withTimeout(25000) { gathered.await() }
                val local = peer.localDescription ?: throw IllegalStateException("Local SDP offer नहीं मिला।")
                val tracks = JSONArray().put(JSONObject()
                    .put("location", "local").put("trackName", "camera")
                    .put("mid", videoTransceiver.mid ?: throw IllegalStateException("Camera MID नहीं मिला।")))
                audioTransceiver?.let {
                    tracks.put(JSONObject().put("location", "local").put("trackName", "microphone")
                        .put("mid", it.mid ?: throw IllegalStateException("Microphone MID नहीं मिला।")))
                }
                val response = NativeLiveRepository.publishSfuTracks(roomId, sfuSessionId, local.description, tracks, session)
                val answer = response.optJSONObject("sessionDescription")
                    ?: throw IllegalStateException("Cloudflare SFU ने SDP answer नहीं लौटाया।")
                val answerSdp = answer.optString("sdp")
                if (answer.optString("type") != "answer" || answerSdp.isBlank()) {
                    throw IllegalStateException("Cloudflare SFU SDP answer अमान्य है।")
                }
                setDescription(peer, SessionDescription(SessionDescription.Type.ANSWER, answerSdp), false)
                withTimeout(25000) { connected.await() }
                return NativeSfuPublisher(
                    roomId, session, sfuSessionId, factory, egl, capturer, texture,
                    videoSource, videoTrack, audioSource, audioTrack, peer, JSONArray().apply {
                        for (i in 0 until tracks.length()) put(JSONObject().put("mid", tracks.getJSONObject(i).getString("mid")))
                    }, onConnectionState
                )
            } catch (error: Exception) {
                runCatching { capturer.stopCapture() }
                runCatching { peer?.close(); peer?.dispose() }
                runCatching { videoTrack.dispose(); audioTrack?.dispose() }
                runCatching { videoSource.dispose(); audioSource?.dispose() }
                runCatching { capturer.dispose(); texture.dispose() }
                runCatching { factory.dispose(); egl.release() }
                runCatching { NativeLiveRepository.abandonSfuSession(roomId, sfuSessionId, session) }
                Log.e("NewStarSFU", "Publisher setup failed", error)
                throw error
            }
        }
    }
}
