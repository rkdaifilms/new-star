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
import org.webrtc.SurfaceViewRenderer
import java.util.concurrent.atomic.AtomicBoolean
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal interface NativeLivePublisher {
    fun setMicrophoneEnabled(enabled: Boolean)
    fun setCameraEnabled(enabled: Boolean)
    fun attachPreview(renderer: org.webrtc.SurfaceViewRenderer)
    fun detachPreview(renderer: org.webrtc.SurfaceViewRenderer)
    suspend fun stop()
}

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
) : NativeLivePublisher {
    private val stopped = AtomicBoolean(false)
    @Volatile private var previewRenderer: SurfaceViewRenderer? = null

    override fun setMicrophoneEnabled(enabled: Boolean) { audioTrack?.setEnabled(enabled) }
    override fun setCameraEnabled(enabled: Boolean) { videoTrack.setEnabled(enabled) }

    override fun attachPreview(renderer: SurfaceViewRenderer) {
        check(!stopped.get()) { "LIVE publisher बंद हो चुका है।" }
        if (previewRenderer === renderer) return
        previewRenderer?.let { videoTrack.removeSink(it) }
        renderer.init(egl.eglBaseContext, null)
        renderer.setEnableHardwareScaler(true)
        renderer.setMirror(true)
        videoTrack.addSink(renderer)
        previewRenderer = renderer
    }

    override fun detachPreview(renderer: SurfaceViewRenderer) {
        if (previewRenderer === renderer) {
            runCatching { videoTrack.removeSink(renderer) }
            previewRenderer = null
        }
    }

    override suspend fun stop() {
        if (!stopped.compareAndSet(false, true)) return
        previewRenderer?.let { renderer ->
            runCatching { videoTrack.removeSink(renderer) }
            previewRenderer = null
        }
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
                val connected = CompletableDeferred<Unit>()
                val iceServers = listOf(
                    PeerConnection.IceServer.builder("stun:stun.cloudflare.com:3478").createIceServer()
                )
                val config = PeerConnection.RTCConfiguration(iceServers).apply {
                    sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                }
                peer = factory.createPeerConnection(config, object : PeerConnection.Observer {
                    override fun onSignalingChange(state: PeerConnection.SignalingState) {}
                    override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                        when (state) {
                            PeerConnection.IceConnectionState.CONNECTED,
                            PeerConnection.IceConnectionState.COMPLETED -> if (!connected.isCompleted) connected.complete(Unit)
                            PeerConnection.IceConnectionState.FAILED -> {
                                Log.e("NewStarLIVE", "ICE failed")
                                if (!connected.isCompleted) connected.completeExceptionally(IllegalStateException("Cloudflare LIVE media connection failed. Network or relay connectivity is unavailable."))
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
                withTimeout(20000) { connected.await() }
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
